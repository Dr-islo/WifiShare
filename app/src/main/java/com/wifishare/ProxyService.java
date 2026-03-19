package com.wifishare;

import android.app.*;
import android.content.*;
import android.net.*;
import android.net.wifi.p2p.*;
import android.os.*;
import android.util.Log;
import androidx.core.app.NotificationCompat;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/**
 * Foreground service that:
 *  1. Creates a WiFi Direct Autonomous Group Owner (phone becomes hotspot
 *     while staying connected to the router).
 *  2. Runs an HTTP/HTTPS proxy server so connected devices can reach the internet.
 */
public class ProxyService extends Service {

    // ── Public constants ──────────────────────────────────────────
    public static final String ACTION_START      = "com.wifishare.START";
    public static final String ACTION_STOP       = "com.wifishare.STOP";
    public static final String BROADCAST_ACTION  = "com.wifishare.STATUS";
    public static final int    PROXY_PORT        = 8282;

    public static volatile boolean running   = false;
    public static volatile String  currentIp = "192.168.49.1";

    // ── Private ───────────────────────────────────────────────────
    private static final String TAG        = "WifiShare";
    private static final String CHANNEL_ID = "WifiShareCh";
    private static final int    NOTIF_ID   = 42;

    private WifiP2pManager       p2pManager;
    private WifiP2pManager.Channel p2pChannel;
    private ServerSocket         proxySocket;
    private ExecutorService      threadPool;
    private boolean              groupCreated = false;
    private String               pendingSsid  = "";
    private String               pendingPass  = "";

    // ── Lifecycle ─────────────────────────────────────────────────
    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        p2pManager = (WifiP2pManager) getSystemService(WIFI_P2P_SERVICE);
        p2pChannel  = p2pManager.initialize(this, getMainLooper(), null);
        threadPool  = Executors.newCachedThreadPool();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            pendingSsid = intent.getStringExtra("ssid");
            pendingPass = intent.getStringExtra("pass");
            startForeground(NOTIF_ID, buildNotif("Starting…", ""));
            createP2pGroup();
        } else if (ACTION_STOP.equals(action)) {
            stopEverything();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        stopEverything();
        super.onDestroy();
    }

    // ── WiFi Direct group ─────────────────────────────────────────
    private void createP2pGroup() {
        // Remove any old group first, then create fresh
        p2pManager.removeGroup(p2pChannel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { createGroupNow(); }
            @Override public void onFailure(int r)  { createGroupNow(); } // ignore; might not exist
        });
    }

    private void createGroupNow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ allows custom SSID/passphrase
            WifiP2pConfig config = new WifiP2pConfig.Builder()
                    .setNetworkName("DIRECT-" + pendingSsid)
                    .setPassphrase(pendingPass.isEmpty() ? "12345678" : pendingPass)
                    .enablePersistentMode(false)
                    .build();
            p2pManager.createGroup(p2pChannel, config, groupListener);
        } else {
            // Android 6-9: system picks SSID/pass automatically
            p2pManager.createGroup(p2pChannel, groupListener);
        }
    }

    private final WifiP2pManager.ActionListener groupListener = new WifiP2pManager.ActionListener() {
        @Override
        public void onSuccess() {
            Log.d(TAG, "P2P group created, querying info…");
            // Small delay before requesting group info
            new Handler(Looper.getMainLooper()).postDelayed(ProxyService.this::requestGroupInfo, 1500);
        }

        @Override
        public void onFailure(int reason) {
            String msg = "WiFi Direct failed (code " + reason + "). "
                    + "Make sure WiFi is ON and Location permission is granted.";
            Log.e(TAG, msg);
            broadcast("ERROR", null, 0, msg);
            stopSelf();
        }
    };

    private void requestGroupInfo() {
        p2pManager.requestGroupInfo(p2pChannel, group -> {
            if (group == null) {
                broadcast("ERROR", null, 0,
                    "Could not get group info. Try again.");
                stopSelf();
                return;
            }
            String ssid   = group.getNetworkName();
            String passph = group.getPassphrase();
            currentIp = "192.168.49.1"; // Always the GO IP on Android
            groupCreated = true;
            running = true;

            Log.d(TAG, "Group: " + ssid + " pass=" + passph + " ip=" + currentIp);
            updateNotif("Active — connect to " + ssid, currentIp + ":" + PROXY_PORT);
            startProxyServer(ssid, passph);
        });
    }

    // ── Proxy server ──────────────────────────────────────────────
    private void startProxyServer(String ssid, String passph) {
        threadPool.execute(() -> {
            try {
                proxySocket = new ServerSocket();
                proxySocket.setReuseAddress(true);
                proxySocket.bind(new InetSocketAddress(currentIp, PROXY_PORT), 50);
                Log.d(TAG, "Proxy listening on " + currentIp + ":" + PROXY_PORT);

                // Broadcast success AFTER socket is bound
                broadcast("STARTED", currentIp, PROXY_PORT, null);

                while (!proxySocket.isClosed()) {
                    try {
                        Socket client = proxySocket.accept();
                        threadPool.execute(new ProxyWorker(client));
                    } catch (IOException e) {
                        if (!proxySocket.isClosed()) Log.w(TAG, "Accept err: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Proxy start error: " + e.getMessage());
                broadcast("ERROR", null, 0, "Proxy failed: " + e.getMessage());
                stopSelf();
            }
        });
    }

    // ── Cleanup ───────────────────────────────────────────────────
    private void stopEverything() {
        running = false;
        try { if (proxySocket != null && !proxySocket.isClosed()) proxySocket.close(); }
        catch (IOException ignored) {}
        if (groupCreated && p2pManager != null) {
            p2pManager.removeGroup(p2pChannel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { Log.d(TAG, "Group removed"); }
                @Override public void onFailure(int r) {}
            });
            groupCreated = false;
        }
        if (threadPool != null) threadPool.shutdownNow();
        broadcast("STOPPED", null, 0, null);
        stopForeground(true);
        stopSelf();
    }

    // ── Helpers ───────────────────────────────────────────────────
    public static boolean isRunning() { return running; }

    private void broadcast(String action, String ip, int port, String msg) {
        Intent i = new Intent(BROADCAST_ACTION);
        i.putExtra("action", action);
        if (ip  != null) i.putExtra("ip",   ip);
        if (port > 0)    i.putExtra("port", port);
        if (msg != null) i.putExtra("msg",  msg);
        sendBroadcast(i);
    }

    // ── Notification ─────────────────────────────────────────────
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "WiFi Share", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    private Notification buildNotif(String title, String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        Intent stopI = new Intent(this, ProxyService.class);
        stopI.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopI,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("📡 WiFi Share — " + title)
                .setContentText(text.isEmpty() ? "Running in background" : "Proxy: " + text)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
                .setOngoing(true)
                .build();
    }

    private void updateNotif(String title, String text) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIF_ID, buildNotif(title, text));
    }
}

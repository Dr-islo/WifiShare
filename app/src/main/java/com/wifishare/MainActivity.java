package com.wifishare;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.*;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TextView tvWifiName, tvStatus, tvProxyInfo, tvClientsLabel;
    private EditText etGroupName, etGroupPass;
    private Button btnToggle, btnCopyProxy;
    private View cardProxy;
    private boolean isRunning = false;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getStringExtra("action");
            if (action == null) return;
            switch (action) {
                case "STARTED":
                    String ip   = intent.getStringExtra("ip");
                    int    port = intent.getIntExtra("port", ProxyService.PROXY_PORT);
                    onStarted(ip, port);
                    break;
                case "STOPPED":
                    onStopped();
                    break;
                case "ERROR":
                    String msg = intent.getStringExtra("msg");
                    showError(msg);
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvWifiName    = findViewById(R.id.tvWifiName);
        tvStatus      = findViewById(R.id.tvStatus);
        tvProxyInfo   = findViewById(R.id.tvProxyInfo);
        tvClientsLabel= findViewById(R.id.tvClientsLabel);
        etGroupName   = findViewById(R.id.etGroupName);
        etGroupPass   = findViewById(R.id.etGroupPass);
        btnToggle     = findViewById(R.id.btnToggle);
        btnCopyProxy  = findViewById(R.id.btnCopyProxy);
        cardProxy     = findViewById(R.id.cardProxy);

        requestAllPermissions();
        checkWriteSettings();
        loadCurrentWifi();

        btnToggle.setOnClickListener(v -> {
            if (isRunning) stopSharing();
            else startSharing();
        });

        btnCopyProxy.setOnClickListener(v -> {
            String text = tvProxyInfo.getText().toString();
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("proxy", text));
            Toast.makeText(this, "Copied to clipboard!", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(ProxyService.BROADCAST_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        if (ProxyService.isRunning()) {
            onStarted(ProxyService.currentIp, ProxyService.PROXY_PORT);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(receiver);
    }

    private void loadCurrentWifi() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null && wm.isWifiEnabled()) {
            WifiInfo info = wm.getConnectionInfo();
            String ssid = info.getSSID();
            if (ssid != null) ssid = ssid.replace("\"", "");
            if (ssid == null || ssid.equals("<unknown ssid>")) ssid = "Unknown";
            tvWifiName.setText("Connected to: " + ssid);
            etGroupName.setText(ssid + "_Share");
        } else {
            tvWifiName.setText("WiFi: Not connected");
        }
    }

    private void startSharing() {
        String name = etGroupName.getText().toString().trim();
        String pass = etGroupPass.getText().toString().trim();

        if (name.isEmpty()) { etGroupName.setError("Required"); return; }
        if (!pass.isEmpty() && pass.length() < 8) {
            etGroupPass.setError("Min 8 characters"); return;
        }

        tvStatus.setText("⏳ Starting…");
        btnToggle.setEnabled(false);

        Intent i = new Intent(this, ProxyService.class);
        i.setAction(ProxyService.ACTION_START);
        i.putExtra("ssid", name);
        i.putExtra("pass", pass);
        ContextCompat.startForegroundService(this, i);
    }

    private void stopSharing() {
        Intent i = new Intent(this, ProxyService.class);
        i.setAction(ProxyService.ACTION_STOP);
        startService(i);
    }

    private void onStarted(String ip, int port) {
        isRunning = true;
        btnToggle.setEnabled(true);
        btnToggle.setText("⏹  Stop Sharing");
        btnToggle.setBackgroundResource(R.drawable.btn_stop);
        tvStatus.setText("🟢  Sharing is ACTIVE");
        tvStatus.setTextColor(0xFF4CAF50);

        tvProxyInfo.setText(ip + ":" + port);
        tvClientsLabel.setText(
            "On the connecting device:\n" +
            "1. Join WiFi: \"" + etGroupName.getText() + "\"\n" +
            "2. WiFi settings → Advanced → Proxy → Manual\n" +
            "3. Host: " + ip + "   Port: " + port + "\n" +
            "4. Save — internet will work ✓"
        );
        cardProxy.setVisibility(View.VISIBLE);
    }

    private void onStopped() {
        isRunning = false;
        btnToggle.setEnabled(true);
        btnToggle.setText("▶  Start Sharing");
        btnToggle.setBackgroundResource(R.drawable.btn_start);
        tvStatus.setText("🔴  Sharing is STOPPED");
        tvStatus.setTextColor(0xFFF44336);
        cardProxy.setVisibility(View.GONE);
    }

    private void showError(String msg) {
        btnToggle.setEnabled(true);
        new AlertDialog.Builder(this)
            .setTitle("Error")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show();
    }

    // ── Permissions: handles Android 6, 10, 13, 14, 15 ──────────────
    private void requestAllPermissions() {
        List<String> perms = new ArrayList<>();

        // Always needed
        perms.add(Manifest.permission.ACCESS_WIFI_STATE);
        perms.add(Manifest.permission.CHANGE_WIFI_STATE);
        perms.add(Manifest.permission.ACCESS_NETWORK_STATE);

        // Location needed for WiFi Direct on Android 6-12
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
            perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }

        // Android 13+ uses NEARBY_WIFI_DEVICES instead
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        List<String> toRequest = new ArrayList<>();
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(p);
            }
        }
        if (!toRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), 1);
        }
    }

    private void checkWriteSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(this)) {
            new AlertDialog.Builder(this)
                .setTitle("Permission Needed")
                .setMessage("Allow 'Modify system settings' so the hotspot can be created.")
                .setPositiveButton("Open Settings", (d, w) -> startActivityForResult(
                    new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        android.net.Uri.parse("package:" + getPackageName())), 100))
                .setNegativeButton("Cancel", null)
                .show();
        }
    }
}

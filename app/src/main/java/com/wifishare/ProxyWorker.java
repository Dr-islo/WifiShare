package com.wifishare;

import android.util.Log;

import java.io.*;
import java.net.*;

/**
 * Handles a single client connection through the proxy.
 * Supports:
 *   - Plain HTTP (GET, POST, etc.)  → forwards request to target server
 *   - HTTPS via CONNECT tunnel      → creates raw TCP tunnel, no MITM
 */
public class ProxyWorker implements Runnable {

    private static final String TAG        = "ProxyWorker";
    private static final int    TIMEOUT_MS = 30_000;
    private static final int    BUF_SIZE   = 8192;

    private final Socket clientSocket;

    public ProxyWorker(Socket clientSocket) {
        this.clientSocket = clientSocket;
        try {
            clientSocket.setSoTimeout(TIMEOUT_MS);
            clientSocket.setTcpNoDelay(true);
        } catch (Exception ignored) {}
    }

    @Override
    public void run() {
        try (Socket cs = clientSocket) {
            InputStream  clientIn  = cs.getInputStream();
            OutputStream clientOut = cs.getOutputStream();

            // Read the first line of the HTTP request
            String firstLine = readLine(clientIn);
            if (firstLine == null || firstLine.isEmpty()) return;

            Log.d(TAG, "Request: " + firstLine);

            if (firstLine.startsWith("CONNECT ")) {
                handleConnect(firstLine, clientIn, clientOut);
            } else {
                handleHttp(firstLine, clientIn, clientOut);
            }
        } catch (Exception e) {
            Log.w(TAG, "Worker error: " + e.getMessage());
        }
    }

    // ── HTTPS CONNECT tunnel ──────────────────────────────────────
    private void handleConnect(String firstLine, InputStream clientIn, OutputStream clientOut)
            throws IOException {
        // CONNECT host:port HTTP/1.1
        String[] parts = firstLine.split(" ");
        if (parts.length < 2) return;
        String[] hostPort = parts[1].split(":");
        String host = hostPort[0];
        int    port = hostPort.length > 1 ? Integer.parseInt(hostPort[1]) : 443;

        // Drain remaining headers
        drainHeaders(clientIn);

        // Connect to target
        try (Socket remote = new Socket()) {
            remote.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            remote.setSoTimeout(TIMEOUT_MS);

            // Tell client tunnel is ready
            PrintWriter pw = new PrintWriter(new OutputStreamWriter(clientOut));
            pw.print("HTTP/1.1 200 Connection Established\r\n\r\n");
            pw.flush();

            // Bidirectional pipe
            pipe(clientIn, clientOut, remote.getInputStream(), remote.getOutputStream());
        } catch (Exception e) {
            // Send 502 if can't connect
            clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes());
        }
    }

    // ── Plain HTTP ────────────────────────────────────────────────
    private void handleHttp(String firstLine, InputStream clientIn, OutputStream clientOut)
            throws IOException {
        // GET http://example.com/path HTTP/1.1
        String[] tokens = firstLine.split(" ");
        if (tokens.length < 3) return;

        String method = tokens[0];
        String urlStr = tokens[1];
        String version = tokens[2];

        // Read all headers from client
        StringBuilder headerBuf = new StringBuilder();
        String line;
        String host = null;
        int    port = 80;
        while (!(line = readLine(clientIn)).isEmpty()) {
            if (line.toLowerCase().startsWith("host:")) {
                String hostVal = line.substring(5).trim();
                if (hostVal.contains(":")) {
                    String[] hp = hostVal.split(":");
                    host = hp[0];
                    port = Integer.parseInt(hp[1]);
                } else {
                    host = hostVal;
                }
            }
            // Remove proxy-specific headers
            if (!line.toLowerCase().startsWith("proxy-connection:") &&
                !line.toLowerCase().startsWith("proxy-authorization:")) {
                headerBuf.append(line).append("\r\n");
            }
        }

        if (host == null) return;

        // Build path
        String path = urlStr;
        try {
            URL u = new URL(urlStr);
            path = u.getPath();
            if (path == null || path.isEmpty()) path = "/";
            if (u.getQuery() != null) path += "?" + u.getQuery();
        } catch (Exception ignored) {}

        // Connect to target server
        try (Socket remote = new Socket()) {
            remote.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            remote.setSoTimeout(TIMEOUT_MS);

            OutputStream remoteOut = remote.getOutputStream();
            InputStream  remoteIn  = remote.getInputStream();

            // Forward request line
            String reqLine = method + " " + path + " " + version + "\r\n";
            remoteOut.write(reqLine.getBytes());
            remoteOut.write(headerBuf.toString().getBytes());
            remoteOut.write("Connection: close\r\n\r\n".getBytes());

            // Read possible request body (for POST etc.)
            // Forward available bytes
            byte[] buf = new byte[BUF_SIZE];
            int avail = clientIn.available();
            while (avail > 0) {
                int n = clientIn.read(buf, 0, Math.min(avail, BUF_SIZE));
                if (n < 0) break;
                remoteOut.write(buf, 0, n);
                avail = clientIn.available();
            }
            remoteOut.flush();

            // Stream response back to client
            int n;
            while ((n = remoteIn.read(buf)) > 0) {
                clientOut.write(buf, 0, n);
            }
            clientOut.flush();
        }
    }

    // ── Bidirectional pipe for CONNECT tunnels ─────────────────────
    private void pipe(InputStream c2s, OutputStream c2sOut,
                      InputStream s2c, OutputStream s2cOut) {
        Thread t = new Thread(() -> {
            try {
                byte[] buf = new byte[BUF_SIZE];
                int n;
                while ((n = s2c.read(buf)) > 0) {
                    c2sOut.write(buf, 0, n);
                    c2sOut.flush();
                }
            } catch (Exception ignored) {}
        });
        t.start();
        try {
            byte[] buf = new byte[BUF_SIZE];
            int n;
            while ((n = c2s.read(buf)) > 0) {
                s2cOut.write(buf, 0, n);
                s2cOut.flush();
            }
        } catch (Exception ignored) {}
        try { t.join(3000); } catch (Exception ignored) {}
    }

    // ── Helpers ───────────────────────────────────────────────────
    private String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        return sb.toString();
    }

    private void drainHeaders(InputStream in) throws IOException {
        String line;
        while (!(line = readLine(in)).isEmpty()) {
            // discard
        }
    }
}

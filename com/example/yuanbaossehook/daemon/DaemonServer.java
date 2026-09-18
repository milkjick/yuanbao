package com.example.yuanbaossehook.daemon;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loopback server of the standalone shell daemon.
 *
 * Wire format and authentication are byte-for-byte the same as the module-process bridge
 * (BridgeServer) so the injected host can talk to either backend with one code path:
 *
 *     request : {"method":"shell","token":"...","extras":{...}}      (4 byte big-endian length + body)
 *     response: {"ok":true,"result":"<json string>"} or {"ok":false,"error":"..."}
 *
 * The only differences are deliberate:
 *   - it runs as uid 2000 all by itself, so it needs no Shizuku call to do privileged work;
 *   - the host token is read straight from the handshake file with plain java.io (uid 2000 can read
 *     the host's external files dir), instead of shelling out through Shizuku;
 *   - it lives outside the module app process, so force-stopping or updating the module app does
 *     not take the tool backend down.
 */
final class DaemonServer {

    /** Daemon port range, kept separate from the module bridge range (8799-8809). */
    static final int PORT_FIRST = 8810;
    static final int PORT_LAST = 8819;
    static final String YUANBAO = "com.tencent.hunyuan.app.chat";
    static final String DEFAULT_HOST_DIR =
            "/storage/emulated/0/Android/data/" + YUANBAO + "/files";
    private static final String HANDSHAKE_NAME = "yb_bridge.json";
    private static final long TOKEN_TRUST_MS = 30 * 60 * 1000L;
    private static final int MAX_FRAME = 96 * 1024 * 1024;
    private static final int MAX_WORKERS = 8;
    private static final long MAX_LOG_BYTES = 2 * 1024 * 1024L;

    private static volatile int boundPort;
    private static volatile long startedAt;
    private static final AtomicInteger served = new AtomicInteger();
    /** Last served request, so callers can see the real per-call latency the daemon achieves. */
    private static volatile String lastMethod = "";
    private static volatile long lastTookMs = -1L;
    private static final AtomicInteger rejected = new AtomicInteger();
    private static final AtomicInteger workers = new AtomicInteger();
    private static volatile boolean stopRequested;
    private static volatile String hostToken = "";
    /** Module versionCode the running code was launched from; lets the launcher detect a stale daemon. */
    private static volatile int codeVersion;
    private static volatile long tokenAt;
    private static volatile String handshakePath = "";
    private static volatile File logFile;

    private DaemonServer() {}

    static int boundPort() { return boundPort; }

    static long uptimeMs() { return startedAt == 0L ? 0L : System.currentTimeMillis() - startedAt; }

    static int servedCount() { return served.get(); }

    static String lastMethod() { return lastMethod; }

    static long lastTookMs() { return lastTookMs; }

    static int rejectedCount() { return rejected.get(); }

    static String handshakePath() { return handshakePath; }

    static void setCodeVersion(int v) { codeVersion = v; }

    static int codeVersion() { return codeVersion; }

    // ------------------------------------------------------------------ logging

    static void log(String line) {
        String full = "[" + new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                .format(new java.util.Date()) + "] " + line;
        try { System.out.println(full); } catch (Throwable ignored) {}
        File f = logFile;
        if (f == null) return;
        try {
            if (f.exists() && f.length() > MAX_LOG_BYTES) {
                // Cheap rotation: keep the tail instead of growing without bound.
                try { f.delete(); } catch (Throwable ignored) {}
            }
            FileOutputStream fos = new FileOutputStream(f, true);
            try {
                fos.write((full + "\n").getBytes(StandardCharsets.UTF_8));
                fos.flush();
            } finally {
                try { fos.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            try { System.err.println("daemon log failed: " + t); } catch (Throwable ignored) {}
        }
    }

    // ------------------------------------------------------------------ token handshake

    private static String readText(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }

    /** Plain file read: no Context, no Shizuku, just uid 2000 reading the host's own handshake. */
    private static String readHostToken() {
        try {
            File f = new File(handshakePath);
            if (!f.exists() || !f.canRead()) return "";
            String s = readText(f);
            int brace = s.indexOf('{');
            if (brace < 0) return "";
            return new JSONObject(s.substring(brace)).optString("token", "");
        } catch (Throwable t) {
            log("handshake read failed: " + t);
            return "";
        }
    }

    private static synchronized String refreshToken(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && !hostToken.isEmpty() && now - tokenAt < TOKEN_TRUST_MS) return hostToken;
        tokenAt = now;
        String t = readHostToken();
        if (!t.isEmpty()) {
            if (!t.equals(hostToken)) log("host token " + (hostToken.isEmpty() ? "loaded" : "rotated")
                    + " len=" + t.length());
            hostToken = t;
        } else if (hostToken.isEmpty()) {
            log("handshake token not ready yet: " + handshakePath);
        }
        return hostToken;
    }

    // ------------------------------------------------------------------ lifecycle

    static JSONObject requestStop() {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true).put("stopping", true).put("pid", android.os.Process.myPid());
            o.put("note", "守护进程即将退出；模块 App 或下一次 daemon_ensure 会按需重新拉起");
        } catch (Throwable ignored) {}
        stopRequested = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(300L); } catch (Throwable ignored) {}
                log("stop requested through the bridge, exiting");
                try { System.exit(0); } catch (Throwable ignored) {}
            }
        }, "yb-daemon-exit");
        t.setDaemon(false);
        t.start();
        return o;
    }

    /**
     * Binds the first free port in the daemon range and serves until stopped.
     *
     * @return the bound port, or -1 when every port in the range is taken (i.e. another daemon is
     *         already running or something else owns the range).
     */
    static int run(String hostDir, int preferredPort, File log) {
        logFile = log;
        handshakePath = new File(hostDir == null || hostDir.isEmpty() ? DEFAULT_HOST_DIR : hostDir,
                HANDSHAKE_NAME).getAbsolutePath();
        startedAt = System.currentTimeMillis();

        ServerSocket ss = null;
        int bound = -1;
        for (int p = preferredPort; p <= PORT_LAST; p++) {
            try {
                ServerSocket cand = new ServerSocket();
                cand.setReuseAddress(true);
                cand.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), p), 16);
                ss = cand;
                bound = p;
                break;
            } catch (Throwable t) {
                log("port " + p + " unavailable: " + t);
            }
        }
        if (ss == null) {
            log("no free port in " + preferredPort + "-" + PORT_LAST + ", another daemon is likely running");
            return -1;
        }
        boundPort = bound;
        log("daemon ready pid=" + android.os.Process.myPid() + " uid=" + android.os.Process.myUid()
                + " port=" + bound + " handshake=" + handshakePath);
        // Token is loaded lazily on the first request, but warming it makes the first call fast
        // and logs handshake problems early.
        refreshToken(false);

        while (!stopRequested) {
            Socket s = null;
            try {
                s = ss.accept();
                if (workers.get() >= MAX_WORKERS) {
                    log("too many concurrent requests (" + workers.get() + "), closing peer");
                    try { s.close(); } catch (Throwable ignored) {}
                    continue;
                }
                final Socket sock = s;
                workers.incrementAndGet();
                Thread t = new Thread(new Runnable() {
                    @Override public void run() {
                        try { handle(sock); } finally { workers.decrementAndGet(); }
                    }
                }, "yb-daemon-conn");
                t.setDaemon(true);
                t.start();
            } catch (Throwable t) {
                if (stopRequested) break;
                log("accept failed: " + t);
                if (s != null) try { s.close(); } catch (Throwable ignored) {}
            }
        }
        try { ss.close(); } catch (Throwable ignored) {}
        log("daemon loop ended pid=" + android.os.Process.myPid());
        return bound;
    }

    // ------------------------------------------------------------------ request handling

    private static void handle(Socket s) {
        String peer = "";
        try {
            peer = String.valueOf(s.getRemoteSocketAddress());
            s.setSoTimeout(660000);
            s.setTcpNoDelay(true);
            InputStream raw = new BufferedInputStream(s.getInputStream(), 65536);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 65536);
            JSONObject req = readFrame(raw);
            String method = req.optString("method", "");
            String token = req.optString("token", "");
            JSONObject extras = req.optJSONObject("extras");

            String expected = refreshToken(false);
            if (expected.isEmpty()) expected = refreshToken(true);
            boolean authed = !expected.isEmpty() && expected.equals(token);
            if (!authed) {
                // Host may have restarted with a rotated token: re-read once before rejecting.
                expected = refreshToken(true);
                authed = !expected.isEmpty() && expected.equals(token);
            }
            if (!authed) {
                rejected.incrementAndGet();
                String reason = expected.isEmpty()
                        ? "bridge token 未就绪（宿主进程尚未写入握手文件）"
                        : "bridge token 不匹配";
                log("reject: " + reason + " peer=" + peer + " method=" + method);
                writeFrame(out, new JSONObject().put("ok", false).put("error", reason));
                return;
            }

            long t0 = System.currentTimeMillis();
            JSONObject result = "daemon_stop".equals(method)
                    ? requestStop()
                    : DaemonOps.dispatch(method, extras, null);
            served.incrementAndGet();
            long took = System.currentTimeMillis() - t0;
            lastMethod = method;
            lastTookMs = took;
            if (took > 200L || !result.optBoolean("ok", true)) {
                log("served " + method + " peer=" + peer + " took=" + took + "ms ok="
                        + result.optBoolean("ok", false));
            }
            writeFrame(out, new JSONObject().put("ok", true).put("result", result.toString()));
        } catch (Throwable t) {
            log("connection failed peer=" + peer + ": " + t);
            try {
                writeFrame(s.getOutputStream(), new JSONObject().put("ok", false)
                        .put("error", "daemon error: " + t));
            } catch (Throwable ignored) {}
        } finally {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    private static JSONObject readFrame(InputStream in) throws IOException, org.json.JSONException {
        DataInputStream din = new DataInputStream(in);
        int len = din.readInt();
        if (len <= 0 || len > MAX_FRAME) throw new IOException("bad frame length " + len);
        byte[] buf = new byte[len];
        din.readFully(buf);
        return new JSONObject(new String(buf, StandardCharsets.UTF_8));
    }

    private static void writeFrame(OutputStream out, JSONObject payload) throws IOException {
        byte[] b = payload.toString().getBytes(StandardCharsets.UTF_8);
        DataOutputStream dout = new DataOutputStream(out);
        dout.writeInt(b.length);
        dout.write(b);
        dout.flush();
    }
}

package com.example.yuanbaossehook;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Module-process side of the standalone shell daemon (see {@code com.example.yuanbaossehook.daemon}).
 *
 * Responsibilities, and nothing else:
 *   - probe() : is a daemon listening on the loopback range and does it accept our host token?
 *   - ensure(): probe, and if dead, ask Shizuku (uid 2000) to spawn the daemon detached, then wait
 *               until it answers. This is the ONLY moment Shizuku is needed; the daemon itself and
 *               every later call run without it, and outside this app's process.
 *   - stop()  : token-authenticated shutdown (used by the UI/维护按钮).
 *
 * Why a separate process: a request served by the daemon costs one fork/exec, while the same request
 * served by this process first has to pay a Binder round trip into Shizuku and back. More
 * importantly the daemon keeps working when this app is force-stopped, updated or killed by the ROM.
 */
final class DaemonLauncher {

    static final String DAEMON_CLASS = "com.example.yuanbaossehook.daemon.DaemonMain";
    static final int PORT_FIRST = 8810;
    static final int PORT_LAST = 8819;
    /**
     * Daemon log. /data/local/tmp is owned by shell (the daemon's own uid), so the daemon can always
     * append to it and the ROM's app-data cleaners do not touch it. The host's external files dir was
     * used before, but that directory gets wiped in practice, which silently destroyed the log.
     */
    static final String LOG_PATH = "/data/local/tmp/yb_daemon.log";
    /** Output of the spawning shell, including app_process startup errors. */
    static final String SPAWN_LOG = "/data/local/tmp/yb_daemon_spawn.log";
    private static final int CONNECT_TIMEOUT_MS = 600;
    /**
     * Minimum gap between two spawn attempts. Long on purpose: a broken environment (no Shizuku
     * grant) must not fork an app_process every heartbeat.
     */
    private static final long MIN_RESPAWN_INTERVAL_MS = 60000L;
    private static final long START_DEADLINE_MS = 12000L;

    private static volatile long lastEnsureAt;
    private static volatile JSONObject lastProbe;
    private static volatile String lastError = "";

    private DaemonLauncher() {}

    // ------------------------------------------------------------------ status / probe

    /** Cheap liveness check: connect + authenticated ping. Never launches anything. */
    /** Installed module versionCode, used as the daemon's code fingerprint. 0 when unknown. */
    private static int installedVersionCode(Context ctx) {
        try {
            if (ctx == null) return 0;
            android.content.pm.PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            return pi == null ? 0 : pi.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    static JSONObject probe() {
        JSONObject out = new JSONObject();
        String token = BridgeServer.currentHostToken();
        try {
            out.put("tokenReady", !token.isEmpty());
            if (token.isEmpty()) {
                out.put("alive", false);
                out.put("error", "宿主令牌未就绪（元宝尚未写入握手文件）");
                lastProbe = out;
                return out;
            }
            long t0 = android.os.SystemClock.elapsedRealtime();
            Throwable last = null;
            for (int port = PORT_FIRST; port <= PORT_LAST; port++) {
                try {
                    JSONObject r = call(port, "ping", null, token, 4000);
                    out.put("alive", true).put("port", port);
                    out.put("uid", r.optInt("uid", 0));
                    out.put("pid", r.optInt("pid", 0));
                    out.put("served", r.optInt("served", 0));
                    out.put("uptimeMs", r.optLong("uptimeMs", 0L));
                    out.put("execMode", r.optString("execMode", ""));
                    out.put("codeVersion", r.optInt("codeVersion", 0));
                    out.put("servedCount", r.optInt("served", 0));
                    out.put("lastMethod", r.optString("lastMethod", ""));
                    out.put("lastTookMs", r.optLong("lastTookMs", -1L));
                    out.put("tookMs", android.os.SystemClock.elapsedRealtime() - t0);
                    lastProbe = out;
                    lastError = "";
                    return out;
                } catch (Throwable t) {
                    last = t;
                }
            }
            out.put("alive", false);
            out.put("error", String.valueOf(last));
            out.put("tookMs", android.os.SystemClock.elapsedRealtime() - t0);
        } catch (Throwable t) {
            try { out.put("alive", false).put("error", String.valueOf(t)); } catch (Throwable ignored) {}
        }
        lastProbe = out;
        return out;
    }

    /** Probe + (when dead) spawn through Shizuku + wait for liveness. Safe to call from a heartbeat. */
    static synchronized JSONObject ensure(Context ctx, String reason) {
        JSONObject out = new JSONObject();
        try {
            out.put("reason", reason == null ? "" : reason);
            JSONObject p = probe();
            out.put("probeBefore", p);
            boolean forceRestart = false;
            if (p.optBoolean("alive", false)) {
                // The daemon deliberately outlives module updates, so it can be left serving the
                // previous build's code. Compare fingerprints and restart it when the installed
                // module is newer; otherwise the fast path would silently run stale logic.
                int running = p.optInt("codeVersion", 0);
                int installed = installedVersionCode(ctx);
                if (running != 0 && installed != 0 && running != installed) {
                    McpIdeGateway.log("[DAEMON] stale code " + running + " -> " + installed + ", restarting");
                    out.put("staleCodeVersion", running).put("installedCodeVersion", installed);
                    try { stop(); } catch (Throwable ignored) {}
                    try { Thread.sleep(400L); } catch (Throwable ignored) {}
                    forceRestart = true;
                } else {
                    out.put("ok", true).put("alive", true).put("started", false);
                    return out;
                }
            }
            long since = android.os.SystemClock.elapsedRealtime() - lastEnsureAt;
            if (!forceRestart && since < MIN_RESPAWN_INTERVAL_MS) {
                out.put("ok", false).put("alive", false).put("started", false);
                out.put("error", "距上次拉起仅 " + since + "ms，稍后重试");
                return out;
            }
            lastEnsureAt = android.os.SystemClock.elapsedRealtime();
            String cmd = buildSpawnCommand(ctx);
            out.put("command", cmd);
            ShizukuShell.Result sr = ShizukuShell.exec(cmd, 25000L, null);
            out.put("spawn", new JSONObject()
                    .put("ok", sr.ok).put("exitCode", sr.exitCode).put("stage", sr.stage)
                    .put("stdout", trim(sr.stdout, 2000)).put("stderr", trim(sr.stderr, 800))
                    .put("error", sr.error));
            // The shell returns as soon as the detached process is forked; give the daemon time to
            // bind its port and load its token.
            long deadline = android.os.SystemClock.elapsedRealtime() + START_DEADLINE_MS;
            JSONObject p2 = null;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                try { Thread.sleep(400L); } catch (Throwable ignored) {}
                p2 = probe();
                if (p2.optBoolean("alive", false)) break;
            }
            boolean alive = p2 != null && p2.optBoolean("alive", false);
            out.put("ok", alive).put("alive", alive).put("started", true).put("probeAfter", p2);
            if (!alive) {
                out.put("error", "守护进程拉起后仍未就绪，请看 " + SPAWN_LOG + " 与 " + LOG_PATH);
                out.put("spawnLog", tail(SPAWN_LOG, 40));
                lastError = "daemon spawn failed";
            } else {
                lastError = "";
            }
            return out;
        } catch (Throwable t) {
            try { return out.put("ok", false).put("alive", false).put("error", String.valueOf(t)); }
            catch (Throwable ignored) { return out; }
        }
    }

    /**
     * Diagnostics for the module UI / notification. Uses the cached probe only: this is called from
     * the notification builder on the main thread, so it must never block on a socket read.
     * The heartbeat thread keeps the cache fresh, and {@link #statusFresh} forces a probe.
     */
    static JSONObject status(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("portFirst", PORT_FIRST).put("portLast", PORT_LAST);
            o.put("class", DAEMON_CLASS);
            o.put("logPath", LOG_PATH);
            o.put("spawnLog", SPAWN_LOG);
            o.put("codePath", ctx == null ? "" : String.valueOf(ctx.getPackageCodePath()));
            JSONObject p = lastProbe;
            o.put("probe", p == null ? new JSONObject().put("alive", false)
                    .put("note", "尚未探测（心跳线程会定期刷新）") : p);
            o.put("lastError", lastError);
            o.put("lastEnsureAt", lastEnsureAt);
        } catch (Throwable t) {
            // diagnostics must never throw
        }
        return o;
    }

    /** Same as {@link #status} but with a fresh liveness probe; used by the daemon_status tool. */
    static JSONObject statusFresh(Context ctx) {
        try { probe(); } catch (Throwable ignored) {}
        return status(ctx);
    }

    /** Token-authenticated shutdown of the daemon (the module app or the host can ask for it). */
    static JSONObject stop() {
        JSONObject out = new JSONObject();
        try {
            String token = BridgeServer.currentHostToken();
            if (token.isEmpty()) return out.put("ok", false).put("error", "宿主令牌未就绪");
            for (int port = PORT_FIRST; port <= PORT_LAST; port++) {
                try {
                    JSONObject r = call(port, "daemon_stop", null, token, 5000);
                    return out.put("ok", true).put("port", port).put("result", r);
                } catch (Throwable t) {
                    // try the next port
                }
            }
            return out.put("ok", false).put("error", "守护进程未在运行");
        } catch (Throwable t) {
            try { out.put("ok", false).put("error", String.valueOf(t)); } catch (Throwable ignored) {}
            return out;
        }
    }

    // ------------------------------------------------------------------ process spawn

    /**
     * Detached start command. The classpath is this module's own APK so the daemon always executes
     * the installed code; {@code setsid}/{@code nohup} plus redirects keep it alive after the
     * spawning shell - and Shizuku's remote process - goes away.
     */
    private static String buildSpawnCommand(Context ctx) {
        String apk = ctx == null ? "" : String.valueOf(ctx.getPackageCodePath());
        if (apk.isEmpty()) apk = "/data/app/unknown/base.apk";
        String hostDir = BridgeServer.HOST_FILES_DIR;
        StringBuilder sb = new StringBuilder();
        sb.append("cd /data/local/tmp 2>/dev/null || cd /; ");
        sb.append("if command -v setsid >/dev/null 2>&1; then L=\"setsid\"; else L=\"\"; fi; ");
        sb.append("CLASSPATH=").append(ShizukuShell.q(apk)).append(" ");
        sb.append("nohup $L /system/bin/app_process /system/bin ").append(DAEMON_CLASS);
        sb.append(" --host-dir=").append(ShizukuShell.q(hostDir));
        sb.append(" --port=").append(PORT_FIRST);
        sb.append(" --code-version=").append(installedVersionCode(ctx));
        sb.append(" --log=").append(ShizukuShell.q(LOG_PATH));
        sb.append(" >> ").append(SPAWN_LOG).append(" 2>&1 < /dev/null & ");
        sb.append("echo YB_DAEMON_SPAWNED=$!; ");
        sb.append("sleep 1; tail -n 15 ").append(SPAWN_LOG).append(" 2>/dev/null");
        return sb.toString();
    }

    /** One authenticated request/response over the daemon's frame protocol. */
    private static JSONObject call(int port, String method, JSONObject extras, String token, int readTimeoutMs)
            throws Exception {
        JSONObject req = new JSONObject();
        req.put("method", method);
        req.put("token", token == null ? "" : token);
        req.put("extras", extras == null ? new JSONObject() : extras);
        byte[] payload = req.toString().getBytes(StandardCharsets.UTF_8);

        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(readTimeoutMs);
            s.setTcpNoDelay(true);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 65536));
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 65536));
            int len = in.readInt();
            if (len <= 0 || len > 96 * 1024 * 1024) throw new java.io.IOException("bad frame length " + len);
            byte[] buf = new byte[len];
            in.readFully(buf);
            JSONObject resp = new JSONObject(new String(buf, StandardCharsets.UTF_8));
            if (!resp.optBoolean("ok", false)) {
                throw new java.io.IOException(resp.optString("error", "daemon refused"));
            }
            String inner = resp.optString("result", "");
            if (inner.isEmpty()) throw new java.io.IOException("empty daemon result");
            return new JSONObject(inner);
        } finally {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String trim(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }

    private static String tail(String path, int lines) {
        try {
            return ShizukuShell.exec("tail -n " + lines + " " + ShizukuShell.q(path) + " 2>/dev/null",
                    8000L, null).stdout;
        } catch (Throwable t) {
            return "";
        }
    }

    static boolean logExists() {
        try { return new File(LOG_PATH).exists() || new File(SPAWN_LOG).exists(); }
        catch (Throwable t) { return false; }
    }
}

package com.example.yuanbaossehook.daemon;

import java.io.File;

/**
 * Entry point of the standalone daemon, run as uid 2000 (shell) through Shizuku:
 *
 * <pre>
 *   CLASSPATH=/data/app/.../base.apk app_process /system/bin \
 *       com.example.yuanbaossehook.daemon.DaemonMain --host-dir=... [--port=8810] [--log=...]
 * </pre>
 *
 * The classpath is the *module APK itself*, so the daemon always runs exactly the code of the
 * installed module - no separate dex to keep in sync. Because it is a plain app_process it has no
 * Activity/Context and survives the module app being force-stopped or updated.
 *
 * This class must stay public: app_process instantiates the main class reflectively.
 */
public final class DaemonMain {

    private DaemonMain() {}

    public static void main(String[] args) {
        String hostDir = DaemonServer.DEFAULT_HOST_DIR;
        String logPath = "";
        int port = DaemonServer.PORT_FIRST;
        int codeVersion = 0;
        for (String a : args == null ? new String[0] : args) {
            if (a == null) continue;
            if (a.startsWith("--host-dir=")) hostDir = a.substring("--host-dir=".length());
            else if (a.startsWith("--log=")) logPath = a.substring("--log=".length());
            else if (a.startsWith("--code-version=")) {
                try { codeVersion = Integer.parseInt(a.substring("--code-version=".length()).trim()); }
                catch (Throwable ignored) {}
            }
            else if (a.startsWith("--port=")) {
                try { port = Integer.parseInt(a.substring("--port=".length()).trim()); }
                catch (Throwable ignored) {}
            }
        }
        port = Math.max(DaemonServer.PORT_FIRST, Math.min(DaemonServer.PORT_LAST, port));
        if (hostDir == null || hostDir.trim().isEmpty()) hostDir = DaemonServer.DEFAULT_HOST_DIR;

        DaemonServer.setCodeVersion(codeVersion);
        File log = resolveLog(logPath, hostDir);
        System.out.println("YB_DAEMON_START pid=" + android.os.Process.myPid()
                + " uid=" + android.os.Process.myUid() + " hostDir=" + hostDir
                + " port=" + port + " codeVersion=" + codeVersion
                + " log=" + (log == null ? "(stdout only)" : log.getAbsolutePath()));
        try {
            int bound = DaemonServer.run(hostDir, port, log);
            if (bound < 0) {
                // Every port in the range is taken: another daemon already serves the host.
                System.out.println("YB_DAEMON_ALREADY_RUNNING");
                System.exit(3);
            }
            System.out.println("YB_DAEMON_EXIT port=" + bound);
            System.exit(0);
        } catch (Throwable t) {
            System.err.println("YB_DAEMON_FATAL " + t);
            t.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Prefers the host's own external files dir (the host app and the module can both read it, which
     * makes daemon logs visible from either side), and falls back to /data/local/tmp when uid 2000
     * is not allowed to create files there on this ROM.
     */
    private static File resolveLog(String explicit, String hostDir) {
        File f = null;
        if (explicit != null && !explicit.trim().isEmpty()) f = new File(explicit.trim());
        if (f == null) f = new File(hostDir, "yb_daemon.log");
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            probeLogWritable(f);
            return f;
        } catch (Throwable t) {
            File alt = new File("/data/local/tmp/yb_daemon.log");
            System.err.println("YB_DAEMON log fallback (" + t + ") -> " + alt);
            return alt;
        }
    }

    /** Verifies the log target is writable so logging never silently disappears. */
    private static void probeLogWritable(File f) throws Exception {
        java.io.FileOutputStream fos = new java.io.FileOutputStream(f, true);
        try {
            fos.write(("--- daemon start ---\n").getBytes("UTF-8"));
            fos.flush();
        } finally {
            try { fos.close(); } catch (Throwable ignored) {}
        }
    }
}

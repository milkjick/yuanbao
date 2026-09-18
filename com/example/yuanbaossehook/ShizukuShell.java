package com.example.yuanbaossehook;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Shizuku-backed shell + filesystem helper.
 *
 * ARCHITECTURE NOTE
 * -----------------
 * The gateway (McpIdeGateway) normally runs INSIDE YuanBao's process, injected by Xposed.
 * That process can never hold the Shizuku grant: the grant and the
 * "&lt;applicationId&gt;.shizuku" provider belong to THIS module's package/UID.
 *
 * Therefore every Shizuku call happens in the module's own process and is reached from the
 * injected process through {@link ApkScanProvider}. This class is the module-process half.
 *
 * Everything here is honest: when Shizuku is missing, not running, or not granted, the
 * methods return ok=false with an explicit reason. Nothing is ever faked as success.
 */
final class ShizukuShell {

    private ShizukuShell() {}

    /** Hard cap for one Binder round trip (the platform limit is ~1 MiB per transaction). */
    static final int MAX_PAYLOAD = 512 * 1024;

    private static volatile Context appContext;
    private static volatile boolean listenersInstalled;

    static void attach(Context context) {
        if (context == null) return;
        try { appContext = context.getApplicationContext(); } catch (Throwable ignored) { appContext = context; }
        if (listenersInstalled) return;
        listenersInstalled = true;
        try {
            rikka.shizuku.Shizuku.addBinderReceivedListenerSticky(
                    () -> McpIdeGateway.log("[SHIZUKU] service binder received"));
        } catch (Throwable t) {
            McpIdeGateway.log("[SHIZUKU] addBinderReceivedListener failed: " + t);
        }
        try {
            rikka.shizuku.Shizuku.addBinderDeadListener(
                    () -> McpIdeGateway.log("[SHIZUKU] service binder died (is Shizuku still running?)"));
        } catch (Throwable ignored) {}
    }

    static Context context() { return appContext; }

    // ------------------------------------------------------------------ status

    /** Shizuku availability / grant state. Never throws, never fabricates state. */
    static JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            String mgrVersion = managerVersion();
            o.put("ok", true);
            o.put("managerPackage", managerPackage());
            o.put("managerVersion", mgrVersion == null ? "" : mgrVersion);
            o.put("managerInstalled", mgrVersion != null);
            o.put("modulePackage", modulePackage());
            o.put("moduleAllFilesAccess", allFilesAccess());

            IBinder binder;
            try {
                binder = rikka.shizuku.Shizuku.getBinder();
            } catch (Throwable t) {
                return o.put("available", false).put("binder", false)
                        .put("permissionGranted", false)
                        .put("stage", "CLASS_LOAD_FAILED")
                        .put("reason", "无法加载 Shizuku API 类: " + t);
            }
            if (binder == null) {
                return o.put("available", false).put("binder", false)
                        .put("permissionGranted", false)
                        .put("stage", "BINDER_NOT_RECEIVED")
                        .put("reason", mgrVersion == null
                                ? "未检测到 Shizuku。请先安装并启动 Shizuku（moe.shizuku.privileged.api），再回到本应用点击“请求 Shizuku 授权”。"
                                : "Shizuku 已安装，但服务/授权信息尚未送达本模块进程。请确认 Shizuku 服务正在运行（必要时重启 Shizuku 或重启手机），再回到本应用点击“请求 Shizuku 授权”。");
            }
            boolean alive = false;
            try { alive = binder.isBinderAlive() && binder.pingBinder(); } catch (Throwable ignored) {}
            o.put("available", true).put("binder", alive);
            if (!alive) {
                return o.put("permissionGranted", false).put("stage", "BINDER_DEAD")
                        .put("reason", "Shizuku binder 已断开：Shizuku 服务可能已停止。");
            }
            int perm;
            try {
                perm = rikka.shizuku.Shizuku.checkSelfPermission();
            } catch (Throwable t) {
                return o.put("permissionGranted", false).put("stage", "PERMISSION_CHECK_FAILED")
                        .put("reason", "无法检查 Shizuku 授权: " + t);
            }
            boolean granted = perm == PackageManager.PERMISSION_GRANTED;
            o.put("permissionGranted", granted);
            try { o.put("serverUid", rikka.shizuku.Shizuku.getUid()); } catch (Throwable ignored) {}
            try { o.put("serverApiVersion", rikka.shizuku.Shizuku.getVersion()); } catch (Throwable ignored) {}
            try { o.put("selinuxContext", String.valueOf(rikka.shizuku.Shizuku.getSELinuxContext())); } catch (Throwable ignored) {}
            if (!granted) {
                o.put("stage", "PERMISSION_DENIED");
                o.put("reason", "Shizuku 服务已连接，但本模块尚未获得授权。请打开“元宝本地 Agent 网关”应用并点击“请求 Shizuku 授权”。");
            } else {
                o.put("stage", "READY");
                o.put("reason", "Shizuku 已就绪：可经 shell 读写手机文件（无需 root）。");
            }
            return o;
        } catch (Throwable t) {
            try {
                return new JSONObject().put("ok", false).put("available", false)
                        .put("reason", "Shizuku 状态检查失败: " + t);
            } catch (Throwable ignored) { return o; }
        }
    }

    private static String modulePackage() {
        try {
            Context c = appContext;
            return c == null ? "com.example.yuanbaossehook" : c.getPackageName();
        } catch (Throwable ignored) { return "com.example.yuanbaossehook"; }
    }

    private static String managerPackage() {
        if (isPackageInstalled("moe.shizuku.privileged.api")) return "moe.shizuku.privileged.api";
        if (isPackageInstalled("moe.shizuku.manager")) return "moe.shizuku.manager";
        return "";
    }

    /** True when a Shizuku manager package is installed (it may still not be running). */
    static boolean managerInstalled() { return !managerPackage().isEmpty(); }

    /** Installed Shizuku manager package name, or "" when absent. */
    static String managerPackageName() { return managerPackage(); }

    private static boolean isPackageInstalled(String pkg) {
        Context c = appContext;
        if (c == null || pkg == null || pkg.isEmpty()) return false;
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable ignored) { return false; }
    }

    private static String managerVersion() {
        Context c = appContext;
        if (c == null) return null;
        for (String pkg : new String[]{"moe.shizuku.privileged.api", "moe.shizuku.manager"}) {
            try {
                android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(pkg, 0);
                return pi.versionName == null ? String.valueOf(pi.versionCode) : pi.versionName;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    static boolean allFilesAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 30) return android.os.Environment.isExternalStorageManager();
            return appContext != null && appContext.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) { return false; }
    }

    /** Ask the Shizuku manager for the API grant. Must be called from an Activity context. */
    static void requestPermission(Activity activity, int requestCode) {
        attach(activity);
        try {
            rikka.shizuku.Shizuku.requestPermission(requestCode);
            McpIdeGateway.log("[SHIZUKU] permission requested code=" + requestCode);
        } catch (Throwable t) {
            McpIdeGateway.log("[SHIZUKU] requestPermission failed: " + t);
        }
    }

    // ------------------------------------------------------------------ binder / service

    /**
     * Waits up to waitMs for the Shizuku binder. The manager delivers it shortly after this
     * app's process starts, and a ContentProvider cold start is exactly such a start.
     */
    private static IBinder awaitBinder(long waitMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, waitMs);
        IBinder b = null;
        for (;;) {
            try { b = rikka.shizuku.Shizuku.getBinder(); } catch (Throwable ignored) { b = null; }
            if (b != null) {
                try { if (b.pingBinder()) return b; } catch (Throwable ignored) {}
            }
            if (System.currentTimeMillis() >= deadline) return null;
            try { Thread.sleep(120L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        }
    }

    private static moe.shizuku.server.IShizukuService service(long waitMs) {
        IBinder b = awaitBinder(waitMs);
        if (b == null) return null;
        try { return moe.shizuku.server.IShizukuService.Stub.asInterface(b); } catch (Throwable ignored) { return null; }
    }

    private static boolean grantCheck(StringBuilder why) {
        try {
            if (rikka.shizuku.Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                why.append("Shizuku 尚未授权本模块：请打开“元宝本地 Agent 网关”应用并点击“请求 Shizuku 授权”。");
                return false;
            }
            return true;
        } catch (Throwable t) {
            why.append("无法检查 Shizuku 授权: ").append(t);
            return false;
        }
    }

    // ------------------------------------------------------------------ process execution

    static final class Result {
        boolean ok;
        String error = "";
        String stage = "";
        int exitCode = -1;
        String stdout = "";
        String stderr = "";
        boolean timedOut;
        boolean truncated;
        String command = "";

        JSONObject json() {
            JSONObject o = new JSONObject();
            try {
                o.put("ok", ok);
                o.put("stage", stage);
                o.put("error", error);
                o.put("exitCode", exitCode);
                o.put("stdout", stdout);
                o.put("stderr", stderr);
                o.put("timedOut", timedOut);
                o.put("stdoutTruncated", truncated);
                o.put("command", command);
            } catch (Throwable ignored) {}
            return o;
        }
    }

    private static volatile boolean warmedUp;
    private static volatile long lastExecAt;

    /**
     * Pays Shizuku's one-off user-service startup cost in the background.
     *
     * The very first {@code newProcess} call in a fresh app process can block for a long time while
     * Shizuku spins up its shell user service. Doing that lazily on the first real command makes
     * that command look hung, so the module triggers this as soon as its process starts.
     */
    static void warmUp() {
        if (warmedUp) return;
        warmedUp = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { exec("id", 120000L, null); } catch (Throwable ignored) {}
            }
        }, "yb-shizuku-warmup");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Keeps the privileged shell reachable while work is in flight.
     *
     * Shizuku tears its shell process down (and the ROM may freeze it) once idle, so the command
     * after a quiet period can block for tens of seconds. Re-running a trivial command while the
     * bridge is actively serving that cost away from the next real command.
     */
    static void touch() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastExecAt < 60000L) return;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { exec("true", 45000L, null); } catch (Throwable ignored) {}
            }
        }, "yb-shizuku-touch");
        t.setDaemon(true);
        t.start();
    }

    static Result exec(String command, long timeoutMs, String cwd) {
        return run(command, null, timeoutMs, cwd);
    }

    static Result execWithInput(String command, byte[] stdin, long timeoutMs, String cwd) {
        return run(command, stdin, timeoutMs, cwd);
    }

    private static String unavailableReason() {
        try {
            JSONObject s = status();
            String reason = s.optString("reason", "");
            return reason.isEmpty() ? "Shizuku 不可用" : reason;
        } catch (Throwable ignored) { return "Shizuku 不可用"; }
    }

    private static Result run(String command, byte[] stdin, long timeoutMs, String cwd) {
        lastExecAt = android.os.SystemClock.elapsedRealtime();
        Result r = new Result();
        r.command = command == null ? "" : command;
        if (command == null || command.trim().isEmpty()) {
            r.stage = "EMPTY_COMMAND";
            r.error = "command 不能为空";
            return r;
        }
        moe.shizuku.server.IShizukuService svc = service(6000L);
        if (svc == null) {
            r.stage = "SHIZUKU_UNAVAILABLE";
            r.error = unavailableReason();
            return r;
        }
        StringBuilder why = new StringBuilder();
        if (!grantCheck(why)) {
            r.stage = "SHIZUKU_PERMISSION_DENIED";
            r.error = why.toString();
            return r;
        }

        moe.shizuku.server.IRemoteProcess rp;
        try {
            rp = svc.newProcess(new String[]{"/system/bin/sh", "-c", command}, null,
                    (cwd == null || cwd.trim().isEmpty()) ? null : cwd);
        } catch (Throwable t) {
            r.stage = "NEW_PROCESS_FAILED";
            r.error = "Shizuku newProcess 失败: " + t;
            return r;
        }
        if (rp == null) {
            r.stage = "NEW_PROCESS_NULL";
            r.error = "Shizuku 返回了空进程句柄（服务可能已停止）";
            return r;
        }

        final int cap = 4 * 1024 * 1024;
        final boolean[] trunc = new boolean[1];
        ByteArrayOutputStream ob = new ByteArrayOutputStream();
        ByteArrayOutputStream eb = new ByteArrayOutputStream();
        InputStream out = null, err = null;
        OutputStream in = null;
        Thread to = null, te = null;
        try {
            out = new ParcelFileDescriptor.AutoCloseInputStream(rp.getInputStream());
            err = new ParcelFileDescriptor.AutoCloseInputStream(rp.getErrorStream());
            to = drain(out, ob, cap, trunc);
            te = drain(err, eb, cap, trunc);
            try {
                in = new ParcelFileDescriptor.AutoCloseOutputStream(rp.getOutputStream());
                if (stdin != null && stdin.length > 0) { in.write(stdin); in.flush(); }
                in.close();
                in = null;
            } catch (Throwable ignored) {}

            boolean exited;
            try {
                exited = rp.waitForTimeout(Math.max(200L, timeoutMs), TimeUnit.MILLISECONDS.name());
            } catch (Throwable t) {
                exited = false;
            }
            if (!exited) {
                r.timedOut = true;
                try { rp.destroy(); } catch (Throwable ignored) {}
            }
            join(to, 1500L);
            join(te, 800L);
            try { r.exitCode = rp.exitValue(); } catch (Throwable ignored) { r.exitCode = -1; }
        } catch (Throwable t) {
            r.stage = "IO_FAILED";
            r.error = "读取 Shizuku 进程输出失败: " + t;
            try { rp.destroy(); } catch (Throwable ignored) {}
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            closeQuietly(err);
            if (to != null) to.interrupt();
            if (te != null) te.interrupt();
        }

        r.stdout = new String(ob.toByteArray(), StandardCharsets.UTF_8);
        r.stderr = new String(eb.toByteArray(), StandardCharsets.UTF_8);
        r.truncated = trunc[0];
        if (r.stage.isEmpty()) r.stage = r.timedOut ? "TIMEOUT" : "COMPLETED";
        r.ok = !r.timedOut && r.stage.equals("COMPLETED") && r.exitCode == 0;
        if (!r.ok && r.error.isEmpty()) {
            if (r.timedOut) r.error = "命令超时（" + timeoutMs + "ms），已终止";
            else if (r.exitCode > 0) r.error = "命令退出码 " + r.exitCode + (r.stderr.isEmpty() ? "" : "：" + tail(r.stderr, 800));
        }
        return r;
    }

    private static Thread drain(final InputStream in, final ByteArrayOutputStream sink,
                                final int cap, final boolean[] truncated) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[16 * 1024];
            int n;
            try {
                while ((n = in.read(buf)) > 0) {
                    if (sink.size() >= cap) { truncated[0] = true; continue; }
                    sink.write(buf, 0, Math.min(n, cap - sink.size()));
                }
            } catch (Throwable ignored) {}
        }, "YB-shizuku-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void join(Thread t, long ms) {
        if (t == null) return;
        try { t.join(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Throwable ignored) {}
    }

    private static String tail(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(s.length() - n);
    }

    /** POSIX single-quote escaping so user paths can never break out of the shell command. */
    static String q(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // ------------------------------------------------------------------ filesystem

    private static final String M_NOT_EXISTS = "__YB_NOT_EXISTS__";
    private static final String M_NOT_DIR = "__YB_NOT_DIR__";
    private static final String M_NOT_FILE = "__YB_NOT_FILE__";
    private static final String M_SIZE_PREFIX = "__YB_SIZE__";

    static JSONObject listFiles(String path, boolean recursive, int maxDepth, int limit, String glob) {
        JSONObject o = new JSONObject();
        JSONArray items = new JSONArray();
        try {
            o.put("path", path == null ? "" : path);
            if (path == null || path.trim().isEmpty()) return fail(o, items, "path 不能为空");
            int depth = Math.max(1, Math.min(12, recursive ? maxDepth : 1));
            int cap = Math.max(1, Math.min(2000, limit));
            String g = glob == null ? "" : glob.trim();

            StringBuilder cmd = new StringBuilder();
            cmd.append("d=").append(q(path)).append("; ");
            cmd.append("if [ ! -e \"$d\" ]; then echo ").append(M_NOT_EXISTS).append("; exit 0; fi; ");
            cmd.append("if [ ! -d \"$d\" ]; then echo ").append(M_NOT_DIR).append("; exit 0; fi; ");
            cmd.append("find \"$d\" -mindepth 1 -maxdepth ").append(depth);
            if (!g.isEmpty()) cmd.append(" -iname ").append(q(g));
            cmd.append(" 2>/dev/null | head -n ").append(cap);
            cmd.append(" | while IFS= read -r f; do ");
            cmd.append("if [ -d \"$f\" ]; then t=d; else t=f; fi; ");
            cmd.append("stat -c \"$t|%s|%Y|%A|%n\" \"$f\" 2>/dev/null || echo \"$t|0|0|?|$f\"; done");

            Result r = exec(cmd.toString(), 45000L, null);
            if (!r.ok) {
                JSONObject direct = directList(path, depth, cap, g);
                if (direct.optBoolean("ok", false)) return direct;
                o.put("count", 0).put("items", items);
                o.put("ok", false).put("stage", r.stage).put("error", r.error);
                o.put("directFallback", direct.optString("error", ""));
                return o;
            }

            String stdout = r.stdout == null ? "" : r.stdout;
            if (stdout.contains(M_NOT_EXISTS)) return fail(o, items, "路径不存在: " + path);
            if (stdout.contains(M_NOT_DIR)) return fail(o, items, "不是目录: " + path);
            int dirs = 0;
            for (String line : stdout.split("\n")) {
                if (line.isEmpty()) continue;
                String[] f = split5(line);
                if (f == null) continue;
                boolean isDir = "d".equals(f[0]);
                if (isDir) dirs++;
                JSONObject it = new JSONObject();
                it.put("name", f[4]);
                it.put("path", f[4]);
                it.put("isDir", isDir);
                it.put("size", parseLong(f[1]));
                it.put("mtime", parseLong(f[2]) * 1000L);
                it.put("mode", f[3]);
                items.put(it);
            }
            o.put("ok", true).put("count", items.length()).put("items", items);
            o.put("directoryCount", dirs);
            o.put("truncated", items.length() >= cap || r.truncated);
            o.put("source", "shizuku-shell");
            return o;
        } catch (Throwable t) {
            return fail(o, items, "列出目录失败: " + t);
        }
    }

    static JSONObject stat(String path) {
        JSONObject o = new JSONObject();
        try {
            o.put("path", path == null ? "" : path);
            if (path == null || path.trim().isEmpty()) return fail(o, null, "path 不能为空");
            String cmd = "p=" + q(path) + "; "
                    + "if [ ! -e \"$p\" ]; then echo " + M_NOT_EXISTS + "; exit 0; fi; "
                    + "if [ -d \"$p\" ]; then t=d; else t=f; fi; "
                    + "stat -c \"$t|%s|%Y|%A|%U|%G|%n\" \"$p\"";
            Result r = exec(cmd, 20000L, null);
            if (!r.ok) {
                JSONObject direct = directStat(path);
                if (direct.optBoolean("ok", false)) return direct;
                return fail(o, null, r.error);
            }
            String stdout = r.stdout == null ? "" : r.stdout;
            if (stdout.contains(M_NOT_EXISTS)) {
                return o.put("ok", true).put("exists", false).put("path", path);
            }
            String line = stdout.trim();
            int nl = line.indexOf('\n');
            if (nl > 0) line = line.substring(0, nl);
            String[] f = line.split("\\|", 7);
            if (f.length < 7) return fail(o, null, "无法解析 stat 输出: " + line);
            o.put("ok", true).put("exists", true);
            o.put("isDir", "d".equals(f[0])).put("isFile", "f".equals(f[0]));
            o.put("size", parseLong(f[1]));
            o.put("mtime", parseLong(f[2]) * 1000L);
            o.put("mode", f[3]).put("owner", f[4]).put("group", f[5]).put("name", f[6]);
            o.put("source", "shizuku-shell");
            return o;
        } catch (Throwable t) {
            return fail(o, null, "读取属性失败: " + t);
        }
    }

    /** Reads at most {@link #MAX_PAYLOAD}/2 raw bytes; the caller pages with offset. */
    static JSONObject readChunk(String path, long offset, int length) {
        JSONObject o = new JSONObject();
        try {
            o.put("path", path == null ? "" : path);
            if (path == null || path.trim().isEmpty()) return fail(o, null, "path 不能为空");
            long off = Math.max(0L, offset);
            int len = Math.max(1, Math.min(256 * 1024, length <= 0 ? 256 * 1024 : length));
            String cmd = "p=" + q(path) + "; "
                    + "if [ ! -f \"$p\" ]; then echo " + M_NOT_FILE + " >&2; exit 3; fi; "
                    + "echo \"" + M_SIZE_PREFIX + "$(stat -c %s \"$p\")\"; "
                    + "tail -c +" + (off + 1) + " \"$p\" | head -c " + len + " | base64";
            Result r = exec(cmd, 60000L, null);
            if (!r.ok) {
                JSONObject direct = directRead(path, off, len);
                if (direct.optBoolean("ok", false)) return direct;
                return fail(o, null, r.error);
            }
            String stdout = r.stdout == null ? "" : r.stdout;
            if (stdout.startsWith(M_NOT_FILE)) return fail(o, null, "不是普通文件: " + path);
            long total = -1L;
            int nl = stdout.indexOf('\n');
            String body = stdout;
            if (nl >= 0) {
                String head = stdout.substring(0, nl);
                if (head.startsWith(M_SIZE_PREFIX)) {
                    total = parseLong(head.substring(M_SIZE_PREFIX.length()));
                    body = stdout.substring(nl + 1);
                }
            }
            String b64 = body.replaceAll("\\s", "");
            byte[] data;
            try { data = Base64.decode(b64, Base64.DEFAULT); }
            catch (Throwable t) { return fail(o, null, "base64 解码失败: " + t); }
            o.put("ok", true);
            o.put("totalSize", total);
            o.put("offset", off);
            o.put("length", data.length);
            o.put("eof", total >= 0 && off + data.length >= total);
            o.put("base64", b64);
            o.put("sha256", sha256(data));
            o.put("source", "shizuku-shell");
            return o;
        } catch (Throwable t) {
            return fail(o, null, "读取文件失败: " + t);
        }
    }

    static JSONObject writeFile(String path, byte[] data, boolean append, boolean mkdirs) {
        JSONObject o = new JSONObject();
        try {
            o.put("path", path == null ? "" : path);
            if (path == null || path.trim().isEmpty()) return fail(o, null, "path 不能为空");
            if (data == null) data = new byte[0];
            if (data.length > 32 * 1024 * 1024) return fail(o, null, "单次写入上限 32MB，请分片");
            String cmd;
            if (data.length == 0 && !append) {
                cmd = "p=" + q(path) + "; " + (mkdirs ? "mkdir -p \"$(dirname \"$p\")\"; " : "") + ": > \"$p\"";
            } else {
                cmd = "p=" + q(path) + "; " + (mkdirs ? "mkdir -p \"$(dirname \"$p\")\"; " : "")
                        + "base64 -d " + (append ? ">>" : ">") + " \"$p\"";
            }
            byte[] stdin = data.length == 0 ? null : Base64.encodeToString(data, Base64.NO_WRAP).getBytes(StandardCharsets.UTF_8);
            Result r = execWithInput(cmd, stdin, 120000L, null);
            if (!r.ok) {
                JSONObject direct = directWrite(path, data, append, mkdirs);
                if (direct.optBoolean("ok", false)) return direct;
                return fail(o, null, r.error);
            }
            o.put("ok", true).put("bytesWritten", data.length).put("append", append);
            o.put("sha256", sha256(data));
            o.put("source", "shizuku-shell");
            return o;
        } catch (Throwable t) {
            return fail(o, null, "写入文件失败: " + t);
        }
    }

    static JSONObject makeDirs(String path) {
        JSONObject o = new JSONObject();
        try {
            o.put("path", path == null ? "" : path);
            if (path == null || path.trim().isEmpty()) return fail(o, null, "path 不能为空");
            Result r = exec("mkdir -p " + q(path) + " && echo __YB_MKDIR_OK__", 20000L, null);
            if (!r.ok || !String.valueOf(r.stdout).contains("__YB_MKDIR_OK__")) {
                JSONObject direct = directMakeDirs(path);
                if (direct.optBoolean("ok", false)) return direct;
                return fail(o, null, r.error.isEmpty() ? "mkdir 失败" : r.error);
            }
            o.put("ok", true).put("created", true).put("source", "shizuku-shell");
            return o;
        } catch (Throwable t) {
            return fail(o, null, "创建目录失败: " + t);
        }
    }

    static JSONObject delete(String path, boolean recursive) {
        JSONObject o = new JSONObject();
        try {
            o.put("path", path == null ? "" : path);
            if (path == null || path.trim().isEmpty()) return fail(o, null, "path 不能为空");
            String p = path.trim();
            if ("/".equals(p) || "/sdcard".equals(p) || "/storage/emulated/0".equals(p)
                    || "/storage".equals(p) || "/data".equals(p) || "/system".equals(p)) {
                return fail(o, null, "拒绝删除该关键根路径: " + p);
            }
            String cmd = "p=" + q(p) + "; if [ ! -e \"$p\" ]; then echo " + M_NOT_EXISTS + "; exit 0; fi; "
                    + (recursive ? "rm -rf \"$p\"" : "rm -f \"$p\"") + " && echo __YB_RM_OK__";
            Result r = exec(cmd, 60000L, null);
            if (!r.ok) {
                JSONObject direct = directDelete(p, recursive);
                if (direct.optBoolean("ok", false)) return direct;
                return fail(o, null, r.error);
            }
            String stdout = String.valueOf(r.stdout);
            if (stdout.contains(M_NOT_EXISTS)) return o.put("ok", true).put("deleted", false).put("reason", "路径不存在");
            o.put("ok", true).put("deleted", true).put("recursive", recursive).put("source", "shizuku-shell");
            return o;
        } catch (Throwable t) {
            return fail(o, null, "删除失败: " + t);
        }
    }

    // ------------------------------------------------------------------ direct fallback

    private static JSONObject directList(String path, int depth, int cap, String glob) {
        JSONObject o = new JSONObject();
        JSONArray items = new JSONArray();
        try {
            if (!allFilesAccess()) return fail(o, items, "模块进程没有“所有文件访问权限”，且 Shizuku 不可用");
            File root = new File(path);
            if (!root.exists()) return fail(o, items, "路径不存在: " + path);
            if (!root.isDirectory()) return fail(o, items, "不是目录: " + path);
            List<File> queue = new ArrayList<>();
            queue.add(root);
            int level = 0;
            String pattern = glob == null || glob.isEmpty() ? null : glob.toLowerCase(java.util.Locale.ROOT);
            while (!queue.isEmpty() && level < depth) {
                List<File> next = new ArrayList<>();
                for (File dir : queue) {
                    File[] children = dir.listFiles();
                    if (children == null) continue;
                    for (File c : children) {
                        if (pattern != null && !matchesGlob(c.getName().toLowerCase(java.util.Locale.ROOT), pattern)) {
                            if (c.isDirectory()) next.add(c);
                            continue;
                        }
                        if (items.length() >= cap) break;
                        JSONObject it = new JSONObject();
                        it.put("name", c.getAbsolutePath()).put("path", c.getAbsolutePath());
                        it.put("isDir", c.isDirectory()).put("size", c.length()).put("mtime", c.lastModified());
                        it.put("mode", "");
                        items.put(it);
                        if (c.isDirectory()) next.add(c);
                    }
                }
                queue = next;
                level++;
            }
            o.put("ok", true).put("count", items.length()).put("items", items).put("source", "module-direct-fs");
            return o;
        } catch (Throwable t) {
            return fail(o, items, "直接枚举失败: " + t);
        }
    }

    private static JSONObject directStat(String path) {
        JSONObject o = new JSONObject();
        try {
            File f = new File(path);
            if (!allFilesAccess()) return fail(o, null, "模块进程没有“所有文件访问权限”，且 Shizuku 不可用");
            if (!f.exists()) return o.put("ok", true).put("exists", false).put("path", path);
            return o.put("ok", true).put("exists", true).put("isDir", f.isDirectory())
                    .put("isFile", f.isFile()).put("size", f.length()).put("mtime", f.lastModified())
                    .put("name", f.getName()).put("source", "module-direct-fs");
        } catch (Throwable t) {
            return fail(o, null, "直接读取属性失败: " + t);
        }
    }

    private static JSONObject directRead(String path, long offset, int len) {
        JSONObject o = new JSONObject();
        try {
            if (!allFilesAccess()) return fail(o, null, "模块进程没有“所有文件访问权限”，且 Shizuku 不可用");
            File f = new File(path);
            if (!f.isFile()) return fail(o, null, "不是普通文件: " + path);
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[len];
            try {
                long skipped = 0;
                while (skipped < offset) {
                    long s = in.skip(offset - skipped);
                    if (s <= 0) break;
                    skipped += s;
                }
                int n = 0, r;
                while (n < len && (r = in.read(buf, n, len - n)) > 0) n += r;
                byte[] data = new byte[n];
                System.arraycopy(buf, 0, data, 0, n);
                o.put("ok", true).put("totalSize", f.length()).put("offset", offset).put("length", n);
                o.put("eof", offset + n >= f.length());
                o.put("base64", Base64.encodeToString(data, Base64.NO_WRAP)).put("source", "module-direct-fs");
                return o;
            } finally {
                closeQuietly(in);
            }
        } catch (Throwable t) {
            return fail(o, null, "直接读取失败: " + t);
        }
    }

    private static JSONObject directWrite(String path, byte[] data, boolean append, boolean mkdirs) {
        JSONObject o = new JSONObject();
        try {
            if (!allFilesAccess()) return fail(o, null, "模块进程没有“所有文件访问权限”，且 Shizuku 不可用");
            File f = new File(path);
            if (mkdirs) {
                File parent = f.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) return fail(o, null, "无法创建父目录: " + parent);
            }
            FileOutputStream out = new FileOutputStream(f, append);
            try { out.write(data); out.flush(); } finally { closeQuietly(out); }
            return o.put("ok", true).put("bytesWritten", data.length).put("append", append).put("source", "module-direct-fs");
        } catch (Throwable t) {
            return fail(o, null, "直接写入失败: " + t);
        }
    }

    private static JSONObject directMakeDirs(String path) {
        JSONObject o = new JSONObject();
        try {
            if (!allFilesAccess()) return fail(o, null, "模块进程没有“所有文件访问权限”，且 Shizuku 不可用");
            File d = new File(path);
            boolean created = d.exists() || d.mkdirs();
            return o.put("ok", created).put("created", created).put("source", "module-direct-fs");
        } catch (Throwable t) {
            return fail(o, null, "直接创建目录失败: " + t);
        }
    }

    private static JSONObject directDelete(String path, boolean recursive) {
        JSONObject o = new JSONObject();
        try {
            if (!allFilesAccess()) return fail(o, null, "模块进程没有“所有文件访问权限”，且 Shizuku 不可用");
            File f = new File(path);
            if (!f.exists()) return o.put("ok", true).put("deleted", false).put("reason", "路径不存在");
            if (f.isDirectory() && !recursive) return fail(o, null, "是目录，需 recursive=true");
            boolean done = deleteRecursively(f);
            return o.put("ok", done).put("deleted", done).put("source", "module-direct-fs");
        } catch (Throwable t) {
            return fail(o, null, "直接删除失败: " + t);
        }
    }

    private static boolean deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File x : c) deleteRecursively(x);
        }
        return f.delete();
    }

    // ------------------------------------------------------------------ small helpers

    private static JSONObject fail(JSONObject o, JSONArray items, String message) {
        try {
            o.put("ok", false);
            o.put("error", message == null ? "" : message);
            if (items != null) { o.put("count", 0); o.put("items", items); }
        } catch (Throwable ignored) {}
        return o;
    }

    private static String[] split5(String line) {
        if (line == null || line.isEmpty()) return null;
        int a = line.indexOf('|');
        if (a < 0) return null;
        int b = line.indexOf('|', a + 1);
        if (b < 0) return null;
        int c = line.indexOf('|', b + 1);
        if (c < 0) return null;
        int d = line.indexOf('|', c + 1);
        if (d < 0) return null;
        return new String[]{line.substring(0, a), line.substring(a + 1, b),
                line.substring(b + 1, c), line.substring(c + 1, d), line.substring(d + 1)};
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Throwable ignored) { return -1L; }
    }

    private static boolean matchesGlob(String name, String pattern) {
        StringBuilder re = new StringBuilder();
        for (char ch : pattern.toCharArray()) {
            if (ch == '*') re.append(".*");
            else if (ch == '?') re.append('.');
            else if ("\\.[]{}()+-^$|".indexOf(ch) >= 0) re.append('\\').append(ch);
            else re.append(ch);
        }
        try { return name.matches(re.toString()); } catch (Throwable ignored) { return false; }
    }

    static String sha256(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Throwable ignored) { return ""; }
    }

    /** Convenience for other module-process components that need file bytes. */
    static byte[] readAllQuietly(String path) {
        try {
            File f = new File(path);
            if (!f.isFile()) return null;
            long len = f.length();
            if (len > 24L * 1024 * 1024) return null;
            byte[] buf = new byte[(int) len];
            FileInputStream in = new FileInputStream(f);
            try {
                int n = 0, r;
                while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
                if (n != buf.length) return null;
                return buf;
            } finally { closeQuietly(in); }
        } catch (Throwable ignored) { return null; }
    }

    /** Set of shared-storage roots worth probing, with their readability from the module UID. */
    static JSONArray probeStorageRoots() {
        JSONArray arr = new JSONArray();
        String[] roots = new String[]{
                "/storage/emulated/0", "/sdcard", "/storage/emulated/0/MT2/mcp",
                "/sdcard/Download", "/storage/emulated/0/Android/media", "/data/local/tmp"};
        for (String p : roots) {
            try {
                JSONObject e = stat(p);
                e.put("root", p);
                arr.put(e);
            } catch (Throwable ignored) {}
        }
        return arr;
    }
}

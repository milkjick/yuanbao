package com.example.yuanbaossehook.daemon;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Operations served by the standalone shell daemon (uid 2000).
 *
 * CONTEXT NOTE
 * ------------
 * This code runs inside a bare {@code app_process} started as uid 2000 (shell) by Shizuku - it is
 * NOT an Android application process. Nothing here may touch a Context, SharedPreferences, SAF or
 * the Shizuku API: only plain Java plus framework classes that work without an application context
 * (File/Process/org.json/android.util.Base64).
 *
 * That is the whole point of the daemon: because the process itself already runs as shell, one
 * "shell" request costs a single fork/exec and zero Binder round trips, and it keeps working even
 * when the module app - or Shizuku itself - is gone.
 *
 * The JSON shapes mirror the module-process bridge (BridgeOps/ShizukuShell) so the host parses
 * either backend the same way; only {@code source} differs.
 */
final class DaemonOps {

    /** Methods this daemon can serve; anything else is refused with unsupportedBy=daemon. */
    private static final String[] SUPPORTED = {
            "ping", "daemon_status", "shell", "fs_list", "fs_stat", "fs_read",
            "fs_write", "fs_mkdir", "fs_delete", "scan", "storage_status", "daemon_stop"
    };

    private static final int MAX_STDOUT = 4 * 1024 * 1024;
    private static final int MAX_STDERR = 512 * 1024;
    private static final String SOURCE = "yb-daemon(uid2000-shell)";

    private DaemonOps() {}

    static boolean supports(String method) {
        if (method == null) return false;
        for (String m : SUPPORTED) if (m.equals(method)) return true;
        return false;
    }

    static JSONObject dispatch(String method, JSONObject extras, DaemonServer server) {
        try {
            String m = method == null ? "" : method.trim();
            if (m.isEmpty()) return err("method 不能为空");
            if ("ping".equals(m)) return ping();
            if ("daemon_status".equals(m)) return ping();
            if ("storage_status".equals(m)) return storageStatus();
            if ("shell".equals(m)) return shell(extras);
            if ("fs_list".equals(m)) return fsList(extras);
            if ("fs_stat".equals(m)) return fsStat(extras);
            if ("fs_read".equals(m)) return fsRead(extras);
            if ("fs_write".equals(m)) return fsWrite(extras);
            if ("fs_mkdir".equals(m)) return fsMkdir(extras);
            if ("fs_delete".equals(m)) return fsDelete(extras);
            if ("scan".equals(m)) return scan(extras);
            if ("daemon_stop".equals(m)) return server != null ? server.requestStop() : err("no server");
            return new JSONObject().put("ok", false).put("unsupportedBy", "daemon")
                    .put("error", "守护进程不支持该方法（请回落到模块桥接）: " + m);
        } catch (Throwable t) {
            return err("daemon method " + method + " 失败: " + t);
        }
    }

    // ------------------------------------------------------------------ status

    static JSONObject ping() {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true).put("pong", true);
            o.put("via", "daemon-socket");
            o.put("execMode", "shell-native");
            o.put("source", SOURCE);
            o.put("pid", android.os.Process.myPid());
            o.put("uid", android.os.Process.myUid());
            o.put("uidIsShell", android.os.Process.myUid() == 2000);
            // The daemon needs no Shizuku grant: it IS the shell. Reported for diagnostics parity.
            o.put("shizukuGranted", false);
            o.put("shizukuNotNeeded", true);
            o.put("safMounted", false);
            o.put("port", DaemonServer.boundPort());
            o.put("uptimeMs", DaemonServer.uptimeMs());
            o.put("served", DaemonServer.servedCount());
            o.put("rejected", DaemonServer.rejectedCount());
            // Lets the module detect a daemon left over from an older APK and restart it on new code.
            o.put("codeVersion", DaemonServer.codeVersion());
            // Real latency of the last served call: the whole point of the daemon is that a tool
            // call costs one loopback round trip instead of a Shizuku shell round trip per hop.
            o.put("lastMethod", DaemonServer.lastMethod());
            o.put("lastTookMs", DaemonServer.lastTookMs());
            o.put("ts", System.currentTimeMillis());
        } catch (Throwable ignored) {}
        return o;
    }

    private static JSONObject storageStatus() {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("via", "daemon-socket");
            o.put("execMode", "shell-native");
            o.put("source", SOURCE);
            o.put("daemonPid", android.os.Process.myPid());
            o.put("daemonUid", android.os.Process.myUid());
            o.put("moduleUid", android.os.Process.myUid());
            o.put("modulePid", android.os.Process.myPid());
            o.put("moduleAllFilesAccess", true);
            JSONArray roots = new JSONArray();
            for (String r : new String[]{"/storage/emulated/0", "/sdcard", "/data/local/tmp", "/data/media/0", "/system"}) {
                JSONObject e = new JSONObject();
                File f = new File(r);
                e.put("path", r);
                e.put("exists", f.exists());
                e.put("readable", f.canRead());
                e.put("writable", f.canWrite());
                try { e.put("usableBytes", f.getUsableSpace()); } catch (Throwable ignored) {}
                roots.put(e);
            }
            o.put("roots", roots);
            o.put("shizuku", new JSONObject().put("ok", true).put("permissionGranted", false)
                    .put("notNeeded", true).put("note", "守护进程本身即 uid 2000，无需 Shizuku 授权"));
            return o;
        } catch (Throwable t) {
            return err("storage_status 失败: " + t);
        }
    }

    // ------------------------------------------------------------------ shell

    private static JSONObject shell(JSONObject e) {
        JSONObject o = new JSONObject();
        String cmd = e == null ? "" : e.optString("command", "");
        try {
            o.put("command", cmd);
            if (cmd.trim().isEmpty()) {
                return o.put("ok", false).put("error", "command 不能为空").put("stage", "BAD_REQUEST");
            }
            long timeout = e.optLong("timeoutMs", 30000L);
            timeout = Math.max(1000L, Math.min(600000L, timeout));
            String cwd = e.optString("cwd", "");

            List<String> argv = new ArrayList<>();
            argv.add("/system/bin/sh");
            argv.add("-c");
            argv.add(cmd);
            ProcessBuilder pb = new ProcessBuilder(argv);
            java.util.Map<String, String> env = pb.environment();
            env.put("PATH", "/system/bin:/system/xbin:/vendor/bin:/product/bin:/data/local/tmp");
            env.put("HOME", "/data/local/tmp");
            env.put("TMPDIR", "/data/local/tmp");
            env.put("ANDROID_DATA", "/data");
            if (cwd != null && !cwd.trim().isEmpty()) {
                File d = new File(cwd.trim());
                if (d.isDirectory()) pb.directory(d);
            }
            Process p = pb.start();
            StreamReader outR = new StreamReader(p.getInputStream(), MAX_STDOUT);
            StreamReader errR = new StreamReader(p.getErrorStream(), MAX_STDERR);
            Thread to = new Thread(outR, "yb-daemon-stdout");
            Thread te = new Thread(errR, "yb-daemon-stderr");
            to.setDaemon(true);
            te.setDaemon(true);
            to.start();
            te.start();

            boolean finished = p.waitFor(timeout, TimeUnit.MILLISECONDS);
            boolean timedOut = !finished;
            if (timedOut) {
                try { p.destroy(); } catch (Throwable ignored) {}
                try { p.waitFor(2, TimeUnit.SECONDS); } catch (Throwable ignored) {}
                try { p.destroyForcibly(); } catch (Throwable ignored) {}
            }
            try { to.join(1500L); } catch (Throwable ignored) {}
            try { te.join(1500L); } catch (Throwable ignored) {}

            int exit = -1;
            try { exit = p.exitValue(); } catch (Throwable ignored) {}

            o.put("ok", true);
            o.put("stage", timedOut ? "TIMED_OUT" : "COMPLETED");
            o.put("error", timedOut ? ("命令超时 " + timeout + "ms 已终止") : "");
            o.put("exitCode", exit);
            o.put("stdout", outR.text());
            o.put("stderr", errR.text());
            o.put("timedOut", timedOut);
            o.put("stdoutTruncated", outR.truncated());
            o.put("source", SOURCE);
            o.put("uid", android.os.Process.myUid());
            return o;
        } catch (Throwable t) {
            try {
                return o.put("ok", false).put("stage", "FAILED").put("error", "守护进程执行失败: " + t)
                        .put("stdout", "").put("stderr", "").put("exitCode", -1)
                        .put("timedOut", false).put("stdoutTruncated", false);
            } catch (Throwable ignored) {
                return err("shell 失败: " + t);
            }
        }
    }

    /** Drains one process stream into a capped buffer. */
    private static final class StreamReader implements Runnable {
        private final InputStream in;
        private final int cap;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private volatile boolean truncated;

        StreamReader(InputStream in, int cap) { this.in = in; this.cap = cap; }

        @Override public void run() {
            byte[] tmp = new byte[16384];
            try {
                int n;
                while ((n = in.read(tmp)) > 0) {
                    if (buf.size() < cap) buf.write(tmp, 0, Math.min(n, cap - buf.size()));
                    else truncated = true;
                }
            } catch (Throwable ignored) {
            } finally {
                try { in.close(); } catch (Throwable ignored) {}
            }
        }

        String text() { return new String(buf.toByteArray(), StandardCharsets.UTF_8); }
        boolean truncated() { return truncated; }
    }

    // ------------------------------------------------------------------ filesystem

    /**
     * SAF documents and content URIs are owned by the module process (it holds the persisted tree
     * permission); the daemon has no Context and no URI access. Hand those calls back explicitly
     * instead of reporting a misleading "路径不存在".
     */
    private static JSONObject safHandOff(JSONObject o, String raw) {
        String p = raw == null ? "" : raw.trim();
        if (p.startsWith("saf://") || p.startsWith("saf:/") || p.startsWith("content://")
                || p.startsWith("file://")) {
            try {
                o.put("ok", false);
                o.put("unsupportedBy", "daemon");
                o.put("error", "SAF/URI 路径需由模块进程处理（守护进程无 Context）: " + p);
            } catch (Throwable ignored) {}
            return o;
        }
        return null;
    }

    private static JSONObject fsList(JSONObject e) {
        JSONObject o = new JSONObject();
        try {
            String raw = e == null ? "" : e.optString("path", "");
            o.put("path", raw);
            if (raw.trim().isEmpty()) return fail(o, "path 不能为空");
            { JSONObject hand = safHandOff(o, raw); if (hand != null) return hand; }
            File root = new File(raw.trim());
            if (!root.exists()) return fail(o, "路径不存在: " + raw);
            if (!root.isDirectory()) return fail(o, "不是目录: " + raw);
            boolean recursive = e.optBoolean("recursive", true);
            int maxDepth = Math.max(1, Math.min(12, e.optInt("maxDepth", 3)));
            int limit = Math.max(1, Math.min(2000, e.optInt("limit", 200)));
            String glob = e.optString("glob", "");

            JSONArray items = new JSONArray();
            ArrayDeque<File> queue = new ArrayDeque<>();
            ArrayDeque<Integer> depths = new ArrayDeque<>();
            queue.add(root);
            depths.add(1);
            int dirs = 0;
            int listNull = 0;
            boolean truncated = false;
            while (!queue.isEmpty()) {
                File dir = queue.poll();
                int depth = depths.poll();
                File[] children = dir.listFiles();
                if (children == null) { listNull++; continue; }
                Arrays.sort(children);
                for (File c : children) {
                    if (items.length() >= limit) { truncated = true; break; }
                    if (!matchesGlob(c.getName(), glob)) continue;
                    JSONObject it = new JSONObject();
                    it.put("name", c.getName());
                    it.put("path", c.getAbsolutePath());
                    it.put("isDir", c.isDirectory());
                    it.put("size", c.length());
                    it.put("mtime", c.lastModified());
                    it.put("mode", mode(c));
                    items.put(it);
                    if (c.isDirectory() && recursive && depth < maxDepth) {
                        dirs++;
                        queue.add(c);
                        depths.add(depth + 1);
                    }
                }
                if (items.length() >= limit) { truncated = true; break; }
            }
            o.put("ok", true);
            o.put("count", items.length());
            o.put("items", items);
            o.put("directoryCount", dirs);
            o.put("truncated", truncated);
            o.put("listFilesNull", listNull);
            o.put("source", SOURCE);
            o.put("uid", android.os.Process.myUid());
            return o;
        } catch (Throwable t) {
            return err("fs_list 失败: " + t);
        }
    }

    private static JSONObject fsStat(JSONObject e) {
        JSONObject o = new JSONObject();
        try {
            String raw = e == null ? "" : e.optString("path", "").trim();
            o.put("path", raw);
            if (raw.isEmpty()) return fail(o, "path 不能为空");
            { JSONObject hand = safHandOff(o, raw); if (hand != null) return hand; }
            File f = new File(raw);
            if (!f.exists()) return o.put("ok", true).put("exists", false);
            o.put("ok", true).put("exists", true);
            o.put("isDir", f.isDirectory()).put("isFile", f.isFile());
            o.put("size", f.length());
            o.put("mtime", f.lastModified());
            o.put("mode", mode(f));
            o.put("owner", "").put("group", "");
            o.put("name", f.getName());
            o.put("canRead", f.canRead()).put("canWrite", f.canWrite());
            o.put("source", SOURCE);
            return o;
        } catch (Throwable t) {
            return err("fs_stat 失败: " + t);
        }
    }

    private static JSONObject fsRead(JSONObject e) {
        JSONObject o = new JSONObject();
        RandomAccessFile raf = null;
        try {
            String raw = e == null ? "" : e.optString("path", "").trim();
            o.put("path", raw);
            if (raw.isEmpty()) return fail(o, "path 不能为空");
            { JSONObject hand = safHandOff(o, raw); if (hand != null) return hand; }
            long offset = Math.max(0L, e.optLong("offset", 0L));
            int length = e.optInt("length", 256 * 1024);
            length = Math.max(1, Math.min(256 * 1024, length));
            File f = new File(raw);
            if (!f.exists()) return fail(o, "文件不存在: " + raw);
            if (f.isDirectory()) return fail(o, "不是普通文件: " + raw);
            long total = f.length();
            raf = new RandomAccessFile(f, "r");
            raf.seek(Math.min(offset, total));
            int toRead = (int) Math.min(length, Math.max(0L, total - offset));
            byte[] data = new byte[toRead];
            int read = 0;
            while (read < toRead) {
                int n = raf.read(data, read, toRead - read);
                if (n <= 0) break;
                read += n;
            }
            if (read != data.length) data = Arrays.copyOf(data, Math.max(0, read));
            String b64 = Base64.encodeToString(data, Base64.NO_WRAP);
            o.put("ok", true);
            o.put("totalSize", total);
            o.put("offset", offset);
            o.put("length", data.length);
            o.put("eof", offset + data.length >= total);
            o.put("base64", b64);
            o.put("sha256", sha256(data));
            o.put("source", SOURCE);
            return o;
        } catch (Throwable t) {
            return err("fs_read 失败: " + t);
        } finally {
            if (raf != null) try { raf.close(); } catch (Throwable ignored) {}
        }
    }

    private static JSONObject fsWrite(JSONObject e) {
        JSONObject o = new JSONObject();
        try {
            String raw = e == null ? "" : e.optString("path", "").trim();
            o.put("path", raw);
            if (raw.isEmpty()) return fail(o, "path 不能为空");
            { JSONObject hand = safHandOff(o, raw); if (hand != null) return hand; }
            String b64 = e.optString("base64", "");
            if (b64.isEmpty()) {
                b64 = Base64.encodeToString(e.optString("content", "").getBytes(StandardCharsets.UTF_8),
                        Base64.NO_WRAP);
            }
            byte[] data;
            try {
                data = Base64.decode(b64, Base64.DEFAULT);
            } catch (Throwable t) {
                return fail(o, "base64 解码失败: " + t);
            }
            boolean append = e.optBoolean("append", false);
            boolean mkdirs = e.optBoolean("mkdirs", true);
            File f = new File(raw);
            File parent = f.getParentFile();
            if (mkdirs && parent != null && !parent.exists() && !parent.mkdirs()) {
                return fail(o, "无法创建父目录: " + parent);
            }
            FileOutputStream fos = new FileOutputStream(f, append);
            try {
                fos.write(data);
                fos.flush();
            } finally {
                try { fos.close(); } catch (Throwable ignored) {}
            }
            o.put("ok", true).put("bytesWritten", data.length).put("append", append);
            o.put("sha256", sha256(data));
            o.put("source", SOURCE);
            return o;
        } catch (Throwable t) {
            return err("fs_write 失败: " + t);
        }
    }

    private static JSONObject fsMkdir(JSONObject e) {
        JSONObject o = new JSONObject();
        try {
            String raw = e == null ? "" : e.optString("path", "").trim();
            o.put("path", raw);
            if (raw.isEmpty()) return fail(o, "path 不能为空");
            { JSONObject hand = safHandOff(o, raw); if (hand != null) return hand; }
            File f = new File(raw);
            boolean created = f.isDirectory() || f.mkdirs();
            if (!created) return fail(o, "创建目录失败: " + raw);
            o.put("ok", true).put("created", true).put("source", SOURCE);
            return o;
        } catch (Throwable t) {
            return err("fs_mkdir 失败: " + t);
        }
    }

    private static JSONObject fsDelete(JSONObject e) {
        JSONObject o = new JSONObject();
        try {
            String raw = e == null ? "" : e.optString("path", "").trim();
            o.put("path", raw);
            if (raw.isEmpty()) return fail(o, "path 不能为空");
            { JSONObject hand = safHandOff(o, raw); if (hand != null) return hand; }
            boolean recursive = e.optBoolean("recursive", false);
            File f = new File(raw);
            if (!f.exists()) {
                return o.put("ok", true).put("deleted", false).put("reason", "路径不存在");
            }
            if (f.isDirectory() && !recursive) {
                String[] kids = f.list();
                if (kids != null && kids.length > 0) return fail(o, "目录非空且未指定 recursive=true");
            }
            if (!deleteRecursive(f)) return fail(o, "删除失败: " + raw);
            o.put("ok", true).put("deleted", true).put("recursive", recursive).put("source", SOURCE);
            return o;
        } catch (Throwable t) {
            return err("fs_delete 失败: " + t);
        }
    }

    private static boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) if (!deleteRecursive(k)) return false;
        }
        return f.delete();
    }

    // ------------------------------------------------------------------ apk scan

    /**
     * Lists *.apk files under a directory. Mirrors the module-process scanner's result shape so
     * {@code local_apk_discover} keeps working whichever backend answered.
     */
    private static JSONObject scan(JSONObject e) {
        JSONObject out = new JSONObject();
        JSONArray items = new JSONArray();
        int dirs = 0;
        int listNull = 0;
        int seen = 0;
        try {
            String directory = e == null ? "" : e.optString("directory", "");
            if (directory.trim().isEmpty()) directory = "/storage/emulated/0/MT2/mcp";
            { JSONObject hand = safHandOff(out, directory); if (hand != null) return hand; }
            boolean recursive = e.optBoolean("recursive", true);
            int maxDepth = Math.max(1, Math.min(12, e.optInt("maxDepth", 3)));
            int limit = Math.max(1, Math.min(2000, e.optInt("limit", 200)));
            File root = new File(directory.trim());
            out.put("directory", directory);
            if (!root.exists() || !root.isDirectory()) {
                out.put("ok", false).put("error", "目录不存在或不是目录: " + directory);
                out.put("count", 0).put("items", items).put("modulePermissionMissing", false);
                return out;
            }
            ArrayDeque<File> queue = new ArrayDeque<>();
            ArrayDeque<Integer> depths = new ArrayDeque<>();
            queue.add(root);
            depths.add(1);
            while (!queue.isEmpty() && items.length() < limit) {
                File dir = queue.poll();
                int depth = depths.poll();
                File[] children = dir.listFiles();
                if (children == null) { listNull++; continue; }
                Arrays.sort(children);
                for (File c : children) {
                    if (items.length() >= limit) break;
                    seen++;
                    if (c.isDirectory()) {
                        if (recursive && depth < maxDepth) { dirs++; queue.add(c); depths.add(depth + 1); }
                        continue;
                    }
                    if (!c.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) continue;
                    JSONObject it = new JSONObject();
                    it.put("path", c.getAbsolutePath());
                    it.put("name", c.getName());
                    it.put("sizeBytes", c.length());
                    it.put("lastModified", c.lastModified());
                    it.put("sourceType", "ANDROID_FILESYSTEM");
                    items.put(it);
                }
            }
            out.put("ok", true);
            out.put("sourceType", "SHELL_DAEMON_FILESYSTEM");
            out.put("count", items.length());
            out.put("items", items);
            out.put("modulePermissionMissing", false);
            out.put("scan", new JSONObject().put("listFilesNull", listNull)
                    .put("directoriesVisited", dirs).put("entriesSeen", seen));
            out.put("via", "daemon-socket");
            out.put("uid", android.os.Process.myUid());
            return out;
        } catch (Throwable t) {
            try {
                return out.put("ok", false).put("error", "守护进程 APK 扫描失败: " + t)
                        .put("count", items.length()).put("items", items)
                        .put("modulePermissionMissing", false);
            } catch (Throwable ignored) {
                return err("scan 失败: " + t);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static boolean matchesGlob(String name, String glob) {
        if (glob == null || glob.trim().isEmpty()) return true;
        try {
            StringBuilder re = new StringBuilder();
            for (char c : glob.toCharArray()) {
                if (c == '*') re.append(".*");
                else if (c == '?') re.append('.');
                else re.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
            return name.matches(re.toString());
        } catch (Throwable t) {
            return true;
        }
    }

    private static String mode(File f) {
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> perms =
                    java.nio.file.Files.getPosixFilePermissions(f.toPath());
            java.nio.file.attribute.PosixFilePermission[][] map = {
                    {java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE},
                    {java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                            java.nio.file.attribute.PosixFilePermission.GROUP_WRITE,
                            java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE},
                    {java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
                            java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE,
                            java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE},
            };
            char[] order = {'r', 'w', 'x'};
            StringBuilder sb = new StringBuilder(f.isDirectory() ? "d" : "-");
            for (java.nio.file.attribute.PosixFilePermission[] group : map) {
                for (int i = 0; i < 3; i++) sb.append(perms.contains(group[i]) ? order[i] : '-');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static JSONObject fail(JSONObject o, String message) {
        try {
            return o.put("ok", false).put("error", message).put("source", SOURCE);
        } catch (Throwable t) {
            return err(message);
        }
    }

    private static JSONObject err(String message) {
        JSONObject o = new JSONObject();
        try { o.put("ok", false).put("error", message); } catch (Throwable ignored) {}
        return o;
    }
}

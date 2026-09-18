package com.example.yuanbaossehook;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Every privileged bridge method, implemented exactly once and shared by both transports.
 *
 * Transport 1 - {@link ApkScanProvider} (Binder/ContentProvider).
 *     Only usable when the *caller* is allowed to see this package. YuanBao targets a recent
 *     API level and does not declare this module in its <queries>, so Android 11+ package
 *     visibility filtering rejects ContentResolver.call from inside YuanBao's process.
 *     This transport therefore stays as a fast path for adb/shell/self callers.
 *
 * Transport 2 - {@link BridgeServer} (127.0.0.1 TCP + per-install token).
 *     Package visibility does not apply to sockets, so this is the transport the injected
 *     YuanBao process actually uses.
 *
 * Path resolution order for every fs_* method:
 *   1. saf://default/<relative>                     -> SAF document tree
 *   2. an absolute path inside the mounted SAF tree -> SAF document tree
 *   3. anything else                                -> ShizukuShell (uid 2000 shell, then java.io.File)
 * Step 2 is what makes /storage/emulated/0/MT2/mcp work even when the user has not granted the
 * module "all files access" and Shizuku is unavailable, because the module already holds a
 * persistable SAF grant for that tree.
 *
 * No credential, cookie, token or signing key is ever returned by any method.
 */
final class BridgeOps {

    static final String YUANBAO = "com.tencent.hunyuan.app.chat";
    static final String SELF = "com.example.yuanbaossehook";
    private static final String TAG = "YB-MCP";

    private BridgeOps() {}

    static void log(String s) {
        try { android.util.Log.i(TAG, "[BRIDGE] " + s); } catch (Throwable ignored) {}
    }

    static JSONObject err(String message) {
        try { return new JSONObject().put("ok", false).put("error", message == null ? "" : message); }
        catch (Throwable t) { return new JSONObject(); }
    }

    static String errString(String message) {
        return err(message).toString();
    }

    // ------------------------------------------------------------------ caller authentication

    /**
     * Caller authentication for the Binder transport. Two independent, OS-supplied facts are used:
     * the package AMS recorded for the transaction (getCallingPackage()) and the kernel-supplied
     * uid (Binder.getCallingUid()) resolved through PackageManager. Neither can be forged by the
     * caller. Root (0) and the adb/Shizuku shell uid (2000) are accepted because they already hold
     * strictly greater privileges than this bridge grants.
     */
    static boolean callerAllowed(Context ctx, String callerPackage, int callerUid) {
        if (YUANBAO.equals(callerPackage) || SELF.equals(callerPackage)) return true;
        if (callerUid >= 0 && callerUid == android.os.Process.myUid()) return true;
        if (callerUid == 0 || callerUid == 2000) return true;
        if (callerUid == 1000 || callerUid < 0) {
            log("rejected caller: uid=" + callerUid + " pkg=" + callerPackage + " (system_server/unknown)");
            return false;
        }
        try {
            if (ctx != null) {
                String[] pkgs = ctx.getPackageManager().getPackagesForUid(callerUid);
                if (pkgs != null) {
                    for (String p : pkgs) if (YUANBAO.equals(p) || SELF.equals(p)) return true;
                }
            }
        } catch (Throwable t) {
            log("caller uid " + callerUid + " not resolvable: " + t);
        }
        log("rejected caller: uid=" + callerUid + " pkg=" + callerPackage);
        return false;
    }

    // ------------------------------------------------------------------ tolerant extras

    // The socket transport carries JSON, not a typed Bundle, so every accessor below accepts
    // Number/String/Boolean values instead of assuming one concrete type.

    static String str(Bundle b, String k, String d) {
        if (b == null) return d;
        Object o = b.get(k);
        if (o == null) return d;
        if (o instanceof String) return (String) o;
        if (o instanceof byte[]) return new String((byte[]) o);
        return String.valueOf(o);
    }

    static boolean bool(Bundle b, String k, boolean d) {
        if (b == null) return d;
        Object o = b.get(k);
        if (o == null) return d;
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Number) return ((Number) o).doubleValue() != 0d;
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return d;
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
    }

    static int i32(Bundle b, String k, int d) {
        if (b == null) return d;
        Object o = b.get(k);
        if (o == null) return d;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Throwable ignored) { return d; }
    }

    static long i64(Bundle b, String k, long d) {
        if (b == null) return d;
        Object o = b.get(k);
        if (o == null) return d;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o).trim()); } catch (Throwable ignored) { return d; }
    }

    static byte[] bytes(Bundle b, String k) {
        if (b == null) return new byte[0];
        Object o = b.get(k);
        if (o instanceof byte[]) return (byte[]) o;
        String s = str(b, k, "");
        if (s.isEmpty()) return new byte[0];
        try { return android.util.Base64.decode(s, android.util.Base64.DEFAULT); } catch (Throwable ignored) { return new byte[0]; }
    }

    // ------------------------------------------------------------------ SAF helpers

    static boolean isSafPath(String raw) {
        return raw != null && (raw.startsWith("saf://") || raw.startsWith("saf:/"));
    }

    static String safRelative(String raw) throws java.io.IOException {
        String p = raw == null ? "" : raw.trim();
        if (p.startsWith("saf://default/")) return p.substring("saf://default/".length());
        if (p.equals("saf://default") || p.equals("saf:/default")) return "";
        if (p.startsWith("saf:/")) return p.substring("saf:/".length());
        throw new java.io.IOException("只支持 saf://default/<relative> 工作区路径");
    }

    /** Absolute shared-storage path of the mounted SAF tree, e.g. /storage/emulated/0/MT2/mcp. */
    static String safRootAbsolute(Context c) {
        try {
            Uri tree = SafStorage.tree(c);
            if (tree == null) return "";
            String id = DocumentsContract.getTreeDocumentId(tree);
            int colon = id.indexOf(':');
            String rel = colon >= 0 ? id.substring(colon + 1) : id;
            while (rel.startsWith("/")) rel = rel.substring(1);
            return rel.isEmpty() ? "" : "/storage/emulated/0/" + rel;
        } catch (Throwable t) {
            return "";
        }
    }

    /** Maps an absolute path inside the mounted SAF tree to a tree-relative path, or null. */
    static String safRelativeForAbsolute(Context c, String path) {
        if (path == null) return null;
        String p = path.trim();
        if (p.isEmpty()) return null;
        String root = safRootAbsolute(c);
        if (root.isEmpty()) return null;
        String[] roots = new String[]{root, root.replace("/storage/emulated/0", "/sdcard")};
        for (String r : roots) {
            if (p.equals(r)) return "";
            if (p.startsWith(r + "/")) return p.substring(r.length() + 1);
        }
        return null;
    }

    /** SAF path for an absolute path inside the mounted tree, or "" when it is not inside. */
    static String safPathFor(Context c, String absolute) {
        String rel = safRelativeForAbsolute(c, absolute);
        return rel == null ? "" : ("saf://default/" + rel);
    }
    // ------------------------------------------------------------------ SAF backed operations

    private static JSONObject safList(Context c, String rel, int limit) {
        JSONObject o = new JSONObject();
        JSONArray items = new JSONArray();
        try {
            JSONArray raw = SafStorage.list(c, rel);
            String root = safRootAbsolute(c);
            int cap = Math.max(1, Math.min(2000, limit));
            for (int i = 0; i < raw.length() && items.length() < cap; i++) {
                JSONObject it = raw.optJSONObject(i);
                if (it == null) continue;
                String name = it.optString("name", "");
                String childRel = it.optString("path", name);
                boolean isDir = it.optBoolean("directory", false);
                long size = it.optLong("sizeBytes", 0L);
                long mtime = it.optLong("lastModified", 0L);
                JSONObject x = new JSONObject();
                x.put("name", name);
                x.put("path", root.isEmpty() ? childRel : root + "/" + childRel);
                x.put("safPath", "saf://default/" + childRel);
                x.put("isDir", isDir);
                x.put("directory", isDir);
                x.put("size", size);
                x.put("sizeBytes", size);
                x.put("mtime", mtime);
                x.put("mode", isDir ? "drwx" : "-rw");
                x.put("source", "saf");
                items.put(x);
            }
            o.put("ok", true).put("path", "saf://default/" + rel).put("count", items.length());
            o.put("items", items).put("source", "saf").put("backend", "SAF");
            o.put("safRoot", root).put("truncated", items.length() >= cap);
            return o;
        } catch (Throwable t) {
            try {
                return o.put("ok", false).put("count", 0).put("items", items).put("source", "saf")
                        .put("error", "SAF 枚举失败: " + t);
            } catch (Throwable ignored) {
                return o;
            }
        }
    }

    private static JSONObject safStat(Context c, String rel) {
        JSONObject o = new JSONObject();
        try {
            Uri u = SafStorage.childOrThrow(c, rel);
            boolean isDir = false;
            try { isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getContentResolver().getType(u)); } catch (Throwable ignored) {}
            long size = 0L, mtime = 0L;
            android.database.Cursor q = null;
            try {
                q = c.getContentResolver().query(u, new String[]{
                        DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null);
                if (q != null && q.moveToFirst()) {
                    if (!q.isNull(0)) size = q.getLong(0);
                    if (!q.isNull(1)) mtime = q.getLong(1);
                }
            } finally { if (q != null) q.close(); }
            String root = safRootAbsolute(c);
            return new JSONObject().put("ok", true).put("path", root.isEmpty() ? rel : root + "/" + rel)
                    .put("safPath", "saf://default/" + rel).put("isDir", isDir).put("size", size)
                    .put("mtime", mtime).put("source", "saf");
        } catch (Throwable t) {
            try {
                return o.put("ok", false).put("error", "SAF stat 失败: " + t);
            } catch (Throwable ignored) {
                return o;
            }
        }
    }

    private static JSONObject safRead(Context c, String rel, int max, long offset) {
        try {
            byte[] b = SafStorage.read(c, rel, Math.max(1, Math.min(2 * 1024 * 1024, max)), Math.max(0L, offset));
            return new JSONObject().put("ok", true).put("path", "saf://default/" + rel).put("offset", offset)
                    .put("bytesRead", b.length).put("source", "saf")
                    .put("base64", android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP))
                    .put("text", new String(b, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return err("SAF 读取失败: " + t);
        }
    }

    private static JSONObject safWrite(Context c, String rel, byte[] data) {
        try {
            SafStorage.write(c, rel, data);
            return new JSONObject().put("ok", true).put("path", "saf://default/" + rel)
                    .put("bytes", data.length).put("source", "saf");
        } catch (Throwable t) {
            return err("SAF 写入失败: " + t);
        }
    }

    private static JSONObject safMkdir(Context c, String rel) {
        try {
            SafStorage.mkdir(c, rel);
            return new JSONObject().put("ok", true).put("path", "saf://default/" + rel)
                    .put("directory", true).put("source", "saf");
        } catch (Throwable t) {
            return err("SAF 创建目录失败: " + t);
        }
    }

    private static JSONObject safDelete(Context c, String rel) {
        try {
            String clean = SafStorage.normalizeRelative(rel);
            if (clean.isEmpty()) throw new java.io.IOException("拒绝删除 SAF 工作区根目录");
            Uri u = SafStorage.childOrThrow(c, clean);
            boolean done = DocumentsContract.deleteDocument(c.getContentResolver(), u);
            return new JSONObject().put("ok", done).put("path", "saf://default/" + clean)
                    .put("deleted", done).put("source", "saf");
        } catch (Throwable t) {
            return err("SAF 删除失败: " + t);
        }
    }
    // ------------------------------------------------------------------ path routing

    /** True when the path targets the mounted SAF tree (explicit saf:// path or absolute path inside it). */
    static boolean useSaf(Context c, String raw) {
        if (isSafPath(raw)) return true;
        String p = raw == null ? "" : raw.trim();
        if (p.isEmpty() || !p.startsWith("/")) return false;
        return safRelativeForAbsolute(c, p) != null;
    }

    static String safRelativeOf(Context c, String raw) throws java.io.IOException {
        if (isSafPath(raw)) return safRelative(raw);
        String rel = safRelativeForAbsolute(c, raw);
        if (rel == null) throw new java.io.IOException("路径不在已挂载的 SAF 工作区内: " + raw);
        return rel;
    }

    /** Single-line, length-capped text for logcat. */
    static String brief(String s) {
        if (s == null) return "";
        String one = s.replace('\n', ' ').replace('\r', ' ');
        return one.length() <= 200 ? one : one.substring(0, 200) + "…";
    }

    // ------------------------------------------------------------------ dispatch

    static JSONObject dispatch(Context ctx, String method, Bundle e) {
        return dispatch(ctx, method, e, "unknown");
    }

    /**
     * Runs one bridge method inside the module process.
     *
     * @param via which transport delivered the request: "binder" (ApkScanProvider) or "socket".
     */
    static JSONObject dispatch(Context ctx, String method, Bundle e, String via) {
        try {
            if (method == null || method.trim().isEmpty()) return err("method 不能为空");
            String m = method.trim();

            if ("ping".equals(m)) {
                JSONObject pong = new JSONObject().put("ok", true).put("pong", true)
                        .put("modulePackage", SELF).put("pid", android.os.Process.myPid())
                        .put("uid", android.os.Process.myUid()).put("via", via)
                        .put("shizukuGranted", ShizukuShell.status().optBoolean("permissionGranted", false))
                        .put("safMounted", SafStorage.mounted(ctx))
                        .put("ts", System.currentTimeMillis());
                try { pong.put("daemon", DaemonLauncher.status(ctx)); } catch (Throwable ignored) {}
                return pong;
            }
            if ("daemon_status".equals(m)) {
                JSONObject o = DaemonLauncher.statusFresh(ctx);
                o.put("ok", o.optJSONObject("probe") != null && o.optJSONObject("probe").optBoolean("alive", false));
                o.put("via", via);
                return o;
            }
            if ("daemon_ensure".equals(m)) {
                JSONObject o = DaemonLauncher.ensure(ctx, str(e, "reason", "tool"));
                o.put("via", via);
                return o;
            }
            if ("daemon_stop".equals(m)) {
                JSONObject o = DaemonLauncher.stop();
                o.put("via", via);
                return o;
            }
            if ("shizuku_status".equals(m)) return ShizukuShell.status();
            if ("storage_status".equals(m)) return storageStatus(ctx, via);
            if ("shizuku_request".equals(m)) return shizukuRequest(ctx);
            if ("shell".equals(m)) return shell(e);
            if ("fs_list".equals(m)) return fsList(ctx, e);
            if ("fs_stat".equals(m)) return fsStat(ctx, e);
            if ("fs_read".equals(m)) return fsRead(ctx, e);
            if ("fs_write".equals(m)) return fsWrite(ctx, e);
            if ("fs_mkdir".equals(m)) return fsMkdir(ctx, e);
            if ("fs_delete".equals(m)) return fsDelete(ctx, e);
            if ("scan".equals(m)) {
                return McpIdeGateway.scanLocalApksForProvider(str(e, "directory", ""),
                        bool(e, "recursive", true), i32(e, "maxDepth", 3), i32(e, "limit", 50));
            }
            return err("unknown method: " + m);
        } catch (Throwable t) {
            return err("bridge method " + method + " 失败: " + t);
        }
    }

    private static JSONObject storageStatus(Context ctx, String via) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("modulePackage", ctx == null ? SELF : ctx.getPackageName());
            o.put("moduleUid", android.os.Process.myUid());
            o.put("modulePid", android.os.Process.myPid());
            o.put("moduleAllFilesAccess", ShizukuShell.allFilesAccess());
            o.put("shizuku", ShizukuShell.status());
            o.put("safMounted", SafStorage.mounted(ctx));
            o.put("safRoot", safRootAbsolute(ctx));
            o.put("safStatus", SafStorage.status(ctx));
            o.put("roots", ShizukuShell.probeStorageRoots());
            o.put("via", via);
            return o;
        } catch (Throwable t) {
            return err("storage_status 失败: " + t);
        }
    }

    private static JSONObject shizukuRequest(Context ctx) {
        try {
            Intent i = new Intent(ctx, StorageAccessActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            i.putExtra("request_shizuku", true);
            ctx.startActivity(i);
            return new JSONObject().put("ok", true).put("launched", true)
                    .put("note", "已打开模块界面，请点击“请求 Shizuku 授权”并允许");
        } catch (Throwable t) {
            return err("无法打开模块界面: " + t);
        }
    }

    private static JSONObject shell(Bundle e) {
        String cmd = str(e, "command", "");
        if (cmd.trim().isEmpty()) return err("command 不能为空");
        long timeout = Math.max(1000L, Math.min(600000L, i64(e, "timeoutMs", 30000L)));
        String cwd = str(e, "cwd", "");
        long t0 = android.os.SystemClock.elapsedRealtime();
        ShizukuShell.Result sr = ShizukuShell.exec(cmd, timeout, cwd);
        log("shell ok=" + sr.ok + " exit=" + sr.exitCode + " stage=" + sr.stage
                + " took=" + (android.os.SystemClock.elapsedRealtime() - t0) + "ms cmd=" + brief(cmd));
        if (!sr.ok && !sr.error.isEmpty()) log("shell error: " + brief(sr.error));
        return sr.json();
    }
    // ------------------------------------------------------------------ filesystem methods

    /**
     * Lists a directory. Paths inside the mounted SAF tree are served by the SAF document provider
     * (works without any storage permission and without Shizuku); every other path goes to the
     * uid 2000 shell. If SAF fails (grant revoked, tree moved) the shell is tried before failing.
     */
    private static JSONObject fsList(Context ctx, Bundle e) throws Exception {
        String raw = str(e, "path", "");
        if (raw.trim().isEmpty()) return err("path 不能为空");
        boolean recursive = bool(e, "recursive", true);
        int maxDepth = Math.max(1, Math.min(6, i32(e, "maxDepth", 3)));
        int limit = Math.max(1, Math.min(2000, i32(e, "limit", 200)));
        String glob = str(e, "glob", "");

        if (!useSaf(ctx, raw)) return ShizukuShell.listFiles(raw, recursive, maxDepth, limit, glob);

        JSONObject o;
        try {
            o = safListTree(ctx, safRelativeOf(ctx, raw), recursive ? maxDepth : 1, limit, glob);
        } catch (Throwable t) {
            o = err("SAF 路径解析失败: " + t);
        }
        if (o.optBoolean("ok", false)) return o;
        JSONObject viaShell = ShizukuShell.listFiles(raw, recursive, maxDepth, limit, glob);
        if (viaShell.optBoolean("ok", false)) return viaShell.put("safFallback", o.optString("error", ""));
        return o.put("shellFallback", viaShell.optString("error", ""));
    }

    /** Breadth-first SAF enumeration honouring the same depth/limit/glob semantics as the shell path. */
    private static JSONObject safListTree(Context ctx, String rel, int maxDepth, int limit, String glob) {
        JSONObject first = safList(ctx, rel, limit);
        if (!first.optBoolean("ok", false)) return first;
        String root = safRootAbsolute(ctx);
        JSONArray items = new JSONArray();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<Integer> depths = new java.util.ArrayDeque<>();
        queue.add(rel == null ? "" : rel);
        depths.add(1);
        while (!queue.isEmpty() && items.length() < limit) {
            String cur = queue.poll();
            int depth = depths.poll();
            JSONObject page = safList(ctx, cur, limit);
            if (!page.optBoolean("ok", false)) continue;
            JSONArray list = page.optJSONArray("items");
            if (list == null) continue;
            for (int i = 0; i < list.length() && items.length() < limit; i++) {
                JSONObject it = list.optJSONObject(i);
                if (it == null) continue;
                if (!matchesGlob(it.optString("name", ""), glob)) continue;
                items.put(it);
                if (it.optBoolean("isDir", false) && depth < maxDepth) {
                    String child = it.optString("safPath", "");
                    if (child.startsWith("saf://default/")) {
                        queue.add(child.substring("saf://default/".length()));
                        depths.add(depth + 1);
                    }
                }
            }
        }
        try {
            return new JSONObject().put("ok", true).put("path", "saf://default/" + (rel == null ? "" : rel))
                    .put("count", items.length()).put("items", items).put("source", "saf")
                    .put("backend", "SAF").put("safRoot", root).put("maxDepth", maxDepth)
                    .put("truncated", items.length() >= limit);
        } catch (Throwable t) {
            return err("SAF 结果构造失败: " + t);
        }
    }

    private static JSONObject fsStat(Context ctx, Bundle e) throws Exception {
        String raw = str(e, "path", "");
        if (raw.trim().isEmpty()) return err("path 不能为空");
        if (!useSaf(ctx, raw)) return ShizukuShell.stat(raw);
        JSONObject o = safStat(ctx, safRelativeOf(ctx, raw));
        if (o.optBoolean("ok", false)) return o;
        JSONObject viaShell = ShizukuShell.stat(raw);
        if (viaShell.optBoolean("ok", false)) return viaShell.put("safFallback", o.optString("error", ""));
        return o.put("shellFallback", viaShell.optString("error", ""));
    }

    private static JSONObject fsRead(Context ctx, Bundle e) throws Exception {
        String raw = str(e, "path", "");
        if (raw.trim().isEmpty()) return err("path 不能为空");
        int max = Math.max(1, i32(e, "length", i32(e, "maxBytes", 262144)));
        long off = Math.max(0L, i64(e, "offset", 0L));
        if (!useSaf(ctx, raw)) return ShizukuShell.readChunk(raw, off, max);
        JSONObject o = safRead(ctx, safRelativeOf(ctx, raw), max, off);
        if (o.optBoolean("ok", false)) return o;
        JSONObject viaShell = ShizukuShell.readChunk(raw, off, max);
        if (viaShell.optBoolean("ok", false)) return viaShell.put("safFallback", o.optString("error", ""));
        return o.put("shellFallback", viaShell.optString("error", ""));
    }

    private static JSONObject fsWrite(Context ctx, Bundle e) throws Exception {
        String raw = str(e, "path", "");
        if (raw.trim().isEmpty()) return err("path 不能为空");
        byte[] data = bytes(e, "base64");
        if (data.length == 0) {
            String content = str(e, "content", "");
            if (!content.isEmpty()) data = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        boolean append = bool(e, "append", false);
        boolean mkdirs = bool(e, "mkdirs", true);
        if (!useSaf(ctx, raw)) return ShizukuShell.writeFile(raw, data, append, mkdirs);
        JSONObject o = safWrite(ctx, safRelativeOf(ctx, raw), data);
        if (o.optBoolean("ok", false)) return o;
        JSONObject viaShell = ShizukuShell.writeFile(raw, data, append, mkdirs);
        if (viaShell.optBoolean("ok", false)) return viaShell.put("safFallback", o.optString("error", ""));
        return o.put("shellFallback", viaShell.optString("error", ""));
    }

    private static JSONObject fsMkdir(Context ctx, Bundle e) throws Exception {
        String raw = str(e, "path", "");
        if (raw.trim().isEmpty()) return err("path 不能为空");
        if (!useSaf(ctx, raw)) return ShizukuShell.makeDirs(raw);
        JSONObject o = safMkdir(ctx, safRelativeOf(ctx, raw));
        if (o.optBoolean("ok", false)) return o;
        JSONObject viaShell = ShizukuShell.makeDirs(raw);
        if (viaShell.optBoolean("ok", false)) return viaShell.put("safFallback", o.optString("error", ""));
        return o.put("shellFallback", viaShell.optString("error", ""));
    }

    private static JSONObject fsDelete(Context ctx, Bundle e) throws Exception {
        String raw = str(e, "path", "");
        if (raw.trim().isEmpty()) return err("path 不能为空");
        boolean recursive = bool(e, "recursive", false);
        if (!useSaf(ctx, raw)) return ShizukuShell.delete(raw, recursive);
        JSONObject o = safDelete(ctx, safRelativeOf(ctx, raw));
        if (o.optBoolean("ok", false)) return o;
        JSONObject viaShell = ShizukuShell.delete(raw, recursive);
        if (viaShell.optBoolean("ok", false)) return viaShell.put("safFallback", o.optString("error", ""));
        return o.put("shellFallback", viaShell.optString("error", ""));
    }

    /** Case-insensitive wildcard match supporting {@code *} and {@code ?}; empty pattern matches all. */
    static boolean matchesGlob(String name, String glob) {
        if (glob == null || glob.trim().isEmpty()) return true;
        StringBuilder rx = new StringBuilder();
        for (String part : glob.trim().split(";")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (rx.length() > 0) rx.append('|');
            rx.append('^');
            for (int i = 0; i < p.length(); i++) {
                char ch = p.charAt(i);
                if (ch == '*') rx.append(".*");
                else if (ch == '?') rx.append('.');
                else if ("\\.[]{}()+-^$|".indexOf(ch) >= 0) rx.append('\\').append(ch);
                else rx.append(ch);
            }
            rx.append('$');
        }
        if (rx.length() == 0) return true;
        try {
            return java.util.regex.Pattern.compile(rx.toString(), java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(name == null ? "" : name).find();
        } catch (Throwable t) {
            return true;
        }
    }
}

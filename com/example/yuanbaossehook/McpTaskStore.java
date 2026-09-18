package com.example.yuanbaossehook;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

/** Durable local implementation of the MCP 2026-07-28 Tasks state machine. */
final class McpTaskStore {
    private static final Object LOCK = new Object();
    private static File root;

    static void init(Context context) {
        synchronized (LOCK) {
            if (root != null) return;
            try {
                Context c = context == null ? null : context.getApplicationContext();
                if (c != null) root = new File(c.getFilesDir(), "mcp-tasks");
                else root = new File(System.getProperty("java.io.tmpdir", "."), "yuanbao-mcp-tasks");
                if (!root.exists()) root.mkdirs();
                recoverStaleWorkingLocked();
            } catch (Throwable ignored) {}
        }
    }

    static String createChild(String parentTaskId, String tool, JSONObject arguments, long ttlMs, long pollMs) throws Exception {
        JSONObject a = arguments == null ? new JSONObject() : new JSONObject(arguments.toString());
        a.put("parentTaskId", parentTaskId == null ? "" : parentTaskId);
        String id = create(tool, a, ttlMs, pollMs);
        synchronized (LOCK) {
            try {
                JSONObject parent = readLocked(parentTaskId);
                if (parent != null) {
                    org.json.JSONArray children=parent.optJSONArray("children"); if(children==null){children=new org.json.JSONArray(); parent.put("children",children);} children.put(id);
                    touch(parent); writeLocked(parentTaskId, parent);
                }
            } catch (Throwable ignored) {}
        }
        return id;
    }

    static String create(String tool, JSONObject arguments, long ttlMs, long pollMs) throws Exception {
        synchronized (LOCK) {
            ensureRootLocked();
            String id = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            JSONObject o = new JSONObject();
            o.put("resultType", "task");
            o.put("taskId", id);
            o.put("tool", tool == null ? "" : tool);
            o.put("arguments", arguments == null ? new JSONObject() : new JSONObject(arguments.toString()));
            o.put("status", "working");
            o.put("statusMessage", "The operation is now in progress.");
            o.put("createdAt", iso(now));
            o.put("lastUpdatedAt", iso(now));
            o.put("createdAtMs", now);
            o.put("lastUpdatedAtMs", now);
            o.put("ttlMs", ttlMs);
            o.put("pollIntervalMs", pollMs);
            o.put("workerId", "");
            o.put("workerHeartbeatMs", 0L);
            o.put("checkpoint", new JSONObject());
            writeLocked(id, o);
            return id;
        }
    }

    static JSONObject get(String id) throws Exception {
        synchronized (LOCK) {
            JSONObject o = readLocked(id);
            if (o == null) throw new IllegalArgumentException("Unknown taskId: " + id);
            return publicTask(o);
        }
    }

    static boolean claimWorker(String id, String workerId) {
        synchronized (LOCK) {
            try {
                JSONObject o = readLocked(id);
                if (o == null || !"working".equals(o.optString("status"))) return false;
                String current = o.optString("workerId", "");
                if (!current.isEmpty() && !current.equals(workerId)) {
                    long hb = o.optLong("workerHeartbeatMs", 0L);
                    long age = hb <= 0L ? Long.MAX_VALUE : (System.currentTimeMillis() - hb);
                    if (age < Math.max(5000L, o.optLong("pollIntervalMs", 1000L) * 3L)) return false;
                }
                o.put("workerId", workerId == null ? "" : workerId);
                o.put("workerHeartbeatMs", System.currentTimeMillis());
                touch(o);
                writeLocked(id, o);
                return true;
            } catch (Throwable e) { return false; }
        }
    }

    static void heartbeat(String id, String workerId) {
        synchronized (LOCK) {
            try {
                JSONObject o = readLocked(id);
                if (o == null || !"working".equals(o.optString("status"))) return;
                if (!workerId.equals(o.optString("workerId", ""))) return;
                o.put("workerHeartbeatMs", System.currentTimeMillis());
                touch(o);
                writeLocked(id, o);
            } catch (Throwable ignored) {}
        }
    }

    static void checkpoint(String id, JSONObject checkpoint) {
        synchronized (LOCK) {
            try {
                JSONObject o = readLocked(id);
                if (o == null || !"working".equals(o.optString("status"))) return;
                o.put("checkpoint", checkpoint == null ? new JSONObject() : new JSONObject(checkpoint.toString()));
                touch(o);
                writeLocked(id, o);
            } catch (Throwable ignored) {}
        }
    }

    static void requireInput(String id, JSONObject inputRequests, String message) {
        synchronized (LOCK) {
            try {
                JSONObject o = readLocked(id);
                if (o == null) return;
                o.put("status", "input_required");
                o.put("inputRequests", inputRequests == null ? new JSONObject() : new JSONObject(inputRequests.toString()));
                if (message != null) o.put("statusMessage", message);
                touch(o);
                writeLocked(id, o);
            } catch (Throwable ignored) {}
        }
    }

    static JSONObject applyInputResponses(String id, JSONObject responses) throws Exception {
        synchronized (LOCK) {
            JSONObject o = readLocked(id);
            if (o == null) throw new IllegalArgumentException("Unknown taskId: " + id);
            JSONObject pending = o.optJSONObject("inputRequests");
            if (pending == null) pending = new JSONObject();
            JSONObject accepted = responses == null ? new JSONObject() : new JSONObject(responses.toString());
            java.util.Iterator<String> it = accepted.keys();
            while (it.hasNext()) {
                String key = it.next();
                if (pending.has(key)) pending.remove(key);
            }
            o.put("inputResponses", accepted);
            if (pending.length() == 0) {
                o.remove("inputRequests");
                if ("input_required".equals(o.optString("status"))) o.put("status", "working");
                o.put("statusMessage", "Input received; worker resumed.");
            } else {
                o.put("inputRequests", pending);
            }
            touch(o);
            writeLocked(id, o);
            return publicTask(o);
        }
    }

    static JSONObject consumeInputResponses(String id) throws Exception {
        synchronized (LOCK) {
            JSONObject o = readLocked(id);
            if (o == null) throw new IllegalArgumentException("Unknown taskId: " + id);
            JSONObject r = o.optJSONObject("inputResponses");
            if (r == null) r = new JSONObject();
            o.remove("inputResponses");
            touch(o);
            writeLocked(id, o);
            return r;
        }
    }

    static List<JSONObject> recoverableInputTasks() {
        synchronized (LOCK) {
            List<JSONObject> out = new ArrayList<>();
            try {
                ensureRootLocked();
                File[] fs = root.listFiles((d,n)->n.endsWith(".json"));
                if (fs == null) return out;
                for (File f : fs) {
                    try {
                        JSONObject o = readFile(f);
                        if ("input_required".equals(o.optString("status")) && o.optJSONObject("inputRequests") != null) out.add(publicTask(o));
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            return out;
        }
    }

    static List<JSONObject> recoverableOrchestratorTasks() {
        synchronized (LOCK) {
            List<JSONObject> out = new ArrayList<>();
            try {
                ensureRootLocked();
                File[] fs = root.listFiles((d,n)->n.endsWith(".json"));
                if (fs == null) return out;
                for (File f : fs) {
                    try {
                        JSONObject o = readFile(f);
                        String tool=o.optString("tool",""); String st=o.optString("status","");
                        if ("agent_orchestrate".equals(tool) && ("working".equals(st)||"paused".equals(st)||"failed".equals(st))) {
                            long hb=o.optLong("workerHeartbeatMs",0L);
                            long age=hb<=0L?Long.MAX_VALUE:System.currentTimeMillis()-hb;
                            if (age >= Math.max(10000L, o.optLong("pollIntervalMs",1500L)*4L)) {
                                o.put("status","working").put("statusMessage","Recovered durable autonomous agent after worker interruption.");
                                o.put("workerId","").put("workerHeartbeatMs",0L); touch(o); writeFile(f,o);
                                out.add(publicTask(o));
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            return out;
        }
    }

    static List<JSONObject> recoverableNativeTasks() {
        synchronized (LOCK) {
            List<JSONObject> out = new ArrayList<>();
            try {
                ensureRootLocked();
                File[] fs = root.listFiles((d,n)->n.endsWith(".json"));
                if (fs == null) return out;
                for (File f : fs) {
                    try {
                        JSONObject o = readFile(f);
                        if ("native_agent".equals(o.optString("tool")) && ("working".equals(o.optString("status")) || "input_required".equals(o.optString("status")))) {
                            out.add(publicTask(o));
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            return out;
        }
    }

    static JSONObject update(String id, String statusMessage) throws Exception {
        synchronized (LOCK) {
            JSONObject o = readLocked(id);
            if (o == null) throw new IllegalArgumentException("Unknown taskId: " + id);
            if (statusMessage != null && !statusMessage.trim().isEmpty()) o.put("statusMessage", statusMessage.trim());
            touch(o);
            writeLocked(id, o);
            return publicTask(o);
        }
    }

    static void progress(String id, String message, int round, String next) {
        synchronized (LOCK) {
            try {
                JSONObject o = readLocked(id);
                if (o == null) return;
                if (message != null) o.put("statusMessage", message);
                if (round >= 0) o.put("round", round);
                if (next != null) o.put("next", next);
                touch(o);
                writeLocked(id, o);
            } catch (Throwable ignored) {}
        }
    }

    static void complete(String id, JSONObject result) {
        terminal(id, "completed", null, result);
    }

    static void failIfWorking(String id, String message) {
        try {
            JSONObject o = read(id);
            if (o != null && "working".equals(o.optString("status"))) fail(id, message);
        } catch (Throwable ignored) {}
    }

    static boolean exists(String id) {
        synchronized (LOCK) {
            try { return readLocked(id) != null; } catch (Throwable ignored) { return false; }
        }
    }

    private static JSONObject read(String id) throws Exception {
        synchronized (LOCK) { return readLocked(id); }
    }

    static void fail(String id, String message) {
        try {
            JSONObject error = new JSONObject().put("code", -32603).put("message", message == null ? "Task failed" : message);
            terminal(id, "failed", error, null);
        } catch (Throwable ignored) {}
    }

    static void cancel(String id) throws Exception {
        synchronized (LOCK) {
            JSONObject o = readLocked(id);
            if (o == null) throw new IllegalArgumentException("Unknown taskId: " + id);
            o.put("status", "cancelled");
            touch(o);
            o.put("statusMessage", "Cancellation requested.");
            writeLocked(id, o);
        }
    }

    static boolean isCancelled(String id) {
        synchronized (LOCK) {
            try {
                JSONObject o = readLocked(id);
                return o != null && "cancelled".equals(o.optString("status"));
            } catch (Throwable ignored) { return false; }
        }
    }

    static File taskFile(String id) {
        synchronized (LOCK) {
            try { ensureRootLocked(); return new File(root, safeName(id) + ".json"); }
            catch (Throwable ignored) { return null; }
        }
    }

    private static void terminal(String id, String status, JSONObject error, JSONObject result) {
        synchronized (LOCK) {
            try {
                JSONObject o = readLocked(id);
                if (o == null) return;
                o.put("status", status);
                touch(o);
                if (error != null) o.put("error", error);
                if (result != null) o.put("result", result);
                writeLocked(id, o);
            } catch (Throwable ignored) {}
        }
    }

    private static void recoverStaleWorkingLocked() {
        try {
            File[] fs = root.listFiles((d, n) -> n.endsWith(".json"));
            if (fs == null) return;
            long now = System.currentTimeMillis();
            for (File f : fs) {
                try {
                    JSONObject o = readFile(f);
                    if (!"working".equals(o.optString("status"))) continue;
                    long ttl = o.optLong("ttlMs", 3600000L);
                    long updated = o.optLong("lastUpdatedAtMs", f.lastModified());
                    // A process restart cannot safely claim the worker is still running.
                    if (now - updated > Math.max(60000L, Math.min(ttl, 24 * 3600000L))) {
                        if ("agent_orchestrate".equals(o.optString("tool", ""))) {
                            o.put("status", "paused");
                            o.put("statusMessage", "Gateway process restarted; autonomous agent will resume from checkpoint.");
                            o.put("workerId", "").put("workerHeartbeatMs", 0L);
                        } else {
                            o.put("status", "failed");
                            o.put("statusMessage", "Gateway process restarted before task completion.");
                            o.put("error", new JSONObject().put("code", -32603).put("message", "Task worker was interrupted by process restart; use the saved arguments to resume."));
                        }
                        touch(o);
                        writeFile(f, o);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static void touch(JSONObject o) throws Exception {
        long now = System.currentTimeMillis();
        o.put("lastUpdatedAt", iso(now));
        o.put("lastUpdatedAtMs", now);
    }

    private static String iso(long ms) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                .format(new java.util.Date(ms));
    }

    private static JSONObject publicTask(JSONObject o) throws Exception {
        JSONObject p = new JSONObject();
        String[] fields = {"resultType","taskId","status","statusMessage","createdAt","lastUpdatedAt","ttlMs","pollIntervalMs","round","next","result","error","inputRequests","inputResponses","checkpoint","tool","children","parentTaskId"};
        for (String k : fields) if (o.has(k)) p.put(k, o.opt(k));
        return p;
    }

    private static void ensureRootLocked() {
        if (root == null) root = new File(System.getProperty("java.io.tmpdir", "."), "yuanbao-mcp-tasks");
        if (!root.exists()) root.mkdirs();
    }

    private static String safeName(String id) {
        if (id == null || !id.matches("[A-Za-z0-9._-]{8,128}")) throw new IllegalArgumentException("Invalid taskId");
        return id;
    }

    private static JSONObject readLocked(String id) throws Exception {
        ensureRootLocked();
        File f = new File(root, safeName(id) + ".json");
        if (!f.isFile()) return null;
        return readFile(f);
    }

    private static JSONObject readFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[(int)Math.min(Integer.MAX_VALUE, f.length())];
            int p = 0, n;
            while (p < b.length && (n = in.read(b, p, b.length - p)) > 0) p += n;
            return new JSONObject(new String(b, 0, p, StandardCharsets.UTF_8));
        } finally { in.close(); }
    }

    private static void writeLocked(String id, JSONObject o) throws Exception {
        ensureRootLocked();
        writeFile(new File(root, safeName(id) + ".json"), o);
    }

    private static void writeFile(File f, JSONObject o) throws Exception {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            byte[] b = o.toString().getBytes(StandardCharsets.UTF_8);
            out.write(b); out.flush(); out.getFD().sync();
        } finally { out.close(); }
        if (!tmp.renameTo(f)) {
            FileOutputStream out2 = new FileOutputStream(f);
            try { out2.write(o.toString().getBytes(StandardCharsets.UTF_8)); out2.flush(); out2.getFD().sync(); }
            finally { out2.close(); tmp.delete(); }
        }
    }
}

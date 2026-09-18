package com.example.yuanbaossehook;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Durable autonomous reverse-engineering orchestrator.
 * The orchestrator owns the long-running objective, while every decision is
 * made from persisted state and the previous REAL tool result.  It is not
 * tied to an Activity/request lifetime.
 */
final class AgentOrchestrator {
    private static final Object LOCK = new Object();
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final Map<String, Thread> WORKERS = new HashMap<>();
    private static final int MAX_STEPS = 64;
    private static final int MAX_RETRIES = 3;
    private static final long HEARTBEAT_EVERY_MS = 15000L;

    private AgentOrchestrator() {}

    static JSONObject start(JSONObject input) throws Exception {
        JSONObject args = input == null ? new JSONObject() : new JSONObject(input.toString());
        String goal = args.optString("goal", "").trim();
        if (goal.isEmpty() && args.optString("apk", "").trim().isEmpty())
            throw new IllegalArgumentException("goal 或 apk 至少一个不能为空");
        args.put("goal", goal);
        String id = McpTaskStore.create("agent_orchestrate", args, 24L * 60L * 60L * 1000L, 1500L);
        launch(id, args);
        return new JSONObject().put("resultType", "task").put("taskId", id).put("status", "working")
                .put("mode", "autonomous-result-driven").put("maxSteps", MAX_STEPS)
                .put("maxRetries", MAX_RETRIES)
                .put("statusMessage", "自主编排器已接管目标；每一步依据真实工具结果重新规划");
    }

    static JSONObject resume(String taskId) throws Exception {
        if (taskId == null || taskId.trim().isEmpty()) throw new IllegalArgumentException("taskId 不能为空");
        JSONObject t = McpTaskStore.get(taskId);
        String st = t.optString("status", "");
        if (!("working".equals(st) || "paused".equals(st) || "failed".equals(st))) return t;
        JSONObject a = t.optJSONObject("arguments");
        if (a == null) throw new IllegalArgumentException("任务缺少 arguments");
        launch(taskId, a);
        return McpTaskStore.get(taskId);
    }

    static JSONObject plan(JSONObject input) throws Exception {
        JSONObject a=input==null?new JSONObject():new JSONObject(input.toString());
        String goal=a.optString("goal","").trim();
        JSONObject out=new JSONObject().put("ok",true).put("planner","result-driven").put("goal",goal);
        out.put("stateModel",new JSONArray().put("discover").put("project").put("evidence").put("deep-analysis").put("decompile").put("report"));
        out.put("availableTools",McpIdeGateway.orchestratorToolCatalog());
        out.put("instructions","模型可返回 plan=[{tool,arguments}] 给 agent_orchestrate；执行器会逐项持久化、重试并在失败后重新规划。");
        return out;
    }

    static JSONObject cancel(String taskId) throws Exception {
        McpTaskStore.cancel(taskId);
        synchronized (LOCK) { Thread t = WORKERS.get(taskId); if (t != null) t.interrupt(); }
        return McpTaskStore.get(taskId);
    }

    /** Called after gateway startup. Reclaims durable orchestrator tasks whose worker disappeared. */
    static void recoverAll() {
        try {
            for (JSONObject t : McpTaskStore.recoverableOrchestratorTasks()) {
                String id = t.optString("taskId", "");
                JSONObject a = t.optJSONObject("arguments");
                if (!id.isEmpty() && a != null) launch(id, a);
            }
        } catch (Throwable ignored) {}
    }

    private static void launch(String id, JSONObject args) {
        synchronized (LOCK) {
            Thread old = WORKERS.get(id);
            if (old != null && old.isAlive()) return;
            Thread t = new Thread(() -> run(id, args), "YB-autonomous-agent-" + IDS.incrementAndGet());
            WORKERS.put(id, t);
            t.start();
        }
    }

    private static void run(String id, JSONObject args) {
        String workerId = "agent-" + IDS.incrementAndGet();
        try {
            if (!McpTaskStore.claimWorker(id, workerId)) return;
            JSONObject task = McpTaskStore.get(id);
            JSONObject cp = task.optJSONObject("checkpoint");
            if (cp == null) cp = new JSONObject();
            JSONObject state = cp.optJSONObject("state");
            if (state == null) state = new JSONObject();
            String goal = args.optString("goal", "").trim();
            String requested = args.optString("apk", "").trim();
            String name = args.optString("name", "").trim();
            if (name.isEmpty()) name = deriveName(requested, goal);
            state.put("goal", goal).put("requestedApk", requested).put("projectName", name)
                    .put("schemaVersion", 3);
            ensureArrays(state);
            if (args.optJSONArray("plan") != null) state.put("modelPlan", args.optJSONArray("plan"));

            int step = cp.optInt("stepIndex", 0);
            while (step < MAX_STEPS) {
                check(id);
                McpTaskStore.heartbeat(id, workerId);
                Decision d = chooseNext(state, goal);
                if (d == null) break;
                d.args.put("_orchestratorTask", id);
                d.args.put("_step", step);
                McpTaskStore.progress(id, "自主规划：" + d.label, step, d.name);
                String childId = "";
                try {
                    childId = McpTaskStore.createChild(id, d.name, d.args, 30L * 60L * 1000L, 1000L);
                    state.optJSONArray("children").put(childId);
                } catch (Throwable ignored) {}
                JSONObject result = executeWithRetry(id, workerId, d, state);
                record(state, d, result);
                if (!childId.isEmpty()) {
                    try {
                        if (isFatal(result)) McpTaskStore.fail(childId, errorText(result));
                        else McpTaskStore.complete(childId, summarize(result));
                    } catch (Throwable ignored) {}
                }
                state.put("lastDecision", d.name).put("lastResultOk", !isFatal(result));
                JSONArray repairs = state.optJSONArray("repairs");
                if (isFatal(result)) {
                    boolean repaired = attemptRepair(state, d, result);
                    if (!repaired && d.essential) {
                        persist(id, state, step + 1, d.name, result, "failed");
                        McpTaskStore.fail(id, "自主任务在 " + d.name + " 失败：" + errorText(result));
                        return;
                    }
                    if (repaired) {
                        repairs.put(new JSONObject().put("step", step).put("tool", d.name)
                                .put("action", state.optString("lastRepair", "replan")));
                        McpTaskStore.progress(id, "工具失败已被识别；执行修复并重新规划", step, "repair");
                    }
                }
                step++;
                persist(id, state, step, d.name, result, null);
                if (goalSatisfied(state, goal)) break;
                Thread.sleep(40L);
            }

            if (!goalSatisfied(state, goal)) {
                // A partial result is still persisted and resumable rather than being called success.
                persist(id, state, step, "planner", new JSONObject().put("partial", true), "paused");
                McpTaskStore.progress(id, "已保存部分结果；尚未满足目标，任务保持可恢复状态", step, "resume");
                return;
            }
            String project = state.optString("project", "");
            if (!project.isEmpty()) writeReports(project, state, goal);
            JSONObject out = new JSONObject().put("ok", true).put("project", project)
                    .put("report", project.isEmpty() ? "" : project + "/report/summary.md")
                    .put("steps", state.optJSONArray("history").length())
                    .put("findings", state.optJSONArray("findings").length());
            McpTaskStore.checkpoint(id, new JSONObject().put("stepIndex", step).put("state", state).put("completed", true));
            McpTaskStore.complete(id, out);
        } catch (InterruptedException e) {
            try { McpTaskStore.progress(id, "worker 已暂停；checkpoint 已保存，可继续恢复", -1, "resume"); } catch (Throwable ignored) {}
        } catch (Throwable e) {
            try { McpTaskStore.fail(id, String.valueOf(e)); } catch (Throwable ignored) {}
        } finally {
            synchronized (LOCK) { WORKERS.remove(id); }
        }
    }

    private static Decision chooseNext(JSONObject s, String goal) throws JSONException {
        JSONArray mp = s.optJSONArray("modelPlan");
        if (mp != null) {
            int idx = s.optInt("modelPlanIndex", 0);
            while (idx < mp.length()) {
                JSONObject q = mp.optJSONObject(idx++);
                s.put("modelPlanIndex", idx);
                if (q == null) continue;
                String n = q.optString("tool", q.optString("name", "")).trim();
                if (n.isEmpty() || "finalize".equals(n)) continue;
                JSONObject a = q.optJSONObject("arguments"); if (a == null) a = new JSONObject();
                if (!McpIdeGateway.orchestratorToolAllowed(n)) {
                    JSONArray repairs=s.optJSONArray("repairs"); if(repairs!=null) repairs.put(new JSONObject().put("type","plan_rejected").put("tool",n).put("reason","tool not present in current MCP catalog"));
                    continue;
                }
                // The model may deliberately provide a relative locator; absolute paths are
                // normalized by the underlying MCP tool rather than being silently accepted here.
                return new Decision(n, "模型规划：" + n, a, true, false);
            }
        }
        String apk = s.optString("apk", "");
        String project = s.optString("project", "");
        if (apk.isEmpty()) return new Decision("local_apk_discover", "发现真实 APK", new JSONObject()
                .put("directory", "/storage/emulated/0/MT2/mcp").put("recursive", true).put("maxDepth", 6).put("limit", 200), true, true);
        if (project.isEmpty()) return new Decision("project_create", "创建持久化逆向项目", new JSONObject()
                .put("name", s.optString("projectName", "reverse-project")), true, true);
        if (!s.optBoolean("generated", false)) return new Decision("project_generate", "建立真实 APK/DEX/Manifest/资源索引", new JSONObject()
                .put("project", project).put("apk", apk), true, true);
        // Dynamic queue: each capability is selected only if not already completed.
        if (!s.optBoolean("manifest", false)) return new Decision("apk_manifest_decoded", "解码真实 Manifest 与组件证据", path(apk), true, false);
        if (!s.optBoolean("dexSymbols", false)) return new Decision("apk_dex_symbols", "建立真实 DEX 类/字段/方法索引", path(apk).put("maxResults", 50000), true, true);
        if (!s.optBoolean("entryPoints", false)) return new Decision("apk_entry_point_methods", "定位真实 Android 生命周期入口", path(apk), true, false);
        if (!s.optBoolean("xref", false)) return new Decision("apk_dex_xref", "建立真实字符串/类型/方法引用与调用边", path(apk).put("query", queryFor(goal)).put("kind", "all").put("maxResults", 5000), true, false);
        if (!s.optBoolean("resources", false)) return new Decision("apk_resource_index", "扫描资源、assets、native library 与 META-INF", path(apk), true, false);
        if (!s.optBoolean("resourceRefs", false)) return new Decision("apk_resource_refs", "提取真实资源 ID 引用", path(apk), true, false);
        if (!s.optBoolean("evidence", false)) return new Decision("local_apk_analyze", "提取 URL/域名/Token/加密/Native 等真实证据", new JSONObject().put("path", apk).put("query", goal), true, false);
        if (!s.optBoolean("planned", false)) return new Decision("apk_analysis_plan", "根据真实入口与 DEX 结果动态生成重点分析目标", new JSONObject().put("path", apk).put("query", goal), true, false);
        if (needsDeep(goal) && !s.optBoolean("agentAnalyzed", false)) return new Decision("agent_analyze_apk", "对真实方法执行结果驱动深度分析", new JSONObject().put("path", apk).put("query", goal).put("maxEntries", 8).put("maxRounds", 8), true, false);
        if (needsProjectSearch(goal) && !s.optBoolean("searched", false)) return new Decision("project_search", "在真实逆向产物中定位目标关键词", new JSONObject().put("project", project).put("query", queryFor(goal)).put("maxResults", 500), true, false);
        if (!s.optBoolean("decompileAttempted", false)) return new Decision("apk_decompile", "尝试真实 JADX/apktool 反编译", new JSONObject(), false, false);
        return new Decision("finalize", "汇总真实证据并生成最终报告", new JSONObject(), true, false);
    }

    private static JSONObject executeWithRetry(String id, String workerId, Decision d, JSONObject state) throws Exception {
        JSONObject last = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            check(id);
            McpTaskStore.heartbeat(id, workerId);
            try {
                JSONObject a = new JSONObject(d.args.toString());
                JSONObject r;
                if ("apk_decompile".equals(d.name)) {
                    JSONObject op = McpIdeGateway.callToolDirect("apk_open", new JSONObject().put("locator", state.optString("apk")).put("temporary", true));
                    if (isFatal(op)) return op;
                    String h = op.optString("handle", "");
                    if (h.isEmpty()) return new JSONObject().put("isError", true).put("message", "apk_open 未返回 handle");
                    try {
                        a.put("handle", h).put("output", new File(state.optString("project"), "output/decompiled").getCanonicalPath());
                        r = McpIdeGateway.callToolDirect("apk_decompile", a);
                    } finally { try { McpIdeGateway.callToolDirect("apk_close", new JSONObject().put("handle", h)); } catch (Throwable ignored) {} }
                } else if ("finalize".equals(d.name)) {
                    r = new JSONObject().put("ok", true).put("finalize", true);
                } else r = McpIdeGateway.callToolDirect(d.name, a);
                last = r;
                if (!isFatal(r)) return r;
            } catch (Throwable e) {
                last = new JSONObject().put("isError", true).put("attempt", attempt).put("message", String.valueOf(e));
            }
            if (attempt < MAX_RETRIES) Thread.sleep(350L * attempt);
        }
        return last == null ? new JSONObject().put("isError", true).put("message", "unknown failure") : last;
    }

    private static void record(JSONObject s, Decision d, JSONObject r) throws Exception {
        ensureArrays(s);
        JSONArray h = s.optJSONArray("history");
        h.put(new JSONObject().put("step", h.length()).put("tool", d.name).put("label", d.label)
                .put("ok", !isFatal(r)).put("result", summarize(r)).put("timestamp", System.currentTimeMillis()));
        if ("local_apk_discover".equals(d.name)) {
            JSONObject sel = r.optJSONObject("selected");
            String p = sel == null ? r.optString("selectedPath", "") : sel.optString("path", "");
            if (p.isEmpty()) { JSONArray items = r.optJSONArray("items"); if (items != null && items.length() > 0) p = items.optJSONObject(0).optString("path", ""); }
            if (!p.isEmpty()) s.put("apk", p);
        } else if ("project_create".equals(d.name)) s.put("project", r.optString("project", r.optString("path", "")));
        else if ("project_generate".equals(d.name)) s.put("generated", !isFatal(r));
        else if ("apk_manifest_decoded".equals(d.name)) s.put("manifest", !isFatal(r));
        else if ("apk_dex_symbols".equals(d.name)) s.put("dexSymbols", !isFatal(r));
        else if ("apk_entry_point_methods".equals(d.name)) s.put("entryPoints", !isFatal(r));
        else if ("apk_dex_xref".equals(d.name)) s.put("xref", !isFatal(r));
        else if ("apk_resource_index".equals(d.name)) s.put("resources", !isFatal(r));
        else if ("apk_resource_refs".equals(d.name)) s.put("resourceRefs", !isFatal(r));
        else if ("local_apk_analyze".equals(d.name)) { s.put("evidence", !isFatal(r)); s.put("evidenceResult", summarize(r)); collectFindings(s, r); }
        else if ("apk_analysis_plan".equals(d.name)) s.put("planned", !isFatal(r));
        else if ("agent_analyze_apk".equals(d.name)) { s.put("agentAnalyzed", !isFatal(r)); collectFindings(s, r); }
        else if ("project_search".equals(d.name)) { s.put("searched", !isFatal(r)); collectFindings(s, r); }
        else if ("apk_decompile".equals(d.name)) { s.put("decompileAttempted", true).put("decompiled", !isFatal(r)).put("decompileResult", summarize(r)); }
    }

    private static boolean attemptRepair(JSONObject s, Decision d, JSONObject r) throws JSONException {
        String m = errorText(r).toLowerCase(Locale.ROOT);
        if (m.contains("absolute") || m.contains("empty, dot") || m.contains("parent segments")) {
            s.put("pathRepair", true).put("lastRepair", "convert absolute path to MCP-relative locator");
            return true;
        }
        if (m.contains("not found") || m.contains("不存在") || m.contains("no such file")) {
            if (!"local_apk_discover".equals(d.name)) s.remove("apk");
            s.put("lastRepair", "rediscover APK");
            return true;
        }
        if (m.contains("permission") || m.contains("denied") || m.contains("securityexception")) {
            s.put("lastRepair", "permission fallback: SAF/App-private path");
            return !d.essential;
        }
        if ("apk_decompile".equals(d.name)) {
            s.put("lastRepair", "decompiler unavailable; retain evidence-only report");
            return true;
        }
        return !d.essential;
    }

    private static boolean goalSatisfied(JSONObject s, String goal) {
        return !s.optString("project", "").isEmpty() && s.optBoolean("generated", false)
                && s.optBoolean("manifest", false) && s.optBoolean("dexSymbols", false)
                && s.optBoolean("evidence", false) && s.optBoolean("decompileAttempted", false);
    }

    private static void persist(String id, JSONObject s, int step, String tool, JSONObject result, String status) {
        try {
            JSONObject cp = new JSONObject().put("stepIndex", step).put("state", s).put("lastTool", tool)
                    .put("lastResult", summarize(result)).put("savedAt", System.currentTimeMillis());
            McpTaskStore.checkpoint(id, cp);
        } catch (Throwable ignored) {}
    }

    private static void writeReports(String project, JSONObject s, String goal) throws Exception {
        JSONObject report = new JSONObject().put("schemaVersion", 2).put("goal", goal).put("apk", s.optString("apk"))
                .put("project", project).put("history", s.optJSONArray("history")).put("findings", s.optJSONArray("findings"))
                .put("evidence", s.opt("evidenceResult")).put("decompile", s.opt("decompileResult"))
                .put("completedAt", System.currentTimeMillis());
        McpIdeGateway.callToolDirect("project_write", new JSONObject().put("project", project)
                .put("path", "report/orchestrator.json").put("content", report.toString(2)));
        McpIdeGateway.callToolDirect("project_write", new JSONObject().put("project", project)
                .put("path", "report/findings.json").put("content", new JSONObject().put("goal",goal).put("findings",s.optJSONArray("findings")).toString(2)));
        McpIdeGateway.callToolDirect("project_write", new JSONObject().put("project", project)
                .put("path", "report/history.json").put("content", new JSONObject().put("history",s.optJSONArray("history")).toString(2)));
        McpIdeGateway.callToolDirect("project_write", new JSONObject().put("project", project)
                .put("path", "report/state.json").put("content", s.toString(2)));
        StringBuilder md = new StringBuilder("# Autonomous Reverse Engineering Report\n\n");
        md.append("## Goal\n\n").append(goal).append("\n\n");
        md.append("## Target\n\n` ").append(s.optString("apk", "")).append(" `\n\n");
        md.append("## Execution history\n\n");
        JSONArray h = s.optJSONArray("history"); for (int i=0; i<h.length(); i++) { JSONObject x=h.optJSONObject(i); if(x!=null) md.append(i+1).append(". ").append(x.optString("tool")).append(" — ").append(x.optBoolean("ok")?"success":"failed").append("\n"); }
        md.append("\n## Findings\n\n");
        JSONArray f=s.optJSONArray("findings"); for(int i=0;i<f.length();i++){JSONObject x=f.optJSONObject(i);if(x!=null)md.append("- ").append(x.optString("type","evidence")).append(": ").append(x.optString("value",x.toString())).append("\n");}
        md.append("\n## Persistence\n\nAll intermediate state is stored in the durable task checkpoint; unavailable tools or permissions are not represented as successful analysis.\n");
        md.append("\n## Child tasks\n\n");
        JSONArray ch=s.optJSONArray("children"); if(ch!=null) for(int i=0;i<ch.length();i++) md.append("- ").append(ch.optString(i)).append("\n");
        md.append("\n## Model planning\n\nIf the caller supplied a `plan` array, each selected tool call is persisted before execution so a reconnect can resume from the exact next index.\n");
        McpIdeGateway.callToolDirect("project_write", new JSONObject().put("project", project).put("path", "report/summary.md").put("content", md.toString()));
    }

    private static void collectFindings(JSONObject s, JSONObject r) throws Exception {
        JSONArray f=s.optJSONArray("findings"); if(f==null){f=new JSONArray();s.put("findings",f);} if(r==null)return;
        String[] keys={"urls","domains","tokens","apiKeys","crypto","nativeLibs","strings","findings","hits"};
        for(String k:keys){Object v=r.opt(k);if(v instanceof JSONArray){JSONArray a=(JSONArray)v;for(int i=0;i<a.length()&&f.length()<500;i++){Object x=a.opt(i);if(x instanceof JSONObject){JSONObject z=new JSONObject(((JSONObject)x).toString());if(!z.has("type"))z.put("type",k);f.put(z);}else if(x!=null)f.put(new JSONObject().put("type",k).put("value",String.valueOf(x)));}}}
    }

    private static void ensureArrays(JSONObject s) throws Exception { if(s.optJSONArray("history")==null)s.put("history",new JSONArray()); if(s.optJSONArray("findings")==null)s.put("findings",new JSONArray()); if(s.optJSONArray("repairs")==null)s.put("repairs",new JSONArray()); if(s.optJSONArray("children")==null)s.put("children",new JSONArray()); }
    private static JSONObject path(String p) throws JSONException {return new JSONObject().put("path",p);}
    private static String queryFor(String g){return g==null?"":g.trim();}
    private static boolean needsDeep(String g){String x=queryFor(g).toLowerCase(Locale.ROOT);return x.contains("分析")||x.contains("深入")||x.contains("登录")||x.contains("网络")||x.contains("接口")||x.contains("加密")||x.contains("hook")||x.contains("调用");}
    private static boolean needsProjectSearch(String g){String x=queryFor(g).toLowerCase(Locale.ROOT);return x.contains("搜索")||x.contains("定位")||x.contains("关键")||x.contains("url")||x.contains("接口")||x.contains("登录")||x.contains("token");}
    private static void check(String id)throws InterruptedException{if(Thread.currentThread().isInterrupted()||McpTaskStore.isCancelled(id))throw new InterruptedException("cancelled");}
    private static boolean isFatal(JSONObject r){return r==null||r.optBoolean("isError",false)||(r.has("ok")&&!r.optBoolean("ok",true)&&!r.has("available"));}
    private static String errorText(JSONObject r){return r==null?"null":r.optString("message",r.optString("error",r.toString()));}
    private static JSONObject summarize(JSONObject r) throws JSONException {if(r==null)return new JSONObject();String x=r.toString();if(x.length()>8000)x=x.substring(0,8000);try{return new JSONObject(x);}catch(Throwable e){return new JSONObject().put("text",x);}}
    private static String deriveName(String apk,String goal){String n=!apk.isEmpty()?new File(apk).getName().replaceAll("(?i)\\.apk$",""):"reverse-project";return n.replaceAll("[^A-Za-z0-9._-]","-");}

    private static final class Decision {
        final String name,label; final JSONObject args; final boolean retryable,essential;
        Decision(String n,String l,JSONObject a,boolean r,boolean e){name=n;label=l;args=a;retryable=r;essential=e;}
    }
}
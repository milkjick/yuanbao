package com.example.yuanbaossehook;
import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;

/**
 * Pure Android/Xposed MCP gateway.
 * IDE -> :8788 -> YuanBao bridge :8318 -> MCP servers.
 * No Python/backend process is required.
 */
final class McpIdeGateway {
    private static final String TAG = "YB-MCP";
    private static final int PORT = 8788;
    private static final String UPSTREAM = "http://127.0.0.1:8318/v1/chat/completions";
    private static final int MAX_ROUNDS = 10;
    private static final int MAX_CALLS = 5;
    private static final long TIMEOUT = 60000L;
    private static final long PROBE_TIMEOUT = 7000L;
    private static final String MCP_ROOT = "/storage/emulated/0/MT2";
    static String MCP_ROOT_PUBLIC(){ return MCP_ROOT; }
    static Context applicationContextPublic(){ return applicationContext; }
    static JSONObject shellExecPublic(JSONObject a) throws Exception { return shellExec(a); }
    private static final AtomicInteger IDS = new AtomicInteger(1);
    private static final ConcurrentHashMap<String, File> APK_HANDLES = new ConcurrentHashMap<>();
    private static final long PROJECT_LOCK_STALE_MS = 90_000L;
    private static volatile boolean started;
    private static volatile ServerSocket serverSocket;
    /** Application context from the YuanBao process. Used only for storage diagnostics / MediaStore fallback. */
    private static volatile Context applicationContext;

    static void setApplicationContext(Context context) {
        if (context == null) return;
        try { applicationContext = context.getApplicationContext(); } catch (Throwable ignored) { applicationContext = context; }
        // Hand the loopback bridge token to the module process before anything tries to use it.
        try { writeBridgeHandshake(); } catch (Throwable ignored) {}
        try { McpTaskStore.init(applicationContext); } catch (Throwable ignored) {}
        try { recoverPersistentProjectTasks(); } catch (Throwable e) { log("[PROJECT] recovery scan failed: " + e); }
        try { AgentOrchestrator.recoverAll(); } catch (Throwable e) { log("[AGENT] durable recovery failed: " + e); }
    }

    private static volatile boolean PROJECT_RECOVERY_STARTED = false;
    private static void recoverPersistentProjectTasks() {
        if (PROJECT_RECOVERY_STARTED) return;
        synchronized (McpIdeGateway.class) {
            if (PROJECT_RECOVERY_STARTED) return;
            PROJECT_RECOVERY_STARTED = true;
            File root = new File(MCP_ROOT, "projects");
            File[] dirs = root.listFiles(File::isDirectory);
            if (dirs == null) return;
            for (File p : dirs) {
                try {
                    File meta = new File(p, "project.json");
                    if (!meta.isFile()) continue;
                    JSONObject o = new JSONObject(new String(readAll(new FileInputStream(meta), 131072), StandardCharsets.UTF_8));
                    String status=o.optString("status","");
                    String apk=o.optString("source_apk","");
                    String next=o.optString("next_step","");
                    if (("paused".equals(status) || "queued".equals(status) || "working".equals(status)) && !apk.isEmpty() && new File(apk).isFile()) {
                        long hb=o.optLong("heartbeat",0L);
                        if ("working".equals(status) && hb>0 && System.currentTimeMillis()-hb < PROJECT_LOCK_STALE_MS) continue;
                        if (ACTIVE_PROJECT_FUTURE!=null && !ACTIVE_PROJECT_FUTURE.isDone()) return;
                        final String fp=new File(apk).getCanonicalPath(), pr=p.getCanonicalPath();
                        final String q=o.optString("query","");
                        final boolean dec=o.optBoolean("decompile",true);
                        final int rounds=Math.max(1,Math.min(12,o.optInt("maxRounds",8)));
                        ACTIVE_PROJECT=pr; ACTIVE_PROJECT_TASK="recovering"; ACTIVE_PROJECT_HEARTBEAT=System.currentTimeMillis();
                        ACTIVE_PROJECT_FUTURE=PROJECT_EXECUTOR.submit(() -> runAutonomousPipeline(fp,pr,q,dec,rounds));
                        log("[PROJECT] recovered persistent task project="+pr+" next="+next);
                        return;
                    }
                } catch(Throwable e) { log("[PROJECT] recovery ignored for "+p+": "+e); }
            }
        }
    }

    private static String stableId(String name,String url){
        try{ MessageDigest md=MessageDigest.getInstance("SHA-256"); byte[] b=md.digest((name+"\n"+url).getBytes(StandardCharsets.UTF_8)); StringBuilder x=new StringBuilder(); for(int i=0;i<6&&i<b.length;i++) x.append(String.format(Locale.ROOT,"%02x",b[i])); return x.toString(); }catch(Throwable e){ return Integer.toHexString((name+"\n"+url).hashCode()); }
    }

    private static List<ServerDef> serverDefs() {
        List<ServerDef> out=new ArrayList<>();
        JSONArray a=BridgeConfig.servers();
        for(int i=0;i<a.length();i++){ JSONObject o=a.optJSONObject(i); if(o==null || !o.optBoolean("enabled",true)) continue;
            String n=o.optString("name","").trim(), u=o.optString("url","").trim();
            if(!n.isEmpty()&&!u.isEmpty()) {
                String client=o.optString("clientName", "").trim();
                String id=o.optString("id", "").trim();
                if(id.isEmpty()) id=stableId(n+"\n"+client,u);
                if(client.isEmpty()) client="yuanbao-xposed/"+id;
                JSONObject headers=o.optJSONObject("headers");
                String routeHeader=o.optString("routeHeader","").trim();
                String routeValue=o.optString("routeValue","").trim();
                String fallback=o.optString("fallbackUrl","").trim();
                out.add(new ServerDef(n,u,id,client,headers,routeHeader,routeValue,fallback));
            }
        }
        return out;
    }
    private static volatile List<ToolDef> cachedTools = Collections.emptyList();
    // Last successfully discovered EXTERNAL (non-builtin) tools, keyed by public name.
    // Kept across transient discovery failures so a temporarily unreachable MCP server
    // does not silently erase the whole external tool catalog from the model's view.
    private static volatile Map<String,ToolDef> lastExternalTools = Collections.emptyMap();
    private static volatile long lastExternalToolsAt = 0L;
    private static final long EXTERNAL_TOOLS_TTL_MS = 10 * 60_000L;
    // Throttle full network re-discovery so the native agent loop does not block on a
    // slow/unreachable MCP server on every turn.
    private static volatile long lastRefreshAt = 0L;
    private static final long REFRESH_THROTTLE_MS = 15_000L;
    /** Per-server discovery status, exposed through diagnostics so "no tools" is explainable. */
    private static final ConcurrentHashMap<String,JSONObject> serverStatus = new ConcurrentHashMap<>();
    // Last substantive native task, used only to interpret short follow-up messages such as
    // "继续分析". It expires quickly and never stores credentials or session material.
    private static volatile String lastNativeTask = "";
    private static volatile long lastNativeTaskAt = 0L;
    private static volatile String ACTIVE_LOCAL_APK_PATH = "";
    private static volatile long ACTIVE_LOCAL_APK_AT = 0L;
    private static volatile String ACTIVE_PROJECT = "";
    private static volatile String ACTIVE_PROJECT_TASK = "IDLE";
    private static volatile long ACTIVE_PROJECT_HEARTBEAT = 0L;
    private static final ExecutorService PROJECT_EXECUTOR = Executors.newSingleThreadExecutor(r -> { Thread t=new Thread(r,"YB-project-runner"); t.setDaemon(true); return t; });
    private static volatile Future<?> ACTIVE_PROJECT_FUTURE;

    static boolean isRunning(){ return started && serverSocket != null && !serverSocket.isClosed(); }
    static int toolCount(){ return cachedTools.size(); }
    static int externalToolCount(){ int n=0; for(ToolDef t:cachedTools) if(!"builtin".equals(t.server)) n++; return n; }

    static String serverDiscoverySummary() {
        StringBuilder b = new StringBuilder();
        for (ServerDef s : serverDefs()) {
            int count = 0;
            for (ToolDef t : cachedTools) if (t != null && s.id.equals(t.serverId)) count++;
            b.append("server=").append(s.name).append(" id=").append(s.id)
             .append(" url=").append(s.url).append(" tools=").append(count).append('\n');
        }
        return b.toString();
    }
    static String transportDiagnostics() {
        StringBuilder b=new StringBuilder();
        for(ServerDef s:serverDefs()) {
            long startedAt=System.currentTimeMillis();
            try {
                ProbeResult r=probeServer(s);
                b.append("✓ ").append(s.name).append(" | ").append(r.protocol)
                 .append(" | HTTP ").append(r.http).append(" | tools=").append(r.tools)
                 .append(" | ").append(System.currentTimeMillis()-startedAt).append("ms\n");
            } catch(Throwable e) {
                b.append("✗ ").append(s.name).append(" | HTTP ").append(lastHttpCode)
                 .append(" | ").append(shorten(String.valueOf(e),700)).append("\n");
            }
        }
        if(b.length()==0) b.append("没有启用的 MCP Server\n");
        return b.toString();
    }

    static String toolDiagnostics(){
        StringBuilder b=new StringBuilder();
        for(ServerDef s:serverDefs()){
            try {
                JSONObject r=probeForDiagnostics(s);
                b.append("✓ ").append(s.name).append(" HTTP ").append(r.optInt("http",-1))
                 .append(" ").append(r.optString("protocol","?"))
                 .append(" tools=").append(r.optInt("tools",0)).append("\n");
            } catch(Throwable e){ b.append("✗ ").append(s.name).append(" ").append(shorten(e.toString(),500)).append("\n"); }
        }
        JSONObject st = serverStatusSummary();
        if(st.length()>0) b.append("\n最近一次发现状态：\n").append(st.toString());
        return b.toString();
    }

    /** Human-readable, non-secret status of the last discovery attempt per MCP server. */
    static JSONObject serverStatusSummary(){
        JSONObject out=new JSONObject();
        try {
            for(Map.Entry<String,JSONObject> e:serverStatus.entrySet()){
                JSONObject v=e.getValue(); if(v==null)continue;
                String name=v.optString("name",e.getKey());
                JSONObject x=new JSONObject();
                x.put("url",v.optString("url",""));
                x.put("ok",v.optBoolean("ok",false));
                x.put("tools",v.optInt("tools",0));
                if(v.has("error")) x.put("error",v.optString("error",""));
                long at=v.optLong("at",0L);
                if(at>0) x.put("age_ms", System.currentTimeMillis()-at);
                out.put(name,x);
            }
        } catch(Throwable ignored){}
        return out;
    }

    /** One-line reason explaining why external tools are (not) available right now. */
    static String externalToolsStatusLine(){
        int ext=externalToolCount();
        if(ext>0) return "外部 MCP 工具可用："+ext+" 个";
        if(serverDefs().isEmpty()) return "未配置任何外部 MCP 服务器（设置 → MCP 工具服务器）";
        if(!lastExternalTools.isEmpty() && System.currentTimeMillis()-lastExternalToolsAt < EXTERNAL_TOOLS_TTL_MS)
            return "外部服务器暂时不可达，正在使用 "+(System.currentTimeMillis()-lastExternalToolsAt)/1000+" 秒前的缓存工具目录（"+lastExternalTools.size()+" 个）";
        StringBuilder b=new StringBuilder("外部 MCP 工具不可用：");
        for(Map.Entry<String,JSONObject> e:serverStatus.entrySet()){
            JSONObject v=e.getValue(); if(v==null)continue;
            b.append("\n- ").append(v.optString("name",e.getKey())).append(" → ")
             .append(v.optBoolean("ok",false)?"已连通但未返回工具":"连接失败: "+v.optString("error","未知错误"));
        }
        return b.toString();
    }
    static void refreshForSettings(){ refreshTools(true); }

    /** Restart the HTTP gateway so a changed bind mode takes effect immediately. */
    static void restart() {
        stop();
        start();
    }

    static void stop() {
        synchronized (McpIdeGateway.class) {
            started = false;
            ServerSocket ss = serverSocket;
            serverSocket = null;
            if (ss != null) { try { ss.close(); } catch (Throwable ignored) {} }
        }
    }

    /** Probe every enabled MCP server modern-first, with standard legacy fallback. */
    static JSONArray testMcpServers() throws Exception {
        JSONArray out = new JSONArray();
        JSONArray configured = BridgeConfig.servers();
        for (int i = 0; i < configured.length(); i++) {
            JSONObject cfg = configured.optJSONObject(i);
            if (cfg == null || !cfg.optBoolean("enabled", true)) continue;
            String name = cfg.optString("name", "server").trim();
            String url = cfg.optString("url", "").trim();
            long startedAt = System.currentTimeMillis();
            JSONObject r = new JSONObject().put("name", name).put("url", url);
            try {
                if (url.isEmpty()) throw new IOException("MCP URL is empty");
                ProbeResult pr = probeServer(newServerDef(name, url, cfg));
                r.put("ok", true)
                 .put("http", pr.http)
                 .put("tools", pr.tools)
                 .put("protocol", pr.protocol)
                 .put("latency_ms", System.currentTimeMillis() - startedAt)
                 .put("message", "连接成功，tools/list 正常");
            } catch (Throwable e) {
                int code = (e instanceof McpHttpException) ? ((McpHttpException)e).code : -1;
                r.put("ok", false)
                 .put("http", code)
                 .put("tools", 0)
                 .put("protocol", "unknown")
                 .put("latency_ms", System.currentTimeMillis() - startedAt)
                 .put("message", shorten(String.valueOf(e), 900));
                log("MCP 2026 status " + name + ": " + e);
            }
            out.put(r);
        }
        return out;
    }

    private static ServerDef newServerDef(String name,String url,JSONObject cfg){
        String client=cfg==null?"":cfg.optString("clientName","").trim();
        String id=cfg==null?"":cfg.optString("id","").trim();
        if(id.isEmpty()) id=stableId(name+"\n"+client,url);
        if(client.isEmpty()) client="yuanbao-xposed/"+id;
        return new ServerDef(name,url,id,client,cfg==null?null:cfg.optJSONObject("headers"),cfg==null?null:cfg.optString("routeHeader","").trim(),cfg==null?null:cfg.optString("routeValue","").trim(),cfg==null?null:cfg.optString("fallbackUrl","").trim());
    }

    private static JSONObject probeForDiagnostics(ServerDef s) throws Exception {
        ProbeResult r=probeServer(s);
        return new JSONObject().put("http",r.http).put("protocol",r.protocol).put("tools",r.tools);
    }

    private static ProbeResult probeServer(ServerDef s) throws Exception {
        // Prefer the modern 2026-07-28 wire format. If the endpoint rejects
        // that format, transparently try the standard pre-2026 handshake.
        // No MCP endpoint is hard-coded; the configured ServerDef is the only target.
        try {
            log("[MCP] connecting url=" + s.url + (s.fallbackUrl.isEmpty() ? "" : " fallback=" + s.fallbackUrl));
            log("[MCP] tools/list -> server=" + s.name);
            JSONObject list = postMcpModern(s, "tools/list", new JSONObject(), null);
            int count = countTools(list);
            if (count == 0 && !s.fallbackUrl.isEmpty() && !s.fallbackUrl.equals(s.url)) {
                log("[MCP] tools/list returned 0; trying fallback url=" + s.fallbackUrl);
                list = postMcpModernAt(s, s.fallbackUrl, "tools/list", new JSONObject(), null);
                count = countTools(list);
            }
            log("[MCP] tools/list ok, count=" + count + " protocol=2026-07-28");
            return new ProbeResult("2026-07-28", lastHttpCode, count);
        } catch (Throwable modernError) {
            log("[MCP] modern probe failed server="+s.name+"; trying legacy compatibility: "+shorten(String.valueOf(modernError),500));
            LegacySession legacy = legacyInitialize(s);
            JSONObject list = postMcpLegacy(s, legacy, "tools/list", new JSONObject());
            int count = countTools(list);
            return new ProbeResult(legacy.protocolVersion, lastHttpCode, count);
        }
    }

    private static boolean containsString(JSONArray a, String wanted) {
        if (a == null || wanted == null) return false;
        for (int i = 0; i < a.length(); i++) {
            if (wanted.equals(a.optString(i, ""))) return true;
        }
        return false;
    }

    private static Exception asException(Throwable t) {
        if (t instanceof Exception) return (Exception)t;
        return new IOException(String.valueOf(t));
    }

    private static int countTools(JSONObject r) throws Exception {
        if (r == null) throw new IOException("empty MCP response");
        if (r.has("error")) throw new IOException("MCP JSON-RPC error: " + r.optJSONObject("error"));
        JSONObject result = r.optJSONObject("result");
        if (result == null) throw new IOException("MCP response has no result");
        JSONArray tools = result.optJSONArray("tools");
        if (tools == null) throw new IOException("MCP tools/list returned no tools array");
        return tools.length();
    }

    private static volatile int lastHttpCode = -1;

    static String lanHostAddress() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Throwable e) { log("lanHostAddress: " + e); }
        return "<手机局域网IP>";
    }

    static void start() {
        if (started) return;
        synchronized (McpIdeGateway.class) {
            if (started) return;
            started = true;
            Thread t = new Thread(new Runnable() {
                @Override public void run() { serve(); }
            }, "YB-MCP-8788");
            t.setDaemon(true);
            t.start();
        }
    }

    private static void serve() {
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            String bindHost = BridgeConfig.lanEnabled() ? "0.0.0.0" : "127.0.0.1";
            ss.bind(new InetSocketAddress(InetAddress.getByName(bindHost), PORT));
            serverSocket = ss;
            log("listening " + bindHost + ":" + PORT + (BridgeConfig.lanEnabled() ? " (LAN enabled, API key required=" + BridgeConfig.auth() + ")" : ""));
            while (started && !ss.isClosed()) {
                final Socket s = ss.accept();
                Thread t = new Thread(new Runnable() { @Override public void run() { handle(s); } }, "YB-MCP-client");
                t.setDaemon(true);
                t.start();
            }
            try { ss.close(); } catch (Throwable ignored) {}
        } catch (Throwable e) {
            if (started) log("server failed: " + e);
        } finally {
            serverSocket = null;
        }
    }

    private static void handle(Socket socket) {
        try {
            socket.setSoTimeout((int) TIMEOUT);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] first = requestLine.split(" ", 3);
            if (first.length < 2) { writeJson(out, 400, error("bad request")); return; }
            String method = first[0];
            Map<String,String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int p = line.indexOf(':');
                if (p > 0) headers.put(line.substring(0,p).trim().toLowerCase(Locale.ROOT), line.substring(p+1).trim());
            }
            int len = 0;
            try { len = Integer.parseInt(headers.getOrDefault("content-length", "0")); } catch (Throwable ignored) {}
            String body = len > 0 ? new String(readFully(in, len), StandardCharsets.UTF_8) : "{}";
            String path = first[1];

            if ("OPTIONS".equalsIgnoreCase(method)) { writeOptions(out); return; }
            if (!authorized(headers, path)) { writeJson(out,401,error("unauthorized")); return; }
            if ("GET".equalsIgnoreCase(method) && "/health".equals(path)) { writeJson(out,200,health()); return; }
            if ("GET".equalsIgnoreCase(method) && "/v1/models".equals(path)) { writeJson(out,200,models()); return; }
            if ("GET".equalsIgnoreCase(method) && "/mcp/tools".equals(path)) { writeJson(out,200,toolsResponse()); return; }
            if ("POST".equalsIgnoreCase(method) && "/mcp".equals(path)) {
                JSONObject rpcReq=new JSONObject(body); JSONObject rpcResp=handleLocalMcp(rpcReq, headers); writeJson(out,200,rpcResp.toString()); return;
            }
            if ("POST".equalsIgnoreCase(method) && "/v1/chat/completions".equals(path)) {
                JSONObject req = new JSONObject(body);
                String projectHeader = headers.get("x-yb-project");
                if(projectHeader!=null && !projectHeader.trim().isEmpty()) req.put("_yb_project", projectHeader.trim());
                JSONObject response = runAgent(req);
                if (req.optBoolean("stream", false)) writeSse(out, response);
                else writeJson(out, 200, response.toString());
                return;
            }
            writeJson(out, 404, error("not found"));
        } catch (Throwable e) {
            log("client error: " + e);
            try { writeJson(socket.getOutputStream(), 500, error(e.toString())); } catch (Throwable ignored) {}
        } finally { try { socket.close(); } catch (Throwable ignored) {} }
    }

    private static JSONObject runAgent(JSONObject req) throws Exception {
        refreshTools();
        JSONArray input = req.optJSONArray("messages");
        List<JSONObject> messages = new ArrayList<>();
        if (input != null) {
            for (int i = 0; i < input.length(); i++) {
                JSONObject m = input.optJSONObject(i);
                if (m != null) messages.add(new JSONObject(m.toString()));
            }
        }
        JSONObject meta = req.optJSONObject("metadata");
        String project = req.optString("_yb_project",
                meta == null ? BridgeConfig.project() : meta.optString("project_id", BridgeConfig.project()));
        if (project == null || project.trim().isEmpty()) project = BridgeConfig.project();
        String memory = BridgeConfig.memory(project);
        String system = BridgeConfig.systemPrompt() + "\n\n" + buildToolPrompt(cachedTools);
        if (memory != null && !memory.trim().isEmpty()) {
            system += "\n\n[PROJECT MEMORY: " + project + "]\n" + memory + "\n[/PROJECT MEMORY]";
        }
        messages.add(0, new JSONObject().put("role", "system").put("content", system));

        /*
         * 3.4.5: Standard OpenAI / DeepSeek-style MCP Agent Loop.
         *
         * When the client supplies tools, the gateway now performs the complete loop itself:
         * model assistant.tool_calls -> MCP tools/call -> role=tool -> next model turn.
         * Native tool_calls are kept as structured JSON; they are never converted to
         * <yb_tool_call> protocol text in this path. The loop ends only when the model
         * returns an assistant message without tool_calls.
         */
        JSONArray clientTools = req.optJSONArray("tools");
        boolean standardToolMode = clientTools != null && clientTools.length() > 0;
        if (standardToolMode) {
            String model = req.optString("model", "yuanbao-mcp");
            Set<String> executed = new HashSet<>();
            JSONObject lastAnswer = null;
            for (int round = 1; round <= MAX_ROUNDS; round++) {
                JSONObject up = new JSONObject(req.toString());
                up.remove("_yb_project");
                up.put("stream", false);
                up.put("tool_choice", "auto");
                // The MCP catalog is always supplied to the model. Client-supplied tools are
                // retained and merged by function name so the agent can use both sets.
                JSONArray mergedTools = mergeTools(clientTools, openAiTools(cachedTools));
                up.put("tools", mergedTools);
                JSONArray ma = new JSONArray();
                for (JSONObject m : messages) ma.put(m);
                up.put("messages", ma);
                log("[OPENAI-TOOLS] agent loop round=" + round + " tools.len=" + mergedTools.length());

                lastAnswer = postJson(UPSTREAM, up, null);
                JSONObject assistant = extractAssistantMessage(lastAnswer);
                if (assistant == null) break;
                JSONArray calls = assistant.optJSONArray("tool_calls");
                if (calls == null || calls.length() == 0) {
                    log("[OPENAI-TOOLS] final answer round=" + round + " no tool_calls");
                    return normalizeStandardToolResponse(lastAnswer, model);
                }

                // Preserve the native assistant.tool_calls object exactly; do not convert it
                // into XML/private protocol text. Then append one role=tool result per call.
                messages.add(new JSONObject(assistant.toString()));
                int callCount = Math.min(calls.length(), MAX_CALLS);
                boolean any = false;
                for (int i = 0; i < callCount; i++) {
                    JSONObject tc = calls.optJSONObject(i);
                    if (tc == null) continue;
                    JSONObject fn = tc.optJSONObject("function");
                    String name = fn == null ? tc.optString("name", "") : fn.optString("name", "");
                    String argText = fn == null ? tc.optString("arguments", "{}") : fn.optString("arguments", "{}");
                    if (name == null || name.trim().isEmpty()) continue;
                    JSONObject args;
                    try { args = new JSONObject(argText == null || argText.trim().isEmpty() ? "{}" : argText); }
                    catch (Throwable badArgs) {
                        args = new JSONObject();
                        log("[MCP] tool_call invalid arguments name=" + name + " error=" + shorten(String.valueOf(badArgs), 500));
                    }
                    String toolCallId = tc.optString("id", "yb_" + IDS.incrementAndGet());
                    String key = name + "\u0000" + args.toString();
                    if (executed.contains(key)) {
                        log("[MCP] duplicate tool_call suppressed name=" + name);
                        continue;
                    }
                    executed.add(key);
                    any = true;
                    String result;
                    try {
                        result = callTool(name, args).toString();
                    } catch (Throwable e) {
                        result = new JSONObject().put("isError", true).put("tool", name).put("message", String.valueOf(e)).toString();
                    }
                    if (result.length() > 12000) result = result.substring(0, 6000) + "\n...[truncated]...\n" + result.substring(result.length() - 6000);
                    messages.add(new JSONObject().put("role", "tool")
                            .put("tool_call_id", toolCallId)
                            .put("content", result));
                    log("[MCP] role=tool tool_call_id=" + toolCallId + " name=" + name + " resultLen=" + result.length());
                }
                if (!any) break;
            }
            if (lastAnswer != null) return normalizeStandardToolResponse(lastAnswer, model);
        }

        // Legacy/autonomous gateway mode: no client tools were supplied, so the gateway owns
        // the tool loop and returns the final answer as ordinary text.
        String finalText = "";
        String model = req.optString("model", "yuanbao-mcp");
        int rounds = 0;
        Set<String> executed = new HashSet<>();
        while (rounds++ < MAX_ROUNDS) {
            JSONObject up = new JSONObject(req.toString());
            up.remove("_yb_project");
            up.put("stream", false);
            up.put("tool_choice", "auto");
            up.put("tools", openAiTools(cachedTools));
            JSONArray ma = new JSONArray();
            for (JSONObject m : messages) ma.put(m);
            up.put("messages", ma);

            JSONObject answer = postJson(UPSTREAM, up, null);
            finalText = extractContent(answer);
            List<Call> calls = parseCalls(finalText);
            if (calls.isEmpty()) calls = fallbackCalls(finalText, cachedTools, messages);
            log("round=" + rounds + " parsed tool calls=" + calls.size());
            if (calls.isEmpty()) break;
            if (calls.size() > MAX_CALLS) calls = new ArrayList<>(calls.subList(0, MAX_CALLS));

            messages.add(new JSONObject().put("role", "assistant").put("content", stripCalls(finalText)));
            boolean any = false;
            for (Call c : calls) {
                String key = c.name + "\u0000" + c.args.toString();
                if (executed.contains(key)) continue;
                executed.add(key);
                any = true;
                String result;
                try {
                    result = callTool(c.name, c.args).toString();
                } catch (Throwable e) {
                    result = new JSONObject().put("error", e.toString()).put("tool", c.name).toString();
                }
                if (result.length() > 12000) {
                    result = result.substring(0, 6000) + "\n...[truncated]...\n" + result.substring(result.length() - 6000);
                }
                messages.add(new JSONObject().put("role", "tool").put("name", c.name)
                        .put("tool_call_id", "yb_" + IDS.incrementAndGet()).put("content", result));
                messages.add(new JSONObject().put("role", "user").put("content",
                        "工具 " + c.name + " 已实际执行。下面是工具原始结果：\n" + result +
                                "\n继续完成原任务。先根据工具结果判断下一步；需要更多操作就继续调用工具。只有结果证实成功时才能声称成功。"));
            }
            if (!any) break;
        }
        JSONObject msg = new JSONObject().put("role", "assistant")
                .put("content", finalText == null ? "" : stripCalls(finalText));
        return new JSONObject().put("id", "chatcmpl-yb-" + System.currentTimeMillis())
                .put("object", "chat.completion").put("created", System.currentTimeMillis() / 1000)
                .put("model", model).put("choices", new JSONArray().put(new JSONObject()
                        .put("index", 0).put("message", msg).put("finish_reason", "stop")));
    }

    private static JSONArray mergeTools(JSONArray first, JSONArray second) throws Exception {
        LinkedHashMap<String, JSONObject> map = new LinkedHashMap<>();
        if (first != null) {
            for (int i=0;i<first.length();i++) {
                JSONObject x=first.optJSONObject(i); if(x==null) continue;
                JSONObject fn=x.optJSONObject("function");
                String n=fn==null?x.optString("name",""):fn.optString("name","");
                if(!n.isEmpty()) map.put(n,new JSONObject(x.toString()));
            }
        }
        if (second != null) {
            for (int i=0;i<second.length();i++) {
                JSONObject x=second.optJSONObject(i); if(x==null) continue;
                JSONObject fn=x.optJSONObject("function");
                String n=fn==null?x.optString("name",""):fn.optString("name","");
                if(!n.isEmpty() && !map.containsKey(n)) map.put(n,new JSONObject(x.toString()));
            }
        }
        JSONArray out=new JSONArray();
        for(JSONObject x:map.values()) out.put(x);
        return out;
    }

    private static JSONObject extractAssistantMessage(JSONObject answer) {
        try {
            JSONArray choices=answer==null?null:answer.optJSONArray("choices");
            JSONObject c=choices==null?null:choices.optJSONObject(0);
            JSONObject m=c==null?null:c.optJSONObject("message");
            return m==null?null:new JSONObject(m.toString());
        } catch(Throwable e) { log("[OPENAI-TOOLS] assistant message parse failed: "+e); return null; }
    }

    /** Keep the upstream assistant message in standard OpenAI shape; never wrap it in action JSON. */
    private static JSONObject normalizeStandardToolResponse(JSONObject answer, String requestedModel) throws Exception {
        if (answer == null) answer = new JSONObject();
        JSONObject out = new JSONObject();
        out.put("id", answer.optString("id", "chatcmpl-yb-" + System.currentTimeMillis()));
        out.put("object", "chat.completion");
        out.put("created", answer.optLong("created", System.currentTimeMillis() / 1000));
        out.put("model", answer.optString("model", requestedModel));
        JSONArray choices = answer.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            JSONObject msg = new JSONObject().put("role", "assistant").put("content", "");
            choices = new JSONArray().put(new JSONObject().put("index", 0).put("message", msg).put("finish_reason", "stop"));
        } else {
            JSONArray clean = new JSONArray();
            for (int i = 0; i < choices.length(); i++) {
                JSONObject c = choices.optJSONObject(i);
                if (c == null) continue;
                JSONObject cc = new JSONObject(c.toString());
                JSONObject m = cc.optJSONObject("message");
                if (m != null) {
                    // Explicitly remove any legacy wrapper fields if an upstream compatibility
                    // layer happened to add them. Native OpenAI tool_calls are preserved.
                    m.remove("action");
                    m.remove("params");
                    m.remove("reply");
                    m.remove("answer");
                    m.remove("output");
                }
                clean.put(cc);
            }
            if (clean.length() > 0) choices = clean;
        }
        out.put("choices", choices);
        if (answer.has("usage")) out.put("usage", answer.opt("usage"));
        return out;
    }

    private static void logStandardToolResponse(JSONObject response) {
        try {
            JSONArray choices = response.optJSONArray("choices");
            JSONObject c = choices == null ? null : choices.optJSONObject(0);
            JSONObject m = c == null ? null : c.optJSONObject("message");
            JSONArray tc = m == null ? null : m.optJSONArray("tool_calls");
            String content = m == null ? "" : m.optString("content", "");
            log("[OPENAI-TOOLS] response tool_calls=" + (tc == null ? 0 : tc.length()) +
                    " contentLen=" + (content == null ? 0 : content.length()) +
                    " finish=" + (c == null ? "" : c.optString("finish_reason", "")));
        } catch (Throwable e) { log("[OPENAI-TOOLS] response inspect failed: " + e); }
    }

    /**
     * Native YuanBao chat bridge: the normal in-app chat does NOT pass through /v1/chat/completions,
     * so the external Agent loop cannot see its messages. This method executes a small deterministic
     * MCP preflight for native chat requests and injects the REAL tool result into the next model turn.
     * It is deliberately conservative: destructive write/shell tools are never guessed from vague text.
     */
    static boolean shouldInterceptNativeAgent(String text) {
        return shouldInterceptNativeAgent(text, null);
    }

    static boolean shouldInterceptNativeAgent(String text, Object[] hookArgs) {
        if (!BridgeConfig.nativeAgent() || text == null) return false;
        if (containsNativeToolCall(text)) return true;
        String l = text.toLowerCase(Locale.ROOT);
        if (isNativeContinuation(text) && lastNativeTaskAt > 0
                && System.currentTimeMillis() - lastNativeTaskAt < 10 * 60_000L) {
            // Force: a throttled cache may still be empty right after the first failed probe,
            // which would silently refuse to intercept a legitimate continuation.
            try { refreshTools(true); return externalToolCount() > 0 || hasLocalBuiltinContinuationContext(); } catch (Throwable ignored) {}
        }
        boolean attachment = false;
        try { attachment = findAttachmentPath(hookArgs, 2) != null; } catch(Throwable ignored) {}
        // Do not maintain a fixed list of MCP tool names. The trigger is derived from the
        // currently discovered catalog, so a newly connected MCP server becomes usable
        // without another APK update. Explicit MCP/tool words remain a fast path.
        if (l.contains("mcp") || l.contains("调用工具") || l.contains("使用工具") || l.contains("工具")) return true;
        try {
            refreshTools(true);
            if (externalToolCount() == 0) return false;
            if (attachment && !cachedTools.isEmpty()) return true;
            return hasDynamicToolIntent(text);
        } catch(Throwable ignored) { return false; }
    }

    private static boolean hasDynamicToolIntent(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String l = text.toLowerCase(Locale.ROOT);
        String[] genericIntent = {
                "分析","检查","查看","读取","打开","搜索","查找","检索","列出","列表","目录",
                "反编译","解析","诊断","修复","修改","写入","保存","创建","删除","移动","复制",
                "构建","编译","打包","安装","运行","执行","命令","抓包","网络","请求","调用",
                "analy","inspect","read","open","search","find","list","directory","decompile",
                "diagnos","fix","edit","write","save","create","delete","move","copy","build",
                "compile","package","install","run","execute","command","network","request","invoke"
        };
        for (String k : genericIntent) if (l.contains(k.toLowerCase(Locale.ROOT))) return true;
        for (ToolDef t : cachedTools) {
            if (t == null || "builtin".equals(t.server)) continue;
            // A custom MCP server name is a first-class alias. Users often say
            // “use <server name>” without mentioning the generated mcp_<id>_ prefix.
            if (containsServerAlias(l, t.server)) return true;
            if (containsExactToolMention(l, t)) return true;
        }
        return false;
    }

    private static boolean containsServerAlias(String lowerText, String serverName) {
        if (lowerText == null || serverName == null) return false;
        String x = serverName.trim().toLowerCase(Locale.ROOT);
        if (x.length() < 2) return false;
        // Match the configured display name as a phrase, not as an identifier.
        return lowerText.contains(x);
    }

    private static boolean containsExactToolMention(String lowerText, ToolDef t) {
        String[] names = {t.publicName, t.realName, t.server, t.description};
        for (String n : names) {
            if (n == null) continue;
            String x = n.trim().toLowerCase(Locale.ROOT);
            if (x.length() < 3) continue;
            if (lowerText.contains(x)) return true;
        }
        return false;
    }

    static boolean isNativeContinuation(String text) {
        if (text == null) return false;
        String x = text.trim().toLowerCase(Locale.ROOT);
        if (x.equals("继续") || x.equals("继续分析") || x.equals("继续处理") || x.equals("继续检查")
                || x.equals("继续执行") || x.equals("继续查看") || x.equals("继续排查")
                || x.equals("continue") || x.equals("continue analysis") || x.equals("continue analyzing")) return true;
        // YuanBao can echo the exact MCP tool name after the gateway has already executed
        // the first deterministic call. Treat an exact discovered tool name as a continuation
        // only while a substantive native task is still active; otherwise a user-entered tool
        // name remains a normal explicit request.
        if (lastNativeTaskAt > 0 && System.currentTimeMillis() - lastNativeTaskAt < 10 * 60_000L
                && !lastNativeTask.isEmpty()) {
            for (ToolDef t : cachedTools) {
                if (t == null || "builtin".equals(t.server)) continue;
                String publicName = t.publicName == null ? "" : t.publicName.trim().toLowerCase(Locale.ROOT);
                String realName = t.realName == null ? "" : t.realName.trim().toLowerCase(Locale.ROOT);
                if ((!publicName.isEmpty() && x.equals(publicName)) || (!realName.isEmpty() && x.equals(realName))) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isNativeToolNameEcho(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String x = text.trim().toLowerCase(Locale.ROOT);
        if (lastNativeTaskAt <= 0 || System.currentTimeMillis() - lastNativeTaskAt >= 2 * 60_000L
                || lastNativeTask.isEmpty()) return false;
        for (ToolDef t : cachedTools) {
            if (t == null || "builtin".equals(t.server)) continue;
            String publicName = t.publicName == null ? "" : t.publicName.trim().toLowerCase(Locale.ROOT);
            String realName = t.realName == null ? "" : t.realName.trim().toLowerCase(Locale.ROOT);
            if ((!publicName.isEmpty() && x.equals(publicName)) || (!realName.isEmpty() && x.equals(realName))) return true;
            // Some YuanBao native-agent continuations expose the MCP name with a generated
            // server prefix even when the catalog stores a normalized public/real name. Treat
            // an exact single-token suffix match as the same tool-name echo, but only while a
            // recent native task is active; ordinary user text is not affected.
            String pBase = publicName;
            String rBase = realName;
            if (!pBase.isEmpty() && x.endsWith("_" + pBase)) return true;
            if (!rBase.isEmpty() && x.endsWith("_" + rBase)) return true;
            if (x.contains("mt_apk_list_available_apks") &&
                    (x.equals("mt_apk_list_available_apks") || x.endsWith("_mt_apk_list_available_apks"))) return true;
        }
        return false;
    }

    static String nativeIntentSource(String text) {
        if (isNativeContinuation(text) && lastNativeTaskAt > 0
                && System.currentTimeMillis() - lastNativeTaskAt < 10 * 60_000L
                && !lastNativeTask.isEmpty()) return lastNativeTask;
        return text == null ? "" : text;
    }

    static void rememberNativeTask(String text) {
        if (text == null || text.trim().isEmpty() || isNativeContinuation(text)) return;
        lastNativeTask = text.trim();
        lastNativeTaskAt = System.currentTimeMillis();
    }

    /** Build a model-facing tool catalog. No MCP tool is executed at this stage. */
    static String buildNativeAgentPrompt(String text, Object[] hookArgs) {
        if (text == null) return "";
        try {
            refreshTools();
            StringBuilder b = new StringBuilder();
            b.append("你正在 YuanBao 本地 Agent 模式中。用户原始任务如下：\n")
             .append(text).append("\n\n")
             .append("你可以使用下面列出的真实 MCP 工具。必须根据工具描述和 inputSchema 选择工具，不要猜测不存在的工具。\n")
             .append("如果需要工具，不要在聊天正文输出任何工具调用标记、XML、JSON 或函数调用文本；网关会根据真实 MCP 工具目录自动执行高置信度工具。\n")
             .append("工具调用会被网关真实执行，结果随后返回给你。不要声称工具已经执行，除非结果已经返回。\n")
             .append("如果不需要工具，直接回答用户。\n\n【真实 MCP 工具】\n");
            int n=0;
            for (ToolDef t : cachedTools) {
                if (n++ >= 40) break;
                b.append("- name=").append(t.publicName)
                 .append(" server=").append(t.serverId)
                 .append(" description=").append(shorten(t.description,500));
                if (t.schema != null) b.append(" inputSchema=").append(shorten(t.schema.toString(),1800));
                b.append('\n');
            }
            if (cachedTools.isEmpty()) b.append("（当前没有发现 MCP 工具；不要伪造工具调用。）\n");
            b.append("\n注意：工具名必须完全使用上面的 name；arguments 必须是 JSON object；不要把工具调用放入 Markdown 代码块。\n");
            return b.toString();
        } catch (Throwable e) {
            log("[NATIVE-AGENT] build catalog failed: "+e);
            return text;
        }
    }

    /** Compatibility entry retained for older callers; it now only builds the catalog and never pre-executes tools. */
    static String enrichNativeUserPrompt(String text, Object[] hookArgs) {
        return buildNativeAgentPrompt(text, hookArgs);
    }

    static boolean containsNativeToolCall(String text) {
        if (text == null) return false;
        return text.contains("<yb_tool_call>") || text.contains("<tool_call>") || text.contains("<call>");
    }

    /** True when the current discovered MCP catalog contains a high-confidence, safe tool
     * for the user's native-chat request. This is used to bypass fragile model text protocols
     * for the first read/analyze/search/list/decompile/network inspection call. */
    static boolean canAutoExecuteNativeIntent(String originalPrompt) {
        try {
            String source = nativeIntentSource(originalPrompt);
            List<Call> calls = inferNativeCalls(source);
            return calls != null && !calls.isEmpty();
        } catch (Throwable e) {
            log("[NATIVE-AGENT] auto-intent check failed: " + shorten(String.valueOf(e), 500));
            return false;
        }
    }

    /** Execute a catalog-inferred first call without requiring YuanBao to emit <yb_tool_call>. */
    static String executeInferredNativeToolCalls(String originalPrompt, int round) throws Exception {
        refreshTools();
        setNativeTaskStage("DISCOVERED_TOOLS");
        String source = nativeIntentSource(originalPrompt);
        List<Call> calls = inferNativeCalls(source);
        if (calls == null || calls.isEmpty()) {
            throw new IOException("没有从当前 MCP 工具目录找到高置信度工具");
        }
        log("[NATIVE-AGENT] executing inferred calls=" + calls.size());
        setNativeTaskStage("EXECUTING_TOOL_CHAIN");
        String result = executeCallsAndBuildNextPrompt(originalPrompt, calls, round);
        setNativeTaskStage("RESULT_READY");
        return result;
    }

    private static final class ApkItem {
        String path;
        String chineseName;
        String packageName;
        String versionName;
        long sizeBytes;
        String label;
        JSONObject raw;
    }

    /** Persistent native MCP task state. A single task owns the whole list -> select -> open/decompile flow.
     * Tool calls never re-enter hb.I6.s3 individually; only the final accumulated result is replayed once.
     */
    static final Object NATIVE_TASK_LOCK = new Object();
    static volatile String ACTIVE_NATIVE_TASK_ID = "";
    static volatile String ACTIVE_NATIVE_TASK_PROMPT = "";
    static volatile String ACTIVE_NATIVE_TASK_STAGE = "IDLE";
    static volatile long ACTIVE_NATIVE_TASK_STARTED = 0L;
    static volatile String ACTIVE_NATIVE_WORKER_ID = "";
    static volatile Thread ACTIVE_NATIVE_HEARTBEAT = null;

    static String beginPersistentNativeTask(String prompt) {
        synchronized (NATIVE_TASK_LOCK) {
            try {
                JSONObject args = new JSONObject().put("prompt", prompt == null ? "" : prompt);
                ACTIVE_NATIVE_TASK_ID = McpTaskStore.create("native_agent", args, 60L * 60L * 1000L, 1000L);
            } catch (Throwable e) {
                ACTIVE_NATIVE_TASK_ID = "yb-mcp-" + System.currentTimeMillis();
                log("[MCP-TASK] durable create failed: " + e);
            }
            ACTIVE_NATIVE_TASK_PROMPT = prompt == null ? "" : prompt;
            ACTIVE_NATIVE_WORKER_ID = "native-worker-" + UUID.randomUUID().toString();
            ACTIVE_NATIVE_TASK_STAGE = "STARTED";
            ACTIVE_NATIVE_TASK_STARTED = System.currentTimeMillis();
            try {
                McpTaskStore.claimWorker(ACTIVE_NATIVE_TASK_ID, ACTIVE_NATIVE_WORKER_ID);
                McpTaskStore.progress(ACTIVE_NATIVE_TASK_ID, "Native Agent 已启动", 0, "DISCOVER_TOOLS");
                final String taskId = ACTIVE_NATIVE_TASK_ID;
                final String workerId = ACTIVE_NATIVE_WORKER_ID;
                ACTIVE_NATIVE_HEARTBEAT = new Thread(() -> {
                    while (taskId.equals(ACTIVE_NATIVE_TASK_ID) && !Thread.currentThread().isInterrupted()) {
                        try { Thread.sleep(1000L); } catch (InterruptedException e) { break; }
                        try { McpTaskStore.heartbeat(taskId, workerId); } catch (Throwable ignored) {}
                    }
                }, "YB-native-task-heartbeat");
                ACTIVE_NATIVE_HEARTBEAT.setDaemon(true);
                ACTIVE_NATIVE_HEARTBEAT.start();
            } catch (Throwable ignored) {}
            log("[MCP-TASK] start id=" + ACTIVE_NATIVE_TASK_ID + " worker=" + ACTIVE_NATIVE_WORKER_ID + " prompt=" + safeLog(ACTIVE_NATIVE_TASK_PROMPT));
            return ACTIVE_NATIVE_TASK_ID;
        }
    }

    static void setNativeTaskStage(String stage) {
        synchronized (NATIVE_TASK_LOCK) {
            ACTIVE_NATIVE_TASK_STAGE = stage == null ? "" : stage;
            try { McpTaskStore.progress(ACTIVE_NATIVE_TASK_ID, humanNativeStage(ACTIVE_NATIVE_TASK_STAGE), 0, ACTIVE_NATIVE_TASK_STAGE); } catch (Throwable ignored) {}
            log("[MCP-TASK] id=" + ACTIVE_NATIVE_TASK_ID + " stage=" + ACTIVE_NATIVE_TASK_STAGE);
        }
    }

    static void finishPersistentNativeTask(String finalResult) {
        synchronized (NATIVE_TASK_LOCK) {
            log("[MCP-TASK] finish id=" + ACTIVE_NATIVE_TASK_ID + " stage=" + ACTIVE_NATIVE_TASK_STAGE + " ageMs=" + (System.currentTimeMillis() - ACTIVE_NATIVE_TASK_STARTED));
            ACTIVE_NATIVE_TASK_STAGE = "COMPLETED";
            stopNativeHeartbeatLocked();
            try {
                JSONObject result = new JSONObject().put("content", finalResult == null ? "" : finalResult);
                McpTaskStore.complete(ACTIVE_NATIVE_TASK_ID, result);
            } catch (Throwable ignored) {}
        }
    }

    static void failPersistentNativeTask(String message) {
        synchronized (NATIVE_TASK_LOCK) {
            ACTIVE_NATIVE_TASK_STAGE = "FAILED";
            stopNativeHeartbeatLocked();
            try { McpTaskStore.failIfWorking(ACTIVE_NATIVE_TASK_ID, message); } catch (Throwable ignored) {}
        }
    }

    static String activeNativeTaskId() { synchronized (NATIVE_TASK_LOCK) { return ACTIVE_NATIVE_TASK_ID; } }

    static boolean activeNativeTaskCancelled() { synchronized (NATIVE_TASK_LOCK) { try { return ACTIVE_NATIVE_TASK_ID != null && !ACTIVE_NATIVE_TASK_ID.isEmpty() && McpTaskStore.isCancelled(ACTIVE_NATIVE_TASK_ID); } catch (Throwable ignored) { return false; } } }

    private static void stopNativeHeartbeatLocked() {
        Thread t = ACTIVE_NATIVE_HEARTBEAT;
        ACTIVE_NATIVE_HEARTBEAT = null;
        if (t != null) { try { t.interrupt(); } catch (Throwable ignored) {} }
    }

    static void checkpointNativeTask(String phase, String originalPrompt, List<Call> calls, int index, String accumulated) {
        synchronized (NATIVE_TASK_LOCK) {
            if (ACTIVE_NATIVE_TASK_ID == null || ACTIVE_NATIVE_TASK_ID.isEmpty()) return;
            try {
                JSONObject cp = new JSONObject();
                cp.put("phase", phase == null ? "" : phase);
                cp.put("originalPrompt", originalPrompt == null ? "" : originalPrompt);
                cp.put("nextIndex", index);
                cp.put("accumulated", accumulated == null ? "" : accumulated);
                JSONArray ca = new JSONArray();
                if (calls != null) for (Call c : calls) ca.put(new JSONObject().put("name", c.name).put("args", c.args == null ? new JSONObject() : c.args).put("displayName", c.displayName == null ? "" : c.displayName));
                cp.put("calls", ca);
                McpTaskStore.checkpoint(ACTIVE_NATIVE_TASK_ID, cp);
            } catch (Throwable ignored) {}
        }
    }

    private static String humanNativeStage(String stage) {
        if (stage == null) return "Native Agent";
        if (stage.contains("LISTING_APKS")) return "正在扫描本地 APK";
        if (stage.contains("OPENING_SELECTED_APK")) return "正在打开选中的 APK";
        if (stage.contains("ANALYZING_LOCAL_APK")) return "正在进行本地 APK 分析";
        if (stage.contains("CALLING_")) return "正在执行 MCP 工具：" + stage.substring("CALLING_".length());
        if (stage.contains("REPLAYING")) return "正在把最终真实结果回灌元宝";
        if (stage.contains("RESULT_READY")) return "真实工具结果已准备完成";
        if (stage.contains("FAILED")) return "工具链执行失败";
        return stage;
    }

    private static final class ApkSelection {
        ApkItem item;
        int score;
        String reason;
    }

    /**
     * APK list -> open bridge. The list tool is deliberately executed first. We never pass the
     * user's absolute directory to mt_apk_open. Only a path returned by items[].path is eligible.
     */
    private static List<Call> buildApkSecondStageCalls(String originalPrompt, Call listCall, JSONObject listResult) {
        List<Call> out = new ArrayList<>();
        List<ApkItem> items = extractApkItems(listResult);
        if (items.isEmpty()) {
            log("[APK-LOOP] list result contained no items[] paths");
            return out;
        }
        ToolDef open = findBestApkOpenTool();
        if (open == null) {
            log("[APK-LOOP] no APK open tool discovered");
            return out;
        }

        ApkSelection selected = selectApk(originalPrompt, items);
        List<ApkItem> targets = new ArrayList<>();
        if (selected.item != null && selected.score >= 8) {
            targets.add(selected.item);
            log("[APK-LOOP] selected APK chineseName=" + safeLog(selected.item.chineseName)
                    + " path=" + safeLog(selected.item.path) + " score=" + selected.score
                    + " reason=" + selected.reason);
        } else if (items.size() == 1) {
            targets.add(items.get(0));
            log("[APK-LOOP] single APK selected path=" + safeLog(items.get(0).path));
        } else {
            // Do not guess among unrelated APKs. Put a Chinese-readable inventory into the
            // model-facing prompt; YuanBao can then select by an explicit name/tool call.
            log("[APK-LOOP] multiple APKs and no confident match; returning inventory to model");
            return out;
        }

        for (ApkItem item : targets) {
            if (item == null || item.path == null || item.path.trim().isEmpty()) continue;
            JSONObject args = buildApkOpenArgs(open, item.path);
            String display = "打开 APK";
            if (item.chineseName != null && !item.chineseName.isEmpty()) display += " · " + item.chineseName;
            if (item.packageName != null && !item.packageName.isEmpty()) display += " · " + item.packageName;
            if (item.sizeBytes >= APK_LIGHTWEIGHT_THRESHOLD) {
                // Large APKs must not be sent through the recursive/full-open path. Prefer a
                // dedicated decompile/disassembly tool first; only fall back to lightweight
                // search/overview when no decompiler is exposed by the MCP server.
                ToolDef decompiler = findBestApkDecompileTool();
                if (decompiler != null) {
                    Call decompile = buildApkDecompileCall(originalPrompt, item.path, decompiler);
                    if (decompile != null) {
                        log("[APK-LOOP] large APK " + formatBytes(item.sizeBytes)
                                + "; using decompile/disassembly instead of mt_apk_open tool=" + decompile.name);
                        out.add(decompile);
                        continue;
                    }
                }
                ToolDef search=findBestApkSearchTool();
                if (search != null) {
                    Call lightweight=buildLightweightApkSearchCall(originalPrompt,args);
                    if (lightweight!=null) {
                        log("[APK-LOOP] large APK " + formatBytes(item.sizeBytes) + "; no decompiler, using lightweight search instead of mt_apk_open");
                        out.add(lightweight);
                        continue;
                    }
                }
                JSONObject la = new JSONObject();
                try { la.put("path", item.path); la.put("query", originalPrompt == null ? "" : originalPrompt); } catch (Throwable ignored) {}
                log("[APK-LOOP] large APK " + formatBytes(item.sizeBytes) + "; using local_apk_analyze instead of mt_apk_open");
                out.add(new Call("local_apk_analyze", la, "本地 APK 安全分析"));
                continue;
            }
            out.add(new Call(open.publicName, args, display));
        }
        return out;
    }

    private static ToolDef findBestApkOpenTool() {
        ToolDef best = null; int score = -1;
        for (ToolDef t : cachedTools) {
            if (t == null || "builtin".equals(t.server)) continue;
            String x = (t.publicName + " " + t.realName + " " + t.description + " "
                    + (t.schema == null ? "" : t.schema.toString())).toLowerCase(Locale.ROOT);
            int s = 0;
            if (x.contains("mt_apk_open")) s += 40;
            if (x.contains("apk_open")) s += 28;
            if (x.contains("open_apk")) s += 25;
            if (x.contains("apk") && containsAny(x, "open", "inspect", "analy", "decode", "decompile", "解析", "分析")) s += 15;
            if (hasPathProperty(t.schema)) s += 8;
            if (isDestructiveTool(x)) s = 0;
            if (s > score) { score = s; best = t; }
        }
        return score >= 25 ? best : null;
    }

    private static boolean hasPathProperty(JSONObject schema) {
        try {
            JSONObject p = schema == null ? null : schema.optJSONObject("properties");
            if (p == null) return false;
            Iterator<String> it = p.keys();
            while (it.hasNext()) if (isPathLikeArgument(it.next())) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static JSONObject buildApkOpenArgs(ToolDef t, String exactReturnedPath) {
        JSONObject a = new JSONObject();
        try {
            JSONObject props = t.schema == null ? null : t.schema.optJSONObject("properties");
            if (props == null) { a.put("locator", exactReturnedPath); a.put("temporary", "true"); return a; }
            Iterator<String> it = props.keys();
            while (it.hasNext()) {
                String k = it.next();
                String lk = k.toLowerCase(Locale.ROOT);
                if (isPathLikeArgument(lk) || lk.equals("apk_path") || lk.equals("target")) {
                    a.put(k, exactReturnedPath);
                    if (props.has("temporary")) {
                        JSONObject spec = props.optJSONObject("temporary");
                        String typ = spec == null ? "" : spec.optString("type", "");
                        if ("boolean".equalsIgnoreCase(typ)) a.put("temporary", true);
                        else a.put("temporary", "true");
                    }
                    return a;
                }
            }
            // If the server has an unlabelled single string argument, only use it when there is
            // exactly one property; this avoids accidentally placing a path into a non-path field.
            if (props.length() == 1) {
                String k = props.keys().next();
                JSONObject spec = props.optJSONObject(k);
                if (spec == null || "string".equalsIgnoreCase(spec.optString("type", ""))) a.put(k, exactReturnedPath);
            }
        } catch (Throwable ignored) {}
        return a;
    }

    private static List<ApkItem> extractApkItems(JSONObject root) {
        List<ApkItem> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try { collectApkItems(root, out, seen, 0); } catch (Throwable e) { log("[APK-LOOP] extract items failed: " + e); }
        return out;
    }

    private static void collectApkItems(Object node, List<ApkItem> out, Set<String> seen, int depth) {
        if (node == null || depth > 8 || out.size() >= 50) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String path = firstString(o, "path", "filePath", "filepath", "relativePath", "file", "apkPath");
            if (looksLikeApkPath(path)) {
                ApkItem item = new ApkItem();
                item.path = path.trim();
                item.chineseName = firstString(o, "chineseName", "appName", "applicationName", "label", "displayName", "name", "title");
                item.label = firstString(o, "label", "displayName", "appName", "applicationName", "name", "title");
                item.packageName = firstString(o, "packageName", "package", "applicationId", "appId");
                item.versionName = firstString(o, "versionName", "version", "versionLabel");
                item.sizeBytes = firstLong(o, "sizeBytes", "size", "fileSize", "length", "bytes");
                item.raw = o;
                String key = item.path + "\u0000" + String.valueOf(item.packageName);
                if (seen.add(key)) out.add(item);
            }
            Iterator<String> it = o.keys();
            while (it.hasNext()) { String k = it.next(); Object v = o.opt(k); collectApkItems(v, out, seen, depth + 1); }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length() && out.size() < 50; i++) collectApkItems(a.opt(i), out, seen, depth + 1);
        } else if (node instanceof String) {
            String x = ((String) node).trim();
            if (x.startsWith("{") || x.startsWith("[")) {
                try {
                    Object parsed = x.startsWith("{") ? new JSONObject(x) : new JSONArray(x);
                    collectApkItems(parsed, out, seen, depth + 1);
                } catch (Throwable ignored) {}
            }
        }
    }

    private static String firstString(JSONObject o, String... keys) {
        for (String k : keys) {
            try {
                Object v = o.opt(k);
                if (v != null && v != JSONObject.NULL) {
                    String s = String.valueOf(v).trim();
                    if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) return s;
                }
            } catch (Throwable ignored) {}
        }
        return "";
    }

    private static long firstLong(JSONObject o, String... keys) {
        for (String k : keys) {
            try {
                Object v = o.opt(k);
                if (v instanceof Number) return ((Number)v).longValue();
                if (v != null && v != JSONObject.NULL) {
                    String x = String.valueOf(v).trim().replace(",", "");
                    if (!x.isEmpty()) return Long.parseLong(x);
                }
            } catch (Throwable ignored) {}
        }
        return 0L;
    }

    private static boolean looksLikeApkPath(String p) {
        if (p == null || p.trim().isEmpty()) return false;
        String x = p.trim().toLowerCase(Locale.ROOT);
        return x.endsWith(".apk") || x.endsWith(".xapk") || x.endsWith(".apks");
    }

    private static ApkSelection selectApk(String prompt, List<ApkItem> items) {
        ApkSelection best = new ApkSelection();
        if (prompt == null) return best;
        String p = prompt.toLowerCase(Locale.ROOT);
        for (ApkItem item : items) {
            int score = 0; String reason = "";
            String[] fields = {item.chineseName, item.label, item.packageName, item.path};
            for (String f : fields) {
                if (f == null || f.trim().isEmpty()) continue;
                String v = f.trim().toLowerCase(Locale.ROOT);
                if (p.contains(v) && v.length() >= 2) { score += v.length() >= 4 ? 14 : 9; reason = "用户文本直接命中 " + f; }
            }
            // Chinese names often contain punctuation/spaces or the user only says part of the name.
            String cn = item.chineseName == null ? "" : item.chineseName.trim();
            if (!cn.isEmpty()) {
                String compact = cn.replaceAll("[\\s·•_\\-()（）]", "").toLowerCase(Locale.ROOT);
                String compactPrompt = p.replaceAll("[\\s·•_\\-()（）]", "");
                if (compact.length() >= 2 && compactPrompt.contains(compact)) { score += 18; reason = "命中 APK 中文名 " + cn; }
                else if (containsChinese(compact) && compact.length() >= 2) {
                    int hits = 0;
                    for (int i = 0; i < compact.length(); i++) if (compactPrompt.indexOf(compact.substring(i, i + 1)) >= 0) hits++;
                    if (hits >= Math.max(2, compact.length() / 2)) { score += 6; reason = "部分命中中文名 " + cn; }
                }
            }
            if (score > best.score) { best.score = score; best.item = item; best.reason = reason; }
        }
        return best;
    }

    private static boolean containsChinese(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (c >= '\u4e00' && c <= '\u9fff') return true; }
        return false;
    }

    private static String safeLog(String s) { return s == null ? "" : shorten(s.replace('\n',' '), 220); }

    static String shortenForLog(String s, int max) { return shorten(s == null ? "" : s.replace('\n',' '), max); }


    private static String executeCallsAndBuildNextPrompt(String originalPrompt, List<Call> calls, int round) throws Exception {
        StringBuilder next = new StringBuilder();
        next.append("【MCP技术分析诊断】\n")
            .append("核心信号：tools.len=").append(cachedTools == null ? 0 : cachedTools.size())
            .append("；本地 Agent Loop 已启用。\n")
            .append("链路：NativeAgent → NativeToolRegistry → tools/list/tools/call → 真实结果 → Agent State → 最终一次回灌。\n")
            .append("改动点：1) 有标准 tools 时不走 action-wrap；2) APK 路径优先转换为 MCP 相对路径；3) APK 目录先 list，再使用 items[].path 调 open。\n\n")
            .append("用户原始任务：\n").append(nativeIntentSource(originalPrompt)).append("\n")
            .append("本轮用户补充：").append(originalPrompt).append("\n\n")
            .append("第 ").append(round).append(" 轮 MCP 工具执行结果如下。请基于真实结果继续任务；如果仍需要更多工具，只需用自然语言说明下一步；不要输出工具协议、XML 标签或 JSON 工具调用，网关会根据真实 MCP 工具目录继续执行。\n\n");
        int n = 0;
        for (Call c : calls) {
            if (activeNativeTaskCancelled()) throw new IOException("Native Agent task cancelled by user");
            if (n++ >= 5) break;
            ToolDef known = findTool(c.name);
            if (known == null) {
                JSONObject synth = synthesizeMissingApkList(c);
                if (synth != null) {
                    log("[NATIVE-AGENT] synthesized " + c.name + " from local APK discovery");
                    next.append("[MCP_RESULT name=\"").append(c.name).append("\"]\n")
                        .append(shorten(synth.toString(), 14000)).append("\n[/MCP_RESULT]\n");
                } else {
                    next.append("[MCP_ERROR tool=\"").append(c.name).append("\"] 未找到该工具\n[/MCP_ERROR]\n");
                }
                continue;
            }
            if (isApkListCall(c)) setNativeTaskStage("LISTING_APKS");
            else setNativeTaskStage("CALLING_" + c.name);
            checkpointNativeTask("BEFORE_TOOL", originalPrompt, calls, n, next.toString());
            log("[NATIVE-AGENT] tools/call name=" + c.name + " args=" + shorten(c.args.toString(), 1200));
            NativeToolCard.beginTool(c.name, c.args.toString());
            try {
                JSONObject result = NativeToolRegistry.callDirect(c.name, c.args);
                NativeToolCard.finishTool(c.name, result == null ? "" : result.toString(), false);
                next.append("[MCP_RESULT tool=\"").append(c.name).append("\"]\n")
                    .append(shorten(result == null ? "null" : result.toString(), 12000)).append("\n[/MCP_RESULT]\n");
                checkpointNativeTask("AFTER_TOOL", originalPrompt, calls, n, next.toString());

                // Real two-stage APK workflow: list first, then use only items[].path for open.
                if (isLocalApkDiscoverCall(c)) {
                    List<ApkItem> localItems = extractApkItems(result);
                    appendApkInventory(next, localItems);
                    if (!localItems.isEmpty()) {
                        ApkSelection selected = selectApk(originalPrompt, localItems);
                        ApkItem item = selected.item != null && selected.score >= 8 ? selected.item : (localItems.size() == 1 ? localItems.get(0) : null);
                        if (item != null && item.path != null && !item.path.isEmpty()) {
                            if (activeNativeTaskCancelled()) throw new IOException("Native Agent task cancelled by user");
                            JSONObject aa = new JSONObject();
                            aa.put("path", item.path);
                            aa.put("query", originalPrompt == null ? "" : originalPrompt);
                            Call analyze = new Call("local_apk_analyze", aa, "本地 APK 安全分析");
                            setNativeTaskStage("ANALYZING_LOCAL_APK");
                            NativeToolCard.beginTool(analyze.name, aa.toString(), analyze.displayName);
                            try {
                                JSONObject ar = NativeToolRegistry.callDirect(analyze.name, aa);
                                NativeToolCard.finishTool(analyze.name, ar == null ? "" : ar.toString(), false);
                                next.append("[LOCAL_APK_ANALYSIS]\n").append(shorten(ar == null ? "null" : ar.toString(), 18000)).append("\n[/LOCAL_APK_ANALYSIS]\n");
                            } catch (Throwable ae) {
                                NativeToolCard.finishTool(analyze.name, String.valueOf(ae), true);
                                next.append("[LOCAL_APK_ANALYSIS_ERROR]\n").append(shorten(String.valueOf(ae), 4000)).append("\n[/LOCAL_APK_ANALYSIS_ERROR]\n");
                            }
                        } else if (localItems.size() > 1) {
                            next.append("\n已发现多个本地 APK；请根据清单中的 path/name 指定要分析的 APK。\n");
                        }
                    }
                }
                if (isApkListCall(c)) {
                    List<ApkItem> listed = extractApkItems(result);
                    appendApkInventory(next, listed);
                    List<Call> stage2 = buildApkSecondStageCalls(originalPrompt, c, result);
                    for (Call openCall : stage2) {
                        if (activeNativeTaskCancelled()) throw new IOException("Native Agent task cancelled by user");
                        ToolDef ot = findTool(openCall.name);
                        if (ot == null) continue;
                        setNativeTaskStage("OPENING_SELECTED_APK");
                        log("[APK-LOOP] stage2 mt_apk_open path=" + safeLog(openCall.args.toString()));
                        NativeToolCard.beginTool(openCall.name, openCall.args.toString(), openCall.displayName);
                        try {
                            JSONObject openResult = NativeToolRegistry.callDirect(openCall.name, openCall.args);
                            NativeToolCard.finishTool(openCall.name, openResult == null ? "" : openResult.toString(), false);
                            next.append("[APK_OPEN_RESULT tool=\"").append(openCall.name).append("\"]\n")
                                .append(shorten(openResult == null ? "null" : openResult.toString(), 14000)).append("\n[/APK_OPEN_RESULT]\n");
                            appendApkMetadata(next, openResult);
                        } catch (Throwable openErr) {
                            String err = String.valueOf(openErr);
                            NativeToolCard.finishTool(openCall.name, humanizeApkOpenError(err), true);
                            next.append("[APK_OPEN_ERROR tool=\"").append(openCall.name).append("\"]\n")
                                .append(humanizeApkOpenError(err)).append("\n[/APK_OPEN_ERROR]\n");
                            if (isStackOverflowError(err) || containsAny(err.toLowerCase(Locale.ROOT), "stackoverflowerror", "stack overflow")) {
                                log("[APK-LOOP] mt_apk_open StackOverflowError; switching to lightweight search");
                                Call fallback = buildLightweightApkSearchCall(originalPrompt, openCall.args);
                                if (fallback != null) {
                                    NativeToolCard.beginTool(fallback.name, fallback.args.toString(), fallback.displayName);
                                    try {
                                        JSONObject sr = NativeToolRegistry.callDirect(fallback.name, fallback.args);
                                        NativeToolCard.finishTool(fallback.name, sr == null ? "" : sr.toString(), false);
                                        next.append("[APK_SEARCH_RESULT tool=\"").append(fallback.name).append("\"]\n")
                                            .append(shorten(sr == null ? "null" : sr.toString(), 14000)).append("\n[/APK_SEARCH_RESULT]\n");
                                    } catch (Throwable se) {
                                        NativeToolCard.finishTool(fallback.name, humanizeApkOpenError(String.valueOf(se)), true);
                                        next.append("[APK_SEARCH_ERROR tool=\"").append(fallback.name).append("\"]\n")
                                            .append(humanizeApkOpenError(String.valueOf(se))).append("\n[/APK_SEARCH_ERROR]\n");
                                    }
                                } else {
                                    next.append("[APK_SEARCH_UNAVAILABLE]\n未发现轻量 APK 搜索工具；不要再次调用 mt_apk_open。建议使用 mt_apk_search 的 overview 模式或指定 DEX/资源/字符串目标。\n[/APK_SEARCH_UNAVAILABLE]\n");
                                }
                            }
                        }
                    }
                }
                if (isApkDecompileCall(c)) {
                    next.append("\n【大型 APK 反编译 / DEX 分析】\n")
                        .append("已跳过 mt_apk_open 全量展开，改用专用反编译/反汇编工具。\n")
                        .append("路径必须来自 APK 列表的 items[].path：")
                        .append(extractPathFromArgs(c.args)).append("\n");
                }
            } catch (Throwable e) {
                NativeToolCard.finishTool(c.name, String.valueOf(e), true);
                next.append("[MCP_ERROR name=\"").append(c.name).append("\"]\n")
                    .append(shorten(String.valueOf(e), 3000)).append("\n[/MCP_ERROR]\n");
            }
        }
        next.append("\n真实 MCP 工具结果结束。不要重复解释工具调用协议；继续完成用户任务。\n\n【工具目录】\n");
        int n2 = 0;
        for (ToolDef t : cachedTools) {
            if (n2++ >= 40) break;
            next.append("- ").append(t.publicName).append(": ").append(shorten(t.description, 350));
            if (t.schema != null) next.append(" schema=").append(shorten(t.schema.toString(), 1200));
            next.append('\n');
        }
        return next.toString();
    }

    private static final long APK_LIGHTWEIGHT_THRESHOLD = 200L * 1024L * 1024L;

    private static boolean isStackOverflowError(String s) {
        if (s == null) return false;
        String x = s.toLowerCase(Locale.ROOT);
        return x.contains("stackoverflowerror") || x.contains("stack overflow") || x.contains("stack size");
    }

    private static String humanizeApkOpenError(String err) {
        if (isStackOverflowError(err)) {
            return "分析：mt_apk_open 在服务端处理该 APK 时发生 StackOverflowError，说明全量 APK 展开/递归分析超出了服务端线程栈。\n"
                    + "处理：已停止重复调用 mt_apk_open，改用轻量搜索/overview 路径。\n"
                    + "建议：优先搜索具体类、方法、字符串、资源或 ZIP 条目，而不是一次性展开整个 APK。";
        }
        if (err != null && err.toLowerCase(Locale.ROOT).contains("invalid_argument")) {
            return "分析：APK 工具拒绝了当前参数。网关不会重复发送未经验证的绝对路径；将优先使用 list 返回的相对 path。\n" + shorten(err, 1200);
        }
        return shorten(err == null ? "未知 APK 打开错误" : err, 3000);
    }

    private static String formatBytes(long n) {
        if (n <= 0) return "";
        double x = n; String[] u = {"B","KB","MB","GB"}; int i=0;
        while (x >= 1024 && i < u.length-1) { x /= 1024.0; i++; }
        return String.format(Locale.ROOT, "%.2f %s", x, u[i]);
    }

    /** Prefer dedicated decompile/disassembly tools for large APKs. This avoids feeding a
     * huge archive into mt_apk_open, which may recursively expand the entire package. */
    private static ToolDef findBestApkDecompileTool() {
        ToolDef best = null; int score = 0;
        for (ToolDef t : cachedTools) {
            if (t == null || "builtin".equals(t.server)) continue;
            String x = (t.publicName + " " + t.realName + " " + t.description + " "
                    + (t.schema == null ? "" : t.schema.toString())).toLowerCase(Locale.ROOT);
            int s = 0;
            if (x.contains("mt_apk_decompile")) s += 90;
            if (x.contains("apk_decompile")) s += 65;
            if (x.contains("decompile_apk")) s += 60;
            if (x.contains("apk") && containsAny(x, "decompile", "disassemble", "dex2smali", "smali", "jadx")) s += 35;
            if (containsAny(x, "build", "repack", "install", "sign", "delete", "write")) s -= 20;
            if (hasPathProperty(t.schema)) s += 8;
            if (s > score) { score = s; best = t; }
        }
        return score >= 35 ? best : null;
    }

    /** Build arguments from the actual decompiler schema. Never replace the returned relative
     * path with the user's absolute path. */
    private static Call buildApkDecompileCall(String originalPrompt, String exactReturnedPath, ToolDef t) {
        if (t == null || exactReturnedPath == null || exactReturnedPath.trim().isEmpty()) return null;
        JSONObject a = new JSONObject();
        try {
            JSONObject props = t.schema == null ? null : t.schema.optJSONObject("properties");
            if (props != null) {
                Iterator<String> it = props.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    String lk = k.toLowerCase(Locale.ROOT);
                    if (isPathLikeArgument(lk) || lk.equals("apk_path") || lk.equals("locator") || lk.equals("target") || lk.equals("input")) {
                        a.put(k, exactReturnedPath);
                    } else if (lk.equals("mode") || lk.equals("format") || lk.equals("output_mode") || lk.equals("decompile_mode")) {
                        // Prefer source-like Java output when the tool supports it; otherwise
                        // use smali/disassembly rather than a full archive open.
                        JSONObject spec = props.optJSONObject(k);
                        String enumValue = pickEnum(spec, "java", "jadx", "smali", "dex");
                        a.put(k, enumValue == null ? "smali" : enumValue);
                    } else if (lk.equals("recursive") || lk.equals("full") || lk.equals("open_all")) {
                        a.put(k, false);
                    } else if (lk.equals("temporary")) {
                        JSONObject spec = props.optJSONObject(k);
                        String typ = spec == null ? "" : spec.optString("type", "");
                        a.put(k, "boolean".equals(typ) ? false : "false");
                    } else if (lk.equals("limit") || lk.equals("max_results") || lk.equals("maxResults")) {
                        a.put(k, 200);
                    } else if (lk.equals("query") || lk.equals("keyword") || lk.equals("q") || lk.equals("pattern")) {
                        a.put(k, originalPrompt == null ? "" : originalPrompt);
                    }
                }
            }
            if (a.length() == 0) {
                a.put("path", exactReturnedPath);
                a.put("mode", "smali");
                a.put("recursive", false);
            }
        } catch (Throwable ignored) {}
        return new Call(t.publicName, a, "大型 APK 反编译 / DEX 分析");
    }

    private static String pickEnum(JSONObject spec, String... preferred) {
        if (spec == null) return null;
        try {
            org.json.JSONArray en = spec.optJSONArray("enum");
            if (en == null) return null;
            for (String p : preferred) for (int i = 0; i < en.length(); i++) {
                if (p.equalsIgnoreCase(en.optString(i))) return en.optString(i);
            }
            return en.length() > 0 ? en.optString(0) : null;
        } catch (Throwable ignored) { return null; }
    }

    private static ToolDef findBestApkSearchTool() {
        ToolDef best=null; int score=0;
        for (ToolDef t: cachedTools) {
            if (t==null || "builtin".equals(t.server)) continue;
            String x=(t.publicName+" "+t.realName+" "+t.description+" "+(t.schema==null?"":t.schema.toString())).toLowerCase(Locale.ROOT);
            int s=0;
            if (x.contains("mt_apk_search")) s+=60;
            if (x.contains("apk_search")) s+=40;
            if (x.contains("apk") && x.contains("search")) s+=20;
            if (x.contains("overview")) s+=8;
            if (hasPathProperty(t.schema)) s+=5;
            if (s>score) {score=s; best=t;}
        }
        return score>=25?best:null;
    }

    private static Call buildLightweightApkSearchCall(String originalPrompt, JSONObject openArgs) {
        ToolDef t=findBestApkSearchTool();
        if (t==null) return null;
        JSONObject a=new JSONObject();
        try {
            JSONObject p=t.schema==null?null:t.schema.optJSONObject("properties");
            String target="";
            if (openArgs!=null) {
                Iterator<String> it=openArgs.keys();
                while(it.hasNext()){String k=it.next(); Object v=openArgs.opt(k); if(v instanceof String && isPathLikeArgument(k)){target=(String)v;break;}}
            }
            if (p!=null) {
                Iterator<String> it=p.keys();
                while(it.hasNext()){String k=it.next(); String lk=k.toLowerCase(Locale.ROOT);
                    if(isPathLikeArgument(lk) || lk.equals("apk_path") || lk.equals("locator") || lk.equals("target")) { a.put(k,target); continue; }
                    if(lk.equals("query") || lk.equals("keyword") || lk.equals("q") || lk.equals("pattern")) {
                        String q=originalPrompt==null?"":originalPrompt;
                        if(target!=null&&!target.isEmpty()) q=target;
                        a.put(k,q); continue;
                    }
                    if(lk.equals("mode") || lk.equals("type")) { a.put(k,"overview"); continue; }
                    if(lk.equals("scope")) { a.put(k,"overview"); continue; }
                    if(lk.equals("limit") || lk.equals("max_results") || lk.equals("maxResults")) { a.put(k,20); continue; }
                }
            }
            if(a.length()==0){a.put("path",target);a.put("query",originalPrompt==null?"":originalPrompt);a.put("mode","overview");}
        } catch(Throwable ignored){}
        return new Call(t.publicName,a,"轻量 APK 搜索 · overview");
    }

    private static boolean isApkDecompileCall(Call c) {
        if (c == null) return false;
        ToolDef t = findTool(c.name);
        if (t == null) return false;
        String x = (t.publicName + " " + t.realName + " " + t.description).toLowerCase(Locale.ROOT);
        return x.contains("decompile") || x.contains("disassembl") || x.contains("dex2smali") || x.contains("jadx");
    }

    private static String extractPathFromArgs(JSONObject args) {
        if (args == null) return "";
        try {
            Iterator<String> it = args.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = args.opt(k);
                if (v instanceof String && isPathLikeArgument(k)) return (String) v;
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static boolean isLocalApkDiscoverCall(Call c) {
        return c != null && "local_apk_discover".equals(c.name);
    }

    private static boolean isLocalApkAnalyzeCall(Call c) {
        return c != null && "local_apk_analyze".equals(c.name);
    }

    private static boolean isApkListCall(Call c) {
        if (c == null) return false;
        ToolDef t = findTool(c.name);
        if (t == null) return false;
        return isMtApkListTool(t) || (t.realName != null && t.realName.toLowerCase(Locale.ROOT).contains("list_available_apks"));
    }

    private static void appendApkInventory(StringBuilder next, List<ApkItem> items) {
        if (items == null || items.isEmpty()) return;
        next.append("\n【APK清单 / APK Inventory】\n");
        int i = 0;
        for (ApkItem item : items) {
            if (i++ >= 50) break;
            next.append(i).append(". ");
            if (item.chineseName != null && !item.chineseName.isEmpty()) next.append("中文名=").append(item.chineseName).append(" | ");
            if (item.label != null && !item.label.isEmpty() && !item.label.equals(item.chineseName)) next.append("显示名=").append(item.label).append(" | ");
            if (item.packageName != null && !item.packageName.isEmpty()) next.append("包名=").append(item.packageName).append(" | ");
            if (item.versionName != null && !item.versionName.isEmpty()) next.append("版本=").append(item.versionName).append(" | ");
            if (item.sizeBytes > 0) next.append("大小=").append(formatBytes(item.sizeBytes)).append(" | ");
            next.append("path=").append(item.path).append('\n');
        }
        next.append("注意：选择 APK 时必须使用上面列表返回的 path；绝对目录 /storage/emulated/0/MT2/mcp/ 不能直接传给 mt_apk_open。\n");
    }

    private static void appendApkMetadata(StringBuilder next, JSONObject result) {
        if (result == null) return;
        String cn = findNestedString(result, "chineseName", "appName", "applicationName", "label", "displayName", "title", "appLabel");
        String pkg = findNestedString(result, "packageName", "package", "applicationId", "appId");
        String ver = findNestedString(result, "versionName", "version", "versionLabel");
        if ((cn == null || cn.isEmpty()) && (pkg == null || pkg.isEmpty()) && (ver == null || ver.isEmpty())) return;
        next.append("【APK识别信息】\n");
        if (cn != null && !cn.isEmpty()) next.append("中文名：").append(cn).append('\n');
        if (pkg != null && !pkg.isEmpty()) next.append("包名：").append(pkg).append('\n');
        if (ver != null && !ver.isEmpty()) next.append("版本：").append(ver).append('\n');
        next.append("后续分析请优先使用中文名、包名和版本信息描述该 APK。\n");
    }

    private static String findNestedString(Object node, String... keys) {
        if (node == null) return "";
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            for (String k : keys) {
                try {
                    Object v = o.opt(k);
                    if (v != null && v != JSONObject.NULL) {
                        String s = String.valueOf(v).trim();
                        if (!s.isEmpty() && !"null".equalsIgnoreCase(s) && s.length() < 500) return s;
                    }
                } catch (Throwable ignored) {}
            }
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String r = findNestedString(o.opt(it.next()), keys);
                if (r != null && !r.isEmpty()) return r;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                String r = findNestedString(a.opt(i), keys);
                if (r != null && !r.isEmpty()) return r;
            }
        } else if (node instanceof String) {
            String x = ((String) node).trim();
            if (x.startsWith("{") || x.startsWith("[")) {
                try { return findNestedString(x.startsWith("{") ? new JSONObject(x) : new JSONArray(x), keys); }
                catch (Throwable ignored) {}
            }
        }
        return "";
    }

    static boolean canRecoverNativeTool(String originalPrompt) {
        try {
            // externalToolCount() reads the cached catalog without refreshing, so a throttled
            // or previously-failed discovery would make this report "no recovery possible"
            // and drop the whole task. Force exactly one refresh before deciding.
            if (externalToolCount() == 0) refreshTools(true);
            if (externalToolCount() == 0) return false;
            return !inferNativeCalls(originalPrompt).isEmpty();
        } catch(Throwable e) {
            return false;
        }
    }

    /**
     * Infer a safe native-chat tool call from the ACTUAL discovered MCP catalog.
     * No MCP URL, server name, or tool name is hard-coded here. Only read/search/list/
     * analysis-style tools are eligible for automatic recovery; mutating, shell, delete,
     * build, and write tools still require an explicit model-generated tool call.
     */
    static List<Call> inferNativeCalls(String text) {
        List<Call> out = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return out;
        try { refreshTools(true); } catch(Throwable ignored) {}

        String l = text.toLowerCase(Locale.ROOT);
        if ((isNativeContinuation(text) || containsAny(l, "分析这个apk", "继续分析apk", "继续分析这个应用", "深入分析", "入口分析", "登录分析", "分析登录")) && hasLocalBuiltinContinuationContext()) {
            ToolDef agent = findTool("agent_analyze_apk");
            if (agent != null && !ACTIVE_LOCAL_APK_PATH.isEmpty() && containsAny(l, "分析", "analy", "入口", "登录", "login", "activity", "ui", "继续")) {
                JSONObject aa = new JSONObject();
                try { aa.put("path", ACTIVE_LOCAL_APK_PATH).put("query", text).put("maxEntries", 3); } catch (Throwable ignored) {}
                out.add(new Call(agent.publicName, aa, "继续 APK Agent 深度分析"));
                return out;
            }
            ToolDef inspect = findTool("local_apk_inspect");
            if (inspect != null && !ACTIVE_LOCAL_APK_PATH.isEmpty()) {
                JSONObject a = new JSONObject();
                try {
                    a.put("path", ACTIVE_LOCAL_APK_PATH);
                    a.put("operation", continuationOperation(text));
                    a.put("query", text);
                    a.put("maxResults", 100);
                } catch (Throwable ignored) {}
                out.add(new Call(inspect.publicName, a, "继续本地 APK 分析"));
                return out;
            }
        }
        if (externalToolCount() == 0) return out;
        String apkPath = extractPathHint(text);
        boolean apkContext = containsAny(l, "apk", ".apk", "安装包", "应用包");
        boolean directoryContext = apkPath != null && (apkPath.endsWith("/") ||
                containsAny(l, "目录", "文件夹", "路径下", "directory", "folder"));
        // Deterministic APK-file route: even when the user names a concrete APK, do NOT
        // call mt_apk_open with the absolute path. First list the containing MCP directory,
        // then buildApkSecondStageCalls() selects the exact items[].path and opens that value.
        // This is intentionally before the generic intent inference so YuanBao cannot replace
        // the two-stage workflow with a natural-language tool instruction.
        if (apkContext && apkPath != null && looksLikeApkPath(apkPath)) {
            ToolDef listTool = findBestApkListTool();
            if (listTool != null) {
                String parent = apkParentDirectory(apkPath);
                out.add(new Call(listTool.publicName, buildApkListArgs(listTool, text, parent)));
                log("[APK-LOOP] deterministic file route list=" + listTool.publicName
                        + " requested=" + apkPath + " parent=" + parent);
                return out;
            }
        }
        if (apkContext && directoryContext && isAndroidAbsolutePath(apkPath)) {
            // IMPORTANT: an Android filesystem directory is NOT an MCP workspace prefix.
            // mt_apk_list_available_apks only enumerates server-side indexed/workspace APKs,
            // so never feed /storage/emulated/0/... into its prefix/path argument. Discover
            // the real APK files locally first, then optionally hand one off through an
            // explicitly advertised MCP import/register tool.
            ToolDef local = findTool("local_apk_discover");
            if (local != null) {
                JSONObject a = new JSONObject();
                try {
                    a.put("directory", apkPath);
                    a.put("recursive", true);
                    a.put("maxDepth", 3);
                    a.put("limit", 50);
                } catch (org.json.JSONException je) {
                    log("[PATH-ROUTE] failed to build local_apk_discover args: " + je);
                    return out;
                }
                out.add(new Call(local.publicName, a, "本地 APK 文件发现"));
                log("[PATH-ROUTE] Android directory -> local_apk_discover path=" + apkPath);
                return out;
            }
        }

        String path = extractPathHint(text);
        IntentProfile intent = detectIntentProfile(text);
        ToolDef best = null;
        int bestScore = 0;

        for (ToolDef t : cachedTools) {
            if (t == null || "builtin".equals(t.server)) continue;
            String hay = (t.publicName + " " + t.realName + " " + t.description + " " +
                    (t.schema == null ? "" : t.schema.toString())).toLowerCase(Locale.ROOT);
            int score = 0;

            // Direct tool-name/description mention is the strongest signal. This is what makes
            // arbitrary MCP tools work without hard-coded server/tool names.
            if (containsExactToolMention(l, t)) score += 18;

            score += intentScore(hay, intent);
            score += schemaIntentScore(t.schema, intent);
            if (path != null && containsAny(hay, "path","file","filepath","file_path","input","target","apk","url")) score += 2;
            // APK analysis is a common compound intent. A tool named/advertised as APK/open/decompile
            // is a strong candidate even when its description does not literally contain "analyze".
            if (intent.keys.contains("analyze") && (l.contains("apk") || l.contains("xapk") || l.contains("apks"))) {
                if (containsAny(hay, "apk", "xapk", "apks")) score += 6;
                if (containsAny(hay, "open", "inspect", "decompile", "decode", "分析", "解析")) score += 4;
            }
            if (intent.keys.contains("decompile") && containsAny(hay, "apk", "dex", "smali", "jadx", "decompile")) score += 5;

            // Do not auto-guess destructive operations from a generic word such as “处理”.
            // They are allowed only when the user explicitly expresses the operation or names
            // the concrete tool. Build/install/run can be selected by their explicit keywords.
            if (isDestructiveTool(hay) && !intent.explicitAction && !containsExactToolMention(l, t)) score = 0;

            if (score > bestScore) { bestScore = score; best = t; }
        }

        if (best == null || bestScore < 7) return out;
        JSONObject args = buildArgs(best, text, path);
        JSONArray required = best.schema == null ? null : best.schema.optJSONArray("required");
        if (required != null) {
            for (int i = 0; i < required.length(); i++) {
                String r = required.optString(i, "");
                if (isPathLikeArgument(r) && path == null && !hasArgumentFromText(best, text, r)) return out;
            }
        }
        out.add(new Call(best.publicName, args));
        log("[NATIVE-AGENT] dynamic catalog match intent=" + intent.label +
                " tool=" + best.publicName + " score=" + bestScore +
                " explicit=" + intent.explicitAction);
        return dedupCalls(out);
    }

    private static boolean isAndroidAbsolutePath(String path) {
        if (path == null) return false;
        String v = path.trim();
        return v.startsWith("/storage/emulated/0/") || v.startsWith("/sdcard/") ||
                v.startsWith("/mnt/sdcard/") || v.startsWith("/storage/");
    }

    /**
     * Enumerate *.apk under an Android absolute directory using the Shizuku shell reachable
     * through the module process. Returns null when Shizuku/the bridge is unavailable, so the
     * caller can keep falling back instead of silently reporting "0 APKs".
     */
    private static JSONArray scanViaShizuku(String directory, boolean recursive, int maxDepth, int limit) {
        try {
            Bundle b = new Bundle();
            b.putString("path", directory);
            b.putBoolean("recursive", recursive);
            b.putInt("maxDepth", maxDepth <= 0 ? 1 : maxDepth);
            b.putInt("limit", Math.max(1, Math.min(2000, Math.max(1, limit) * 4)));
            b.putString("glob", "");
            JSONObject r = moduleCall("fs_list", b);
            if (r == null) {
                log("[LOCAL-APK] module bridge unreachable (provider+socket both failed)");
                return null;
            }
            if (!r.optBoolean("ok", false)) {
                log("[LOCAL-APK] module bridge fs_list failed: " + shorten(r.optString("error", ""), 400));
                MainHook.log("[LOCAL-APK] bridge fs_list error dir=" + directory + " err="
                        + shorten(r.optString("error", ""), 300));
                return null;
            }
            JSONArray raw = r.optJSONArray("items");
            JSONArray res = new JSONArray();
            if (raw == null) return res;
            for (int i = 0; i < raw.length() && res.length() < limit; i++) {
                JSONObject it = raw.optJSONObject(i);
                if (it == null || it.optBoolean("isDir", false)) continue;
                String p = it.optString("path", "");
                if (!looksLikeApkPath(p)) continue;
                JSONObject o = new JSONObject();
                o.put("path", p);
                o.put("name", new File(p).getName());
                o.put("sizeBytes", it.optLong("size", 0L));
                o.put("mtime", it.optLong("mtime", 0L));
                o.put("source", "shizuku");
                res.put(o);
            }
            log("[LOCAL-APK] Shizuku scan dir=" + directory + " matched=" + res.length());
            return res;
        } catch (Throwable t) {
            log("[LOCAL-APK] Shizuku scan error: " + t);
            return null;
        }
    }

    /** Adds Shizuku-found APKs to {@code items} and records scan provenance in {@code out}. */
    private static int appendShizukuApkItems(String directory, boolean recursive, int maxDepth,
                                             int limit, JSONArray items, JSONObject out) {
        try {
            int before = items.length();
            JSONArray got = scanViaShizuku(directory, recursive, maxDepth, limit);
            out.put("shizukuScanTried", got != null);
            if (got == null) {
                // The module side (Shizuku shell or SAF workspace) is the only thing that can read
                // this directory from the injected process, so report exactly why it was unusable
                // instead of returning a bare "0 APKs".
                out.put("bridge", bridgeDiagnostics(true));
                MainHook.log("[LOCAL-APK] module bridge unusable dir=" + directory
                        + " providerError=" + shorten(BRIDGE_PROVIDER_ERROR, 200)
                        + " socketError=" + shorten(BRIDGE_SOCKET_ERROR, 200));
                return 0;
            }
            for (int i = 0; i < got.length() && items.length() < limit; i++) items.put(got.getJSONObject(i));
            int added = items.length() - before;
            out.put("shizukuScanMatches", added);
            if (added > 0) out.put("scanSource", "module-bridge(SAF/shizuku)");
            return added;
        } catch (Throwable t) {
            log("[LOCAL-APK] appendShizukuApkItems failed: " + t);
            return 0;
        }
    }

    private static JSONObject discoverLocalApks(JSONObject args) {
        JSONObject out = new JSONObject();
        JSONArray items = new JSONArray();
        String directory = args == null ? "" : args.optString("directory", "").trim();
        boolean recursive = args == null || args.optBoolean("recursive", true);
        int maxDepth = args == null ? 3 : Math.max(0, Math.min(8, args.optInt("maxDepth", 3)));
        int limit = args == null ? 50 : Math.max(1, Math.min(500, args.optInt("limit", 50)));
        if (!isAndroidAbsolutePath(directory)) {
            return jsonError(out, "只允许 Android 共享存储绝对目录", items);
        }
        try {
            File root = new File(directory);
            boolean exists = root.exists();
            boolean isDir = exists && root.isDirectory();
            boolean canRead = exists && root.canRead();
            boolean allFiles = false;
            if (Build.VERSION.SDK_INT >= 30) {
                try { allFiles = Environment.isExternalStorageManager(); } catch (Throwable ignored) {}
            }
            String packageName = applicationContext == null ? "<unknown>" : applicationContext.getPackageName();
            log("[LOCAL-APK] probe path=" + directory +
                    " package=" + packageName +
                    " exists=" + exists +
                    " isDirectory=" + isDir +
                    " canRead=" + canRead +
                    " allFiles=" + allFiles +
                    " api=" + Build.VERSION.SDK_INT);
            if (!exists || !isDir) {
                // The injected YuanBao process is blind to shared storage under Android 11+
                // scoped storage, so File.exists() can be false even though /sdcard/... is
                // really there. Shell (uid 2000) *can* read it, so ask Shizuku before failing.
                JSONArray viaShizuku = scanViaShizuku(directory, recursive, maxDepth, limit);
                if (viaShizuku != null && viaShizuku.length() > 0) {
                    for (int i = 0; i < viaShizuku.length() && items.length() < limit; i++) items.put(viaShizuku.getJSONObject(i));
                    out.put("ok", true);
                    out.put("sourceType", "ANDROID_FILESYSTEM_VIA_SHIZUKU");
                    out.put("directory", directory);
                    out.put("recursive", recursive);
                    out.put("maxDepth", maxDepth);
                    out.put("count", items.length());
                    out.put("items", items);
                    out.put("hostProcessVisible", exists && isDir);
                    out.put("scanSource", "shizuku-shell");
                    out.put("mcpOpenPolicy", "NEVER_PASS_ABSOLUTE_PATH_TO_MT_APK_OPEN");
                    log("[LOCAL-APK] host process cannot see " + directory + " but Shizuku found " + items.length() + " apk(s)");
                    return out;
                }
                if (!exists) return jsonError(out, "目录不存在（宿主进程与 Shizuku 均无法确认）: " + directory, items);
                return jsonError(out, "不是目录: " + directory, items);
            }

            Set<String> seen = new HashSet<>();
            ScanStats stats = new ScanStats();
            scanLocalApkDirectory(root, 0, maxDepth, recursive, limit, items, seen, stats);

            boolean accessProblem = stats.listFailure || (stats.rootListed && stats.entriesSeen == 0 && !canRead);
            if (items.length() == 0) {
                // File.listFiles() may return null under scoped-storage restrictions. Do not
                // report that as "there are no APKs". Try MediaStore as an independent index.
                scanMediaStoreApks(directory, recursive, maxDepth, limit, items, seen, stats);
            }
            if (items.length() == 0 && Build.VERSION.SDK_INT >= 26) {
                // NIO DirectoryStream can succeed on devices where File.listFiles() is
                // unexpectedly null. This is a second direct-filesystem enumeration path.
                scanNioApkDirectory(root, 0, maxDepth, recursive, limit, items, seen, stats);
            }

            if (items.length() == 0) {
                // Shizuku-shell enumeration: the module's shell (uid 2000) is not subject to
                // scoped storage, so this works even when the host process sees an empty dir.
                appendShizukuApkItems(directory, recursive, maxDepth, limit, items, out);
            }

            if (items.length() == 0 && (stats.listNull > 0 || !canRead || !allFiles)) {
                // Important: the injected YuanBao process has YuanBao's UID/permissions.
                // If it cannot enumerate shared storage, ask the installed Xposed module
                // process to scan with its own storage permission. This is still local-only.
                JSONObject delegated = scanViaModuleProvider(directory, recursive, maxDepth, limit);
                JSONArray delegatedItems = delegated.optJSONArray("items");
                if (delegatedItems != null) {
                    for (int i = 0; i < delegatedItems.length() && items.length() < limit; i++) {
                        JSONObject item = delegatedItems.optJSONObject(i);
                        if (item != null) items.put(item);
                    }
                }
                if (delegated.optBoolean("modulePermissionMissing", false)) {
                    out.put("modulePermissionMissing", true);
                    out.put("modulePermissionHint", "请打开“元宝本地 Agent 网关”模块并授予所有文件访问权限，再重试。");
                }
            }

            out.put("ok", true);
            out.put("sourceType", "ANDROID_FILESYSTEM");
            out.put("directory", root.getAbsolutePath());
            out.put("recursive", recursive);
            out.put("maxDepth", maxDepth);
            out.put("count", items.length());
            out.put("items", items);
            out.put("scan", new JSONObject()
                    .put("listFilesCalls", stats.listCalls)
                    .put("listFilesNull", stats.listNull)
                    .put("listFailures", stats.listFailure)
                    .put("directoriesVisited", stats.directoriesVisited)
                    .put("entriesSeen", stats.entriesSeen)
                    .put("mediaStoreTried", stats.mediaStoreTried)
                    .put("mediaStoreMatches", stats.mediaStoreMatches)
                    .put("nioTried", stats.nioTried)
                    .put("nioEntries", stats.nioEntries)
                    .put("accessProblemSuspected", accessProblem));
            out.put("mcpOpenPolicy", "NEVER_PASS_ABSOLUTE_PATH_TO_MT_APK_OPEN");
            out.put("accessHint", items.length() == 0
                    ? "目录存在但未发现 APK；若 listFiles 返回 null，请给当前宿主进程/模块授予共享存储访问，或通过 SAF 选择该目录。"
                    : "APK 已发现；后续不得把 Android 绝对路径直接传给 mt_apk_open。");
            out.put("safPolicy", "需要用户授权目录时使用 ACTION_OPEN_DOCUMENT_TREE；不要伪造 content URI。");
            MainHook.log("[LOCAL-APK] discovered directory=" + root.getAbsolutePath() +
                    " count=" + items.length() +
                    " listNull=" + stats.listNull +
                    " dirs=" + stats.directoriesVisited +
                    " entries=" + stats.entriesSeen +
                    " mediaMatches=" + stats.mediaStoreMatches +
                    " nioEntries=" + stats.nioEntries);
            return out;
        } catch (SecurityException se) {
            return jsonError(out, "Android 文件访问被拒绝: " + se + "；请授予共享存储/目录访问权限", items);
        } catch (org.json.JSONException je) {
            return jsonError(out, "本地 APK 扫描结果 JSON 构造失败: " + je, items);
        } catch (Throwable t) {
            return jsonError(out, "本地 APK 扫描失败: " + t, items);
        }
    }

    /**
     * Safe local APK overview. This intentionally uses ZipFile metadata and PackageManager
     * archive parsing instead of recursively expanding the APK. It is designed for very large
     * APKs where the remote mt_apk_open implementation may recurse until StackOverflowError.
     */
    private static JSONObject analyzeLocalApk(JSONObject args) {
        JSONObject out = new JSONObject();
        String path = args == null ? "" : args.optString("path", "").trim();
        String query = args == null ? "" : args.optString("query", "").trim();
        JSONArray dex = new JSONArray();
        JSONArray libs = new JSONArray();
        JSONArray entries = new JSONArray();
        JSONArray top = new JSONArray();
        if (!isAndroidAbsolutePath(path) || !looksLikeApkPath(path)) {
            return jsonError(out, "只允许分析已发现的 Android APK 绝对路径", new JSONArray());
        }
        File apk = new File(path);
        if (!apk.isFile()) return jsonError(out, "APK 文件不存在: " + path, new JSONArray());
        try {
            out.put("ok", true);
            out.put("sourceType", "ANDROID_LOCAL_APK");
            out.put("path", apk.getCanonicalPath());
            out.put("name", apk.getName());
            out.put("sizeBytes", apk.length());
            out.put("lastModified", apk.lastModified());
            out.put("policy", "LOCAL_ZIP_OVERVIEW_NO_FULL_EXTRACTION");

            PackageInfo pi = null;
            try {
                Context ctx = applicationContext;
                if (ctx != null) {
                    int flags = PackageManager.GET_META_DATA | PackageManager.GET_PERMISSIONS |
                            PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES |
                            PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS;
                    pi = ctx.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), flags);
                }
            } catch (Throwable t) { log("[LOCAL-APK] PackageManager metadata failed: " + t); }
            if (pi != null) {
                out.put("packageName", pi.packageName == null ? "" : pi.packageName);
                if (pi.versionName != null) out.put("versionName", pi.versionName);
                out.put("versionCode", pi.getLongVersionCode());
                if (pi.applicationInfo != null) {
                    CharSequence label = null;
                    try { label = pi.applicationInfo.loadLabel(applicationContext.getPackageManager()); } catch (Throwable ignored) {}
                    if (label != null) out.put("label", String.valueOf(label));
                }
                out.put("activities", pi.activities == null ? 0 : pi.activities.length);
                out.put("services", pi.services == null ? 0 : pi.services.length);
                out.put("receivers", pi.receivers == null ? 0 : pi.receivers.length);
                out.put("providers", pi.providers == null ? 0 : pi.providers.length);
                out.put("requestedPermissions", pi.requestedPermissions == null ? 0 : pi.requestedPermissions.length);
            }

            java.util.zip.ZipFile zf = null;
            long totalUncompressed = 0L;
            long totalCompressed = 0L;
            int count = 0;
            List<String> queryMatches = new ArrayList<>();
            List<String> largest = new ArrayList<>();
            try {
                zf = new java.util.zip.ZipFile(apk, java.util.zip.ZipFile.OPEN_READ);
                Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    java.util.zip.ZipEntry e = en.nextElement();
                    count++;
                    long sz = Math.max(0L, e.getSize());
                    long csz = Math.max(0L, e.getCompressedSize());
                    totalUncompressed += sz;
                    totalCompressed += csz;
                    String n = e.getName();
                    if (n.endsWith(".dex") || n.matches("classes\\d+\\.dex")) {
                        JSONObject d = new JSONObject().put("name", n).put("sizeBytes", sz).put("compressedBytes", csz);
                        dex.put(d);
                    }
                    if (n.startsWith("lib/") && n.endsWith(".so")) {
                        JSONObject l = new JSONObject().put("name", n).put("sizeBytes", sz).put("compressedBytes", csz);
                        libs.put(l);
                    }
                    if (query.length() > 1 && n.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
                        if (queryMatches.size() < 100) queryMatches.add(n);
                    }
                    if (entries.length() < 80 && (n.equals("AndroidManifest.xml") || n.equals("resources.arsc") ||
                            n.startsWith("META-INF/") || n.startsWith("assets/") || n.startsWith("res/"))) {
                        entries.put(n);
                    }
                    if (!e.isDirectory()) {
                        largest.add(sz + "\t" + n);
                    }
                }
            } finally { if (zf != null) try { zf.close(); } catch (Throwable ignored) {} }
            Collections.sort(largest, new Comparator<String>() {
                @Override public int compare(String a, String b) {
                    try { return Long.compare(Long.parseLong(b.substring(0, b.indexOf('\t'))), Long.parseLong(a.substring(0, a.indexOf('\t')))); }
                    catch (Throwable ignored) { return a.compareTo(b); }
                }
            });
            for (int i = 0; i < Math.min(20, largest.size()); i++) {
                String x = largest.get(i); int tab = x.indexOf('\t');
                if (tab > 0) top.put(new JSONObject().put("name", x.substring(tab + 1)).put("sizeBytes", Long.parseLong(x.substring(0, tab))));
            }
            out.put("zipEntryCount", count);
            out.put("uncompressedBytes", totalUncompressed);
            out.put("compressedEntryBytes", totalCompressed);
            out.put("dexFiles", dex);
            out.put("nativeLibraries", libs);
            out.put("importantEntries", entries);
            out.put("largestEntries", top);
            if (query.length() > 1) out.put("entryNameMatches", new JSONArray(queryMatches));
            out.put("analysisNote", "未完整解压 APK；仅读取 ZIP central directory 元数据并使用 Android PackageManager 读取包信息，适合超大 APK。后续可按用户指定 DEX/资源/字符串继续按需读取。");
            rememberLocalApkContext(apk.getCanonicalPath());
            MainHook.log("[LOCAL-APK] analyze path=" + apk.getAbsolutePath() + " entries=" + count + " dex=" + dex.length() + " libs=" + libs.length());
            return out;
        } catch (java.util.zip.ZipException ze) {
            return jsonError(out, "APK/ZIP 结构无法读取: " + ze, new JSONArray());
        } catch (SecurityException se) {
            return jsonError(out, "APK 文件访问被拒绝: " + se, new JSONArray());
        } catch (Throwable t) {
            return jsonError(out, "本地 APK 分析失败: " + t, new JSONArray());
        }
    }


    private static boolean hasLocalBuiltinContinuationContext() {
        return !ACTIVE_LOCAL_APK_PATH.isEmpty() && System.currentTimeMillis() - ACTIVE_LOCAL_APK_AT < 30 * 60_000L;
    }

    private static String continuationOperation(String text) {
        String l = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (containsAny(l,"搜索","查找","字符串","类名","方法","search","find","string")) return "search";
        if (containsAny(l,"条目","zip","目录","entries")) return "entries";
        if (containsAny(l,"dex","classes")) return "dex_names";
        return "overview";
    }

    private static void rememberLocalApkContext(String path) {
        ACTIVE_LOCAL_APK_PATH = path == null ? "" : path;
        ACTIVE_LOCAL_APK_AT = System.currentTimeMillis();
        try {
            Context c = applicationContext;
            if (c != null) {
                File f = new File(c.getFilesDir(), "native_apk_context.json");
                JSONObject o = new JSONObject().put("path", ACTIVE_LOCAL_APK_PATH).put("at", ACTIVE_LOCAL_APK_AT);
                FileOutputStream fos = new FileOutputStream(f); fos.write(o.toString().getBytes(StandardCharsets.UTF_8)); fos.close();
            }
        } catch (Throwable ignored) {}
    }

    private static void restoreLocalApkContext() {
        if (!ACTIVE_LOCAL_APK_PATH.isEmpty() && System.currentTimeMillis() - ACTIVE_LOCAL_APK_AT < 30 * 60_000L) return;
        try {
            Context c = applicationContext; if (c == null) return;
            File f = new File(c.getFilesDir(), "native_apk_context.json"); if (!f.isFile()) return;
            byte[] b = readAll(new FileInputStream(f), 4096); JSONObject o = new JSONObject(new String(b, StandardCharsets.UTF_8));
            long at = o.optLong("at",0L); String path=o.optString("path","");
            if (!path.isEmpty() && System.currentTimeMillis()-at < 30*60_000L && new File(path).isFile()) { ACTIVE_LOCAL_APK_PATH=path; ACTIVE_LOCAL_APK_AT=at; }
        } catch(Throwable ignored) {}
    }

    private static JSONObject inspectLocalApk(JSONObject args) {
        restoreLocalApkContext();
        String path=args==null?"":args.optString("path","").trim(); if(path.isEmpty()) path=ACTIVE_LOCAL_APK_PATH;
        String op=args==null?"overview":args.optString("operation","overview"); String q=args==null?"":args.optString("query","");
        int limit=args==null?100:Math.max(1,Math.min(500,args.optInt("maxResults",100)));
        JSONObject out=new JSONObject(); JSONArray items=new JSONArray();
        if(!isAndroidAbsolutePath(path)||!looksLikeApkPath(path)) return jsonError(out,"没有可继续分析的本地 APK 上下文",items);
        File apk=new File(path); if(!apk.isFile()) return jsonError(out,"APK 已不存在: "+path,items);
        rememberLocalApkContext(path);
        try {
            java.util.zip.ZipFile zf=new java.util.zip.ZipFile(apk,java.util.zip.ZipFile.OPEN_READ);
            try {
                if("dex_names".equals(op)) {
                    Enumeration<? extends java.util.zip.ZipEntry> en=zf.entries(); while(en.hasMoreElements()&&items.length()<limit){String n=en.nextElement().getName();if(n.matches("classes(\\d+)?\\.dex")) items.put(n);}
                } else if("entries".equals(op) || "search".equals(op)) {
                    String qq=q.toLowerCase(Locale.ROOT); Enumeration<? extends java.util.zip.ZipEntry> en=zf.entries();
                    while(en.hasMoreElements()&&items.length()<limit){java.util.zip.ZipEntry e=en.nextElement();String n=e.getName();if("entries".equals(op)||(qq.length()>0&&n.toLowerCase(Locale.ROOT).contains(qq)))items.put(new JSONObject().put("name",n).put("sizeBytes",Math.max(0L,e.getSize())).put("compressedBytes",Math.max(0L,e.getCompressedSize())));}
                } else if("read_entry".equals(op)) {
                    String entry=args==null?"":args.optString("entry","").trim();
                    if(entry.isEmpty()||entry.contains("..")||entry.startsWith("/")) return jsonError(out,"entry 路径非法",items);
                    java.util.zip.ZipEntry e=zf.getEntry(entry); if(e==null)return jsonError(out,"ZIP 条目不存在: "+entry,items);
                    int max=args==null?65536:Math.max(1024,Math.min(262144,args.optInt("maxBytes",65536)));
                    InputStream in=zf.getInputStream(e); byte[] b=readAll(in,max); out.put("entry",entry).put("bytesRead",b.length).put("truncated",e.getSize()>b.length).put("text",new String(b,StandardCharsets.UTF_8));
                } else {
                    out.put("path",path).put("sizeBytes",apk.length()).put("next","可继续使用 entries/search/dex_names/read_entry");
                }
            } finally { try{zf.close();}catch(Throwable ignored){} }
            out.put("ok",true).put("sourceType","ANDROID_LOCAL_APK").put("operation",op).put("items",items).put("path",apk.getCanonicalPath());
            return out;
        } catch(Throwable t){return jsonError(out,"本地 APK 继续分析失败: "+t,items);}
    }


    /**
     * FIXED34: Resolve Android manifest components to concrete DEX class descriptors.
     * This is intentionally based on PackageManager + the actual classes*.dex type table;
     * it does not infer class names from natural-language guesses.
     */
    private static JSONObject apkEntryPoints(JSONObject a) throws Exception {
        File apk = resolveLocalApk(a.optString("path", ""));
        JSONObject out = new JSONObject().put("ok", true).put("path", apk.getCanonicalPath());
        PackageManager pm = applicationContext == null ? null : applicationContext.getPackageManager();
        PackageInfo pi = null;
        if (pm != null) {
            try {
                int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES |
                        PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS;
                pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
            } catch (Throwable ignored) {}
        }
        JSONArray activities = new JSONArray(), services = new JSONArray(), receivers = new JSONArray(), providers = new JSONArray();
        String pkg = pi == null ? "" : pi.packageName;
        if (pi != null) {
            if (pi.activities != null) for (android.content.pm.ActivityInfo x : pi.activities) addComponent(activities, pkg, x.name, "activity", x.exported, x.permission);
            if (pi.services != null) for (android.content.pm.ServiceInfo x : pi.services) addComponent(services, pkg, x.name, "service", x.exported, x.permission);
            if (pi.receivers != null) for (android.content.pm.ActivityInfo x : pi.receivers) addComponent(receivers, pkg, x.name, "receiver", x.exported, x.permission);
            if (pi.providers != null) for (android.content.pm.ProviderInfo x : pi.providers) addComponent(providers, pkg, x.name, "provider", x.exported, x.readPermission);
        }
        out.put("packageName", pkg).put("activities", activities).put("services", services)
                .put("receivers", receivers).put("providers", providers);
        // Resolve component names against the actual DEX type table. This produces a concrete
        // class descriptor and dex file without reading or executing application code.
        JSONArray classes = new JSONArray();
        ZipFile zf = new ZipFile(apk, ZipFile.OPEN_READ);
        try {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (!n.matches("classes(\\d+)?\\.dex")) continue;
                byte[] b = readAll(zf.getInputStream(e), 32 * 1024 * 1024);
                if (b.length < 112 || b[0] != 'd' || b[1] != 'e' || b[2] != 'x') continue;
                int ss=u32(b,56), so=u32(b,60), ts=u32(b,64), to=u32(b,68);
                String[] strings=dexStrings(b,ss,so);
                for (int i=0;i<ts;i++) {
                    int q=to+i*4; if(q<0||q+4>b.length) break;
                    int si=u32(b,q); if(si<0||si>=strings.length) continue;
                    String desc=strings[si];
                    String dotted=desc.startsWith("L")&&desc.endsWith(";")?desc.substring(1,desc.length()-1).replace('/','.'):desc;
                    JSONObject hit = findComponentByName(activities,dotted,desc,"activity");
                    if(hit==null) hit=findComponentByName(services,dotted,desc,"service");
                    if(hit==null) hit=findComponentByName(receivers,dotted,desc,"receiver");
                    if(hit==null) hit=findComponentByName(providers,dotted,desc,"provider");
                    if(hit!=null) classes.put(new JSONObject().put("dex",n).put("typeIndex",i).put("descriptor",desc).put("componentType",hit.optString("componentType"))
                            .put("name",hit.optString("name")).put("exported",hit.optBoolean("exported",false)));
                }
            }
        } finally { zf.close(); }
        out.put("resolvedClasses", classes).put("resolvedCount", classes.length());
        return out;
    }

    private static void addComponent(JSONArray a,String pkg,String name,String kind,boolean exported,String permission) {
        if(name==null||name.trim().isEmpty()) return;
        String n=name.trim();
        if(n.startsWith(".")) n=pkg+n;
        else if(n.indexOf('.')<0 && pkg!=null && !pkg.isEmpty()) n=pkg+'.'+n;
        try { a.put(new JSONObject().put("name",n).put("componentType",kind).put("exported",exported).put("permission",permission==null?"":permission)); } catch(Throwable ignored) {}
    }
    private static JSONObject findComponentByName(JSONArray a,String dotted,String desc,String kind) {
        for(int i=0;i<a.length();i++) {
            JSONObject x=a.optJSONObject(i); if(x==null) continue;
            String n=x.optString("name","");
            if(n.equals(dotted)||n.equals(desc)||n.replace('/','.').equals(dotted)) return x;
        }
        return null;
    }

    /**
     * FIXED34: Produce concrete analysis entry points from manifest + DEX symbols.
     * Lifecycle candidates are only emitted when the corresponding method actually exists
     * in class_data/method_ids; otherwise they are marked missing rather than fabricated.
     */
    private static JSONObject apkEntryPointMethods(JSONObject a) throws Exception {
        File apk=resolveLocalApk(a.optString("path",""));
        JSONObject ep=apkEntryPoints(new JSONObject().put("path",apk.getCanonicalPath()));
        JSONObject sym=apkDexSymbols(new JSONObject().put("path",apk.getCanonicalPath()).put("maxResults",50000));
        JSONArray methods=sym.optJSONArray("methods");
        JSONArray result=new JSONArray(); JSONArray classes=ep.optJSONArray("resolvedClasses");
        String[] lifecycle={"onCreate","onStart","onResume","onPause","onStop","onDestroy","onNewIntent"};
        if(classes!=null) for(int i=0;i<classes.length();i++) {
            JSONObject c=classes.optJSONObject(i); if(c==null) continue;
            String cn=c.optString("descriptor","");
            for(String name:lifecycle) {
                JSONObject found=null;
                if(methods!=null) for(int j=0;j<methods.length();j++) {
                    JSONObject m=methods.optJSONObject(j); if(m==null) continue;
                    if(cn.equals(m.optString("class","")) && name.equals(m.optString("name",""))) { found=m; break; }
                }
                JSONObject x=new JSONObject().put("class",cn).put("name",name).put("componentType",c.optString("componentType"))
                        .put("present",found!=null);
                if(found!=null) x.put("dex",found.optString("dex","")).put("codeOff",found.optInt("codeOff",0)).put("accessFlags",found.optInt("accessFlags",0)).put("kind",found.optString("kind",""));
                result.put(x);
            }
        }
        return new JSONObject().put("ok",true).put("path",apk.getCanonicalPath()).put("packageName",ep.optString("packageName","")).put("entryPoints",result)
                .put("resolvedClasses",classes==null?new JSONArray():classes).put("methodSource","actual dex class_data/method_ids");
    }

    /**
     * FIXED34: Given a natural-language target, select only from concrete manifest classes and
     * already-indexed DEX methods. This is a read-only planner result, not code execution.
     */
    private static JSONObject apkAnalysisPlan(JSONObject a) throws Exception {
        File apk=resolveLocalApk(a.optString("path",ACTIVE_LOCAL_APK_PATH));
        String query=a.optString("query","").trim().toLowerCase(Locale.ROOT);
        JSONObject ep=apkEntryPointMethods(new JSONObject().put("path",apk.getCanonicalPath()));
        JSONArray eps=ep.optJSONArray("entryPoints"), candidates=new JSONArray();
        if(eps!=null) for(int i=0;i<eps.length();i++) {
            JSONObject x=eps.optJSONObject(i); if(x==null||!x.optBoolean("present",false)) continue;
            String hay=(x.optString("class","")+" "+x.optString("name","")+" "+x.optString("componentType","")).toLowerCase(Locale.ROOT);
            int score=0;
            if(query.isEmpty()) score=1;
            else {
                for(String part:query.split("[^a-z0-9_$.]+")) if(part.length()>1 && hay.contains(part)) score+=4;
                if(containsAny(query,"登录","login","signin","sign-in","认证","auth")) { if(containsAny(hay,"login","signin","auth","account")) score+=8; }
                if(containsAny(query,"启动","入口","启动页","launch","entry")) { if("onCreate".equals(x.optString("name"))) score+=6; }
            }
            if(score>0) candidates.put(new JSONObject(x.toString()).put("score",score));
        }
        // Deterministic descending score without inventing a candidate.
        List<JSONObject> list=new ArrayList<>(); for(int i=0;i<candidates.length();i++) list.add(candidates.optJSONObject(i));
        Collections.sort(list,new Comparator<JSONObject>(){public int compare(JSONObject x,JSONObject y){return Integer.compare(y.optInt("score"),x.optInt("score"));}});
        JSONArray ordered=new JSONArray(); int lim=Math.min(30,list.size()); for(int i=0;i<lim;i++) ordered.put(list.get(i));
        JSONArray next=new JSONArray();
        for(int i=0;i<Math.min(5,ordered.length());i++){JSONObject x=ordered.optJSONObject(i);if(x==null)continue;String cls=x.optString("class","");String name=x.optString("name","");next.put(new JSONObject().put("tool","apk_method_semantics").put("args",new JSONObject().put("path",apk.getCanonicalPath()).put("class",cls).put("method",name)).put("reason","真实 Manifest/DEX 入口候选"));}
        return new JSONObject().put("ok",true).put("query",query).put("candidates",ordered).put("nextTools",next).put("note","候选仅来自实际 Manifest 组件与 DEX class_data/method_ids；没有匹配项时不会伪造入口");
    }

    private static JSONObject apkManifest(JSONObject a) throws Exception {
        File apk=resolveLocalApk(a.optString("path","")); JSONObject out=new JSONObject().put("ok",true).put("path",apk.getCanonicalPath());
        PackageManager pm=applicationContext==null?null:applicationContext.getPackageManager();
        if(pm!=null){try{PackageInfo pi=pm.getPackageArchiveInfo(apk.getAbsolutePath(),PackageManager.GET_ACTIVITIES|PackageManager.GET_SERVICES|PackageManager.GET_PROVIDERS|PackageManager.GET_RECEIVERS);if(pi!=null){out.put("packageName",pi.packageName).put("versionName",pi.versionName==null?"":pi.versionName).put("versionCode",Build.VERSION.SDK_INT>=28?pi.getLongVersionCode():pi.versionCode).put("activityCount",pi.activities==null?0:pi.activities.length).put("serviceCount",pi.services==null?0:pi.services.length).put("providerCount",pi.providers==null?0:pi.providers.length).put("receiverCount",pi.receivers==null?0:pi.receivers.length);}}catch(Throwable ignored){}}
        ZipFile zf=new ZipFile(apk,ZipFile.OPEN_READ);try{ZipEntry e=zf.getEntry("AndroidManifest.xml");if(e==null)throw new IOException("AndroidManifest.xml 不存在");byte[] b=readAll(zf.getInputStream(e),4*1024*1024);boolean text=b.length>0&&b[0]=='<';out.put("format",text?"text-xml":"binary-axml").put("bytes",b.length);if(text){String xml=new String(b,StandardCharsets.UTF_8);out.put("xml",xml.length()>200000?xml.substring(0,200000):xml);}else out.put("binaryAxml",true).put("note","AndroidManifest.xml 为二进制 AXML；组件/包信息由 PackageManager 提取，原始条目可用 local_apk_inspect/read_entry 获取");}finally{zf.close();}return out;
    }
    private static JSONObject apkDexIndex(JSONObject a) throws Exception {
        File apk=resolveLocalApk(a.optString("path",""));int max=Math.max(100,Math.min(50000,a.optInt("maxClasses",20000)));JSONArray files=new JSONArray(),classes=new JSONArray();int dexCount=0;long classCount=0;
        ZipFile zf=new ZipFile(apk,ZipFile.OPEN_READ);try{Enumeration<? extends ZipEntry> en=zf.entries();while(en.hasMoreElements()){ZipEntry e=en.nextElement();String n=e.getName();if(!n.matches("classes(\\d+)?\\.dex"))continue;dexCount++;JSONObject di=new JSONObject().put("name",n).put("sizeBytes",e.getSize());byte[] all=readAll(zf.getInputStream(e),16*1024*1024);if(all.length>=112&&all[0]=='d'&&all[1]=='e'&&all[2]=='x'){int stringCount=u32(all,56),stringOff=u32(all,60),typeCount=u32(all,64),typeOff=u32(all,68),classSize=u32(all,96),classOff=u32(all,100);di.put("stringIdsSize",stringCount).put("stringIdsOff",stringOff).put("typeIdsSize",typeCount).put("typeIdsOff",typeOff).put("classDefsSize",classSize).put("classDefsOff",classOff).put("indexedBytes",all.length);if(classOff>=0&&classSize>=0&&classOff+classSize*32L<=all.length){for(int i=0;i<classSize&&classes.length()<max;i++){int off=classOff+i*32;int classIdx=u32(all,off);int access=u32(all,off+4);int superIdx=u32(all,off+8);String desc=dexTypeDescriptor(all,typeOff,typeCount,stringOff,stringCount,classIdx);classes.put(new JSONObject().put("dex",n).put("index",i).put("descriptor",desc).put("accessFlags",access).put("superTypeIndex",superIdx));classCount++;}}}files.put(di);}}finally{zf.close();}return new JSONObject().put("ok",true).put("path",apk.getCanonicalPath()).put("dexCount",dexCount).put("classCount",classCount).put("truncated",classes.length()>=max).put("files",files).put("classes",classes);
    }
    private static int u32(byte[] b,int o){if(o<0||o+4>b.length)return 0;return (b[o]&255)|((b[o+1]&255)<<8)|((b[o+2]&255)<<16)|((b[3+o]&255)<<24);}
    private static String dexTypeDescriptor(byte[] b,int typeOff,int typeCount,int stringOff,int stringCount,int idx){if(idx<0||idx>=typeCount)return "<invalid-type:"+idx+">";int p=typeOff+idx*4;if(p<0||p+4>b.length)return "<bad-type>";int si=u32(b,p);if(si<0||si>=stringCount)return "<invalid-string:"+si+">";int sp=stringOff+si*4;if(sp<0||sp+4>b.length)return "<bad-string-ref>";int data=u32(b,sp);if(data<=0||data>=b.length)return "<bad-string>";int q=data;while(q<b.length&&b[q]!=0)q++;return new String(b,data,q-data,StandardCharsets.UTF_8);}
    private static JSONObject apkDexSymbols(JSONObject a) throws Exception {
        File apk=resolveLocalApk(a.optString("path",""));
        String want=a.optString("class","").trim();
        String query=a.optString("query","").trim().toLowerCase(Locale.ROOT);
        int max=Math.max(100,Math.min(50000,a.optInt("maxResults",10000)));
        JSONArray classes=new JSONArray(), methods=new JSONArray(), fields=new JSONArray();
        ZipFile zf=new ZipFile(apk,ZipFile.OPEN_READ);
        try {
            Enumeration<? extends ZipEntry> en=zf.entries();
            while(en.hasMoreElements() && (classes.length()<max || methods.length()<max || fields.length()<max)) {
                ZipEntry e=en.nextElement(); String n=e.getName();
                if(!n.matches("classes(\\d+)?\\.dex")) continue;
                byte[] b=readAll(zf.getInputStream(e),32*1024*1024);
                if(b.length<112 || b[0]!='d'||b[1]!='e'||b[2]!='x') continue;
                int stringSize=u32(b,56), stringOff=u32(b,60), typeSize=u32(b,64), typeOff=u32(b,68);
                int protoSize=u32(b,72), protoOff=u32(b,76), fieldSize=u32(b,80), fieldOff=u32(b,84);
                int methodSize=u32(b,88), methodOff=u32(b,92), classSize=u32(b,96), classOff=u32(b,100);
                String[] strings=dexStrings(b,stringSize,stringOff);
                int[] typeString=new int[typeSize];
                for(int i=0;i<typeSize;i++){int q=typeOff+i*4; typeString[i]=(q+4<=b.length)?u32(b,q):-1;}
                String[] types=new String[typeSize];
                for(int i=0;i<typeSize;i++){int si=typeString[i];types[i]=(si>=0&&si<strings.length)?strings[si]:"<type:"+i+">";}
                JSONObject fileInfo=new JSONObject().put("dex",n).put("strings",stringSize).put("types",typeSize).put("protos",protoSize).put("fields",fieldSize).put("methods",methodSize).put("classes",classSize);
                // Build field/method id tables first so class_data can resolve their delta-encoded indices.
                JSONArray fieldIds=new JSONArray(), methodIds=new JSONArray();
                for(int i=0;i<fieldSize;i++){
                    int q=fieldOff+i*8; if(q<0||q+8>b.length) break;
                    int ci=u16(b,q), ti=u16(b,q+2), ni=u32(b,q+4);
                    String cn=ci<types.length?types[ci]:"<class:"+ci+">"; String fn=ni>=0&&ni<strings.length?strings[ni]:"<string:"+ni+">";
                    fieldIds.put(new JSONObject().put("class",cn).put("name",fn).put("type",ti<types.length?types[ti]:"<type:"+ti+">"));
                }
                for(int i=0;i<methodSize;i++){
                    int q=methodOff+i*8; if(q<0||q+8>b.length) break;
                    int ci=u16(b,q), pi=u16(b,q+2), ni=u32(b,q+4);
                    String cn=ci<types.length?types[ci]:"<class:"+ci+">"; String mn=ni>=0&&ni<strings.length?strings[ni]:"<string:"+ni+">";
                    methodIds.put(new JSONObject().put("class",cn).put("name",mn).put("protoIndex",pi));
                }
                if(classOff>=0 && classOff + classSize*32L <= b.length){
                    for(int ci=0;ci<classSize && classes.length()<max;ci++){
                        int q=classOff+ci*32; int classIdx=u32(b,q), access=u32(b,q+4), superIdx=u32(b,q+8), interfacesOff=u32(b,q+12), sourceIdx=u32(b,q+16), annotationsOff=u32(b,q+20), classDataOff=u32(b,q+24), staticValuesOff=u32(b,q+28);
                        String cn=classIdx<types.length?types[classIdx]:"<class:"+classIdx+">";
                        if(!want.isEmpty()&&!cn.equals(want)&&!cn.replace('/','.').equals(want.replace('/','.'))) continue;
                        if(!query.isEmpty()&&!cn.toLowerCase(Locale.ROOT).contains(query)) continue;
                        JSONObject co=new JSONObject().put("dex",n).put("classIndex",ci).put("descriptor",cn).put("accessFlags",access).put("super",superIdx<types.length?types[superIdx]:JSONObject.NULL).put("interfacesOff",interfacesOff).put("sourceString",sourceIdx>=0&&sourceIdx<strings.length?strings[sourceIdx]:JSONObject.NULL).put("classDataOff",classDataOff).put("staticValuesOff",staticValuesOff);
                        JSONArray cm=new JSONArray(), cf=new JSONArray();
                        if(classDataOff>0 && classDataOff<b.length){
                            int[] pos={classDataOff}; int staticFields=uleb(b,pos), instanceFields=uleb(b,pos), directMethods=uleb(b,pos), virtualMethods=uleb(b,pos);
                            int idx=0;
                            for(int k=0;k<staticFields+instanceFields;k++){int delta=uleb(b,pos); idx+=delta; int af=uleb(b,pos); if(idx>=0&&idx<fieldIds.length()){JSONObject f=fieldIds.getJSONObject(idx);JSONObject x=new JSONObject(f.toString()).put("accessFlags",af).put("kind",k<staticFields?"static":"instance");cf.put(x);if(fields.length()<max)fields.put(new JSONObject(x.toString()).put("dex",n));}}
                            idx=0;
                            for(int k=0;k<directMethods+virtualMethods;k++){int delta=uleb(b,pos);idx+=delta;int af=uleb(b,pos);int code=uleb(b,pos);if(idx>=0&&idx<methodIds.length()){JSONObject m=methodIds.getJSONObject(idx);JSONObject x=new JSONObject(m.toString()).put("accessFlags",af).put("codeOff",code).put("kind",k<directMethods?"direct":"virtual");cm.put(x);if(methods.length()<max)methods.put(new JSONObject(x.toString()).put("dex",n));}}
                        }
                        co.put("fields",cf).put("methods",cm); classes.put(co);
                    }
                }
                fileInfo.put("classDataParsed",classes.length()>0); fileInfo.put("methodIdsParsed",methodIds.length()); fileInfo.put("fieldIdsParsed",fieldIds.length());
                // File-level information is kept under a deterministic array so callers can inspect multiple dex files.
                if(classes.length()>=max) break;
            }
        } finally { zf.close(); }
        return new JSONObject().put("ok",true).put("path",apk.getCanonicalPath()).put("classes",classes).put("methods",methods).put("fields",fields).put("truncated",classes.length()>=max||methods.length()>=max||fields.length()>=max);
    }
    /** FIXED31: build real DEX method/string/type xrefs from code_item instructions. */
    private static JSONObject apkDexXref(JSONObject a) throws Exception {
        File apk=resolveLocalApk(a.optString("path",""));
        String query=a.optString("query","").trim().toLowerCase(Locale.ROOT);
        String kind=a.optString("kind","all").trim().toLowerCase(Locale.ROOT);
        int max=Math.max(20,Math.min(10000,a.optInt("maxResults",1000)));
        JSONArray hits=new JSONArray(), calls=new JSONArray(), inheritance=new JSONArray();
        HashMap<String,JSONArray> outgoing=new HashMap<>();
        HashMap<String,String> superOf=new HashMap<>();
        ZipFile zf=new ZipFile(apk,ZipFile.OPEN_READ);
        try {
            Enumeration<? extends ZipEntry> en=zf.entries();
            while(en.hasMoreElements()) {
                ZipEntry e=en.nextElement(); String n=e.getName();
                if(!n.matches("classes(\\d+)?\\.dex")) continue;
                byte[] b=readAll(zf.getInputStream(e),48*1024*1024);
                if(b.length<112 || b[0]!='d'||b[1]!='e'||b[2]!='x') continue;
                int ss=u32(b,56), so=u32(b,60), ts=u32(b,64), to=u32(b,68), fs=u32(b,80), fo=u32(b,84), ms=u32(b,88), mo=u32(b,92), cs=u32(b,96), co=u32(b,100);
                String[] strings=dexStrings(b,ss,so); String[] types=new String[Math.max(0,ts)];
                for(int i=0;i<types.length;i++){int q=to+i*4,si=u32(b,q);types[i]=(si>=0&&si<strings.length)?strings[si]:"<type:"+i+">";}
                JSONArray methods=new JSONArray(), fields=new JSONArray();
                for(int i=0;i<ms;i++){int q=mo+i*8;if(q+8>b.length)break;int ci=u16(b,q),pi=u16(b,q+2),ni=u32(b,q+4);String cn=ci<types.length?types[ci]:"<class:"+ci+">",mn=ni<strings.length?strings[ni]:"<method:"+ni+">";methods.put(new JSONObject().put("class",cn).put("name",mn).put("protoIndex",pi));}
                for(int i=0;i<fs;i++){int q=fo+i*8;if(q+8>b.length)break;int ci=u16(b,q),ti=u16(b,q+2),ni=u32(b,q+4);String cn=ci<types.length?types[ci]:"<class:"+ci+">",fn=ni<strings.length?strings[ni]:"<field:"+ni+">";fields.put(new JSONObject().put("class",cn).put("name",fn).put("type",ti<types.length?types[ti]:"<type:"+ti+">"));}
                if(co<0 || co+(long)cs*32>b.length) continue;
                for(int ci=0;ci<cs;ci++){
                    int q=co+ci*32, classIdx=u32(b,q), superIdx=u32(b,q+8), dataOff=u32(b,q+24);
                    String cn=classIdx<types.length?types[classIdx]:"<class:"+classIdx+">";
                    if(superIdx>=0 && superIdx<types.length){superOf.put(cn,types[superIdx]); if(inheritance.length()<max) inheritance.put(new JSONObject().put("dex",n).put("class",cn).put("super",types[superIdx]));}
                    if(dataOff<=0||dataOff>=b.length) continue;
                    int[] pos={dataOff}; int sf=uleb(b,pos),inf=uleb(b,pos),dm=uleb(b,pos),vm=uleb(b,pos); pos[0]=dataOff;
                    // skip field arrays, then walk direct+virtual methods.
                    uleb(b,pos); uleb(b,pos); uleb(b,pos); uleb(b,pos);
                    int fieldCount=sf+inf, idx=0; for(int k=0;k<fieldCount;k++){idx+=uleb(b,pos);uleb(b,pos);}
                    idx=0; int total=dm+vm;
                    for(int k=0;k<total;k++){
                        idx+=uleb(b,pos); int af=uleb(b,pos), code=uleb(b,pos);
                        if(idx<0||idx>=methods.length()) continue;
                        JSONObject mi=methods.getJSONObject(idx); String owner=mi.optString("class"), mn=mi.optString("name");
                        String methodKey=owner+"->"+mn+"#"+idx;
                        if(code<=0||code+16>b.length) continue;
                        int registers=u16(b,code), ins=u16(b,code+2), outs=u16(b,code+4), tries=u16(b,code+6), insns=u32(b,code+12), insnsOff=code+16;
                        if(insnsOff<0||insnsOff+(long)insns*2>b.length) continue;
                        JSONArray refs=new JSONArray();
                        for(int pc=0;pc<insns;){
                            int op=b[insnsOff+pc*2]&255; int ref=-1; String refKind=null, refValue=null; int width=1;
                            if(op==0x1a && pc+2<insns){ref=u16(b,insnsOff+(pc+1)*2);refKind="string";refValue=ref<strings.length?strings[ref]:"<string:"+ref+">";width=2;}
                            else if(op==0x1b && pc+3<insns){ref=u32(b,insnsOff+(pc+1)*2);refKind="string";refValue=ref<strings.length?strings[ref]:"<string:"+ref+">";width=3;}
                            else if(op>=0x52&&op<=0x6d){
                                if(pc+2<insns){ref=u16(b,insnsOff+(pc+1)*2);refKind="field";refValue=ref<fields.length()?fieldDisplay(fields,ref):"<field:"+ref+">";width=2;}
                            } else if((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78)){
                                if(pc+2<insns){ref=u16(b,insnsOff+(pc+1)*2);refKind="method";refValue=ref<methods.length()?methodDisplay(methods,ref):"<method:"+ref+">";width=(op>=0x74?3:3);}
                            } else if(op==0x1c||op==0x1f||op==0x20||op==0x22){
                                if(pc+2<insns){ref=u16(b,insnsOff+(pc+1)*2);refKind="type";refValue=ref<types.length?types[ref]:"<type:"+ref+">";width=2;}
                            }
                            if(refKind!=null){JSONObject rr=new JSONObject().put("from",methodKey).put("class",owner).put("method",mn).put("pc",pc).put("opcode",op).put("kind",refKind).put("index",ref).put("value",refValue).put("dex",n);refs.put(rr);if(hits.length()<max && ("all".equals(kind)||kind.equals(refKind)) && (query.isEmpty()||refValue.toLowerCase(Locale.ROOT).contains(query)||methodKey.toLowerCase(Locale.ROOT).contains(query))) hits.put(rr);if("method".equals(refKind)){String target=refValue;JSONArray oa=outgoing.get(methodKey);if(oa==null){oa=new JSONArray();outgoing.put(methodKey,oa);}oa.put(target);if(calls.length()<max && (query.isEmpty()||target.toLowerCase(Locale.ROOT).contains(query)||methodKey.toLowerCase(Locale.ROOT).contains(query)))calls.put(rr);}}
                            pc+=Math.max(1,width);
                        }
                    }
                }
            }
        } finally { zf.close(); }
        JSONArray edges=new JSONArray(); for(Map.Entry<String,JSONArray> e:outgoing.entrySet()){JSONArray aout=e.getValue();for(int i=0;i<aout.length()&&edges.length()<max;i++)edges.put(new JSONObject().put("from",e.getKey()).put("to",aout.optString(i)).put("kind","method_call"));}
        return new JSONObject().put("ok",true).put("path",apk.getCanonicalPath()).put("query",query).put("kind",kind).put("hits",hits).put("calls",calls).put("edges",edges).put("inheritance",inheritance).put("truncated",hits.length()>=max||edges.length()>=max);
    }
    private static String fieldDisplay(JSONArray fields,int idx){try{JSONObject f=fields.getJSONObject(idx);return f.optString("class")+"->"+f.optString("name")+":"+f.optString("type")+"#"+idx;}catch(Throwable t){return "<field:"+idx+">";}}
    private static String methodDisplay(JSONArray methods,int idx){try{JSONObject m=methods.getJSONObject(idx);return m.optString("class")+"->"+m.optString("name")+"#"+idx;}catch(Throwable t){return "<method:"+idx+">";}}
    /** FIXED32: locate one real encoded_method/code_item and decode its instruction stream. */
    private static JSONObject apkMethodRead(JSONObject a) throws Exception {
        File apk=resolveLocalApk(a.optString("path",""));
        String cls=a.optString("class","").trim(); String mn=a.optString("method","").trim(); String key=a.optString("methodKey","").trim();
        int max=Math.max(16,Math.min(20000,a.optInt("maxInstructions",4000)));
        ZipFile zf=new ZipFile(apk,ZipFile.OPEN_READ); try {
            Enumeration<? extends ZipEntry> en=zf.entries();
            while(en.hasMoreElements()) { ZipEntry e=en.nextElement(); String n=e.getName(); if(!n.matches("classes(\\d+)?\\.dex")) continue;
                byte[] b=readAll(zf.getInputStream(e),64*1024*1024); if(b.length<112||b[0]!='d'||b[1]!='e'||b[2]!='x')continue;
                JSONObject r=findDexMethodCode(b,n,cls,mn,key,max); if(r!=null)return r;
            }
        } finally { zf.close(); }
        throw new IOException("未找到目标方法: "+(key.isEmpty()?cls+"->"+mn:key));
    }

    private static JSONObject findDexMethodCode(byte[] b,String dex,String cls,String mn,String key,int max) throws Exception {
        int ss=u32(b,56),so=u32(b,60),ts=u32(b,64),to=u32(b,68),ms=u32(b,88),mo=u32(b,92),cs=u32(b,96),co=u32(b,100);
        String[] strings=dexStrings(b,ss,so); String[] types=new String[Math.max(0,ts)];
        for(int i=0;i<types.length;i++){int q=to+i*4,si=u32(b,q);types[i]=si>=0&&si<strings.length?strings[si]:"<type:"+i+">";}
        if(co<0||co+(long)cs*32>b.length)return null;
        for(int ci=0;ci<cs;ci++){
            int q=co+ci*32,classIdx=u32(b,q),dataOff=u32(b,q+24); String owner=classIdx>=0&&classIdx<types.length?types[classIdx]:"<class:"+classIdx+">";
            if(!cls.isEmpty()&&!owner.equals(cls)&&!owner.replace('/','.').equals(cls.replace('/','.')))continue;
            if(dataOff<=0||dataOff>=b.length)continue;
            int[] pos={dataOff};int sf=uleb(b,pos),inf=uleb(b,pos),dm=uleb(b,pos),vm=uleb(b,pos);int fc=sf+inf,idx=0;
            for(int i=0;i<fc;i++){idx+=uleb(b,pos);uleb(b,pos);} idx=0;
            int total=dm+vm;
            for(int i=0;i<total;i++){
                idx+=uleb(b,pos);int access=uleb(b,pos),codeOff=uleb(b,pos); String name=idx<ms?dexMethodName(b,mo,idx,strings,types):"<method:"+idx+">";
                String mk=owner+"->"+name+"#"+idx;
                if((!mn.isEmpty()&&!name.equals(mn))||(!key.isEmpty()&&!mk.equals(key)))continue;
                if(codeOff<=0||codeOff+16>b.length)throw new IOException("目标方法没有可读取的 code_item: "+mk);
                int registers=u16(b,codeOff),ins=u16(b,codeOff+2),outs=u16(b,codeOff+4),tries=u16(b,codeOff+6),debug=u32(b,codeOff+8),insns=u32(b,codeOff+12),base=codeOff+16;
                if(base<0||base+(long)insns*2>b.length)throw new IOException("code_item 超出 DEX 边界: "+mk);
                JSONArray ops=new JSONArray(); int pc=0,count=0;
                while(pc<insns&&count<max){int op=b[base+pc*2]&255;int width=dexInsnWidth(b,base,pc,insns,op);String mnemonic=dexOpcodeName(op);JSONObject o=new JSONObject().put("pc",pc).put("opcode",op).put("mnemonic",mnemonic).put("width",width);
                    if(isBranchOpcode(op)){int target=dexBranchTarget(b,base,pc,insns,op);if(target>=0)o.put("targetPc",target);}
                    if(isReturnOpcode(op))o.put("terminal",true); ops.put(o);pc+=Math.max(1,width);count++;
                }
                return new JSONObject().put("ok",true).put("dex",dex).put("class",owner).put("method",name).put("methodIndex",idx).put("methodKey",mk).put("accessFlags",access).put("codeOff",codeOff).put("registersSize",registers).put("insSize",ins).put("outsSize",outs).put("triesSize",tries).put("debugInfoOff",debug).put("insnsSize",insns).put("instructions",ops).put("truncated",pc<insns);
            }
        }
        return null;
    }
    private static String dexMethodName(byte[] b,int mo,int idx,String[] strings,String[] types){int q=mo+idx*8;if(q<0||q+8>b.length)return "<method:"+idx+">";int ni=u32(b,q+4);return ni>=0&&ni<strings.length?strings[ni]:"<method:"+idx+">";}
    private static int dexInsnWidth(byte[] b,int base,int pc,int size,int op){
        if(op==0x00){if(pc+1<size){int hi=(b[base+(pc+1)*2]&255)<<8|(b[base+pc*2+1]&255);if(hi==0x0100)return 4;if(hi==0x0200)return 2;}return 1;}
        if((op>=0x0a&&op<=0x11)||op==0x1a||op==0x1c||op==0x1d||op==0x1e||op==0x1f||op==0x20||op==0x22||op==0x23||op==0x24||op==0x25||op==0x26||op==0x27||op==0x28||op==0x29||op==0x2a||op==0x2b||op==0x2c)return 2;
        if(op==0x1b)return 3;
        if(op==0x2b||op==0x2c)return 3;
        if(op>=0x52&&op<=0x6d)return 2;
        if((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78))return 3;
        if(op==0x2d||op==0x2e||op==0x2f||op==0x30||op==0x31)return 2;
        return 1;
    }
    private static boolean isReturnOpcode(int op){return op==0x0e||(op>=0x0f&&op<=0x11)||op==0x27||op==0x28||op==0x29||op==0x2a||op==0x2b||op==0x2c;}
    private static boolean isBranchOpcode(int op){return op==0x28||op==0x29||op==0x2a||(op>=0x32&&op<=0x3d);}
    private static int dexBranchTarget(byte[] b,int base,int pc,int size,int op){
        if(op==0x28){int off=(byte)b[base+pc*2+1];return pc+off;}
        if(op==0x29){int rel=(short)u16(b,base+(pc+1)*2);return pc+rel;}
        if(op==0x2a||op==0x2b||op==0x2c){if(pc+2>=size)return -1;int rel=(b[base+(pc+1)*2]&255)|((b[base+(pc+1)*2+1]&255)<<8)|((b[base+(pc+2)*2]&255)<<16)|((b[base+(pc+2)*2+1]&255)<<24);return pc+rel;}
        if(op>=0x32&&op<=0x37){if(pc+1>=size)return -1;return pc+(short)u16(b,base+(pc+1)*2);}
        if(op>=0x38&&op<=0x3d){if(pc+1>=size)return -1;return pc+(short)u16(b,base+(pc+1)*2);}
        return -1;
    }
    private static String dexOpcodeName(int op){
        switch(op){case 0x00:return "nop";case 0x01:return "move";case 0x02:return "move/from16";case 0x03:return "move/16";case 0x0e:return "return-void";case 0x0f:return "return";case 0x10:return "return-wide";case 0x11:return "return-object";case 0x1a:return "const-string";case 0x1b:return "const-string/jumbo";case 0x1c:return "const-class";case 0x1f:return "check-cast";case 0x20:return "instance-of";case 0x22:return "new-instance";case 0x28:return "goto";case 0x29:return "goto/16";case 0x2a:return "goto/32";case 0x2b:return "packed-switch";case 0x2c:return "sparse-switch";default:if(op>=0x32&&op<=0x37)return "if-test";if(op>=0x38&&op<=0x3d)return "if-testz";if(op>=0x52&&op<=0x5f)return "iget/iput";if(op>=0x60&&op<=0x6d)return "sget/sput";if(op>=0x6e&&op<=0x72)return "invoke";if(op>=0x74&&op<=0x78)return "invoke/range";return "op_"+Integer.toHexString(op);}
    }

    /** FIXED32: build basic blocks from actual branch targets and terminal instructions. */
    private static JSONObject apkMethodCfg(JSONObject a) throws Exception {
        JSONObject method=apkMethodRead(new JSONObject(a.toString()).put("maxInstructions",20000)); JSONArray ins=method.optJSONArray("instructions"); if(ins==null)throw new IOException("没有指令");
        TreeSet<Integer> leaders=new TreeSet<>(); leaders.add(0); HashMap<Integer,JSONObject> byPc=new HashMap<>();
        for(int i=0;i<ins.length();i++){JSONObject o=ins.getJSONObject(i);int pc=o.optInt("pc",0);byPc.put(pc,o);if(o.has("targetPc"))leaders.add(o.optInt("targetPc"));if(o.optBoolean("terminal",false)&&i+1<ins.length())leaders.add(ins.getJSONObject(i+1).optInt("pc",0));}
        ArrayList<Integer> ls=new ArrayList<>(leaders); JSONArray blocks=new JSONArray(),edges=new JSONArray();
        for(int i=0;i<ls.size();i++){int start=ls.get(i),end=(i+1<ls.size()?ls.get(i+1):method.optInt("insnsSize",start));JSONArray arr=new JSONArray();for(int j=0;j<ins.length();j++){JSONObject o=ins.getJSONObject(j);int pc=o.optInt("pc",-1);if(pc>=start&&pc<end)arr.put(o);}blocks.put(new JSONObject().put("id",i).put("startPc",start).put("endPc",end).put("instructions",arr));}
        HashMap<Integer,Integer> blockOf=new HashMap<>();for(int i=0;i<blocks.length();i++){JSONObject b=blocks.getJSONObject(i);for(int pc=b.optInt("startPc");pc<b.optInt("endPc");pc++){if(byPc.containsKey(pc))blockOf.put(pc,i);}}
        for(int i=0;i<blocks.length();i++){JSONObject b=blocks.getJSONObject(i);JSONArray ains=b.optJSONArray("instructions");if(ains==null||ains.length()==0)continue;JSONObject last=ains.getJSONObject(ains.length()-1);if(last.has("targetPc")){int t=last.optInt("targetPc",-1),tb=blockOf.containsKey(t)?blockOf.get(t):-1;if(tb>=0)edges.put(new JSONObject().put("from",i).put("to",tb).put("kind","branch"));if(last.optInt("opcode",-1)>=0x32&&last.optInt("opcode",-1)<=0x3d&&i+1<blocks.length())edges.put(new JSONObject().put("from",i).put("to",i+1).put("kind","fallthrough"));}else if(!last.optBoolean("terminal",false)&&i+1<blocks.length())edges.put(new JSONObject().put("from",i).put("to",i+1).put("kind","fallthrough"));}
        return new JSONObject().put("ok",true).put("methodKey",method.optString("methodKey")).put("dex",method.optString("dex")).put("class",method.optString("class")).put("method",method.optString("method")).put("blocks",blocks).put("edges",edges).put("blockCount",blocks.length()).put("edgeCount",edges.length());
    }

    /** FIXED33: enrich one real DEX method with prototype/signature, register semantics and operand references. */
    private static JSONObject apkMethodSemantics(JSONObject a) throws Exception {
        File apk=resolveLocalApk(a.optString("path",""));
        String cls=a.optString("class","").trim(), mn=a.optString("method","").trim(), key=a.optString("methodKey","").trim();
        int max=Math.max(16,Math.min(20000,a.optInt("maxInstructions",4000)));
        ZipFile zf=new ZipFile(apk,ZipFile.OPEN_READ);
        try {
            Enumeration<? extends ZipEntry> en=zf.entries();
            while(en.hasMoreElements()) { ZipEntry e=en.nextElement(); String n=e.getName(); if(!n.matches("classes(\\d+)?\\.dex")) continue;
                byte[] b=readAll(zf.getInputStream(e),64*1024*1024); if(b.length<112||b[0]!='d'||b[1]!='e'||b[2]!='x')continue;
                JSONObject r=findDexMethodSemantics(b,n,cls,mn,key,max); if(r!=null)return r;
            }
        } finally { zf.close(); }
        throw new IOException("未找到目标方法: "+(key.isEmpty()?cls+"->"+mn:key));
    }

    private static JSONObject findDexMethodSemantics(byte[] b,String dex,String cls,String mn,String key,int max) throws Exception {
        int ss=u32(b,56),so=u32(b,60),ts=u32(b,64),to=u32(b,68),ps=u32(b,72),po=u32(b,76),ms=u32(b,88),mo=u32(b,92),cs=u32(b,96),co=u32(b,100);
        String[] strings=dexStrings(b,ss,so); String[] types=new String[Math.max(0,ts)];
        for(int i=0;i<types.length;i++){int q=to+i*4,si=u32(b,q);types[i]=si>=0&&si<strings.length?strings[si]:"<type:"+i+">";}
        if(co<0||co+(long)cs*32>b.length)return null;
        for(int ci=0;ci<cs;ci++){
            int q=co+ci*32,classIdx=u32(b,q),dataOff=u32(b,q+24); String owner=classIdx>=0&&classIdx<types.length?types[classIdx]:"<class:"+classIdx+">";
            if(!cls.isEmpty()&&!owner.equals(cls)&&!owner.replace('/','.').equals(cls.replace('/','.')))continue;
            if(dataOff<=0||dataOff>=b.length)continue;
            int[] pos={dataOff}; int sf=uleb(b,pos),inf=uleb(b,pos),dm=uleb(b,pos),vm=uleb(b,pos); int idx=0;
            for(int i=0;i<sf+inf;i++){idx+=uleb(b,pos);uleb(b,pos);} idx=0;
            for(int i=0;i<dm+vm;i++){
                idx+=uleb(b,pos); int access=uleb(b,pos),codeOff=uleb(b,pos);
                String name=dexMethodName(b,mo,idx,strings,types); String mk=owner+"->"+name+"#"+idx;
                if((!mn.isEmpty()&&!name.equals(mn))||(!key.isEmpty()&&!mk.equals(key)))continue;
                JSONObject proto=dexMethodProto(b,mo,idx,po,ps,types);
                if(codeOff<=0||codeOff+16>b.length) throw new IOException("目标方法没有可读取的 code_item: "+mk);
                int registers=u16(b,codeOff),ins=u16(b,codeOff+2),outs=u16(b,codeOff+4),tries=u16(b,codeOff+6),debug=u32(b,codeOff+8),insns=u32(b,codeOff+12),base=codeOff+16;
                if(base<0||base+(long)insns*2>b.length) throw new IOException("code_item 超出 DEX 边界: "+mk);
                JSONArray ops=new JSONArray(); int pc=0,count=0;
                while(pc<insns&&count<max){
                    int op=b[base+pc*2]&255; int width=dexInsnWidth(b,base,pc,insns,op); if(width<1||pc+width>insns) width=1;
                    JSONObject o=dexSemanticInstruction(b,base,pc,insns,op,width,strings,types,mo,ms,po,ps);
                    o.put("pc",pc).put("opcode",op).put("width",width).put("mnemonic",dexOpcodeName(op));
                    if(isBranchOpcode(op)){int target=dexBranchTarget(b,base,pc,insns,op);if(target>=0)o.put("targetPc",target);}
                    if(isReturnOpcode(op))o.put("terminal",true);
                    ops.put(o); pc+=width; count++;
                }
                String sig=proto.optString("descriptor","()V");
                return new JSONObject().put("ok",true).put("dex",dex).put("class",owner).put("method",name).put("methodIndex",idx).put("methodKey",mk)
                        .put("signature",owner+"->"+name+sig).put("prototype",proto).put("accessFlags",access)
                        .put("codeOff",codeOff).put("registersSize",registers).put("insSize",ins).put("outsSize",outs).put("triesSize",tries)
                        .put("debugInfoOff",debug).put("insnsSize",insns).put("instructions",ops).put("truncated",pc<insns)
                        .put("analysisNote","寄存器/操作数来自真实 DEX 指令格式；无法安全解析的操作数保留原始字节/索引，不猜测源码语义");
            }
        }
        return null;
    }

    private static JSONObject dexMethodProto(byte[] b,int mo,int idx,int protoOff,int protoSize,String[] types) {
        JSONObject o=new JSONObject();
        try {
            int mq=mo+idx*8; if(mq<0||mq+8>b.length){return o.put("descriptor","()V");}
            int pi=u16(b,mq+2); if(pi<0||pi>=protoSize){return o.put("protoIndex",pi).put("descriptor","()V");}
            int q=protoOff+pi*12; if(q<0||q+12>b.length){return o.put("protoIndex",pi).put("descriptor","()V");}
            int ret=u32(b,q+4), paramsOff=u32(b,q+8); StringBuilder d=new StringBuilder("("); JSONArray ps=new JSONArray();
            if(paramsOff>0&&paramsOff+4<=b.length){int n=u32(b,paramsOff);for(int i=0;i<n&&i<4096;i++){int ti=u16(b,paramsOff+4+i*2);String t=ti<types.length?types[ti]:"<type:"+ti+">";ps.put(t);d.append(t);}}
            d.append(')'); String r=ret<types.length?types[ret]:"V"; d.append(r);
            return o.put("protoIndex",pi).put("returnType",r).put("parameters",ps).put("descriptor",d.toString());
        } catch(Throwable t){try{return o.put("descriptor","()V").put("error",String.valueOf(t));}catch(Throwable ignored){return o;}}
    }

    private static JSONObject dexSemanticInstruction(byte[] b,int base,int pc,int size,int op,int width,String[] strings,String[] types,int methodOff,int methodSize,int protoOff,int protoSize) {
        JSONObject o=new JSONObject(); int w=base+pc*2;
        try {
            int a=0,bb=0,cc=0,index=-1;
            if((op>=0x01&&op<=0x0d)||op==0x1d||op==0x1e||op==0x07||op==0x08||op==0x09||op==0x0a||op==0x0b||op==0x0c||op==0x0d){a=b[w]&0x0f;bb=(b[w]>>>4)&0x0f;o.put("registers",new JSONArray().put(a).put(bb));}
            if(op==0x12){a=b[w]&0x0f;int lit=(b[w]>>4);o.put("register",a).put("literal",lit);}
            else if(op==0x13||op==0x15){a=b[w]&255;int lit=(short)u16(b,w+2);o.put("register",a).put("literal",lit);}
            else if(op==0x14){a=b[w]&255;int lit=u32(b,w+2);o.put("register",a).put("literal",lit);}
            else if(op==0x16){a=b[w]&255;long lit=(u16(b,w+2)&0xffffL)|((u16(b,w+4)&0xffffL)<<16)|((long)(u16(b,w+6)&0xffffL)<<32)|((long)(u16(b,w+8)&0xffffL)<<48);o.put("register",a).put("literal",lit);}
            else if(op==0x1a||op==0x1c||op==0x1f||op==0x22){a=b[w]&255;index=u16(b,w+2);o.put("register",a).put("index",index).put("referenceKind",op==0x1a?"string":"type");o.put("reference",op==0x1a?(index<strings.length?strings[index]:"<string:"+index+">"):(index<types.length?types[index]:"<type:"+index+">"));}
            else if(op==0x1b){a=b[w]&255;index=u32(b,w+2);o.put("register",a).put("index",index).put("referenceKind","string").put("reference",index>=0&&index<strings.length?strings[index]:"<string:"+index+">");}
            else if(op>=0x52&&op<=0x6d){a=b[w]&255;bb=(b[w]>>>4)&15;index=u16(b,w+2);o.put("registers",new JSONArray().put(a).put(bb)).put("index",index).put("referenceKind","field").put("reference",fieldDisplayFromBytes(b,index,op,strings,types));}
            else if((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78)){
                index=u16(b,w+2); if(op>=0x74){int first=u16(b,w+4),count=b[w]&255;JSONArray rs=new JSONArray();for(int i=0;i<count;i++)rs.put(first+i);o.put("registers",rs);} else {int count=(b[w]>>>4)&15;int g=(b[w+1]>>>4)&15,c=(b[w+1]&15),d=(b[w+2]>>>4)&15,e=(b[w+2]&15),f=(b[w+3]>>>4)&15;JSONArray rs=new JSONArray();if(count>0)rs.put(c);if(count>1)rs.put(d);if(count>2)rs.put(e);if(count>3)rs.put(f);if(count>4)rs.put(g);o.put("registers",rs).put("argumentCount",count);}
                o.put("index",index).put("referenceKind","method").put("reference",methodDisplayFromBytes(b,index,methodOff,methodSize,strings,types));
            } else if(op>=0x32&&op<=0x37){a=b[w]&15;bb=(b[w]>>>4)&15;o.put("registers",new JSONArray().put(a).put(bb)).put("branchTarget",pc+(short)u16(b,w+2));}
            else if(op>=0x38&&op<=0x3d){a=b[w]&255;o.put("register",a).put("branchTarget",pc+(short)u16(b,w+2));}
            else if(op==0x28){o.put("branchTarget",pc+(byte)b[w+1]);}
            else if(op==0x29){o.put("branchTarget",pc+(short)u16(b,w+2));}
            else if(op==0x2a){int rel=(b[w+2]&255)|((b[w+3]&255)<<8)|((b[w+4]&255)<<16)|((b[w+5]&255)<<24);o.put("branchTarget",pc+rel);}
            else if(op>=0x01&&op<=0x03){o.put("registerForm","move");}
            return o;
        } catch(Throwable t){try{return o.put("decodeError",String.valueOf(t));}catch(Throwable ignored){return o;}}
    }

    private static String fieldDisplayFromBytes(byte[] b,int idx,int op,String[] strings,String[] types){try{
        int fo=u32(b,84);int q=fo+idx*8;if(q<0||q+8>b.length)return "<field:"+idx+">";int ci=u16(b,q),ti=u16(b,q+2),ni=u32(b,q+4);String c=ci<types.length?types[ci]:"<type:"+ci+">",t=ti<types.length?types[ti]:"<type:"+ti+">",n=ni<strings.length?strings[ni]:"<string:"+ni+">";return c+"->"+n+":"+t;}catch(Throwable e){return "<field:"+idx+">";}}
    private static String methodDisplayFromBytes(byte[] b,int idx,int methodOff,int methodSize,String[] strings,String[] types){try{int q=methodOff+idx*8;if(idx<0||idx>=methodSize||q<0||q+8>b.length)return "<method:"+idx+">";int ci=u16(b,q),ni=u32(b,q+4);String c=ci<types.length?types[ci]:"<type:"+ci+">",n=ni<strings.length?strings[ni]:"<string:"+ni+">";return c+"->"+n+"#"+idx;}catch(Throwable e){return "<method:"+idx+">";}}

    private static JSONObject apkCallPath(JSONObject a) throws Exception {
        JSONObject g=apkDexXref(new JSONObject().put("path",a.optString("path","")).put("kind","method").put("maxResults",10000));
        String from=a.optString("from","").trim(), to=a.optString("to","").trim(); int maxDepth=Math.max(1,Math.min(20,a.optInt("maxDepth",8))), maxPaths=Math.max(1,Math.min(50,a.optInt("maxPaths",10)));
        HashMap<String,ArrayList<String>> adj=new HashMap<>(); JSONArray edges=g.optJSONArray("edges"); if(edges!=null)for(int i=0;i<edges.length();i++){JSONObject e=edges.getJSONObject(i);String f=e.optString("from"),t=e.optString("to");ArrayList<String> xs=adj.get(f);if(xs==null){xs=new ArrayList<>();adj.put(f,xs);}if(!xs.contains(t))xs.add(t);}
        JSONArray paths=new JSONArray(); if(!from.isEmpty()&&!to.isEmpty()){ArrayList<String> path=new ArrayList<>();HashSet<String> seen=new HashSet<>();dfsCallPath(from,to,adj,maxDepth,seen,path,paths,maxPaths);}
        return new JSONObject().put("ok",true).put("from",from).put("to",to).put("maxDepth",maxDepth).put("paths",paths).put("graphEdges",edges==null?0:edges.length()).put("truncated",paths.length()>=maxPaths);
    }
    private static void dfsCallPath(String cur,String target,HashMap<String,ArrayList<String>> adj,int depth,HashSet<String> seen,ArrayList<String> path,JSONArray out,int max){if(out.length()>=max||depth<0||seen.contains(cur))return;seen.add(cur);path.add(cur);if(cur.equals(target)){JSONArray p=new JSONArray();for(String x:path)p.put(x);out.put(p);}else{ArrayList<String> ns=adj.get(cur);if(ns!=null)for(String n:ns)dfsCallPath(n,target,adj,depth-1,seen,path,out,max);}path.remove(path.size()-1);seen.remove(cur);}

    private static int u16(byte[] b,int o){if(o<0||o+2>b.length)return 0;return (b[o]&255)|((b[o+1]&255)<<8);}
    private static int uleb(byte[] b,int[] pos){int p=pos[0],r=0,shift=0;while(p<b.length&&shift<35){int v=b[p++]&255;r|=(v&127)<<shift;if((v&128)==0){pos[0]=p;return r;}shift+=7;}pos[0]=Math.min(p,b.length);return r;}
    private static String[] dexStrings(byte[] b,int count,int off){String[] out=new String[Math.max(0,count)];for(int i=0;i<out.length;i++){int q=off+i*4;if(q<0||q+4>b.length){out[i]="<invalid>";continue;}int data=u32(b,q);if(data<=0||data>=b.length){out[i]="<invalid>";continue;}int p=data;while(p<b.length&&b[p]!=0)p++;out[i]=new String(b,data,Math.max(0,p-data),StandardCharsets.UTF_8);}return out;}

    private static JSONObject apkManifestDecoded(JSONObject a) throws Exception {
        File apk=resolveLocalApk(a.optString("path","")); ZipFile zf=new ZipFile(apk,ZipFile.OPEN_READ);
        try { ZipEntry e=zf.getEntry("AndroidManifest.xml"); if(e==null)throw new IOException("AndroidManifest.xml 不存在"); byte[] b=readAll(zf.getInputStream(e),8*1024*1024);
            if(b.length>0&&b[0]=='<') return new JSONObject().put("ok",true).put("format","text-xml").put("xml",new String(b,StandardCharsets.UTF_8));
            return decodeBinaryAxml(b);
        } finally { zf.close(); }
    }
    private static JSONObject decodeBinaryAxml(byte[] b) throws Exception {
        if(b.length<8)throw new IOException("AXML 太短"); int rootType=u16(b,0), rootSize=u16(b,2); if(rootType!=0x0003)throw new IOException("不是 XML chunk: 0x"+Integer.toHexString(rootType));
        String[] strings=new String[0]; JSONArray events=new JSONArray(); StringBuilder xml=new StringBuilder(); xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"); int off=8;
        while(off+8<=b.length){int type=u16(b,off), size=u32(b,off+4);if(size<8||off+size>b.length)break;
            if(type==0x0001){strings=parseAxmlStringPool(b,off,size);}
            else if(type==0x0102){int nameIdx=u32(b,off+20);int attrStart=u16(b,off+24),attrSize=u16(b,off+26),attrCount=u16(b,off+28);String name=axString(strings,nameIdx);xml.append('<').append(name);JSONArray attrs=new JSONArray();int ap=off+8+attrStart;for(int i=0;i<attrCount;i++){if(ap+20>b.length)break;int an=u32(b,ap+4),raw=u32(b,ap+8),tv=u32(b,ap+12),dv=u32(b,ap+16);String anm=axString(strings,an);String val=raw!=0?axString(strings,raw):((tv&0xff)==0x03?axString(strings,dv):Integer.toString(dv));attrs.put(new JSONObject().put("name",anm).put("value",val).put("type",tv&0xff).put("data",dv));xml.append(' ').append(anm).append("=\"").append(xmlEscape(val)).append("\"");ap+=attrSize>0?attrSize:20;}xml.append('>');events.put(new JSONObject().put("type","start").put("name",name).put("attributes",attrs));}
            else if(type==0x0103){int nameIdx=u32(b,off+20);String name=axString(strings,nameIdx);xml.append("</").append(name).append('>');events.put(new JSONObject().put("type","end").put("name",name));}
            else if(type==0x0104){int data=u32(b,off+16);String text=axString(strings,data);if(!text.isEmpty()){xml.append(xmlEscape(text));events.put(new JSONObject().put("type","text").put("text",text));}}
            off+=size;
        }
        return new JSONObject().put("ok",true).put("format","binary-axml").put("events",events).put("decodedXml",xml.toString()).put("stringCount",strings.length);
    }
    private static String[] parseAxmlStringPool(byte[] b,int off,int size){int base=off;int stringCount=u32(b,off+8),flags=u32(b,off+16),stringsStart=u32(b,off+20);String[] out=new String[Math.max(0,stringCount)];for(int i=0;i<out.length;i++){int q=off+28+i*4;if(q+4>b.length)continue;int rel=u32(b,q);int p=off+stringsStart+rel;if(p<off||p>=off+size||p>=b.length)continue;if((flags&0x100)!=0){int len=readUleb16(b,p);p+=len>0?2:0;int end=p;while(end+1<b.length&&b[end]!=0&&b[end+1]!=0)end+=2;out[i]=new String(b,p,Math.max(0,end-p),StandardCharsets.UTF_16LE);}else{int[] z={p};int len=uleb(b,z);p=z[0];int end=p;while(end<b.length&&b[end]!=0)end++;out[i]=new String(b,p,Math.max(0,end-p),StandardCharsets.UTF_8);}}return out;}
    private static int readUleb16(byte[] b,int p){if(p+1>=b.length)return 0;return (b[p]&255)|((b[p+1]&127)<<8);}
    private static String axString(String[] a,int i){return i>=0&&i<a.length&&a[i]!=null?a[i]:"";}
    private static String xmlEscape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}

    /** FIXED35: Extract real resource references from binary/text XML entries and correlate them with
     * manifest/component files. This is deliberately ID-level: unresolved resources are reported as
     * unresolved instead of inventing resource names. */
    private static JSONObject apkResourceRefs(JSONObject a) throws Exception {
        File apk=resolveLocalApk(a.optString("path",""));
        ZipFile zf=new ZipFile(apk,ZipFile.OPEN_READ); JSONArray refs=new JSONArray(); int files=0;
        try {
            Enumeration<? extends ZipEntry> en=zf.entries();
            while(en.hasMoreElements() && files<1000){
                ZipEntry e=en.nextElement(); String n=e.getName();
                if(e.isDirectory() || !(n.equals("AndroidManifest.xml") || (n.startsWith("res/") && n.endsWith(".xml")))) continue;
                files++;
                byte[] b=readAll(zf.getInputStream(e),4*1024*1024);
                if(b.length>0 && b[0]=='<') {
                    String x=new String(b,StandardCharsets.UTF_8);
                    java.util.regex.Matcher m=java.util.regex.Pattern.compile("@0x([0-9a-fA-F]{1,8})").matcher(x);
                    while(m.find() && refs.length()<5000) refs.put(new JSONObject().put("file",n).put("resourceId", "0x"+m.group(1)).put("source","text-xml").put("resolved",false));
                } else if(n.equals("AndroidManifest.xml")) {
                    // Binary AXML attributes contain typed resource IDs. Reuse our real decoder output
                    // and inspect attribute data fields instead of guessing names.
                    JSONObject dec=decodeBinaryAxml(b); JSONArray ev=dec.optJSONArray("events");
                    if(ev!=null) for(int i=0;i<ev.length() && refs.length()<5000;i++){
                        JSONObject z=ev.optJSONObject(i); if(z==null)continue; JSONArray at=z.optJSONArray("attributes"); if(at==null)continue;
                        for(int j=0;j<at.length() && refs.length()<5000;j++){JSONObject q=at.optJSONObject(j);if(q==null)continue;int type=q.optInt("type",0);long data=q.optLong("data",0);if(type==1 || type==2 || type==0x10 || type==0x11) refs.put(new JSONObject().put("file",n).put("attribute",q.optString("name")).put("resourceId","0x"+Long.toHexString(data)).put("source","binary-axml").put("valueType",type).put("resolved",false));}
                    }
                }
            }
        } finally { zf.close(); }
        return new JSONObject().put("ok",true).put("apk",apk.getCanonicalPath()).put("scannedXmlFiles",files).put("references",refs).put("truncated",refs.length()>=5000).put("resolution","resource IDs are exact; names require resources.arsc value decoding");
    }

    /** FIXED37: Result-driven native Agent loop.
     * The next operation is selected from the previous REAL result, not from a fixed
     * unconditional chain. Only local read-only APK analyzers are eligible here.
     */
    private static JSONObject agentAnalyzeApk(JSONObject a) throws Exception {
        File apk=resolveLocalApk(a.optString("path",ACTIVE_LOCAL_APK_PATH));
        String query=a.optString("query","").trim();
        int maxRounds=Math.max(1,Math.min(12,a.optInt("maxRounds",8)));
        int maxTargets=Math.max(1,Math.min(8,a.optInt("maxTargets",4)));
        JSONArray steps=new JSONArray();
        JSONArray findings=new JSONArray();
        Set<String> visited=new HashSet<>();
        String taskId="yb-agent-"+System.currentTimeMillis();
        JSONObject state=new JSONObject().put("taskId",taskId).put("path",apk.getCanonicalPath())
                .put("query",query).put("status","working").put("round",0).put("next","apk_analysis_plan");
        persistNativeAgentState(state);
        String next="apk_analysis_plan";
        JSONObject previous=null;
        String nextClass="", nextMethod="";
        for(int round=0; round<maxRounds && next!=null && !next.isEmpty(); round++) {
            state.put("round",round+1).put("next",next).put("lastUpdatedAt",System.currentTimeMillis());
            persistNativeAgentState(state);
            JSONObject result;
            String target="";
            String executedTool=next;
            if("apk_analysis_plan".equals(next)) {
                result=apkAnalysisPlan(new JSONObject().put("path",apk.getCanonicalPath()).put("query",query));
                next="apk_entry_point_methods";
            } else if("apk_entry_point_methods".equals(next)) {
                result=apkEntryPointMethods(new JSONObject().put("path",apk.getCanonicalPath()));
                JSONObject t=selectNextEntryMethod(result,query,visited);
                nextClass=t.optString("class",""); nextMethod=t.optString("method","");
                if(!nextClass.isEmpty()&&!nextMethod.isEmpty()) next="apk_method_semantics"; else next="apk_resource_refs";
            } else if("apk_method_semantics".equals(next)) {
                if(nextClass.isEmpty()||nextMethod.isEmpty()) { next="apk_resource_refs"; continue; }
                target=nextClass+"->"+nextMethod;
                result=apkMethodSemantics(new JSONObject().put("path",apk.getCanonicalPath()).put("class",nextClass).put("method",nextMethod).put("maxInstructions",1600));
                next="apk_method_cfg";
            } else if("apk_method_cfg".equals(next)) {
                target=nextClass+"->"+nextMethod;
                result=apkMethodCfg(new JSONObject().put("path",apk.getCanonicalPath()).put("class",nextClass).put("method",nextMethod).put("maxInstructions",1600));
                next="apk_dex_xref";
            } else if("apk_dex_xref".equals(next)) {
                target=nextClass+"->"+nextMethod;
                result=apkDexXref(new JSONObject().put("path",apk.getCanonicalPath()).put("query",target).put("kind","method").put("maxResults",700));
                // Decide from the actual xref result whether there is a useful call target.
                JSONObject candidate=selectNextCallTarget(result,visited);
                if(candidate!=null) {
                    String sourceClass=nextClass, sourceMethod=nextMethod;
                    nextClass=candidate.optString("class",""); nextMethod=candidate.optString("method","");
                    state.put("candidateFrom",sourceClass+"->"+sourceMethod).put("candidateTo",nextClass+"->"+nextMethod);
                    if(!nextClass.isEmpty()&&!nextMethod.isEmpty()) {
                        state.put("callPathFrom",sourceClass+"->"+sourceMethod).put("callPathTo",nextClass+"->"+nextMethod);
                        next="apk_call_path";
                    } else next="apk_resource_refs";
                } else next="apk_resource_refs";
            } else if("apk_call_path".equals(next)) {
                String from=state.optString("callPathFrom","");
                String to=state.optString("callPathTo","");
                target=from+" -> "+to;
                if(from.isEmpty()||to.isEmpty()) { result=new JSONObject().put("ok",false).put("reason","没有从真实 XRef 发现可追踪的调用目标"); next="apk_resource_refs"; }
                else {
                    result=apkCallPath(new JSONObject().put("path",apk.getCanonicalPath())
                            .put("from",from).put("to",to).put("maxDepth",6).put("maxPaths",3));
                    next="apk_resource_refs";
                }
            } else if("apk_resource_refs".equals(next)) {
                result=apkResourceRefs(new JSONObject().put("path",apk.getCanonicalPath()));
                next=null;
            } else {
                next=null; continue;
            }
            previous=result;
            JSONObject step=new JSONObject().put("round",round+1).put("tool",executedTool)
                    .put("result",result);
            if(!target.isEmpty()) step.put("target",target);
            steps.put(step);
            String summary=summarizeAgentResult(result);
            if(!summary.isEmpty()) findings.put(new JSONObject().put("round",round+1).put("summary",summary).put("target",target));
            if(target.contains("->") && ("apk_dex_xref".equals(next) || "apk_method_cfg".equals(next))) visited.add(target);
            // After resource refs, if we have another unvisited entry method, continue a second
            // evidence-driven branch when budget remains. This is selected from actual methods.
            if(next==null && round+1<maxRounds && visited.size()<maxTargets) {
                JSONObject ep=apkEntryPointMethods(new JSONObject().put("path",apk.getCanonicalPath()));
                JSONObject t=selectNextEntryMethod(ep,query,visited);
                if(!t.optString("class","").isEmpty()&&!t.optString("method","").isEmpty()) {
                    nextClass=t.optString("class",""); nextMethod=t.optString("method",""); next="apk_method_semantics";
                }
            }
        }
        boolean completed=next==null;
        state.put("status",completed?"completed":"paused").put("next",next==null?"":next)
                .put("stepCount",steps.length()).put("completedAt",completed?System.currentTimeMillis():0);
        persistNativeAgentState(state);
        return new JSONObject().put("ok",true).put("mode","result-driven-readonly-agent-loop")
                .put("taskId",taskId).put("path",apk.getCanonicalPath()).put("query",query)
                .put("steps",steps).put("findings",findings).put("stepCount",steps.length())
                .put("status",completed?"completed":"paused").put("next",next==null?"":next)
                .put("note","下一工具由上一真实结果选择；仅执行只读 APK 分析器");
    }

    private static JSONObject selectNextEntryMethod(JSONObject result,String query,Set<String> visited) throws Exception {
        JSONArray a=result.optJSONArray("entryPoints");
        if(a==null) return new JSONObject();
        String q=query.toLowerCase(Locale.ROOT);
        String[] preferred={"oncreate","onnewintent","onstart","onresume","onreceive","onbind","onserviceconnected"};
        JSONObject fallback=null;
        for(int i=0;i<a.length();i++) {
            JSONObject e=a.optJSONObject(i); if(e==null||!e.optBoolean("present",false)) continue;
            String c=e.optString("class",""), m=e.optString("name","");
            if(c.isEmpty()||m.isEmpty()) continue;
            String key=c+"->"+m; if(visited.contains(key)) continue;
            if(fallback==null) fallback=new JSONObject().put("class",c).put("method",m);
            String ml=m.toLowerCase(Locale.ROOT), cl=c.toLowerCase(Locale.ROOT);
            if(!q.isEmpty() && (cl.contains(q)||ml.contains(q))) return new JSONObject().put("class",c).put("method",m);
            for(String p:preferred) if(ml.equals(p)) return new JSONObject().put("class",c).put("method",m);
        }
        return fallback==null?new JSONObject():fallback;
    }

    private static JSONObject selectNextCallTarget(JSONObject result,Set<String> visited) throws Exception {
        JSONArray a=result.optJSONArray("references");
        if(a==null) a=result.optJSONArray("edges");
        if(a==null) return null;
        for(int i=0;i<a.length();i++) {
            JSONObject e=a.optJSONObject(i); if(e==null) continue;
            String to=e.optString("to",e.optString("target",""));
            if(to.startsWith("L") && to.contains(";->")) {
                String key=to;
                if(visited.contains(key)) continue;
                int sep=to.indexOf(";->");
                if(sep>1) {
                    String cls=to.substring(0,sep+1), rest=to.substring(sep+3);
                    int par=rest.indexOf('('); String mn=par>0?rest.substring(0,par):rest;
                    if(!mn.isEmpty()) return new JSONObject().put("class",cls).put("method",mn);
                }
            }
        }
        return null;
    }

    private static String summarizeAgentResult(JSONObject r) {
        if(r==null) return "";
        if(r.has("entryPoints")) return "发现 " + r.optJSONArray("entryPoints").length() + " 个入口方法候选";
        if(r.has("blocks")) return "CFG 基本块=" + r.optJSONArray("blocks").length();
        if(r.has("references")) return "XRef references=" + r.optJSONArray("references").length();
        if(r.has("references") && r.has("scannedXmlFiles")) return "资源引用扫描完成";
        if(r.has("references")) return "发现资源/代码引用=" + r.optJSONArray("references").length();
        if(r.has("method")) return "方法语义解析完成：" + r.optString("method");
        return r.optString("status",r.optBoolean("ok",false)?"工具执行完成":"");
    }

    private static void persistNativeAgentState(JSONObject state) {
        try {
            File f=new File(applicationContext!=null?applicationContext.getFilesDir():new File("."),"native_agent_task.json");
            File tmp=new File(f.getParentFile(),f.getName()+".tmp");
            FileOutputStream out=new FileOutputStream(tmp); out.write(state.toString().getBytes(StandardCharsets.UTF_8)); out.flush(); out.close();
            if(!tmp.renameTo(f)){ FileOutputStream o=new FileOutputStream(f);o.write(state.toString().getBytes(StandardCharsets.UTF_8));o.close();tmp.delete(); }
        } catch(Throwable t){ log("[AGENT-STATE] persist failed: "+t); }
    }

    private static JSONObject apkResourceTable(JSONObject a)throws Exception{
        File apk=resolveLocalApk(a.optString("path",""));ZipFile zf=new ZipFile(apk,ZipFile.OPEN_READ);try{ZipEntry e=zf.getEntry("resources.arsc");if(e==null)return new JSONObject().put("ok",true).put("present",false);byte[] b=readAll(zf.getInputStream(e),16*1024*1024);JSONArray chunks=new JSONArray();int off=0;while(off+8<=b.length&&chunks.length()<10000){int type=u16(b,off),head=u16(b,off+2),size=u32(b,off+4);if(size<8||off+size>b.length)break;chunks.put(new JSONObject().put("offset",off).put("type","0x"+Integer.toHexString(type)).put("headerSize",head).put("size",size));off+=size;}return new JSONObject().put("ok",true).put("present",true).put("bytes",b.length).put("chunkCount",chunks.length()).put("chunks",chunks);}finally{zf.close();}}

    private static JSONObject projectTrace(JSONObject a)throws Exception {
        String project=a.optString("project",ACTIVE_PROJECT); String from=a.optString("from","").trim(); String to=a.optString("to","").trim();
        if(project.isEmpty()||from.isEmpty()||to.isEmpty()) throw new IOException("project/from/to 不能为空");
        JSONObject x=projectJson(project,"input/dex-xref.json");
        JSONArray edges=x.optJSONArray("edges"); if(edges==null) throw new IOException("项目没有 input/dex-xref.json，请先 project_generate 或 project_run");
        HashMap<String,ArrayList<String>> adj=new HashMap<>();
        for(int i=0;i<edges.length();i++){JSONObject e=edges.getJSONObject(i);String f=e.optString("from"),t=e.optString("to");ArrayList<String> xs=adj.get(f);if(xs==null){xs=new ArrayList<>();adj.put(f,xs);}if(!xs.contains(t))xs.add(t);}
        int depth=Math.max(1,Math.min(20,a.optInt("maxDepth",8))), pathsN=Math.max(1,Math.min(50,a.optInt("maxPaths",10))); JSONArray paths=new JSONArray();
        dfsCallPath(from,to,adj,depth,new HashSet<String>(),new ArrayList<String>(),paths,pathsN);
        return new JSONObject().put("ok",true).put("project",safeLocalFile(project).getCanonicalPath()).put("from",from).put("to",to).put("maxDepth",depth).put("paths",paths).put("edgeCount",edges.length()).put("truncated",paths.length()>=pathsN);
    }

    private static JSONObject projectSearch(JSONObject a)throws Exception{
        String project=a.optString("project",ACTIVE_PROJECT);String q=a.optString("query","").trim().toLowerCase(Locale.ROOT);if(project.isEmpty()||q.isEmpty())throw new IOException("project/query 不能为空");File root=safeLocalFile(project);if(!root.isDirectory())throw new IOException("项目不存在");JSONArray hits=new JSONArray();walkSearch(root,root,q,hits,Math.max(1,Math.min(500,a.optInt("maxResults",100))));return new JSONObject().put("ok",true).put("project",root.getCanonicalPath()).put("query",q).put("results",hits).put("truncated",hits.length()>=a.optInt("maxResults",100));
    }
    private static void walkSearch(File root,File f,String q,JSONArray hits,int max)throws Exception{if(hits.length()>=max)return;if(f.isDirectory()){File[] xs=f.listFiles();if(xs!=null)for(File x:xs)walkSearch(root,x,q,hits,max);return;}if(f.length()>2*1024*1024)return;byte[] b=readAll(new FileInputStream(f),2*1024*1024);String s=new String(b,StandardCharsets.UTF_8);String low=s.toLowerCase(Locale.ROOT);int p=low.indexOf(q);if(p>=0)hits.put(new JSONObject().put("path",root.toPath().relativize(f.toPath()).toString()).put("offset",p).put("preview",s.substring(Math.max(0,p-120),Math.min(s.length(),p+Math.min(300,q.length()+180)))));}

    private static JSONObject apkResourceIndex(JSONObject a)throws Exception{File apk=resolveLocalApk(a.optString("path",""));int assets=0,nativeLibs=0,meta=0,xml=0,res=0,other=0;long total=0;JSONArray samples=new JSONArray();ZipFile zf=new ZipFile(apk,ZipFile.OPEN_READ);try{Enumeration<? extends ZipEntry> en=zf.entries();while(en.hasMoreElements()){ZipEntry e=en.nextElement();String n=e.getName();total++;if("resources.arsc".equals(n))res++;else if(n.startsWith("assets/")){assets++;if(samples.length()<100)samples.put(n);}else if(n.startsWith("lib/")&&n.endsWith(".so")){nativeLibs++;if(samples.length()<100)samples.put(n);}else if(n.startsWith("META-INF/"))meta++;else if(n.startsWith("res/")&&n.endsWith(".xml"))xml++;else other++;}}finally{zf.close();}return new JSONObject().put("ok",true).put("path",apk.getCanonicalPath()).put("zipEntryCount",total).put("hasResourcesArsc",res>0).put("assetCount",assets).put("nativeLibCount",nativeLibs).put("metaInfCount",meta).put("resXmlCount",xml).put("otherCount",other).put("samples",samples);}
    private static File resolveLocalApk(String path)throws Exception{String p=path==null?"":path.trim();if(isSafPath(p))throw new IOException("当前 APK 解析器要求本地文件路径；请先复制 SAF 文件到模块工作目录");File f=safeLocalFile(p);if(!f.isFile())throw new FileNotFoundException("APK 不存在: "+p);if(!p.toLowerCase(Locale.ROOT).endsWith(".apk"))throw new IOException("不是 APK: "+p);return f.getCanonicalFile();}
    private static JSONObject projectGenerate(JSONObject a)throws Exception{String project=a.optString("project",ACTIVE_PROJECT),apk=a.optString("apk",ACTIVE_LOCAL_APK_PATH);if(project.isEmpty()||apk.isEmpty())throw new IOException("project/apk 未指定");File root=safeLocalFile(project);if(!root.isDirectory())throw new IOException("项目不存在: "+project);JSONObject man=apkManifest(new JSONObject().put("path",apk)),dex=apkDexIndex(new JSONObject().put("path",apk).put("maxClasses",20000)),ri=apkResourceIndex(new JSONObject().put("path",apk));writeProjectJson(project,"input/manifest.json",man.toString(2));writeProjectJson(project,"input/dex-index.json",dex.toString(2));writeProjectJson(project,"input/resource-index.json",ri.toString(2)); JSONObject sym=apkDexSymbols(new JSONObject().put("path",apk).put("maxResults",20000)); writeProjectJson(project,"input/dex-symbols.json",sym.toString(2)); JSONObject xref=apkDexXref(new JSONObject().put("path",apk).put("kind","all").put("maxResults",20000)); writeProjectJson(project,"input/dex-xref.json",xref.toString(2)); JSONObject ep=apkEntryPoints(new JSONObject().put("path",apk)); writeProjectJson(project,"input/entry-points.json",ep.toString(2)); JSONObject epm=apkEntryPointMethods(new JSONObject().put("path",apk)); writeProjectJson(project,"input/entry-point-methods.json",epm.toString(2)); JSONObject dec=apkManifestDecoded(new JSONObject().put("path",apk)); writeProjectJson(project,"input/manifest-decoded.json",dec.toString(2)); JSONObject rt=apkResourceTable(new JSONObject().put("path",apk)); writeProjectJson(project,"input/resource-table.json",rt.toString(2)); writeProjectText(project,"src/README.md","# Reverse project\n\nThis directory is generated from actual APK structures.\n\n- input/dex-index.json: class definitions\n- input/dex-symbols.json: fields/methods/class_data\n- input/dex-xref.json: code_item references and method call edges\n- input/manifest-decoded.json: decoded AXML\n- input/resource-table.json: resources.arsc chunks\n"); writeProjectText(project,"report/analysis-state.md","# Analysis State\n\n本项目由真实 APK ZIP/DEX 解析器生成；没有伪造 Java/Smali 源码。\n\n- Manifest: input/manifest.json\n- DEX index: input/dex-index.json\n- Resource index: input/resource-index.json\n");return new JSONObject().put("ok",true).put("project",root.getCanonicalPath()).put("artifacts",new JSONArray().put("input/manifest.json").put("input/manifest-decoded.json").put("input/dex-index.json").put("input/dex-symbols.json").put("input/dex-xref.json").put("input/resource-index.json").put("input/resource-table.json").put("src/README.md").put("report/analysis-state.md"));}

    private static byte[] readAll(InputStream in,int max) throws IOException { ByteArrayOutputStream b=new ByteArrayOutputStream(); byte[] buf=new byte[8192]; int total=0,n; while((n=in.read(buf))>0){int take=Math.min(n,max-total); if(take>0)b.write(buf,0,take); total+=take; if(total>=max)break;} try{in.close();}catch(Throwable ignored){} return b.toByteArray(); }

    private static boolean isSafPath(String raw) { return raw != null && (raw.startsWith("saf://") || raw.startsWith("saf:/")); }
    private static String safRelative(String raw) throws IOException {
        String p=raw==null?"":raw.trim();
        if(p.startsWith("saf://default/")) return p.substring("saf://default/".length());
        if(p.equals("saf://default")||p.equals("saf:/default")) return "";
        if(p.startsWith("saf:/")) return p.substring("saf:/".length());
        throw new IOException("只支持 saf://default/<relative> 工作区路径");
    }
    private static File safeLocalFile(String raw) throws IOException {
        if(raw==null||raw.trim().isEmpty())throw new IOException("path 不能为空"); String p=raw.trim();
        if(isSafPath(p) || p.startsWith("content://")) throw new IOException("这是 SAF 路径，请使用 SAF 存储后端");
        File f=new File(p.startsWith("/")?p:new File(MCP_ROOT,p).getPath()).getCanonicalFile();
        File root=new File(MCP_ROOT).getCanonicalFile(); File priv=applicationContext==null?null:applicationContext.getFilesDir().getCanonicalFile();
        String fp=f.getPath(), rp=root.getPath(); boolean ok=fp.equals(rp)||fp.startsWith(rp+File.separator)||(priv!=null&&(fp.equals(priv.getPath())||fp.startsWith(priv.getPath()+File.separator)));
        if(!ok) throw new IOException("path 超出允许工作根目录: "+p); return f;
    }
    private static JSONObject fsList(JSONObject a)throws Exception{
        String raw=a.optString("path","");
        if(isSafPath(raw)){ JSONArray x=SafStorage.list(applicationContext,safRelative(raw)); return new JSONObject().put("ok",true).put("backend","SAF").put("path",raw).put("items",x); }
        File d=safeLocalFile(raw);if(!d.isDirectory())throw new IOException("不是目录");JSONArray x=new JSONArray();File[] fs=d.listFiles();if(fs!=null)for(File f:fs)x.put(new JSONObject().put("name",f.getName()).put("directory",f.isDirectory()).put("sizeBytes",f.length()).put("path",f.getPath()));return new JSONObject().put("ok",true).put("backend","FILESYSTEM").put("path",d.getCanonicalPath()).put("items",x);
    }
    private static JSONObject fsRead(JSONObject a)throws Exception{
        String raw=a.optString("path","");int max=Math.max(1,Math.min(2*1024*1024,a.optInt("maxBytes",262144)));long off=Math.max(0,a.optLong("offset",0));byte[] b;
        if(isSafPath(raw)) b=SafStorage.read(applicationContext,safRelative(raw),max,off); else {File f=safeLocalFile(raw);RandomAccessFile r=new RandomAccessFile(f,"r");r.seek(Math.min(off,r.length()));b=new byte[Math.min(max,(int)Math.min(Integer.MAX_VALUE,r.length()-r.getFilePointer()))];int n=r.read(b);r.close();if(n<0)b=new byte[0];else if(n<b.length)b=Arrays.copyOf(b,n);}
        return new JSONObject().put("ok",true).put("backend",isSafPath(raw)?"SAF":"FILESYSTEM").put("path",raw).put("offset",off).put("bytesRead",b.length).put("text",new String(b,StandardCharsets.UTF_8));
    }
    private static JSONObject fsWrite(JSONObject a)throws Exception{
        String raw=a.optString("path","");byte[] b=a.optString("content","").getBytes(StandardCharsets.UTF_8);
        if(isSafPath(raw)){SafStorage.write(applicationContext,safRelative(raw),b);return new JSONObject().put("ok",true).put("backend","SAF").put("path",raw).put("bytes",b.length);}
        File f=safeLocalFile(raw);File par=f.getParentFile();if(par!=null&&!par.exists()&&!par.mkdirs())throw new IOException("无法创建父目录");FileOutputStream o=new FileOutputStream(f,false);o.write(b);o.close();return new JSONObject().put("ok",true).put("backend","FILESYSTEM").put("path",f.getCanonicalPath()).put("bytes",b.length);
    }
    private static JSONObject fsMkdir(JSONObject a)throws Exception{
        String raw=a.optString("path","");if(isSafPath(raw)){SafStorage.mkdir(applicationContext,safRelative(raw));return new JSONObject().put("ok",true).put("backend","SAF").put("path",raw).put("directory",true);}
        File d=safeLocalFile(raw);return new JSONObject().put("ok",d.exists()||d.mkdirs()).put("backend","FILESYSTEM").put("path",d.getCanonicalPath()).put("directory",d.isDirectory());
    }
    private static JSONObject fsCopy(JSONObject a)throws Exception{
        String src=a.optString("src","");String dst=a.optString("dst","");if(isSafPath(src)||isSafPath(dst)){if(!(isSafPath(src)&&isSafPath(dst)))throw new IOException("SAF copy 当前要求源和目标都在 SAF 工作区");SafStorage.copy(applicationContext,safRelative(src),safRelative(dst));return new JSONObject().put("ok",true).put("backend","SAF").put("src",src).put("dst",dst);}
        File s=safeLocalFile(src),d=safeLocalFile(dst);if(!s.isFile())throw new IOException("源文件不存在");File par=d.getParentFile();if(par!=null&&!par.exists())par.mkdirs();FileInputStream in=new FileInputStream(s);FileOutputStream out=new FileOutputStream(d,false);byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);in.close();out.close();return new JSONObject().put("ok",true).put("backend","FILESYSTEM").put("src",s.getCanonicalPath()).put("dst",d.getCanonicalPath()).put("bytes",d.length());
    }

    private static JSONObject storageStatus(JSONObject a) throws Exception {
        JSONObject o=new JSONObject(); o.put("ok",true).put("mounted",SafStorage.mounted(applicationContext)).put("status",SafStorage.status(applicationContext)).put("pathSyntax","saf://default/<relative>");
        return o;
    }
    private static synchronized JSONObject autonomousReverse(JSONObject a) throws Exception {
        String apk=a.optString("apk",ACTIVE_LOCAL_APK_PATH).trim();
        if(apk.isEmpty()) throw new IOException("apk 未指定；请提供 APK 路径或先完成 APK discovery");
        File source=resolveLocalApk(apk);
        String name=a.optString("name",source.getName().replaceAll("(?i)\\.apk$","")).trim();
        if(name.isEmpty()) name="apk-project";
        // A durable project is the source of truth. The worker can be recreated from project.json.
        JSONObject created=projectCreate(new JSONObject().put("name",name));
        String project=created.optString("project","");
        updateProjectSource(project,source.getCanonicalPath());
        try { File meta=new File(safeLocalFile(project),"project.json"); JSONObject pm=new JSONObject(new String(readAll(new FileInputStream(meta),131072),StandardCharsets.UTF_8)); pm.put("query",a.optString("query","")).put("decompile",a.optBoolean("decompile",true)).put("maxRounds",Math.max(1,Math.min(12,a.optInt("maxRounds",8)))); atomicWrite(meta,pm.toString(2).getBytes(StandardCharsets.UTF_8)); } catch(Throwable ignored) {}
        try { KeepAliveService.start(applicationContext); } catch(Throwable ignored) {}
        final String fp=source.getCanonicalPath(), pr=project, query=a.optString("query","");
        final boolean decompile=a.optBoolean("decompile",true);
        final int maxRounds=Math.max(1,Math.min(12,a.optInt("maxRounds",8)));
        updateProjectMeta(pr,"queued",1,"none","autonomous_reverse",new JSONArray());
        ACTIVE_PROJECT=pr; ACTIVE_PROJECT_TASK="queued"; ACTIVE_PROJECT_HEARTBEAT=System.currentTimeMillis();
        if(ACTIVE_PROJECT_FUTURE!=null&&!ACTIVE_PROJECT_FUTURE.isDone()) throw new IOException("已有项目任务运行中: "+ACTIVE_PROJECT);
        ACTIVE_PROJECT_FUTURE=PROJECT_EXECUTOR.submit(() -> runAutonomousPipeline(fp,pr,query,decompile,maxRounds));
        return new JSONObject().put("ok",true).put("started",true).put("mode","persistent-autonomous-reverse")
                .put("project",pr).put("taskId",created.optString("taskId","")).put("source_apk",fp)
                .put("next_step","autonomous_reverse").put("keepAliveRequested",applicationContext!=null);
    }

    private static void runAutonomousPipeline(String apk,String project,String query,boolean decompile,int maxRounds){
        String taskId="";
        try {
            JSONObject m=projectStatus(new JSONObject().put("project",project)); taskId=m.optString("taskId","");
            String last=m.optString("last_completed_step","none");
            File source=new File(apk);
            // 1) immutable source evidence
            if("none".equals(last)||"created".equals(last)){
                checkpointInterrupt(); setProjectStage(project,"source_verified",8,"overview");
                JSONObject overview=analyzeLocalApk(new JSONObject().put("path",apk).put("query",query));
                writeProjectJson(project,"input/overview.json",overview.toString(2));
                writeProjectJson(project,"input/source.json",new JSONObject().put("path",source.getCanonicalPath()).put("sizeBytes",source.length()).put("lastModified",source.lastModified()).toString(2));
                setProjectStage(project,"analyze_complete",18,"dex_index"); last="analyze";
            }
            if("analyze".equals(last)){
                checkpointInterrupt(); setProjectStage(project,"dex_indexing",28,"dex_index");
                JSONObject x=apkDexIndex(new JSONObject().put("path",apk).put("maxClasses",50000)); writeProjectJson(project,"input/dex-index.json",x.toString(2));
                setProjectStage(project,"dex_index_complete",38,"dex_symbols"); last="dex_index";
            }
            if("dex_index".equals(last)){
                checkpointInterrupt(); setProjectStage(project,"dex_symbols_indexing",45,"dex_symbols");
                JSONObject x=apkDexSymbols(new JSONObject().put("path",apk).put("maxResults",50000)); writeProjectJson(project,"input/dex-symbols.json",x.toString(2));
                setProjectStage(project,"dex_symbols_complete",52,"xref"); last="dex_symbols";
            }
            if("dex_symbols".equals(last)){
                checkpointInterrupt(); setProjectStage(project,"xref_indexing",58,"xref");
                JSONObject x=apkDexXref(new JSONObject().put("path",apk).put("kind","all").put("maxResults",50000)); writeProjectJson(project,"input/dex-xref.json",x.toString(2));
                JSONObject ep=apkEntryPoints(new JSONObject().put("path",apk)); writeProjectJson(project,"input/entry-points.json",ep.toString(2));
                JSONObject epm=apkEntryPointMethods(new JSONObject().put("path",apk)); writeProjectJson(project,"input/entry-point-methods.json",epm.toString(2));
                setProjectStage(project,"xref_complete",65,"manifest"); last="dex_xref";
            }
            if("dex_xref".equals(last)){
                checkpointInterrupt(); setProjectStage(project,"manifest_decoding",70,"manifest");
                JSONObject man=apkManifest(new JSONObject().put("path",apk)); JSONObject dec=apkManifestDecoded(new JSONObject().put("path",apk));
                writeProjectJson(project,"input/manifest.json",man.toString(2)); writeProjectJson(project,"input/manifest-decoded.json",dec.toString(2));
                setProjectStage(project,"manifest_complete",74,"resources"); last="manifest";
            }
            if("manifest".equals(last)){
                checkpointInterrupt(); setProjectStage(project,"resource_analysis",78,"resources");
                JSONObject ri=apkResourceIndex(new JSONObject().put("path",apk)); JSONObject rt=apkResourceTable(new JSONObject().put("path",apk)); JSONObject rr=apkResourceRefs(new JSONObject().put("path",apk));
                writeProjectJson(project,"input/resource-index.json",ri.toString(2)); writeProjectJson(project,"input/resource-table.json",rt.toString(2)); writeProjectJson(project,"input/resource-refs.json",rr.toString(2));
                setProjectStage(project,"resource_complete",82,"evidence"); last="resource_table";
            }
            if("resource_table".equals(last)||"resource_index".equals(last)){
                checkpointInterrupt(); setProjectStage(project,"evidence_analysis",85,"evidence");
                JSONObject ev=ReverseAnalysisEngine.analyze(source,2000,1000); writeProjectJson(project,"input/evidence.json",ev.toString(2));
                setProjectStage(project,"evidence_complete",89,"decompile"); last="evidence";
            }
            if("evidence".equals(last)){
                checkpointInterrupt(); setProjectStage(project,"decompile",90,"decompile");
                JSONObject dec=new JSONObject().put("attempted",false).put("available",false);
                if(decompile){
                    try {
                        String h=""; JSONObject op=apkOpenHandle(new JSONObject().put("locator",apk).put("temporary",true)); h=op.optString("handle","");
                        if(!h.isEmpty()) { dec=apkDecompileHandle(new JSONObject().put("handle",h).put("output",new File(safeLocalFile(project),"output/decompiled").getCanonicalPath())); apkCloseHandle(new JSONObject().put("handle",h)); }
                    } catch(Throwable e){ dec.put("attempted",true).put("ok",false).put("error",String.valueOf(e)); }
                }
                writeProjectJson(project,"input/decompile.json",dec.toString(2)); setProjectStage(project,"decompile_complete",93,"report"); last="decompile";
            }
            if("decompile".equals(last)){
                checkpointInterrupt(); setProjectStage(project,"reporting",96,"done");
                JSONObject ov=projectJson(project,"input/overview.json"), dex=projectJson(project,"input/dex-index.json"), ev=projectJson(project,"input/evidence.json"), dec=projectJson(project,"input/decompile.json");
                StringBuilder md=new StringBuilder(); md.append("# 自动逆向分析报告\\n\\n");
                md.append("> Evidence-first：本报告只引用实际读取/执行得到的数据，不把推测当作事实。\\n\\n");
                md.append("## Source\\n\\n- APK: `").append(apk).append("`\\n- SHA-256: `").append(ev.optString("sha256", "unknown")).append("`\\n- Size: ").append(source.length()).append(" bytes\\n\\n");
                md.append("## Package\\n\\n- Package: `").append(ov.optString("packageName","unknown")).append("`\\n- Version: `").append(ov.optString("versionName","")).append("` / ").append(ov.optLong("versionCode",0)).append("\\n\\n");
                md.append("## DEX\\n\\n- Count: ").append(dex.optInt("dexCount",0)).append("\\n- Classes: ").append(dex.optInt("classCount",0)).append("\\n\\n");
                md.append("## Network evidence\\n\\n- URLs: ").append(ev.optJSONArray("urls")==null?0:ev.optJSONArray("urls").length()).append("\\n- Domains: ").append(ev.optJSONArray("domains")==null?0:ev.optJSONArray("domains").length()).append("\\n\\n");
                md.append("## Security indicators\\n\\n- Crypto indicators: ").append(ev.optJSONArray("cryptoIndicators")==null?0:ev.optJSONArray("cryptoIndicators").length()).append("\\n- Interesting indicators: ").append(ev.optJSONArray("interestingIndicators")==null?0:ev.optJSONArray("interestingIndicators").length()).append("\\n\\n");
                md.append("## Decompile\\n\\n- Attempted: ").append(dec.optBoolean("attempted",false)).append("\\n- OK: ").append(dec.optBoolean("ok",false)).append("\\n- Tool: `").append(dec.optString("tool","")).append("`\\n- Output: `").append(dec.optString("output","")).append("`\\n\\n");
                md.append("## Evidence files\\n\\n- `input/overview.json`\\n- `input/dex-index.json`\\n- `input/dex-symbols.json`\\n- `input/dex-xref.json`\\n- `input/entry-points.json`\\n- `input/entry-point-methods.json`\\n- `input/manifest.json`\\n- `input/manifest-decoded.json`\\n- `input/resource-index.json`\\n- `input/resource-table.json`\\n- `input/resource-refs.json`\\n- `input/evidence.json`\\n- `input/decompile.json`\\n");
                writeProjectText(project,"report/summary.md",md.toString());
                writeProjectText(project,"report/findings.json",ev.toString(2));
                setProjectStage(project,"completed",100,"done");
            }
        } catch(InterruptedException e){ try{setProjectStage(project,"paused",Math.max(1,projectStatus(new JSONObject().put("project",project)).optInt("progress",0)),"resume");updateProjectError(project,"Worker interrupted; checkpoint preserved. Use project_resume.");}catch(Throwable ignored){} Thread.currentThread().interrupt(); }
          catch(Throwable e){ try{int p=projectStatus(new JSONObject().put("project",project)).optInt("progress",1);setProjectStage(project,"paused",Math.max(1,p),"resume");updateProjectError(project,"Recoverable failure: "+e);}catch(Throwable ignored){} }
    }

    private static synchronized JSONObject projectRun(JSONObject a) throws Exception {
        if(ACTIVE_PROJECT_FUTURE!=null && !ACTIVE_PROJECT_FUTURE.isDone()) throw new IOException("已有项目任务运行中: "+ACTIVE_PROJECT);
        String apk=a.optString("apk",ACTIVE_LOCAL_APK_PATH).trim(); String name=a.optString("name","").trim();
        if(apk.isEmpty()) throw new IOException("apk 未指定，也没有可恢复的本地 APK 上下文");
        if(name.isEmpty()){File f=new File(apk);name=f.getName().replaceAll("(?i)\\.apk$","");if(name.isEmpty())name="apk-project";}
        JSONObject created=projectCreate(new JSONObject().put("name",name)); String project=created.optString("project","");
        updateProjectSource(project, apk);
        final String fp=apk, pr=project;
        ACTIVE_PROJECT=project; ACTIVE_PROJECT_TASK="queued"; ACTIVE_PROJECT_HEARTBEAT=System.currentTimeMillis();
        updateProjectMeta(pr,"queued",5,"none","analyze",new JSONArray());
        try { if(applicationContext!=null) KeepAliveService.start(applicationContext); } catch(Throwable ignored) {}
        ACTIVE_PROJECT_FUTURE=PROJECT_EXECUTOR.submit(() -> runProjectPipeline(fp,pr));
        return new JSONObject().put("ok",true).put("started",true).put("project",project).put("task","analyze_apk").put("heartbeat",ACTIVE_PROJECT_HEARTBEAT);
    }
    private static void runProjectPipeline(String apk,String project){
        try{
            JSONObject meta=projectStatus(new JSONObject().put("project",project));
            String last=meta.optString("last_completed_step","none");
            if("none".equals(last) || "created".equals(last)){
                checkpointInterrupt(); setProjectStage(project,"analyzing",15,"analyze");
                JSONObject overview=analyzeLocalApk(new JSONObject().put("path",apk).put("query",""));
                if(!overview.optBoolean("ok",false)) throw new IOException(overview.optString("error","APK analysis failed"));
                writeProjectJson(project,"input/overview.json",overview.toString(2));
                setProjectStage(project,"analyze_complete",22,"dex_index"); last="analyze";
            }
            if("analyze".equals(last)){
                checkpointInterrupt(); setProjectStage(project,"dex_indexing",30,"dex_index");
                JSONObject dex=apkDexIndex(new JSONObject().put("path",apk).put("maxClasses",30000));
                writeProjectJson(project,"input/dex-index.json",dex.toString(2));
                setProjectStage(project,"dex_index_complete",42,"dex_symbols"); last="dex_index";
            }
            if("dex_index".equals(last)){
                checkpointInterrupt(); setProjectStage(project,"dex_symbol_indexing",48,"dex_symbols");
                JSONObject sym=apkDexSymbols(new JSONObject().put("path",apk).put("maxResults",30000));
                writeProjectJson(project,"input/dex-symbols.json",sym.toString(2));
                setProjectStage(project,"dex_symbols_complete",58,"dex_xref"); last="dex_symbols";
            }
            if("dex_symbols".equals(last)){
                checkpointInterrupt(); setProjectStage(project,"dex_xref_indexing",64,"dex_xref");
                JSONObject xr=apkDexXref(new JSONObject().put("path",apk).put("kind","all").put("maxResults",30000)); JSONObject ep=apkEntryPoints(new JSONObject().put("path",apk)); writeProjectJson(project,"input/entry-points.json",ep.toString(2)); checkpointInterrupt(); JSONObject epm=apkEntryPointMethods(new JSONObject().put("path",apk)); writeProjectJson(project,"input/entry-point-methods.json",epm.toString(2));
                writeProjectJson(project,"input/dex-xref.json",xr.toString(2));
                JSONObject codeCaps=new JSONObject().put("ok",true).put("tool","apk_method_semantics").put("source","classes*.dex").put("note","方法级语义按需解析，不在项目任务中伪造全量反编译源码");
                writeProjectJson(project,"input/dex-code-capabilities.json",codeCaps.toString(2));
                setProjectStage(project,"dex_xref_complete",70,"manifest"); last="dex_xref";
            }
            if("dex_xref".equals(last)){
                checkpointInterrupt(); setProjectStage(project,"manifest_decoding",74,"manifest");
                JSONObject man=apkManifest(new JSONObject().put("path",apk));
                JSONObject dec=apkManifestDecoded(new JSONObject().put("path",apk));
                writeProjectJson(project,"input/manifest.json",man.toString(2));
                writeProjectJson(project,"input/manifest-decoded.json",dec.toString(2));
                setProjectStage(project,"manifest_complete",78,"resource_index"); last="manifest";
            }
            if("manifest".equals(last)){
                checkpointInterrupt(); setProjectStage(project,"resource_indexing",82,"resource_table");
                JSONObject ri=apkResourceIndex(new JSONObject().put("path",apk));
                JSONObject rt=apkResourceTable(new JSONObject().put("path",apk));
                JSONObject rr=apkResourceRefs(new JSONObject().put("path",apk));
                writeProjectJson(project,"input/resource-index.json",ri.toString(2));
                writeProjectJson(project,"input/resource-table.json",rt.toString(2));
                writeProjectJson(project,"input/resource-refs.json",rr.toString(2));
                setProjectStage(project,"resource_complete",90,"report"); last="resource_table";
            }
            if("resource_table".equals(last) || "resource_index".equals(last)){
                checkpointInterrupt(); setProjectStage(project,"reporting",94,"done");
                JSONObject overview=projectJson(project,"input/overview.json"), dex=projectJson(project,"input/dex-index.json"), sym=projectJson(project,"input/dex-symbols.json"), xr=projectJson(project,"input/dex-xref.json"), man=projectJson(project,"input/manifest.json"), ri=projectJson(project,"input/resource-index.json"), rr=projectJson(project,"input/resource-refs.json");
                StringBuilder md=new StringBuilder();
                md.append("# APK 逆向分析项目\n\n");
                md.append("此报告由真实 APK ZIP、AXML、DEX 和 resources.arsc 解析结果生成，不生成虚假的 Java/Smali 源码。\n\n");
                md.append("## Package\n\n- APK: `").append(apk).append("`\n- 包名: `").append(overview.optString("packageName","未知")).append("`\n- 版本: `").append(overview.optString("versionName","")).append("` / ").append(overview.optLong("versionCode",0)).append("\n\n");
                md.append("## Manifest\n\n- XML: `").append(man.optString("format","unknown")).append("`\n- Activity: ").append(man.optInt("activityCount",0)).append("\n- Service: ").append(man.optInt("serviceCount",0)).append("\n- Provider: ").append(man.optInt("providerCount",0)).append("\n- Receiver: ").append(man.optInt("receiverCount",0)).append("\n\n");
                md.append("## DEX\n\n- 文件数: ").append(dex.optInt("dexCount",0)).append("\n- 类: ").append(dex.optInt("classCount",0)).append("\n- 已索引方法: ").append(sym.optJSONArray("methods")==null?0:sym.optJSONArray("methods").length()).append("\n- 已索引字段: ").append(sym.optJSONArray("fields")==null?0:sym.optJSONArray("fields").length()).append("\n\n");
                md.append("## Xref / Code Semantics\n\n- 引用命中: ").append(xr.optJSONArray("hits")==null?0:xr.optJSONArray("hits").length()).append("\n- 方法调用边: ").append(xr.optJSONArray("edges")==null?0:xr.optJSONArray("edges").length()).append("\n- 继承关系: ").append(xr.optJSONArray("inheritance")==null?0:xr.optJSONArray("inheritance").length()).append("\n- 方法级语义: `apk_method_semantics` 按需读取真实 code_item\n\n");
                md.append("## Resources\n\n- resources.arsc: ").append(ri.optBoolean("hasResourcesArsc",false)).append("\n- native libs: ").append(ri.optInt("nativeLibCount",0)).append("\n- assets: ").append(ri.optInt("assetCount",0)).append("\n- META-INF: ").append(ri.optInt("metaInfCount",0)).append("\n- XML resource references: ").append(rr.optJSONArray("references")==null?0:rr.optJSONArray("references").length()).append("\n\n");
                md.append("## 可继续分析\n\n- `apk_dex_symbols`：按类/关键字定位真实方法和字段。\n- `apk_dex_xref`：反查真实字符串、类型、方法引用。\n- `apk_call_path`：在真实方法调用边上做有界路径追踪。\n- `project_trace`：直接使用已保存的 `input/dex-xref.json` 继续追踪。\n- `apk_manifest_decoded`：查看真实 AXML 标签和属性。\n- `apk_resource_table`：查看 resources.arsc chunk。\n- `apk_resource_refs`：查看 Manifest/res XML 中真实资源 ID 引用。\n- `agent_analyze_apk`：执行有界只读连续分析链。\n- `project_search`：在生成项目中继续定位关键字。\n");
                writeProjectText(project,"report/summary.md",md.toString());
                writeProjectText(project,"src/README.md","# Reverse Project\n\n真实结构索引：\n\n- `input/dex-index.json`\n- `input/dex-symbols.json`\n- `input/manifest-decoded.json`\n- `input/resource-index.json`\n- `input/resource-table.json`\n\n本目录不声称包含尚未实际反编译得到的 Java/Smali 源码。\n");
                setProjectStage(project,"completed",100,"done");
            }
            log("[PROJECT] completed project="+project);
        }catch(InterruptedException e){
            try{setProjectStage(project,"cancelled",Math.max(0,projectStatus(new JSONObject().put("project",project)).optInt("progress",0)),"resume");updateProjectError(project,"任务被中断，可使用 project_resume 恢复");}catch(Throwable ignored){} Thread.currentThread().interrupt();
        }catch(Throwable e){
            try{int pr=projectStatus(new JSONObject().put("project",project)).optInt("progress",1);setProjectStage(project,"failed",Math.max(1,pr),"resume");updateProjectError(project,String.valueOf(e));}catch(Throwable ignored){} log("[PROJECT] failed "+project+" "+e);
        }
    }
    private static JSONObject projectJson(String project,String rel)throws Exception{File f=new File(safeLocalFile(project),rel);return new JSONObject(new String(readAll(new FileInputStream(f),2*1024*1024),StandardCharsets.UTF_8));}

    private static void checkpointInterrupt() throws InterruptedException { if(Thread.currentThread().isInterrupted()) throw new InterruptedException("项目任务已取消"); }
    private static void setProjectStage(String project,String stage,int progress,String next) throws Exception {
        ACTIVE_PROJECT=project; ACTIVE_PROJECT_TASK=stage; ACTIVE_PROJECT_HEARTBEAT=System.currentTimeMillis();
        JSONObject current=projectStatus(new JSONObject().put("project",project));
        String last=current.optString("last_completed_step","none");
        if("analyze_complete".equals(stage)) last="analyze";
        else if("dex_index_complete".equals(stage)) last="dex_index";
        else if("dex_symbols_complete".equals(stage)) last="dex_symbols";
        else if("dex_xref_complete".equals(stage)||"xref_complete".equals(stage)) last="dex_xref";
        else if("manifest_complete".equals(stage)) last="manifest";
        else if("resource_complete".equals(stage)) last="resource_table";
        else if("resource_index_complete".equals(stage)) last="resource_index";
        else if("evidence_complete".equals(stage)) last="evidence";
        else if("decompile_complete".equals(stage)) last="decompile";
        else if("completed".equals(stage)) last="report";
        else if("cancelled".equals(stage)||"failed".equals(stage)) last=current.optString("last_completed_step","none");
        updateProjectMeta(project,stage,progress,last,next,new JSONArray());
    }
    private static void updateProjectMeta(String project,String status,int progress,String last,String next,JSONArray artifacts)throws Exception{
        File p=safeLocalFile(project),m=new File(p,"project.json");JSONObject o=m.isFile()?new JSONObject(new String(readAll(new FileInputStream(m),131072),StandardCharsets.UTF_8)):new JSONObject();JSONArray old=o.optJSONArray("artifacts");if(artifacts==null||artifacts.length()==0)artifacts=old==null?new JSONArray():old;o.put("status",status).put("progress",progress).put("last_completed_step",last).put("next_step",next).put("heartbeat",System.currentTimeMillis()).put("artifacts",artifacts);atomicWrite(m,o.toString(2).getBytes(StandardCharsets.UTF_8));
        File lock=new File(p,"project.lock");JSONObject l=new JSONObject().put("taskId",o.optString("taskId","yb-project")).put("heartbeat",System.currentTimeMillis()).put("stage",status).put("progress",progress).put("owner",android.os.Process.myPid());atomicWrite(lock,l.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    private static void updateProjectArtifacts(String project,JSONArray artifacts)throws Exception{File m=new File(safeLocalFile(project),"project.json");JSONObject o=new JSONObject(new String(readAll(new FileInputStream(m),131072),StandardCharsets.UTF_8));o.put("artifacts",artifacts);FileOutputStream out=new FileOutputStream(m,false);out.write(o.toString(2).getBytes(StandardCharsets.UTF_8));out.close();}
    private static void updateProjectError(String project,String err)throws Exception{File m=new File(safeLocalFile(project),"project.json");JSONObject o=new JSONObject(new String(readAll(new FileInputStream(m),131072),StandardCharsets.UTF_8));o.put("error",err).put("heartbeat",System.currentTimeMillis());FileOutputStream out=new FileOutputStream(m,false);out.write(o.toString(2).getBytes(StandardCharsets.UTF_8));out.close();}
    private static void writeProjectText(String project,String rel,String text)throws Exception{projectWrite(new JSONObject().put("project",project).put("path",rel).put("content",text));}
    private static void writeProjectJson(String project,String rel,String text)throws Exception{writeProjectText(project,rel,text);}
    private static synchronized JSONObject projectCancel(JSONObject a)throws Exception{if(ACTIVE_PROJECT_FUTURE==null||ACTIVE_PROJECT_FUTURE.isDone())return new JSONObject().put("ok",false).put("cancelled",false).put("reason","没有运行中的任务");ACTIVE_PROJECT_FUTURE.cancel(true);ACTIVE_PROJECT_FUTURE=null;String p=a.optString("project",ACTIVE_PROJECT);if(!p.isEmpty()){setProjectStage(p,"cancelled",Math.max(0,projectStatus(new JSONObject().put("project",p)).optInt("progress",0)),"resume");updateProjectError(p,"用户取消任务；可使用 project_resume 恢复");}return new JSONObject().put("ok",true).put("cancelled",true).put("project",p);}

    private static void copyFile(File src,File dst)throws Exception{FileInputStream in=new FileInputStream(src);FileOutputStream out=new FileOutputStream(dst,false);byte[] buf=new byte[8192];int n;try{while((n=in.read(buf))>0)out.write(buf,0,n);out.flush();}finally{try{in.close();}catch(Throwable ignored){}try{out.close();}catch(Throwable ignored){}}}
    private static JSONObject projectWrite(JSONObject a)throws Exception{String project=a.optString("project",ACTIVE_PROJECT);if(project.isEmpty())throw new IOException("project 未指定");File root=safeLocalFile(project);String relPath=a.optString("path","").trim();validateRelativeMcpPath(relPath);File target=new File(root,relPath).getCanonicalFile();String rp=root.getCanonicalPath();if(!target.getCanonicalPath().startsWith(rp+File.separator))throw new IOException("project path 越界");File par=target.getParentFile();if(par!=null&&!par.exists()&&!par.mkdirs())throw new IOException("无法创建父目录");byte[] b=a.optString("content","").getBytes(StandardCharsets.UTF_8);File backupDir=new File(root,"backup");if(target.exists()&&target.isFile()){String safe=relPath.replace("/","__");File backup=new File(backupDir,safe+"."+System.currentTimeMillis()+".bak");backupDir.mkdirs();copyFile(target,backup);}atomicWrite(target,b);ACTIVE_PROJECT=root.getCanonicalPath();ACTIVE_PROJECT_TASK="file_written";ACTIVE_PROJECT_HEARTBEAT=System.currentTimeMillis();return new JSONObject().put("ok",true).put("project",root.getCanonicalPath()).put("path",target.getCanonicalPath()).put("bytes",b.length).put("heartbeat",ACTIVE_PROJECT_HEARTBEAT).put("backupEnabled",true);}
    private static synchronized JSONObject projectCreate(JSONObject a)throws Exception{
        String name=a.optString("name","").trim();
        if(name.isEmpty()||name.contains("/")||name.contains("\\")) throw new IOException("project name 非法");
        String root=a.optString("root",MCP_ROOT+"/projects");
        File base=safeLocalFile(root); if(!base.exists()&&!base.mkdirs()) throw new IOException("无法创建项目根目录");
        File p=new File(base,name).getCanonicalFile();
        String bp=base.getCanonicalPath(); if(!p.getPath().startsWith(bp+File.separator)) throw new IOException("project 越界");
        File lock=new File(p,"project.lock");
        if(p.exists() && new File(p,"project.json").isFile() && lock.isFile()) {
            try {
                JSONObject lk=new JSONObject(new String(readAll(new FileInputStream(lock),16384),StandardCharsets.UTF_8));
                long hb=lk.optLong("heartbeat",0L); String status=lk.optString("stage","");
                boolean active=hb>0 && System.currentTimeMillis()-hb < PROJECT_LOCK_STALE_MS &&
                        !"completed".equals(status) && !"cancelled".equals(status) && !"failed".equals(status);
                if(active) throw new IOException("项目已被任务锁占用: "+p.getCanonicalPath()+" taskId="+lk.optString("taskId",""));
            } catch (IOException e) { throw e; } catch(Throwable ignored) {}
        }
        if(!p.exists()&&!p.mkdirs()) throw new IOException("无法创建项目");
        for(String n:new String[]{"input","output","report","src","backup"}) if(!new File(p,n).exists()&&!new File(p,n).mkdirs()) throw new IOException("无法创建目录: "+n);
        String taskId="yb-project-"+UUID.randomUUID(); long now=System.currentTimeMillis();
        JSONObject lk=new JSONObject().put("taskId",taskId).put("startedAt",now).put("heartbeat",now).put("stage","created").put("progress",0).put("owner",android.os.Process.myPid());
        atomicWrite(lock,lk.toString(2).getBytes(StandardCharsets.UTF_8));
        File meta=new File(p,"project.json");
        JSONObject m=new JSONObject().put("name",name).put("root",p.getCanonicalPath()).put("taskId",taskId).put("status","created").put("progress",0).put("last_completed_step","none").put("next_step","analyze").put("artifacts",new JSONArray()).put("lock",lock.getCanonicalPath()).put("createdAt",now).put("heartbeat",now);
        atomicWrite(meta,m.toString(2).getBytes(StandardCharsets.UTF_8));
        ACTIVE_PROJECT=p.getCanonicalPath(); ACTIVE_PROJECT_TASK="created"; ACTIVE_PROJECT_HEARTBEAT=now;
        return new JSONObject().put("ok",true).put("project",p.getCanonicalPath()).put("metadata",m).put("taskId",taskId);
    }

    private static void atomicWrite(File target, byte[] data)throws Exception{
        File parent=target.getParentFile(); if(parent!=null&&!parent.exists())parent.mkdirs();
        File tmp=new File(parent,target.getName()+".tmp-"+UUID.randomUUID());
        FileOutputStream out=new FileOutputStream(tmp,false); try{out.write(data);out.flush();}finally{try{out.close();}catch(Throwable ignored){}}
        if(!tmp.renameTo(target)){FileOutputStream q=new FileOutputStream(target,false);try{q.write(data);q.flush();}finally{try{q.close();}catch(Throwable ignored){}}tmp.delete();}
    }

    private static void updateProjectSource(String project,String apk)throws Exception{File m=new File(safeLocalFile(project),"project.json");JSONObject o=new JSONObject(new String(readAll(new FileInputStream(m),131072),StandardCharsets.UTF_8));o.put("source_apk",new File(apk).getCanonicalPath()).put("taskId",o.optString("taskId","yb-project-"+System.currentTimeMillis()));FileOutputStream out=new FileOutputStream(m,false);out.write(o.toString(2).getBytes(StandardCharsets.UTF_8));out.close();}
    // ------------------------------------------------------------------ Shizuku-backed FS tools

    /** Uniform "the module process (the only Shizuku holder) is unreachable" answer. */
    private static JSONObject bridgeUnreachable(String tool) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false).put("available", false);
            o.put("stage", "MODULE_BRIDGE_UNREACHABLE");
            o.put("tool", tool);
            o.put("error", "无法连接模块进程。Shizuku 授权只能由模块自身 UID 持有，宿主（元宝）进程无法直接调用 Shizuku，"
                    + "所以全部能力都要经模块桥接执行；两条通道都没能打通。");
            o.put("providerError", shorten(BRIDGE_PROVIDER_ERROR, 300));
            o.put("socketError", shorten(BRIDGE_SOCKET_ERROR, 300));
            o.put("bridge", bridgeDiagnostics(false));
            o.put("nextStep", "打开一次“元宝本地 Agent 网关”（会启动桥接前台服务并写入握手文件），"
                    + "确认其通知常驻，然后重试；必要时调用 shizuku_request 触发授权。");
        } catch (Throwable ignored) {}
        return o;
    }

    /** Combined view: module all-files access, Shizuku state and real readability of key roots. */
    private static JSONObject androidStorageStatus()throws Exception{
        JSONObject mod = moduleCall("storage_status", null);
        if (mod == null) return bridgeUnreachable("android_storage_status");
        try {
            mod.put("hostProcessNote", "宿主进程是元宝进程，不受模块权限影响；文件访问统一经 Shizuku shell（uid 2000）执行。");
            mod.put("recommendedTool", "android_fs_list / android_fs_read / android_fs_write");
            mod.put("bridgeTransport", BRIDGE_TRANSPORT);
            mod.put("bridgeHandshakeFile", bridgeHandshakePath());
            return mod;
        } catch (Throwable t) { return mod; }
    }

    private static JSONObject shizukuRequest(JSONObject a)throws Exception{
        JSONObject r = moduleCall("shizuku_request", null);
        JSONObject o = new JSONObject();
        if (r == null) {
            return bridgeUnreachable("shizuku_request");
        }
        o.put("ok", r.optBoolean("ok", false));
        o.put("launched", r.optBoolean("launched", false));
        o.put("note", r.optString("note", ""));
        o.put("error", r.optString("error", ""));
        o.put("manualSteps", new JSONArray()
                .put("1) 打开“元宝本地 Agent 网关”应用")
                .put("2) 点击“请求 Shizuku 授权”，在 Shizuku 弹窗中允许")
                .put("3) 回到元宝再次调用 android_storage_status 确认 stage=READY"));
        return o;
    }

    private static JSONObject androidFsList(JSONObject a)throws Exception{
        String path = a.optString("path", "").trim();
        if (path.isEmpty()) path = "/sdcard";
        Bundle b = new Bundle();
        b.putString("path", path);
        b.putBoolean("recursive", a.optBoolean("recursive", true));
        b.putInt("maxDepth", Math.max(1, Math.min(12, a.optInt("maxDepth", 3))));
        b.putInt("limit", Math.max(1, Math.min(2000, a.optInt("limit", 200))));
        b.putString("glob", a.optString("glob", ""));
        JSONObject r = moduleCall("fs_list", b);
        if (r == null) return bridgeUnreachable("android_fs_list");
        return r;
    }

    private static JSONObject androidFsStat(JSONObject a)throws Exception{
        String path = a.optString("path", "").trim();
        if (path.isEmpty()) throw new IOException("path 不能为空");
        Bundle b = new Bundle();
        b.putString("path", path);
        JSONObject r = moduleCall("fs_stat", b);
        if (r == null) return bridgeUnreachable("android_fs_stat");
        return r;
    }

    private static JSONObject androidFsRead(JSONObject a)throws Exception{
        String path = a.optString("path", "").trim();
        if (path.isEmpty()) throw new IOException("path 不能为空");
        long offset = Math.max(0L, a.optLong("offset", 0L));
        int length = Math.max(1, Math.min(256 * 1024, a.optInt("length", 256 * 1024)));
        Bundle b = new Bundle();
        b.putString("path", path);
        b.putLong("offset", offset);
        b.putInt("length", length);
        JSONObject r = moduleCall("fs_read", b);
        if (r == null) return bridgeUnreachable("android_fs_read");
        if (r.optBoolean("ok", false) && a.optBoolean("asText", false)) {
            try {
                byte[] data = android.util.Base64.decode(r.optString("base64", ""), android.util.Base64.DEFAULT);
                String text = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                boolean binary = text.indexOf('\u0000') >= 0;
                r.put("text", text);
                r.put("textIsBinary", binary);
            } catch (Throwable t) {
                r.put("textDecodeError", String.valueOf(t));
            }
        }
        r.put("chunkHint", "分片读取：下次调用请传 offset=" + (offset + r.optInt("length", 0)));
        return r;
    }

    private static JSONObject androidFsWrite(JSONObject a)throws Exception{
        String path = a.optString("path", "").trim();
        if (path.isEmpty()) throw new IOException("path 不能为空");
        String b64 = a.optString("base64", "");
        if (b64.isEmpty()) {
            String content = a.optString("content", "");
            b64 = android.util.Base64.encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        }
        Bundle b = new Bundle();
        b.putString("path", path);
        b.putString("base64", b64);
        b.putBoolean("append", a.optBoolean("append", false));
        b.putBoolean("mkdirs", a.optBoolean("mkdirs", true));
        JSONObject r = moduleCall("fs_write", b);
        if (r == null) return bridgeUnreachable("android_fs_write");
        return r;
    }

    private static JSONObject androidFsMkdir(JSONObject a)throws Exception{
        String path = a.optString("path", "").trim();
        if (path.isEmpty()) throw new IOException("path 不能为空");
        Bundle b = new Bundle();
        b.putString("path", path);
        JSONObject r = moduleCall("fs_mkdir", b);
        if (r == null) return bridgeUnreachable("android_fs_mkdir");
        return r;
    }

    private static JSONObject androidFsDelete(JSONObject a)throws Exception{
        String path = a.optString("path", "").trim();
        if (path.isEmpty()) throw new IOException("path 不能为空");
        Bundle b = new Bundle();
        b.putString("path", path);
        b.putBoolean("recursive", a.optBoolean("recursive", false));
        JSONObject r = moduleCall("fs_delete", b);
        if (r == null) return bridgeUnreachable("android_fs_delete");
        return r;
    }

    /**
     * Shizuku status. The injected host process can never hold the grant, so the module
     * process's answer is authoritative and is fetched over the bridge. The host-side probe is
     * still reported so a real misconfiguration stays visible instead of being hidden.
     */
    private static JSONObject shizukuStatus(){
        JSONObject host=new JSONObject();
        try{
            Class<?> c=Class.forName("rikka.shizuku.Shizuku");
            boolean binder=false, granted=false; int uid=-1, version=-1;
            try{ Object r=c.getMethod("getBinder").invoke(null); binder=r!=null && ((android.os.IBinder)r).isBinderAlive(); }catch(Throwable ignored){}
            try{ granted=((Integer)c.getMethod("checkSelfPermission").invoke(null))==android.content.pm.PackageManager.PERMISSION_GRANTED; }catch(Throwable ignored){}
            try{ uid=((Integer)c.getMethod("getUid").invoke(null)); }catch(Throwable ignored){}
            try{ version=((Integer)c.getMethod("getVersion").invoke(null)); }catch(Throwable ignored){}
            host.put("ok",true).put("apiPresent",true).put("binder",binder).put("permissionGranted",granted).put("uid",uid).put("version",version);
        }catch(Throwable e){
            try{ host.put("ok",true).put("apiPresent",false).put("binder",false).put("permissionGranted",false)
                    .put("reason","Shizuku API 不在宿主（元宝）进程 ClassLoader 中；这是预期行为，权威结果来自模块进程"); }catch(Throwable ignored){}
        }
        JSONObject o=new JSONObject();
        try{
            o.put("ok",true);
            o.put("hostProcess",host);
            JSONObject mod=moduleCall("shizuku_status",null);
            if(mod==null){
                return o.put("available",false).put("binder",false).put("permissionGranted",false)
                        .put("authoritative","module-process")
                        .put("stage","MODULE_BRIDGE_UNREACHABLE")
                        .put("reason","无法连接模块进程（provider=" + shorten(BRIDGE_PROVIDER_ERROR, 200)
                                + " / socket=" + shorten(BRIDGE_SOCKET_ERROR, 200) + "）。"
                                + "请打开一次“元宝本地 Agent 网关”让桥接服务启动，并保持其通知常驻。")
                        .put("bridge", bridgeDiagnostics(false));
            }
            o.put("moduleProcess",mod);
            o.put("authoritative","module-process");
            o.put("available",mod.optBoolean("available",false));
            o.put("binder",mod.optBoolean("binder",false));
            o.put("permissionGranted",mod.optBoolean("permissionGranted",false));
            o.put("stage",mod.optString("stage",""));
            o.put("reason",mod.optString("reason",""));
            o.put("managerPackage",mod.optString("managerPackage",""));
            o.put("managerVersion",mod.optString("managerVersion",""));
            o.put("serverUid",mod.optInt("serverUid",-1));
            o.put("moduleAllFilesAccess",mod.optBoolean("moduleAllFilesAccess",false));
            o.put("nextStep","若 stage=PERMISSION_DENIED：打开“元宝本地 Agent 网关”并点击“请求 Shizuku 授权”。");
            o.put("bridgeTransport", BRIDGE_TRANSPORT);
            o.put("bridgeHandshakeFile", bridgeHandshakePath());
            return o;
        }catch(Throwable e){
            try{ return o.put("ok",false).put("error","shizuku_status 失败: "+e); }catch(Throwable ignored){ return o; }
        }
    }
    private static JSONObject shellExec(JSONObject a)throws Exception{
        String cmd=a.optString("command","").trim(); if(cmd.isEmpty())throw new IOException("command 不能为空"); int timeout=Math.max(1000,Math.min(600000,a.optInt("timeoutMs",30000))); String cwd=a.optString("cwd","").trim();
        // The injected YuanBao process can never hold the Shizuku grant, so the module process
        // is the authoritative executor. Bridge first; the local reflection path stays as a
        // fallback for environments where the host process itself somehow has Shizuku.
        if (!a.optBoolean("localOnly", false)) {
            JSONObject bridged = shellExecViaModule(cmd, timeout, cwd);
            if (bridged != null && bridged.optBoolean("ok", false)) { bridged.put("executor", "module-process"); return bridged; }
            JSONObject local = shellExecLocal(cmd, timeout, cwd);
            if (local.optBoolean("ok", false)) { local.put("executor", "host-process"); return local; }
            if (bridged != null) {
                bridged.put("executor", "module-process").put("hostAttempt", local);
                return bridged;
            }
            local.put("executor", "host-process");
            return local;
        }
        JSONObject local = shellExecLocal(cmd, timeout, cwd);
        local.put("executor", "host-process");
        return local;
    }

    /** Run a shell command through the module process (which holds the Shizuku grant). */
    private static JSONObject shellExecViaModule(String cmd, int timeout, String cwd) {
        Bundle b = new Bundle();
        b.putString("command", cmd);
        b.putLong("timeoutMs", (long) timeout);
        b.putString("cwd", cwd == null ? "" : cwd);
        return moduleCall("shell", b);
    }

    private static JSONObject shellExecLocal(String cmd, int timeout, String cwd)throws Exception{
        Class<?> c; try{c=Class.forName("rikka.shizuku.Shizuku");}catch(Throwable e){return new JSONObject().put("ok",false).put("available",false).put("error","Shizuku API 未加载；请安装/启用 Shizuku API 依赖后再执行 shell_exec");}
        try{ Object b=c.getMethod("getBinder").invoke(null); if(b==null || !(b instanceof android.os.IBinder) || !((android.os.IBinder)b).isBinderAlive()) return new JSONObject().put("ok",false).put("available",true).put("error","Shizuku binder 未连接"); }catch(Throwable ignored){}
        try{int p=(Integer)c.getMethod("checkSelfPermission").invoke(null);if(p!=android.content.pm.PackageManager.PERMISSION_GRANTED)return new JSONObject().put("ok",false).put("available",true).put("permissionGranted",false).put("error","Shizuku 尚未授权");}catch(Throwable e){return new JSONObject().put("ok",false).put("available",true).put("error","无法检查 Shizuku 授权: "+e);}
        java.lang.reflect.Method np=c.getDeclaredMethod("newProcess",String[].class,String[].class,String.class);np.setAccessible(true);
        Object rp=np.invoke(null,new String[]{"/system/bin/sh","-c",cmd},null,cwd.isEmpty()?null:cwd);
        java.lang.reflect.Method gis=rp.getClass().getMethod("getInputStream");java.lang.reflect.Method ges=rp.getClass().getMethod("getErrorStream");InputStream in=(InputStream)gis.invoke(rp);InputStream er=(InputStream)ges.invoke(rp);
        ExecutorService io=Executors.newFixedThreadPool(2); Future<String> of=io.submit(() -> new String(readAll(in,2*1024*1024),StandardCharsets.UTF_8)); Future<String> ef=io.submit(() -> new String(readAll(er,2*1024*1024),StandardCharsets.UTF_8));
        String out,err; boolean timed=false; try{out=of.get(timeout,TimeUnit.MILLISECONDS);}catch(TimeoutException te){timed=true;of.cancel(true);out="";}try{err=ef.get(Math.max(1000,timeout/2),TimeUnit.MILLISECONDS);}catch(Throwable te){err="";}io.shutdownNow();
        try{rp.getClass().getMethod("destroy").invoke(rp);}catch(Throwable ignored){}
        int exit=-1; try{ Object w=rp.getClass().getMethod("waitFor").invoke(rp); if(w instanceof Integer) exit=((Integer)w).intValue(); }catch(Throwable ignored){}
        boolean ok=!timed && exit==0;
        return new JSONObject().put("ok",ok).put("available",true).put("permissionGranted",true).put("timedOut",timed).put("exitCode",exit).put("stdout",out).put("stderr",err).put("command",cmd);
    }

    private static JSONObject projectStatus(JSONObject a)throws Exception{File p=safeLocalFile(a.optString("project",ACTIVE_PROJECT));File m=new File(p,"project.json");if(!m.isFile())throw new IOException("project.json 不存在");byte[] b=readAll(new FileInputStream(m),65536);return new JSONObject(new String(b,StandardCharsets.UTF_8));}
    private static synchronized JSONObject projectResume(JSONObject a)throws Exception{
        JSONObject o=projectStatus(a); String p=o.optString("root",a.optString("project",ACTIVE_PROJECT)); String apk=o.optString("source_apk","");
        if(apk.isEmpty()) throw new IOException("checkpoint 缺少 source_apk，无法安全恢复");
        if(ACTIVE_PROJECT_FUTURE!=null&&!ACTIVE_PROJECT_FUTURE.isDone()) throw new IOException("已有项目任务运行中");
        ACTIVE_PROJECT=p; ACTIVE_PROJECT_TASK="resuming"; ACTIVE_PROJECT_HEARTBEAT=System.currentTimeMillis();
        ACTIVE_PROJECT_FUTURE=PROJECT_EXECUTOR.submit(() -> runProjectPipeline(apk,p));
        return new JSONObject().put("ok",true).put("resumed",true).put("project",p).put("source_apk",apk).put("next_step",o.optString("next_step","analyze")).put("heartbeat",ACTIVE_PROJECT_HEARTBEAT);
    }

    private static final class ScanStats {
        int listCalls;
        int listNull;
        int directoriesVisited;
        int entriesSeen;
        int mediaStoreMatches;
        int nioTried;
        int nioEntries;
        boolean listFailure;
        boolean rootListed;
        boolean mediaStoreTried;
    }

    private static void addLocalApkItem(JSONArray out, File f, Set<String> seen, String sourceType) {
        if (f == null || out.length() >= 500 || !looksLikeApkPath(f.getName())) return;
        try {
            String path = f.getCanonicalPath();
            if (!seen.add(path)) return;
            JSONObject item = new JSONObject();
            item.put("path", path);
            item.put("name", f.getName());
            item.put("sizeBytes", f.length());
            item.put("lastModified", f.lastModified());
            item.put("sourceType", sourceType == null ? "ANDROID_FILESYSTEM" : sourceType);
            out.put(item);
        } catch (Throwable ignored) {}
    }

    private static void scanLocalApkDirectory(File dir, int depth, int maxDepth, boolean recursive,
                                              int limit, JSONArray out, Set<String> seen, ScanStats stats) {
        if (dir == null || out.length() >= limit || depth > maxDepth) return;
        stats.directoriesVisited++;
        try {
            String canonical = dir.getCanonicalPath();
            if (!seen.add("DIR:" + canonical)) return;
        } catch (Throwable ignored) {
            if (!seen.add("DIR:" + dir.getAbsolutePath())) return;
        }
        File[] files = null;
        stats.listCalls++;
        try { files = dir.listFiles(); } catch (Throwable t) { stats.listFailure = true; log("[LOCAL-APK] listFiles exception " + dir + " -> " + t); }
        if (files == null) {
            stats.listNull++;
            log("[LOCAL-APK] listFiles=null dir=" + dir + " depth=" + depth +
                    " canRead=" + safeCanRead(dir));
            return;
        }
        if (depth == 0) stats.rootListed = true;
        Arrays.sort(files, new Comparator<File>() {
            @Override public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); }
        });
        for (File f : files) {
            if (out.length() >= limit) break;
            if (f == null) continue;
            stats.entriesSeen++;
            try {
                if (f.isFile() && looksLikeApkPath(f.getName())) {
                    addLocalApkItem(out, f, seen, "ANDROID_FILESYSTEM");
                } else if (recursive && f.isDirectory() && depth < maxDepth) {
                    scanLocalApkDirectory(f, depth + 1, maxDepth, true, limit, out, seen, stats);
                }
            } catch (Throwable ignored) {}
        }
    }

    private static boolean safeCanRead(File f) {
        try { return f != null && f.canRead(); } catch (Throwable ignored) { return false; }
    }

    private static void scanNioApkDirectory(File root, int depth, int maxDepth, boolean recursive,
                                            int limit, JSONArray out, Set<String> seen, ScanStats stats) {
        if (Build.VERSION.SDK_INT < 26 || root == null || out.length() >= limit || depth > maxDepth) return;
        stats.nioTried++;
        try {
            Path p = root.toPath();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(p)) {
                for (Path child : stream) {
                    if (out.length() >= limit) break;
                    stats.nioEntries++;
                    File f = child.toFile();
                    if (Files.isRegularFile(child) && looksLikeApkPath(child.getFileName().toString())) {
                        addLocalApkItem(out, f, seen, "ANDROID_FILESYSTEM_NIO");
                    } else if (recursive && Files.isDirectory(child) && depth < maxDepth) {
                        scanNioApkDirectory(f, depth + 1, maxDepth, true, limit, out, seen, stats);
                    }
                }
            }
        } catch (Throwable t) {
            log("[LOCAL-APK] NIO scan failed dir=" + root + " -> " + t);
        }
    }

    private static void scanMediaStoreApks(String directory, boolean recursive, int maxDepth,
                                           int limit, JSONArray out, Set<String> seen, ScanStats stats) {
        Context ctx = applicationContext;
        if (ctx == null || out.length() >= limit) return;
        stats.mediaStoreTried = true;
        Cursor c = null;
        try {
            Uri uri = MediaStore.Files.getContentUri("external");
            String[] projection = new String[]{
                    MediaStore.Files.FileColumns.DATA,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.DATE_MODIFIED
            };
            String prefix = directory.endsWith("/") ? directory : directory + "/";
            String selection = MediaStore.Files.FileColumns.DATA + " LIKE ? AND LOWER(" +
                    MediaStore.Files.FileColumns.DISPLAY_NAME + ") LIKE ?";
            String[] args = new String[]{ prefix + (recursive ? "%" : "%"), "%.apk" };
            c = ctx.getContentResolver().query(uri, projection, selection, args,
                    MediaStore.Files.FileColumns.DISPLAY_NAME + " COLLATE NOCASE ASC");
            if (c == null) return;
            int pathIx = c.getColumnIndex(MediaStore.Files.FileColumns.DATA);
            int nameIx = c.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME);
            int sizeIx = c.getColumnIndex(MediaStore.Files.FileColumns.SIZE);
            int modIx = c.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED);
            while (c.moveToNext() && out.length() < limit) {
                String path = pathIx >= 0 ? c.getString(pathIx) : "";
                String name = nameIx >= 0 ? c.getString(nameIx) : "";
                if (path == null || path.isEmpty()) continue;
                if (!isUnderDirectory(path, directory, recursive, maxDepth)) continue;
                try {
                    File f = new File(path);
                    String canonical = f.getCanonicalPath();
                    if (!seen.add(canonical)) continue;
                    JSONObject item = new JSONObject();
                    item.put("path", canonical);
                    item.put("name", name == null || name.isEmpty() ? f.getName() : name);
                    item.put("sizeBytes", sizeIx >= 0 ? c.getLong(sizeIx) : f.length());
                    item.put("lastModified", modIx >= 0 ? c.getLong(modIx) * 1000L : f.lastModified());
                    item.put("sourceType", "MEDIASTORE_FILES");
                    out.put(item);
                    stats.mediaStoreMatches++;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            log("[LOCAL-APK] MediaStore scan unavailable: " + t);
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) {}
        }
    }

    private static boolean isUnderDirectory(String path, String directory, boolean recursive, int maxDepth) {
        try {
            File p = new File(path).getCanonicalFile();
            File d = new File(directory).getCanonicalFile();
            String ps = p.getPath(), ds = d.getPath();
            if (!ps.startsWith(ds.endsWith(File.separator) ? ds : ds + File.separator)) return false;
            if (recursive) {
                int depth = 0;
                File cur = p.getParentFile();
                while (cur != null && !cur.equals(d)) { depth++; cur = cur.getParentFile(); }
                return cur != null && depth <= maxDepth;
            }
            return p.getParentFile() != null && p.getParentFile().equals(d);
        } catch (Throwable ignored) { return false; }
    }

    static JSONObject scanLocalApksForProvider(String directory, boolean recursive, int maxDepth, int limit) {
        JSONObject out = new JSONObject();
        JSONArray items = new JSONArray();
        try {
            if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                out.put("ok", false);
                out.put("modulePermissionMissing", true);
                out.put("error", "模块进程未获得 MANAGE_EXTERNAL_STORAGE");
                out.put("items", items);
                return out;
            }
            File root = new File(directory);
            if (!root.exists() || !root.isDirectory()) return jsonError(out, "模块进程无法访问目录: " + directory, items);
            ScanStats stats = new ScanStats();
            Set<String> seen = new HashSet<>();
            scanLocalApkDirectory(root, 0, Math.min(8, Math.max(0, maxDepth)), recursive,
                    Math.min(500, Math.max(1, limit)), items, seen, stats);
            if (items.length() == 0 && Build.VERSION.SDK_INT >= 26) {
                scanNioApkDirectory(root, 0, Math.min(8, Math.max(0, maxDepth)), recursive,
                        Math.min(500, Math.max(1, limit)), items, seen, stats);
            }
            out.put("ok", true);
            out.put("sourceType", "MODULE_PROCESS_FILESYSTEM");
            out.put("directory", root.getCanonicalPath());
            out.put("count", items.length());
            out.put("items", items);
            out.put("modulePermissionMissing", false);
            out.put("scan", new JSONObject().put("listFilesNull", stats.listNull)
                    .put("directoriesVisited", stats.directoriesVisited).put("entriesSeen", stats.entriesSeen));
            log("[LOCAL-APK][MODULE-PROCESS] directory=" + directory + " count=" + items.length());
            return out;
        } catch (Throwable t) {
            return jsonError(out, "模块进程 APK 扫描失败: " + t, items);
        }
    }

    /**
     * Calls a method in the module process. Returns null when the bridge is unreachable.
     *
     * Two transports are tried in order:
     *   1. {@link ApkScanProvider} over Binder. Rejected by Android 11+ package visibility when the
     *      caller (this injected YuanBao process) does not declare the module in its &lt;queries&gt;,
     *      which is exactly our situation, so this is only a fast path for shell/self callers.
     *   2. {@link #moduleCallViaSocket} - loopback TCP to {@link BridgeServer} in the module process.
     *      Package visibility does not apply to sockets, so this is the transport that actually
     *      works from inside YuanBao.
     */
    static JSONObject moduleCall(String method, Bundle extras) {
        // 1) Standalone shell daemon (uid 2000): fastest path, and the only one that keeps working
        //    after the module app is force-stopped, updated or killed by the ROM.
        JSONObject viaDaemon = daemonCall(method, extras);
        if (viaDaemon != null) {
            BRIDGE_TRANSPORT = "shell-daemon";
            return viaDaemon;
        }
        // 2) Binder ContentProvider in the module process. Rejected by Android 11+ package
        //    visibility from inside YuanBao, but free to try for shell/self callers.
        JSONObject viaProvider = moduleCallViaProvider(method, extras);
        if (viaProvider != null) {
            BRIDGE_TRANSPORT = "binder-provider";
            return viaProvider;
        }
        // 3) Loopback socket into the module process (Shizuku-backed: needs the module app alive).
        JSONObject viaSocket = moduleCallViaSocket(method, extras);
        BRIDGE_TRANSPORT = viaSocket != null ? "localhost-socket" : "none";
        if (viaSocket != null) requestDaemonEnsure("module-fallback");
        return viaSocket;
    }

    /**
     * Asks the module process to (re)start the standalone daemon, through the module's own socket
     * (never through {@link #moduleCall}, which would just bounce back to the dead daemon).
     * Fire-and-forget on a background thread, at most once every 30s, so that later calls are served
     * by the fast path instead of the Shizuku-backed module process.
     */
    private static void requestDaemonEnsure(String reason) {
        long now = System.currentTimeMillis();
        if (now - BRIDGE_DAEMON_ENSURE_AT < 30000L) return;
        BRIDGE_DAEMON_ENSURE_AT = now;
        final String why = reason == null ? "" : reason;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Bundle b = new Bundle();
                    b.putString("reason", why);
                    JSONObject r = socketCallOn("daemon_ensure", b,
                            BRIDGE_PORT_FIRST_DEFAULT, BRIDGE_PORT_LAST_DEFAULT, false);
                    log("[BRIDGE][daemon] ensure via module process: "
                            + (r == null ? "unreachable" : shorten(r.toString(), 400)));
                } catch (Throwable t2) {
                    log("[BRIDGE][daemon] ensure failed: " + t2);
                }
            }
        }, "yb-daemon-ensure");
        t.setDaemon(true);
        t.start();
    }

    /** Transport 1: ContentProvider/Binder. Returns null when unreachable or refused. */
    private static JSONObject moduleCallViaProvider(String method, Bundle extras) {
        try {
            Context ctx = applicationContext;
            if (ctx == null) { BRIDGE_PROVIDER_ERROR = "no application context yet"; return null; }
            Bundle r = ctx.getContentResolver().call(
                    Uri.parse("content://" + ApkScanProvider.AUTHORITY), method, null,
                    extras == null ? new Bundle() : extras);
            if (r == null) { BRIDGE_PROVIDER_ERROR = "provider returned null bundle"; return null; }
            String json = r.getString("json", "");
            if (json == null || json.isEmpty()) { BRIDGE_PROVIDER_ERROR = "provider returned empty json"; return null; }
            JSONObject parsed = new JSONObject(json);
            // A provider answer of "caller not allowed" means the module process was reached after
            // all; keep it out of the socket path to avoid running a privileged method twice.
            if (!parsed.optBoolean("ok", true) && parsed.optString("error", "").contains("caller not allowed")) {
                BRIDGE_PROVIDER_ERROR = "caller not allowed";
                BRIDGE_TRANSPORT = "binder-provider(rejected)";
                return parsed;
            }
            BRIDGE_PROVIDER_ERROR = "";
            return parsed;
        } catch (Throwable t) {
            // Typical here: SecurityException / IllegalArgumentException from package visibility
            // filtering ("unknown provider", "not exported", "Failed to find provider info").
            BRIDGE_PROVIDER_ERROR = String.valueOf(t);
            return null;
        }
    }

    // ----------------------------------------------------------- module process bridge (transport 2)

    /** Which transport answered the last moduleCall(): binder-provider | localhost-socket | none. */
    private static volatile String BRIDGE_TRANSPORT = "none";
    private static volatile String BRIDGE_PROVIDER_ERROR = "";
    private static volatile String BRIDGE_SOCKET_ERROR = "";
    private static volatile long BRIDGE_BLOCKED_UNTIL = 0L;
    private static volatile int BRIDGE_SOCKET_CALLS = 0;
    private static volatile int BRIDGE_PORT_HINT = 0;
    private static volatile JSONObject BRIDGE_HANDSHAKE;

    // ---- standalone shell daemon (uid 2000), reached directly over loopback ----------------------

    /** Daemon port family. Kept disjoint from the module bridge range so both can coexist. */
    static final int BRIDGE_DAEMON_PORT_FIRST = 8810;
    static final int BRIDGE_DAEMON_PORT_LAST = 8819;
    /** Module process bridge defaults (the values BridgeServer actually uses). */
    static final int BRIDGE_PORT_FIRST_DEFAULT = 8799;
    static final int BRIDGE_PORT_LAST_DEFAULT = 8809;
    private static volatile String BRIDGE_DAEMON_ERROR = "";
    private static volatile long BRIDGE_DAEMON_BLOCKED_UNTIL = 0L;
    private static volatile int BRIDGE_DAEMON_CALLS = 0;
    private static volatile int BRIDGE_DAEMON_PORT_HINT = 0;
    /** Which family answered the last socket call: daemon | module. */
    private static volatile String LAST_SOCKET_FAMILY = "";
    private static volatile long BRIDGE_DAEMON_ENSURE_AT = 0L;

    /** Handshake file the module reads through Shizuku. It lives in OUR own external files dir. */
    private static File bridgeHandshakeFile() {
        try {
            Context ctx = applicationContext;
            File dir = ctx == null ? null : ctx.getExternalFilesDir(null);
            if (dir == null) {
                String pkg = ctx == null ? "com.tencent.hunyuan.app.chat" : ctx.getPackageName();
                dir = new File("/storage/emulated/0/Android/data/" + pkg + "/files");
            }
            if (!dir.exists()) dir.mkdirs();
            return new File(dir, "yb_bridge.json");
        } catch (Throwable t) {
            return null;
        }
    }

    static String bridgeHandshakePath() {
        File f = bridgeHandshakeFile();
        return f == null ? "" : f.getAbsolutePath();
    }

    /**
     * Writes the loopback bridge token for the module process to pick up.
     *
     * Only YuanBao's own external files directory is used for two reasons: the injected host process
     * can always create it without any storage permission, and the module process can read it as
     * uid 2000 (shell) through its Shizuku grant. The directory is not reachable by other apps, so
     * possession of the token means the caller is YuanBao, the shell or root. The token is stable
     * across process restarts and rotated once a day, so the module can reuse its cached copy and
     * skip re-reading this file through Shizuku on every host restart.
     */
    private static synchronized void writeBridgeHandshake() {
        try {
            String pkg = applicationContext == null ? "" : applicationContext.getPackageName();
            // Only the injected YuanBao process needs to publish a token; in the module's own
            // process the external files dir belongs to the module and has nothing to hand off.
            if ("com.example.yuanbaossehook".equals(pkg)) return;
            File f = bridgeHandshakeFile();
            if (f == null) { log("[BRIDGE] handshake path unavailable"); return; }
            JSONObject o = new JSONObject();
            o.put("protocol", "yb-bridge/1");
            o.put("token", BridgeConfig.bridgeToken());
            o.put("portFirst", BRIDGE_PORT_FIRST_DEFAULT);
            o.put("portLast", BRIDGE_PORT_LAST_DEFAULT);
            // Port family of the standalone uid-2000 shell daemon, so the module and the daemon agree
            // on where the fast path lives without any further coordination.
            o.put("daemonPortFirst", BRIDGE_DAEMON_PORT_FIRST);
            o.put("daemonPortLast", BRIDGE_DAEMON_PORT_LAST);
            o.put("hostPackage", applicationContext == null ? "" : applicationContext.getPackageName());
            o.put("hostPid", android.os.Process.myPid());
            o.put("ts", System.currentTimeMillis());
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(f, false);
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally {
                if (out != null) try { out.close(); } catch (Throwable ignored) {}
            }
            BRIDGE_HANDSHAKE = o;
            BRIDGE_PORT_HINT = 0;
            BRIDGE_BLOCKED_UNTIL = 0L;
            log("[BRIDGE] handshake written " + f.getAbsolutePath());
        } catch (Throwable t) {
            log("[BRIDGE] handshake write failed: " + t);
        }
    }

    private static long bundleLong(Bundle b, String key, long def) {
        if (b == null) return def;
        try {
            Object o = b.get(key);
            if (o instanceof Number) return ((Number) o).longValue();
            if (o != null) return Long.parseLong(String.valueOf(o).trim());
        } catch (Throwable ignored) {}
        return def;
    }

    /** Bundle -> JSON for the socket transport (base64 for byte[]). */
    private static JSONObject bundleToJson(Bundle b) {
        JSONObject o = new JSONObject();
        if (b == null) return o;
        try {
            for (String k : b.keySet()) {
                Object v = b.get(k);
                if (v == null) continue;
                if (v instanceof byte[]) o.put(k, android.util.Base64.encodeToString((byte[]) v, android.util.Base64.NO_WRAP));
                else o.put(k, v);
            }
        } catch (Throwable ignored) {}
        return o;
    }

    /**
     * Transport 1 (preferred): the standalone shell daemon (uid 2000) started by the module through
     * Shizuku. It runs outside the module app process, so this transport keeps working when the
     * module app is force-stopped, updated or killed by the ROM, and it skips the Binder round trip
     * into Shizuku for every request. Returns null when the daemon is not running (the caller then
     * falls back to the module process) or when the daemon explicitly does not implement the method,
     * in which case the module process stays authoritative.
     */
    private static JSONObject daemonCall(String method, Bundle extras) {
        JSONObject r = socketCallOn(method, extras, BRIDGE_DAEMON_PORT_FIRST, BRIDGE_DAEMON_PORT_LAST, true);
        if (r == null) return null;
        // Context-dependent methods (SAF documents, Shizuku grant, media index) are only served by
        // the module process: hand them over instead of reporting a failure.
        if (!r.optBoolean("ok", true) && "daemon".equals(r.optString("unsupportedBy", ""))) {
            log("[BRIDGE][daemon] " + method + " not implemented by the daemon, using module process");
            return null;
        }
        return r;
    }

    /**
     * Transport 2: loopback TCP to {@link BridgeServer} inside the module process.
     * Returns null when unreachable. Package visibility does not apply to sockets, which is why
     * this path works from inside YuanBao where the ContentProvider path cannot.
     */
    private static JSONObject moduleCallViaSocket(String method, Bundle extras) {
        return socketCallOn(method, extras, BRIDGE_PORT_FIRST_DEFAULT, BRIDGE_PORT_LAST_DEFAULT, false);
    }

    /**
     * One loopback call against a port family. Both families speak the same frame protocol; they
     * differ in who answers (standalone uid-2000 daemon vs. module app process) and therefore in
     * which failure state is reported.
     */
    private static JSONObject socketCallOn(String method, Bundle extras, int defaultFirst, int defaultLast,
                                           boolean daemonFamily) {
        try {
            if (applicationContext == null) {
                setSocketError(daemonFamily, "no application context yet");
                return null;
            }
            long now = System.currentTimeMillis();
            if (now < (daemonFamily ? BRIDGE_DAEMON_BLOCKED_UNTIL : BRIDGE_BLOCKED_UNTIL)) {
                return null;  // recent failure: skip the connect penalty
            }
            JSONObject hs = BRIDGE_HANDSHAKE;
            if (hs == null || hs.optString("token", "").isEmpty()) { writeBridgeHandshake(); hs = BRIDGE_HANDSHAKE; }
            if (hs == null) {
                setSocketError(daemonFamily, "handshake unavailable");
                return null;
            }
            String token = hs.optString("token", "");
            if (token.isEmpty()) {
                setSocketError(daemonFamily, "handshake token empty");
                return null;
            }
            String prefix = daemonFamily ? "daemonPort" : "port";
            int first = hs.optInt(prefix + "First", defaultFirst);
            int last = hs.optInt(prefix + "Last", defaultLast);
            if (last < first) last = first;

            JSONObject req = new JSONObject();
            req.put("method", method);
            req.put("token", token);
            req.put("extras", bundleToJson(extras));
            byte[] payload = req.toString().getBytes(StandardCharsets.UTF_8);

            int readTimeout = "shell".equals(method)
                    ? (int) Math.min(660000L, Math.max(20000L, bundleLong(extras, "timeoutMs", 30000L) + 20000L))
                    : 120000;

            int hint = daemonFamily ? BRIDGE_DAEMON_PORT_HINT : BRIDGE_PORT_HINT;
            Throwable lastError = null;
            String lastErrorMessage = "";
            for (int i = 0; i <= (last - first); i++) {
                int port = first + i;
                if (hint >= first && hint <= last) port = (i == 0) ? hint : (first + i);
                if (port < first || port > last) continue;
                Socket s = new Socket();
                try {
                    s.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), daemonFamily ? 350 : 700);
                    s.setSoTimeout(readTimeout);
                    s.setTcpNoDelay(true);
                    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 65536));
                    out.writeInt(payload.length);
                    out.write(payload);
                    out.flush();
                    DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 65536));
                    int len = in.readInt();
                    if (len <= 0 || len > 96 * 1024 * 1024) throw new IOException("bad frame length " + len);
                    byte[] buf = new byte[len];
                    in.readFully(buf);
                    JSONObject resp = new JSONObject(new String(buf, StandardCharsets.UTF_8));
                    if (!resp.optBoolean("ok", false)) throw new IOException(resp.optString("error", "bridge refused"));
                    String inner = resp.optString("result", "");
                    if (inner.isEmpty()) throw new IOException("empty bridge result");
                    if (daemonFamily) {
                        BRIDGE_DAEMON_PORT_HINT = port;
                        BRIDGE_DAEMON_CALLS++;
                        BRIDGE_DAEMON_ERROR = "";
                        BRIDGE_DAEMON_BLOCKED_UNTIL = 0L;
                    } else {
                        BRIDGE_PORT_HINT = port;
                        BRIDGE_SOCKET_CALLS++;
                        BRIDGE_SOCKET_ERROR = "";
                        BRIDGE_BLOCKED_UNTIL = 0L;
                    }
                    LAST_SOCKET_FAMILY = daemonFamily ? "daemon" : "module";
                    log("[BRIDGE][socket] " + method + " ok family=" + LAST_SOCKET_FAMILY + " port=" + port);
                    return new JSONObject(inner);
                } catch (Throwable t) {
                    lastError = t;
                    lastErrorMessage = String.valueOf(t.getMessage());
                } finally {
                    try { s.close(); } catch (Throwable ignored) {}
                }
            }
            setSocketError(daemonFamily, String.valueOf(lastError));
            if (daemonFamily) {
                if (isAuthFailure(lastErrorMessage)) {
                    // The daemon reads its shared secret from a file in this process's external data
                    // dir, and that directory does get cleaned by the ROM while both processes keep
                    // running. Rewriting the handshake here heals the daemon path on the next call
                    // instead of permanently degrading to the slower module transport.
                    log("[BRIDGE][daemon] token rejected, rewriting handshake for the next call");
                    try { writeBridgeHandshake(); } catch (Throwable ignored) {}
                    BRIDGE_DAEMON_BLOCKED_UNTIL = 0L;
                } else {
                    BRIDGE_DAEMON_BLOCKED_UNTIL = System.currentTimeMillis() + 3000L;
                }
                log("[BRIDGE][daemon] " + method + " not reachable on 127.0.0.1:" + first + "-" + last
                        + ": " + lastError);
            } else {
                BRIDGE_BLOCKED_UNTIL = System.currentTimeMillis() + 3000L;
                log("[BRIDGE][socket] " + method + " unreachable on 127.0.0.1:" + first + "-" + last
                        + ": " + lastError);
            }
            return null;
        } catch (Throwable t) {
            setSocketError(daemonFamily, String.valueOf(t));
            if (daemonFamily) BRIDGE_DAEMON_BLOCKED_UNTIL = System.currentTimeMillis() + 3000L;
            else BRIDGE_BLOCKED_UNTIL = System.currentTimeMillis() + 3000L;
            return null;
        }
    }

    /** True when a bridge reply indicates a token/handshake mismatch rather than an outage. */
    private static boolean isAuthFailure(String message) {
        if (message == null) return false;
        String m = message.toLowerCase(java.util.Locale.US);
        return m.contains("token") || m.contains("鉴权") || m.contains("auth");
    }

    private static void setSocketError(boolean daemonFamily, String error) {
        if (daemonFamily) BRIDGE_DAEMON_ERROR = error == null ? "" : error;
        else BRIDGE_SOCKET_ERROR = error == null ? "" : error;
    }

    /**
     * Machine readable bridge state for tool results, so "0 APKs" can always be explained instead
     * of silently looking like "the directory is empty".
     */
    /**
     * Direct, independent view of the standalone uid-2000 daemon. Unlike {@link #bridgeDiagnostics}
     * this never falls back: it answers exactly "is the fast path up, and who is on the other end".
     * With {@code probe=true} it costs at most one 350ms connect attempt.
     */
    static JSONObject daemonDiagnostics(boolean probe) {
        JSONObject o = new JSONObject();
        try {
            o.put("portFirst", BRIDGE_DAEMON_PORT_FIRST);
            o.put("portLast", BRIDGE_DAEMON_PORT_LAST);
            o.put("calls", BRIDGE_DAEMON_CALLS);
            o.put("portHint", BRIDGE_DAEMON_PORT_HINT);
            o.put("socketError", shorten(BRIDGE_DAEMON_ERROR, 300));
            o.put("blockedForMs", Math.max(0L, BRIDGE_DAEMON_BLOCKED_UNTIL - System.currentTimeMillis()));
            o.put("lastSocketFamily", LAST_SOCKET_FAMILY);
            if (!probe) return o;
            long t0 = SystemClock.elapsedRealtime();
            JSONObject p = socketCallOn("ping", null,
                    BRIDGE_DAEMON_PORT_FIRST, BRIDGE_DAEMON_PORT_LAST, true);
            o.put("probeMs", SystemClock.elapsedRealtime() - t0);
            boolean alive = p != null && p.optBoolean("ok", false);
            o.put("alive", alive);
            if (alive) {
                o.put("pid", p.optInt("pid", 0));
                o.put("uid", p.optInt("uid", 0));
                o.put("uidIsShell", p.optInt("uid", 0) == 2000);
                o.put("via", p.optString("via", ""));
                o.put("execMode", p.optString("execMode", ""));
                o.put("uptimeMs", p.optLong("uptimeMs", 0L));
                o.put("served", p.optInt("served", 0));
                o.put("port", p.optInt("port", 0));
            }
        } catch (Throwable ignored) {}
        return o;
    }

    /**
     * One call that answers "which transport is serving my tools, as whom, and how fast".
     * The round trip is measured around a real {@code ping}, so it reflects the actual hop the tools
     * will take (daemon = no Shizuku involvement, module process = Shizuku-backed).
     */
    static JSONObject bridgeStatus() {
        JSONObject o = new JSONObject();
        try {
            long t0 = SystemClock.elapsedRealtime();
            JSONObject pong = moduleCall("ping", null);
            long took = SystemClock.elapsedRealtime() - t0;
            o.put("ok", pong != null && pong.optBoolean("ok", false));
            o.put("transport", BRIDGE_TRANSPORT);
            o.put("roundTripMs", took);
            if (pong != null) {
                o.put("pid", pong.optInt("pid", 0));
                o.put("uid", pong.optInt("uid", 0));
                o.put("uidIsShell", pong.optInt("uid", 0) == 2000);
                o.put("via", pong.optString("via", ""));
                o.put("execMode", pong.optString("execMode", ""));
                o.put("source", pong.optString("source", ""));
                o.put("uptimeMs", pong.optLong("uptimeMs", 0L));
                o.put("shizukuGranted", pong.optBoolean("shizukuGranted", false));
                o.put("safMounted", pong.optBoolean("safMounted", false));
            }
            o.put("daemon", daemonDiagnostics(false));
            o.put("bridge", bridgeDiagnostics(false));
            o.put("explain", "transport=shell-daemon: 直达 uid2000 守护进程（无 Shizuku 往返，模块 App 被杀/升级后仍可用）；"
                    + "localhost-socket: 回落到模块 App 进程（依赖模块存活）；none: 两条通道都不通。"
                    + "daemon.alive=false 时下一次调用会自动请求模块进程拉起守护进程。");
        } catch (Throwable t) {
            try { o.put("ok", false).put("error", "bridge_status 失败: " + t); } catch (Throwable ignored) {}
        }
        return o;
    }

    static JSONObject bridgeDiagnostics(boolean probe) {
        JSONObject o = new JSONObject();
        try {
            o.put("transport", BRIDGE_TRANSPORT);
            o.put("handshakeFile", bridgeHandshakePath());
            o.put("providerError", shorten(BRIDGE_PROVIDER_ERROR, 300));
            o.put("socketError", shorten(BRIDGE_SOCKET_ERROR, 300));
            o.put("socketCalls", BRIDGE_SOCKET_CALLS);
            o.put("portHint", BRIDGE_PORT_HINT);
            o.put("blockedForMs", Math.max(0L, BRIDGE_BLOCKED_UNTIL - System.currentTimeMillis()));
            // The daemon is the fast path: always report its state, so a slow call can be explained
            // ("fell back to the module process because the daemon was down") instead of guessed at.
            o.put("daemon", daemonDiagnostics(false));
            if (probe) {
                JSONObject st = moduleCall("ping", null);
                o.put("reachable", st != null && st.optBoolean("ok", false));
                if (st != null && st.optBoolean("ok", false)) {
                    o.put("modulePid", st.optInt("pid", 0));
                    o.put("moduleUid", st.optInt("uid", 0));
                    o.put("shizukuGranted", st.optBoolean("shizukuGranted", false));
                    o.put("safMounted", st.optBoolean("safMounted", false));
                }
            }
            if (!o.optBoolean("reachable", true)) {
                o.put("hint", "元宝进程连不上模块桥接服务。请在手机上打开一次模块 App「元宝本地 Agent 网关」，"
                        + "它会自动启动桥接前台服务；保持通知常驻后再重试。");
            }
        } catch (Throwable ignored) {}
        return o;
    }

    private static JSONObject scanViaModuleProvider(String directory, boolean recursive, int maxDepth, int limit) {
        JSONObject out = new JSONObject();
        JSONArray items = new JSONArray();
        try {
            Context ctx = applicationContext;
            if (ctx == null) return out;
            Bundle b = new Bundle();
            b.putString("directory", directory);
            b.putBoolean("recursive", recursive);
            b.putInt("maxDepth", maxDepth);
            b.putInt("limit", limit);
            Bundle r = ctx.getContentResolver().call(
                    Uri.parse("content://" + ApkScanProvider.AUTHORITY), "scan", null, b);
            if (r == null) return out;
            String json = r.getString("json", "");
            if (json.isEmpty()) return out;
            JSONObject parsed = new JSONObject(json);
            JSONArray a = parsed.optJSONArray("items");
            if (a != null) for (int i = 0; i < a.length(); i++) items.put(a.optJSONObject(i));
            out.put("ok", parsed.optBoolean("ok", false));
            out.put("items", items);
            out.put("modulePermissionMissing", parsed.optBoolean("modulePermissionMissing", false));
            out.put("error", parsed.optString("error", ""));
            log("[LOCAL-APK] module provider result count=" + items.length() +
                    " permissionMissing=" + parsed.optBoolean("modulePermissionMissing", false));
        } catch (Throwable t) {
            log("[LOCAL-APK] module provider fallback failed: " + t);
        }
        return out;
    }

    private static JSONObject jsonError(JSONObject out, String error, JSONArray items) {
        try {
            out.put("ok", false);
            out.put("error", error == null ? "unknown error" : error);
            out.put("items", items == null ? new JSONArray() : items);
        } catch (Throwable ignored) {}
        return out;
    }

    private static ToolDef findBestApkListTool() {
        ToolDef best = null;
        int score = -1;
        for (ToolDef t : cachedTools) {
            if (t == null || "builtin".equals(t.server)) continue;
            String x = (t.publicName + " " + t.realName + " " + t.description).toLowerCase(Locale.ROOT);
            int s = 0;
            if (containsAny(x, "list_available_apks", "list_apks", "available_apks")) s += 20;
            if (containsAny(x, "apk")) s += 8;
            if (containsAny(x, "list", "available", "scan", "directory", "folder", "文件", "目录")) s += 5;
            if (s > score) { score = s; best = t; }
        }
        return score >= 20 ? best : null;
    }

    private static boolean isMtApkListTool(ToolDef t) {
        if (t == null) return false;
        String x = (t.publicName + " " + t.realName + " " + t.description).toLowerCase(Locale.ROOT);
        return x.contains("mt_apk_list_available_apks") || x.contains("list_available_apks");
    }

    private static String apkParentDirectory(String apkPath) {
        if (apkPath == null || apkPath.trim().isEmpty()) return apkPath;
        String v = apkPath.trim();
        int slash = Math.max(v.lastIndexOf('/'), v.lastIndexOf('\\'));
        if (slash < 0) return v;
        String parent = v.substring(0, slash + 1);
        return parent.isEmpty() ? v : parent;
    }

    private static String directoryToMcpPrefix(String path) {
        if (path == null || path.trim().isEmpty()) return "";
        String v = path.trim();
        String root = MCP_ROOT.endsWith("/") ? MCP_ROOT.substring(0, MCP_ROOT.length() - 1) : MCP_ROOT;
        if (v.startsWith("file://")) {
            try { v = new File(new URI(v)).getPath(); } catch (Throwable ignored) {}
        }
        if (v.equals(root)) return "";
        if (v.startsWith(root + "/")) {
            v = v.substring(root.length() + 1);
        }
        while (v.startsWith("/")) v = v.substring(1);
        if (!v.isEmpty() && !v.endsWith("/")) v += "/";
        return v;
    }

    private static JSONObject buildApkListArgs(ToolDef t, String text, String path) {
        JSONObject a = new JSONObject();
        try {
            JSONObject schema = t.schema;
            JSONObject properties = schema == null ? null : schema.optJSONObject("properties");
            // mt_apk_list_available_apks is intentionally prefix-based: its prefix is a
            // relative MCP workspace prefix, not an absolute filesystem path. When the user
            // names /storage/emulated/0/MT2/mcp/, translate it to exactly "mcp/".
            boolean prefixOnlyApkList = properties != null && properties.has("prefix")
                    && isMtApkListTool(t);
            if (prefixOnlyApkList) {
                String prefix = directoryToMcpPrefix(path);
                a.put("prefix", prefix);
                if (properties.has("limit")) a.put("limit", 50);
                log("[NATIVE-AGENT] mt_apk_list_available_apks prefix=" + prefix
                        + " sourcePath=" + path);
                return a;
            }
            if (properties != null && properties.has("path") && path != null) a.put("path", path);
            if (properties != null && properties.has("directory") && path != null) a.put("directory", path);
            if (properties != null && properties.has("workspace") && path != null) a.put("workspace", path);
            if (properties != null && properties.has("prefix") && !a.has("prefix")) a.put("prefix", "");
            if (properties != null && properties.has("limit") && !a.has("limit")) a.put("limit", 50);
        } catch (Throwable ignored) {}
        return a;
    }

    private static final class IntentProfile {
        String label = "general";
        boolean explicitAction;
        final List<String> keys = new ArrayList<>();
    }

    private static IntentProfile detectIntentProfile(String text) {
        IntentProfile p = new IntentProfile();
        String l = text.toLowerCase(Locale.ROOT);
        addIntent(p,l,"analyze", "分析","检查","诊断","inspect","analy","diagnos");
        addIntent(p,l,"read", "读取","查看文件","打开文件","文件内容","read","open");
        addIntent(p,l,"search", "搜索","查找","检索","search","find","grep","query");
        addIntent(p,l,"list", "列目录","列出文件","文件列表","目录","list","directory");
        addIntent(p,l,"decompile", "反编译","解析 dex","smali","jadx","decompile");
        addIntent(p,l,"build", "构建","编译","打包","assemble","build","compile","package");
        addIntent(p,l,"network", "抓包","网络","请求","接口","network","request","http","api");
        addIntent(p,l,"write", "修改","写入","保存","创建","编辑","write","edit","save","create","patch");
        addIntent(p,l,"delete", "删除","移除","delete","remove");
        addIntent(p,l,"execute", "执行命令","运行命令","执行","运行","shell","exec","command","run");
        if (p.keys.isEmpty()) p.label = "general";
        // Any explicit mutating/command verb is considered an explicit action.
        p.explicitAction = p.keys.contains("write") || p.keys.contains("delete") || p.keys.contains("execute") || p.keys.contains("build");
        return p;
    }

    private static void addIntent(IntentProfile p, String text, String label, String... words) {
        for (String w : words) {
            if (text.contains(w.toLowerCase(Locale.ROOT))) {
                if (!p.keys.contains(label)) p.keys.add(label);
                if ("general".equals(p.label)) p.label = label;
                break;
            }
        }
    }

    private static int intentScore(String hay, IntentProfile p) {
        int score = 0;
        for (String k : p.keys) {
            if ("analyze".equals(k) && containsAny(hay,"analy","analysis","inspect","diagnos","分析","检查","诊断")) score += 8;
            else if ("read".equals(k) && containsAny(hay,"read","open","file","读取","查看","内容")) score += 8;
            else if ("search".equals(k) && containsAny(hay,"search","find","grep","query","搜索","查找","检索")) score += 8;
            else if ("list".equals(k) && containsAny(hay,"list","directory","files","目录","列表")) score += 8;
            else if ("decompile".equals(k) && containsAny(hay,"decompile","jadx","baksmali","smali","dex","反编译","解析")) score += 9;
            else if ("build".equals(k) && containsAny(hay,"build","assemble","compile","package","构建","编译","打包")) score += 9;
            else if ("network".equals(k) && containsAny(hay,"network","request","http","api","capture","proxy","抓包","网络","请求")) score += 8;
            else if ("write".equals(k) && containsAny(hay,"write","edit","save","patch","create","修改","写入","保存","编辑")) score += 9;
            else if ("delete".equals(k) && containsAny(hay,"delete","remove","删除","移除")) score += 10;
            else if ("execute".equals(k) && containsAny(hay,"shell","exec","command","run","execute","执行","运行","命令")) score += 9;
        }
        return score;
    }

    private static int schemaIntentScore(JSONObject schema, IntentProfile p) {
        if (schema == null) return 0;
        String x = schema.toString().toLowerCase(Locale.ROOT);
        int score = 0;
        if (p.keys.contains("search") && containsAny(x,"query","keyword","pattern")) score += 3;
        if (p.keys.contains("read") && containsAny(x,"path","file","content")) score += 3;
        if (p.keys.contains("network") && containsAny(x,"url","method","headers","body")) score += 3;
        if (p.keys.contains("build") && containsAny(x,"project","module","variant","gradle")) score += 3;
        return score;
    }

    private static boolean isDestructiveTool(String hay) {
        return containsAny(hay,"delete","remove","write","edit","patch","shell","exec","command","修改","写入","删除","执行命令");
    }

    private static boolean hasArgumentFromText(ToolDef t, String text, String arg) {
        if (text == null || arg == null) return false;
        String path = extractPathHint(text);
        if (path != null && isPathLikeArgument(arg)) return true;
        String l = text.toLowerCase(Locale.ROOT);
        String a = arg.toLowerCase(Locale.ROOT);
        return l.contains(a);
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null) return false;
        for (String n : needles) if (n != null && !n.isEmpty() && text.contains(n.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static boolean isPathLikeArgument(String name) {
        if (name == null) return false;
        String x = name.toLowerCase(Locale.ROOT);
        return x.equals("path") || x.equals("file") || x.equals("filepath") || x.equals("file_path") ||
                x.equals("apk") || x.equals("input") || x.equals("target") || x.equals("directory") || x.equals("workspace") ||
                x.equals("workspaceid") || x.equals("workspace_id") || x.equals("locator") || x.equals("uri") || x.equals("source");
    }

    private static boolean hasDangerousCapability(String hay) {
        return containsAny(hay, "write", "edit", "patch", "delete", "remove", "shell", "exec",
                "command", "修改", "写入", "删除", "执行命令", "构建", "build", "compile");
    }

    /** Execute model-produced tool calls and return the next model turn containing only real results. */
    static String executeNativeModelToolCalls(String originalPrompt, String modelText, int round) throws Exception {
        refreshTools();
        List<Call> calls = parseCalls(modelText);
        if (calls.isEmpty()) {
            calls = fallbackCalls(modelText, cachedTools, Collections.<JSONObject>emptyList());
            if (!calls.isEmpty()) log("[NATIVE-AGENT] recovered untagged tool call(s)="+calls.size());
        }
        if (calls.isEmpty()) {
            // The upstream YuanBao model frequently answers in plain prose and never emits any
            // tool protocol. Previously this threw "模型输出未包含可解析的 MCP tool call", which
            // aborted the round and produced the reported "调了没回结果". Fall back to the
            // catalog-grounded inference so the task continues. inferNativeCalls only ever
            // returns read-only/list/analysis tools, so this cannot trigger destructive actions.
            List<Call> inferred = inferNativeCalls(nativeIntentSource(originalPrompt));
            if (inferred != null && !inferred.isEmpty()) {
                calls = inferred;
                log("[NATIVE-AGENT] model emitted no tool call; recovered " + calls.size() + " catalog-grounded call(s) from prompt");
            }
        }
        if (calls.isEmpty()) throw new IOException("模型输出未包含可解析的 MCP tool call");
        StringBuilder next = new StringBuilder();
        next.append("用户原始任务：\n").append(originalPrompt).append("\n\n")
            .append("第 ").append(round).append(" 轮 MCP 工具执行结果如下。请基于真实结果继续任务；如果仍需要更多工具，只需用自然语言说明下一步；不要输出工具协议、XML 标签或 JSON 工具调用，网关会根据真实 MCP 工具目录继续执行。\n\n");
        int n=0;
        for (Call c : calls) {
            if (activeNativeTaskCancelled()) throw new IOException("Native Agent task cancelled by user");
            if (n++ >= 5) break;
            ToolDef known = findTool(c.name);
            if (known == null) {
                JSONObject synth = synthesizeMissingApkList(c);
                if (synth != null) {
                    log("[NATIVE-AGENT] synthesized " + c.name + " from local APK discovery");
                    next.append("[MCP_RESULT name=\"").append(c.name).append("\"]\n")
                        .append(shorten(synth.toString(), 14000)).append("\n[/MCP_RESULT]\n");
                } else {
                    next.append("[MCP_ERROR tool=\"").append(c.name).append("\"] 未找到该工具\n[/MCP_ERROR]\n");
                }
                continue;
            }
            log("[NATIVE-AGENT] tools/call name="+c.name+" args="+shorten(c.args.toString(),1200));
            try {
                JSONObject result = NativeToolRegistry.callDirect(c.name, c.args);
                next.append("[MCP_RESULT name=\"").append(c.name).append("\"]\n")
                    .append(shorten(result == null ? "null" : result.toString(),14000))
                    .append("\n[/MCP_RESULT]\n");
            } catch (Throwable e) {
                next.append("[MCP_ERROR name=\"").append(c.name).append("\"]\n")
                    .append(shorten(String.valueOf(e),3000)).append("\n[/MCP_ERROR]\n");
            }
        }
        next.append("\n真实 MCP 工具结果结束。不要重复解释工具调用协议；继续完成用户任务。\n\n【工具目录】\n");
        int n2=0;
        for (ToolDef t:cachedTools) {
            if(n2++>=40)break;
            next.append("- ").append(t.publicName).append(" [server=").append(t.server).append("]: ").append(shorten(t.description,350));
            if(t.schema!=null)next.append(" schema=").append(shorten(t.schema.toString(),1200));
            next.append('\n');
        }
        return next.toString();
    }

    /**
     * When the model asks for an APK-listing tool the remote MCP server does not expose (or that is
     * temporarily unreachable), answer it from the local index instead of "工具未找到". The items
     * carry MCP-workspace relative paths, which is exactly what mt_apk_open requires.
     */
    private static JSONObject synthesizeMissingApkList(Call c) {
        try {
            if (c == null || c.name == null) return null;
            String n = c.name.toLowerCase(Locale.ROOT);
            if (!n.contains("available_apks") && !n.contains("list_apks")) return null;
            int limit = c.args == null ? 50 : c.args.optInt("limit", 50);
            limit = Math.max(1, Math.min(500, limit));
            JSONObject scan = scanLocalApksForProvider(MCP_ROOT, true, 3, limit);
            JSONArray found = scan == null ? null : scan.optJSONArray("items");
            JSONArray list = new JSONArray();
            if (found != null) {
                for (int i = 0; i < found.length(); i++) {
                    JSONObject it = found.optJSONObject(i);
                    if (it == null) continue;
                    String abs = it.optString("path", "");
                    list.put(new JSONObject()
                            .put("path", relativeToMcpRoot(abs))
                            .put("name", it.optString("name", new File(abs).getName()))
                            .put("sizeBytes", it.optLong("sizeBytes", 0)));
                }
            }
            return new JSONObject().put("ok", true).put("sourceType", "LOCAL_DISCOVERY_FALLBACK")
                    .put("count", list.length()).put("items", list)
                    .put("prefix", directoryToMcpPrefix(MCP_ROOT))
                    .put("note", "上游 MCP 未提供该工具；已用本地索引合成，path 可直接作为 mt_apk_open 的相对路径");
        } catch (Throwable t) {
            return null;
        }
    }

    /** /storage/emulated/0/MT2/mcp/x.apk -> mcp/x.apk (workspace-relative form mt_apk_open wants). */
    private static String relativeToMcpRoot(String abs) {
        if (abs == null || abs.isEmpty()) return "";
        String root = MCP_ROOT.endsWith("/") ? MCP_ROOT : MCP_ROOT + "/";
        return abs.startsWith(root) ? "mcp/" + abs.substring(root.length()) : abs;
    }

    static boolean orchestratorToolAllowed(String name){
        if(name==null||name.trim().isEmpty()) return false;
        for(ToolDef t:cachedTools) if(t!=null && name.equals(t.publicName)) return true;
        return false;
    }

    static JSONArray orchestratorToolCatalog() {
        JSONArray out=new JSONArray();
        try {
            refreshTools();
            for(ToolDef t:cachedTools){
                if(t==null) continue;
                JSONObject x=new JSONObject().put("name",t.publicName).put("realName",t.realName)
                    .put("server",t.server).put("description",t.description==null?"":t.description)
                    .put("readOnly",isOrchestratorReadOnly(t.publicName));
                if(t.schema!=null) x.put("inputSchema",t.schema);
                out.put(x);
            }
        }catch(Throwable e){ log("orchestratorToolCatalog: "+e); }
        return out;
    }
    private static boolean isOrchestratorReadOnly(String n){
        if(n==null) return false; String x=n.toLowerCase(Locale.ROOT);
        return x.startsWith("apk_") || x.startsWith("local_apk_") || x.equals("project_search") ||
               x.equals("project_trace") || x.equals("project_status") || x.equals("shizuku_status") ||
               x.equals("storage_status") || x.equals("mcp_tasks_get");
    }

    private static ToolDef findTool(String publicName) {
        if (publicName == null) return null;
        String q = publicName.trim();
        if (q.isEmpty()) return null;
        for (ToolDef t:cachedTools) if(t.publicName.equals(q) || t.realName.equals(q)) return t;
        String ql = q.toLowerCase(Locale.ROOT);
        // Resolve user/model aliases such as “自定义服务器/mt_apk_open”,
        // “自定义服务器:mt_apk_open”, or “自定义服务器.mt_apk_open”.
        for (ToolDef t:cachedTools) {
            if (t == null || "builtin".equals(t.server)) continue;
            String server = t.server == null ? "" : t.server.trim().toLowerCase(Locale.ROOT);
            String real = t.realName == null ? "" : t.realName.trim().toLowerCase(Locale.ROOT);
            if (!server.isEmpty() && !real.isEmpty()) {
                if (ql.equals(server + "/" + real) || ql.equals(server + ":" + real) || ql.equals(server + "." + real)) return t;
                if (ql.equals(server + "_" + real)) return t;
            }
        }
        // Case-insensitive exact match on public/real name. Models frequently normalize
        // case or echo the tool name exactly as advertised by the remote server.
        for (ToolDef t:cachedTools) {
            if (t == null) continue;
            String pn = t.publicName == null ? "" : t.publicName.toLowerCase(Locale.ROOT);
            String rn = t.realName == null ? "" : t.realName.toLowerCase(Locale.ROOT);
            if ((!pn.isEmpty() && ql.equals(pn)) || (!rn.isEmpty() && ql.equals(rn))) return t;
        }
        // Bare remote name: the model may return only the server's advertised name
        // (realName) without the generated mcp_<id>_ prefix, or with a different prefix.
        ToolDef unique = null; int matches = 0;
        for (ToolDef t:cachedTools) {
            if (t == null || "builtin".equals(t.server)) continue;
            String rn = t.realName == null ? "" : t.realName.toLowerCase(Locale.ROOT);
            String pn = t.publicName == null ? "" : t.publicName.toLowerCase(Locale.ROOT);
            boolean hit = (!rn.isEmpty() && (ql.equals(rn) || ql.endsWith("_"+rn) || ql.endsWith("/"+rn)))
                    || (!pn.isEmpty() && ql.endsWith("_"+pn));
            if (hit) { unique = t; matches++; }
        }
        if (matches == 1) return unique;
        return null;
    }

    private static JSONObject buildArgs(ToolDef t, String text, String path) {
        JSONObject a=new JSONObject();
        try {
            JSONObject props=t.schema==null?null:t.schema.optJSONObject("properties");
            if(props!=null){
                Iterator<String> it=props.keys();
                while(it.hasNext()){
                    String k=it.next(); String lk=k.toLowerCase(Locale.ROOT);
                    if(path!=null && (lk.equals("path")||lk.equals("file")||lk.equals("filepath")||lk.equals("file_path")||lk.equals("apk")||lk.equals("input"))) a.put(k,path);
                    else if(text!=null && (lk.equals("query")||lk.equals("keyword")||lk.equals("search")||lk.equals("pattern"))) a.put(k,text);
                    else if(lk.equals("action") && text!=null) a.put(k,text);
                }
            }
        } catch(Throwable ignored) {}
        return a;
    }

    private static String findAttachmentPath(Object[] args, int depth) {
        if(args==null) return null;
        for(Object a:args){ String x=findAttachmentPath0(a,depth,new IdentityHashMap<Object,Boolean>()); if(x!=null)return x; }
        return null;
    }
    private static String findAttachmentPath0(Object o,int depth,IdentityHashMap<Object,Boolean> seen){
        if(o==null||depth<0||seen.put(o,Boolean.TRUE)!=null)return null;
        if(o instanceof String){String s=(String)o; if(s.matches(".*\\.(?i:apk|xapk|apks|dex|so|jar|zip|java|kt|smali|xml|json|txt)$"))return s; return null;}
        if(o instanceof Iterable){for(Object x:(Iterable<?>)o){String r=findAttachmentPath0(x,depth-1,seen);if(r!=null)return r;}return null;}
        if(o.getClass().isArray())return null;
        try{
            for(Field f:o.getClass().getDeclaredFields()){
                String n=f.getName().toLowerCase(Locale.ROOT);
                // Attachment containers in obfuscated builds often have meaningless field names.
                // At this shallow depth it is safe to inspect all object fields, but only return
                // strings that look like ordinary project/attachment filenames.
                if(f.isSynthetic() || "this$0".equals(n)) continue;
                f.setAccessible(true); Object v=f.get(o);
                String r=findAttachmentPath0(v,depth-1,seen); if(r!=null)return r;
            }
        }catch(Throwable ignored){}
        return null;
    }

    private static JSONArray openAiTools(List<ToolDef> tools) {
        JSONArray a = new JSONArray();
        if (tools == null) return a;
        for (ToolDef t : tools) {
            try { a.put(t.openAi()); } catch (Throwable ignored) {}
        }
        return a;
    }

    private static synchronized void refreshTools() {
        refreshTools(false);
    }

    /**
     * Discover the MCP tool catalog.
     *
     * @param force when false, a recent successful refresh is reused (throttled) so the
     *              native agent loop never blocks on a slow/unreachable MCP server.
     *
     * Discovery failures never erase previously discovered external tools: the last good
     * external catalog is retained for {@link #EXTERNAL_TOOLS_TTL_MS}. This is what makes
     * "外部 MCP 工具无法调用" recoverable instead of silently removing every external tool.
     */
    private static synchronized void refreshTools(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && lastRefreshAt > 0 && now - lastRefreshAt < REFRESH_THROTTLE_MS
                && !cachedTools.isEmpty()) {
            return;
        }
        lastRefreshAt = now;
        List<ToolDef> all = new ArrayList<>();
        int external=0;
        Map<String,ToolDef> discoveredExternal = new LinkedHashMap<>();
        for (ServerDef s : serverDefs()) {
            JSONObject st = new JSONObject();
            try {
                st.put("name", s.name).put("url", s.url).put("id", s.id);
            } catch (Throwable ignored) {}
            try {
                log("[MCP] discover start server="+s.name+" id="+s.id+" url="+s.url);
                // Modern-first: server/discover is optional, so tools/list is the
                // real capability probe. If modern tools/list is rejected, use the
                // standard legacy initialize/session flow for older MCP servers.
                log("[MCP] tools/list start server="+s.name+" id="+s.id+" url="+s.url);
                String cursor = null;
                int serverCount = 0;
                do {
                    JSONObject params = new JSONObject();
                    if (cursor != null && !cursor.isEmpty()) params.put("cursor", cursor);
                    JSONObject r = rpc(s, "tools/list", params);
                    JSONObject result = r.optJSONObject("result");
                    JSONArray a = result == null ? null : result.optJSONArray("tools");
                    if (a == null) { log("[MCP] tools/list server="+s.name+" returned no result.tools: "+shorten(r.toString(),1000)); break; }
                    serverCount += a.length();
                    for (int i=0;i<a.length();i++) {
                        JSONObject x=a.optJSONObject(i); if(x==null)continue;
                        String name=x.optString("name","").trim(); if(name.isEmpty())continue;
                        String publicName="mcp_"+s.id+"_"+name.replaceAll("[^A-Za-z0-9_\\u4e00-\\u9fff]","_");
                        JSONObject schema=x.optJSONObject("inputSchema");
                        if(schema==null) schema=new JSONObject().put("type","object");
                        ToolDef td = new ToolDef(publicName,s.name,s.id,name,x.optString("description",""),schema);
                        all.add(td);
                        discoveredExternal.put(publicName, td);
                        external++;
                    }
                    cursor = result == null ? null : result.optString("nextCursor", null);
                } while(cursor != null && !cursor.isEmpty() && serverCount < 1000);
                log("[MCP] tools/list server="+s.name+" id="+s.id+" count="+serverCount);
                try { st.put("ok", true).put("tools", serverCount).put("at", System.currentTimeMillis()); } catch (Throwable ignored) {}
            } catch (Throwable e) {
                log("[MCP] tools/list server="+s.name+" FAILED: "+e);
                try { st.put("ok", false).put("tools", 0).put("error", shorten(String.valueOf(e),700)).put("at", System.currentTimeMillis()); } catch (Throwable ignored) {}
            }
            serverStatus.put(s.id, st);
        }
        // Retain the last good external catalog when this refresh discovered no external
        // tools at all (server down / not started / transient network failure).
        boolean discoveredAnyExternal = !discoveredExternal.isEmpty();
        if (discoveredAnyExternal) {
            lastExternalTools = discoveredExternal;
            lastExternalToolsAt = System.currentTimeMillis();
        } else if (!lastExternalTools.isEmpty()
                && System.currentTimeMillis() - lastExternalToolsAt < EXTERNAL_TOOLS_TTL_MS) {
            for (ToolDef t : lastExternalTools.values()) {
                if (t == null) continue;
                all.add(t);
                external++;
            }
            log("[MCP] external discovery empty; retaining "+lastExternalTools.size()
                    +" cached external tool(s) (age="+(System.currentTimeMillis()-lastExternalToolsAt)+"ms)");
        }
        try {
            // Stable core MCP compatibility surface requested by the YuanBao Agent.
            // These names are implemented locally and remain available even when the
            // external APK server exposes a different naming convention.
            JSONObject locatorSchema=new JSONObject().put("type","object").put("properties",new JSONObject().put("locator",new JSONObject().put("type","string")).put("temporary",new JSONObject().put("type","boolean"))).put("required",new JSONArray().put("locator"));
            all.add(new ToolDef("apk_open","builtin","apk_open","打开本地 APK，返回短期句柄；locator 支持 MCP 相对路径或 file:// URI",locatorSchema));
            JSONObject handleSchema=new JSONObject().put("type","object").put("properties",new JSONObject().put("handle",new JSONObject().put("type","string"))).put("required",new JSONArray().put("handle"));
            JSONObject zipSchema=new JSONObject().put("type","object").put("properties",new JSONObject().put("handle",new JSONObject().put("type","string")).put("prefix",new JSONObject().put("type","string")).put("limit",new JSONObject().put("type","integer"))).put("required",new JSONArray().put("handle"));
            all.add(new ToolDef("apk_list_zip","builtin","apk_list_zip","列出 APK ZIP 条目，不全量解压",zipSchema));
            all.add(new ToolDef("apk_read_manifest","builtin","apk_read_manifest","读取 APK AndroidManifest.xml",handleSchema));
            all.add(new ToolDef("apk_list_dex","builtin","apk_list_dex","列出 classes*.dex",handleSchema));
            JSONObject dexReadSchema=new JSONObject().put("type","object").put("properties",new JSONObject().put("handle",new JSONObject().put("type","string")).put("dex",new JSONObject().put("type","string")).put("maxClasses",new JSONObject().put("type","integer"))).put("required",new JSONArray().put("handle"));
            all.add(new ToolDef("apk_read_dex","builtin","apk_read_dex","解析指定 DEX 的真实类/方法索引",dexReadSchema));
            JSONObject decompSchema=new JSONObject().put("type","object").put("properties",new JSONObject().put("handle",new JSONObject().put("type","string")).put("tool",new JSONObject().put("type","string")).put("output",new JSONObject().put("type","string"))).put("required",new JSONArray().put("handle"));
            all.add(new ToolDef("apk_decompile","builtin","apk_decompile","调用已安装的 JADX/apktool 工具进行受控反编译；工具不存在时明确返回 unavailable",decompSchema));
            all.add(new ToolDef("apk_close","builtin","apk_close","关闭 APK 句柄",handleSchema));
            JSONObject shellSchemaCompat=new JSONObject().put("type","object").put("properties",new JSONObject().put("command",new JSONObject().put("type","string")).put("timeout_ms",new JSONObject().put("type","integer"))).put("required",new JSONArray().put("command"));
            // shell_exec is already registered below; schema remains compatible with timeout_ms too.
            JSONObject memSchema=new JSONObject().put("type","object").put("properties",new JSONObject().put("project",new JSONObject().put("type","string")).put("content",new JSONObject().put("type","string"))).put("required",new JSONArray().put("project").put("content"));
            JSONObject readSchema=new JSONObject().put("type","object").put("properties",new JSONObject().put("project",new JSONObject().put("type","string"))).put("required",new JSONArray().put("project"));
            all.add(new ToolDef("project_memory_read","builtin","project_memory_read","读取指定项目的持久记忆",readSchema));
            all.add(new ToolDef("project_memory_write","builtin","project_memory_write","保存/更新指定项目的持久记忆",memSchema));
            JSONObject apkDiscoverSchema = new JSONObject()
                    .put("type", "object")
                    .put("properties", new JSONObject()
                            .put("directory", new JSONObject().put("type", "string"))
                            .put("recursive", new JSONObject().put("type", "boolean"))
                            .put("maxDepth", new JSONObject().put("type", "integer"))
                            .put("limit", new JSONObject().put("type", "integer")))
                    .put("required", new JSONArray().put("directory"));
            all.add(new ToolDef("local_apk_discover", "builtin", "local_apk_discover",
                    "扫描 Android 文件系统目录中的 APK；与 MCP workspace/mt:// 路径分离，只用于发现本地 APK，不会把绝对路径直接传给 mt_apk_open。", apkDiscoverSchema));
            JSONObject apkAnalyzeSchema = new JSONObject()
                    .put("type", "object")
                    .put("properties", new JSONObject()
                            .put("path", new JSONObject().put("type", "string"))
                            .put("query", new JSONObject().put("type", "string")))
                    .put("required", new JSONArray().put("path"));
            all.add(new ToolDef("local_apk_analyze", "builtin", "local_apk_analyze",
                    "安全分析已发现的本地 APK；使用 ZipFile central directory + PackageManager 元数据，不完整解压，不调用 mt_apk_open，适合超大 APK。", apkAnalyzeSchema));
            JSONObject apkInspectSchema = new JSONObject()
                    .put("type", "object")
                    .put("properties", new JSONObject()
                            .put("path", new JSONObject().put("type", "string"))
                            .put("operation", new JSONObject().put("type", "string").put("enum", new JSONArray().put("overview").put("entries").put("search").put("read_entry").put("dex_names")))
                            .put("query", new JSONObject().put("type", "string"))
                            .put("entry", new JSONObject().put("type", "string"))
                            .put("maxResults", new JSONObject().put("type", "integer")));
            all.add(new ToolDef("local_apk_inspect", "builtin", "local_apk_inspect",
                    "继续分析当前本地 APK；按需查看 ZIP 条目、DEX 名称、搜索条目或读取指定条目，绝不全量解压。", apkInspectSchema));
            JSONObject fsPath = new JSONObject().put("type", "string");
            JSONObject fsRead = new JSONObject().put("type", "object").put("properties", new JSONObject().put("path",fsPath).put("offset",new JSONObject().put("type","integer")).put("maxBytes",new JSONObject().put("type","integer"))).put("required",new JSONArray().put("path"));
            JSONObject fsWrite = new JSONObject().put("type", "object").put("properties", new JSONObject().put("path",fsPath).put("content",new JSONObject().put("type","string"))).put("required",new JSONArray().put("path").put("content"));
            JSONObject fsBasic = new JSONObject().put("type", "object").put("properties", new JSONObject().put("path",fsPath)).put("required",new JSONArray().put("path"));
            all.add(new ToolDef("fs_list","builtin","fs_list","列出安全允许目录中的文件",fsBasic));
            all.add(new ToolDef("fs_read","builtin","fs_read","读取本地文件，默认限制大小",fsRead));
            all.add(new ToolDef("fs_write","builtin","fs_write","写入本地工作文件；仅允许工作目录/模块私有目录",fsWrite));
            all.add(new ToolDef("fs_mkdir","builtin","fs_mkdir","创建工作目录",fsBasic));
            JSONObject fsCopy = new JSONObject().put("type","object").put("properties",new JSONObject().put("src",fsPath).put("dst",fsPath)).put("required",new JSONArray().put("src").put("dst"));
            all.add(new ToolDef("fs_copy","builtin","fs_copy","复制工作区文件",fsCopy));
            JSONObject apkPathSchema = new JSONObject().put("type","object").put("properties",new JSONObject().put("path",new JSONObject().put("type","string"))).put("required",new JSONArray().put("path"));
            all.add(new ToolDef("apk_manifest", "builtin", "apk_manifest", "真实读取 APK AndroidManifest.xml；文本 XML 直接返回，二进制 AXML 返回结构化诊断与 PackageManager 元数据", apkPathSchema));
            JSONObject dexIndexSchema = new JSONObject().put("type","object").put("properties",new JSONObject().put("path",new JSONObject().put("type","string")).put("maxClasses",new JSONObject().put("type","integer"))).put("required",new JSONArray().put("path"));
            all.add(new ToolDef("apk_dex_index", "builtin", "apk_dex_index", "真实解析 classes*.dex 的 DEX header、type/string/class_defs 并生成类索引", dexIndexSchema));
            all.add(new ToolDef("apk_resource_index", "builtin", "apk_resource_index", "真实扫描 APK resources、assets、native libs、META-INF 等 ZIP 条目", apkPathSchema));
            JSONObject dexSym = new JSONObject().put("type","object").put("properties",new JSONObject().put("path",new JSONObject().put("type","string")).put("class",new JSONObject().put("type","string")).put("query",new JSONObject().put("type","string")).put("maxResults",new JSONObject().put("type","integer"))).put("required",new JSONArray().put("path"));
            all.add(new ToolDef("apk_dex_symbols","builtin","apk_dex_symbols","真实解析 DEX field_ids/method_ids/class_data，返回字段、方法、code_off、访问标志和类关系",dexSym));
            all.add(new ToolDef("apk_manifest_decoded","builtin","apk_manifest_decoded","真实解码二进制 AndroidManifest AXML 的字符串池、标签、属性并生成可读 XML",apkPathSchema));
            all.add(new ToolDef("apk_resource_refs","builtin","apk_resource_refs","扫描真实 Manifest/res XML 中的资源 ID 引用，并保留无法从 resources.arsc 解析出的引用为 unresolved",apkPathSchema));
            JSONObject agentSchema=new JSONObject().put("type","object").put("properties",new JSONObject().put("path",new JSONObject().put("type","string")).put("query",new JSONObject().put("type","string")).put("maxEntries",new JSONObject().put("type","integer")).put("maxRounds",new JSONObject().put("type","integer")).put("maxTargets",new JSONObject().put("type","integer"))).put("required",new JSONArray().put("path"));
            all.add(new ToolDef("agent_analyze_apk","builtin","agent_analyze_apk","真实结果驱动的只读 Agent Loop：根据上一工具真实结果选择下一分析目标，支持多轮方法/XRef/CFG/资源分析并持久化任务状态",agentSchema));
            JSONObject taskSchema=new JSONObject().put("type","object").put("properties",new JSONObject().put("taskId",new JSONObject().put("type","string"))).put("required",new JSONArray().put("taskId"));
            all.add(new ToolDef("mcp_tasks_get","builtin","mcp_tasks_get","读取本地 MCP durable task 状态；与 Streamable HTTP tasks/get 对应",taskSchema));
            all.add(new ToolDef("mcp_tasks_cancel","builtin","mcp_tasks_cancel","取消本地 MCP durable task；取消是协作式的，正在运行的分析可能在当前安全边界结束后才停止",taskSchema));
            JSONObject xrefSchema=new JSONObject().put("type","object").put("properties",new JSONObject().put("path",new JSONObject().put("type","string")).put("query",new JSONObject().put("type","string")).put("kind",new JSONObject().put("type","string").put("enum",new JSONArray().put("all").put("method").put("field").put("string").put("type"))).put("maxResults",new JSONObject().put("type","integer"))).put("required",new JSONArray().put("path"));
            all.add(new ToolDef("apk_dex_xref","builtin","apk_dex_xref","从真实 DEX code_item 指令建立字符串、类型、方法引用和方法调用边，并支持关键字反查",xrefSchema));
            JSONObject methodSchema=new JSONObject().put("type","object").put("properties",new JSONObject().put("path",new JSONObject().put("type","string")).put("class",new JSONObject().put("type","string")).put("method",new JSONObject().put("type","string")).put("methodKey",new JSONObject().put("type","string")).put("maxInstructions",new JSONObject().put("type","integer"))).put("required",new JSONArray().put("path"));
            all.add(new ToolDef("apk_method_read","builtin","apk_method_read","读取真实 DEX code_item 方法体、寄存器/参数/指令与引用，不生成伪造源码",methodSchema));
            all.add(new ToolDef("apk_method_cfg","builtin","apk_method_cfg","根据真实 DEX branch/return 指令构建有界控制流基本块和边",methodSchema));
            JSONObject pathSchema=new JSONObject().put("type","object").put("properties",new JSONObject().put("path",new JSONObject().put("type","string")).put("from",new JSONObject().put("type","string")).put("to",new JSONObject().put("type","string")).put("maxDepth",new JSONObject().put("type","integer")).put("maxPaths",new JSONObject().put("type","integer"))).put("required",new JSONArray().put("path").put("from").put("to"));
            all.add(new ToolDef("apk_call_path","builtin","apk_call_path","在真实 DEX 方法调用图上执行有界 DFS，返回实际可达调用路径，不猜测不存在的调用",pathSchema));
            JSONObject semSchema=new JSONObject().put("type","object").put("properties",new JSONObject().put("path",new JSONObject().put("type","string")).put("class",new JSONObject().put("type","string")).put("method",new JSONObject().put("type","string")).put("methodKey",new JSONObject().put("type","string")).put("maxInstructions",new JSONObject().put("type","integer"))).put("required",new JSONArray().put("path"));
            all.add(new ToolDef("apk_method_semantics","builtin","apk_method_semantics","基于真实 DEX method prototype/code_item 解析方法签名、参数、寄存器、字面量及字符串/字段/类型/方法引用；未知格式不猜测",semSchema));
            all.add(new ToolDef("apk_entry_points","builtin","apk_entry_points","把真实 AndroidManifest 组件解析为实际 DEX class descriptor，不猜测不存在的类",apkPathSchema));
            all.add(new ToolDef("apk_entry_point_methods","builtin","apk_entry_point_methods","在真实 Manifest 组件类的 DEX class_data 中查找实际生命周期方法；不存在的方法明确标记 present=false",apkPathSchema));
            JSONObject planSchema=new JSONObject().put("type","object").put("properties",new JSONObject().put("path",new JSONObject().put("type","string")).put("query",new JSONObject().put("type","string"))).put("required",new JSONArray().put("path"));
            all.add(new ToolDef("apk_analysis_plan","builtin","apk_analysis_plan","根据真实 Manifest 入口与 DEX 方法建立下一步只读分析计划，并返回可直接继续执行的真实工具参数",planSchema));
            JSONObject searchSchema=new JSONObject().put("type","object").put("properties",new JSONObject().put("project",new JSONObject().put("type","string")).put("query",new JSONObject().put("type","string")).put("maxResults",new JSONObject().put("type","integer"))).put("required",new JSONArray().put("project").put("query"));
            all.add(new ToolDef("project_search","builtin","project_search","在已经生成的逆向项目真实文件中搜索类、方法、Manifest、资源索引和报告",searchSchema));
            all.add(new ToolDef("project_trace","builtin","project_trace","读取已生成的 dex-xref 并对指定方法执行有界调用链追踪",pathSchema));
            JSONObject genSchema = new JSONObject().put("type","object").put("properties",new JSONObject().put("project",new JSONObject().put("type","string")).put("apk",new JSONObject().put("type","string"))).put("required",new JSONArray().put("project").put("apk"));
            all.add(new ToolDef("project_generate", "builtin", "project_generate", "生成真实 APK 逆向项目索引文件，不伪造 Java/Smali 反编译源码", genSchema));
            JSONObject projectArg = new JSONObject().put("type","object").put("properties",new JSONObject().put("project",new JSONObject().put("type","string"))).put("required",new JSONArray().put("project"));
            JSONObject storage = new JSONObject().put("type","object").put("properties",new JSONObject().put("action",new JSONObject().put("type","string").put("enum",new JSONArray().put("status"))));
            all.add(new ToolDef("storage_status","builtin","storage_status","查看 SAF 工作目录是否已由用户授权挂载",storage));
            JSONObject projectRun = new JSONObject().put("type","object").put("properties",new JSONObject().put("project",new JSONObject().put("type","string")).put("apk",new JSONObject().put("type","string")).put("name",new JSONObject().put("type","string")));
            all.add(new ToolDef("project_run","builtin","project_run","真实执行 APK 项目任务：创建项目、分析 APK、记录 DEX、生成 report/summary.md，并持续写入 checkpoint",projectRun));
            JSONObject autonomous = new JSONObject().put("type","object").put("properties",new JSONObject()
                    .put("apk",new JSONObject().put("type","string")).put("name",new JSONObject().put("type","string"))
                    .put("query",new JSONObject().put("type","string")).put("decompile",new JSONObject().put("type","boolean"))
                    .put("maxRounds",new JSONObject().put("type","integer"))).put("required",new JSONArray().put("apk"));
            all.add(new ToolDef("autonomous_reverse","builtin","autonomous_reverse","启动持久化自动逆向任务：发现/校验 APK、建立项目、DEX/Manifest/资源/XRef/字符串与网络证据分析、可用时执行 JADX/apktool、生成结构化报告；支持断点续跑，不伪造结果",autonomous));
            all.add(new ToolDef("project_cancel","builtin","project_cancel","取消真实运行中的项目任务并写入可恢复 checkpoint",projectArg));
            JSONObject shell = new JSONObject().put("type","object").put("properties",new JSONObject().put("command",new JSONObject().put("type","string")).put("cwd",new JSONObject().put("type","string")).put("timeoutMs",new JSONObject().put("type","integer"))).put("required",new JSONArray().put("command"));
            all.add(new ToolDef("shell_exec","builtin","shell_exec","优先通过已授权 Shizuku 执行本地命令；不可用时明确返回原因，不伪造成功",shell));
            all.add(new ToolDef("shizuku_status","builtin","shizuku_status","检查 Shizuku binder/授权状态（权威结果来自模块进程），不读取任何凭据",new JSONObject().put("type","object").put("properties",new JSONObject())));
            all.add(new ToolDef("bridge_status","builtin","bridge_status",
                    "报告工具调用实际走哪条通道（uid2000 守护进程 / 模块 App 回环 / 不通）、执行者 uid/pid 与真实往返毫秒；用于验证透明桥接是否生效",
                    new JSONObject().put("type","object").put("properties",new JSONObject())));
            // ---- Shizuku-backed privileged filesystem (Android 11+ scoped storage bypass) ----
            all.add(new ToolDef("android_storage_status","builtin","android_storage_status",
                    "报告模块进程是否具备所有文件访问权限，以及 Shizuku 是否可用；并探测 /sdcard 等关键根目录的真实可读性",
                    new JSONObject().put("type","object").put("properties",new JSONObject())));
            all.add(new ToolDef("shizuku_request","builtin","shizuku_request",
                    "唤起模块界面以向用户请求 Shizuku 授权（授权对话框必须由前台 Activity 触发）",
                    new JSONObject().put("type","object").put("properties",new JSONObject().put("autoOpen",new JSONObject().put("type","boolean")))));
            JSONObject fsListSchema=new JSONObject().put("type","object").put("properties",new JSONObject()
                    .put("path",new JSONObject().put("type","string"))
                    .put("recursive",new JSONObject().put("type","boolean"))
                    .put("maxDepth",new JSONObject().put("type","integer"))
                    .put("limit",new JSONObject().put("type","integer"))
                    .put("glob",new JSONObject().put("type","string"))).put("required",new JSONArray().put("path"));
            all.add(new ToolDef("android_fs_list","builtin","android_fs_list",
                    "通过 Shizuku shell（uid 2000）真实枚举 /sdcard 等路径，绕过 Android 11+ 分区存储限制；返回真实 name/path/size/mtime/mode",
                    fsListSchema));
            all.add(new ToolDef("android_fs_stat","builtin","android_fs_stat",
                    "通过 Shizuku shell 读取真实文件属性（存在性/类型/大小/mtime/权限/属主）",new JSONObject().put("type","object").put("properties",new JSONObject().put("path",new JSONObject().put("type","string"))).put("required",new JSONArray().put("path"))));
            JSONObject fsReadSchema=new JSONObject().put("type","object").put("properties",new JSONObject()
                    .put("path",new JSONObject().put("type","string"))
                    .put("offset",new JSONObject().put("type","integer"))
                    .put("length",new JSONObject().put("type","integer"))
                    .put("asText",new JSONObject().put("type","boolean"))).put("required",new JSONArray().put("path"));
            all.add(new ToolDef("android_fs_read","builtin","android_fs_read",
                    "通过 Shizuku shell 分片读取真实文件（单次上限 256KB，Binder 事务上限所致）；返回 base64 与 sha256，可选 asText",
                    fsReadSchema));
            JSONObject fsWriteSchema=new JSONObject().put("type","object").put("properties",new JSONObject()
                    .put("path",new JSONObject().put("type","string"))
                    .put("content",new JSONObject().put("type","string"))
                    .put("base64",new JSONObject().put("type","string"))
                    .put("append",new JSONObject().put("type","boolean"))
                    .put("mkdirs",new JSONObject().put("type","boolean"))).put("required",new JSONArray().put("path"));
            all.add(new ToolDef("android_fs_write","builtin","android_fs_write",
                    "通过 Shizuku shell 真实写入手机文件（content 为 UTF-8 文本，或 base64 为二进制）；单次上限 32MB",
                    fsWriteSchema));
            all.add(new ToolDef("android_fs_mkdir","builtin","android_fs_mkdir",
                    "通过 Shizuku shell 真实创建目录（mkdir -p）",new JSONObject().put("type","object").put("properties",new JSONObject().put("path",new JSONObject().put("type","string"))).put("required",new JSONArray().put("path"))));
            all.add(new ToolDef("android_fs_delete","builtin","android_fs_delete",
                    "通过 Shizuku shell 真实删除文件/目录（拒绝关键根路径）",new JSONObject().put("type","object").put("properties",new JSONObject().put("path",new JSONObject().put("type","string")).put("recursive",new JSONObject().put("type","boolean"))).put("required",new JSONArray().put("path"))));
            JSONObject projectWrite = new JSONObject().put("type","object").put("properties",new JSONObject().put("project",new JSONObject().put("type","string")).put("path",fsPath).put("content",new JSONObject().put("type","string"))).put("required",new JSONArray().put("project").put("path").put("content"));
            all.add(new ToolDef("project_write","builtin","project_write","向逆向项目写入文件并更新任务心跳",projectWrite));
            JSONObject projectCreate = new JSONObject().put("type","object").put("properties",new JSONObject().put("name",new JSONObject().put("type","string")).put("root",fsPath)).put("required",new JSONArray().put("name"));
            all.add(new ToolDef("project_create","builtin","project_create","创建逆向项目并初始化 input/output/report/src 与 project.json",projectCreate));
            all.add(new ToolDef("project_status","builtin","project_status","查询项目任务状态",projectArg));
            all.add(new ToolDef("project_resume","builtin","project_resume","恢复最近一次未完成的项目任务",projectArg));
            JSONObject orchestrator = new JSONObject().put("type","object").put("properties",new JSONObject()
                    .put("goal",new JSONObject().put("type","string"))
                    .put("apk",new JSONObject().put("type","string"))
                    .put("name",new JSONObject().put("type","string"))
                    .put("project",new JSONObject().put("type","string"))
                    .put("hints",new JSONObject().put("type","object")))
                    .put("required",new JSONArray().put("goal"));
            all.add(new ToolDef("agent_orchestrate","builtin","agent_orchestrate","持久化自主 Agent 编排器：根据真实工具结果动态选择下一步骤，保存 checkpoint，失败自动重试/修复，任务独立于 UI 请求生命周期",orchestrator));
            JSONObject planner=new JSONObject().put("type","object").put("properties",new JSONObject()
                    .put("goal",new JSONObject().put("type","string"))
                    .put("apk",new JSONObject().put("type","string")))
                    .put("required",new JSONArray().put("goal"));
            all.add(new ToolDef("agent_plan","builtin","agent_plan","返回当前真实 MCP 能力目录和可持久化的 Agent 规划协议；供模型生成动态 plan 后交给 agent_orchestrate 执行",planner));
            all.add(new ToolDef("agent_orchestrate_resume","builtin","agent_orchestrate_resume","恢复指定自主 Agent 任务，从 checkpoint 继续执行",taskSchema));
            all.add(new ToolDef("agent_orchestrate_cancel","builtin","agent_orchestrate_cancel","取消指定自主 Agent 任务并保留 checkpoint",taskSchema));
        } catch(Throwable ignored) {}
        LinkedHashMap<String,ToolDef> dedup=new LinkedHashMap<>(); for(ToolDef t:all)dedup.put(t.publicName,t);
        cachedTools=new ArrayList<>(dedup.values());
        log("[MCP] refresh complete external="+external+" total="+cachedTools.size());
    }

    private static JSONObject toolsResponse() {
        try {
            refreshTools();
            JSONArray a = new JSONArray();
            for (ToolDef t : cachedTools) a.put(t.openAi());
            return new JSONObject().put("object", "list").put("data", a);
        } catch (Throwable e) {
            log("toolsResponse: " + e);
            return error(e.toString());
        }
    }
    private static String buildToolPrompt(List<ToolDef> tools) {
        StringBuilder b = new StringBuilder();
        String readTool = findToolName(tools, "read");
        String writeTool = findToolName(tools, "write");
        String buildTool = findToolName(tools, "build");
        String searchTool = findToolName(tools, "search");
        b.append("你现在处于【本地 Agent / 逆向与 IDE 执行模式】。工具不是示例，也不是建议：网关会真实执行你请求的工具，并把真实结果返回。\n")
         .append("硬约束：需要读取/搜索/检查时必须先调用工具；需要修改文件时必须先读目标内容；修改后必须调用构建/检查/读取工具验证；不能假装已经执行。\n")
         .append("如果任务明确要求‘自动完成/直接修改/构建/修复’，不要只给方案，必须执行可执行的工具调用，直到完成或遇到真实错误。\n")
         .append("优先使用 OpenAI tools/function calling；如果当前模型通道返回 tool_calls，网关会解析 function.name 与 function.arguments，执行真实 MCP tools/call，并把结果以 role=tool + tool_call_id 回填后继续请求模型。\n")
         .append("不要在正常回复中手写 XML/JSON 工具调用协议；只有旧模型兼容路径在必要时才会解析 yb_tool_call/tool_call/call。\n")
         .append("不要把工具调用包在 Markdown 代码块里；arguments 必须是 JSON object。一次可以调用多个工具，但单轮最多 5 个。\n\n")
         .append("【示例 1】用户：读取 McpIdeGateway.java 第 1 行 -> 模型通过 function calling 选择 ").append(readTool).append("。\n")
         .append("【示例 2】用户：搜索项目里所有调用 tools/call 的代码 -> 模型通过 function calling 选择 ").append(searchTool).append("。\n")
         .append("【示例 3】用户：修改这个 Java 文件并验证能否构建 -> 先调用 ").append(readTool).append("，再调用 ").append(writeTool).append("，最后调用 ").append(buildTool).append("，每一步等待真实工具结果。\n\n")
         .append("【可用工具】\n");
        int n = 0;
        for (ToolDef t : tools) {
            if (n++ >= 14) break;
            b.append("- ").append(t.publicName).append(" [server=").append(t.server).append("]: ").append(shorten(t.description, 260));
            if (t.schema != null) b.append(" args=").append(shorten(t.schema.toString(), 480));
            b.append('\n');
        }
        return b.toString();
    }

    private static String findToolName(List<ToolDef> tools, String intent) {
        if (tools == null || tools.isEmpty()) return "工具名";
        ToolDef best = null; int score = -1;
        for (ToolDef t : tools) {
            String h = (t.publicName + " " + t.description).toLowerCase(Locale.ROOT);
            int s = 0;
            if ("read".equals(intent) && (h.contains("read") || h.contains("读取") || h.contains("查看") || h.contains("文件内容"))) s += 10;
            if ("write".equals(intent) && (h.contains("write") || h.contains("写入") || h.contains("修改文件"))) s += 10;
            if ("build".equals(intent) && (h.contains("build") || h.contains("构建") || h.contains("compile"))) s += 10;
            if ("search".equals(intent) && (h.contains("search") || h.contains("搜索") || h.contains("查找"))) s += 10;
            if (s > score) { score = s; best = t; }
        }
        return best == null ? "工具名" : best.publicName;
    }

    private static List<Call> parseCalls(String text) throws Exception {
        List<Call> out = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return out;
        addStructuredCalls(out, text, "yb_tool_call");
        addStructuredCalls(out, text, "tool_call");
        Pattern p = Pattern.compile("<call>\\s*([A-Za-z0-9_\\u4e00-\\u9fff.-]+)\\s*\\(", Pattern.DOTALL);
        Matcher m = p.matcher(text);
        while (m.find()) {
            String json = balancedObject(text, m.end());
            if (json == null) continue;
            try { out.add(new Call(m.group(1), new JSONObject(json))); } catch (Throwable ignored) {}
        }
        // Plain function syntax: tool_name({ ... })
        for (ToolDef t : cachedTools) {
            Pattern fp = Pattern.compile("(?:^|[^A-Za-z0-9_])" + Pattern.quote(t.publicName) + "\\s*\\(", Pattern.DOTALL);
            Matcher fm = fp.matcher(text);
            while (fm.find()) {
                String json = balancedObject(text, fm.end());
                if (json == null) continue;
                try { out.add(new Call(t.publicName, new JSONObject(json))); } catch (Throwable ignored) {}
            }
        }
        // Raw JSON tool call: {"name":"...","arguments":{...}}
        Pattern raw = Pattern.compile("\\{\\s*\"(?:name|tool)\"\\s*:\\s*\"[^\"]+\"\\s*,\\s*\"(?:arguments|args)\"\\s*:", Pattern.DOTALL);
        Matcher rm = raw.matcher(text);
        while (rm.find()) {
            String json = balancedObject(text, rm.start());
            if (json == null) continue;
            try {
                JSONObject o = new JSONObject(json);
                String name = o.optString("name", o.optString("tool", ""));
                JSONObject args = o.optJSONObject("arguments");
                if (args == null) args = o.optJSONObject("args");
                if (!name.isEmpty() && args != null) out.add(new Call(name, args));
            } catch (Throwable ignored) {}
        }
        return dedupCalls(out);
    }

    /** Exact-token, case-insensitive tool-name test: "mt_apk_open" must not match "mt_apk_open_ws". */
    private static boolean namesTool(String text, String name) {
        if (text == null || name == null || name.trim().isEmpty()) return false;
        try {
            return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(name.trim()) + "(?![A-Za-z0-9_])",
                    Pattern.CASE_INSENSITIVE).matcher(text).find();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Never auto-execute a named tool whose name/description marks it as destructive. */
    private static boolean isObviouslyDestructiveTool(ToolDef t) {
        if (t == null) return true;
        String x = ((t.publicName == null ? "" : t.publicName) + " "
                + (t.realName == null ? "" : t.realName) + " "
                + (t.description == null ? "" : t.description)).toLowerCase(Locale.ROOT);
        return x.contains("delete") || x.contains("remove") || x.contains("uninstall")
                || x.contains("rmdir") || x.contains("overwrite")
                || x.contains("删除") || x.contains("移除") || x.contains("覆写");
    }

    /** First quoted/ticked search term in the text, or the word after 搜索/查找/search. */
    private static String firstSearchTerm(String text) {
        if (text == null) return null;
        try {
            Matcher m = Pattern.compile("[`\"'\u201c\u201d]([A-Za-z0-9_.$:/-]{2,60})[`\"'\u201c\u201d]")
                    .matcher(text);
            if (m.find()) return m.group(1);
            m = Pattern.compile("(?:搜索|查找|检索|search|query|grep)\\s*[：:=]?\\s*([A-Za-z0-9_.$:/-]{2,60})",
                    Pattern.CASE_INSENSITIVE).matcher(text);
            if (m.find()) return m.group(1);
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * True when the text names at least one non-destructive catalog tool explicitly.
     * YuanBao's model often describes the call it wants in prose ("正在等待网关执行
     * mt_apk_list_available_apks") without emitting the <tool_call> protocol; the agent loop uses
     * this to execute the tool the model actually named instead of inferring a different one.
     */
    static boolean modelTextNamesTool(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        try {
            refreshTools();
            for (ToolDef t : cachedTools) {
                if (t == null || t.publicName == null || t.publicName.trim().isEmpty()) continue;
                if ("builtin".equals(t.server)) continue;
                if (isObviouslyDestructiveTool(t)) continue;
                if (namesTool(text, t.publicName) || namesTool(text, t.realName)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Fill the properties a tool actually declares with the values implied by the task text, so an
     * explicitly named tool can run even though the model never emitted a structured argument set.
     */
    private static JSONObject synthArgsFromText(ToolDef t, String text, String pathHint) {
        JSONObject a = new JSONObject();
        try {
            JSONObject props = t == null || t.schema == null ? null : t.schema.optJSONObject("properties");
            if (props == null) return a;
            String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
            for (java.util.Iterator<String> it = props.keys(); it.hasNext(); ) {
                String k = it.next();
                String kl = k.toLowerCase(Locale.ROOT);
                if (kl.equals("prefix")) a.put(k, pathHint == null ? "" : directoryToMcpPrefix(pathHint));
                else if (kl.equals("limit") || kl.equals("maxresults") || kl.equals("max_results") || kl.equals("topk")) a.put(k, 50);
                else if (kl.equals("view")) {
                    if (lower.contains("zip_entries") || lower.contains("zip entries")) a.put(k, "zip_entries");
                    else if (lower.contains("dex_classes") || lower.contains("dex classes")) a.put(k, "dex_classes");
                } else if (kl.equals("locator")) {
                    if (lower.contains("manifest") || lower.contains("清单")) a.put(k, "axml:AndroidManifest.xml");
                } else if (kl.equals("query") || kl.equals("keyword") || kl.equals("pattern")) {
                    String q = firstSearchTerm(text);
                    if (q != null) a.put(k, q);
                } else if (kl.equals("querytype")) a.put(k, "literal");
                else if (kl.equals("matchmode")) a.put(k, "contains");
                else if (kl.equals("casesensitive")) a.put(k, false);
                else if (kl.equals("temporary")) a.put(k, true);
                else if (kl.equals("recursive")) a.put(k, true);
                else if (kl.equals("maxdepth")) a.put(k, 3);
                else if ((kl.equals("path") || kl.equals("file") || kl.equals("directory")) && pathHint != null)
                    a.put(k, pathHint);
            }
        } catch (Throwable ignored) {}
        return a;
    }

    private static List<Call> fallbackCalls(String text, List<ToolDef> tools, List<JSONObject> messages) {
        List<Call> out = new ArrayList<>();
        if (text == null) text = "";
        // First, recover JSON objects even if the model omitted the protocol tag.
        try {
            Pattern any = Pattern.compile("\\{\\s*\"(?:name|tool)\"\\s*:", Pattern.DOTALL);
            Matcher m = any.matcher(text);
            while (m.find()) {
                String json = balancedObject(text, m.start());
                if (json == null) continue;
                JSONObject o = new JSONObject(json);
                String n = o.optString("name", o.optString("tool", ""));
                JSONObject a = o.optJSONObject("arguments"); if (a == null) a = o.optJSONObject("args");
                if (!n.isEmpty() && a != null && knownTool(n)) out.add(new Call(n, a));
            }
        } catch (Throwable ignored) {}
        if (!out.isEmpty()) return dedupCalls(out);

        // Explicitly named catalog tool.
        // YuanBao's model frequently names a tool in plain prose ("正在等待网关执行
        // mt_apk_list_available_apks", "调用 mt_apk_open") or with a bare function call instead of
        // the <tool_call> protocol. That text used to parse to zero calls, so the gateway silently
        // executed *different* inferred tools and the model then waited forever for a result of the
        // tool it had actually asked for. Naming a catalog tool is as strong a signal as a tagged
        // call, so honour it and synthesise the arguments from the text/schema.
        try {
            String namedPathHint = extractPathHint(text);
            for (ToolDef t : tools) {
                if (t == null || t.publicName == null || t.publicName.trim().isEmpty()) continue;
                if (isObviouslyDestructiveTool(t)) continue;
                if (!namesTool(text, t.publicName) && !namesTool(text, t.realName)) continue;
                JSONObject a = synthArgsFromText(t, text, namedPathHint);
                out.add(new Call(t.publicName, a));
                log("recovered named tool call -> " + t.publicName + " args=" + shorten(a.toString(), 400));
            }
        } catch (Throwable ignored) {}
        if (!out.isEmpty()) return dedupCalls(out);

        String lower = text.toLowerCase(Locale.ROOT);
        String requestedPath = extractPathHint(text);
        // Conservative natural-language fallback: only map explicit read/search/build/write intent
        // to a registered tool whose name/description clearly matches that intent.
        String intent = lower.contains("读取") || lower.contains("read") || lower.contains("查看文件") ? "read" :
                lower.contains("搜索") || lower.contains("查找") || lower.contains("search") ? "search" :
                lower.contains("构建") || lower.contains("编译") || lower.contains("build") ? "build" :
                lower.contains("写入") || lower.contains("修改文件") || lower.contains("write file") ? "write" : null;
        if (intent == null) return out;
        ToolDef best = null; int score = 0;
        for (ToolDef t : tools) {
            String hay = (t.publicName + " " + t.description).toLowerCase(Locale.ROOT);
            int s = 0;
            if ("read".equals(intent) && (hay.contains("read") || hay.contains("读取") || hay.contains("文件内容"))) s += 5;
            if ("search".equals(intent) && (hay.contains("search") || hay.contains("搜索") || hay.contains("查找"))) s += 5;
            if ("build".equals(intent) && (hay.contains("build") || hay.contains("构建") || hay.contains("compile"))) s += 5;
            if ("write".equals(intent) && (hay.contains("write") || hay.contains("写文件") || hay.contains("修改"))) s += 5;
            if (requestedPath != null && (hay.contains("file") || hay.contains("path") || hay.contains("文件"))) s += 2;
            if (s > score) { score = s; best = t; }
        }
        if (best != null && score >= 5) {
            JSONObject a = new JSONObject();
            try {
                if (requestedPath != null) {
                    a.put("path", requestedPath);
                    a.put("file", requestedPath);
                }
                if ("read".equals(intent)) { a.put("start_line", 1); a.put("end_line", 1); }
            } catch (Throwable ignored) {}
            out.add(new Call(best.publicName, a));
            log("fallback heuristic -> " + best.publicName + " path=" + requestedPath);
        }
        return dedupCalls(out);
    }

    /**
     * Native YuanBao sometimes rewrites the agent prompt as ordinary prose and
     * omits the tool-call tags. For non-destructive intents we can recover a
     * call from the original user request using the already discovered catalog.
     * We never synthesize a tool name that is not in cachedTools.
     */
    static List<String> recoverNativeToolCallsFromPrompt(String originalPrompt) {
        List<String> out = new ArrayList<>();
        if (originalPrompt == null || originalPrompt.trim().isEmpty()) return out;
        try {
            List<Call> calls = fallbackCalls(originalPrompt, cachedTools, Collections.<JSONObject>emptyList());
            for (Call c : calls) {
                ToolDef t = findTool(c.name);
                String hay = t == null ? "" : (t.publicName + " " + t.description).toLowerCase(Locale.ROOT);
                // Native automatic recovery is intentionally limited to read/search/list/analyze
                // style tools. Mutating/build/shell tools still require an explicit model call tag.
                if (hay.contains("write") || hay.contains("修改") || hay.contains("写入") ||
                        hay.contains("build") || hay.contains("构建") || hay.contains("compile") ||
                        hay.contains("shell") || hay.contains("exec") || hay.contains("delete") ||
                        hay.contains("删除")) continue;
                out.add(c.name);
            }
        } catch (Throwable e) {
            log("[NATIVE-AGENT] prompt fallback failed: " + e);
        }
        return out;
    }

    private static String extractPathHint(String text) {
        if (text == null) return null;
        // Android absolute paths are often followed immediately by Chinese prose, e.g.
        // /storage/emulated/0/MT2/mcp/分析这个路径下的apk. Keep the path and stop at CJK punctuation/text.
        Matcher abs = Pattern.compile("(/(?:storage/emulated/0|sdcard|mnt/sdcard)/[^\\s\\u4e00-\\u9fff，。！？；：、]+)").matcher(text);
        if (abs.find()) return abs.group(1);
        Matcher m = Pattern.compile("(?:读取|查看|打开|修改|写入|read|open|edit|write)\\s*[`\"]?([^`\"\\s]+(?:\\.[A-Za-z0-9_]+)?)", Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) return m.group(1);
        Matcher m2 = Pattern.compile("([A-Za-z0-9_./\\-]+\\.(?:apk|xapk|apks|java|kt|kts|xml|smali|json|gradle|txt|md|c|cpp|h|dart))").matcher(text);
        return m2.find() ? m2.group(1) : null;
    }

    private static List<Call> dedupCalls(List<Call> calls) {
        LinkedHashMap<String, Call> uniq = new LinkedHashMap<>();
        for (Call c : calls) if (c != null && knownTool(c.name)) uniq.put(c.name + "\u0000" + c.args.toString(), c);
        if (!uniq.isEmpty()) log("parsed tool calls=" + uniq.size());
        return new ArrayList<>(uniq.values());
    }

    private static void addStructuredCalls(List<Call> out, String text, String tag) {
        Pattern p = Pattern.compile("<" + Pattern.quote(tag) + ">\\s*(.*?)\\s*</" + Pattern.quote(tag) + ">", Pattern.DOTALL);
        Matcher m = p.matcher(text);
        while (m.find()) {
            try {
                JSONObject o = new JSONObject(m.group(1).trim());
                String n = o.optString("name", o.optString("tool", ""));
                JSONObject a = o.optJSONObject("arguments");
                if (a == null) a = o.optJSONObject("args");
                if (!n.isEmpty() && a != null) out.add(new Call(n, a));
            } catch (Throwable ignored) {}
        }
    }

    private static String balancedObject(String s, int start) {
        int i = start;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        if (i >= s.length() || s.charAt(i) != '{') return null;
        int begin = i, depth = 0; boolean str = false, esc = false;
        for (; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (str) {
                if (esc) esc = false;
                else if (ch == '\\') esc = true;
                else if (ch == '"') str = false;
                continue;
            }
            if (ch == '"') { str = true; continue; }
            if (ch == '{') depth++;
            else if (ch == '}') { depth--; if (depth == 0) return s.substring(begin, i + 1); }
        }
        return null;
    }

    private static boolean knownTool(String name) {
        if ("project_memory_read".equals(name) || "project_memory_write".equals(name)) return true;
        for (ToolDef t : cachedTools) if (t.publicName.equals(name)) return true;
        return false;
    }

    private static String stripCalls(String s) {
        if (s == null) return "";
        String x = s.replaceAll("(?s)<yb_tool_call>.*?</yb_tool_call>", "")
                .replaceAll("(?s)<tool_call>.*?</tool_call>", "")
                .replaceAll("(?s)<call>.*?</call>", "");
        // If fallback parsing recovered a raw JSON call, don't leak the protocol object to the user.
        try {
            Matcher m = Pattern.compile("\\{\\s*\"(?:name|tool)\"\\s*:").matcher(x);
            StringBuilder out = new StringBuilder(); int last = 0;
            while (m.find()) {
                String obj = balancedObject(x, m.start());
                if (obj == null) continue;
                try {
                    JSONObject o = new JSONObject(obj);
                    if (!o.has("arguments") && !o.has("args")) continue;
                    out.append(x, last, m.start()); last = m.start() + obj.length();
                } catch (Throwable ignored) {}
            }
            if (last > 0) { out.append(x.substring(last)); x = out.toString(); }
        } catch (Throwable ignored) {}
        return x.trim();
    }

    private static boolean authorized(Map<String,String> headers,String path){
        if("/health".equals(path)) return true;
        // LAN exposure must never be unauthenticated. Loopback can remain open for local IDE use.
        if(!BridgeConfig.lanEnabled() && !BridgeConfig.auth()) return true;
        String h=headers.get("authorization"); if(h==null)return false;
        String prefix="Bearer "; return h.regionMatches(true,0,prefix,0,prefix.length()) && BridgeConfig.apiKey().equals(h.substring(prefix.length()).trim());
    }
    private static JSONObject handleLocalMcp(JSONObject r) throws Exception { return handleLocalMcp(r, java.util.Collections.emptyMap()); }

    private static JSONObject handleLocalMcp(JSONObject r, Map<String,String> headers) throws Exception {
        String method=r.optString("method",""); Object id=r.has("id")?r.opt("id"):JSONObject.NULL;
        // MCP 2026-07-28 Streamable HTTP task routing. For task methods, a modern
        // client must bind Mcp-Name to the durable task id and Mcp-Method to the RPC
        // method. Legacy/local direct callers remain accepted when no protocol header
        // is supplied, so the in-app native bridge does not regress.
        if ("tasks/get".equals(method) || "tasks/update".equals(method) || "tasks/cancel".equals(method)) {
            JSONObject pp = r.optJSONObject("params");
            String taskId = pp == null ? "" : pp.optString("taskId", "");
            String proto = headers == null ? "" : String.valueOf(headers.getOrDefault("mcp-protocol-version", ""));
            String hMethod = headers == null ? "" : String.valueOf(headers.getOrDefault("mcp-method", ""));
            String hName = headers == null ? "" : String.valueOf(headers.getOrDefault("mcp-name", ""));
            if ("2026-07-28".equals(proto)) {
                if (!method.equalsIgnoreCase(hMethod)) return new JSONObject().put("jsonrpc","2.0").put("id",id).put("error",new JSONObject().put("code",-32600).put("message","Mcp-Method does not match JSON-RPC method"));
                if (taskId.isEmpty() || !taskId.equals(hName)) return new JSONObject().put("jsonrpc","2.0").put("id",id).put("error",new JSONObject().put("code",-32602).put("message","Mcp-Name must equal params.taskId for MCP task routing"));
            }
        }
        if ("server/discover".equals(method)) return new JSONObject().put("jsonrpc","2.0").put("id",id).put("result",new JSONObject().put("protocolVersion","2026-07-28").put("capabilities",new JSONObject().put("tools",new JSONObject())).put("serverInfo",new JSONObject().put("name","yuanbao-xposed-mcp").put("version","3.5.16")));
        if ("tools/list".equals(method)){
            refreshTools(); JSONArray a=new JSONArray();
            for(ToolDef t:cachedTools)a.put(new JSONObject().put("name",t.publicName).put("description",t.description).put("inputSchema",t.schema==null?new JSONObject().put("type","object"):t.schema));
            return new JSONObject().put("jsonrpc","2.0").put("id",id).put("result",new JSONObject().put("tools",a));
        }
        if ("tools/call".equals(method)){
       JSONObject p=r.optJSONObject("params");
       final String n = p==null ? "" : p.optString("name","");
       JSONObject aTmp = p==null ? null : p.optJSONObject("arguments");
       final JSONObject a = aTmp==null ? new JSONObject() : aTmp;
        boolean wantsTasks=false;
            try {
                JSONObject meta=p==null?null:p.optJSONObject("_meta");
                JSONObject caps=meta==null?null:meta.optJSONObject("io.modelcontextprotocol/clientCapabilities");
                wantsTasks=caps!=null && caps.has("extensions") && caps.optJSONObject("extensions")!=null && caps.optJSONObject("extensions").has("io.modelcontextprotocol/tasks");
            } catch(Throwable ignored) {}
            // Only genuinely long-running APK Agent analysis is task-augmented. Short local tools
            // retain the ordinary synchronous CallToolResult contract.
            if (wantsTasks && "project_write".equals(n)) {
                JSONObject existingArgs = new JSONObject(a.toString());
                String project = existingArgs.optString("project", ACTIVE_PROJECT);
                String rel = existingArgs.optString("path", "");
                try {
                    File root = safeLocalFile(project);
                    File target = new File(root, rel).getCanonicalFile();
                    if (target.exists()) {
                        JSONObject task = createProjectWriteConfirmationTask(existingArgs);
                        return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", task);
                    }
                } catch (Throwable ignored) {}
            }
            if (wantsTasks && "agent_analyze_apk".equals(n)) {
                final String taskId=McpTaskStore.create(n,a,60L*60L*1000L,1500L);
                final JSONObject taskSeed=McpTaskStore.get(taskId);
                new Thread(() -> {
                    try {
                        if (McpTaskStore.isCancelled(taskId)) return;
                        McpTaskStore.progress(taskId,"Agent APK analysis started",0,"apk_analysis_plan");
                        JSONObject result=callToolDirect(n,a);
                        if (McpTaskStore.isCancelled(taskId)) return;
                        McpTaskStore.complete(taskId,result);
                    } catch(Throwable e) {
                        McpTaskStore.fail(taskId,String.valueOf(e));
                    }
                },"YB-MCP-Task-"+taskId.substring(0,8)).start();
                return new JSONObject().put("jsonrpc","2.0").put("id",id).put("result",taskSeed);
            }
            return new JSONObject().put("jsonrpc","2.0").put("id",id).put("result",callTool(n,a));
        }
        if ("tasks/get".equals(method)) {
            JSONObject p=r.optJSONObject("params"); String taskId=p==null?"":p.optString("taskId","");
            try {
                JSONObject task=McpTaskStore.get(taskId);
                task.put("resultType","complete");
                return new JSONObject().put("jsonrpc","2.0").put("id",id).put("result",task);
            } catch(IllegalArgumentException e) {
                return new JSONObject().put("jsonrpc","2.0").put("id",id).put("error",new JSONObject().put("code",-32602).put("message",e.getMessage()));
            }
        }
        if ("tasks/update".equals(method)) {
            JSONObject p=r.optJSONObject("params"); String taskId=p==null?"":p.optString("taskId","");
            String msg=p==null?"":p.optString("statusMessage","");
            JSONObject inputResponses=p==null?null:p.optJSONObject("inputResponses");
            try {
                JSONObject out;
                if (inputResponses != null) {
                    handleTaskInputUpdate(taskId,inputResponses);
                } else {
                    McpTaskStore.update(taskId,msg);
                }
                return new JSONObject().put("jsonrpc","2.0").put("id",id).put("result",new JSONObject().put("resultType","complete"));
            } catch(IllegalArgumentException e) {
                return new JSONObject().put("jsonrpc","2.0").put("id",id).put("error",new JSONObject().put("code",-32602).put("message",e.getMessage()));
            }
        }
        if ("tasks/cancel".equals(method)) {
            JSONObject p=r.optJSONObject("params"); String taskId=p==null?"":p.optString("taskId","");
            try {
                McpTaskStore.cancel(taskId);
                return new JSONObject().put("jsonrpc","2.0").put("id",id).put("result",new JSONObject().put("resultType","complete"));
            } catch(IllegalArgumentException e) {
                return new JSONObject().put("jsonrpc","2.0").put("id",id).put("error",new JSONObject().put("code",-32602).put("message",e.getMessage()));
            }
        }
        return new JSONObject().put("jsonrpc","2.0").put("id",id).put("error",new JSONObject().put("code",-32601).put("message","method not found"));
    }

    private static JSONObject normalizeToolArguments(JSONObject args, ServerDef server) {
        JSONObject out;
        try {
            out = args == null ? new JSONObject() : new JSONObject(args.toString());
        } catch (Throwable e) {
            log("[MCP] tool arguments JSON copy failed: " + e);
            out = args == null ? new JSONObject() : args;
        }
        try {
            normalizeObjectInPlace(out);
        } catch (Throwable e) {
            log("[MCP] path normalization failed: " + e);
        }
        return out;
    }

    private static void normalizeObjectInPlace(JSONObject o) throws Exception {
        if (o == null) return;
        Iterator<String> it = o.keys();
        List<String> keys = new ArrayList<>();
        while (it.hasNext()) keys.add(it.next());
        for (String k : keys) {
            Object v = o.opt(k);
            if (v instanceof JSONObject) { normalizeObjectInPlace((JSONObject)v); continue; }
            if (v instanceof JSONArray) {
                JSONArray a = (JSONArray)v;
                for (int i=0;i<a.length();i++) { Object x=a.opt(i); if(x instanceof JSONObject) normalizeObjectInPlace((JSONObject)x); }
                continue;
            }
            if (!(v instanceof String) || !isPathLikeArgument(k)) continue;
            String raw = (String)v;
            String resolved = resolveMcpPathStrict(raw);
            log("[MCP] path raw=" + raw);
            log("[MCP] path resolved=" + resolved);
            o.put(k, resolved);
        }
    }

    /**
     * Strict MCP path adapter. MCP workspace paths are relative and MUST NOT contain
     * empty, '.' or '..' segments. Android absolute paths below MCP_ROOT are converted
     * to relative paths; other absolute paths are represented as file:// URIs.
     */
    private static String resolveMcpPathStrict(String raw) throws IOException {
        if (raw == null) throw new IOException("path 不能为空");
        String v = raw.trim();
        if (v.isEmpty()) throw new IOException("path 不能为空");
        if (v.startsWith("content://")) return v;
        if (v.startsWith("file://")) return validateUriPath(v);
        if (v.startsWith("saf://") || v.startsWith("saf:/")) return v;
        boolean absolute = v.startsWith("/");
        String candidate = v.replace('\\','/');
        if (absolute) {
            String root = MCP_ROOT.endsWith("/") ? MCP_ROOT.substring(0,MCP_ROOT.length()-1) : MCP_ROOT;
            if (candidate.equals(root)) return "";
            if (candidate.startsWith(root + "/")) {
                candidate = candidate.substring(root.length()+1);
                return validateRelativeMcpPath(candidate);
            }
            // Extra workspace roots the user explicitly mapped to the MCP server's own
            // workspace model (e.g. a shared folder the MCP server can actually read).
            for (String extra : extraMcpRoots()) {
                if (extra.isEmpty()) continue;
                String er = extra.endsWith("/") ? extra.substring(0, extra.length()-1) : extra;
                if (candidate.equals(er)) return "";
                if (candidate.startsWith(er + "/")) return validateRelativeMcpPath(candidate.substring(er.length()+1));
            }
            // Android shared storage outside the MCP workspace root: the MCP server runs in
            // its own sandbox and cannot read this path. Converting it to file:// produced a
            // silent no-result failure ("调了没回结果"). Fail loudly with an actionable fix.
            if (candidate.startsWith("/storage/emulated/0/") || candidate.startsWith("/sdcard/")) {
                throw new IOException("该路径不在 MCP 工作区根目录 " + MCP_ROOT + " 内，外部 MCP 服务（沙盒内）无法读取 " + v
                        + "。请把文件放到 " + MCP_ROOT + " 下，或在“MCP 工具服务器”设置中把该目录加入额外工作区根。");
            }
            try { return new File(v).getCanonicalFile().toURI().toString(); }
            catch (Throwable e) { throw new IOException("绝对路径无法转换为 file:// URI: " + v, e); }
        }
        return validateRelativeMcpPath(candidate);
    }

    /** User-configured extra roots that map into the external MCP server's workspace model. */
    private static List<String> extraMcpRoots() {
        List<String> out = new ArrayList<>();
        try {
            String csv = BridgeConfig.extraMcpRoots();
            if (csv != null && !csv.trim().isEmpty()) {
                for (String s : csv.split(",")) {
                    String t = s.trim().replace('\\','/');
                    if (!t.isEmpty()) out.add(t);
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static String validateRelativeMcpPath(String p) throws IOException {
        if (p == null || p.isEmpty()) throw new IOException("path 不允许为空");
        String x=p.replace('\\','/');
        if (x.startsWith("/") || x.endsWith("/") || x.contains("//"))
            throw new IOException("path must not contain empty, dot, or parent segments: " + p);
        String[] parts=x.split("/",-1);
        for(String part:parts) {
            if(part.isEmpty() || ".".equals(part) || "..".equals(part))
                throw new IOException("path must not contain empty, dot, or parent segments: " + p);
        }
        return x;
    }

    private static String validateUriPath(String uri) throws IOException {
        try {
            Uri u=Uri.parse(uri);
            String path=u.getPath();
            if(path!=null && path.contains("//")) throw new IOException("URI path contains empty segment");
            if(path!=null) for(String part:path.split("/",-1)) if(".".equals(part)||"..".equals(part)) throw new IOException("URI path must not contain dot/parent segments");
            return uri;
        } catch (IOException e) { throw e; }
        catch(Throwable e) { throw new IOException("非法 URI path: " + uri,e); }
    }

    private static String resolveMcpPath(String raw) {
        try { return resolveMcpPathStrict(raw); }
        catch(Throwable e) { log("[MCP] path rejected raw=" + raw + " error=" + e); return raw; }
    }

    /** Legacy/internal HTTP bridge entry. Native Agent must not use this entry. */
    private static JSONObject callTool(String publicName, JSONObject args) throws Exception {
        return callToolDirect(publicName, args);
    }

    /** Direct Native Agent tool execution. This path never enters local :8318. */
    private static JSONObject createProjectWriteConfirmationTask(JSONObject args) throws Exception {
        final String taskId = McpTaskStore.create("project_write", args, 30L * 60L * 1000L, 1000L);
        final JSONObject req = new JSONObject();
        req.put("confirm", new JSONObject()
                .put("method", "elicitation/create")
                .put("params", new JSONObject()
                        .put("mode", "form")
                        .put("message", "确认写入项目文件：" + args.optString("path", "") + "？")
                        .put("requestedSchema", new JSONObject().put("type", "object")
                                .put("properties", new JSONObject().put("confirmed", new JSONObject().put("type", "boolean")))
                                .put("required", new JSONArray().put("confirmed")))));
        // Task creation itself is returned in the initial working state. The input_required
        // transition happens asynchronously, matching the 2026-07-28 Tasks lifecycle.
        new Thread(() -> {
            try {
                Thread.sleep(50L);
                if (!McpTaskStore.isCancelled(taskId)) McpTaskStore.requireInput(taskId, req, "等待确认后写入项目文件");
            } catch (Throwable ignored) {}
        }, "YB-task-input-" + taskId.substring(0, 8)).start();
        return McpTaskStore.get(taskId);
    }

    private static JSONObject handleTaskInputUpdate(String taskId, JSONObject responses) throws Exception {
        JSONObject task = McpTaskStore.get(taskId);
        String tool = task.optString("tool", "");
        JSONObject applied = McpTaskStore.applyInputResponses(taskId, responses);
        if (!"project_write".equals(tool)) return applied;
        JSONObject confirm = responses == null ? null : responses.optJSONObject("confirm");
        boolean accepted = false;
        if (confirm != null) {
            String action = confirm.optString("action", "");
            JSONObject content = confirm.optJSONObject("content");
            boolean confirmed = content != null && content.optBoolean("confirmed", false);
            accepted = ("accept".equalsIgnoreCase(action) || action.isEmpty()) && confirmed;
        }
        if (!accepted) {
            McpTaskStore.fail(taskId, "用户未确认项目文件写入");
            return McpTaskStore.get(taskId);
        }
        JSONObject args = task.optJSONObject("arguments");
        if (args == null) args = new JSONObject();
        try {
            McpTaskStore.progress(taskId, "已确认，正在写入项目文件", 0, "project_write");
            JSONObject result = projectWrite(args);
            McpTaskStore.complete(taskId, result);
        } catch (Throwable e) {
            McpTaskStore.fail(taskId, String.valueOf(e));
        }
        return McpTaskStore.get(taskId);
    }


    private static File handleFile(String handle) throws Exception {
        if(handle==null||handle.trim().isEmpty()) throw new IOException("handle 不能为空");
        File f=APK_HANDLES.get(handle.trim());
        if(f==null) throw new IOException("未知或已关闭 APK handle: " + handle);
        if(!f.isFile()) { APK_HANDLES.remove(handle); throw new FileNotFoundException("APK 已不存在: "+f); }
        return f;
    }
    private static JSONObject apkOpenHandle(JSONObject a) throws Exception {
        String locator=a.optString("locator","").trim();
        if(locator.isEmpty()) throw new IOException("locator 不能为空");
        String resolved=locator;
        if(!locator.startsWith("file://") && !locator.startsWith("content://") && !locator.startsWith("saf://") && !locator.startsWith("/")) resolved=resolveMcpPathStrict(locator);
        File f;
        if(resolved.startsWith("file://")) f=new File(Uri.parse(resolved).getPath());
        else if(resolved.startsWith("/")) f=new File(resolved);
        else f=safeLocalFile(resolved);
        f=f.getCanonicalFile();
        if(!f.isFile() || !f.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) throw new IOException("APK 不存在或不是 APK: "+resolved);
        String h="apk-"+UUID.randomUUID().toString(); APK_HANDLES.put(h,f);
        return new JSONObject().put("ok",true).put("handle",h).put("locator",locator).put("resolved",f.getCanonicalPath()).put("temporary",a.optBoolean("temporary",true));
    }
    private static JSONObject apkListZipHandle(JSONObject a) throws Exception {
        File f=handleFile(a.optString("handle","")); String prefix=a.optString("prefix",""); int limit=Math.max(1,Math.min(5000,a.optInt("limit",1000))); JSONArray items=new JSONArray();
        ZipFile z=new ZipFile(f,ZipFile.OPEN_READ); try { Enumeration<? extends ZipEntry> en=z.entries(); while(en.hasMoreElements()&&items.length()<limit){ZipEntry e=en.nextElement(); if(!prefix.isEmpty()&&!e.getName().startsWith(prefix))continue; items.put(new JSONObject().put("name",e.getName()).put("directory",e.isDirectory()).put("sizeBytes",e.getSize()).put("compressedSize",e.getCompressedSize()));}} finally {z.close();}
        return new JSONObject().put("ok",true).put("handle",a.optString("handle","")).put("items",items).put("truncated",items.length()>=limit);
    }
    private static JSONObject apkReadManifestHandle(JSONObject a) throws Exception { File f=handleFile(a.optString("handle","")); return apkManifest(new JSONObject().put("path",f.getCanonicalPath())); }
    private static JSONObject apkListDexHandle(JSONObject a) throws Exception { File f=handleFile(a.optString("handle","")); JSONArray d=new JSONArray(); ZipFile z=new ZipFile(f,ZipFile.OPEN_READ); try{Enumeration<? extends ZipEntry> en=z.entries();while(en.hasMoreElements()){ZipEntry e=en.nextElement();if(e.getName().matches("classes(\\d+)?\\.dex"))d.put(new JSONObject().put("name",e.getName()).put("sizeBytes",e.getSize()));}}finally{z.close();} return new JSONObject().put("ok",true).put("handle",a.optString("handle","")).put("dex",d); }
    private static JSONObject apkReadDexHandle(JSONObject a) throws Exception { File f=handleFile(a.optString("handle","")); JSONObject x=apkDexIndex(new JSONObject().put("path",f.getCanonicalPath()).put("maxClasses",Math.max(100,Math.min(50000,a.optInt("maxClasses",20000))))); String dex=a.optString("dex",""); if(dex.isEmpty()) return x; JSONArray all=x.optJSONArray("dexFiles"), keep=new JSONArray(); if(all!=null)for(int i=0;i<all.length();i++){JSONObject q=all.optJSONObject(i);if(q!=null&&dex.equals(q.optString("name")))keep.put(q);} return new JSONObject(x.toString()).put("selectedDex",dex).put("dexFiles",keep); }
    private static JSONObject apkDecompileHandle(JSONObject a) throws Exception {
        File f=handleFile(a.optString("handle","")); String requested=a.optString("tool","").trim().toLowerCase(Locale.ROOT); String tool=requested;
        if(tool.isEmpty()) { File base=applicationContext==null?null:applicationContext.getFilesDir(); if(base!=null){String[] candidates={"jadx","bin/jadx","apktool","bin/apktool"}; for(String c:candidates){File q=new File(base,c);if(q.isFile()&&q.canExecute()){tool=q.getAbsolutePath();break;}}} }
        String output=a.optString("output","").trim();
        if(output.isEmpty()) output=new File(MCP_ROOT,"projects/decompile-"+System.currentTimeMillis()).getCanonicalPath();
        new File(output).mkdirs();
        // App-private toolchain first; if absent, use the already-authorized Shizuku shell
        // as the real-device fallback. This is an execution attempt, never a fabricated success.
        if(tool.isEmpty()) {
            try {
                JSONObject probe=McpIdeGateway.shellExec(new JSONObject().put("command","command -v jadx || command -v apktool || true").put("timeoutMs",5000));
                if(probe.optBoolean("ok",false)) {
                    String found=probe.optString("stdout","").trim();
                    if(!found.isEmpty()) tool=found.split("\\s+")[0];
                }
            } catch(Throwable ignored) {}
        }
        if(tool.isEmpty()) return new JSONObject().put("ok",false).put("available",false).put("reason","未发现 JADX/apktool 可执行文件；已尝试应用私有工具链与 Shizuku PATH 探测").put("apk",f.getCanonicalPath()).put("output",output); if(output.isEmpty()) output=new File(MCP_ROOT,"projects/decompile-"+System.currentTimeMillis()).getCanonicalPath(); new File(output).mkdirs();
        String cmd;
        if(tool.contains("apktool")) cmd=quoteShell(tool)+" d -f "+quoteShell(f.getCanonicalPath())+" -o "+quoteShell(output); else cmd=quoteShell(tool)+" -d "+quoteShell(f.getCanonicalPath())+" -r -ds -o "+quoteShell(output);
        JSONObject r=shellExec(new JSONObject().put("command",cmd).put("timeoutMs",120000).put("cwd",output));
        return r.put("apk",f.getCanonicalPath()).put("output",output).put("tool",tool);
    }
    private static String quoteShell(String x){return "'"+String.valueOf(x).replace("'","'\\''")+"'";}
    private static JSONObject apkCloseHandle(JSONObject a) throws JSONException { String h=a.optString("handle",""); boolean removed=APK_HANDLES.remove(h)!=null; return new JSONObject().put("ok",removed).put("handle",h).put("closed",removed); }

    static JSONObject callToolDirect(String publicName, JSONObject args) throws Exception {
        if ("apk_open".equals(publicName)) return apkOpenHandle(args);
        if ("apk_list_zip".equals(publicName)) return apkListZipHandle(args);
        if ("apk_read_manifest".equals(publicName)) return apkReadManifestHandle(args);
        if ("apk_list_dex".equals(publicName)) return apkListDexHandle(args);
        if ("apk_read_dex".equals(publicName)) return apkReadDexHandle(args);
        if ("apk_decompile".equals(publicName)) return apkDecompileHandle(args);
        if ("apk_close".equals(publicName)) return apkCloseHandle(args);
        if ("local_apk_discover".equals(publicName)) {
            return discoverLocalApks(args);
        }
        if ("local_apk_analyze".equals(publicName)) {
            return analyzeLocalApk(args);
        }
        if ("local_apk_inspect".equals(publicName)) {
            return inspectLocalApk(args);
        }
        if ("apk_manifest".equals(publicName)) return apkManifest(args);
        if ("apk_dex_index".equals(publicName)) return apkDexIndex(args);
        if ("apk_resource_index".equals(publicName)) return apkResourceIndex(args);
        if ("apk_dex_symbols".equals(publicName)) return apkDexSymbols(args);
        if ("apk_dex_xref".equals(publicName)) return apkDexXref(args);
        if ("apk_call_path".equals(publicName)) return apkCallPath(args);
        if ("apk_method_read".equals(publicName)) return apkMethodRead(args);
        if ("apk_method_cfg".equals(publicName)) return apkMethodCfg(args);
        if ("apk_method_semantics".equals(publicName)) return apkMethodSemantics(args);
        if ("apk_entry_points".equals(publicName)) return apkEntryPoints(args);
        if ("apk_entry_point_methods".equals(publicName)) return apkEntryPointMethods(args);
        if ("apk_analysis_plan".equals(publicName)) return apkAnalysisPlan(args);
        if ("apk_manifest_decoded".equals(publicName)) return apkManifestDecoded(args);
        if ("apk_resource_table".equals(publicName)) return apkResourceTable(args);
        if ("apk_resource_refs".equals(publicName)) return apkResourceRefs(args);
        if ("agent_analyze_apk".equals(publicName)) return agentAnalyzeApk(args);
        if ("mcp_tasks_get".equals(publicName)) return new JSONObject().put("resultType","complete").put("task",McpTaskStore.get(args.optString("taskId","")));
        if ("mcp_tasks_cancel".equals(publicName)) { McpTaskStore.cancel(args.optString("taskId","")); return new JSONObject().put("ok",true).put("taskId",args.optString("taskId","")); }
        if ("project_search".equals(publicName)) return projectSearch(args);
        if ("project_trace".equals(publicName)) return projectTrace(args);
        if ("project_generate".equals(publicName)) return projectGenerate(args);
        if ("fs_list".equals(publicName)) return fsList(args);
        if ("fs_read".equals(publicName)) return fsRead(args);
        if ("fs_write".equals(publicName)) return fsWrite(args);
        if ("fs_mkdir".equals(publicName)) return fsMkdir(args);
        if ("fs_copy".equals(publicName)) return fsCopy(args);
        if ("project_create".equals(publicName)) return projectCreate(args);
        if ("project_write".equals(publicName)) return projectWrite(args);
        if ("project_status".equals(publicName)) return projectStatus(args);
        if ("project_resume".equals(publicName)) return projectResume(args);
        if ("agent_plan".equals(publicName)) return AgentOrchestrator.plan(args);
        if ("agent_orchestrate".equals(publicName)) return AgentOrchestrator.start(args);
        if ("agent_orchestrate_resume".equals(publicName)) return AgentOrchestrator.resume(args.optString("taskId",""));
        if ("agent_orchestrate_cancel".equals(publicName)) return AgentOrchestrator.cancel(args.optString("taskId",""));
        if ("agent_e2e".equals(publicName)) return E2EReverseEngine.start(args);
        if ("agent_e2e_status".equals(publicName)) return E2EReverseEngine.status(args);
        if ("agent_e2e_cancel".equals(publicName)) return E2EReverseEngine.cancel(args);
        if ("storage_status".equals(publicName)) return storageStatus(args);
        if ("project_run".equals(publicName)) return projectRun(args);
        if ("autonomous_reverse".equals(publicName)) return autonomousReverse(args);
        if ("project_cancel".equals(publicName)) return projectCancel(args);
        if ("shell_exec".equals(publicName)) return shellExec(args);
        if ("shizuku_status".equals(publicName)) return shizukuStatus();
        if ("bridge_status".equals(publicName)) return bridgeStatus();
        if ("android_storage_status".equals(publicName)) return androidStorageStatus();
        if ("shizuku_request".equals(publicName)) return shizukuRequest(args);
        if ("android_fs_list".equals(publicName)) return androidFsList(args);
        if ("android_fs_stat".equals(publicName)) return androidFsStat(args);
        if ("android_fs_read".equals(publicName)) return androidFsRead(args);
        if ("android_fs_write".equals(publicName)) return androidFsWrite(args);
        if ("android_fs_mkdir".equals(publicName)) return androidFsMkdir(args);
        if ("android_fs_delete".equals(publicName)) return androidFsDelete(args);
        if("project_memory_read".equals(publicName)){
            String project=args.optString("project",BridgeConfig.project());
            return new JSONObject().put("project",project).put("memory",BridgeConfig.memory(project));
        }
        if("project_memory_write".equals(publicName)){
            String project=args.optString("project",BridgeConfig.project()); String content=args.optString("content","");
            BridgeConfig.setMemory(project,content); BridgeConfig.setProject(project);
            return new JSONObject().put("ok",true).put("project",project).put("memoryLength",content.length());
        }
        ToolDef found=findTool(publicName);
        if(found==null)throw new IOException("unknown MCP tool: "+publicName);
        // Safety route: never send an Android filesystem directory/file directly to mt_apk_open.
        // The MCP server's mt_apk_open accepts its own indexed/workspace path model; an
        // absolute /storage/... path must first be resolved through a server-advertised
        // workspace/import mechanism. Do not silently convert it to an MCP prefix.
        if (isMtApkOpenTool(found)) {
            String path = findPathArgument(args);
            if (isAndroidAbsolutePath(path)) {
                throw new IOException("拒绝将 Android 绝对路径直接传给 mt_apk_open；请先通过 local_apk_discover 或 MCP workspace/import 工具建立 MCP 句柄: " + path);
            }
        }

        ServerDef target=findServer(found.server,found.serverId); if(target==null) throw new IOException("MCP server not configured: "+found.server+"/"+found.serverId);
        JSONObject resolvedArgs = normalizeToolArguments(args, target);
        JSONObject params=new JSONObject().put("name",found.realName).put("arguments",resolvedArgs);
        log("[MCP] tool_call name=" + found.realName + " server=" + target.name + " url=" + target.url);
        JSONObject r=null; Throwable last=null; for(int attempt=1;attempt<=3;attempt++){ try{ log("[MCP] tools/call attempt="+attempt+"/3 name="+found.realName); r=rpc(target,"tools/call",params); last=null; break; }catch(Throwable e){ last=e; log("[MCP] tools/call retry="+attempt+" error="+shorten(String.valueOf(e),900)); if(attempt<3) try{Thread.sleep(350L*attempt);}catch(InterruptedException ie){Thread.currentThread().interrupt();throw ie;} }} if(r==null&&last!=null) throw new IOException("MCP tools/call failed after 3 attempts: "+last,last);
        JSONObject result=r.optJSONObject("result");
        log("[MCP] tool_result ok name=" + found.realName + " isError=" + (result != null && result.optBoolean("isError", false)));
        if (result == null) {
            // The server answered the JSON-RPC request but produced no result object.
            // Returning the raw envelope here used to look like "调了没回结果" to the model.
            // Surface it explicitly instead of silently handing back a non-tool payload.
            String raw = r == null ? "" : shorten(r.toString(), 1200);
            throw new IOException("MCP tools/call 已发送但服务器未返回 result（name=" + found.realName
                    + ", server=" + target.name + ", url=" + target.url + "）; raw=" + raw);
        }
        if (result.optBoolean("isError", false)) {
            String msg = extractToolErrorText(result);
            log("[MCP] tool_result isError name=" + found.realName + " msg=" + shorten(msg, 700));
            throw new IOException("MCP 工具返回错误（name=" + found.realName + ", server=" + target.name + "）: " + msg);
        }
        return result;
    }

    /** Extract a human-readable error string from an MCP result.content[] payload. */
    private static String extractToolErrorText(JSONObject result) {
        try {
            JSONArray content = result.optJSONArray("content");
            if (content != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < content.length(); i++) {
                    JSONObject c = content.optJSONObject(i);
                    if (c == null) continue;
                    String t = c.optString("text", "");
                    if (!t.isEmpty()) { if (sb.length() > 0) sb.append("\n"); sb.append(t); }
                }
                if (sb.length() > 0) return shorten(sb.toString(), 1500);
            }
            String s = result.optString("error", "");
            if (!s.isEmpty()) return shorten(s, 1500);
        } catch (Throwable ignored) {}
        return shorten(String.valueOf(result), 1500);
    }

    private static ServerDef findServer(String name,String id){for(ServerDef s:serverDefs())if(s.name.equals(name)&&s.id.equals(id))return s;return null;}
    private static boolean isMtApkOpenTool(ToolDef t) {
        if (t == null) return false;
        String x = (t.publicName + " " + t.realName + " " + t.description).toLowerCase(Locale.ROOT);
        return x.contains("mt_apk_open") || (x.contains("apk") && x.contains("open"));
    }

    private static String findPathArgument(JSONObject args) {
        if (args == null) return null;
        try {
            Iterator<String> it = args.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object ov = args.opt(k);
                if (!(ov instanceof String)) continue;
                String v = ((String) ov).trim();
                String kl = k.toLowerCase(Locale.ROOT);
                if (!v.isEmpty() && (isPathLikeArgument(k) || kl.contains("path") || kl.contains("file") || kl.contains("directory") || kl.contains("uri"))) return v;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String findDirectoryArgument(JSONObject args) {
        if (args == null) return null;
        try {
            Iterator<String> it = args.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object ov = args.opt(k);
                if (!(ov instanceof String)) continue;
                String v = ((String)ov).trim();
                if (v.isEmpty()) continue;
                String kl = k.toLowerCase(Locale.ROOT);
                boolean pathKey = isPathLikeArgument(k) || kl.contains("path") || kl.contains("file") || kl.contains("directory");
                if (!pathKey) continue;
                if (v.endsWith("/") || v.endsWith("\\") || v.equals(MCP_ROOT)
                        || (v.startsWith("/storage/emulated/0/") && !v.matches(".*\\.[A-Za-z0-9_]{2,6}$"))) return v;
            }
        } catch (Throwable ignored) {}
        return null;
    }


    private static JSONObject rpc(ServerDef s,String method,JSONObject params) throws Exception {
        final String requestId=UUID.randomUUID().toString();
        log("[MCP] connecting url=" + s.url);
        if ("tools/call".equals(method)) log("[MCP] tool_call name=" + params.optString("name", ""));
        log("[MCP-REQ] id="+requestId+" serverId="+s.id+" server="+s.name+" method="+method);
        try {
            JSONObject r=postMcpModern(s,method,params,requestId);
            log("[MCP-RESULT] id="+requestId+" serverId="+s.id+" protocol=2026-07-28 method="+method+" http="+lastHttpCode);
            return r;
        } catch(Throwable modernError) {
            log("[MCP] modern call failed server="+s.name+" method="+method+"; legacy fallback: "+shorten(String.valueOf(modernError),500));
            LegacySession legacy = legacyInitialize(s);
            JSONObject r = postMcpLegacy(s, legacy, method, params);
            log("[MCP-RESULT] id="+requestId+" serverId="+s.id+" protocol="+legacy.protocolVersion+" method="+method+" http="+lastHttpCode);
            return r;
        }
    }

    private static final class LegacySession {
        final String sessionId;
        final String protocolVersion;
        final String endpoint;
        LegacySession(String sessionId, String protocolVersion, String endpoint) {
            this.sessionId=sessionId;
            this.protocolVersion=protocolVersion;
            this.endpoint=endpoint;
        }
    }

    private static LegacySession legacyInitialize(ServerDef s) throws Exception {
        // Handshake-era MCP versions must be negotiated explicitly. Try newest first,
        // then older published revisions before falling back to HTTP+SSE endpoint discovery.
        final String[] versions = new String[]{
                "2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05", "2024-10-07"
        };
        Throwable lastError = null;
        for (String requestedVersion : versions) {
            String requestId=UUID.randomUUID().toString();
            JSONObject params=new JSONObject()
                    .put("protocolVersion",requestedVersion)
                    .put("capabilities",new JSONObject())
                    .put("clientInfo",new JSONObject().put("name",s.clientName).put("version","3.4.0"));
            JSONObject req=new JSONObject().put("jsonrpc","2.0").put("id",requestId)
                    .put("method","initialize").put("params",params);

            HttpResult hr=null;
            String endpoint=s.url;
            try {
                log("[MCP] initialize -> protocol=" + requestedVersion + " url=" + endpoint);
                log("[MCP-LEGACY] initialize POST version="+requestedVersion+" url="+endpoint);
                hr=postRawLegacy(s,endpoint,req.toString(),null,7000,12000);
                lastHttpCode=hr.code;
                if(hr.code<200||hr.code>=300) throw new McpHttpException(hr.code,"MCP legacy initialize HTTP "+hr.code+": "+shorten(hr.body,700));
                JSONObject x=parseSseOrJson(hr.body);
                if(x.has("error")) {
                    JSONObject er=x.optJSONObject("error");
                    int ec=er==null?Integer.MIN_VALUE:er.optInt("code",Integer.MIN_VALUE);
                    String em=er==null?x.optString("error","MCP initialize error"):er.optString("message","MCP initialize error");
                    throw new McpJsonRpcException(ec,em,er);
                }
                JSONObject result=x.optJSONObject("result");
                if(result==null) throw new IOException("MCP legacy initialize has no result");
                log("[MCP] initialize ok, protocol=" + result.optString("protocolVersion", requestedVersion));
                return finishLegacySession(s,endpoint,hr,result,requestedVersion);
            } catch(Throwable postError) {
                lastError=postError;
                log("[MCP-LEGACY] initialize version="+requestedVersion+" failed server="+s.name+" error="+shorten(String.valueOf(postError),700));
            }
        }

        if (!s.fallbackUrl.isEmpty() && !s.fallbackUrl.equals(s.url)) {
            log("[MCP] legacy primary failed, trying fallback url=" + s.fallbackUrl);
            try {
                ServerDef fb = new ServerDef(s.name, s.fallbackUrl, s.id, s.clientName, s.headers, s.routeHeaderName, s.routeHeaderValue, "");
                return legacyInitialize(fb);
            } catch (Throwable e) {
                lastError = e;
                log("[MCP] legacy fallback failed: " + shorten(String.valueOf(e), 700));
            }
        }

        // Legacy HTTP+SSE servers use GET to advertise a POST back-channel via an
        // `endpoint` event. The URL is discovered from the user's configured URL;
        // no MCP service address/path is embedded in the client.
        try {
            String endpoint=discoverLegacySseEndpoint(s);
            if(endpoint==null||endpoint.isEmpty()) throw new IOException("MCP legacy SSE endpoint event not found");
            log("[MCP-LEGACY] SSE endpoint="+endpoint);
            final String[] versions2 = new String[]{"2025-11-25","2025-06-18","2025-03-26","2024-11-05","2024-10-07"};
            for(String requestedVersion:versions2){
                try {
                    String requestId=UUID.randomUUID().toString();
                    JSONObject params=new JSONObject()
                            .put("protocolVersion",requestedVersion)
                            .put("capabilities",new JSONObject())
                            .put("clientInfo",new JSONObject().put("name",s.clientName).put("version","3.4.0"));
                    JSONObject req=new JSONObject().put("jsonrpc","2.0").put("id",requestId)
                            .put("method","initialize").put("params",params);
                    HttpResult hr=postRawLegacy(s,endpoint,req.toString(),null,7000,12000);
                    lastHttpCode=hr.code;
                    if(hr.code<200||hr.code>=300) throw new McpHttpException(hr.code,"MCP legacy SSE initialize HTTP "+hr.code+": "+shorten(hr.body,700));
                    JSONObject x=parseSseOrJson(hr.body);
                    if(x.has("error")) {
                        JSONObject er=x.optJSONObject("error");
                        throw new McpJsonRpcException(er==null?Integer.MIN_VALUE:er.optInt("code",Integer.MIN_VALUE),
                                er==null?x.optString("error","MCP initialize error"):er.optString("message","MCP initialize error"),er);
                    }
                    JSONObject result=x.optJSONObject("result");
                    if(result==null) throw new IOException("MCP legacy SSE initialize has no result");
                    log("[MCP-LEGACY] SSE initialize version="+requestedVersion+" success");
                    return finishLegacySession(s,endpoint,hr,result,requestedVersion);
                } catch(Throwable e){
                    lastError=e;
                    log("[MCP-LEGACY] SSE initialize version="+requestedVersion+" failed: "+shorten(String.valueOf(e),500));
                }
            }
        } catch(Throwable e) {
            lastError=e;
            log("[MCP-LEGACY] SSE discovery failed: "+shorten(String.valueOf(e),700));
        }
        throw asException(lastError==null?new IOException("MCP legacy initialize failed"):lastError);
    }

    private static LegacySession finishLegacySession(ServerDef s,String endpoint,HttpResult hr,JSONObject result,String requestedVersion) throws Exception {
        String pv=result.optString("protocolVersion",requestedVersion);
        String sid="";
        for(Map.Entry<String,String> he:hr.headers.entrySet()){
            if(he.getKey()!=null && "Mcp-Session-Id".equalsIgnoreCase(he.getKey())){
                sid=he.getValue()==null?"":he.getValue().trim();
                break;
            }
        }
        try {
            HttpResult initialized=postRawLegacy(s,endpoint,
                    new JSONObject().put("jsonrpc","2.0").put("method","notifications/initialized")
                            .put("params",new JSONObject()).toString(),sid,7000,12000);
            lastHttpCode=initialized.code;
            log("[MCP] notifications/initialized -> server=" + s.name + " http=" + initialized.code);
            log("[MCP-LEGACY] notifications/initialized http="+initialized.code+" server="+s.name+" version="+pv);
        } catch(Throwable e) {
            log("[MCP-LEGACY] notifications/initialized ignored: "+shorten(String.valueOf(e),500));
        }
        return new LegacySession(sid,pv,endpoint);
    }

    private static JSONObject postMcpLegacy(ServerDef s,LegacySession session,String method,JSONObject params) throws Exception {
        String id=UUID.randomUUID().toString();
        JSONObject req=new JSONObject().put("jsonrpc","2.0").put("id",id)
                .put("method",method).put("params",params==null?new JSONObject():params);
        String endpoint=session==null||session.endpoint==null||session.endpoint.isEmpty()?s.url:session.endpoint;
        if ("tools/list".equals(method)) log("[MCP] tools/list -> " + endpoint);
        HttpResult hr=postRawLegacy(s,endpoint,req.toString(),session==null?null:session.sessionId,10000,TIMEOUT);
        lastHttpCode=hr.code;
        if(hr.code<200||hr.code>=300) throw new McpHttpException(hr.code,"MCP legacy HTTP "+hr.code+": "+shorten(hr.body,700));
        JSONObject x=parseSseOrJson(hr.body);
        if(x.has("error")) {
            JSONObject er=x.optJSONObject("error");
            throw new McpJsonRpcException(er==null?Integer.MIN_VALUE:er.optInt("code",Integer.MIN_VALUE),
                    er==null?x.optString("error","MCP legacy JSON-RPC error"):er.optString("message","MCP legacy JSON-RPC error"),er);
        }
        return x;
    }

    private static HttpResult postRawLegacy(ServerDef s,String endpoint,String body,String sessionId,int connectTimeout,long readTimeout) throws Exception {
        return postRawInternal(endpoint,s,body,null,null,null,connectTimeout,readTimeout,sessionId);
    }

    private static String discoverLegacySseEndpoint(ServerDef s) throws Exception {
        HttpURLConnection c=null;
        try {
            URL u=new URL(s.url);
            c=(HttpURLConnection)u.openConnection();
            c.setConnectTimeout(7000);
            c.setReadTimeout(12000);
            c.setRequestMethod("GET");
            c.setUseCaches(false);
            c.setRequestProperty("Accept","text/event-stream");
            if(s.headers!=null){ Iterator<String> it=s.headers.keys(); while(it.hasNext()){ String k=it.next(); String v=s.headers.optString(k,""); if(!k.isEmpty()&&!v.isEmpty()) c.setRequestProperty(k,v); } }
            if(s.routeHeaderName!=null&&!s.routeHeaderName.isEmpty()&&s.routeHeaderValue!=null&&!s.routeHeaderValue.isEmpty()) c.setRequestProperty(s.routeHeaderName,s.routeHeaderValue);
            int code=c.getResponseCode();
            lastHttpCode=code;
            InputStream in=code>=400?c.getErrorStream():c.getInputStream();
            String endpoint=parseSseEndpoint(in,u);
            if(code<200||code>=300) throw new McpHttpException(code,"MCP legacy SSE GET HTTP "+code+": "+endpoint);
            if(endpoint==null||endpoint.isEmpty()) throw new IOException("MCP legacy SSE endpoint event not found");
            return endpoint;
        } finally { if(c!=null)c.disconnect(); }
    }

    private static String parseSseEndpoint(InputStream in,URL base) throws Exception {
        if(in==null)return null;
        BufferedReader br=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8));
        String line; String event=""; StringBuilder data=new StringBuilder();
        while((line=br.readLine())!=null){
            if(line.startsWith("event:")){ event=line.substring(6).trim(); continue; }
            if(line.startsWith("data:")){ if(data.length()>0)data.append('\n'); data.append(line.substring(5).trim()); continue; }
            if(line.trim().isEmpty()){
                String d=data.toString().trim();
                if("endpoint".equalsIgnoreCase(event)){
                    String candidate=d;
                    if(candidate.startsWith("http://")||candidate.startsWith("https://")){ return candidate; }
                    if(!candidate.isEmpty() && (candidate.startsWith("/") || candidate.startsWith("./") || candidate.startsWith("../"))) return new URL(base,candidate).toString();
                }
                event=""; data.setLength(0);
            }
        }
        String d=data.toString().trim();
        if("endpoint".equalsIgnoreCase(event) && !d.isEmpty()) return (d.startsWith("http://")||d.startsWith("https://"))?d:new URL(base,d).toString();
        return null;
    }

    private static JSONObject postMcpModern(ServerDef s,String method,JSONObject params,String requestId) throws Exception {
        try {
            return postMcpModernAt(s, s.url, method, params, requestId);
        } catch (Throwable primary) {
            if (!s.fallbackUrl.isEmpty() && !s.fallbackUrl.equals(s.url)) {
                log("[MCP] primary failed, fallback url=" + s.fallbackUrl + " error=" + shorten(String.valueOf(primary), 500));
                return postMcpModernAt(s, s.fallbackUrl, method, params, requestId);
            }
            throw primary;
        }
    }

    private static JSONObject postMcpModernAt(ServerDef s,String endpoint,String method,JSONObject params,String requestId) throws Exception {
        JSONObject clientInfo=new JSONObject().put("name",s.clientName).put("version","3.4.5");
        JSONObject meta=new JSONObject()
                .put("io.modelcontextprotocol/protocolVersion","2026-07-28")
                .put("io.modelcontextprotocol/clientInfo",clientInfo)
                .put("io.modelcontextprotocol/clientCapabilities",new JSONObject());
        JSONObject p=params==null?new JSONObject():new JSONObject(params.toString());
        p.put("_meta",meta);
        JSONObject req=new JSONObject().put("jsonrpc","2.0").put("id",requestId==null?UUID.randomUUID().toString():requestId).put("method",method).put("params",p);
        String name="";
        if("tools/call".equals(method)) name=p.optString("name","");
        if ("tools/list".equals(method)) log("[MCP] tools/list -> " + endpoint);
        HttpResult hr=postRaw(endpoint,s,req.toString(),"2026-07-28",method,name,10000,TIMEOUT);
        if(requestId!=null) log("[MCP-HTTP] id="+requestId+" serverId="+s.id+" code="+hr.code+" contentType="+shorten(hr.contentType,120));
        lastHttpCode=hr.code;
        if(hr.code<200||hr.code>=300) throw new McpHttpException(hr.code,"MCP modern HTTP "+hr.code+": "+shorten(hr.body,700));
        JSONObject x=parseSseOrJson(hr.body);
        if(x.has("error")){
            JSONObject er=x.optJSONObject("error");
            int ec=er==null?Integer.MIN_VALUE:er.optInt("code",Integer.MIN_VALUE);
            String em=er==null?x.optString("error","MCP JSON-RPC error"):er.optString("message","MCP JSON-RPC error");
            throw new McpJsonRpcException(ec, em, er);
        }
        return x;
    }

    private static final class McpJsonRpcException extends IOException {
        final int code;
        final JSONObject error;
        McpJsonRpcException(int code,String message,JSONObject error){
            super("MCP modern JSON-RPC error: "+(error==null?message:error.toString()));
            this.code=code;
            this.error=error;
        }
    }

    private static JSONObject postJson(String url,JSONObject body,String ignored) throws Exception {
        Exception last=null;
        for(int attempt=0;attempt<2;attempt++){
            try {
                HttpResult r=postRaw(url,body.toString());
                if(r.code<200||r.code>=300) throw new IOException("upstream HTTP "+r.code+": "+shorten(r.body,800));
                JSONObject x=parseSseOrJson(r.body);
                if(x.has("error")) throw new IOException(x.optJSONObject("error").toString());
                return x;
            } catch(Exception e){
                last=e;
                log("[UPSTREAM] attempt="+(attempt+1)+" failed: "+e);
                if(attempt==0){ try{Thread.sleep(250L);}catch(InterruptedException ie){Thread.currentThread().interrupt();throw e;} }
            }
        }
        throw last==null?new IOException("upstream request failed"):last;
    }
    private static HttpResult postRaw(ServerDef s,String body,String protocolVersion,String mcpMethod,String mcpName,int connectTimeout,long readTimeout) throws Exception {
        return postRawInternal(s.url,s,body,protocolVersion,mcpMethod,mcpName,connectTimeout,readTimeout);
    }

    private static HttpResult postRaw(String url,ServerDef server,String body,String protocolVersion,String mcpMethod,String mcpName,int connectTimeout,long readTimeout) throws Exception {
        return postRawInternal(url,server,body,protocolVersion,mcpMethod,mcpName,connectTimeout,readTimeout,null);
    }

    private static HttpResult postRaw(String url,String body) throws Exception {
        return postRawInternal(url,null,body,null,null,null,10000,TIMEOUT);
    }
    private static HttpResult postRawInternal(String url,ServerDef server,String body,String protocolVersion,String mcpMethod,String mcpName,int connectTimeout,long readTimeout) throws Exception {
        return postRawInternal(url,server,body,protocolVersion,mcpMethod,mcpName,connectTimeout,readTimeout,null);
    }
    private static HttpResult postRawInternal(String url,ServerDef server,String body,String protocolVersion,String mcpMethod,String mcpName,int connectTimeout,long readTimeout,String sessionId) throws Exception {
        HttpURLConnection c=null;
        try {
            c=(HttpURLConnection)new URL(url).openConnection();
            c.setConnectTimeout(connectTimeout);
            c.setReadTimeout((int)readTimeout);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setRequestProperty("Content-Type","application/json; charset=utf-8");
            c.setRequestProperty("Accept","application/json, text/event-stream");
            if(sessionId!=null && !sessionId.isEmpty()) c.setRequestProperty("Mcp-Session-Id",sessionId);
            if(protocolVersion!=null&&!protocolVersion.isEmpty())c.setRequestProperty("MCP-Protocol-Version",protocolVersion);
            if(mcpMethod!=null&&!mcpMethod.isEmpty())c.setRequestProperty("Mcp-Method",mcpMethod);
            if(mcpName!=null&&!mcpName.isEmpty())c.setRequestProperty("Mcp-Name",mcpName);
            if(server!=null){
                if(server.routeHeaderName!=null && !server.routeHeaderName.isEmpty() && server.routeHeaderValue!=null && !server.routeHeaderValue.isEmpty()){
                    c.setRequestProperty(server.routeHeaderName,server.routeHeaderValue);
                }
                if(server.headers!=null){ java.util.Iterator<String> it=server.headers.keys(); while(it.hasNext()){ String k=it.next(); String v=server.headers.optString(k,""); if(!k.isEmpty()&&!v.isEmpty()) c.setRequestProperty(k,v); } }
            }
            byte[] b=body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(b.length);
            try(OutputStream o=c.getOutputStream()){o.write(b);o.flush();}
            int code=c.getResponseCode();
            InputStream in=code>=400?c.getErrorStream():c.getInputStream();
            String contentType=c.getContentType();
            String text=readMcpResponse(in,contentType);
            if(text==null) text="";
            Map<String,List<String>> rawHeaders=c.getHeaderFields();
            Map<String,String> headers=new HashMap<>();
            if(rawHeaders!=null){
                for(Map.Entry<String,List<String>> e:rawHeaders.entrySet()){
                    String k=e.getKey();
                    List<String> vs=e.getValue();
                    if(k!=null && vs!=null && !vs.isEmpty()) headers.put(k,vs.get(0));
                }
            }
            return new HttpResult(code,text,contentType,headers);
        } finally { if(c!=null) c.disconnect(); }
    }
    /**
     * MCP Streamable HTTP may return an SSE response whose connection stays open.
     * Waiting for EOF makes the first tools/call appear to work and later calls time out.
     * Stop as soon as a complete JSON-RPC result/error event is received.
     */
    private static String readMcpResponse(InputStream in,String contentType) throws Exception {
        if(in==null) return "";
        if(contentType==null || !contentType.toLowerCase(Locale.ROOT).contains("text/event-stream")) return readAll(in);
        BufferedReader br=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8));
        StringBuilder raw=new StringBuilder(); String line; StringBuilder data=new StringBuilder();
        while((line=br.readLine())!=null){
            raw.append(line).append('\n');
            if(line.startsWith("data:")){
                String d=line.substring(5).trim();
                if(!d.isEmpty() && !"[DONE]".equals(d)) data.append(d);
                if(data.length()>0){
                    try{ JSONObject o=new JSONObject(data.toString()); if(o.has("result")||o.has("error")) return "data: "+o.toString()+"\n"; }catch(Throwable ignored){}
                }
            } else if(line.trim().isEmpty() && data.length()>0){
                try{ JSONObject o=new JSONObject(data.toString()); if(o.has("result")||o.has("error")) return "data: "+o.toString()+"\n"; }catch(Throwable ignored){}
                data.setLength(0);
            }
        }
        return raw.toString();
    }

    private static JSONObject parseSseOrJson(String s) throws Exception {
        if(s==null||s.trim().isEmpty())return new JSONObject(); String t=s.trim(); if(t.startsWith("{"))return new JSONObject(t);
        JSONObject last=null; for(String line:s.split("\\r?\\n")){if(line.startsWith("data:")){String d=line.substring(5).trim();if(d.isEmpty()||"[DONE]".equals(d))continue;try{last=new JSONObject(d);}catch(Throwable ignored){}}}return last==null?new JSONObject().put("raw",s):last;
    }

    private static String extractContent(JSONObject r) {
        try {
            JSONArray c = r.optJSONArray("choices");
            if (c == null || c.length() == 0) return "";
            JSONObject choice = c.optJSONObject(0);
            if (choice == null) return "";
            JSONObject msg = choice.optJSONObject("message");
            if (msg == null) return choice.optString("text", "");
            String content = msg.optString("content", "");
            JSONArray calls = msg.optJSONArray("tool_calls");
            if (calls != null) {
                StringBuilder b = new StringBuilder(content == null ? "" : content);
                for (int i = 0; i < calls.length(); i++) {
                    JSONObject tc = calls.optJSONObject(i); if (tc == null) continue;
                    JSONObject fn = tc.optJSONObject("function");
                    String name = fn == null ? tc.optString("name", "") : fn.optString("name", "");
                    String args = fn == null ? tc.optString("arguments", "{}") : fn.optString("arguments", "{}");
                    if (name.isEmpty()) continue;
                    try { new JSONObject(args); } catch (Throwable bad) { args = "{}"; }
                    b.append("\\n<yb_tool_call>").append(new JSONObject().put("name", name).put("arguments", new JSONObject(args))).append("</yb_tool_call>");
                }
                return b.toString();
            }
            return content == null ? "" : content;
        } catch (Throwable e) { log("extractContent: " + e); return ""; }
    }

    private static JSONObject health(){
        try {
            JSONObject x=new JSONObject().put("status","ok").put("gateway","xposed-java").put("upstream",UPSTREAM).put("mcp_servers",serverDefs().size()).put("autonomous",true);
            try {
                HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:8318/health").openConnection();
                c.setConnectTimeout(1500); c.setReadTimeout(2000); c.setRequestMethod("GET"); c.setRequestProperty("Connection","close");
                int code=c.getResponseCode(); x.put("upstream_http",code); c.disconnect();
            } catch(Throwable e) { x.put("upstream_http",-1).put("upstream_error",e.toString()); }
            return x;
        } catch(Throwable e){return new JSONObject();}
    }
    private static JSONObject models(){try{return new JSONObject().put("object","list").put("data",new JSONArray().put(new JSONObject().put("id","yuanbao-mcp").put("object","model").put("owned_by","xposed-yuanbao").put("capabilities",new JSONObject().put("tools",true).put("function_calling",true).put("streaming",true).put("chat",true))));}catch(Throwable e){return error(e.toString());}}
    private static JSONObject error(String m){try{return new JSONObject().put("error",new JSONObject().put("message",m).put("type","gateway_error"));}catch(Throwable e){return new JSONObject();}}
    private static void writeSse(OutputStream out,JSONObject response)throws IOException{
        try {
            String id=response.optString("id");String model=response.optString("model");
            JSONObject d=new JSONObject().put("id",id).put("object","chat.completion.chunk").put("created",response.optLong("created")).put("model",model).put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",new JSONObject().put("role","assistant")).put("finish_reason",JSONObject.NULL)));
            writeRaw(out,"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-cache\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n");writeRaw(out,"data: "+d+"\n\n");
            JSONObject msg=response.optJSONArray("choices").optJSONObject(0).optJSONObject("message");
            String text=msg==null?"":msg.optString("content","");
            JSONArray toolCalls=msg==null?null:msg.optJSONArray("tool_calls");
            if(toolCalls!=null && toolCalls.length()>0){
                for(int i=0;i<toolCalls.length();i++){
                    JSONObject tc=toolCalls.optJSONObject(i); if(tc==null)continue;
                    JSONObject delta=new JSONObject();
                    JSONArray one=new JSONArray().put(new JSONObject(tc.toString()).put("index",i));
                    delta.put("tool_calls",one);
                    JSONObject x=new JSONObject().put("id",id).put("object","chat.completion.chunk").put("created",response.optLong("created")).put("model",model).put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",delta).put("finish_reason",JSONObject.NULL)));
                    writeRaw(out,"data: "+x+"\n\n");
                }
                JSONObject stop=new JSONObject().put("id",id).put("object","chat.completion.chunk").put("created",response.optLong("created")).put("model",model).put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",new JSONObject()).put("finish_reason","tool_calls")));
                writeRaw(out,"data: "+stop+"\n\n");
            } else {
                for(int i=0;i<text.length();i+=800){String part=text.substring(i,Math.min(text.length(),i+800));JSONObject x=new JSONObject().put("id",id).put("object","chat.completion.chunk").put("created",response.optLong("created")).put("model",model).put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",new JSONObject().put("content",part)).put("finish_reason",JSONObject.NULL)));writeRaw(out,"data: "+x+"\n\n");}
                JSONObject stop=new JSONObject().put("id",id).put("object","chat.completion.chunk").put("created",response.optLong("created")).put("model",model).put("choices",new JSONArray().put(new JSONObject().put("index",0).put("delta",new JSONObject()).put("finish_reason","stop")));
                writeRaw(out,"data: "+stop+"\n\n");
            }
            writeRaw(out,"data: [DONE]\n\n");
        } catch (Exception e) { throw new IOException(e); }
    }

    private static void writeOptions(OutputStream out)throws IOException{writeRaw(out,"HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: *\r\nAccess-Control-Allow-Methods: GET,POST,OPTIONS\r\nConnection: close\r\n\r\n");}
    private static void writeJson(OutputStream out,int code,String body)throws IOException{byte[]b=body.getBytes(StandardCharsets.UTF_8);String st=code==200?"OK":code==400?"Bad Request":code==404?"Not Found":"Internal Server Error";writeRaw(out,"HTTP/1.1 "+code+" "+st+"\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "+b.length+"\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n");out.write(b);out.flush();}
    private static void writeJson(OutputStream out,int code,JSONObject body)throws IOException{writeJson(out,code,body.toString());}
    private static void writeRaw(OutputStream out,String s)throws IOException{out.write(s.getBytes(StandardCharsets.UTF_8));out.flush();}
    private static String readLine(InputStream in)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int c;while((c=in.read())!=-1){if(c=='\n')break;if(c!='\r')b.write(c);if(b.size()>16384)throw new IOException("line too long");}if(c==-1&&b.size()==0)return null;return b.toString("UTF-8");}
    private static byte[] readFully(InputStream in,int n)throws IOException{byte[]b=new byte[n];int p=0;while(p<n){int r=in.read(b,p,n-p);if(r<0)throw new EOFException();p+=r;}return b;}
    private static String readAll(InputStream in)throws IOException{if(in==null)return "";ByteArrayOutputStream b=new ByteArrayOutputStream();byte[]x=new byte[4096];int n;while((n=in.read(x))!=-1)b.write(x,0,n);return b.toString("UTF-8");}
    private static String shorten(String s,int n){if(s==null)return "";return s.length()<=n?s:s.substring(0,n)+"…";}
    /**
     * Module-side log sink. Inside the injected YuanBao process Xposed is present, so lines go to the
     * Xposed log as before. In the module's own process Xposed is absent, which used to swallow every
     * diagnostic silently (bridge start, daemon spawn, heartbeats). Those lines are now written to
     * logcat and to a size-capped file so the daemon/bridge state stays inspectable from outside.
     */
    static void log(String s){
        try{de.robv.android.xposed.XposedBridge.log(TAG+": "+s); return;}catch(Throwable ignored){}
        try{ android.util.Log.i(TAG, s); }catch(Throwable ignored){}
        appendModuleLog(s);
    }

    /** Module external files dir: no permission needed, and readable by the shell for diagnostics. */
    private static final String MODULE_LOG_PATH =
            "/sdcard/Android/data/com.example.yuanbaossehook/files/yuanbao_module.log";
    private static final long MODULE_LOG_MAX = 2 * 1024 * 1024L;
    private static final Object MODULE_LOG_LOCK = new Object();

    private static void appendModuleLog(String s) {
        try {
            synchronized (MODULE_LOG_LOCK) {
                File f = new File(MODULE_LOG_PATH);
                File dir = f.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();
                if (f.length() > MODULE_LOG_MAX) {
                    // Keep the tail of the previous log instead of growing without bound.
                    f.renameTo(new File(f.getAbsolutePath() + ".old"));
                }
                FileWriter w = new FileWriter(f, true);
                try {
                    w.append(new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                            .format(new java.util.Date()));
                    w.append(' ').append(s == null ? "" : s).append('\n');
                } finally { try { w.close(); } catch (Throwable ignored) {} }
            }
        } catch (Throwable ignored) {}
    }

    private static final class ServerDef{
        final String name,url,id,clientName,routeHeaderName,routeHeaderValue,fallbackUrl; final JSONObject headers;
        ServerDef(String n,String u){this(n,u,stableId(n,u),"yuanbao-xposed/"+stableId(n,u),null,null,null,"");}
        ServerDef(String n,String u,String i,String c,JSONObject h){this(n,u,i,c,h,null,null,"");}
        ServerDef(String n,String u,String i,String c,JSONObject h,String rh,String rv){this(n,u,i,c,h,rh,rv,"");}
        ServerDef(String n,String u,String i,String c,JSONObject h,String rh,String rv,String fb){name=n;url=u;id=i;clientName=c;headers=h;routeHeaderName=rh;routeHeaderValue=rv;fallbackUrl=fb==null?"":fb;}
        String key(){return name+"\u0000"+id+"\u0000"+url;}
    }
    private static final class ProbeResult{final String protocol;final int http;final int tools;ProbeResult(String p,int h,int t){protocol=p;http=h;tools=t;}}
    private static final class McpHttpException extends IOException{final int code;McpHttpException(int c,String m){super(m);code=c;}}
    private static final class ToolDef{final String publicName,server,serverId,realName,description;final JSONObject schema;ToolDef(String p,String s,String r,String d,JSONObject sc){this(p,s,"",r,d,sc);}ToolDef(String p,String s,String sid,String r,String d,JSONObject sc){publicName=p;server=s;serverId=sid;realName=r;description=d;schema=sc;}JSONObject openAi() throws Exception {String d=description==null?"":description; if(server!=null&&!server.isEmpty()&&!"builtin".equals(server)) d="[MCP Server: "+server+"] "+d; return new JSONObject().put("type","function").put("function",new JSONObject().put("name",publicName).put("description",d).put("parameters",schema==null?new JSONObject().put("type","object"):schema));}}
    private static final class Call{
        final String name; final JSONObject args; final String displayName;
        Call(String n,JSONObject a){this(n,a,null);}
        Call(String n,JSONObject a,String d){name=n;args=a;displayName=d;}
    }
    private static final class HttpResult{
        final int code;
        final String body,contentType;
        final Map<String,String> headers;
        HttpResult(int c,String b,String ct){this(c,b,ct,Collections.emptyMap());}
        HttpResult(int c,String b,String ct,Map<String,String> h){code=c;body=b;contentType=ct;headers=h==null?Collections.emptyMap():h;}
    }

    /** Returns durable native-agent tasks that survived a disconnect/process restart.
     * The caller can explicitly resume them; no hidden background work is fabricated. */
    static JSONObject submitTaskInput(String taskId, JSONObject responses) throws Exception {
        return handleTaskInputUpdate(taskId, responses == null ? new JSONObject() : responses);
    }

    static JSONArray recoverableInputTasks() {
        JSONArray a = new JSONArray();
        try { for (JSONObject o : McpTaskStore.recoverableInputTasks()) a.put(o); } catch (Throwable ignored) {}
        return a;
    }

    static JSONArray recoverableNativeTasks() {
        JSONArray a = new JSONArray();
        try { for (JSONObject o : McpTaskStore.recoverableNativeTasks()) a.put(o); } catch (Throwable ignored) {}
        return a;
    }

}

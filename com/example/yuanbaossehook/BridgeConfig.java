package com.example.yuanbaossehook;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.SecureRandom;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Persistent, non-secret gateway configuration. API key is generated locally and stored privately. */
final class BridgeConfig {
    private static final String PREF = "yb_gateway_config";
    private static SharedPreferences p;
    private static SharedPreferences moduleP;
    private static final Object LOCK = new Object();

    static void init(Context c) { synchronized (LOCK) { if (p == null) p = c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE); ensure(); } }
    private static SharedPreferences prefs() { if (p == null) throw new IllegalStateException("BridgeConfig not initialized"); return p; }

    static void initModule(Context c) {
        synchronized (LOCK) {
            try {
                Context mc = c.getApplicationContext();
                if (!"com.example.yuanbaossehook".equals(mc.getPackageName())) {
                    mc = mc.createPackageContext("com.example.yuanbaossehook", Context.CONTEXT_IGNORE_SECURITY);
                }
                moduleP = mc.getSharedPreferences("yb_module_runtime", Context.MODE_PRIVATE);
                if (!moduleP.contains("keep_alive")) moduleP.edit().putBoolean("keep_alive", false).putBoolean("auto_start", true).putInt("theme_mode", 0).apply();
            } catch (Throwable ignored) {}
        }
    }

    private static SharedPreferences modulePrefs(Context c) {
        if (moduleP == null) initModule(c);
        return moduleP;
    }
    private static void ensure() {
        SharedPreferences x=prefs();
        if (!x.contains("api_key")) x.edit().putString("api_key", newKey()).putBoolean("auth", false).putBoolean("native_agent", true).putBoolean("auto_refresh_mcp", true).putBoolean("lan_enabled", false).putString("system_prompt", defaultPrompt()).putString("project", "default").putString("memory_default", "").apply();
        if (!x.contains("servers")) x.edit().putString("servers", defaultServers().toString()).apply();
        ensureDefaultMcpServer(x);
        removeLegacyBuiltIns(x);
    }
    private static void removeLegacyBuiltIns(SharedPreferences x) {
        try {
            JSONArray a = new JSONArray(x.getString("servers", "[]"));
            JSONArray kept = new JSONArray();
            boolean changed = false;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) { changed = true; continue; }
                String id = o.optString("id", "");
                String name = o.optString("name", "");
                if ("mt-local".equals(id) || "xuanxing-local".equals(id) ||
                        "proxypin-local".equals(id) || "webmcp-local".equals(id)) {
                    changed = true;
                    continue;
                }
                kept.put(o);
            }
            if (changed) x.edit().putString("servers", kept.toString()).apply();
        } catch (Throwable ignored) {}
    }
    private static void ensureDefaultMcpServer(SharedPreferences x) {
        try {
            JSONArray a = new JSONArray(x.getString("servers", "[]"));
            boolean changed = false;
            for (int i=0;i<a.length();i++) {
                JSONObject o=a.optJSONObject(i); if(o==null) continue;
                String name=o.optString("name","").trim();
                String url=o.optString("url","").trim();
                if (("mt".equalsIgnoreCase(name) || "apk-mcp".equalsIgnoreCase(name)) &&
                        (url.contains("127.0.0.1:8318") || url.contains("localhost:8318"))) {
                    o.put("url","http://127.0.0.1:8787/mcp");
                    o.put("fallbackUrl","http://10.103.160.2:8787/mcp");
                    o.put("clientName","yuanbao-local-agent");
                    changed=true;
                }
            }
            if (a.length() == 0) {
                a.put(new JSONObject()
                        .put("name", "apk-mcp")
                        .put("url", "http://127.0.0.1:8787/mcp")
                        .put("fallbackUrl", "http://10.103.160.2:8787/mcp")
                        .put("enabled", true)
                        .put("clientName", "yuanbao-local-agent"));
                changed=true;
            }
            if (changed) x.edit().putString("servers", a.toString()).apply();
        } catch (Throwable ignored) {}
    }

    private static String newKey(){ return "yb-local-"+UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().substring(0,8); }
    static String apiKey(){ return prefs().getString("api_key",""); }
    static void regenerateKey(){ prefs().edit().putString("api_key",newKey()).apply(); }

    /**
     * Token the injected host hands to the module process for the loopback bridge.
     *
     * It is persisted (and only rotated once a day) because a token that survives host restarts lets
     * the module keep using its cached copy instead of re-reading the handshake file through
     * Shizuku - that re-read is the slowest step in the bridge path. It only ever lives in YuanBao's
     * own private storage and in this app's private storage, never on shared storage.
     */
    static String bridgeToken(){
        try {
            SharedPreferences p = prefs();
            String t = p.getString("bridge_token", "");
            long at = p.getLong("bridge_token_at", 0L);
            if (t == null || t.length() != 32 || System.currentTimeMillis() - at > 24L*60L*60L*1000L) {
                StringBuilder sb = new StringBuilder();
                java.security.SecureRandom rnd = new java.security.SecureRandom();
                for (int i = 0; i < 32; i++) sb.append("0123456789abcdef".charAt(rnd.nextInt(16)));
                t = sb.toString();
                p.edit().putString("bridge_token", t).putLong("bridge_token_at", System.currentTimeMillis()).apply();
            }
            return t;
        } catch (Throwable t) {
            return newKey();
        }
    }
    static boolean auth(){ return prefs().getBoolean("auth",false); }
    static boolean lanEnabled(){ return prefs().getBoolean("lan_enabled",false); }
    static void setLanEnabled(boolean v){ prefs().edit().putBoolean("lan_enabled",v).apply(); }
    static boolean nativeAgent(){ return prefs().getBoolean("native_agent",true); }
    static void setNativeAgent(boolean v){ prefs().edit().putBoolean("native_agent",v).apply(); }
    static boolean autoRefreshMcp(){ return prefs().getBoolean("auto_refresh_mcp", true); }
    static void setAutoRefreshMcp(boolean v){ prefs().edit().putBoolean("auto_refresh_mcp", v).apply(); }
    static void setAuth(boolean v){ prefs().edit().putBoolean("auth",v).apply(); }
    static String systemPrompt(){ return prefs().getString("system_prompt",defaultPrompt()); }
    static void setSystemPrompt(String s){ prefs().edit().putString("system_prompt",s==null?"":s).apply(); }
    static String project(){ return prefs().getString("project","default"); }
    static void setProject(String s){ prefs().edit().putString("project",s==null||s.trim().isEmpty()?"default":s.trim()).apply(); }
    /** Comma-separated extra Android paths mapped into the external MCP server workspace. */
    static String extraMcpRoots(){ return prefs().getString("extra_mcp_roots",""); }
    static void setExtraMcpRoots(String s){ prefs().edit().putString("extra_mcp_roots",s==null?"":s.trim()).apply(); }

    static boolean keepAlive(){ return moduleP != null && moduleP.getBoolean("keep_alive", false); }
    static boolean keepAlive(Context c){ return runtimeBool(c, "keep_alive", false); }
    static void setKeepAlive(Context c, boolean v){ setRuntime(c, "keep_alive", v); }
    static boolean autoStart(){ return moduleP == null || moduleP.getBoolean("auto_start", true); }
    static boolean autoStart(Context c){ return runtimeBool(c, "auto_start", true); }
    static void setAutoStart(Context c, boolean v){ setRuntime(c, "auto_start", v); }
    /** 0=system, 1=light, 2=dark. */
    static int themeMode(){ return moduleP == null ? 0 : moduleP.getInt("theme_mode", 0); }
    static int themeMode(Context c){
        try {
            if (isModuleContext(c)) return themeMode();
            android.os.Bundle b = c.getContentResolver().call(android.net.Uri.parse("content://" + ApkScanProvider.AUTHORITY), "get_runtime", null, null);
            return b == null ? 0 : b.getInt("theme_mode", 0);
        } catch (Throwable e) { return 0; }
    }
    static void setThemeMode(Context c, int mode){
        mode = Math.max(0, Math.min(2, mode));
        try {
            if (isModuleContext(c)) { initModule(c); if (moduleP != null) moduleP.edit().putInt("theme_mode", mode).apply(); return; }
            android.os.Bundle x = new android.os.Bundle(); x.putString("key", "theme_mode"); x.putInt("value_int", mode);
            c.getContentResolver().call(android.net.Uri.parse("content://" + ApkScanProvider.AUTHORITY), "set_runtime", null, x);
        } catch (Throwable ignored) {}
    }

    private static boolean isModuleContext(Context c){ return c != null && "com.example.yuanbaossehook".equals(c.getPackageName()); }
    private static boolean runtimeBool(Context c, String key, boolean def){
        try {
            if (isModuleContext(c)) { initModule(c); return moduleP == null ? def : moduleP.getBoolean(key, def); }
            android.os.Bundle b = c.getContentResolver().call(android.net.Uri.parse("content://" + ApkScanProvider.AUTHORITY), "get_runtime", null, null);
            return b == null ? def : b.getBoolean(key, def);
        } catch (Throwable e) { return def; }
    }
    private static void setRuntime(Context c, String key, boolean value){
        try {
            if (isModuleContext(c)) { initModule(c); if (moduleP != null) moduleP.edit().putBoolean(key, value).apply(); return; }
            android.os.Bundle x = new android.os.Bundle(); x.putString("key", key); x.putBoolean("value", value);
            c.getContentResolver().call(android.net.Uri.parse("content://" + ApkScanProvider.AUTHORITY), "set_runtime", null, x);
        } catch (Throwable ignored) {}
    }

    static JSONArray servers(){
        try {
            JSONArray a = new JSONArray(prefs().getString("servers", "[]"));
            JSONArray kept = new JSONArray();
            boolean changed = false;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) { changed = true; continue; }
                if (isLegacyBuiltIn(o)) { changed = true; continue; }
                kept.put(o);
            }
            if (changed) prefs().edit().putString("servers", kept.toString()).apply();
            if (kept.length() == 0) {
                JSONArray defaults = defaultServers();
                prefs().edit().putString("servers", defaults.toString()).apply();
                return defaults;
            }
            return kept;
        } catch(Throwable e){
            return new JSONArray();
        }
    }
    private static boolean isLegacyBuiltIn(JSONObject o) {
        if (o == null) return true;
        String id = o.optString("id", "").trim();
        String name = o.optString("name", "").trim();
        return "mt-local".equals(id) || "xuanxing-local".equals(id) ||
               "proxypin-local".equals(id) || "webmcp-local".equals(id);
    }
    static void setServers(JSONArray a){
        if (a == null || a.length() == 0) prefs().edit().putString("servers", defaultServers().toString()).apply();
        else prefs().edit().putString("servers",a.toString()).apply();
    }

    static String memory(String project){
        try{return prefs().getString("memory."+safe(project),"");}catch(Throwable e){return "";}
    }
    static void setMemory(String project,String value){ prefs().edit().putString("memory."+safe(project),value==null?"":value).apply(); }
    static String memoryJson(){
        try{
            JSONObject o=new JSONObject(); for(String k:prefs().getAll().keySet()) if(k.startsWith("memory.")) o.put(k.substring(7),prefs().getString(k,"")); return o.toString();
        }catch(Throwable e){return "{}";}
    }
    private static String safe(String s){ return (s==null?"default":s).replaceAll("[^A-Za-z0-9_.\\-\\u4e00-\\u9fff]","_"); }
    private static String defaultPrompt(){ return "你是一个严谨的本地编程/逆向分析 Agent。优先使用已提供的 MCP 工具完成可验证的操作；先检查上下文，再修改，最后验证。不要虚构工具结果。工具调用由网关执行，不要把工具调用当作普通聊天内容。"; }
    private static JSONArray defaultServers(){
        JSONArray a = new JSONArray();
        try {
            a.put(new JSONObject()
                    .put("name", "apk-mcp")
                    .put("url", "http://127.0.0.1:8787/mcp")
                    .put("fallbackUrl", "http://10.103.160.2:8787/mcp")
                    .put("enabled", true)
                    .put("clientName", "yuanbao-local-agent"));
        } catch (Throwable ignored) {}
        return a;
    }
}

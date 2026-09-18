package com.example.yuanbaossehook;

import android.app.Application;
import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * YuanBao Local OpenAI Bridge v3.3
 * 相对 3.2：
 *   - 新增 stripStaleContext()：剥离 system prompt 里 ## Retrieved Memory / ## Current Plan 段落
 *   - extractPrompt() 对 system 消息先清洗再拼
 *   目的：GitHubK Studio 每次都会注入旧 Memory/Plan，导致元宝总在回答旧话题
 */
public final class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "YB-Bridge";
    private static final String TARGET = "com.tencent.hunyuan.app.chat";
    private static final int PORT = 8318;
    private static final long STREAM_TIMEOUT_MS = 60_000L;
    private static final int MAX_PROMPT_CHARS = 16_000;
    private static final int SYS_PROMPT_LOG_CHUNK = 2000;

    /** 要剥离的 system prompt 段落锚点（出现即从此处截断到末尾） */
    private static final String[] STALE_ANCHORS = {
        "\n## Retrieved Memory",
        "\n## Current Plan",
        "\n## Stale Memory",
        "\n## Stale Plan",
        "\n## Memory",
        "\n## Plan"
    };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicInteger SEQ = new AtomicInteger(1);
    private static final Object CTX_LOCK = new Object();
    private static WeakReference<Object> activeI6 = new WeakReference<>(null);

    private static final ConcurrentHashMap<String, RequestContext> REQUESTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, String> MSG_TO_REQ = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> TOKEN_TO_REQ = new ConcurrentHashMap<>();
    private static final ThreadLocal<String> CURRENT_REQ = new ThreadLocal<>();
    /** Only true while the gateway itself synchronously calls hb.I6.s3().
     * CURRENT_REQ is also used for request correlation and must NOT be used as a recursion flag,
     * because I7.a4 can leave it populated on the UI thread after a normal native chat send. */
    private static final ThreadLocal<Boolean> BRIDGE_TRIGGER = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> NATIVE_AGENT_REPLAY = new ThreadLocal<>();
    private static final ConcurrentHashMap<String, Long> NATIVE_INFLIGHT = new ConcurrentHashMap<>();
    private static final Object NATIVE_TASK_EXECUTION_LOCK = new Object();
    private static final ScheduledExecutorService NATIVE_AGENT_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "YB-native-agent-loop");
        t.setDaemon(true);
        return t;
    });
    private static volatile NativeAgentState NATIVE_STATE;
    private static final Deque<String> PENDING = new ArrayDeque<>();
    private static File logFile;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (!TARGET.equals(lp.packageName)) return;
        if (lp.processName != null && !TARGET.equals(lp.processName)) return;
        initLog(lp);
        log("===== YuanBao Local OpenAI Bridge 3.5.2 SAF + Project Task Runner + Native MCP Tool Card =====");
        log("process=" + lp.processName);
        installApplicationStart();
        installContextHook(lp);
        installActivityCardHook(lp);
        installInAppSettingsHook();
        installMessageCtorHook(lp);
        installMessageHook(lp);
        installSendOrchestratorHooks(lp);
        installNativeAgentOutputHook(lp);
        installRequestBuilderHook(lp);
        installSseHooks(lp);
    }

    private void installApplicationStart() {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try { if (p.thisObject instanceof Application) { BridgeConfig.init((Application)p.thisObject); McpIdeGateway.setApplicationContext((Application)p.thisObject); } } catch (Throwable ignored) {}
                    LocalHttpServer.start();
                    McpIdeGateway.start();
                }
            });
            log("[OK] Application.onCreate");
        } catch (Throwable t) { log("[MISS] Application.onCreate " + t); }
    }

    private void installActivityCardHook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.thisObject instanceof Activity) {
                        NativeToolCard.bindActivity((Activity) p.thisObject);
                        try {
                            org.json.JSONArray recovered = McpIdeGateway.recoverableNativeTasks();
                            if (recovered.length() > 0) {
                                org.json.JSONObject t = recovered.optJSONObject(0);
                                if (t != null) {
                                    String tid = t.optString("taskId", "");
                                    if (!tid.isEmpty()) {
                                        NativeToolCard.beginAgent("检测到可恢复的 Native Agent 任务");
                                        NativeToolCard.bindDurableTask(tid);
                                        NativeToolCard.setStatus("发现上次未完成任务；已恢复持久化状态，等待显式继续", false);
                                    }
                                }
                            } else {
                                org.json.JSONArray inputs = McpIdeGateway.recoverableInputTasks();
                                if (inputs.length() > 0) {
                                    org.json.JSONObject t = inputs.optJSONObject(0);
                                    if (t != null) {
                                        String tid = t.optString("taskId", "");
                                        if (!tid.isEmpty()) {
                                            NativeToolCard.beginAgent("检测到需要用户确认的 MCP 任务");
                                            NativeToolCard.bindDurableTask(tid);
                                            NativeToolCard.setStatus("任务等待 input_required；请在原生卡片中确认或取消", false);
                                        }
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            });
            XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.thisObject instanceof Activity) NativeToolCard.unbindActivity((Activity) p.thisObject);
                }
            });
            log("[OK] Activity lifecycle -> NativeToolCard");
        } catch (Throwable t) { log("[MISS] Activity lifecycle -> NativeToolCard " + t); }
    }

    private void installContextHook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> a1 = XposedHelpers.findClass("hb.a1", lp.classLoader);
            for (Method m : a1.getDeclaredMethods()) {
                if (!"onResume".equals(m.getName()) || m.getParameterTypes().length != 0) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        synchronized (CTX_LOCK) { activeI6 = new WeakReference<>(p.thisObject); }
                        log("[CTX] active=" + p.thisObject.getClass().getName());
                    }
                });
                log("[OK] hb.a1.onResume");
                return;
            }
        } catch (Throwable t) { log("[MISS] hb.a1.onResume " + t); }
    }

    /** Inject a small settings entry into YuanBao itself; the old standalone module UI is no longer required. */
    private void installInAppSettingsHook() {
        try {
            XposedBridge.hookAllMethods(android.app.Activity.class, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Activity a = (Activity) p.thisObject;
                        if (a != null && !a.isFinishing()) InAppSettings.attach(a);
                    } catch (Throwable e) { log("in-app settings: " + e); }
                }
            });
            log("[OK] Activity.onResume -> in-app settings");
        } catch (Throwable t) { log("[MISS] Activity.onResume " + t); }
    }

    private void installMessageCtorHook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> mfs = XposedHelpers.findClass("com.tencent.hunyuan.deps.service.bean.chats.MessageForSend", lp.classLoader);
            int n = 0;
            for (Constructor<?> ctor : mfs.getDeclaredConstructors()) {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Object msg = p.thisObject;
                        if (msg == null) return;
                        String req = CURRENT_REQ.get();
                        if (req == null) req = peekPending();
                        if (req == null) return;
                        try { XposedHelpers.setAdditionalInstanceField(msg, "yb_req_id", req); } catch (Throwable ignored) {}
                        MSG_TO_REQ.put(System.identityHashCode(msg), req);
                        log("[MSG] ctor bound req=" + req);
                    }
                });
                n++;
            }
            log("[OK] MessageForSend ctor x" + n);
        } catch (Throwable t) { log("[MISS] MessageForSend ctor " + t); }
    }

    private void installMessageHook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> pp = XposedHelpers.findClass("com.tencent.hunyuan.app.chat.biz.chats.conversation.base.viewholder.MessageForSendPreprocessor", lp.classLoader);
            for (Method m : pp.getDeclaredMethods()) {
                if (!("a".equals(m.getName()) || "b".equals(m.getName()) || "c".equals(m.getName()))) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Object msg = p.getResult();
                        if (msg == null || !msg.getClass().getName().contains("MessageForSend")) return;
                        String req = CURRENT_REQ.get();
                        if (req == null) req = peekPending();
                        if (req == null) return;
                        try { XposedHelpers.setAdditionalInstanceField(msg, "yb_req_id", req); } catch (Throwable ignored) {}
                        MSG_TO_REQ.put(System.identityHashCode(msg), req);
                        log("[MSG] a/b/c bound req=" + req + " method=" + m.getName());
                    }
                });
            }
            log("[OK] MessageForSendPreprocessor a/b/c");
        } catch (Throwable t) { log("[MISS] MessageForSendPreprocessor " + t); }
    }

    private void installSendOrchestratorHooks(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> a4 = XposedHelpers.findClass("I7.a4", lp.classLoader);
            for (Method m : a4.getDeclaredMethods()) {
                final String name = m.getName();
                if (!"A".equals(name) && !"m".equals(name)) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        String req = findReqInArgs(p.args);
                        if (req == null) req = peekPending();
                        if (req == null) { log("[SEND] I7.a4." + name + " matched=<none>"); return; }
                        CURRENT_REQ.set(req);
                        if (p.args != null) {
                            for (Object a : p.args) {
                                if (a != null && a.getClass().getName().contains("MessageForSend")) {
                                    MSG_TO_REQ.put(System.identityHashCode(a), req);
                                    try { XposedHelpers.setAdditionalInstanceField(a, "yb_req_id", req); } catch (Throwable ignored) {}
                                }
                            }
                        }
                        log("[SEND] I7.a4." + name + " matched=" + req + " args=" + (p.args == null ? 0 : p.args.length));
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) { }
                });
                log("[OK] I7.a4." + name);
            }
        } catch (Throwable t) { log("[MISS] I7.a4 A/m " + t); }

        try {
            Class<?> i6 = XposedHelpers.findClass("hb.I6", lp.classLoader);
            for (Method m : i6.getDeclaredMethods()) {
                if (!"s3".equals(m.getName()) || m.getParameterTypes().length != 3) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (p.args == null || p.args.length == 0 || !(p.args[0] instanceof String)) return;
                        String text = (String) p.args[0];
                        if (text.isEmpty()) return;

                        // Native YuanBao chat normally bypasses :8788. When Agent mode is enabled,
                        // intercept an explicit MCP/analysis request, execute a real MCP preflight,
                        // then replay the same send with the real tool result embedded. This makes
                        // the in-app chat use MCP instead of merely showing tool-call instructions.
                        // Requests triggered by the local OpenAI bridge already belong to an
                        // in-flight gateway request. Do not route those prompts through the native
                        // Agent again, otherwise 8788 -> 8318 -> s3 can recursively re-enter MCP.
                        boolean bridgeTriggered = Boolean.TRUE.equals(BRIDGE_TRIGGER.get());
                        boolean replay = Boolean.TRUE.equals(NATIVE_AGENT_REPLAY.get());
                        // YuanBao sometimes echoes the exact MCP tool name after the gateway has
                        // already executed the deterministic preflight. Do not treat that echo as a
                        // new user task: otherwise the gateway executes list_available_apks a second
                        // time with a lost directory context and may generate prefix="".
                        if (!bridgeTriggered && !replay && McpIdeGateway.isNativeToolNameEcho(text)) {
                            log("[NATIVE-AGENT] suppressed tool-name echo after completed preflight: " + text.trim());
                            p.setResult(null);
                            return;
                        }
                        // Clone the hook arguments before any asynchronous/native replay work.
                        // This local must be declared before it is passed to the Agent matcher.
                        final Object[] originalArgs = p.args == null ? new Object[0] : p.args.clone();
                        boolean shouldAgent = McpIdeGateway.shouldInterceptNativeAgent(text, originalArgs);
                        log("[NATIVE-AGENT-CHECK] enabled=" + BridgeConfig.nativeAgent()
                                + " bridge=" + bridgeTriggered + " replay=" + replay
                                + " match=" + shouldAgent + " len=" + text.length()
                                + " args=" + p.args.length + " preview=" + text.substring(0, Math.min(120, text.length())));
                        if (!replay && !bridgeTriggered && shouldAgent) {
                            final String dedupeKey = text.trim();
                            long now = System.currentTimeMillis();
                            Long previous = NATIVE_INFLIGHT.get(dedupeKey);
                            if (previous != null && now - previous < 15000L) {
                                log("[NATIVE-AGENT] duplicate suppressed age="+(now-previous)+"ms");
                                return;
                            }
                            NATIVE_INFLIGHT.put(dedupeKey, now);
                            McpIdeGateway.rememberNativeTask(text);
                            final Object ctx = p.thisObject;
                            p.setResult(null);
                            synchronized (MainHook.class) {
                                NATIVE_STATE = new NativeAgentState(text, ctx);
                            }
                            log("[NATIVE-AGENT] intercept hb.I6.s3 -> model-driven loop: " + text.substring(0, Math.min(180, text.length())));
                            NativeToolCard.beginAgent(text);
                            new Thread(() -> {
                                try {
                                    // Prefer a deterministic first MCP call when the user's request
                                    // unambiguously matches a real discovered tool. This avoids relying
                                    // on YuanBao emitting the private <yb_tool_call> text protocol.
                                    if (McpIdeGateway.canAutoExecuteNativeIntent(text)) {
                                        log("[NATIVE-AGENT] persistent task matched; executing one uninterrupted MCP task chain");
                                        synchronized (NATIVE_TASK_EXECUTION_LOCK) {
                                            String durableTaskId = McpIdeGateway.beginPersistentNativeTask(text);
                                            NativeToolCard.bindDurableTask(durableTaskId);
                                            try {
                                                final String toolResultPrompt = McpIdeGateway.executeInferredNativeToolCalls(text, 1);
                                                if (McpIdeGateway.activeNativeTaskCancelled()) {
                                                    NativeToolCard.setStatus("任务已取消，不回灌结果", false);
                                                    McpIdeGateway.setNativeTaskStage("CANCELLED");
                                                    NATIVE_INFLIGHT.remove(dedupeKey);
                                                    return;
                                                }
                                                // NATIVE_STATE is volatile and can be nulled by
                                                // clearNativeAgent() from the UI/output-hook thread
                                                // while this worker runs. We must capture a local
                                                // reference first: synchronized(null) throws
                                                // "NullPointerException: Null reference used for
                                                // synchronization (monitor-enter)", which aborted the
                                                // whole persistent MCP task and produced exactly the
                                                // reported "调了没回结果" (no result ever replayed).
                                                NativeAgentState stRef = NATIVE_STATE;
                                                if (stRef != null) {
                                                    synchronized (stRef) {
                                                        stRef.round = 1;
                                                        stRef.waitingForToolResult = true;
                                                        stRef.processing = false;
                                                    }
                                                }
                                                // All MCP stages (list -> select -> open/decompile -> fallback) finish first.
                                                // Only ONE replay is sent to YuanBao, preventing tool-result -> s3 -> new-task recursion.
                                                MAIN.post(() -> {
                                                    try {
                                                        NATIVE_AGENT_REPLAY.set(Boolean.TRUE);
                                                        McpIdeGateway.setNativeTaskStage("REPLAYING_FINAL_RESULT");
                                                        XposedHelpers.callMethod(ctx, "s3", toolResultPrompt, "", Boolean.FALSE);
                                                        log("[NATIVE-AGENT] persistent MCP task completed; final accumulated result replayed once");
                                                        NativeToolCard.setStatus("工具链已完成，结果已连续回灌", false);
                                                        McpIdeGateway.finishPersistentNativeTask(toolResultPrompt);
                                                    } catch (Throwable e) {
                                                        log("[NATIVE-AGENT] final result replay failed: " + e);
                                                        clearNativeAgent();
                                                    } finally {
                                                        NATIVE_AGENT_REPLAY.remove();
                                                        NATIVE_INFLIGHT.remove(dedupeKey);
                                                    }
                                                });
                                            } catch (Throwable e) {
                                                log("[NATIVE-AGENT] persistent MCP task failed: " + e);
                                                McpIdeGateway.failPersistentNativeTask(String.valueOf(e));
                                                McpIdeGateway.setNativeTaskStage("FAILED");
                                                MAIN.post(() -> {
                                                    try { NativeToolCard.setStatus("工具调用失败：" + String.valueOf(e), true); } catch (Throwable ignored) {}
                                                });
                                                NATIVE_INFLIGHT.remove(dedupeKey);
                                            }
                                        }
                                        return;
                                    }
                                    final String enriched = McpIdeGateway.buildNativeAgentPrompt(text, originalArgs);
                                    MAIN.post(() -> {
                                        try {
                                            NATIVE_AGENT_REPLAY.set(Boolean.TRUE);
                                            XposedHelpers.callMethod(ctx, "s3", enriched, "", Boolean.FALSE);
                                        } catch (Throwable e) {
                                            log("[NATIVE-AGENT] initial replay failed: " + e);
                                            clearNativeAgent();
                                        } finally {
                                            NATIVE_AGENT_REPLAY.remove();
                                            NATIVE_INFLIGHT.remove(dedupeKey);
                                        }
                                    });
                                } catch (Throwable e) {
                                    log("[NATIVE-AGENT] catalog/direct MCP failed: " + e);
                                    MAIN.post(() -> {
                                        try {
                                            NATIVE_AGENT_REPLAY.set(Boolean.TRUE);
                                            XposedHelpers.callMethod(ctx, "s3", text, "", Boolean.FALSE);
                                        } catch (Throwable ignored) {}
                                        finally { NATIVE_AGENT_REPLAY.remove(); NATIVE_INFLIGHT.remove(dedupeKey); }
                                    });
                                }
                            }, "YB-native-agent").start();
                            return;
                        }

                        String req = pollPending();
                        if (req != null) CURRENT_REQ.set(req);
                        log("[SEND] hb.I6.s3 textLen=" + text.length() + " req=" + req);
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) { }
                });
                log("[OK] hb.I6.s3");
            }
        } catch (Throwable t) { log("[MISS] hb.I6.s3 " + t); }
    }

    /**
     * Captures YuanBao's native model output and turns an explicit tool-call response into a
     * real MCP tools/call round-trip. This is deliberately independent of the OpenAI :8318 path.
     */
    private void installNativeAgentOutputHook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> v1 = XposedHelpers.findClass("Uc.V1", lp.classLoader);
            for (Method m : v1.getDeclaredMethods()) {
                if (!"g".equals(m.getName()) || m.getParameterTypes().length != 4) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        NativeAgentState state = NATIVE_STATE;
                        if (state == null || !state.active || p.args == null) return;
                        int stringIndex = -1;
                        String chunk = null;
                        for (int i = p.args.length - 1; i >= 0; i--) {
                            if (p.args[i] instanceof String) { stringIndex = i; chunk = (String)p.args[i]; break; }
                        }
                        if (chunk == null || chunk.trim().isEmpty()) return;
                        synchronized (state) { state.append(chunk); state.generation++; }

                        // Consume a complete tool marker immediately if it arrives in one
                        // transport chunk. This prevents even a transient protocol flash in
                        // YuanBao's native chat bubble.
                        if (McpIdeGateway.containsNativeToolCall(chunk)) {
                            String immediate = sanitizeNativeToolProtocolChunk(state, chunk);
                            if (immediate == null || immediate.isEmpty()) {
                                p.setResult(null);
                                log("[NATIVE-UI] immediate MCP protocol suppression len=" + chunk.length());
                                scheduleNativeAgentParse(state, state.generation);
                                return;
                            }
                            if (!immediate.equals(chunk) && stringIndex >= 0) p.args[stringIndex] = immediate;
                        }

                        // YuanBao renders Uc.V1.g directly into the chat bubble. The model-facing
                        // <yb_tool_call> protocol must therefore NEVER be allowed to reach the UI.
                        // Keep normal prose in the same stream, but consume the protocol fragments
                        // while the MCP executor works in the background.
                        String visible = sanitizeNativeToolProtocolChunk(state, chunk);
                        if (visible == null || visible.isEmpty()) {
                            p.setResult(null);
                            log("[NATIVE-UI] suppressed MCP protocol chunk len=" + chunk.length());
                            return;
                        }
                        if (!visible.equals(chunk) && stringIndex >= 0) {
                            p.args[stringIndex] = visible;
                        }
                        scheduleNativeAgentParse(state, state.generation);
                    }
                });
                log("[OK] Uc.V1.g native-agent output hook");
                return;
            }
            log("[MISS] Uc.V1.g method not found");
        } catch (Throwable t) { log("[MISS] Uc.V1.g native-agent output hook " + t); }
    }

    private static String sanitizeNativeToolProtocolChunk(NativeAgentState state, String chunk) {
        if (chunk == null || chunk.isEmpty()) return chunk;
        synchronized (state) {
            String x = chunk;
            StringBuilder out = new StringBuilder();
            int pos = 0;
            while (pos < x.length()) {
                int open = findNativeToolOpen(x, pos);
                if (state.uiProtocolMode && open < 0) {
                    int close = x.indexOf("</yb_tool_call>", pos);
                    if (close < 0) close = x.indexOf("</tool_call>", pos);
                    if (close < 0) close = x.indexOf("</call>", pos);
                    if (close < 0) return out.length() == 0 ? "" : out.toString();
                    int end = x.indexOf('>', close);
                    state.uiProtocolMode = false;
                    pos = end >= 0 ? end + 1 : x.length();
                    continue;
                }
                if (open < 0) {
                    // If a tag is split exactly at the transport chunk boundary, hold the
                    // suspicious suffix rather than exposing half of the private protocol.
                    int partial = partialNativeToolOpenAtEnd(x, pos);
                    if (partial >= 0) {
                        out.append(x, pos, partial);
                        state.uiProtocolMode = true;
                        return out.toString();
                    }
                    out.append(x.substring(pos));
                    break;
                }
                out.append(x, pos, open);
                int close = x.indexOf("</yb_tool_call>", open);
                if (close < 0) close = x.indexOf("</tool_call>", open);
                if (close < 0) close = x.indexOf("</call>", open);
                if (close < 0) {
                    state.uiProtocolMode = true;
                    return out.toString();
                }
                int end = x.indexOf('>', close);
                state.uiProtocolMode = false;
                pos = end >= 0 ? end + 1 : x.length();
            }
            return out.toString();
        }
    }

    private static int findNativeToolOpen(String s, int from) {
        int a = s.indexOf("<yb_tool_call", from);
        int b = s.indexOf("<tool_call", from);
        int c = s.indexOf("<call>", from);
        int r = -1;
        if (a >= 0) r = a;
        if (b >= 0 && (r < 0 || b < r)) r = b;
        if (c >= 0 && (r < 0 || c < r)) r = c;
        return r;
    }

    private static int partialNativeToolOpenAtEnd(String s, int from) {
        String tail = s.substring(Math.max(from, s.length() - 24));
        String[] prefixes = {"<yb_tool_call", "<tool_call", "<call>"};
        int best = -1;
        for (String prefix : prefixes) {
            for (int n = 1; n < prefix.length(); n++) {
                if (tail.endsWith(prefix.substring(0, n))) {
                    int at = s.length() - n;
                    if (at >= from && (best < 0 || at < best)) best = at;
                }
            }
        }
        return best;
    }

    private static void scheduleNativeAgentParse(NativeAgentState state, long generation) {
        boolean immediate = false;
        synchronized (state) {
            immediate = McpIdeGateway.containsNativeToolCall(state.text.toString());
        }
        final long delayMs = immediate ? 80L : 900L;
        NATIVE_AGENT_SCHEDULER.schedule(() -> {
            if (NATIVE_STATE != state || !state.active) return;
            synchronized (state) { if (generation != state.generation) return; }
            String text;
            synchronized (state) { text = state.text.toString(); }
            if (!McpIdeGateway.containsNativeToolCall(text)) {
                // The model described the tool it wants in plain prose instead of the tagged
                // protocol. Execute exactly that named tool: previously this branch parsed the
                // *user prompt* instead, so the gateway ran a different inferred tool and the model
                // kept waiting for a result of the call it had actually named.
                if (McpIdeGateway.modelTextNamesTool(text)) {
                    log("[NATIVE-AGENT] model named a catalog tool without protocol tags; executing it");
                    NativeToolCard.setStatus("正在执行模型点名的 MCP 工具", false);
                    handleNativeToolRound(state, text);
                    return;
                }
                if (state.round == 0 && McpIdeGateway.canRecoverNativeTool(state.originalPrompt)) {
                    log("[NATIVE-AGENT] model omitted tool-call syntax; recovered from discovered MCP catalog");
                    NativeToolCard.setStatus("正在根据任务继续调用 MCP", false);
                    handleNativeToolRound(state, state.originalPrompt);
                    return;
                }
                if (state.round == 0) {
                    log("[NATIVE-AGENT] model returned final text without a recoverable tool call");
                    NativeToolCard.finishAgent();
                    clearNativeAgent();
                }
                return;
            }
            handleNativeToolRound(state, text);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private static void handleNativeToolRound(NativeAgentState state, String modelText) {
        synchronized (state) {
            if (!state.active || state.processing || state.round >= 10) return;
            state.processing = true;
            state.round++;
            state.waitingForToolResult = true;
        }
        new Thread(() -> {
            try {
                NativeToolCard.showParsedCalls(modelText);
                NativeToolCard.setStatus("正在执行 MCP 工具", true);
                String next = McpIdeGateway.executeNativeModelToolCalls(state.originalPrompt, modelText, state.round);
                synchronized (state) {
                    state.text.setLength(0);
                    state.lastChunk = "";
                    state.generation++;
                }
                MAIN.post(() -> {
                    try {
                        NATIVE_AGENT_REPLAY.set(Boolean.TRUE);
                        XposedHelpers.callMethod(state.context, "s3", next, "", Boolean.FALSE);
                        log("[NATIVE-AGENT] round=" + state.round + " replayed with real MCP results");
                    } catch (Throwable e) {
                        log("[NATIVE-AGENT] tool-result replay failed: " + e);
                        clearNativeAgent();
                    } finally {
                        NATIVE_AGENT_REPLAY.remove();
                        synchronized (state) { state.processing = false; }
                    }
                });
            } catch (Throwable e) {
                log("[NATIVE-AGENT] tool execution failed: " + e);
                synchronized (state) { state.processing = false; state.waitingForToolResult = false; }
                clearNativeAgent();
            }
        }, "YB-native-agent-call").start();
    }

    private static void clearNativeAgent() {
        NativeAgentState s;
        synchronized (MainHook.class) {
            s = NATIVE_STATE;
            if (s != null) s.active = false;
            NATIVE_STATE = null;
        }
    }

    /** Read the current native agent state, or null, without ever exposing a torn reference. */
    private static NativeAgentState currentNativeState() {
        synchronized (MainHook.class) { return NATIVE_STATE; }
    }

    private static String findReqInArgs(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a == null) continue;
            try {
                Object x = XposedHelpers.getAdditionalInstanceField(a, "yb_req_id");
                if (x instanceof String) return (String) x;
            } catch (Throwable ignored) {}
            String mapped = MSG_TO_REQ.get(System.identityHashCode(a));
            if (mapped != null) return mapped;
        }
        return null;
    }

    private void installRequestBuilderHook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> n1 = XposedHelpers.findClass("androidx.appcompat.widget.n1", lp.classLoader);
            for (Method m : n1.getDeclaredMethods()) {
                if (!"e".equals(m.getName()) || m.getParameterTypes().length != 0) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Object qrD = p.getResult();
                        String req = CURRENT_REQ.get();
                        if (req == null) req = peekPending();
                        if (qrD == null) return;
                        String token = safeStringField(qrD, "g");
                        if (req == null) { log("[REQ] qr.d req=<none> tokenLen=" + (token == null ? -1 : token.length())); return; }
                        if (token != null && !token.isEmpty()) {
                            TOKEN_TO_REQ.put(token, req);
                            log("[REQ] qr.d req=" + req + " tokenLen=" + token.length());
                        } else log("[REQ] qr.d req=" + req + " token=<none>");
                    }
                });
                log("[OK] n1.e");
            }
        } catch (Throwable t) { log("[MISS] n1.e " + t); }
    }

    private void installSseHooks(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> piE = XposedHelpers.findClass("pi.e", lp.classLoader);
            for (Method m : piE.getDeclaredMethods()) {
                final String name = m.getName();
                if ("b".equals(name) && m.getParameterTypes().length == 1) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            String req = reqFromPi(p.thisObject);
                            log("[EVT] pi.e.b req=" + req);
                            if (req != null) finish(req, false, "");
                        }
                    });
                } else if ("c".equals(name) && m.getParameterTypes().length >= 2) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            String req = reqFromPi(p.thisObject);
                            log("[EVT] pi.e.c req=" + req);
                            if (req != null) finish(req, true, p.args.length > 1 ? String.valueOf(p.args[1]) : "stream failure");
                        }
                    });
                } else if ("onEvent".equals(name)) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            String req = reqFromPi(p.thisObject);
                            if (p.args != null && p.args.length >= 4) {
                                Object idObj = p.args[2];
                                Object dataObj = p.args[3];
                                int idLen = (idObj instanceof String) ? ((String) idObj).length() : -1;
                                int dataLen = (dataObj instanceof String) ? ((String) dataObj).length() : -1;
                                log("[EVT] pi.e.onEvent req=" + req + " idLen=" + idLen + " dataLen=" + dataLen);
                            }
                            if (req == null || p.args == null) return;
                            for (Object a : p.args) {
                                if (a instanceof String && feed(req, (String) a)) { log("[EVT] fed req=" + req); break; }
                            }
                        }
                    });
                }
            }
            log("[OK] pi.e callbacks");
        } catch (Throwable t) { log("[MISS] pi.e callbacks " + t); }
    }

    private static String reqFromPi(Object piE) {
        if (piE == null) return null;
        String token = safeStringField(piE, "f");
        if (token != null) {
            String req = TOKEN_TO_REQ.get(token);
            if (req != null) return req;
        }
        Object piF = getField(piE, "e");
        if (piF != null) {
            String alt = safeStringField(piF, "e");
            if (alt != null) {
                String req = TOKEN_TO_REQ.get(alt);
                if (req != null) return req;
            }
        }
        return null;
    }

    private static boolean feed(String req, String raw) {
        RequestContext c = REQUESTS.get(req);
        if (c == null || raw == null || raw.isEmpty()) return false;
        log("[FEED] raw.len=" + raw.length() + " head=" + raw.substring(0, Math.min(240, raw.length())));
        if ("[DONE]".equals(raw)) { finish(req, false, ""); return true; }
        if (raw.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(raw);
                String type = o.optString("type", "");
                if ("text".equals(type)) {
                    String msg = o.optString("msg", "");
                    if (msg.isEmpty()) return true;
                    // 3.4.0 standard OpenAI tool mode must buffer the native YuanBao text
                    // protocol until the complete turn is known. We then translate only the
                    // explicit tool-call envelope into standard message.tool_calls. No action/
                    // params wrapper and no <yb_tool_call> text is exposed to the client.
                    if (c.standardToolMode || c.agentStyle) c.agentBuf.append(msg);
                    else c.encoder.emitDelta(msg);
                    return true;
                }
                if ("meta".equals(type)) {
                    String stopReason = o.optString("stopReason", "");
                    boolean endConv = o.optBoolean("endConv", false);
                    if ("stop".equals(stopReason) || endConv) finish(req, false, "");
                    return true;
                }
                if ("modelError".equals(type)) {
                    finish(req, true, o.optString("modelErrorMsg", "model error"));
                    return true;
                }
                return true;
            } catch (Throwable ignored) { return false; }
        }
        return false;
    }

    private static void finish(String req, boolean error, String message) {
        RequestContext c = REQUESTS.get(req);
        if (c == null) return;

        if (c.standardToolMode && !error) {
            String raw = c.agentBuf.toString();
            JSONArray calls = extractStandardToolCalls(raw);
            String visible = stripToolProtocol(raw);
            c.standardToolCalls = calls;
            c.standardVisibleText = visible;
            if (calls.length() > 0) {
                for (int i = 0; i < calls.length(); i++) {
                    try {
                        JSONObject tc = calls.optJSONObject(i);
                        if (tc == null) continue;
                        JSONObject fn = tc.optJSONObject("function");
                        String name = fn == null ? tc.optString("name", "") : fn.optString("name", "");
                        String args = fn == null ? tc.optString("arguments", "{}") : fn.optString("arguments", "{}");
                        c.encoder.emitToolCall(name, args, i);
                    } catch (Throwable ignored) {}
                }
                c.encoder.doneToolCalls();
                log("[OPENAI-TOOLS] native YuanBao -> standard tool_calls=" + calls.length());
                cleanup(req);
                log("[SSE] tool_calls req=" + req);
                return;
            }
            if (!visible.isEmpty()) c.encoder.emitDelta(visible);
            c.encoder.done();
            cleanup(req);
            log("[SSE] close standard-tools(no-call) req=" + req);
            return;
        }

        if (c.agentStyle && !error) {
            String text = c.agentBuf.toString();
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"action\":\"final_answer\",");
            sb.append("\"type\":\"text\",");
            sb.append("\"content\":").append(OpenAiSseEncoder.json(text)).append(",");
            sb.append("\"text\":").append(OpenAiSseEncoder.json(text)).append(",");
            sb.append("\"message\":").append(OpenAiSseEncoder.json(text)).append(",");
            sb.append("\"reply\":").append(OpenAiSseEncoder.json(text)).append(",");
            sb.append("\"answer\":").append(OpenAiSseEncoder.json(text)).append(",");
            sb.append("\"params\":{\"content\":").append(OpenAiSseEncoder.json(text))
              .append(",\"text\":").append(OpenAiSseEncoder.json(text)).append("}");
            sb.append("}");
            c.encoder.emitDelta(sb.toString());
            log("[AGENT] wrapped generic textLen=" + text.length());
        }
        if (error) c.encoder.error(message); else c.encoder.done();
        cleanup(req);
        log("[SSE] " + (error ? "failure" : "close") + " req=" + req);
    }

    private static JSONArray extractStandardToolCalls(String raw) {
        JSONArray out = new JSONArray();
        if (raw == null || raw.trim().isEmpty()) return out;
        // Primary bridge protocol emitted by the native YuanBao prompt.
        Pattern tag = Pattern.compile("<yb_tool_call>\\s*(.*?)\\s*</yb_tool_call>", Pattern.DOTALL);
        Matcher m = tag.matcher(raw);
        while (m.find()) {
            try {
                JSONObject o = new JSONObject(m.group(1).trim());
                String name = o.optString("name", o.optString("tool", ""));
                JSONObject args = o.optJSONObject("arguments");
                if (args == null) args = o.optJSONObject("args");
                if (!name.isEmpty() && args != null) {
                    out.put(new JSONObject()
                            .put("id", "call_yb_" + SEQ.incrementAndGet())
                            .put("type", "function")
                            .put("function", new JSONObject().put("name", name).put("arguments", args.toString())));
                }
            } catch (Throwable ignored) {}
        }
        if (out.length() > 0) return out;
        // Compatibility: if the model already emitted an OpenAI-style JSON envelope, preserve it.
        try {
            Matcher rawJson = Pattern.compile("\\{\\s*\\\"tool_calls\\\"\\s*:", Pattern.DOTALL).matcher(raw);
            if (rawJson.find()) {
                String obj = balancedJsonObject(raw, rawJson.start());
                if (obj != null) {
                    JSONArray a = new JSONObject(obj).optJSONArray("tool_calls");
                    if (a != null) for (int i=0;i<a.length();i++) if(a.optJSONObject(i)!=null) out.put(a.optJSONObject(i));
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static String stripToolProtocol(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("(?s)<yb_tool_call>.*?</yb_tool_call>", "")
                .replaceAll("(?s)<tool_call>.*?</tool_call>", "")
                .trim();
    }

    private static String balancedJsonObject(String s, int start) {
        int i=start, depth=0; boolean str=false, esc=false, begun=false;
        for(;i<s.length();i++){
            char ch=s.charAt(i);
            if(str){ if(esc) esc=false; else if(ch=='\\') esc=true; else if(ch=='\"') str=false; continue; }
            if(ch=='\"'){str=true;continue;}
            if(ch=='{'){depth++;begun=true;}
            else if(ch=='}' && begun){depth--;if(depth==0)return s.substring(start,i+1);}
        }
        return null;
    }

    private static void cleanup(String req) {
        RequestContext c = REQUESTS.remove(req);
        // IMPORTANT: non-streaming requests collect the answer in a ByteArrayOutputStream and
        // must not close the HTTP client socket here. The handler still needs that socket to write
        // the final application/json response. Closing it here was the direct cause of
        // "java.net.SocketException: Socket closed" / client-side "unexpected end of stream".
        if (c != null && c.streaming) c.closeSocket();
        for (Map.Entry<String,String> e : TOKEN_TO_REQ.entrySet()) if (req.equals(e.getValue())) TOKEN_TO_REQ.remove(e.getKey(), req);
        for (Map.Entry<Integer,String> e : MSG_TO_REQ.entrySet()) if (req.equals(e.getValue())) MSG_TO_REQ.remove(e.getKey(), req);
    }

    private static void trigger(String req, String prompt) {
        Object ctx;
        synchronized (CTX_LOCK) { ctx = activeI6.get(); }
        if (ctx == null) { finish(req, true, "YuanBao conversation context is not ready"); return; }
        synchronized (PENDING) { PENDING.addLast(req); }
        MAIN.post(() -> {
            try {
                CURRENT_REQ.set(req);
                BRIDGE_TRIGGER.set(Boolean.TRUE);
                XposedHelpers.callMethod(ctx, "s3", prompt, "", Boolean.FALSE);
            } catch (Throwable t) {
                synchronized (PENDING) { PENDING.remove(req); }
                finish(req, true, "send trigger failed: " + t.getClass().getSimpleName());
                log("[SEND] trigger failed " + t);
            } finally {
                BRIDGE_TRIGGER.remove();
                CURRENT_REQ.remove();
            }
        });
    }

    private static String newReq() { return "REQ-" + SEQ.getAndIncrement(); }
    private static String peekPending() { synchronized (PENDING) { return PENDING.peekFirst(); } }
    private static String pollPending() { synchronized (PENDING) { return PENDING.pollFirst(); } }

    /**
     * === v3.3 新增 ===
     * 剥离 system prompt 里客户端注入的旧 Memory / Plan 段落。
     * 出现任意一个锚点即从该处截断到末尾。
     */
    private static String stripStaleContext(String sp) {
        if (sp == null || sp.isEmpty()) return sp;
        int earliest = -1;
        for (String anchor : STALE_ANCHORS) {
            int idx = sp.indexOf(anchor);
            if (idx >= 0 && (earliest < 0 || idx < earliest)) earliest = idx;
        }
        if (earliest < 0) return sp;
        String cleaned = sp.substring(0, earliest);
        log("[STRIP] system prompt " + sp.length() + " -> " + cleaned.length());
        return cleaned;
    }

    private static final class NativeAgentState {
        final String originalPrompt;
        final Object context;
        final StringBuilder text = new StringBuilder();
        volatile boolean active = true;
        volatile boolean processing;
        volatile boolean waitingForToolResult;
        volatile int round;
        volatile long generation;
        String lastChunk = "";
        boolean uiProtocolMode;
        NativeAgentState(String p, Object c) { originalPrompt=p; context=c; }
        synchronized void append(String s) {
            if (s == null || s.isEmpty()) return;
            if (lastChunk.equals(s)) return;
            if (s.startsWith(text.toString()) && s.length() >= text.length()) text.setLength(0);
            if (s.startsWith(text.toString())) text.append(s.substring(text.length()));
            else if (!text.toString().endsWith(s)) text.append(s);
            lastChunk=s;
            if (text.length() > 30000) text.delete(0, text.length()-30000);
        }
    }

    private static final class RequestContext {
        final String id; final String model; final Socket socket; final OpenAiSseEncoder encoder;
        // Streaming responses own the client socket until [DONE]. Non-streaming responses use an
        // in-memory buffer and the HTTP handler must keep the socket open until it writes JSON.
        final boolean streaming;
        final boolean agentStyle; final String agentActionName;
        final boolean standardToolMode;
        final StringBuilder agentBuf = new StringBuilder();
        volatile JSONArray standardToolCalls = new JSONArray();
        volatile String standardVisibleText = "";
        final CountDownLatch done = new CountDownLatch(1);
        RequestContext(String id, String model, Socket socket, OutputStream out, boolean streaming,
                       boolean agentStyle, String agentActionName, boolean standardToolMode) {
            this.id=id; this.model=model; this.socket=socket; this.streaming=streaming;
            this.encoder=new OpenAiSseEncoder(this, out);
            this.agentStyle=agentStyle; this.agentActionName=agentActionName;
            this.standardToolMode=standardToolMode;
        }
        void closeSocket() { try { socket.close(); } catch (Throwable ignored) {} done.countDown(); }
    }

    private static final class OpenAiSseEncoder {
        final RequestContext owner; final OutputStream out; volatile boolean done;
        final StringBuilder fullText = new StringBuilder();
        OpenAiSseEncoder(RequestContext owner, OutputStream out){this.owner=owner;this.out=out;}
        synchronized void headers(){ write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-cache\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n"); }
        synchronized void emitRole(){ if(!done) write(chunk("{\"role\":\"assistant\",\"content\":null}", null)); }
        synchronized void emitDelta(String s){ if(done||s==null||s.isEmpty())return; fullText.append(s); write(chunk("{\"content\":"+json(s)+"}", null)); }
        synchronized void emitToolCall(String name, String argsJson, int index){
            if(done)return;
            String tc = "{\"tool_calls\":[{\"index\":" + index + ",\"id\":\"call_" + owner.id + "_" + index + "\",\"type\":\"function\",\"function\":{\"name\":" + json(name) + ",\"arguments\":" + json(argsJson) + "}}]}";
            write(chunk(tc, null));
        }
        synchronized void doneToolCalls(){ if(done)return;done=true; write(chunk("{\"content\":null}","tool_calls")); write("data: [DONE]\n\n"); owner.done.countDown(); }
        synchronized void done(){ if(done)return;done=true; write(chunk("{\"content\":null}","stop")); write("data: [DONE]\n\n"); owner.done.countDown(); }
        synchronized void error(String s){ if(done)return;done=true; write(chunk("{\"content\":"+json(s==null?"error":s)+"}","stop")); write("data: [DONE]\n\n"); owner.done.countDown(); }
        private String chunk(String delta,String finish){
            return "data: {\"id\":"+json(owner.id)+",\"object\":\"chat.completion.chunk\",\"created\":"+(System.currentTimeMillis()/1000)+",\"model\":"+json(owner.model)+",\"choices\":[{\"index\":0,\"delta\":"+delta+",\"finish_reason\":"+(finish==null?"null":"\""+finish+"\"")+"}]}\n\n";
        }
        private void write(String s){ try{out.write(s.getBytes(StandardCharsets.UTF_8));out.flush();}catch(IOException e){done=true;owner.done.countDown();} }
        static String json(String s){
            if(s==null)return "null";
            StringBuilder b=new StringBuilder("\"");
            for(int i=0;i<s.length();i++){
                char c=s.charAt(i);
                switch(c){
                    case '"':b.append("\\\"");break;
                    case '\\':b.append("\\\\");break;
                    case '\n':b.append("\\n");break;
                    case '\r':b.append("\\r");break;
                    case '\t':b.append("\\t");break;
                    default:if(c<32)b.append(String.format(Locale.US,"\\u%04x",(int)c));else b.append(c);
                }
            }
            return b.append('"').toString();
        }
    }

    private static final class LocalHttpServer implements Runnable {
        private static volatile boolean started;
        static synchronized void start(){
            if(started)return;
            started=true;
            Thread t=new Thread(new LocalHttpServer(),"YB-HTTP");
            t.setDaemon(true); t.start();
            log("[HTTP] starting 127.0.0.1:"+PORT);
        }
        @Override public void run(){
            try(ServerSocket ss=new ServerSocket(PORT,32,InetAddress.getByName("127.0.0.1"))){
                while(true){ Socket s=ss.accept(); Thread t=new Thread(()->handle(s),"YB-HTTP-client"); t.setDaemon(true); t.start(); }
            }catch(Throwable t){started=false;log("[HTTP] stopped "+t);}
        }

        private static void handle(Socket socket){
            boolean handoff=false;
            try {
                socket.setSoTimeout(90_000);
                BufferedInputStream in=new BufferedInputStream(socket.getInputStream());
                OutputStream out=socket.getOutputStream();
                String requestLine=readLine(in); if(requestLine==null)return;
                String[] first=requestLine.split(" "); if(first.length<2)return;
                String method=first[0], path=first[1];
                Map<String,String> headers=new HashMap<>();
                String line; int len=0;
                while((line=readLine(in))!=null&&!line.isEmpty()){
                    int k=line.indexOf(':');
                    if(k>0){
                        String key=line.substring(0,k).trim().toLowerCase(Locale.ROOT);
                        String val=line.substring(k+1).trim();
                        headers.put(key,val);
                        if("content-length".equals(key))try{len=Integer.parseInt(val);}catch(Exception ignored){}
                    }
                }
                if("OPTIONS".equalsIgnoreCase(method)){
                    writeRaw(out,"HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nConnection: close\r\n\r\n");
                    return;
                }
                if("GET".equalsIgnoreCase(method)&&"/health".equals(path)){
                    writeJson(out,200,"{\"status\":\"ok\",\"service\":\"yuanbao-local-bridge\",\"port\":8318}");
                    return;
                }
                if("GET".equalsIgnoreCase(method)&&"/v1/models".equals(path)){
                    writeJson(out,200,"{\"object\":\"list\",\"data\":[{\"id\":\"yuanbao\",\"object\":\"model\",\"owned_by\":\"local-yuanbao-bridge\",\"capabilities\":{\"tools\":true,\"function_calling\":true,\"streaming\":true,\"chat\":true}}]}");
                    return;
                }
                if("POST".equalsIgnoreCase(method)&&"/v1/chat/completions".equals(path)){
                    byte[] body=readBody(in,headers,len);
                    String bodyStr=new String(body,StandardCharsets.UTF_8);
                    log("[REQ-BODY] len=" + bodyStr.length() + " head=" + bodyStr.substring(0, Math.min(800, bodyStr.length())));
                    JSONObject req=new JSONObject(bodyStr);
                    String model=req.optString("model","yuanbao");
                    boolean stream=req.optBoolean("stream",true);

                    String sysPrompt = collectSystemPrompt(req);
                    if (sysPrompt != null && !sysPrompt.isEmpty()) logFullSystemPrompt(sysPrompt);
                    // v3.3：agentStyle 用清洗后的 system prompt 检测
                    String sysPromptClean = stripStaleContext(sysPrompt);
                    JSONArray tools = req.optJSONArray("tools");
                    int toolsLen = tools == null ? 0 : tools.length();
                    boolean agentStyle = detectAgentStyle(sysPromptClean, toolsLen);
                    String agentActionName = agentStyle ? extractRespondActionName(sysPromptClean) : null;

                    String toolChoice = req.optString("tool_choice", "");
                    log("[REQ] model=" + model + " stream=" + stream + " agentStyle=" + agentStyle
                        + " agentAction=" + agentActionName + " tools.len=" + toolsLen
                        + " tool_choice=" + toolChoice + " sysLen=" + (sysPrompt == null ? 0 : sysPrompt.length())
                        + " cleanSysLen=" + (sysPromptClean == null ? 0 : sysPromptClean.length()));

                    String prompt=extractPrompt(req);
                    if(prompt==null||prompt.trim().isEmpty()){
                        String pingId = newReq();
                        String response = "{\"id\":" + OpenAiSseEncoder.json(pingId) + ",\"object\":\"chat.completion\",\"created\":" + (System.currentTimeMillis() / 1000) + ",\"model\":" + OpenAiSseEncoder.json(model) + ",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"pong\"},\"finish_reason\":\"stop\"}]}";
                        writeJson(out, 200, response);
                        return;
                    }

                    String id=newReq();
                    if(stream){
                        RequestContext c=new RequestContext(id,model,socket,out,true,agentStyle,agentActionName,toolsLen > 0);
                        REQUESTS.put(id,c);
                        c.encoder.headers();
                        c.encoder.emitRole();
                        trigger(id,prompt);
                        scheduleTimeout(id);
                        handoff=true;
                        return;
                    }
                    ByteArrayOutputStream buffer=new ByteArrayOutputStream();
                    RequestContext c=new RequestContext(id,model,socket,buffer,false,agentStyle,agentActionName,toolsLen > 0);
                    REQUESTS.put(id,c);
                    trigger(id,prompt);
                    boolean completed=c.done.await(STREAM_TIMEOUT_MS,TimeUnit.MILLISECONDS);
                    REQUESTS.remove(id);
                    if(!completed){c.encoder.error("timeout");}
                    String text=c.standardToolMode ? c.standardVisibleText : c.encoder.fullText.toString();
                    StringBuilder response=new StringBuilder();
                    response.append("{\"id\":").append(OpenAiSseEncoder.json(id))
                           .append(",\"object\":\"chat.completion\",\"created\":").append(System.currentTimeMillis()/1000)
                           .append(",\"model\":").append(OpenAiSseEncoder.json(model)).append(",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":")
                           .append(OpenAiSseEncoder.json(text));
                    if(c.standardToolMode && c.standardToolCalls != null && c.standardToolCalls.length()>0){
                        response.append(",\"tool_calls\":").append(c.standardToolCalls.toString());
                    }
                    response.append("},\"finish_reason\":\"")
                           .append(c.standardToolMode && c.standardToolCalls != null && c.standardToolCalls.length()>0 ? "tool_calls" : "stop")
                           .append("\"}]}" );
                    writeJson(out,200,response.toString());
                    return;
                }
                writeJson(out,404,"{\"error\":{\"message\":\"not found\"}}");
            }catch(Throwable t){
                log("[HTTP] request error "+t);
                try{if(!handoff)writeJson(socket.getOutputStream(),500,"{\"error\":{\"message\":"+OpenAiSseEncoder.json(t.toString())+"}}");}catch(Throwable ignored){}
            }finally {
                if(!handoff)try{socket.close();}catch(Throwable ignored){}
            }
        }

        private static void scheduleTimeout(final String id){
            new Thread(()->{
                try{Thread.sleep(STREAM_TIMEOUT_MS);}catch(InterruptedException ignored){return;}
                RequestContext c=REQUESTS.get(id);
                if(c!=null){c.encoder.error("timeout");cleanup(id);}
            },"YB-timeout").start();
        }

        private static String collectSystemPrompt(JSONObject req) {
            try {
                JSONArray a = req.optJSONArray("messages");
                if (a == null) return null;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject m = a.optJSONObject(i);
                    if (m == null) continue;
                    if (!"system".equalsIgnoreCase(m.optString("role", ""))) continue;
                    Object c = m.opt("content");
                    if (c instanceof String) sb.append((String) c).append("\n");
                    else if (c instanceof JSONArray) {
                        JSONArray ar = (JSONArray) c;
                        for (int j = 0; j < ar.length(); j++) {
                            JSONObject x = ar.optJSONObject(j);
                            if (x != null && "text".equals(x.optString("type"))) sb.append(x.optString("text")).append("\n");
                        }
                    }
                }
                return sb.length() == 0 ? null : sb.toString();
            } catch (Throwable ignored) { return null; }
        }

        private static boolean detectAgentStyle(String sp, int toolsLen) {
            if (toolsLen > 0) return false;
            if (sp == null || sp.isEmpty()) return false;
            if (sp.contains("\"action\"") || sp.contains("\"action\" :")) return true;
            if (sp.toLowerCase(Locale.ROOT).contains("respond with json")) return true;
            if (sp.toLowerCase(Locale.ROOT).contains("you must respond with")) return true;
            return false;
        }

        private static String extractRespondActionName(String sp) {
            if (sp == null || sp.isEmpty()) return null;
            LinkedHashSet<String> actions = new LinkedHashSet<>();
            try {
                Matcher m = Pattern.compile("\"action\"\\s*:\\s*\"([a-zA-Z_]+)\"").matcher(sp);
                while (m.find()) actions.add(m.group(1));
            } catch (Throwable ignored) {}
            String[] preferred = {"final_answer", "final", "respond", "reply", "chat", "message", "answer", "text", "output", "say"};
            for (String p : preferred) if (actions.contains(p)) return p;
            for (String a : actions) {
                String lower = a.toLowerCase(Locale.ROOT);
                for (String p : preferred) if (lower.contains(p)) return a;
            }
            return null;
        }

        private static void logFullSystemPrompt(String sp) {
            int total = sp.length();
            int chunks = (total + SYS_PROMPT_LOG_CHUNK - 1) / SYS_PROMPT_LOG_CHUNK;
            log("[SYS-PROMPT] totalLen=" + total + " chunks=" + chunks);
            for (int i = 0; i < total; i += SYS_PROMPT_LOG_CHUNK) {
                int end = Math.min(total, i + SYS_PROMPT_LOG_CHUNK);
                int idx = i / SYS_PROMPT_LOG_CHUNK + 1;
                log("[SYS-PROMPT #" + idx + "/" + chunks + "] " + sp.substring(i, end));
            }
        }

        /**
         * === v3.3 修改 ===
         * 对 role=="system" 的消息先 stripStaleContext 再拼入 prompt，
         * 从源头阻止旧 Memory/Plan 被送到元宝。
         */
        private static String extractPrompt(JSONObject req){
            try{
                JSONArray a=req.optJSONArray("messages");
                if(a==null)return null;
                StringBuilder sb=new StringBuilder();
                for(int i=0;i<a.length();i++){
                    JSONObject m=a.optJSONObject(i);
                    if(m==null)continue;
                    String role=m.optString("role","");
                    Object c=m.opt("content");
                    String text="";
                    if(c instanceof String) text=(String)c;
                    else if(c instanceof JSONArray){
                        StringBuilder b=new StringBuilder();
                        JSONArray ar=(JSONArray)c;
                        for(int j=0;j<ar.length();j++){
                            JSONObject x=ar.optJSONObject(j);
                            if(x!=null&&"text".equals(x.optString("type")))b.append(x.optString("text"));
                        }
                        text=b.toString();
                    }
                    if(text.isEmpty())continue;

                    if("system".equalsIgnoreCase(role)){
                        String cleaned = stripStaleContext(text);
                        sb.append("[SYSTEM INSTRUCTIONS]\n").append(cleaned).append("\n[/SYSTEM INSTRUCTIONS]\n\n");
                    } else if("user".equalsIgnoreCase(role)){
                        sb.append("[USER]\n").append(text).append("\n[/USER]\n\n");
                    } else if("assistant".equalsIgnoreCase(role)){
                        sb.append("[ASSISTANT]\n").append(text).append("\n[/ASSISTANT]\n\n");
                    } else {
                        sb.append("[").append(role).append("]\n").append(text).append("\n\n");
                    }
                }
                String full = sb.toString().trim();
                if (full.isEmpty()) return null;
                if (full.length() > MAX_PROMPT_CHARS) { log("[PROMPT] truncating " + full.length() + " -> " + MAX_PROMPT_CHARS); full = full.substring(0, MAX_PROMPT_CHARS) + "\n\n[truncated]"; }
                log("[PROMPT] totalLen=" + full.length() + " head=" + full.substring(0, Math.min(400, full.length())));
                return full;
            }catch(Throwable ignored){}
            return null;
        }

        private static byte[] readBody(InputStream in,Map<String,String> headers,int len)throws IOException{
            if(len>0)return readFully(in,len);
            String te=headers.get("transfer-encoding");
            if(te!=null&&te.toLowerCase(Locale.ROOT).contains("chunked"))return readChunked(in);
            return new byte[0];
        }
        private static byte[] readChunked(InputStream in)throws IOException{
            ByteArrayOutputStream b=new ByteArrayOutputStream();
            while(true){
                String line=readLine(in);
                if(line==null)throw new EOFException();
                int n=Integer.parseInt(line.trim().split(";",2)[0],16);
                if(n==0){readLine(in);break;}
                b.write(readFully(in,n)); readLine(in);
            }
            return b.toByteArray();
        }
        private static String readLine(InputStream in)throws IOException{
            ByteArrayOutputStream b=new ByteArrayOutputStream();
            int c;
            while((c=in.read())!=-1){
                if(c=='\n')break;
                if(c!='\r')b.write(c);
                if(b.size()>16384)throw new IOException("line too long");
            }
            if(c==-1&&b.size()==0)return null;
            return b.toString(StandardCharsets.UTF_8.name());
        }
        private static byte[] readFully(InputStream in,int n)throws IOException{
            byte[] b=new byte[n]; int p=0;
            while(p<n){ int r=in.read(b,p,n-p); if(r<0)throw new EOFException(); p+=r; }
            return b;
        }
        private static void writeRaw(OutputStream out,String s)throws IOException{ out.write(s.getBytes(StandardCharsets.UTF_8)); out.flush(); }
        private static void writeJson(OutputStream out,int code,String body)throws IOException{
            byte[] b=body.getBytes(StandardCharsets.UTF_8);
            String status=code==200?"OK":code==400?"Bad Request":code==404?"Not Found":"Internal Server Error";
            writeRaw(out,"HTTP/1.1 "+code+" "+status+"\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "+b.length+"\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n");
            out.write(b); out.flush();
        }
    }

    private static void initLog(XC_LoadPackage.LoadPackageParam lp){
        try{
            Class<?> at=XposedHelpers.findClass("android.app.ActivityThread",lp.classLoader);
            Object cur=XposedHelpers.callStaticMethod(at,"currentActivityThread");
            Object app=XposedHelpers.callMethod(cur,"getApplication");
            File dir=app instanceof Context?((Context)app).getExternalFilesDir(null):null;
            if(dir==null)dir=new File("/storage/emulated/0/Android/data/"+TARGET+"/files");
            if(!dir.exists())dir.mkdirs();
            logFile=new File(dir,"yuanbao_bridge.log");
        }catch(Throwable t){XposedBridge.log(TAG+" initLog "+t);}
    }
    private static Object getField(Object obj,String name){
        if(obj==null)return null;
        Class<?> c=obj.getClass();
        while(c!=null&&c!=Object.class){
            try{ Field f=c.getDeclaredField(name); f.setAccessible(true); return f.get(obj); }catch(Throwable ignored){}
            c=c.getSuperclass();
        }
        return null;
    }
    private static String safeStringField(Object obj,String name){ Object v=getField(obj,name); return v instanceof String?(String)v:null; }
    private static boolean trySetStringField(Object obj,String name,String value){
        if(obj==null)return false;
        Class<?> c=obj.getClass();
        while(c!=null&&c!=Object.class){
            try{ Field f=c.getDeclaredField(name); if(f.getType()==String.class){ f.setAccessible(true); f.set(obj,value); return true; } }catch(Throwable ignored){}
            c=c.getSuperclass();
        }
        return false;
    }
    static void log(String s){
        String line=new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS",Locale.US).format(new Date())+" "+s;
        XposedBridge.log(TAG+": "+line);
        if(logFile!=null)try(FileWriter w=new FileWriter(logFile,true)){w.append(line).append('\n');}catch(IOException ignored){}
    }
}
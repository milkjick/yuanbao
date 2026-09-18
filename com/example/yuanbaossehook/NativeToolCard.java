package com.example.yuanbaossehook;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 3.4.5: MCP Tool Card is a real item of YuanBao's chat RecyclerView.
 *
 * We do NOT replace YuanBao's adapter. Instead, Xposed hooks the existing
 * concrete adapter methods and reserves one additional item range at the end:
 *
 *   original items [0 .. baseCount-1]
 *   MCP cards      [baseCount .. baseCount + cardCount-1]
 *
 * This keeps YuanBao's own message adapter/data model intact while making the
 * tool card participate in RecyclerView's normal ViewHolder lifecycle.
 */
final class NativeToolCard {
    private static final int CARD_VIEW_TYPE = Integer.MIN_VALUE + 345;
    private static final long CARD_ID_BASE = 0x5f4d435000000000L;

    private static WeakReference<Activity> activityRef = new WeakReference<>(null);
    private static WeakReference<View> chatRef = new WeakReference<>(null);
    private static WeakReference<Object> adapterRef = new WeakReference<>(null);
    private static boolean hooksInstalled;
    private static final WeakHashMap<Object, Boolean> TARGET_ADAPTERS = new WeakHashMap<>();
    private static final WeakHashMap<Class<?>, Boolean> HOOKED_ADAPTER_CLASSES = new WeakHashMap<>();
    private static int baseCount = -1;
    private static long nextCardId = 1;

    private static final List<CardState> cards = new ArrayList<>();
    private static CardState current;

    private static int dp(float v) {
        Activity a = activityRef.get();
        float d = a == null ? 1f : a.getResources().getDisplayMetrics().density;
        return (int)(v * d + 0.5f);
    }

    static void bindActivity(Activity a) {
        if (a == null) return;
        activityRef = new WeakReference<>(a);
        a.runOnUiThread(() -> attach(a, 0));
    }

    static void unbindActivity(Activity a) {
        Activity cur = activityRef.get();
        if (cur == a) {
            chatRef = new WeakReference<>(null);
            // Keep adapter hook registrations alive. Xposed hooks are process-wide;
            // clearing the registration here would cause duplicate hooks when the
            // Activity is resumed with the same adapter class.
            activityRef = new WeakReference<>(null);
        }
    }

    /** Bind the visible native card to the durable MCP Tasks record. The UI polls
     * the real persisted task state; it never invents progress locally. */
    static void bindDurableTask(final String taskId) {
        if (taskId == null || taskId.trim().isEmpty()) return;
        post(() -> {
            CardState c = ensureCurrent();
            if (c == null) return;
            c.taskId = taskId.trim();
            c.status = "● 任务已持久化 · 等待工具链";
            refreshCard(c);
            pollDurableTask(c, taskId.trim(), 0);
        });
    }

    private static void pollDurableTask(final CardState c, final String taskId, final int attempt) {
        if (c == null || !taskId.equals(c.taskId)) return;
        try {
            JSONObject t = McpTaskStore.get(taskId);
            String status = t.optString("status", "working");
            c.taskStatus = status;
            c.taskMessage = t.optString("statusMessage", "");
            c.round = t.optInt("round", -1);
            c.next = t.optString("next", "");
            c.inputRequests = t.optJSONObject("inputRequests");
            if ("failed".equals(status)) { c.error = true; c.done = true; c.status = "✕ " + (c.taskMessage.isEmpty() ? "任务失败" : c.taskMessage); }
            else if ("cancelled".equals(status)) { c.done = true; c.status = "■ 已取消"; }
            else if ("completed".equals(status)) {
                c.done = true; c.status = "✓ 真实 MCP 任务已完成";
                JSONObject result = t.optJSONObject("result");
                if (result != null) c.finalResult = result.optString("content", result.toString());
            } else {
                c.status = "● " + (c.taskMessage.isEmpty() ? "工具链执行中" : c.taskMessage);
            }
            refreshCard(c);
            if (!c.done) {
                Activity a = activityRef.get();
                if (a != null) a.getWindow().getDecorView().postDelayed(() -> pollDurableTask(c, taskId, attempt + 1), Math.max(500L, t.optLong("pollIntervalMs", 1000L)));
            }
        } catch (Throwable e) {
            Activity a = activityRef.get();
            if (a != null && attempt < 120) a.getWindow().getDecorView().postDelayed(() -> pollDurableTask(c, taskId, attempt + 1), 1000L);
        }
    }

    static void beginAgent(String prompt) {
        post(() -> {
            CardState c = new CardState(nextCardId++, prompt == null ? "" : prompt.trim());
            cards.add(c);
            current = c;
            c.visible = true;
            ensureAttachedAndInsert(c);
        });
    }

    static void showParsedCalls(String modelText) {
        List<String[]> calls = parseCalls(modelText);
        if (calls.isEmpty()) return;
        post(() -> {
            CardState c = ensureCurrent();
            if (c == null) return;
            for (String[] call : calls) addTool(c, call[0], call[1]);
            refreshCard(c);
        });
    }

    static void beginTool(String name, String args) { beginTool(name, args, null); }

    static void beginTool(String name, String args, String displayName) {
        post(() -> {
            CardState c = ensureCurrent();
            if (c == null) return;
            addTool(c, name, args, displayName);
            setStatus(c, "● 正在执行 · " + safe(name));
            refreshCard(c);
        });
    }

    static void finishTool(String name, String result, boolean error) {
        post(() -> {
            CardState c = current;
            if (c == null) return;
            for (int i = c.rows.size() - 1; i >= 0; i--) {
                ToolRow r = c.rows.get(i);
                if (r.name.equals(name) && !r.finished) {
                    r.finished = true;
                    r.error = error;
                    r.result = result == null ? "" : result;
                    break;
                }
            }
            setStatus(c, error ? "✕ 执行失败" : "✓ 已完成 · MCP 结果已返回");
            refreshCard(c);
        });
    }

    static void setStatus(final String s, final boolean busy) {
        post(() -> {
            CardState c = current;
            if (c == null) return;
            setStatus(c, (busy ? "RUNNING: " : "DONE: ") + safe(s));
            refreshCard(c);
        });
    }

    static void finishAgent() {
        post(() -> {
            CardState c = current;
            if (c == null) return;
            c.done = true;
            setStatus(c, "✓ 已完成");
            refreshCard(c);
        });
    }

    private static CardState ensureCurrent() {
        if (current != null) return current;
        CardState c = new CardState(nextCardId++, "MCP 工具调用");
        cards.add(c);
        current = c;
        c.visible = true;
        ensureAttachedAndInsert(c);
        return c;
    }

    private static void ensureAttachedAndInsert(CardState c) {
        Activity a = activityRef.get();
        if (a == null) return;
        attach(a, 0);
        Object adapter = adapterRef.get();
        if (adapter == null || !hooksInstalled) return;

        // beginAgent already appended c to cards. Insert exactly one new
        // RecyclerView item at the original adapter's current item count.
        int n = baseCount;
        if (n < 0) {
            n = readBaseCount(adapter);
            baseCount = n;
        }
        if (!c.inserted) {
            c.inserted = true;
            notifyAdapterInserted(adapter, n + cards.indexOf(c));
        }
    }

    private static void attach(Activity a, int attempt) {
        if (a == null || a.isFinishing()) return;
        View chat = findChatRecycler(a);
        if (chat == null || chat.getWidth() <= 0 || chat.getHeight() <= 0) {
            if (attempt < 12) {
                a.getWindow().getDecorView().postDelayed(() -> attach(a, attempt + 1), 250L);
            }
            return;
        }
        chatRef = new WeakReference<>(chat);
        Object adapter = getAdapter(chat);
        if (adapter == null) {
            if (attempt < 12) {
                chat.postDelayed(() -> attach(a, attempt + 1), 250L);
            }
            return;
        }
        Object previousAdapter = adapterRef.get();
        boolean adapterChanged = previousAdapter != null && previousAdapter != adapter;
        if (adapterChanged) {
            // A different conversation/list adapter normally means a different
            // chat dataset. Do not leak cards into the new conversation.
            cards.clear();
            current = null;
            baseCount = -1;
        }
        adapterRef = new WeakReference<>(adapter);
        synchronized (TARGET_ADAPTERS) { TARGET_ADAPTERS.put(adapter, Boolean.TRUE); }
        if (!HOOKED_ADAPTER_CLASSES.containsKey(adapter.getClass())) {
            installAdapterHooks(adapter);
        } else {
            hooksInstalled = true;
        }
        // readBaseCount() must only be called before cards are inserted. Once our
        // getItemCount hook is active, invoking it returns baseCount + cards.size(),
        // which would otherwise recursively inflate the stored base count.
        if (baseCount < 0) baseCount = readBaseCount(adapter);
        // If a card was created before the adapter became available, insert it now.
        if (hooksInstalled) {
            for (CardState c : cards) {
                if (!c.inserted) {
                    c.inserted = true;
                    int pos = baseCount + indexOfCard(c);
                    notifyAdapterInserted(adapter, pos);
                }
            }
        }
    }

    private static int indexOfCard(CardState c) {
        int i = cards.indexOf(c);
        return i < 0 ? 0 : i;
    }

    private static Object getAdapter(View chat) {
        try {
            return XposedHelpers.callMethod(chat, "getAdapter");
        } catch (Throwable t) {
            return null;
        }
    }

    private static int readBaseCount(Object adapter) {
        try {
            Method m = findMethod(adapter.getClass(), "getItemCount", 0);
            if (m == null) return 0;
            m.setAccessible(true);
            Object v = m.invoke(adapter);
            return v instanceof Integer ? (Integer)v : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void notifyAdapterInserted(Object adapter, int position) {
        try {
            // RecyclerView.Adapter.notifyItemInserted is inherited and public.
            XposedHelpers.callMethod(adapter, "notifyItemInserted", position);
        } catch (Throwable t) {
            log("notifyItemInserted failed: " + t);
            try {
                Method m = findMethod(adapter.getClass(), "notifyItemInserted", 1);
                if (m != null) {
                    m.setAccessible(true);
                    m.invoke(adapter, position);
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void installAdapterHooks(Object adapter) {
        final Class<?> cls = adapter.getClass();
        boolean count = false, type = false, create = false, bind = false, id = false;
        try {
            // getItemCount must be the adapter's concrete implementation.
            Method m = findMethod(cls, "getItemCount", 0);
            if (m != null) {
                hookCount(m, adapter);
                count = true;
            }

            Method vt = findMethod(cls, "getItemViewType", 1);
            if (vt != null) {
                hookViewType(vt, adapter);
                type = true;
            } else {
                Class<?> base = findRecyclerAdapterBase(cls.getClassLoader());
                Method bm = findMethod(base, "getItemViewType", 1);
                if (bm != null) {
                    hookViewType(bm, adapter);
                    type = true;
                }
            }

            Method createM = findCreateMethod(cls);
            if (createM != null) {
                hookCreate(createM, adapter);
                create = true;
            }

            Method bindM = findBindMethod(cls);
            if (bindM != null) {
                hookBind(bindM, adapter);
                bind = true;
            }

            Method idM = findMethod(cls, "getItemId", 1);
            if (idM != null) {
                hookId(idM, adapter);
                id = true;
            } else {
                Class<?> base = findRecyclerAdapterBase(cls.getClassLoader());
                Method bm = findMethod(base, "getItemId", 1);
                if (bm != null) {
                    hookId(bm, adapter);
                    id = true;
                }
            }
        } catch (Throwable t) {
            log("adapter hook error: " + t);
        }
        hooksInstalled = count && type && create && bind;
        if (hooksInstalled) HOOKED_ADAPTER_CLASSES.put(cls, Boolean.TRUE);
        log("[CHAT-ADAPTER] " + cls.getName() + " count=" + count + " type=" + type +
                " create=" + create + " bind=" + bind + " id=" + id);
    }

    private static boolean isTargetAdapter(Object adapter) {
        synchronized (TARGET_ADAPTERS) { return TARGET_ADAPTERS.containsKey(adapter); }
    }

    private static void hookCount(Method m, final Object adapter) {
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!isTargetAdapter(p.thisObject)) return;
                int n = toInt(p.getResult(), 0);
                baseCount = n;
                if (!cards.isEmpty()) p.setResult(n + cards.size());
            }
        });
    }

    private static void hookViewType(Method m, final Object adapter) {
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!isTargetAdapter(p.thisObject) || p.args == null || p.args.length != 1) return;
                int position = toInt(p.args[0], -1);
                int n = currentBaseCount();
                if (isCardPosition(position, n)) p.setResult(CARD_VIEW_TYPE);
            }
        });
    }

    private static void hookCreate(Method m, final Object adapter) {
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!isTargetAdapter(p.thisObject) || p.args == null || p.args.length != 2) return;
                int type = toInt(p.args[1], 0);
                if (type != CARD_VIEW_TYPE) return;
                try {
                    View cardView = buildCardView(activityRef.get());
                    Class<?> returnType = m.getReturnType();
                    Object holder = instantiateHolder(returnType, cardView, p.thisObject);
                    if (holder != null) {
                        p.setResult(holder);
                        p.setThrowable(null);
                    } else {
                        log("[CHAT-ADAPTER] unable to instantiate ViewHolder " + returnType);
                    }
                } catch (Throwable t) {
                    log("[CHAT-ADAPTER] create card holder failed: " + t);
                }
            }
        });
    }

    private static void hookBind(Method m, final Object adapter) {
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!isTargetAdapter(p.thisObject) || p.args == null || p.args.length != 2) return;
                int position = toInt(p.args[1], -1);
                int n = currentBaseCount();
                if (!isCardPosition(position, n)) return;
                int idx = position - n;
                if (idx < 0 || idx >= cards.size()) return;
                CardState c = cards.get(idx);
                Object holder = p.args[0];
                View item = getItemView(holder);
                if (item instanceof LinearLayout) {
                    c.itemView = item;
                    renderCard(c, (LinearLayout)item);
                }
                // Do not call YuanBao's normal message binder for our item.
                p.setResult(null);
            }
        });
    }

    private static void hookId(Method m, final Object adapter) {
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!isTargetAdapter(p.thisObject) || p.args == null || p.args.length != 1) return;
                int position = toInt(p.args[0], -1);
                int n = currentBaseCount();
                if (!isCardPosition(position, n)) return;
                int idx = position - n;
                if (idx >= 0 && idx < cards.size()) p.setResult(CARD_ID_BASE | cards.get(idx).id);
            }
        });
    }

    private static int currentBaseCount() {
        return Math.max(0, baseCount);
    }

    private static boolean isCardPosition(int position, int n) {
        return position >= n && position < n + cards.size();
    }

    private static Method findCreateMethod(Class<?> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if ("onCreateViewHolder".equals(m.getName()) && m.getParameterTypes().length == 2
                        && m.getParameterTypes()[1] == int.class) return m;
            }
        }
        return null;
    }

    private static Method findBindMethod(Class<?> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if ("onBindViewHolder".equals(m.getName()) && m.getParameterTypes().length == 2
                        && m.getParameterTypes()[1] == int.class) return m;
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> cls, String name, int argc) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (name.equals(m.getName()) && m.getParameterTypes().length == argc) return m;
            }
        }
        return null;
    }

    private static Class<?> findRecyclerAdapterBase(ClassLoader cl) {
        try { return Class.forName("androidx.recyclerview.widget.RecyclerView$Adapter", false, cl); }
        catch (Throwable t) { return null; }
    }

    private static Object instantiateHolder(Class<?> holderType, View item, Object adapter) {
        if (holderType == null || item == null) return null;
        try {
            Constructor<?> fallback = null;
            for (Constructor<?> c : holderType.getDeclaredConstructors()) {
                Class<?>[] ps = c.getParameterTypes();
                int viewIndex = -1;
                for (int i = 0; i < ps.length; i++) {
                    if (View.class.isAssignableFrom(ps[i])) { viewIndex = i; break; }
                }
                if (viewIndex < 0) continue;
                Object[] args = new Object[ps.length];
                boolean ok = true;
                for (int i = 0; i < ps.length; i++) {
                    if (i == viewIndex) {
                        args[i] = item;
                    } else if (adapter != null && ps[i].isAssignableFrom(adapter.getClass())) {
                        args[i] = adapter;
                    } else {
                        // Synthetic Kotlin/Java outer references are often the only
                        // extra constructor argument. Other required arguments cannot
                        // be reconstructed safely, so keep this constructor as a fallback.
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    c.setAccessible(true);
                    return c.newInstance(args);
                }
                if (ps.length == 1) fallback = c;
            }
            if (fallback != null) {
                fallback.setAccessible(true);
                return fallback.newInstance(item);
            }
            // The normal RecyclerView.ViewHolder has a protected View constructor.
            Constructor<?> c = holderType.getDeclaredConstructor(View.class);
            c.setAccessible(true);
            return c.newInstance(item);
        } catch (Throwable t) {
            log("instantiate ViewHolder failed: " + t);
            return null;
        }
    }

    private static View getItemView(Object holder) {
        if (holder == null) return null;
        try {
            Class<?> c = holder.getClass();
            while (c != null) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField("itemView");
                    f.setAccessible(true);
                    Object v = f.get(holder);
                    return v instanceof View ? (View)v : null;
                } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static View findChatRecycler(Activity a) {
        try {
            int id = a.getResources().getIdentifier("chat_recycler_view", "id", a.getPackageName());
            if (id != 0) {
                View v = a.findViewById(id);
                if (v != null) return v;
            }
        } catch (Throwable ignored) {}
        try {
            return findByClassName((ViewGroup)a.getWindow().getDecorView(), "RecyclerView");
        } catch (Throwable ignored) { return null; }
    }

    private static View findByClassName(View v, String suffix) {
        if (v == null) return null;
        String n = v.getClass().getName();
        if (n.endsWith(suffix) || n.contains(".RecyclerView")) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup)v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = findByClassName(g.getChildAt(i), suffix);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static View buildCardView(Activity a) {
        if (a == null) return new View(null);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.rgb(225, 225, 230));
        root.setBackground(bg);
        root.setElevation(0);
        root.setLayoutParams(new ViewGroup.LayoutParams(-1, -2));
        return root;
    }

    private static void renderCard(CardState c, LinearLayout root) {
        root.removeAllViews();
        LinearLayout header = new LinearLayout(root.getContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView ic = text(root.getContext(), "✦", 17, Color.rgb(55, 78, 155));
        ic.setPadding(0, 0, dp(7), 0);
        header.addView(ic);
        TextView title = text(root.getContext(), "MCP 工具调用", 14, Color.rgb(28, 28, 31));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView status = text(root.getContext(), c.status, 12,
                c.error ? Color.rgb(190,65,65) : Color.rgb(90,90,98));
        header.addView(status);
        root.addView(header);

        if (!c.taskId.isEmpty()) {
            LinearLayout taskBar = new LinearLayout(root.getContext());
            taskBar.setGravity(Gravity.CENTER_VERTICAL);
            TextView meta = text(root.getContext(), "Task " + shorten(c.taskId, 24) +
                    (c.round >= 0 ? " · round " + c.round : "") +
                    (!c.next.isEmpty() ? " · next " + shorten(c.next, 42) : ""), 10, Color.rgb(110,110,118));
            taskBar.addView(meta, new LinearLayout.LayoutParams(0, -2, 1));
            if (!c.done && "working".equals(c.taskStatus)) {
                TextView cancel = text(root.getContext(), "取消", 11, Color.rgb(170,65,65));
                cancel.setPadding(dp(10), dp(4), dp(4), dp(4));
                cancel.setOnClickListener(v -> {
                    try {
                        McpTaskStore.cancel(c.taskId);
                        c.status = "■ 已发送取消请求";
                        refreshCard(c);
                    } catch (Throwable e) {
                        c.status = "✕ 取消失败：" + e.getMessage();
                        c.error = true;
                        refreshCard(c);
                    }
                });
                taskBar.addView(cancel);
            }
            root.addView(taskBar);
        }

        if (!c.task.isEmpty()) {
            TextView t = text(root.getContext(), shorten(c.task, 500), 12, Color.rgb(100,100,108));
            t.setPadding(dp(2), dp(8), dp(2), dp(7));
            root.addView(t);
        }

        if (!c.finalResult.isEmpty()) {
            TextView fr = text(root.getContext(), "最终结果\n" + shorten(c.finalResult, 10000), 11, Color.rgb(60,60,68));
            fr.setTypeface(Typeface.MONOSPACE);
            fr.setPadding(dp(2), dp(7), dp(2), dp(9));
            root.addView(fr);
        }

        if (c.inputRequests != null && c.inputRequests.length() > 0 && !c.done) {
            root.addView(buildInputRequired(root.getContext(), c));
        }

        // Do not put the tool stream inside a fixed-height ScrollView.  The
        // MCP item is a real RecyclerView item, so its height must grow with
        // the conversation just like a normal YuanBao message.  A fixed
        // 190dp viewport made tool calls look like a separate mini-window and
        // prevented the tool result from flowing naturally with the chat.
        LinearLayout body = new LinearLayout(root.getContext());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(2), 0, 0);
        for (ToolRow r : c.rows) body.addView(buildTool(root.getContext(), r, c));
        root.addView(body, new LinearLayout.LayoutParams(-1, -2));
    }

    private static View buildInputRequired(android.content.Context ctx, CardState c) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(255, 248, 235));
        bg.setCornerRadius(dp(13));
        bg.setStroke(dp(1), Color.rgb(235, 205, 150));
        box.setBackground(bg);

        String message = "需要你的确认";
        try {
            JSONObject x = c.inputRequests.optJSONObject("confirm");
            if (x != null) {
                if (!x.optString("message", "").isEmpty()) message = x.optString("message");
                JSONObject params = x.optJSONObject("params");
                if (params != null && !params.optString("message", "").isEmpty()) message = params.optString("message");
            }
        } catch (Throwable ignored) {}
        TextView title = text(ctx, "⚠ " + message, 13, Color.rgb(105, 75, 20));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        box.addView(title);
        TextView desc = text(ctx, "这是 MCP input_required，不是模拟进度；选择后会通过 tasks/update 把真实 inputResponses 提交给任务。", 10, Color.rgb(120,100,70));
        desc.setPadding(0, dp(5), 0, dp(8));
        box.addView(desc);

        LinearLayout actions = new LinearLayout(ctx);
        actions.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        TextView reject = text(ctx, "取消写入", 11, Color.rgb(160,65,65));
        reject.setPadding(dp(12), dp(6), dp(12), dp(6));
        reject.setOnClickListener(v -> submitConfirmation(c, false));
        TextView accept = text(ctx, "确认并继续", 11, Color.rgb(45,100,65));
        accept.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        accept.setPadding(dp(12), dp(6), dp(4), dp(6));
        accept.setOnClickListener(v -> submitConfirmation(c, true));
        actions.addView(reject);
        actions.addView(accept);
        box.addView(actions);
        return box;
    }

    private static void submitConfirmation(CardState c, boolean confirmed) {
        if (c == null || c.taskId == null || c.taskId.isEmpty()) return;
        post(() -> {
            try {
                JSONObject content = new JSONObject().put("confirmed", confirmed);
                JSONObject response = new JSONObject().put("confirm", new JSONObject()
                        .put("action", confirmed ? "accept" : "decline")
                        .put("content", content));
                JSONObject t = McpIdeGateway.submitTaskInput(c.taskId, response);
                c.inputRequests = t.optJSONObject("inputRequests");
                c.taskStatus = t.optString("status", "");
                c.taskMessage = t.optString("statusMessage", "");
                if ("failed".equals(c.taskStatus)) {
                    c.error = true;
                    c.done = true;
                    c.status = "✕ " + c.taskMessage;
                } else if ("completed".equals(c.taskStatus)) {
                    c.done = true;
                    c.status = "✓ 已确认并完成写入";
                    JSONObject r = t.optJSONObject("result");
                    if (r != null) c.finalResult = r.toString();
                } else {
                    c.status = "● " + (c.taskMessage.isEmpty() ? "已提交，继续执行" : c.taskMessage);
                }
                refreshCard(c);
            } catch (Throwable e) {
                c.error = true;
                c.status = "✕ 提交确认失败：" + e.getMessage();
                refreshCard(c);
            }
        });
    }

    private static View buildTool(android.content.Context ctx, ToolRow r, CardState c) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(9), dp(12), dp(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(247,247,249));
        bg.setCornerRadius(dp(13));
        box.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(7);
        box.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(ctx);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView b = text(ctx, "◆", 13, Color.rgb(65,95,180));
        b.setPadding(0,0,dp(7),0);
        head.addView(b);
        TextView n = text(ctx, r.displayName == null || r.displayName.isEmpty() ? r.name : r.displayName, 14, Color.rgb(35,35,38));
        n.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        head.addView(n, new LinearLayout.LayoutParams(0,-2,1));
        TextView st = text(ctx, r.finished ? (r.error ? "失败" : "已完成") : "执行中", 12,
                r.error ? Color.rgb(190,65,65) : Color.rgb(90,90,98));
        head.addView(st);
        box.addView(head);

        TextView ar = text(ctx, r.expanded ? "参数\n" + r.args : "参数  " + shorten(r.args,1600), 11, Color.rgb(105,105,112));
        ar.setTypeface(Typeface.MONOSPACE);
        ar.setPadding(dp(22),dp(5),0,0);
        box.addView(ar);
        if (r.finished && !r.result.isEmpty()) {
            TextView re = text(ctx, r.expanded ? "结果\n" + r.result : "结果  " + shorten(r.result,4200), 11, Color.rgb(70,70,76));
            re.setTypeface(Typeface.MONOSPACE);
            re.setPadding(dp(22),dp(7),0,0);
            box.addView(re);
        }
        box.setOnClickListener(v -> {
            r.expanded = !r.expanded;
            View p = c.itemView;
            if (p instanceof LinearLayout) renderCard(c, (LinearLayout)p);
        });
        return box;
    }

    private static void addTool(CardState c, String name, String args) {
        addTool(c, name, args, null);
    }

    private static void addTool(CardState c, String name, String args, String displayName) {
        String raw = name == null ? "unknown" : name;
        c.rows.add(new ToolRow(raw, args == null ? "{}" : args, displayName));
    }

    private static void setStatus(CardState c, String s) {
        c.status = safe(s);
        c.error = c.status.contains("失败") || c.status.contains("✕");
    }

    private static void refreshCard(CardState c) {
        if (c.itemView instanceof LinearLayout) renderCard(c, (LinearLayout)c.itemView);
        View chat = chatRef.get();
        if (chat != null) chat.post(() -> {
            try {
                // Rebind only our item. notifyItemChanged is safe because the card
                // is already part of the adapter's item count.
                Object adapter = adapterRef.get();
                if (adapter != null && c.inserted) {
                    int pos = currentBaseCount() + indexOfCard(c);
                    XposedHelpers.callMethod(adapter, "notifyItemChanged", pos);
                    scrollChatToItem(pos);
                }
            } catch (Throwable ignored) {}
        });
    }


    /** Keep the MCP item attached to the live conversation tail.  This is
     * intentionally a RecyclerView scroll operation, not a floating overlay
     * reposition.  As YuanBao adds normal chat messages, getItemCount keeps
     * the MCP item at the end of the logical message stream. */
    private static void scrollChatToItem(final int position) {
        final View chat = chatRef.get();
        if (chat == null) return;
        try {
            chat.post(() -> {
                try {
                    XposedHelpers.callMethod(chat, "scrollToPosition", position);
                } catch (Throwable first) {
                    try {
                        XposedHelpers.callMethod(chat, "smoothScrollToPosition", position);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    private static void post(Runnable r) {
        Activity a = activityRef.get();
        if (a != null) a.runOnUiThread(r);
    }

    private static TextView text(android.content.Context ctx, String s, float size, int color) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setIncludeFontPadding(true);
        return t;
    }

    private static String safe(String s) { return s == null ? "" : s; }
    private static int toInt(Object x, int d) { return x instanceof Number ? ((Number)x).intValue() : d; }
    private static String shorten(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, Math.max(0,n-1)) + "…";
    }

    private static List<String[]> parseCalls(String s) {
        List<String[]> out = new ArrayList<>();
        if (s == null) return out;
        String[] tags = {"yb_tool_call", "tool_call", "call"};
        for (String tag : tags) {
            String o = "<" + tag + ">", c = "</" + tag + ">";
            int p = 0;
            while (true) {
                int a = s.indexOf(o, p);
                if (a < 0) break;
                int b = s.indexOf(c, a + o.length());
                if (b < 0) break;
                try {
                    JSONObject x = new JSONObject(s.substring(a + o.length(), b).trim());
                    String n = x.optString("name", x.optString("tool", ""));
                    JSONObject ar = x.optJSONObject("arguments");
                    if (ar == null) ar = x.optJSONObject("args");
                    out.add(new String[]{n, ar == null ? "{}" : ar.toString()});
                } catch (Throwable ignored) {}
                p = b + c.length();
            }
        }
        return out;
    }

    private static class CardState {
        final long id;
        final String task;
        final List<ToolRow> rows = new ArrayList<>();
        String status = "● 分析任务 · 准备调用工具";
        boolean visible, inserted, done, error;
        String taskId = "";
        String taskStatus = "";
        String taskMessage = "";
        String next = "";
        String finalResult = "";
        int round = -1;
        JSONObject inputRequests;
        View itemView;
        CardState(long id, String task) { this.id = id; this.task = task; }
    }

    private static class ToolRow {
        final String name, args;
        final String displayName;
        String result = "";
        boolean finished, error, expanded;
        ToolRow(String n, String a) { this(n, a, null); }
        ToolRow(String n, String a, String d) { name = n; args = a; displayName = d; }
    }

    private static void log(String s) {
        try { XposedBridge.log("[YB-ToolCard] " + s); } catch (Throwable ignored) {}
    }
}

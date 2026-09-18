package com.example.yuanbaossehook;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-app settings injected into YuanBao. This implementation intentionally has
 * zero AndroidX/Material runtime dependencies so an Xposed module can be built
 * offline in AIDE/Android Code Studio. The widgets are styled to match a
 * Material-You-like surface and use Android 12 system accent colors when
 * available.
 */
final class InAppSettings {
    private static final String TAG = "YB-InAppSettings";
    private static final int ID_FLOAT = 0x59424701;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newCachedThreadPool();
    private static volatile boolean showing;

    private static final int BG = Color.rgb(248, 247, 251);
    private static final int SURFACE = Color.WHITE;
    private static final int TEXT = Color.rgb(35, 34, 40);
    private static final int MUTED = Color.rgb(92, 90, 100);
    private static final int PRIMARY = Color.rgb(103, 80, 164);
    private static final int PRIMARY_CONTAINER = Color.rgb(234, 221, 255);

    private InAppSettings() {}

    static void attach(final Activity activity) {
        if (activity == null || activity.isFinishing() || isDestroyed(activity)) return;
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                try { attachNow(activity); } catch (Throwable e) { log("attach: " + e); }
            }
        }, 350L);
    }

    private static boolean isDestroyed(Activity a) {
        return Build.VERSION.SDK_INT >= 17 && a.isDestroyed();
    }

    private static void attachNow(final Activity activity) {
        if (activity.isFinishing() || isDestroyed(activity)) return;
        BridgeConfig.init(activity.getApplicationContext());
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null || content.findViewById(ID_FLOAT) != null) return;

        final Context c = activity;
        Button button = new Button(c);
        button.setId(ID_FLOAT);
        button.setText("⚙");
        button.setTextSize(20f);
        button.setTextColor(TEXT);
        button.setGravity(Gravity.CENTER);
        button.setAllCaps(false);
        button.setPadding(0, 0, 0, 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setBackground(roundDrawable(c, PRIMARY_CONTAINER, 26));
        button.setContentDescription("元宝本地 Agent 设置");
        button.setOnClickListener(v -> show(activity));

        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(dp(c, 52), dp(c, 52));
        lp.gravity = Gravity.TOP | Gravity.END;
        // Keep the floating settings button away from YuanBao's composer/send/stop controls.
        // The old bottom-end placement overlapped the native send button on some layouts.
        lp.setMargins(0, dp(c, 82), dp(c, 16), 0);
        content.addView(button, lp);
    }

    static void show(final Activity activity) {
        if (showing || activity == null || activity.isFinishing() || isDestroyed(activity)) return;
        showing = true;
        try {
            BridgeConfig.init(activity.getApplicationContext());
            BridgeConfig.initModule(activity.getApplicationContext());
            if (BridgeConfig.autoRefreshMcp()) IO.execute(() -> { try { McpIdeGateway.refreshForSettings(); } catch (Throwable ignored) {} });
            final Dialog dialog = new Dialog(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackground(roundDrawable(activity, BG, 28));

            LinearLayout bar = new LinearLayout(activity);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(dp(activity, 8), dp(activity, 10), dp(activity, 12), dp(activity, 8));
            TextView back = new TextView(activity);
            back.setText("‹"); back.setTextSize(34); back.setTextColor(TEXT); back.setGravity(Gravity.CENTER);
            bar.addView(back, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)));
            TextView title = label(activity, "元宝本地 Agent", 22, true);
            bar.addView(title, new LinearLayout.LayoutParams(0, dp(activity, 48), 1));
            root.addView(bar);
            back.setOnClickListener(v -> dialog.dismiss());

            LinearLayout tabs = new LinearLayout(activity);
            tabs.setPadding(dp(activity, 8), 0, dp(activity, 8), dp(activity, 8));
            String[] names = {"常规", "API", "MCP", "角色", "项目记忆"};
            for (int i = 0; i < names.length; i++) {
                final int index = i;
                TextView tab = label(activity, names[i], 14, false);
                tab.setGravity(Gravity.CENTER);
                tab.setBackground(roundDrawable(activity, i == 0 ? PRIMARY_CONTAINER : Color.TRANSPARENT, 20));
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, dp(activity, 40), 1);
                tlp.setMargins(dp(activity, 3), 0, dp(activity, 3), 0);
                tabs.addView(tab, tlp);
                tab.setOnClickListener(v -> {
                    View target = root.findViewWithTag("section_" + index);
                    if (target != null) target.getParent();
                });
            }
            root.addView(tabs);

            ScrollView scroll = new ScrollView(activity);
            scroll.setFillViewport(true);
            LinearLayout body = new LinearLayout(activity);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(activity, 14), 0, dp(activity, 14), dp(activity, 20));
            scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

            View general = buildGeneral(activity, body);
            View api = buildApi(activity, body);
            View mcp = buildMcp(activity, body);
            View role = buildRole(activity, body);
            View memory = buildMemory(activity, body);
            general.setTag("section_0"); api.setTag("section_1"); mcp.setTag("section_2"); role.setTag("section_3"); memory.setTag("section_4");

            // Rebind tab clicks after sections exist.
            for (int i = 0; i < tabs.getChildCount(); i++) {
                final int index = i;
                tabs.getChildAt(i).setOnClickListener(v -> {
                    View target = body.findViewWithTag("section_" + index);
                    if (target != null) scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, target.getTop())));
                });
            }

            dialog.setContentView(root);
            dialog.setOnDismissListener(d -> showing = false);
            dialog.show();
            applyM3Theme(root, activity);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(roundDrawable(activity, BG, 28));
                dialog.getWindow().setLayout((int)(activity.getResources().getDisplayMetrics().widthPixels * .94f),
                        (int)(activity.getResources().getDisplayMetrics().heightPixels * .86f));
                dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        } catch (Throwable e) {
            showing = false;
            log("show: " + e);
            Toast.makeText(activity, "打开设置失败：" + e.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
        }
    }

    private static View buildGeneral(final Activity a, LinearLayout body) {
        LinearLayout card = card(a, "常规 / 后台与外观");
        TextView intro = label(a, "这里控制模块侧网关的运行方式。后台保活只负责保持本模块进程的辅助服务运行，不会强制阻止 Android 回收元宝进程。", 12, false);
        intro.setTextColor(MUTED); card.addView(intro, lp(a, 4));

        final Switch keep = new Switch(a);
        keep.setText("启用模块后台保活"); keep.setTextColor(TEXT);
        keep.setChecked(BridgeConfig.keepAlive(a));
        keep.setOnCheckedChangeListener((v, checked) -> {
            BridgeConfig.setKeepAlive(a, checked);
            if (checked) {
                requestNotificationPermission(a);
                KeepAliveService.start(a);
                toast(a, "后台保活已启动；状态栏会显示服务通知");
            } else {
                KeepAliveService.stop(a);
                toast(a, "后台保活已关闭");
            }
        });
        card.addView(keep, lp(a, 8));

        final Switch boot = new Switch(a);
        boot.setText("开机 / 模块更新后自动恢复保活"); boot.setTextColor(TEXT);
        boot.setChecked(BridgeConfig.autoStart(a));
        boot.setOnCheckedChangeListener((v, checked) -> {
            BridgeConfig.setAutoStart(a, checked);
            toast(a, checked ? "自动恢复已开启" : "自动恢复已关闭");
        });
        card.addView(boot, lp(a, 4));

        final Switch autoRefresh = new Switch(a);
        autoRefresh.setText("打开设置时自动刷新 MCP tools/list"); autoRefresh.setTextColor(TEXT);
        autoRefresh.setChecked(BridgeConfig.autoRefreshMcp());
        autoRefresh.setOnCheckedChangeListener((v, checked) -> {
            BridgeConfig.setAutoRefreshMcp(checked);
            if (checked) IO.execute(() -> { try { McpIdeGateway.refreshForSettings(); } catch (Throwable ignored) {} });
            toast(a, checked ? "自动刷新已开启" : "自动刷新已关闭");
        });
        card.addView(autoRefresh, lp(a, 4));

        TextView themeTitle = label(a, "主题", 15, true); card.addView(themeTitle, lp(a, 12));
        LinearLayout themes = new LinearLayout(a); themes.setOrientation(LinearLayout.HORIZONTAL);
        String[] themeNames = {"跟随系统", "浅色", "深色"};
        for (int i = 0; i < themeNames.length; i++) {
            final int mode = i;
            Button b = button(a, themeNames[i]);
            b.setOnClickListener(v -> {
                BridgeConfig.setThemeMode(a, mode);
                toast(a, "主题已保存：" + themeNames[mode] + "；关闭并重新打开设置后生效");
            });
            themes.addView(b, new LinearLayout.LayoutParams(0, dp(a, 44), 1));
        }
        card.addView(themes, lp(a, 6));

        Button restart = button(a, "重启本地网关");
        restart.setOnClickListener(v -> { McpIdeGateway.restart(); toast(a, "8788 本地网关已请求重启"); });
        card.addView(restart, lp(a, 10));

        Button permission = button(a, "打开 APK 扫描存储权限");
        permission.setOnClickListener(v -> {
            try {
                Intent i = new Intent(a, StorageAccessActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                a.startActivity(i);
            } catch (Throwable e) { toast(a, "无法打开权限页面：" + e.getClass().getSimpleName()); }
        });
        card.addView(permission, lp(a, 6));

        Button notify = button(a, "打开通知权限设置");
        notify.setOnClickListener(v -> {
            try {
                Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                i.putExtra(Settings.EXTRA_APP_PACKAGE, "com.example.yuanbaossehook");
                a.startActivity(i);
            } catch (Throwable e) { toast(a, "无法打开通知设置"); }
        });
        card.addView(notify, lp(a, 6));

        Button diag = button(a, "运行网关 / MCP 快速诊断");
        diag.setOnClickListener(v -> {
            diag.setEnabled(false); diag.setText("正在诊断…");
            IO.execute(() -> {
                String d;
                try {
                    d = "Gateway: " + (McpIdeGateway.isRunning() ? "RUNNING" : "STOPPED") +
                            "\nLAN: " + (BridgeConfig.lanEnabled() ? "ON" : "OFF") +
                            "\nNative Agent: " + (BridgeConfig.nativeAgent() ? "ON" : "OFF") +
                            "\nMCP tools: " + McpIdeGateway.externalToolCount() +
                            "\n" + McpIdeGateway.toolDiagnostics();
                } catch (Throwable e) { d = "诊断失败：" + e; }
                final String out = d;
                a.runOnUiThread(() -> { diag.setEnabled(true); diag.setText("运行网关 / MCP 快速诊断"); showInfo(a, "诊断结果", out); });
            });
        });
        card.addView(diag, lp(a, 6));

        TextView note = label(a, "后台保活使用 Android 前台服务，因此系统会显示常驻通知；Android 12+ 对后台启动前台服务有系统限制，开机恢复也会受到系统版本规则约束。", 12, false);
        note.setTextColor(MUTED); card.addView(note, lp(a, 8));
        body.addView(card, lp(a, 0, 10));
        return card;
    }

    private static void requestNotificationPermission(Activity a) {
        if (Build.VERSION.SDK_INT >= 33) {
            try { a.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 8318); } catch (Throwable ignored) {}
        }
    }

    private static void showInfo(Context c, String title, String message) {
        final Dialog d = new Dialog(c);
        LinearLayout box = card(c, title);
        TextView t = label(c, message, 13, false); t.setTextColor(MUTED); box.addView(t, lp(c, 10));
        Button ok = button(c, "关闭"); ok.setOnClickListener(v -> d.dismiss()); box.addView(ok, lp(c, 10));
        d.setContentView(box); d.show();
        if (d.getWindow() != null) d.getWindow().setLayout((int)(c.getResources().getDisplayMetrics().widthPixels * .9f), -2);
    }

    private static View buildApi(final Activity a, LinearLayout body) {
        LinearLayout card = card(a, "API / 本地网关");
        final TextView info = label(a, "", 14, false);
        info.setTextColor(MUTED);
        card.addView(info, lp(a, 0, 8));

        final String loopBase = "http://127.0.0.1:8788/v1";
        final String loopMcp = "http://127.0.0.1:8788/mcp";
        final Runnable update = () -> {
            String host = McpIdeGateway.lanHostAddress();
            String lanBase = "http://" + host + ":8788/v1";
            String lanMcp = "http://" + host + ":8788/mcp";
            info.setText("本机 OpenAI Base URL\n" + loopBase +
                    "\n\n本机 MCP URL\n" + loopMcp +
                    "\n\n局域网 OpenAI Base URL\n" + lanBase +
                    "\n\n局域网 MCP URL\n" + lanMcp +
                    "\n\nAPI Key\n" + BridgeConfig.apiKey());
        };
        update.run();
        Button b1 = button(a, "复制本机 Base URL"); b1.setOnClickListener(v -> copy(a, loopBase)); card.addView(b1, lp(a, 8));
        Button b2 = button(a, "复制本机 MCP URL"); b2.setOnClickListener(v -> copy(a, loopMcp)); card.addView(b2, lp(a, 6));
        Button b3 = button(a, "复制局域网 Base URL"); b3.setOnClickListener(v -> copy(a, "http://" + McpIdeGateway.lanHostAddress() + ":8788/v1")); card.addView(b3, lp(a, 6));
        Button b4 = button(a, "复制局域网 MCP URL"); b4.setOnClickListener(v -> copy(a, "http://" + McpIdeGateway.lanHostAddress() + ":8788/mcp")); card.addView(b4, lp(a, 6));
        Button b5 = button(a, "一键复制 API Key"); b5.setOnClickListener(v -> copy(a, BridgeConfig.apiKey())); card.addView(b5, lp(a, 6));
        Button b6 = button(a, "重新生成 API Key"); b6.setOnClickListener(v -> { BridgeConfig.regenerateKey(); update.run(); toast(a, "API Key 已重新生成"); }); card.addView(b6, lp(a, 6));

        Button test = button(a, "测试真实 Chat 反代");
        test.setOnClickListener(v -> testGateway(a, test));
        card.addView(test, lp(a, 8));

        final Switch auth = new Switch(a);
        auth.setText("启用 API Key 校验"); auth.setTextColor(TEXT); auth.setChecked(BridgeConfig.auth());
        auth.setOnCheckedChangeListener((v, checked) -> {
            if (BridgeConfig.lanEnabled() && !checked) {
                auth.setChecked(true);
                toast(a, "局域网模式不能关闭 API Key 校验");
                return;
            }
            BridgeConfig.setAuth(checked);
            toast(a, checked ? "API Key 校验已开启" : "API Key 校验已关闭（仅建议本机使用）");
        });
        card.addView(auth, lp(a, 4));

        final Switch lan = new Switch(a);
        lan.setText("允许局域网访问元宝 API 反代"); lan.setTextColor(TEXT); lan.setChecked(BridgeConfig.lanEnabled());
        lan.setOnCheckedChangeListener((v, checked) -> {
            if (checked && !BridgeConfig.auth()) {
                BridgeConfig.setAuth(true);
                auth.setChecked(true);
                toast(a, "局域网模式必须启用 API Key 校验");
            }
            BridgeConfig.setLanEnabled(checked);
            McpIdeGateway.restart();
            update.run();
            toast(a, checked ? "已开启局域网反代：" + McpIdeGateway.lanHostAddress() + ":8788" : "已关闭局域网反代");
        });
        card.addView(lan, lp(a, 8));
        TextView lanNote = label(a, "开启后网关监听 0.0.0.0:8788，同一 Wi-Fi / 局域网设备可使用上面的局域网 Base URL。为避免把元宝接口裸露到局域网，开启局域网模式会强制启用 API Key。", 12, false);
        lanNote.setTextColor(MUTED); card.addView(lanNote, lp(a, 4));

        Switch nativeAgent = new Switch(a);
        nativeAgent.setText("元宝内置 Agent：自动执行 MCP 工具");
        nativeAgent.setTextColor(TEXT);
        nativeAgent.setChecked(BridgeConfig.nativeAgent());
        nativeAgent.setOnCheckedChangeListener((v, checked) -> { BridgeConfig.setNativeAgent(checked); toast(a, checked ? "已启用：元宝聊天会自动执行 MCP" : "已关闭元宝内置 MCP Agent"); });
        card.addView(nativeAgent, lp(a, 8));
        TextView note = label(a, "重要：元宝原生聊天不会经过 127.0.0.1:8788。启用后，Xposed 会在 hb.I6.s3 发送阶段识别‘分析 APK / 读取 / 搜索 / MCP / 工具’等明确意图，先真实执行 MCP，再把结果交给元宝继续回答。", 12, false);
        note.setTextColor(MUTED);
        card.addView(note, lp(a, 4));
        body.addView(card, lp(a, 0, 10)); return card;
    }

    private static View buildMcp(final Activity a, LinearLayout body) {
        final LinearLayout card = card(a, "MCP 工具服务器");
        final TextView status = label(a, "", 14, false); status.setTextColor(MUTED); card.addView(status, lp(a, 4));
        TextView endpointNote = label(a, "默认 APK MCP：127.0.0.1:8787/mcp；主地址失败自动尝试 10.103.160.2:8787/mcp。8788 是本地 OpenAI Bridge，不作为外部 MCP Server。", 12, false);
        endpointNote.setTextColor(MUTED); card.addView(endpointNote, lp(a, 4));
        final LinearLayout rows = new LinearLayout(a); rows.setOrientation(LinearLayout.VERTICAL); card.addView(rows, lp(a, 8));
        final Button add = button(a, "添加 MCP 服务器"); final Button save = button(a, "保存 MCP 配置");
        final Button testStatus = button(a, "测试所有 MCP 服务器状态");
        final Button transport = button(a, "MCP 传输层诊断");
        final Button refresh = button(a, "刷新 tools/list");
        card.addView(add, lp(a, 8)); card.addView(save, lp(a, 6)); card.addView(testStatus, lp(a, 6)); card.addView(transport, lp(a, 6)); card.addView(refresh, lp(a, 6));
        // Extra workspace roots: Android paths outside /storage/emulated/0/MT2 that the
        // external MCP server is allowed to see. Comma-separated.
        final EditText rootsInput = edit(a, "额外工作区根（逗号分隔，例如 /storage/emulated/0/MT2,/storage/emulated/0/Download/mcp）", BridgeConfig.extraMcpRoots(), false);
        card.addView(rootsInput, lp(a, 8));
        Button saveRoots = button(a, "保存额外工作区根");
        card.addView(saveRoots, lp(a, 6));
        saveRoots.setOnClickListener(v -> { try { BridgeConfig.setExtraMcpRoots(rootsInput.getText().toString()); toast(a, "已保存额外工作区根"); } catch (Throwable e) { toast(a, "保存失败"); } });
        renderServers(a, rows, status);
        add.setOnClickListener(v -> { try { JSONArray x=BridgeConfig.servers(); x.put(new JSONObject().put("name","new-server").put("url","").put("enabled",true)); BridgeConfig.setServers(x); renderServers(a,rows,status); } catch(Throwable e){toast(a,"添加失败");} });
        save.setOnClickListener(v -> saveServers(a, rows));
        testStatus.setOnClickListener(v -> {
            testStatus.setEnabled(false); status.setText("正在逐个测试 MCP：现代 2026-07-28 tools/list → 失败自动兼容旧版 initialize…");
            IO.execute(() -> {
                final StringBuilder sb = new StringBuilder();
                try {
                    JSONArray results = McpIdeGateway.testMcpServers();
                    for (int i=0;i<results.length();i++) {
                        JSONObject r=results.optJSONObject(i); if(r==null)continue;
                        sb.append(r.optBoolean("ok") ? "✓ " : "✗ ").append(r.optString("name"))
                          .append(" · HTTP ").append(r.optInt("http",-1))
                          .append(" · ").append(r.optString("protocol","unknown"))
                          .append(" · ").append(r.optInt("tools",0)).append(" tools · ")
                          .append(r.optLong("latency_ms",0)).append("ms\n")
                          .append(r.optString("message")).append("\n");
                    }
                    if (results.length()==0) sb.append("没有启用的 MCP Server");
                } catch(Throwable e) { sb.append("测试失败：").append(e); }
                final String resultText=sb.toString();
                a.runOnUiThread(() -> { status.setText(resultText); testStatus.setEnabled(true); });
            });
        });
        transport.setOnClickListener(v -> { transport.setEnabled(false); status.setText("正在诊断 MCP 传输层：2026 modern → legacy initialize → HTTP+SSE…"); IO.execute(() -> { String d; try { d=McpIdeGateway.transportDiagnostics(); } catch(Throwable e) { d="诊断失败："+e; } final String result=d; a.runOnUiThread(() -> { status.setText(result); transport.setEnabled(true); }); }); });
        refresh.setOnClickListener(v -> { refresh.setEnabled(false); status.setText("正在读取所有 MCP 的 tools/list…"); IO.execute(() -> { try { McpIdeGateway.refreshForSettings(); final int n=McpIdeGateway.externalToolCount(); final String line=McpIdeGateway.externalToolsStatusLine(); final String d=McpIdeGateway.toolDiagnostics(); a.runOnUiThread(() -> {status.setText("已发现 "+n+" 个外部 MCP 工具\n"+line+"\n\n"+d); refresh.setEnabled(true);}); } catch(Throwable e){ final String m=e.toString(); a.runOnUiThread(() -> {status.setText("刷新失败："+m); refresh.setEnabled(true);}); }}); });
        body.addView(card, lp(a, 0, 10)); return card;
    }

    /**
     * Performs a real end-to-end request against the local OpenAI-compatible gateway.
     * This is intentionally implemented here instead of only checking the listening port:
     * 8788 must accept the request and return a complete Chat Completions response.
     */
    private static void testGateway(final Activity a, final Button button) {
        if (a == null || button == null) return;
        button.setEnabled(false);
        button.setText("正在测试 8788 → 8318 → 元宝…");
        IO.execute(() -> {
            HttpURLConnection conn = null;
            String result;
            try {
                URL url = new URL("http://127.0.0.1:8788/v1/chat/completions");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(30000);
                conn.setDoOutput(true);
                conn.setUseCaches(false);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Connection", "close");
                if (BridgeConfig.auth() || BridgeConfig.lanEnabled()) {
                    conn.setRequestProperty("Authorization", "Bearer " + BridgeConfig.apiKey());
                }
                JSONObject req = new JSONObject()
                        .put("model", "yuanbao")
                        .put("stream", false)
                        .put("messages", new JSONArray()
                                .put(new JSONObject()
                                        .put("role", "user")
                                        .put("content", "请只回复：GitHubK Gateway OK")));
                byte[] payload = req.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(payload.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                    os.flush();
                }
                int code = conn.getResponseCode();
                InputStream raw = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                String body = readText(raw);
                if (code >= 200 && code < 300) {
                    String compact = body == null ? "" : body.replace('\n', ' ').replace('\r', ' ').trim();
                    if (compact.length() > 500) compact = compact.substring(0, 500) + "…";
                    result = "测试成功：HTTP " + code + "\n8788 → 8318 → 元宝响应正常\n" + compact;
                } else {
                    String compact = body == null ? "" : body.replace('\n', ' ').replace('\r', ' ').trim();
                    if (compact.length() > 700) compact = compact.substring(0, 700) + "…";
                    result = "测试失败：HTTP " + code + "\n" + compact;
                }
            } catch (Throwable e) {
                result = "测试失败：" + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            } finally {
                if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
            }
            final String message = result;
            a.runOnUiThread(() -> {
                button.setEnabled(true);
                button.setText("测试真实 Chat 反代");
                Toast.makeText(a, message, Toast.LENGTH_LONG).show();
            });
        });
    }

    private static String readText(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream x = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = x.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static void saveServers(Context a, LinearLayout rows) {
        try {
            JSONArray out = new JSONArray();
            for (int i=0;i<rows.getChildCount();i++) {
                View child=rows.getChildAt(i); Object tag=child.getTag();
                if (!(tag instanceof ServerRow)) continue;
                ServerRow r=(ServerRow)tag; String n=r.name.getText().toString().trim(), u=r.url.getText().toString().trim();
                if(!n.isEmpty()&&!u.isEmpty()) {
                    JSONObject o = new JSONObject().put("name",n).put("url",u).put("enabled",r.enabled.isChecked());
                    if (r.fallbackUrl != null && !r.fallbackUrl.isEmpty()) o.put("fallbackUrl", r.fallbackUrl);
                    o.put("clientName", "yuanbao-local-agent");
                    out.put(o);
                }
            }
            BridgeConfig.setServers(out);
            toast(a,"MCP 配置已保存，后台刷新 tools/list");
            IO.execute(() -> {
                try { McpIdeGateway.refreshForSettings(); }
                catch(Throwable ignored) {}
            });
        } catch(Throwable e){toast(a,"保存失败："+e.getClass().getSimpleName());}
    }

    private static void renderServers(Context a, LinearLayout rows, TextView status) {
        rows.removeAllViews(); JSONArray arr=BridgeConfig.servers(); int count=0;
        for(int i=0;i<arr.length();i++){ JSONObject o=arr.optJSONObject(i); if(o==null)continue; ServerRow r=new ServerRow(a,o.optString("name"),o.optString("url"),o.optBoolean("enabled",true),o.optString("fallbackUrl","")); rows.addView(r.container,lp(a,0,6)); count++; }
        status.setText(count+" 个 MCP Server · 保存后可刷新 tools/list");
    }

    private static final class ServerRow {
        final LinearLayout container; final EditText name,url; final Switch enabled; final String fallbackUrl;
        ServerRow(Context c,String n,String u,boolean e){
            this(c,n,u,e,"");
        }
        ServerRow(Context c,String n,String u,boolean e,String fb){
            container=card(c,"服务器");
            fallbackUrl=fb == null ? "" : fb;
            name=edit(c,"服务器名称",n,false); url=edit(c,"MCP URL",u, false); enabled=new Switch(c); enabled.setText("启用此服务器"); enabled.setTextColor(TEXT); enabled.setChecked(e);
            Button remove=button(c,"删除服务器"); remove.setOnClickListener(v->{ViewGroup p=(ViewGroup)container.getParent();if(p!=null)p.removeView(container);});
            container.addView(name,lp(c,10)); container.addView(url,lp(c,8)); container.addView(enabled,lp(c,4)); container.addView(remove,lp(c,4)); container.setTag(this);
        }
    }

    private static View buildRole(final Activity a, LinearLayout body) {
        LinearLayout card=card(a,"角色 / System Prompt");
        final EditText input=edit(a,"角色定位与系统提示词",BridgeConfig.systemPrompt(),true); card.addView(input,lp(a,10));
        Button save=button(a,"保存角色与提示词"); save.setOnClickListener(v->{BridgeConfig.setSystemPrompt(input.getText().toString());toast(a,"角色 / System Prompt 已保存");}); card.addView(save,lp(a,8));
        body.addView(card,lp(a,0,10)); return card;
    }

    private static View buildMemory(final Activity a, LinearLayout body) {
        LinearLayout card=card(a,"项目长期记忆");
        final EditText project=edit(a,"项目 ID",BridgeConfig.project(),false); final EditText memory=edit(a,"项目事实、架构决策、约束、进度",BridgeConfig.memory(BridgeConfig.project()),true);
        card.addView(project,lp(a,10)); card.addView(memory,lp(a,8));
        LinearLayout actions=new LinearLayout(a); actions.setOrientation(LinearLayout.HORIZONTAL);
        Button load=button(a,"加载记忆"), save=button(a,"保存记忆"); actions.addView(load,new LinearLayout.LayoutParams(0,-2,1)); actions.addView(save,new LinearLayout.LayoutParams(0,-2,1)); card.addView(actions,lp(a,8));
        load.setOnClickListener(v->{String p=project.getText().toString().trim();if(p.isEmpty())p="default";BridgeConfig.setProject(p);memory.setText(BridgeConfig.memory(p));toast(a,"已加载项目记忆："+p);});
        save.setOnClickListener(v->{String p=project.getText().toString().trim();if(p.isEmpty())p="default";BridgeConfig.setProject(p);BridgeConfig.setMemory(p,memory.getText().toString());toast(a,"项目记忆已保存："+p);});
        TextView note=label(a,"Agent 可使用 project_memory_read / project_memory_write 自动读取和更新项目记忆。不要保存密码、Cookie、Token 或签名密钥。",12,false);note.setTextColor(MUTED);card.addView(note,lp(a,8));
        body.addView(card,lp(a,0,10)); return card;
    }

    private static void applyM3Theme(View root, Context c) {
        boolean dark = BridgeConfig.themeMode(c) == 2 ||
                (BridgeConfig.themeMode(c) == 0 && (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        int bg = dark ? Color.rgb(20, 18, 24) : Color.rgb(255, 251, 254);
        int surface = dark ? Color.rgb(31, 29, 35) : Color.WHITE;
        int text = dark ? Color.rgb(230, 225, 229) : Color.rgb(28, 27, 31);
        int muted = dark ? Color.rgb(202, 196, 208) : Color.rgb(73, 69, 79);
        int primaryContainer = dark ? Color.rgb(79, 55, 139) : Color.rgb(234, 221, 255);
        int editBg = dark ? Color.rgb(35, 33, 40) : Color.WHITE;
        styleM3Tree(root, c, dark, bg, surface, text, muted, primaryContainer, editBg);
    }

    private static void styleM3Tree(View v, Context c, boolean dark, int bg, int surface, int text, int muted, int primaryContainer, int editBg) {
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) styleM3Tree(g.getChildAt(i), c, dark, bg, surface, text, muted, primaryContainer, editBg);
        }
        if (v == null) return;
        if (v.getId() == android.R.id.content) return;
        if (v instanceof EditText) {
            v.setBackground(roundStroke(c, dark ? Color.rgb(147, 143, 153) : Color.rgb(121, 116, 126), 14));
            ((EditText)v).setTextColor(text); ((EditText)v).setHintTextColor(muted);
        } else if (v instanceof Button) {
            v.setBackground(roundDrawable(c, primaryContainer, 18));
            v.setElevation(dp(c, 1));
            ((Button)v).setTextColor(dark ? Color.rgb(234, 221, 255) : Color.rgb(33, 0, 93));
            v.setMinimumHeight(dp(c, 48));
        } else if (v instanceof Switch) {
            ((Switch)v).setTextColor(text);
        } else if (v instanceof TextView) {
            ((TextView)v).setTextColor(text);
        }
        if (v.getParent() == null || !(v.getParent() instanceof ViewGroup)) {
            v.setBackground(roundDrawable(c, bg, 28));
        }
    }

    private static LinearLayout card(Context c,String title){ LinearLayout box=new LinearLayout(c); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(c,16),dp(c,14),dp(c,16),dp(c,14)); box.setBackground(roundDrawable(c,SURFACE,22)); box.setElevation(dp(c,1)); TextView t=label(c,title,19,true);box.addView(t);return box; }
    private static TextView label(Context c,String s,float size,boolean bold){TextView t=new TextView(c);t.setText(s);t.setTextSize(size);t.setTextColor(TEXT);t.setTypeface(Typeface.DEFAULT,bold?Typeface.BOLD:Typeface.NORMAL);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
    private static Button button(Context c,String s){Button b=new Button(c);b.setText(s);b.setTextSize(14);b.setMinHeight(dp(c,48));b.setTextColor(TEXT);b.setAllCaps(false);b.setGravity(Gravity.CENTER);b.setBackground(roundDrawable(c,PRIMARY_CONTAINER,18));b.setPadding(dp(c,12),dp(c,6),dp(c,12),dp(c,6));return b;}
    private static EditText edit(Context c,String hint,String value,boolean multi){EditText e=new EditText(c);e.setHint(hint);e.setText(value==null?"":value);e.setTextColor(TEXT);e.setHintTextColor(MUTED);e.setTextSize(15);e.setPadding(dp(c,14),dp(c,10),dp(c,14),dp(c,10));e.setBackground(roundStroke(c,Color.rgb(120,118,126),14));if(multi){e.setMinHeight(dp(c,180));e.setGravity(Gravity.TOP|Gravity.START);e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);e.setSingleLine(false);}else{e.setSingleLine(true);}return e;}
    private static LinearLayout.LayoutParams lp(Context c,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(c,top),0,0);return p;}
    private static LinearLayout.LayoutParams lp(Context c,int top,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(c,top),0,dp(c,bottom));return p;}
    private static int dp(Context c,int n){return(int)(n*c.getResources().getDisplayMetrics().density+.5f);}
    private static GradientDrawable roundDrawable(Context c,int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(c,radius));return g;}
    private static GradientDrawable roundStroke(Context c,int stroke,int radius){GradientDrawable g=roundDrawable(c,Color.WHITE,radius);g.setStroke(dp(c,1),stroke);return g;}
    private static void copy(Context c,String value){ClipboardManager cm=(ClipboardManager)c.getSystemService(Context.CLIPBOARD_SERVICE);if(cm!=null)cm.setPrimaryClip(ClipData.newPlainText("YuanBao Gateway",value));toast(c,"已复制");}
    private static void toast(Context c,String s){Toast.makeText(c,s,Toast.LENGTH_SHORT).show();}
    private static void log(String s){try{de.robv.android.xposed.XposedBridge.log(TAG+": "+s);}catch(Throwable ignored){}}
}

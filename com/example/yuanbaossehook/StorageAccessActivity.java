package com.example.yuanbaossehook;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Module launcher/settings entry. Uses a dependency-free Material 3 visual system. */
public final class StorageAccessActivity extends Activity {
    private static final int REQ_TREE = 9001;
    private static final int REQ_SHIZUKU = 9002;

    private TextView shizukuStateView;
    private TextView bridgeStateView;
    private boolean permissionListenerRegistered;
    private rikka.shizuku.Shizuku.OnRequestPermissionResultListener permissionListener;

    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }
    private TextView text(String s, float size, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(Color.rgb(28,27,31));
        t.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL); return t;
    }
    private Button button(String s) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextSize(14); b.setTextColor(Color.rgb(33,0,93));
        b.setMinHeight(dp(48)); b.setBackgroundColor(Color.rgb(234,221,255)); return b;
    }
    private LinearLayout card(String title) { LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(16),dp(14),dp(16),dp(14)); box.setBackgroundColor(Color.WHITE); box.setElevation(dp(1)); box.addView(text(title,19,true),lp(0,0,0,8)); return box; }
    private LinearLayout.LayoutParams lp(int l,int t,int r,int b){ LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(dp(l),dp(t),dp(r),dp(b)); return p; }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        BridgeConfig.init(this); BridgeConfig.initModule(this);
        ShizukuShell.attach(this);
        if (BridgeConfig.keepAlive() && BridgeConfig.autoStart()) KeepAliveService.start(this);
        getWindow().setStatusBarColor(Color.rgb(255,251,254));
        getWindow().setNavigationBarColor(Color.rgb(255,251,254));
        getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(20),dp(20),dp(20),dp(24)); root.setBackgroundColor(Color.rgb(255,251,254));
        TextView title = text("元宝本地 Agent 网关", 26, true); root.addView(title, lp(0,0,0,8));
        TextView sub = text("模块设置 · Shizuku · APK 扫描 · 后台服务", 14, false); sub.setTextColor(Color.rgb(73,69,79)); root.addView(sub, lp(0,0,0,16));

        // ---------------- Shizuku (the real fix for /sdcard access) ----------------
        LinearLayout shizuku = card("Shizuku 无 root 文件访问（推荐）");
        TextView szInfo = text("网关代码运行在元宝进程内，元宝进程永远拿不到 Shizuku 授权（授权属于本模块的 UID）。"
                + "因此模块进程会通过 Shizuku 以 shell 身份（uid 2000）执行命令，再把结果回传给元宝进程。"
                + "授权后 android_fs_list / android_fs_read / android_fs_write / shell_exec 即可真实读写 /sdcard（含 /sdcard/MT2/mcp）。全程不需要 root，也不读取登录凭据、Cookie、Token 或签名密钥。", 14, false);
        szInfo.setTextColor(Color.rgb(73,69,79)); shizuku.addView(szInfo, lp(0,0,0,10));

        shizukuStateView = text(shizukuStatusText(), 13, false);
        shizukuStateView.setTextColor(Color.rgb(73,69,79)); shizuku.addView(shizukuStateView, lp(0,0,0,10));

        Button req = button("① 请求 Shizuku 授权"); req.setOnClickListener(v -> requestShizuku()); shizuku.addView(req, lp(0,0,0,8));
        Button test = button("② 测试执行 shell（id / ls /sdcard）"); test.setOnClickListener(v -> runSelfTest()); shizuku.addView(test, lp(0,0,0,8));
        Button openSz = button("打开或安装 Shizuku"); openSz.setOnClickListener(v -> openShizuku()); shizuku.addView(openSz, lp(0,0,0,0));
        root.addView(shizuku, lp(0,0,0,12));

        // ---------------- loopback bridge: how the injected YuanBao process reaches this module ----
        LinearLayout bridge = card("桥接服务（元宝进程 → 本模块）");
        TextView bridgeInfo = text("网关代码运行在元宝进程内，而 Android 11+ 的包可见性过滤会让元宝进程无法用 ContentResolver 找到本模块（元宝的 <queries> 不含本包），"
                + "所以模块进程在 127.0.0.1 上开一个仅本机可达的桥接端口，并用一次性令牌握手。"
                + "元宝进程的 shell / 文件 / APK / Shizuku 工具全部经它转发到本模块执行。", 13, false);
        bridgeInfo.setTextColor(Color.rgb(73,69,79)); bridge.addView(bridgeInfo, lp(0,0,0,10));
        bridgeStateView = text(bridgeStatusText(), 13, false); bridgeStateView.setTextColor(Color.rgb(73,69,79)); bridge.addView(bridgeStateView, lp(0,0,0,10));
        Button bs = button("启动 / 重启桥接服务"); bs.setOnClickListener(v -> { try { BridgeServer.attach(this); BridgeServer.start(this); } catch (Throwable t) { Toast("启动失败：" + t); } refreshBridgeState(); }); bridge.addView(bs, lp(0,0,0,8));
        Button br = button("刷新桥接状态"); br.setOnClickListener(v -> refreshBridgeState()); bridge.addView(br, lp(0,0,0,8));
        Button bstop = button("停止桥接服务"); bstop.setOnClickListener(v -> { BridgeServer.stop(); refreshBridgeState(); }); bridge.addView(bstop, lp(0,0,0,0));
        root.addView(bridge, lp(0,0,0,12));
        // Opening this screen is the documented way to bring the bridge up.
        try { BridgeServer.attach(this); BridgeServer.start(this); } catch (Throwable ignored) {}

        LinearLayout access = card("存储与 APK 分析");
        TextView info = text("为让模块自己的进程扫描 /storage/emulated/0/MT2/mcp/ 等共享存储目录，也可以授予“所有文件访问权限”。"
                + "Shizuku 与“所有文件访问权限”任一可用即可；两者都不可用时模块会明确报错，不会伪造成功。", 14, false);
        info.setTextColor(Color.rgb(73,69,79)); access.addView(info, lp(0,0,0,10));
        Button saf = button("选择并挂载 SAF 工作目录"); saf.setOnClickListener(v -> chooseWorkspace()); access.addView(saf, lp(0,0,0,8));
        TextView safState = text("SAF：" + SafStorage.status(this) + "\n工具路径格式：saf://default/<相对路径>", 13, false); safState.setTextColor(Color.rgb(73,69,79)); access.addView(safState, lp(0,0,0,10));
        Button openFiles = button("打开“所有文件访问权限”设置"); openFiles.setOnClickListener(v -> openSettings()); access.addView(openFiles, lp(0,0,0,8));
        Button analyze = button("返回元宝并继续 APK 分析"); analyze.setOnClickListener(v -> finish()); access.addView(analyze, lp(0,0,0,0));
        root.addView(access, lp(0,0,0,12));

        LinearLayout runtime = card("后台运行");
        Switch keep = new Switch(this); keep.setText("启用模块后台保活"); keep.setTextColor(Color.rgb(28,27,31)); keep.setChecked(BridgeConfig.keepAlive());
        keep.setOnCheckedChangeListener((v, checked) -> { BridgeConfig.setKeepAlive(this, checked); if (checked) KeepAliveService.start(this); else KeepAliveService.stop(this); }); runtime.addView(keep, lp(0,0,0,6));
        Switch boot = new Switch(this); boot.setText("开机 / 模块更新后自动恢复"); boot.setTextColor(Color.rgb(28,27,31)); boot.setChecked(BridgeConfig.autoStart());
        boot.setOnCheckedChangeListener((v, checked) -> BridgeConfig.setAutoStart(this, checked)); runtime.addView(boot, lp(0,0,0,8));
        TextView note = text("保活通过 Android 前台服务实现，会显示常驻通知。它保持的是模块侧辅助服务，不保证系统永不回收元宝目标进程。", 12, false); note.setTextColor(Color.rgb(73,69,79)); runtime.addView(note, lp(0,0,0,0));
        root.addView(runtime, lp(0,0,0,12));

        LinearLayout about = card("运行状态");
        TextView status = text("存储权限：" + storageStatus() + "\n后台保活：" + (BridgeConfig.keepAlive() ? "开启" : "关闭") + "\n自动恢复：" + (BridgeConfig.autoStart() ? "开启" : "关闭"), 14, false); about.addView(status, lp(0,0,0,8));
        Button notify = button("打开模块通知权限"); notify.setOnClickListener(v -> openNotificationSettings()); about.addView(notify, lp(0,0,0,0));
        root.addView(about, lp(0,0,0,12));
        scroll.addView(root); setContentView(scroll);

        if (getIntent() != null && getIntent().getBooleanExtra("request_shizuku", false)) {
            requestShizuku();
        }
    }

    private void registerPermissionListener() {
        if (permissionListenerRegistered) return;
        try {
            permissionListener = (code, grantResult) -> {
                if (code != REQ_SHIZUKU) return;
                boolean granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED;
                Toast(granted ? "Shizuku 授权成功" : "Shizuku 授权被拒绝");
                refreshShizukuState();
            };
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(permissionListener);
            permissionListenerRegistered = true;
        } catch (Throwable t) {
            Toast("注册 Shizuku 监听失败：" + t);
        }
    }

    private void requestShizuku() {
        registerPermissionListener();
        try {
            android.os.IBinder b = rikka.shizuku.Shizuku.getBinder();
            if (b == null) {
                Toast(ShizukuShell.managerInstalled()
                        ? "Shizuku 服务未连接：请先启动 Shizuku（或重启手机），再点此按钮。"
                        : "未检测到 Shizuku：请先安装并启动 Shizuku。");
                refreshShizukuState();
                return;
            }
            rikka.shizuku.Shizuku.requestPermission(REQ_SHIZUKU);
            Toast("已发出授权请求，请在 Shizuku 弹窗中允许");
        } catch (Throwable t) {
            Toast("请求授权失败：" + t.getClass().getSimpleName());
        }
        refreshShizukuState();
    }

    /** Runs `id` plus a real listing of /sdcard through Shizuku, showing the raw truth. */
    private void runSelfTest() {
        shizukuStateView.setText("正在通过 Shizuku 执行测试…");
        new Thread(() -> {
            String report;
            try {
                ShizukuShell.Result a = ShizukuShell.exec("id", 15000L, null);
                ShizukuShell.Result b = ShizukuShell.exec("ls -l /sdcard | head -n 12", 20000L, null);
                ShizukuShell.Result c = ShizukuShell.exec("ls -l /sdcard/MT2/mcp 2>&1 | head -n 12", 20000L, null);
                // The loopback bridge only works when the module can read the token the YuanBao
                // process wrote into its own external files dir, and that read needs Shizuku.
                ShizukuShell.Result h = ShizukuShell.exec("cat " + ShizukuShell.q(BridgeServer.HANDSHAKE_PATH) + " 2>&1", 10000L, null);
                org.json.JSONObject ping = BridgeOps.dispatch(this, "ping", null, "self-test");
                StringBuilder sb = new StringBuilder();
                sb.append("[id] ok=").append(a.ok).append(" code=").append(a.exitCode)
                  .append(a.error.isEmpty() ? "" : " err=" + a.error).append("\n")
                  .append(trim(a.stdout)).append("\n")
                  .append("[ls /sdcard] ok=").append(b.ok).append("\n").append(trim(b.stdout)).append("\n")
                  .append("[ls /sdcard/MT2/mcp] ok=").append(c.ok).append("\n").append(trim(c.stdout)).append("\n")
                  .append(c.ok ? "" : trim(c.stderr)).append("\n")
                  .append("[握手令牌] ok=").append(h.ok)
                  .append(h.stdout.contains("token") ? " 已读到（含 token 字段）" : " 未读到").append("\n")
                  .append("[桥接自检 ping] ok=").append(ping.optBoolean("ok", false))
                  .append(" pid=").append(ping.optInt("pid", 0))
                  .append(" shizuku=").append(ping.optBoolean("shizukuGranted", false))
                  .append(" saf=").append(ping.optBoolean("safMounted", false)).append("\n")
                  .append("桥接：").append(bridgeStatusText());
                report = sb.toString();
            } catch (Throwable t) {
                report = "测试异常：" + t;
            }
            final String out = report;
            runOnUiThread(() -> {
                shizukuStateView.setText("Shizuku 自检结果：\n" + out);
                Toast("自检完成，详见上方结果");
            });
        }, "yb-shizuku-selftest").start();
    }

    private static String trim(String s) {
        if (s == null) return "";
        String x = s.trim();
        return x.length() > 1200 ? x.substring(0, 1200) + "…" : x;
    }

    private void refreshShizukuState() {
        if (shizukuStateView != null) shizukuStateView.setText(shizukuStatusText());
    }

    private String shizukuStatusText() {
        try {
            org.json.JSONObject s = ShizukuShell.status();
            boolean granted = s.optBoolean("permissionGranted", false);
            StringBuilder sb = new StringBuilder();
            sb.append("Shizuku 状态：").append(granted ? "已授权（可读写 /sdcard）" : "未就绪");
            sb.append("\nstage=").append(s.optString("stage", ""))
              .append("  available=").append(s.optBoolean("available", false))
              .append("  binder=").append(s.optBoolean("binder", false));
            if (!s.optString("managerVersion", "").isEmpty())
                sb.append("\nShizuku 版本=").append(s.optString("managerVersion", ""));
            if (granted) {
                sb.append("\nservice uid=").append(s.optInt("serverUid", -1))
                  .append("  SELinux=").append(s.optString("selinuxContext", ""));
            }
            String reason = s.optString("reason", "");
            if (!reason.isEmpty()) sb.append("\n").append(reason);
            sb.append("\n模块“所有文件访问权限”：").append(ShizukuShell.allFilesAccess() ? "已授权" : "未授权");
            return sb.toString();
        } catch (Throwable t) {
            return "Shizuku 状态读取失败：" + t;
        }
    }

    private void openShizuku() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(
                    ShizukuShell.managerInstalled() ? ShizukuShell.managerPackageName() : "moe.shizuku.privileged.api");
            if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); return; }
            Toast("未安装 Shizuku，请先安装 Shizuku（moe.shizuku.privileged.api）");
        } catch (Throwable t) {
            Toast("无法打开 Shizuku：" + t.getClass().getSimpleName());
        }
    }

    private void Toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show(); }

    @Override protected void onResume() { super.onResume(); ShizukuShell.attach(this); refreshShizukuState(); refreshBridgeState(); }

    private void refreshBridgeState() {
        if (bridgeStateView != null) bridgeStateView.setText(bridgeStatusText());
    }

    /** Real bridge state: port, whether the host token was picked up, served/rejected counters. */
    private String bridgeStatusText() {
        try {
            org.json.JSONObject s = BridgeServer.status(this);
            StringBuilder sb = new StringBuilder();
            boolean running = s.optBoolean("running", false);
            sb.append("桥接服务：").append(running ? "运行中" : "未运行");
            if (running) sb.append("   监听=127.0.0.1:").append(s.optInt("port", 0));
            sb.append("\n握手令牌：").append(s.optBoolean("tokenReady", false)
                    ? "已从元宝进程读到" : "尚未读到（需元宝进程先启动过一次 + Shizuku 可读）");
            sb.append("\n已服务=").append(s.optInt("served", 0))
              .append("  已拒绝=").append(s.optInt("rejected", 0))
              .append("  pid=").append(s.optInt("pid", 0));
            sb.append("\nShizuku=").append(s.optBoolean("shizukuGranted", false) ? "已授权" : "未授权")
              .append("  SAF=").append(s.optBoolean("safMounted", false) ? "已挂载" : "未挂载");
            String err = s.optString("lastError", "");
            if (!err.isEmpty()) sb.append("\n最近错误：").append(trim(err));
            return sb.toString();
        } catch (Throwable t) {
            return "桥接状态读取失败：" + t;
        }
    }

    @Override protected void onDestroy() {
        if (permissionListenerRegistered && permissionListener != null) {
            try { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(permissionListener); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    private String storageStatus(){ if(Build.VERSION.SDK_INT>=30){ try{return Environment.isExternalStorageManager()?"已授权":"未授权";}catch(Throwable ignored){return "未知";} } return "Android < 11"; }
    private void chooseWorkspace(){
        try{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i,REQ_TREE);
        }catch(Throwable e){ Toast("打开目录选择器失败："+e.getClass().getSimpleName()); }
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==REQ_TREE && resultCode==RESULT_OK && data!=null && data.getData()!=null){
            try{ int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION); SafStorage.saveTree(this,data.getData(),flags); Toast("SAF 工作目录已挂载"); recreate(); }
            catch(Throwable e){ Toast("保存 SAF 授权失败："+e.getMessage()); }
        }
    }
    private void openSettings(){ try { if(Build.VERSION.SDK_INT>=30){ Intent i=new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION); i.setData(Uri.parse("package:"+getPackageName())); startActivity(i); } else startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))); } catch(Throwable ignored){ try{startActivity(new Intent(Settings.ACTION_SETTINGS));}catch(Throwable ignored2){} } }
    private void openNotificationSettings(){ try { Intent i=new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS); i.putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()); startActivity(i); } catch(Throwable ignored){} }
}

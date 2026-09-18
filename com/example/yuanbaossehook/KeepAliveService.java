package com.example.yuanbaossehook;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import org.json.JSONObject;

/**
 * Optional user-enabled foreground service for the module process.
 * It keeps the module-side diagnostics/IPC helper alive and exposes a visible
 * notification. It does NOT and cannot guarantee that the target YuanBao
 * process itself remains alive; Android owns that process independently.
 */
public final class KeepAliveService extends Service {
    public static void start(android.content.Context context) {
        if (context == null) return;
        try {
            Intent i = new Intent(context, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
            else context.startService(i);
        } catch (Throwable ignored) {}
    }
    public static void stop(android.content.Context context) {
        if (context == null) return;
        try { context.stopService(new Intent(context, KeepAliveService.class)); } catch (Throwable ignored) {}
    }
    private static final String CHANNEL = "yuanbao_gateway_keepalive";
    private static final int NOTIFICATION_ID = 8318;
    private volatile boolean running;
    private Thread heartbeat;
    /** Heartbeat counter, used to throttle daemon respawn attempts. */
    private int hbTick;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        running = true;
        // The bridge server lives in this process: the injected YuanBao process can only reach
        // Shizuku / the SAF workspace / shared storage from here.
        try {
            BridgeServer.attach(getApplicationContext());
        } catch (Throwable ignored) {}
        // Never start the bridge on the main thread: the first Shizuku bind can be slow, and a
        // blocked service start is an ANR.
        Thread boot = new Thread(() -> {
            try {
                ShizukuShell.warmUp();
                BridgeServer.start(getApplicationContext());
            } catch (Throwable ignored) {}
            // Bring the standalone uid-2000 daemon up once, right after the app starts. From then on
            // the host talks to the daemon directly, so force-stopping or updating this app no longer
            // takes the tool backend down.
            try {
                JSONObject r = DaemonLauncher.ensure(getApplicationContext(), "module-boot");
                McpIdeGateway.log("[DAEMON] boot ensure " + r);
            } catch (Throwable t) {
                McpIdeGateway.log("[DAEMON] boot ensure failed: " + t);
            }
        }, "yb-bridge-boot");
        boot.setDaemon(true);
        boot.start();
        heartbeat = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(15000L);
                    if (running) {
                        BridgeConfig.init(getApplicationContext());
                        BridgeServer.start(getApplicationContext());
                        // Keep Shizuku's shell warm while calls are flowing, so the next real
                        // command does not pay a cold start.
                        if (BridgeServer.activeRecently()) ShizukuShell.touch();
                        // Keep the standalone daemon alive: it is the host's fast path and it is
                        // independent of this process, so a dead daemon is respawned here.
                        try {
                            hbTick++;
                            JSONObject dp = DaemonLauncher.probe();
                            if (dp.optBoolean("alive", false)) {
                                McpIdeGateway.log("[DAEMON] alive port=" + dp.optInt("port", 0)
                                        + " uid=" + dp.optInt("uid", 0) + " pid=" + dp.optInt("pid", 0)
                                        + " took=" + dp.optLong("tookMs", 0) + "ms");
                            } else if (hbTick % 4 == 0) {
                                // Every 4th tick (60s) at most: DaemonLauncher.ensure() probes first
                                // and throttles spawns itself, so this stays cheap when the daemon is
                                // permanently unavailable (e.g. no Shizuku grant).
                                McpIdeGateway.log("[DAEMON] not alive (" + dp.optString("error", "")
                                        + "), ensuring: "
                                        + DaemonLauncher.ensure(getApplicationContext(), "heartbeat"));
                            }
                        } catch (Throwable t) {
                            McpIdeGateway.log("[DAEMON] heartbeat probe failed: " + t);
                        }
                        McpIdeGateway.log("[KEEPALIVE] module foreground service heartbeat "
                                + BridgeServer.status(getApplicationContext()).toString());
                        refreshNotification();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable ignored) {}
            }
        }, "YB-Gateway-KeepAlive");
        heartbeat.start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getStringExtra("action"))) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        if (heartbeat != null) heartbeat.interrupt();
        try { BridgeServer.stop(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private Notification buildNotification() {
        Intent open = new Intent(this, StorageAccessActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open,
                (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0) | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("元宝本地 Agent 网关")
                .setContentText(bridgeText())
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setShowWhen(false)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "元宝网关后台服务", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("用于保持模块侧网关辅助服务运行");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    /** Notification line that shows the real bridge state instead of a vague "running" claim. */
    private String bridgeText() {
        try {
            JSONObject st = BridgeServer.status(getApplicationContext());
            String d = daemonSuffix(st);
            if (!st.optBoolean("running", false)) return "后台保活已启用 · 桥接未运行" + d;
            int p = st.optInt("port", 0);
            if (!st.optBoolean("tokenReady", false)) return "桥接监听 127.0.0.1:" + p + " · 等待元宝握手" + d;
            return "桥接监听 127.0.0.1:" + p + " · 已处理 " + st.optInt("served", 0) + " 次调用" + d;
        } catch (Throwable t) {
            return "后台保活已启用 · 模块服务运行中";
        }
    }

    /** Standalone daemon (uid 2000) part of the notification line. */
    private String daemonSuffix(JSONObject st) {
        try {
            JSONObject d = st.optJSONObject("daemon");
            JSONObject p = d == null ? null : d.optJSONObject("probe");
            boolean alive = p != null && p.optBoolean("alive", false);
            if (alive) return " · 守护进程在线(uid" + p.optInt("uid", 0) + ")";
            return " · 守护进程未启动";
        } catch (Throwable t) {
            return "";
        }
    }

    /** Refreshes the ongoing notification so the daemon/bridge state stays truthful. */
    private void refreshNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            Notification n = buildNotification();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                nm.notify(NOTIFICATION_ID, n);
            }
        } catch (Throwable ignored) {}
    }
}

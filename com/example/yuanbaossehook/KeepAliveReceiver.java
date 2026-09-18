package com.example.yuanbaossehook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Starts the optional module-side foreground service after boot/update when enabled. */
public final class KeepAliveReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        try {
            BridgeConfig.init(context.getApplicationContext());
            if (!BridgeConfig.keepAlive()) return;
            Intent i = new Intent(context, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
            else context.startService(i);
        } catch (Throwable ignored) {}
    }
}

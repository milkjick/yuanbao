package com.example.yuanbaossehook;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONObject;

/**
 * Binder transport for the module-process bridge.
 *
 * The gateway runs inside YuanBao's process (Xposed injection). That process has YuanBao's
 * permissions (no all-files access) and can never hold the Shizuku API grant, because the grant and
 * the "&lt;applicationId&gt;.shizuku" provider belong to this module's UID. So all privileged work
 * (Shizuku shell, SAF workspace, shared storage) runs HERE, in the module process.
 *
 * Two ways in:
 *   - this provider (Binder). Works for adb/shell/self callers and for any caller that can see this
 *     package. Android 11+ package visibility filtering blocks YuanBao, which does not declare this
 *     module in its &lt;queries&gt; element.
 *   - {@link BridgeServer} (127.0.0.1 TCP + token), which is what the injected process uses.
 *
 * Both transports run the exact same method implementations in {@link BridgeOps}.
 *
 * Security: only YuanBao's package, this module's own package, root and the shell uid may call.
 * No credential, cookie, token or signing key is ever returned by any method.
 */
public final class ApkScanProvider extends ContentProvider {

    public static final String AUTHORITY = "com.example.yuanbaossehook.apk-scan";
    private static final String LOGTAG = "YB-MCP";

    @Override public boolean onCreate() {
        try { McpIdeGateway.setApplicationContext(getContext()); } catch (Throwable ignored) {}
        try { ShizukuShell.attach(getContext()); } catch (Throwable ignored) {}
        try { BridgeServer.attach(getContext()); } catch (Throwable ignored) {}
        // Any provider hit (module UI opened, adb probe, shell call) also brings the socket bridge
        // up, so the injected process finds a working transport.
        try { BridgeServer.startAsync(getContext()); } catch (Throwable ignored) {}
        return true;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        try {
            int uid = -1;
            String caller = null;
            try { uid = android.os.Binder.getCallingUid(); } catch (Throwable ignored) {}
            try { caller = getCallingPackage(); } catch (Throwable ignored) {}
            if (!BridgeOps.callerAllowed(getContext(), caller, uid)) {
                android.util.Log.i(LOGTAG, "[BRIDGE] call " + method + " REJECTED uid=" + uid + " pkg=" + caller);
                out.putString("json", BridgeOps.errString("caller not allowed"));
                return out;
            }
            Context ctx = getContext();
            final Bundle e = extras == null ? new Bundle() : extras;

            // Module runtime preferences (kept here so the module UI and provider agree).
            if ("get_runtime".equals(method)) {
                android.content.SharedPreferences p = ctx.getSharedPreferences("yb_module_runtime", Context.MODE_PRIVATE);
                out.putBoolean("keep_alive", p.getBoolean("keep_alive", false));
                out.putBoolean("auto_start", p.getBoolean("auto_start", true));
                out.putInt("theme_mode", p.getInt("theme_mode", 0));
                return out;
            }
            if ("set_runtime".equals(method)) {
                String key = BridgeOps.str(e, "key", "");
                android.content.SharedPreferences.Editor ed =
                        ctx.getSharedPreferences("yb_module_runtime", Context.MODE_PRIVATE).edit();
                if ("keep_alive".equals(key) || "auto_start".equals(key)) ed.putBoolean(key, BridgeOps.bool(e, "value", false));
                else if ("theme_mode".equals(key)) ed.putInt(key, BridgeOps.i32(e, "value_int", 0));
                else { out.putBoolean("ok", false); out.putString("error", "unknown runtime key"); return out; }
                ed.apply();
                out.putBoolean("ok", true);
                return out;
            }

            JSONObject result = BridgeOps.dispatch(ctx, method, e, "binder");
            out.putString("json", result == null ? BridgeOps.errString("empty result") : result.toString());
            return out;
        } catch (Throwable t) {
            out.putString("json", BridgeOps.errString("provider 调用失败: " + t));
            return out;
        }
    }

    @Override public String getType(Uri uri) { return "application/json"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}

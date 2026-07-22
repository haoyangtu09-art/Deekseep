package com.dsmod.probe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * Modern Xposed activation bridge.
 *
 * <p>Modern libxposed intentionally does not inject a module into its own application.  Instead,
 * the framework delivers its service Binder through {@code <applicationId>.XposedService}.  This
 * provider implements that official delivery endpoint and also accepts a separately authenticated
 * heartbeat from the DeepSeek target process.</p>
 */
public final class XposedActivationProvider extends ContentProvider {
    static final String AUTHORITY = "com.dsmod.probe.XposedService";
    static final String METHOD_REPORT_TARGET_ACTIVE = "ReportDeepSeekActive";
    static final String METHOD_SET_LOCAL_API_KEEPALIVE = "SetLocalApiKeepAlive";
    static final String METHOD_GET_LOCAL_API_KEEPALIVE = "GetLocalApiKeepAlive";
    static final String METHOD_ACK_LOCAL_API_KEEPALIVE = "AckLocalApiKeepAlive";

    private static final String METHOD_SEND_BINDER = "SendBinder";
    private static final String SERVICE_DESCRIPTOR = "io.github.libxposed.service.IXposedService";
    private static final String TARGET_PACKAGE = "com.deepseek.chat";
    private static final String PREFS = "deekseep_activation";
    private static final String KEY_TARGET_AT = "target_at";
    private static final String KEY_TARGET_VERSION_NAME = "target_version_name";
    private static final String KEY_TARGET_VERSION_CODE = "target_version_code";
    private static final String KEY_FRAMEWORK_AT = "framework_at";
    private static final long TARGET_FRESH_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final String TAG = "DeekseepActivation";

    private static volatile IBinder frameworkBinder;
    private static volatile Runnable stateListener;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (METHOD_SEND_BINDER.equals(method) && extras != null) {
            return receiveFrameworkBinder(extras.getBinder("binder"));
        }
        if (METHOD_REPORT_TARGET_ACTIVE.equals(method)) {
            return receiveTargetHeartbeat(extras);
        }
        if (METHOD_SET_LOCAL_API_KEEPALIVE.equals(method)) {
            return setLocalApiKeepAlive(extras);
        }
        if (METHOD_GET_LOCAL_API_KEEPALIVE.equals(method)) {
            return getLocalApiKeepAlive();
        }
        if (METHOD_ACK_LOCAL_API_KEEPALIVE.equals(method)) {
            return acknowledgeLocalApiKeepAlive(extras);
        }
        return null;
    }

    private Bundle setLocalApiKeepAlive(Bundle extras) {
        Bundle result = new Bundle();
        Context context = getContext();
        int callingUid = Binder.getCallingUid();
        if (context == null || !uidOwnsPackage(context, callingUid, TARGET_PACKAGE)) {
            Log.w(TAG, "rejected keepalive control from uid=" + callingUid);
            result.putBoolean("accepted", false);
            result.putString("error", "caller is not DeepSeek");
            return result;
        }
        boolean enabled = extras != null && extras.getBoolean("enabled", false);
        boolean accepted = LocalApiKeepAliveService.setEnabled(context, enabled);
        result.putBoolean("accepted", accepted);
        result.putBoolean("enabled", enabled);
        LocalApiKeepAliveService.putStatus(result);
        return result;
    }

    private Bundle getLocalApiKeepAlive() {
        Bundle result = new Bundle();
        Context context = getContext();
        int callingUid = Binder.getCallingUid();
        if (context == null || !uidOwnsPackage(context, callingUid, TARGET_PACKAGE)) {
            result.putBoolean("accepted", false);
            result.putString("error", "caller is not DeepSeek");
            return result;
        }
        result.putBoolean("accepted", true);
        LocalApiKeepAliveService.putStatus(result);
        return result;
    }

    private Bundle acknowledgeLocalApiKeepAlive(Bundle extras) {
        Bundle result = new Bundle();
        Context context = getContext();
        int callingUid = Binder.getCallingUid();
        if (context == null || !uidOwnsPackage(context, callingUid, TARGET_PACKAGE)) {
            result.putBoolean("accepted", false);
            return result;
        }
        boolean enabled = extras != null && extras.getBoolean("enabled", false);
        boolean gatewayRunning = extras != null
                && extras.getBoolean("gateway_running", false);
        LocalApiKeepAliveService.acknowledge(enabled, gatewayRunning);
        if (!enabled) LocalApiKeepAliveService.setEnabled(context, false);
        result.putBoolean("accepted", true);
        return result;
    }

    private Bundle receiveFrameworkBinder(final IBinder binder) {
        Bundle result = new Bundle();
        if (binder == null) {
            result.putBoolean("accepted", false);
            return result;
        }
        try {
            String descriptor = binder.getInterfaceDescriptor();
            if (!SERVICE_DESCRIPTOR.equals(descriptor)) {
                Log.w(TAG, "ignored unexpected service descriptor: " + descriptor);
                result.putBoolean("accepted", false);
                return result;
            }
            frameworkBinder = binder;
            binder.linkToDeath(new IBinder.DeathRecipient() {
                @Override public void binderDied() {
                    if (frameworkBinder == binder) frameworkBinder = null;
                    notifyStateChanged();
                }
            }, 0);
            Context context = getContext();
            if (context != null) {
                prefs(context).edit().putLong(KEY_FRAMEWORK_AT,
                        System.currentTimeMillis()).apply();
            }
            result.putBoolean("accepted", true);
            Log.i(TAG, "modern Xposed service connected");
            notifyStateChanged();
        } catch (Throwable t) {
            Log.w(TAG, "failed to accept Xposed service", t);
            result.putBoolean("accepted", false);
        }
        return result;
    }

    private Bundle receiveTargetHeartbeat(Bundle extras) {
        Bundle result = new Bundle();
        Context context = getContext();
        int callingUid = Binder.getCallingUid();
        if (context == null || !uidOwnsPackage(context, callingUid, TARGET_PACKAGE)) {
            Log.w(TAG, "rejected target heartbeat from uid=" + callingUid);
            result.putBoolean("accepted", false);
            return result;
        }
        SharedPreferences.Editor edit = prefs(context).edit()
                .putLong(KEY_TARGET_AT, System.currentTimeMillis());
        if (extras != null) {
            edit.putString(KEY_TARGET_VERSION_NAME,
                    extras.getString("versionName", ""));
            edit.putLong(KEY_TARGET_VERSION_CODE,
                    extras.getLong("versionCode", 0L));
        }
        edit.apply();
        result.putBoolean("accepted", true);
        Log.i(TAG, "DeepSeek target heartbeat accepted, uid=" + callingUid);
        notifyStateChanged();
        return result;
    }

    private static boolean uidOwnsPackage(Context context, int uid, String wanted) {
        try {
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            if (packages == null) return false;
            for (String pkg : packages) {
                if (wanted.equals(pkg)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    static boolean isFrameworkConnected() {
        IBinder binder = frameworkBinder;
        return binder != null && binder.isBinderAlive() && binder.pingBinder();
    }

    static boolean isTargetRecentlyActive(Context context) {
        long at = targetHeartbeatAt(context);
        long age = System.currentTimeMillis() - at;
        return at > 0L && age >= 0L && age <= TARGET_FRESH_MS;
    }

    static long targetHeartbeatAt(Context context) {
        return prefs(context).getLong(KEY_TARGET_AT, 0L);
    }

    static String targetVersion(Context context) {
        SharedPreferences p = prefs(context);
        String name = p.getString(KEY_TARGET_VERSION_NAME, "");
        long code = p.getLong(KEY_TARGET_VERSION_CODE, 0L);
        if (name == null || name.length() == 0) return "";
        return code > 0L ? name + " (" + code + ")" : name;
    }

    static void setStateListener(Runnable listener) {
        stateListener = listener;
        if (listener != null) notifyStateChanged();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void notifyStateChanged() {
        final Runnable listener = stateListener;
        if (listener == null) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                if (stateListener == listener) listener.run();
            }
        });
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }
}

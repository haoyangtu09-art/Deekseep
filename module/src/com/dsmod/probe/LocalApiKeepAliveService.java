package com.dsmod.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * Foreground companion for the localhost API.
 *
 * <p>The actual gateway must stay inside DeepSeek because it calls obfuscated native transport
 * objects captured by the Xposed hooks. A cached activity process, however, can be frozen even
 * after the OEM battery screen reports "unrestricted". This small companion periodically sends
 * an explicit no-op broadcast. ActivityManager unfreezes the target before delivering it, and the
 * injected receiver hook consumes it before DeepSeek's share-result implementation can see it.</p>
 */
public final class LocalApiKeepAliveService extends Service {
    static final String TARGET_PACKAGE = "com.deepseek.chat";
    static final String TARGET_RECEIVER = "com.deepseek.chat.system.ShareResultReceiver";
    static final String ACTION_HEARTBEAT = "com.dsmod.probe.action.LOCAL_API_KEEPALIVE";
    static final String ACTION_CONTROL = "com.dsmod.probe.action.LOCAL_API_CONTROL";

    static final String ACTION_START = "com.dsmod.probe.action.START_LOCAL_API_KEEPALIVE";
    static final String EXTRA_CONTROL_TOKEN = "deekseep_control_token";
    static final String EXTRA_PROTOCOL = "protocol";
    static final String CONTROL_TOKEN = "deekseep-local-api-keepalive-v1";
    private static final String PREFS = "deekseep_local_api_keepalive";
    private static final String KEY_REQUESTED = "requested";
    private static final String CHANNEL_ID = "deekseep_local_api";
    private static final int NOTIFICATION_ID = 0xD5A1;
    private static final long HEARTBEAT_MS = 5_000L;
    private static final long ACK_TIMEOUT_MS = 90_000L;
    private static final String TAG = "DeekseepKeepAlive";

    private static volatile boolean running;
    private static volatile long startedElapsed;
    private static volatile long lastBroadcastElapsed;
    private static volatile long lastAckElapsed;
    private static volatile boolean lastGatewayRunning;
    private static volatile String lastError = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!isRequested(LocalApiKeepAliveService.this)) {
                stopSelf();
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (startedElapsed > 0L && now - startedElapsed >= ACK_TIMEOUT_MS
                    && (lastAckElapsed <= 0L || now - lastAckElapsed >= ACK_TIMEOUT_MS)) {
                lastError = "DeepSeek 长时间未确认保活，服务已自动停止";
                Log.w(TAG, lastError);
                requestedPrefs(LocalApiKeepAliveService.this).edit()
                        .putBoolean(KEY_REQUESTED, false).apply();
                stopSelf();
                return;
            }
            try {
                Intent ping = new Intent(ACTION_HEARTBEAT);
                ping.setComponent(new ComponentName(TARGET_PACKAGE, TARGET_RECEIVER));
                ping.putExtra(EXTRA_CONTROL_TOKEN, CONTROL_TOKEN);
                ping.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                sendOrderedBroadcast(ping, null, new BroadcastReceiver() {
                    @Override public void onReceive(Context context, Intent intent) {
                        if (getResultCode() != Activity.RESULT_OK) return;
                        String state = getResultData();
                        lastAckElapsed = SystemClock.elapsedRealtime();
                        lastGatewayRunning = state != null && state.endsWith("|running");
                        if (state != null && state.startsWith("disabled|")) {
                            requestedPrefs(LocalApiKeepAliveService.this).edit()
                                    .putBoolean(KEY_REQUESTED, false).apply();
                            stopSelf();
                        }
                    }
                }, handler, Activity.RESULT_CANCELED, null, null);
                lastBroadcastElapsed = now;
            } catch (Throwable t) {
                lastError = "发送保活心跳失败：" + safeMessage(t);
                Log.w(TAG, lastError, t);
            }
            handler.postDelayed(this, HEARTBEAT_MS);
        }
    };

    static boolean setEnabled(Context context, boolean enabled) {
        if (context == null) {
            lastError = "模块上下文不可用";
            return false;
        }
        Context app = context.getApplicationContext();
        requestedPrefs(app).edit().putBoolean(KEY_REQUESTED, enabled).apply();
        Intent intent = new Intent(app, LocalApiKeepAliveService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONTROL_TOKEN, CONTROL_TOKEN);
        try {
            if (enabled) {
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent);
                else app.startService(intent);
            } else {
                app.stopService(intent);
            }
            return true;
        } catch (Throwable t) {
            lastError = (enabled ? "启动" : "停止") + "前台保活失败：" + safeMessage(t);
            Log.w(TAG, lastError, t);
            return false;
        }
    }

    static void sendProtocolControl(Context context, String protocol) {
        if (context == null) return;
        Intent control = new Intent(ACTION_CONTROL)
                .setComponent(new ComponentName(TARGET_PACKAGE, TARGET_RECEIVER))
                .putExtra(EXTRA_CONTROL_TOKEN, CONTROL_TOKEN)
                .putExtra(EXTRA_PROTOCOL, protocol)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        context.sendBroadcast(control);
    }

    static void acknowledge(boolean enabled, boolean gatewayRunning) {
        lastAckElapsed = SystemClock.elapsedRealtime();
        lastGatewayRunning = gatewayRunning;
        if (!enabled) lastError = "DeepSeek 已关闭本地 API";
    }

    static void putStatus(android.os.Bundle result) {
        long now = SystemClock.elapsedRealtime();
        result.putBoolean("running", running);
        result.putBoolean("requested", running || startedElapsed > 0L);
        result.putBoolean("gateway_running", lastGatewayRunning);
        result.putLong("last_broadcast_age_ms", age(now, lastBroadcastElapsed));
        result.putLong("last_ack_age_ms", age(now, lastAckElapsed));
        result.putString("error", lastError == null ? "" : lastError);
    }

    @Override public void onCreate() {
        super.onCreate();
        try {
            createNotificationChannel();
            startForeground(NOTIFICATION_ID, buildNotification());
            acquireWakeLock();
            running = true;
            startedElapsed = SystemClock.elapsedRealtime();
            lastError = "";
            handler.removeCallbacks(heartbeat);
            handler.post(heartbeat);
            Log.i(TAG, "local API foreground keepalive started");
        } catch (Throwable t) {
            lastError = "前台保活初始化失败：" + safeMessage(t);
            Log.e(TAG, lastError, t);
            stopSelf();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        boolean systemRestart = intent == null && isRequested(this);
        if (!systemRestart && (intent == null || !ACTION_START.equals(intent.getAction())
                || !CONTROL_TOKEN.equals(intent.getStringExtra(EXTRA_CONTROL_TOKEN)))) {
            lastError = "拒绝了无效的保活启动请求";
            requestedPrefs(this).edit().putBoolean(KEY_REQUESTED, false).apply();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        requestedPrefs(this).edit().putBoolean(KEY_REQUESTED, true).apply();
        if (!isRequested(this)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        handler.removeCallbacks(heartbeat);
        handler.post(heartbeat);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        lastGatewayRunning = false;
        startedElapsed = 0L;
        handler.removeCallbacks(heartbeat);
        releaseWakeLock();
        try { stopForeground(true); } catch (Throwable ignored) {}
        Log.i(TAG, "local API foreground keepalive stopped");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "DeepSeek 本地 API", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("保持本地 API 与 SSE 流在后台可用");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(TARGET_PACKAGE);
        PendingIntent pending = null;
        if (launch != null) {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            pending = PendingIntent.getActivity(this, 0, launch, flags);
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("DeepSeek 本地 API 正在运行")
                .setContentText("正在保持后台监听与流式响应稳定")
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);
        if (pending != null) builder.setContentIntent(pending);
        return builder.build();
    }

    private void acquireWakeLock() {
        try {
            PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (power == null) return;
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "Deekseep:LocalApiKeepAlive");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Throwable t) {
            lastError = "CPU 保活不可用：" + safeMessage(t);
            Log.w(TAG, lastError, t);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {}
        wakeLock = null;
    }

    private static SharedPreferences requestedPrefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean isRequested(Context context) {
        return requestedPrefs(context).getBoolean(KEY_REQUESTED, false);
    }

    private static long age(long now, long value) {
        return value <= 0L ? -1L : Math.max(0L, now - value);
    }

    private static String safeMessage(Throwable t) {
        if (t == null) return "unknown";
        String message = t.getMessage();
        return message == null || message.length() == 0
                ? t.getClass().getSimpleName() : message;
    }
}

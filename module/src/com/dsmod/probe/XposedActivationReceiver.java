package com.dsmod.probe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Explicit-component fallback for hosts that cannot resolve the module provider because their
 * original manifest has no package-visibility query for an add-on installed later.
 */
public final class XposedActivationReceiver extends BroadcastReceiver {
    static final String ACTION = "com.dsmod.probe.action.REPORT_DEEPSEEK_ACTIVE";
    private static final String TAG = "DeekseepActivation";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        int sendingUid;
        try {
            // Android 14 added an authenticated sender UID for manifest receivers. Older systems
            // keep using the provider path; never accept an unverifiable fallback heartbeat.
            if (android.os.Build.VERSION.SDK_INT < 34) return;
            sendingUid = getSentFromUid();
        } catch (Throwable error) {
            Log.w(TAG, "cannot identify activation broadcast sender", error);
            return;
        }
        Bundle extras = intent.getExtras();
        XposedActivationProvider.recordTargetHeartbeat(
                context, sendingUid, extras, "explicit-broadcast");
    }
}

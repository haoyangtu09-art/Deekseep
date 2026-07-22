package com.dsmod.probe;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** Invisible user-initiated bridge that controls the module-private foreground keeper. */
public final class LocalApiKeepAliveActivity extends Activity {
    static final String SCHEME = "deekseep-module";
    static final String HOST = "local-api-keepalive";
    static final String QUERY_MODE = "mode";
    static final String QUERY_TOKEN = "token";
    static final String MODE_START = "start";
    static final String MODE_STOP = "stop";
    static final String MODE_PROTOCOL_OPENAI = "protocol-openai";
    static final String MODE_PROTOCOL_ANTHROPIC = "protocol-anthropic";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        handle(getIntent());
        finishImmediately();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
        finishImmediately();
    }

    private void handle(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null || !SCHEME.equals(data.getScheme()) || !HOST.equals(data.getHost())
                || !LocalApiKeepAliveService.CONTROL_TOKEN.equals(
                        data.getQueryParameter(QUERY_TOKEN))) return;
        String mode = data.getQueryParameter(QUERY_MODE);
        if (MODE_START.equals(mode)) {
            LocalApiKeepAliveService.setEnabled(this, true);
        } else if (MODE_STOP.equals(mode)) {
            LocalApiKeepAliveService.setEnabled(this, false);
        } else if (MODE_PROTOCOL_OPENAI.equals(mode)) {
            LocalApiKeepAliveService.sendProtocolControl(this, "openai");
        } else if (MODE_PROTOCOL_ANTHROPIC.equals(mode)) {
            LocalApiKeepAliveService.sendProtocolControl(this, "anthropic");
        }
    }

    private void finishImmediately() {
        try { overridePendingTransition(0, 0); } catch (Throwable ignored) {}
        finish();
        try { overridePendingTransition(0, 0); } catch (Throwable ignored) {}
    }
}

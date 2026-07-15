package com.dsmod.inject;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** 模块进程里的文件选择中转页，把 SAF 选中的 Uri 授权回 DeepSeek 进程。 */
public class PromptPickerActivity extends Activity {
    private static final int REQ_PICK = 0xD52E;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String caller = getCallingPackage();
        if (caller != null && !"com.deepseek.chat".equals(caller)) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        if (state == null) startPicker();
    }

    private void startPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null || data.getData() == null) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        Uri uri = data.getData();
        int grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {}
        try {
            grantUriPermission("com.deepseek.chat", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {}

        Intent out = new Intent();
        out.setData(uri);
        out.setClipData(ClipData.newUri(getContentResolver(), "deekseep_prompt", uri));
        out.addFlags(grantFlags);
        setResult(RESULT_OK, out);
        finish();
    }
}

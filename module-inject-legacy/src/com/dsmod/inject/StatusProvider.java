package com.dsmod.inject;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import java.io.File;
import java.io.FileWriter;

/**
 * 跨进程握手用的 ContentProvider（FPA 场景激活判定的唯一可靠无 root 通道）。
 *
 * FPA 只把模块注入进 DeepSeek 进程，从不注入模块自身进程(com.dsmod.inject)，
 * 因此 Main.markSelfActive() 永不执行，SettingsActivity 恒判“未激活”。
 *
 * 解决：本 Provider 声明为 exported，运行在模块自身进程。DeepSeek 进程内的 hook
 * 通过 getContentResolver().call("content://com.dsmod.inject.status","ping",..) 触发它——
 * 这会自动拉起模块进程，call() 以模块 uid 身份把新鲜激活标记写进
 * /data/data/com.dsmod.inject/files/deekseep_active，SettingsActivity.selfMarkerFresh() 读它即判“已激活”。
 */
public class StatusProvider extends ContentProvider {

    static final String AUTHORITY = "com.dsmod.inject.status";
    static final String METHOD_PING = "ping";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (METHOD_PING.equals(method)) {
            boolean ok = writeActiveMarker();
            Bundle b = new Bundle();
            b.putBoolean("ok", ok);
            return b;
        }
        return null;
    }

    // 以模块自身 uid 写入激活标记（模块进程对自己的 files 目录有写权限）
    private boolean writeActiveMarker() {
        try {
            File dir = getContext().getFilesDir();
            if (dir != null && !dir.exists()) dir.mkdirs();
            File mf = new File(dir, "deekseep_active");
            FileWriter w = new FileWriter(mf, false);
            w.write(String.valueOf(System.currentTimeMillis()));
            w.close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ── 其余 ContentProvider 抽象方法：无实际数据表，全部 no-op ──
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}

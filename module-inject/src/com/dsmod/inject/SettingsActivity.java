package com.dsmod.inject;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    public static final String VERSION = "1.7-inject";
    private static final int REQ_STORAGE = 0xD540;

    private boolean dark;
    private int text, sub, card, bg;

    public static boolean isModuleActive() { return false; }

    // 二次判据：模块自身进程被注入时会写一个新鲜的激活标记文件
    private boolean selfMarkerFresh() {
        try {
            java.io.File f = new java.io.File("/data/data/" + getPackageName() + "/files/deekseep_active");
            return f.exists() && (System.currentTimeMillis() - f.lastModified() < 5 * 60 * 1000L);
        } catch (Throwable t) {
            return false;
        }
    }

    // 主判据（FPA/容器场景）：模块自身进程不会被注入，selfMarkerFresh 永远失败。
    // 但 DeepSeek 进程每次加载都会往共享外部存储写 LOADED_MARK_EXT，读它即可判活。
    private boolean deepseekLoadedRecently() {
        try {
            java.io.File f = new java.io.File(Main.LOADED_MARK_EXT);
            return f.exists() && (System.currentTimeMillis() - f.lastModified() < 7L * 24 * 60 * 60 * 1000L);
        } catch (Throwable t) {
            return false;
        }
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        ensureStoragePermission();

        dark  = DeekseepUi.isDark(this);
        bg    = dark ? 0xFF1B1B1D : 0xFFF5F6F8;
        card  = dark ? 0xFF2A2A2D : 0xFFFFFFFF;
        text  = dark ? 0xFFECECEC : 0xFF1A1A1A;
        sub   = dark ? 0xFF9A9A9E : 0xFF888888;

        boolean active = isModuleActive() || selfMarkerFresh() || deepseekLoadedRecently();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setPadding(dp(16), dp(24), dp(16), dp(16));

        // 标题
        addText(root, "DeekseepX", 26, text, Typeface.BOLD, 0);
        addText(root, "DeepSeek 注入实验", 13, sub, Typeface.NORMAL, dp(4));

        addSpacer(root, dp(20));

        // 激活状态卡片
        LinearLayout stCard = makeCard();
        stCard.setGravity(Gravity.CENTER);
        stCard.setPadding(dp(20), dp(44), dp(20), dp(44));

        TextView dot = new TextView(this);
        dot.setText(active ? "\u25CF  已激活" : "\u25CB  未激活");
        dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        dot.setTypeface(Typeface.DEFAULT_BOLD);
        dot.setTextColor(active ? 0xFF2ECC71 : 0xFFB0B0B0);
        dot.setGravity(Gravity.CENTER);
        stCard.addView(dot);

        addTextToCard(stCard, active
                ? "已在 DeepSeek 设置页右上角显示 Deekseep 入口"
                : "请在 LSPosed 启用本模块并勾选 DeepSeek 与本应用",
                13, sub, dp(12));

        root.addView(stCard, cardLp(dp(14)));

        // 版本卡片
        LinearLayout verCard = makeCard();
        verCard.setOrientation(LinearLayout.HORIZONTAL);
        verCard.setGravity(Gravity.CENTER_VERTICAL);
        verCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        addText(verCard, "版本", 16, text, Typeface.NORMAL, 0).setLayoutParams(
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addText(verCard, VERSION, 16, sub, Typeface.NORMAL, 0);
        root.addView(verCard, cardLp(dp(14)));

        // 版本下方灰色小字：libxposed API 版本 + 编译日期
        addText(root, "libxposed API " + BuildInfo.API_VERSION + "　·　编译于 " + BuildInfo.BUILD_DATE,
                11, sub, Typeface.NORMAL, dp(8));

        setContentView(root);
    }

    private void ensureStoragePermission() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                if (!Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "请授予 Deekseep 储存权限", Toast.LENGTH_SHORT).show();
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    try {
                        startActivity(i);
                    } catch (Throwable t) {
                        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    }
                }
                return;
            }

            if (Build.VERSION.SDK_INT >= 23
                    && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT <= 28) {
                    requestPermissions(new String[]{
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                    }, REQ_STORAGE);
                } else {
                    requestPermissions(new String[]{
                            Manifest.permission.READ_EXTERNAL_STORAGE
                    }, REQ_STORAGE);
                }
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_STORAGE) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        Toast.makeText(this, granted ? "储存权限已授予" : "未授予储存权限", Toast.LENGTH_SHORT).show();
    }

    // ── UI helpers ─────────────────────────────────────────────

    private LinearLayout makeCard() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg2 = new GradientDrawable();
        bg2.setColor(card);
        bg2.setCornerRadius(dp(14));
        ll.setBackground(bg2);
        return ll;
    }

    private LinearLayout.LayoutParams cardLp(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        return lp;
    }

    private void addSpacer(LinearLayout parent, int height) {
        parent.addView(new android.view.View(this),
                new LinearLayout.LayoutParams(0, height));
    }

    private TextView addText(ViewGroup parent, String txt, int sp, int color,
                             int style, int topMargin) {
        TextView tv = new TextView(this);
        tv.setText(txt);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        tv.setTypeface(style == Typeface.BOLD ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        parent.addView(tv, lp);
        return tv;
    }

    private void addTextToCard(ViewGroup parent, String txt, int sp, int color, int topMargin) {
        TextView tv = new TextView(this);
        tv.setText(txt);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        parent.addView(tv, lp);
    }
}

package com.dsmod.probe;

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
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    public static final String VERSION = "1.7.1";
    private static final int REQ_STORAGE = 0xD540;

    private boolean dark;
    private int text, sub, card, bg;
    private TextView activationTitle;
    private TextView activationDetail;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable activationStateChanged = new Runnable() {
        @Override public void run() { refreshActivationState(); }
    };

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

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setPadding(dp(16), dp(24), dp(16), dp(16));

        // 标题
        addText(root, "Deekseep", 26, text, Typeface.BOLD, 0);
        addText(root, "DeepSeek 模块", 13, sub, Typeface.NORMAL, dp(4));

        addSpacer(root, dp(20));

        // 激活状态卡片
        LinearLayout stCard = makeCard();
        stCard.setGravity(Gravity.CENTER);
        stCard.setPadding(dp(20), dp(44), dp(20), dp(44));

        activationTitle = new TextView(this);
        activationTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        activationTitle.setTypeface(Typeface.DEFAULT_BOLD);
        activationTitle.setGravity(Gravity.CENTER);
        stCard.addView(activationTitle);

        activationDetail = new TextView(this);
        activationDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        activationDetail.setTextColor(sub);
        activationDetail.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusDetailLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusDetailLp.topMargin = dp(12);
        stCard.addView(activationDetail, statusDetailLp);

        root.addView(stCard, cardLp(dp(14)));

        // 版本卡片
        LinearLayout verCard = makeCard();
        verCard.setOrientation(LinearLayout.HORIZONTAL);
        verCard.setGravity(Gravity.CENTER_VERTICAL);
        verCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        addText(verCard, "版本", 16, text, Typeface.NORMAL, 0).setLayoutParams(
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addText(verCard, BuildInfo.MODULE_VERSION, 16, sub, Typeface.NORMAL, 0);
        root.addView(verCard, cardLp(dp(14)));

        // 版本下方灰色小字：libxposed API 版本 + 编译日期
        addText(root, (isLegacyBuild() ? "Xposed API " : "libxposed API ")
                        + BuildInfo.API_VERSION + "　·　编译于 " + BuildInfo.BUILD_DATE,
                11, sub, Typeface.NORMAL, dp(8));

        setContentView(root);
        refreshActivationState();
        XposedActivationProvider.setStateListener(activationStateChanged);
        handler.postDelayed(activationStateChanged, 300L);
        handler.postDelayed(activationStateChanged, 1200L);
    }

    @Override
    protected void onResume() {
        super.onResume();
        XposedActivationProvider.setStateListener(activationStateChanged);
        handler.post(activationStateChanged);
        handler.postDelayed(activationStateChanged, 500L);
    }

    @Override
    protected void onDestroy() {
        XposedActivationProvider.setStateListener(null);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void refreshActivationState() {
        if (activationTitle == null || activationDetail == null || isFinishing()) return;
        boolean framework = XposedActivationProvider.isFrameworkConnected();
        boolean target = XposedActivationProvider.isTargetRecentlyActive(this);
        if (isLegacyBuild()) {
            activationTitle.setText(target ? "\u25CF  已激活" : "\u25CB  待验证");
            activationTitle.setTextColor(target ? 0xFF2ECC71 : 0xFFB0B0B0);
            activationDetail.setText(target
                    ? "DeepSeek 目标进程最近已验证传统 Xposed 注入。" + targetVersionSuffix()
                    : "尚未收到 DeepSeek 目标回报。请在传统 Xposed/FPA 中启用模块、勾选 "
                            + "DeepSeek，然后启动一次 DeepSeek。");
            return;
        }
        if (framework && target) {
            activationTitle.setText("\u25CF  已激活");
            activationTitle.setTextColor(0xFF2ECC71);
            activationDetail.setText("LSPosed 服务已连接，DeepSeek 目标进程也已验证注入。"
                    + targetVersionSuffix());
        } else if (target) {
            activationTitle.setText("\u25CF  已激活");
            activationTitle.setTextColor(0xFF2ECC71);
            activationDetail.setText("DeepSeek 目标进程最近已验证注入；框架服务会在可用时自动重连。"
                    + targetVersionSuffix());
        } else if (framework) {
            activationTitle.setText("\u25CF  已启用");
            activationTitle.setTextColor(0xFF2ECC71);
            activationDetail.setText("LSPosed 已连接本模块。启动一次 DeepSeek 后，将进一步验证目标作用域。 ");
        } else {
            activationTitle.setText("\u25CB  待验证");
            activationTitle.setTextColor(0xFFB0B0B0);
            activationDetail.setText("尚未收到现代 Xposed 服务或 DeepSeek 目标回报。请在 LSPosed 启用模块、"
                    + "勾选 DeepSeek，然后启动一次 DeepSeek。无需勾选模块应用自身。");
        }
    }

    private static boolean isLegacyBuild() {
        return BuildInfo.API_VERSION != null && BuildInfo.API_VERSION.contains("legacy");
    }

    private String targetVersionSuffix() {
        String version = XposedActivationProvider.targetVersion(this);
        return version.length() == 0 ? "" : " DeepSeek " + version;
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

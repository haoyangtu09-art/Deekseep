package com.dsmod.probe;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Plain standalone status page. Its language follows Android's system language. */
public class SettingsActivity extends Activity {

    public static final String VERSION = BuildInfo.MODULE_VERSION;
    private static final int REQ_STORAGE = 0xD540;
    private static final String TARGET_PACKAGE = "com.deepseek.chat";
    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFF5F5F5;
    private static final int MUTED = 0xFF999999;
    private static final int LINE = 0xFF2A2A2A;

    private boolean renderedChinese;
    private boolean rendered;
    private TextView activationTitle;
    private TextView activationDetail;
    private TextView activationMark;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable activationStateChanged = new Runnable() {
        @Override public void run() { refreshActivationState(); }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        UiLanguage.refreshSystem(this);
        renderUi();
        ensureStoragePermission();
        XposedActivationProvider.setStateListener(activationStateChanged);
        handler.postDelayed(activationStateChanged, 300L);
        handler.postDelayed(activationStateChanged, 1200L);
    }

    @Override
    protected void onResume() {
        super.onResume();
        UiLanguage.refreshSystem(this);
        boolean currentChinese = UiLanguage.isChinese(this);
        if (!rendered || currentChinese != renderedChinese) renderUi();
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

    private void renderUi() {
        renderedChinese = UiLanguage.isChinese(this);
        rendered = true;
        configureBlackWindow();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BLACK);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(42), dp(22), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(text("Deekseep", 34, WHITE, Typeface.DEFAULT));
        addDivider(root, 24);

        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(0, dp(22), 0, dp(22));
        root.addView(status, matchWrap());

        LinearLayout statusText = new LinearLayout(this);
        statusText.setOrientation(LinearLayout.VERTICAL);
        status.addView(statusText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        activationTitle = text("", 25, WHITE,
                Typeface.create("sans-serif-medium", Typeface.NORMAL));
        statusText.addView(activationTitle);
        activationDetail = text("", 14, MUTED, Typeface.DEFAULT);
        LinearLayout.LayoutParams detailLp = matchWrap();
        detailLp.topMargin = dp(5);
        statusText.addView(activationDetail, detailLp);

        activationMark = text("", 31, WHITE, Typeface.DEFAULT);
        activationMark.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        markLp.leftMargin = dp(12);
        status.addView(activationMark, markLp);

        addDivider(root, 0);
        root.addView(infoRow(UiLanguage.text(this, "DeepSeek 版本", "DeepSeek version"),
                deepSeekVersion()));
        addDivider(root, 0);
        root.addView(infoRow(UiLanguage.text(this, "模块版本", "Module version"),
                BuildInfo.MODULE_VERSION));
        addDivider(root, 0);
        root.addView(infoRow(UiLanguage.text(this, "模块编译时间", "Module build time"),
                BuildInfo.BUILD_DATE));
        addDivider(root, 0);

        setContentView(scroll);
        refreshActivationState();
    }

    private LinearLayout infoRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(20), 0, dp(20));
        TextView name = text(label, 17, MUTED, Typeface.DEFAULT);
        row.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView data = text(value, 17, WHITE, Typeface.DEFAULT);
        data.setGravity(Gravity.END);
        row.addView(data);
        return row;
    }

    private void refreshActivationState() {
        if (activationTitle == null || isFinishing()) return;
        boolean framework = XposedActivationProvider.isFrameworkConnected();
        boolean target = XposedActivationProvider.isTargetRecentlyActive(this);
        if (target) {
            activationTitle.setText(UiLanguage.text(this, "已激活", "Activated"));
            activationDetail.setText(apiDisplayName() + "  ·  DeepSeek " + deepSeekVersion());
            activationMark.setText("✓");
        } else if (framework || isLegacyBuild()) {
            activationTitle.setText(UiLanguage.text(this, "待验证", "Waiting for verification"));
            activationDetail.setText(apiDisplayName() + "  ·  " + UiLanguage.text(this,
                    "启动 DeepSeek 后确认", "Launch DeepSeek to confirm"));
            activationMark.setText("—");
        } else {
            activationTitle.setText(UiLanguage.text(this, "未激活", "Not activated"));
            activationDetail.setText(apiDisplayName() + "  ·  " + UiLanguage.text(this,
                    "Xposed 框架未连接", "Xposed framework not connected"));
            activationMark.setText("×");
        }
    }

    private String deepSeekVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
            long code = Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode() : info.versionCode;
            String name = info.versionName == null ? "" : info.versionName;
            return code > 0L ? name + " (" + code + ")" : name;
        } catch (Throwable ignored) {
            return UiLanguage.text(this, "未安装", "Not installed");
        }
    }

    private String apiDisplayName() {
        String value = BuildInfo.API_VERSION == null ? "" : BuildInfo.API_VERSION.trim();
        return (isLegacyBuild() ? "Xposed API " : "API ") + value;
    }

    private static boolean isLegacyBuild() {
        return BuildInfo.API_VERSION != null && BuildInfo.API_VERSION.contains("legacy");
    }

    private void configureBlackWindow() {
        Window window = getWindow();
        if (window == null) return;
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(BLACK);
        window.setNavigationBarColor(BLACK);
        View decor = window.getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (Build.VERSION.SDK_INT >= 23) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(flags);
    }

    private void ensureStoragePermission() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                if (!Environment.isExternalStorageManager()) {
                    Toast.makeText(this, UiLanguage.text(this,
                            "请授予 Deekseep 储存权限", "Please grant Deekseep storage access"),
                            Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    try {
                        startActivity(intent);
                    } catch (Throwable error) {
                        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    }
                }
                return;
            }
            if (Build.VERSION.SDK_INT >= 23
                    && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT <= 28) {
                    requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
                } else {
                    requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                            REQ_STORAGE);
                }
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_STORAGE) return;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        Toast.makeText(this, UiLanguage.text(this,
                granted ? "储存权限已授予" : "未授予储存权限",
                granted ? "Storage access granted" : "Storage access was not granted"),
                Toast.LENGTH_SHORT).show();
    }

    private TextView text(String value, int sp, int color, Typeface typeface) {
        TextView view = new TextView(this);
        view.setText(value == null ? "" : value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(typeface);
        return view;
    }

    private View divider() {
        View view = new View(this);
        view.setBackgroundColor(LINE);
        return view;
    }

    private void addDivider(LinearLayout parent, int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.topMargin = dp(topMarginDp);
        parent.addView(divider(), params);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, getResources().getDisplayMetrics()));
    }
}

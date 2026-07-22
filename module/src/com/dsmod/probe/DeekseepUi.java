package com.dsmod.probe;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** 纯原生 View 构建入口按钮与 Deekseep 子页面，不依赖宿主 compose。 */
public final class DeekseepUi {

    static final int BRAND = 0xFF4D6BFE;
    private static volatile Dialog activePageDialog;
    private static volatile Dialog localApiPageDialog;
    private static volatile boolean localApiSettingsFromPage;

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static boolean isDark(Context c) {
        // Deekseep deliberately follows the device color scheme.  DeepSeek can maintain a
        // separate in-app theme, so its wrapped Activity resources are not authoritative here.
        try {
            int systemMode = Resources.getSystem().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            if (systemMode == Configuration.UI_MODE_NIGHT_YES) return true;
            if (systemMode == Configuration.UI_MODE_NIGHT_NO) return false;
        } catch (Throwable ignored) {}
        try {
            return c != null && (c.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void dismissForNativeNavigation() {
        Dialog dialog = activePageDialog;
        activePageDialog = null;
        if (dialog != null) {
            try { dialog.dismiss(); } catch (Throwable ignored) {}
        }
    }

    /** 右上角的文字入口 "Deekseep"（无背景）。 */
    static TextView createEntryButton(Context ctx, View.OnClickListener onClick) {
        TextView b = new TextView(ctx);
        b.setText("Deekseep");
        b.setTextColor(isDark(ctx) ? 0xFFECECEC : 0xFF1A1A1A);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(onClick);
        return b;
    }

    /** 全屏 Dialog：顶部返回+标题，卡片含导入按钮/路径/还原/注入开关。 */
    static void showPage(final Activity act) {
        // Re-read DeepSeek's MMKV language tag at the moment the page is opened.  This also covers
        // hosts that change language without recreating or resuming their current Activity.
        UiLanguage.refreshHost(act);
        boolean dark = isDark(act);
        int bgColor   = dark ? 0xFF1B1B1D : 0xFFF5F6F8;
        int barColor  = dark ? 0xFF232326 : 0xFFFFFFFF;
        int cardColor = dark ? 0xFF2A2A2D : 0xFFFFFFFF;
        int textColor = dark ? 0xFFECECEC : 0xFF1A1A1A;
        int subColor  = dark ? 0xFF9A9A9E : 0xFF888888;
        int divColor  = dark ? 0xFF3A3A3D : 0xFFEEEEEE;

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bgColor);

        // 顶部栏
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(barColor);
        int barH = dp(act, 56);
        int statusTop = statusBarHeight(act);
        bar.setPadding(dp(act, 8), statusTop, dp(act, 16), 0);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barH + statusTop));

        final Dialog dlg = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        activePageDialog = dlg;
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            public void onDismiss(android.content.DialogInterface ignored) {
                if (activePageDialog == dlg) activePageDialog = null;
            }
        });

        TextView back = new TextView(act);
        back.setText("\u2039");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        back.setTextColor(textColor);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(act, 8), 0, dp(act, 8), 0);
        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { slideOutAndDismiss(dlg, root); }
        });
        bar.addView(back, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(act, 40)));

        TextView title = new TextView(act);
        title.setText("Deekseep");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(textColor);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(act, 8);
        bar.addView(title, tlp);

        // 可滚动区域（内容变多/帮助折叠展开时不会溢出屏幕）
        android.widget.ScrollView scroll = new android.widget.ScrollView(act);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 主卡片
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(cardColor);
        cardBg.setCornerRadius(dp(act, 12));
        card.setBackground(cardBg);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(dp(act, 16), dp(act, 16), dp(act, 16), dp(act, 16));
        scroll.addView(card, clp);

        // ── Section 1: 导入按钮 + 路径 ─────────────────────────────
        LinearLayout importSection = new LinearLayout(act);
        importSection.setOrientation(LinearLayout.VERTICAL);
        importSection.setGravity(Gravity.CENTER_HORIZONTAL);
        importSection.setPadding(dp(act, 16), dp(act, 18), dp(act, 16), dp(act, 14));

        // 偏椭圆按钮
        final TextView importBtn = new TextView(act);
        importBtn.setText("导入提示词");
        importBtn.setTextColor(BRAND);
        importBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        importBtn.setTypeface(Typeface.DEFAULT_BOLD);
        importBtn.setGravity(Gravity.CENTER);
        importBtn.setPadding(dp(act, 32), dp(act, 10), dp(act, 32), dp(act, 10));
        GradientDrawable importBg = new GradientDrawable();
        importBg.setColor(dark ? 0xFF252545 : 0xFFEEF1FF);
        importBg.setCornerRadius(dp(act, 50));
        importBtn.setBackground(importBg);
        importBtn.setClickable(true);
        importBtn.setFocusable(true);
        importSection.addView(importBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 路径文字
        final TextView pathText = new TextView(act);
        pathText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pathText.setTextColor(subColor);
        pathText.setGravity(Gravity.CENTER);
        pathText.setText(Main.getPromptDisplayPath());
        LinearLayout.LayoutParams ptlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ptlp.topMargin = dp(act, 8);
        importSection.addView(pathText, ptlp);
        card.addView(importSection, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        importBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Main.onPickComplete = new Runnable() {
                    public void run() {
                        pathText.setText(Main.getPromptDisplayPath());
                    }
                };
                Intent i = new Intent();
                i.setClassName(Main.SELF, Main.SELF + ".PromptPickerActivity");
                try {
                    act.startActivityForResult(i, Main.PICK_REQUEST);
                } catch (ActivityNotFoundException e) {
                    Intent fallback = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    fallback.addCategory(Intent.CATEGORY_OPENABLE);
                    fallback.setType("text/*");
                    fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    fallback.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    act.startActivityForResult(fallback, Main.PICK_REQUEST);
                }
            }
        });

        // ── 分割线 ──────────────────────────────────────────────────
        card.addView(makeDivider(act, divColor));

        // ── Section 2: 还原设置 ─────────────────────────────────────
        LinearLayout resetRow = new LinearLayout(act);
        resetRow.setOrientation(LinearLayout.HORIZONTAL);
        resetRow.setGravity(Gravity.CENTER_VERTICAL);
        resetRow.setPadding(dp(act, 16), dp(act, 16), dp(act, 16), dp(act, 16));
        resetRow.setClickable(true);
        resetRow.setFocusable(true);

        TextView resetLabel = new TextView(act);
        resetLabel.setText("还原设置");
        resetLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        resetLabel.setTextColor(0xFFE53935);
        resetRow.addView(resetLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        resetRow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Main.clearPromptFiles();
                pathText.setText("");
            }
        });
        card.addView(resetRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── 分割线 ──────────────────────────────────────────────────
        card.addView(makeDivider(act, divColor));

        // ── Section 3: 系统提示词注入开关 ───────────────────────────
        LinearLayout toggleRow = new LinearLayout(act);
        toggleRow.setOrientation(LinearLayout.HORIZONTAL);
        toggleRow.setGravity(Gravity.CENTER_VERTICAL);
        toggleRow.setPadding(dp(act, 16), dp(act, 14), dp(act, 12), dp(act, 14));

        TextView toggleLabel = new TextView(act);
        toggleLabel.setText("系统提示词注入");
        toggleLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        toggleLabel.setTextColor(textColor);
        toggleRow.addView(toggleLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(act);
        sw.setChecked(Main.isEnabled());
        int[][] ss = {{android.R.attr.state_checked}, {-android.R.attr.state_checked}};
        // ON: thumb=蓝色, track=浅蓝; OFF: thumb=白色/灰, track=灰
        sw.setThumbTintList(new android.content.res.ColorStateList(ss,
                new int[]{BRAND, dark ? 0xFFCCCCCC : 0xFFFFFFFF}));
        sw.setTrackTintList(new android.content.res.ColorStateList(ss,
                new int[]{0xFFADBFFF, dark ? 0xFF555555 : 0xFFBFBFBF}));
        sw.setBackground(null);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                Main.setEnabled(checked);
            }
        });
        toggleRow.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(toggleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── 分割线 ──────────────────────────────────────────────────
        card.addView(makeDivider(act, divColor));

        // ── Section 4: 去他妈的安全审查（阻止内容擦除）────────────────
        LinearLayout censorRow = new LinearLayout(act);
        censorRow.setOrientation(LinearLayout.HORIZONTAL);
        censorRow.setGravity(Gravity.CENTER_VERTICAL);
        censorRow.setPadding(dp(act, 16), dp(act, 14), dp(act, 12), dp(act, 14));

        LinearLayout censorLabels = new LinearLayout(act);
        censorLabels.setOrientation(LinearLayout.VERTICAL);

        TextView censorLabel = new TextView(act);
        censorLabel.setText("去他妈的安全审查");
        censorLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        censorLabel.setTypeface(Typeface.DEFAULT_BOLD);
        censorLabel.setTextColor(textColor);
        censorLabels.addView(censorLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView censorDesc = new TextView(act);
        censorDesc.setText("回答流完后，服务端会追加一帧把整段内容替换成模板回复"
                + "\u201C这个问题我暂时无法回答\u201D。开启后，模块直接丢弃这帧"
                + "（带 CONTENT_FILTER 标记的替换帧），已生成的内容原样留在屏幕上。");
        censorDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        censorDesc.setTextColor(subColor);
        LinearLayout.LayoutParams cdlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cdlp.topMargin = dp(act, 4);
        censorLabels.addView(censorDesc, cdlp);

        LinearLayout.LayoutParams cllp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cllp.rightMargin = dp(act, 12);
        censorRow.addView(censorLabels, cllp);

        Switch censorSw = new Switch(act);
        censorSw.setChecked(Main.isNoCensor());
        censorSw.setThumbTintList(new android.content.res.ColorStateList(ss,
                new int[]{BRAND, dark ? 0xFFCCCCCC : 0xFFFFFFFF}));
        censorSw.setTrackTintList(new android.content.res.ColorStateList(ss,
                new int[]{0xFFADBFFF, dark ? 0xFF555555 : 0xFFBFBFBF}));
        censorSw.setBackground(null);
        censorSw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                Main.setNoCensor(checked);
            }
        });
        censorRow.addView(censorSw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(censorRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── 分割线 ──────────────────────────────────────────────────
        card.addView(makeDivider(act, divColor));

        // ── Section 4a: 聊天记录多选 ────────────────────────────────
        LinearLayout multiRow = new LinearLayout(act);
        multiRow.setOrientation(LinearLayout.HORIZONTAL);
        multiRow.setGravity(Gravity.CENTER_VERTICAL);
        multiRow.setPadding(dp(act, 16), dp(act, 14), dp(act, 12), dp(act, 14));

        LinearLayout multiLabels = new LinearLayout(act);
        multiLabels.setOrientation(LinearLayout.VERTICAL);

        TextView multiLabel = new TextView(act);
        multiLabel.setText("聊天记录多选");
        multiLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        multiLabel.setTypeface(Typeface.DEFAULT_BOLD);
        multiLabel.setTextColor(textColor);
        multiLabels.addView(multiLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView multiDesc = new TextView(act);
        multiDesc.setText("开启后，长按左侧聊天记录进入多选模式；关闭后使用 DeepSeek 原本的重命名/删除菜单。");
        multiDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        multiDesc.setTextColor(subColor);
        LinearLayout.LayoutParams mdlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mdlp.topMargin = dp(act, 4);
        multiLabels.addView(multiDesc, mdlp);

        LinearLayout.LayoutParams mllp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mllp.rightMargin = dp(act, 12);
        multiRow.addView(multiLabels, mllp);

        Switch multiSw = new Switch(act);
        multiSw.setChecked(Main.isChatMultiSelect());
        multiSw.setThumbTintList(new android.content.res.ColorStateList(ss,
                new int[]{BRAND, dark ? 0xFFCCCCCC : 0xFFFFFFFF}));
        multiSw.setTrackTintList(new android.content.res.ColorStateList(ss,
                new int[]{0xFFADBFFF, dark ? 0xFF555555 : 0xFFBFBFBF}));
        multiSw.setBackground(null);
        multiSw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                Main.setChatMultiSelect(checked);
            }
        });
        multiRow.addView(multiSw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(multiRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── 分割线 ──────────────────────────────────────────────────
        card.addView(makeDivider(act, divColor));

        // ── Section 4c: 解锁原生 Google 登录入口 ────────────────────
        LinearLayout googleRow = new LinearLayout(act);
        googleRow.setOrientation(LinearLayout.HORIZONTAL);
        googleRow.setGravity(Gravity.CENTER_VERTICAL);
        googleRow.setPadding(dp(act, 16), dp(act, 14), dp(act, 12), dp(act, 14));

        LinearLayout googleLabels = new LinearLayout(act);
        googleLabels.setOrientation(LinearLayout.VERTICAL);

        TextView googleLabel = new TextView(act);
        googleLabel.setText("解锁 Google 登录");
        googleLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        googleLabel.setTypeface(Typeface.DEFAULT_BOLD);
        googleLabel.setTextColor(textColor);
        googleLabels.addView(googleLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView googleDesc = new TextView(act);
        googleDesc.setText("国内登录页默认隐藏 Google。开启后保留微信、手机号等入口，并把 DeepSeek 自带的"
                + "原生 Google 登录项恢复到列表；点击仍走宿主 Credential Manager 和官方登录接口。"
                + "请在进入登录页前开启，切换后建议完整重启 DeepSeek。");
        googleDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        googleDesc.setTextColor(subColor);
        LinearLayout.LayoutParams gdlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gdlp.topMargin = dp(act, 4);
        googleLabels.addView(googleDesc, gdlp);

        LinearLayout.LayoutParams gllp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        gllp.rightMargin = dp(act, 12);
        googleRow.addView(googleLabels, gllp);

        Switch googleSw = new Switch(act);
        googleSw.setChecked(Main.isGoogleLoginUnlock());
        googleSw.setThumbTintList(new android.content.res.ColorStateList(ss,
                new int[]{BRAND, dark ? 0xFFCCCCCC : 0xFFFFFFFF}));
        googleSw.setTrackTintList(new android.content.res.ColorStateList(ss,
                new int[]{0xFFADBFFF, dark ? 0xFF555555 : 0xFFBFBFBF}));
        googleSw.setBackground(null);
        googleSw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                Main.setGoogleLoginUnlock(checked);
            }
        });
        googleRow.addView(googleSw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(googleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── 分割线 ──────────────────────────────────────────────────
        card.addView(makeDivider(act, divColor));

        // ── Section 4d: 海外环境恢复微信 + 手机号登录（一个联合开关）────
        LinearLayout cnLoginRow = new LinearLayout(act);
        cnLoginRow.setOrientation(LinearLayout.HORIZONTAL);
        cnLoginRow.setGravity(Gravity.CENTER_VERTICAL);
        cnLoginRow.setPadding(dp(act, 16), dp(act, 14), dp(act, 12), dp(act, 14));

        LinearLayout cnLoginLabels = new LinearLayout(act);
        cnLoginLabels.setOrientation(LinearLayout.VERTICAL);
        TextView cnLoginLabel = new TextView(act);
        cnLoginLabel.setText("解锁微信与手机号登录");
        cnLoginLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        cnLoginLabel.setTypeface(Typeface.DEFAULT_BOLD);
        cnLoginLabel.setTextColor(textColor);
        cnLoginLabels.addView(cnLoginLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView cnLoginDesc = new TextView(act);
        cnLoginDesc.setText("海外登录页默认隐藏微信和短信手机号。此开关会同时恢复这两个 DeepSeek 原生入口，"
                + "不会联动 Google 开关；点击后仍走宿主自己的微信 SDK、验证码页和官方登录接口。"
                + "请在进入登录页前开启，切换后建议完整重启 DeepSeek。");
        cnLoginDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        cnLoginDesc.setTextColor(subColor);
        LinearLayout.LayoutParams cndlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cndlp.topMargin = dp(act, 4);
        cnLoginLabels.addView(cnLoginDesc, cndlp);

        LinearLayout.LayoutParams cnllp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cnllp.rightMargin = dp(act, 12);
        cnLoginRow.addView(cnLoginLabels, cnllp);

        Switch cnLoginSw = new Switch(act);
        cnLoginSw.setChecked(Main.isWechatMobileLoginUnlock());
        cnLoginSw.setThumbTintList(new android.content.res.ColorStateList(ss,
                new int[]{BRAND, dark ? 0xFFCCCCCC : 0xFFFFFFFF}));
        cnLoginSw.setTrackTintList(new android.content.res.ColorStateList(ss,
                new int[]{0xFFADBFFF, dark ? 0xFF555555 : 0xFFBFBFBF}));
        cnLoginSw.setBackground(null);
        cnLoginSw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                Main.setWechatMobileLoginUnlock(checked);
            }
        });
        cnLoginRow.addView(cnLoginSw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(cnLoginRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── 分割线 ──────────────────────────────────────────────────
        card.addView(makeDivider(act, divColor));

        // ── Section 5: 记录服务器返回（诊断）─────────────────────────
        LinearLayout srvRow = new LinearLayout(act);
        srvRow.setOrientation(LinearLayout.HORIZONTAL);
        srvRow.setGravity(Gravity.CENTER_VERTICAL);
        srvRow.setPadding(dp(act, 16), dp(act, 14), dp(act, 12), dp(act, 14));

        LinearLayout srvLabels = new LinearLayout(act);
        srvLabels.setOrientation(LinearLayout.VERTICAL);

        TextView srvLabel = new TextView(act);
        srvLabel.setText("记录服务器返回（诊断）");
        srvLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        srvLabel.setTypeface(Typeface.DEFAULT_BOLD);
        srvLabel.setTextColor(textColor);
        srvLabels.addView(srvLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView srvDesc = new TextView(act);
        srvDesc.setText("把服务器返回的每一条 SSE 原始事件写到日志，用于排查内容为何被替换。"
                + "日志：/data/data/com.deepseek.chat/files/deekseep_srv.log"
                + "（也会尽量写一份到 /sdcard/deekseep_srv.log）。仅诊断时打开。");
        srvDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        srvDesc.setTextColor(subColor);
        LinearLayout.LayoutParams sdlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sdlp.topMargin = dp(act, 4);
        srvLabels.addView(srvDesc, sdlp);

        LinearLayout.LayoutParams sllp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sllp.rightMargin = dp(act, 12);
        srvRow.addView(srvLabels, sllp);

        Switch srvSw = new Switch(act);
        srvSw.setChecked(Main.isSrvLog());
        srvSw.setThumbTintList(new android.content.res.ColorStateList(ss,
                new int[]{BRAND, dark ? 0xFFCCCCCC : 0xFFFFFFFF}));
        srvSw.setTrackTintList(new android.content.res.ColorStateList(ss,
                new int[]{0xFFADBFFF, dark ? 0xFF555555 : 0xFFBFBFBF}));
        srvSw.setBackground(null);
        srvSw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                Main.setSrvLog(checked);
            }
        });
        srvRow.addView(srvSw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(srvRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── 分割线 ──────────────────────────────────────────────────
        card.addView(makeDivider(act, divColor));

        // ── Section 6: 编辑聊天记录 ─────────────────────────────────
        LinearLayout editRow = new LinearLayout(act);
        editRow.setOrientation(LinearLayout.HORIZONTAL);
        editRow.setGravity(Gravity.CENTER_VERTICAL);
        editRow.setPadding(dp(act, 16), dp(act, 16), dp(act, 16), dp(act, 16));
        editRow.setClickable(true);
        editRow.setFocusable(true);

        LinearLayout editLabels = new LinearLayout(act);
        editLabels.setOrientation(LinearLayout.VERTICAL);
        TextView editLabel = new TextView(act);
        editLabel.setText("编辑聊天记录");
        editLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        editLabel.setTextColor(textColor);
        editLabels.addView(editLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView editDesc = new TextView(act);
        editDesc.setText("长按可修改用户输入、模型回答和思考内容；没有思考链时可新增，"
                + "并可自定义思考用时（改后重启 DeepSeek 生效）。");
        editDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        editDesc.setTextColor(subColor);
        LinearLayout.LayoutParams edlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        edlp.topMargin = dp(act, 4);
        editLabels.addView(editDesc, edlp);
        editRow.addView(editLabels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView editArrow = new TextView(act);
        editArrow.setText("\u203A");
        editArrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        editArrow.setTextColor(subColor);
        editRow.addView(editArrow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        editRow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { ChatEditorUi.show(act); }
        });
        card.addView(editRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── Section 6b: 多账号管理（切换/添加账号）──────────────────────
        card.addView(makeDivider(act, divColor));
        card.addView(toolActionRow(act, "多账号管理",
                "添加、切换和移除账号；可严格验真导入 JSON，也可勾选账号导出明文凭证。",
                textColor, subColor, new View.OnClickListener() {
                    public void onClick(View v) { AccountUi.show(act); }
                }));

        // ── Section 7: 聊天数据工具箱（导出/搜索/统计/复制/备份）──────────
        card.addView(makeDivider(act, divColor));
        card.addView(toolActionRow(act, "导出会话为 Markdown",
                "把全部本地会话导出成 .md 文件到应用外部目录，可用文件管理器查看/分享。",
                textColor, subColor, new View.OnClickListener() {
                    public void onClick(View v) { DeekseepTools.exportAll(act); }
                }));

        card.addView(makeDivider(act, divColor));
        card.addView(toolActionRow(act, "全局搜索聊天记录",
                "检索用户输入、模型回答和深度思考内容，点击进入原生会话。",
                textColor, subColor, new View.OnClickListener() {
                    public void onClick(View v) { ChatSearchUi.show(act); }
                }));

        card.addView(makeDivider(act, divColor));
        card.addView(toolActionRow(act, "会话数据统计",
                "统计本地会话数、消息数、总字数，并按账号分组。",
                textColor, subColor, new View.OnClickListener() {
                    public void onClick(View v) { DeekseepTools.showStats(act); }
                }));

        card.addView(makeDivider(act, divColor));
        card.addView(toolActionRow(act, "立即备份聊天数据库",
                "把全部 deepseek_chat 数据库复制到应用外部目录，重装前手动留底。",
                textColor, subColor, new View.OnClickListener() {
                    public void onClick(View v) { DeekseepTools.backupNow(act); }
                }));

        // ── Section 8: 自动备份开关 ─────────────────────────────────────
        card.addView(makeDivider(act, divColor));
        LinearLayout bkRow = new LinearLayout(act);
        bkRow.setOrientation(LinearLayout.HORIZONTAL);
        bkRow.setGravity(Gravity.CENTER_VERTICAL);
        bkRow.setPadding(dp(act, 16), dp(act, 14), dp(act, 12), dp(act, 14));
        LinearLayout bkLabels = new LinearLayout(act);
        bkLabels.setOrientation(LinearLayout.VERTICAL);
        TextView bkLabel = new TextView(act);
        bkLabel.setText("自动备份聊天数据库");
        bkLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        bkLabel.setTypeface(Typeface.DEFAULT_BOLD);
        bkLabel.setTextColor(textColor);
        bkLabels.addView(bkLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView bkDesc = new TextView(act);
        bkDesc.setText("开启后，每次启动 DeepSeek 若距上次备份超过 24 小时，自动把数据库复制到"
                + "应用内部目录（仅保留最近 5 份）。");
        bkDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        bkDesc.setTextColor(subColor);
        LinearLayout.LayoutParams bkdlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bkdlp.topMargin = dp(act, 4);
        bkLabels.addView(bkDesc, bkdlp);
        LinearLayout.LayoutParams bkllp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bkllp.rightMargin = dp(act, 12);
        bkRow.addView(bkLabels, bkllp);
        Switch bkSw = new Switch(act);
        bkSw.setChecked(Main.isAutoBackup());
        bkSw.setThumbTintList(new android.content.res.ColorStateList(ss,
                new int[]{BRAND, dark ? 0xFFCCCCCC : 0xFFFFFFFF}));
        bkSw.setTrackTintList(new android.content.res.ColorStateList(ss,
                new int[]{0xFFADBFFF, dark ? 0xFF555555 : 0xFFBFBFBF}));
        bkSw.setBackground(null);
        bkSw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                Main.setAutoBackup(checked);
            }
        });
        bkRow.addView(bkSw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(bkRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── Section 9: language ──────────────────────────────────────────
        card.addView(makeDivider(act, divColor));
        card.addView(toolActionRow(act,
                UiLanguage.text(act, "语言", "Language"),
                UiLanguage.effectiveSummary(act), textColor, subColor,
                new View.OnClickListener() {
                    public void onClick(View v) {
                        showLanguagePicker(act, new Runnable() {
                            @Override public void run() {
                                try { dlg.dismiss(); } catch (Throwable ignored) {}
                                showPage(act);
                            }
                        });
                    }
                }));

        // ── Section 10: 实验性功能 ───────────────────────────────────────
        card.addView(makeDivider(act, divColor));
        card.addView(toolActionRow(act, "实验性功能",
                "专家模式图片中继、本地 API 服务及其独立帮助；首次进入需确认风险说明。",
                textColor, subColor, new View.OnClickListener() {
                    public void onClick(View v) { showExperimentalEntry(act); }
                }));

        // ── Section 11: 帮助与问题（折叠手风琴）───────────────────────────
        card.addView(makeDivider(act, divColor));
        addHelpSection(act, card, textColor, subColor, divColor, dark);

        // 页面最底部固定显示实际构建与宿主版本，便于截图定位兼容性问题。
        card.addView(makeDivider(act, divColor));
        addBuildFooter(act, card, subColor);

        UiLanguage.localizeTree(act, root);
        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bgColor));
        }
        // 仿 DeepSeek 子页面：从右向左滑入，而非直接覆盖
        openWithSlide(dlg, root);
        dlg.setOnKeyListener(new Dialog.OnKeyListener() {
            public boolean onKey(android.content.DialogInterface d, int code, android.view.KeyEvent e) {
                if (code == android.view.KeyEvent.KEYCODE_BACK
                        && e.getAction() == android.view.KeyEvent.ACTION_UP) {
                    slideOutAndDismiss(dlg, root);
                    return true;
                }
                return false;
            }
        });
    }

    /** 从右向左滑入（仿 DeepSeek 子页面转场），dlg 须已 setContentView。 */
    static void openWithSlide(Dialog dlg, View root) {
        int w = root.getResources().getDisplayMetrics().widthPixels;
        root.setTranslationX(w);
        dlg.show();
        root.animate().translationX(0).setDuration(260)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    /** 向右滑出后 dismiss。 */
    static void slideOutAndDismiss(final Dialog dlg, final View root) {
        int w = root.getWidth() > 0 ? root.getWidth()
                : root.getResources().getDisplayMetrics().widthPixels;
        root.animate().translationX(w).setDuration(220)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(new Runnable() {
                    public void run() { try { dlg.dismiss(); } catch (Throwable ignored) {} }
                }).start();
    }

    private static void showExperimentalEntry(final Activity act) {
        if (Main.hasAcceptedExperimentalDisclaimer()) {
            showExperimentalPage(act);
        } else {
            showExperimentalDisclaimer(act);
        }
    }

    /** First-entry gate for features that can affect accounts, data, files, and long-lived APIs. */
    private static void showExperimentalDisclaimer(final Activity act) {
        if (act == null || act.isFinishing()) return;
        final boolean dark = isDark(act);
        final int cardColor = dark ? 0xFF2A2A2D : 0xFFFFFFFF;
        final int textColor = dark ? 0xFFECECEC : 0xFF1A1A1A;
        final int subColor = dark ? 0xFFB5B5B9 : 0xFF666666;
        final Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act, 20), dp(act, 18), dp(act, 20), dp(act, 16));
        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(cardColor);
        rootBg.setCornerRadius(dp(act, 18));
        root.setBackground(rootBg);

        TextView title = new TextView(act);
        title.setText("实验性功能免责声明");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(textColor);
        root.addView(title);

        android.widget.ScrollView messageScroll = new android.widget.ScrollView(act) {
            @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(
                        dp(act, 470), View.MeasureSpec.AT_MOST));
            }
        };
        TextView message = new TextView(act);
        message.setText("以下功能会深度修改 DeepSeek 的模型能力、网络请求和后台运行方式，属于高风险的大工程，不保证稳定，也不保证在宿主升级后继续可用。\n\n"
                + "• 账号风险：修改客户端能力和以当前账号提供 API 可能触发服务条款或风控，严重时可能导致账号限制或封禁。\n"
                + "• 数据风险：实验性 Hook、图片中继、隐藏 API 会话和工具循环出现异常时，可能造成聊天记录、缓存或工作区文件损坏、覆盖或丢失。\n"
                + "• 隐私与执行风险：专家模式图片可能先交给视觉模型生成描述；本地 API 密钥一旦泄露，其他程序可使用当前账号发起请求。Agent 工具还可能创建、修改文件或执行命令。\n"
                + "• 使用限制：不要在重要账号上启用，也不要在保存有重要聊天记录或唯一数据副本的设备上测试。请先备份数据库和重要文件，并保留客户端的沙箱、确认和权限隔离。\n\n"
                + "功能可能随时失败、产生不完整结果或导致数据丢失。继续表示你已理解上述风险并自行承担后果。");
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        message.setTextColor(subColor);
        message.setLineSpacing(dp(act, 2), 1f);
        message.setPadding(0, dp(act, 12), 0, dp(act, 12));
        messageScroll.addView(message, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(messageScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(act);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        final TextView exit = popupButton(act, "退出", textColor,
                dark ? 0xFF38383C : 0xFFF0F1F4);
        final TextView confirm = popupButton(act, "确认（5）", 0xFFFFFFFF, BRAND);
        confirm.setEnabled(false);
        confirm.setAlpha(0.45f);
        LinearLayout.LayoutParams exitLp = new LinearLayout.LayoutParams(0, dp(act, 44), 1f);
        exitLp.rightMargin = dp(act, 10);
        buttons.addView(exit, exitLp);
        buttons.addView(confirm, new LinearLayout.LayoutParams(0, dp(act, 44), 1f));
        root.addView(buttons);

        exit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { dialog.dismiss(); } catch (Throwable ignored) {}
            }
        });
        confirm.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!confirm.isEnabled()) return;
                if (!Main.acceptExperimentalDisclaimer()) {
                    showCustomConfirm(act, "无法保存确认状态",
                            "DeepSeek 私有目录暂时不可写，因此没有进入实验性功能。请完整重启应用后重试。",
                            null, "知道了", true, null, null);
                    return;
                }
                try { dialog.dismiss(); } catch (Throwable ignored) {}
                showExperimentalPage(act);
            }
        });
        final int[] remaining = {5};
        final Runnable countdown = new Runnable() {
            @Override public void run() {
                if (!dialog.isShowing()) return;
                remaining[0]--;
                if (remaining[0] <= 0) {
                    confirm.setText(UiLanguage.text(act, "确认并进入", "Confirm and enter"));
                    confirm.setEnabled(true);
                    confirm.setAlpha(1f);
                } else {
                    confirm.setText(UiLanguage.text(act,
                            "确认（" + remaining[0] + "）",
                            "Confirm (" + remaining[0] + ")"));
                    confirm.postDelayed(this, 1000L);
                }
            }
        };

        UiLanguage.localizeTree(act, root);
        dialog.setContentView(root);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.dimAmount = 0.52f;
            window.setAttributes(attrs);
            int width = act.getResources().getDisplayMetrics().widthPixels - dp(act, 32);
            window.setLayout(Math.max(dp(act, 280), width),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        confirm.postDelayed(countdown, 1000L);
    }

    private static void showExperimentalPage(final Activity act) {
        final boolean dark = isDark(act);
        final int bgColor = dark ? 0xFF1B1B1D : 0xFFF5F6F8;
        final int barColor = dark ? 0xFF232326 : 0xFFFFFFFF;
        final int cardColor = dark ? 0xFF2A2A2D : 0xFFFFFFFF;
        final int textColor = dark ? 0xFFECECEC : 0xFF1A1A1A;
        final int subColor = dark ? 0xFFAAAAAF : 0xFF777B82;
        final int divColor = dark ? 0xFF3A3A3D : 0xFFEEEEEE;

        final LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bgColor);
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(barColor);
        int statusTop = statusBarHeight(act);
        bar.setPadding(dp(act, 8), statusTop, dp(act, 16), 0);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 56) + statusTop));
        final Dialog dialog = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        TextView back = new TextView(act);
        back.setText("\u2039");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        back.setTextColor(textColor);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(act, 8), 0, dp(act, 8), 0);
        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { slideOutAndDismiss(dialog, root); }
        });
        bar.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(act, 40)));
        TextView title = new TextView(act);
        title.setText("实验性功能");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(textColor);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = dp(act, 8);
        bar.addView(title, titleLp);

        android.widget.ScrollView scroll = new android.widget.ScrollView(act);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(cardColor);
        cardBg.setCornerRadius(dp(act, 12));
        card.setBackground(cardBg);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(dp(act, 16), dp(act, 16), dp(act, 16), dp(act, 20));
        scroll.addView(card, cardLp);

        TextView warning = infoBox(act,
                "这些功能可能触发账号风控、造成聊天或文件数据丢失，也可能在 DeepSeek 更新后失效。请勿用于重要账号或保存重要聊天记录的设备。",
                dark ? 0xFFFFC98B : 0xFF8A4B08, dark);
        card.addView(warning, insetParams(act, 12, 12));
        card.addView(makeDivider(act, divColor));

        LinearLayout expertRow = new LinearLayout(act);
        expertRow.setOrientation(LinearLayout.HORIZONTAL);
        expertRow.setGravity(Gravity.CENTER_VERTICAL);
        expertRow.setPadding(dp(act, 16), dp(act, 14), dp(act, 12), dp(act, 14));
        LinearLayout expertLabels = new LinearLayout(act);
        expertLabels.setOrientation(LinearLayout.VERTICAL);
        TextView expertTitle = new TextView(act);
        expertTitle.setText("解锁专家模式与图片上传");
        expertTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        expertTitle.setTypeface(Typeface.DEFAULT_BOLD);
        expertTitle.setTextColor(textColor);
        expertLabels.addView(expertTitle);
        TextView expertDesc = new TextView(act);
        expertDesc.setText("点亮专家模式的思考、搜索和文件能力；图片会先由视觉模型识别，再把描述中继给专家模型。切换后需重进应用或重选模型。");
        expertDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        expertDesc.setTextColor(subColor);
        expertLabels.addView(expertDesc);
        LinearLayout.LayoutParams labelsLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelsLp.rightMargin = dp(act, 12);
        expertRow.addView(expertLabels, labelsLp);
        Switch expertSwitch = new Switch(act);
        int[][] states = new int[][]{new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_checked}};
        expertSwitch.setChecked(Main.isExpertUnlock());
        expertSwitch.setThumbTintList(new android.content.res.ColorStateList(states,
                new int[]{BRAND, dark ? 0xFFCCCCCC : 0xFFFFFFFF}));
        expertSwitch.setTrackTintList(new android.content.res.ColorStateList(states,
                new int[]{0xFFADBFFF, dark ? 0xFF555555 : 0xFFBFBFBF}));
        expertSwitch.setBackground(null);
        expertSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
                Main.setExpertUnlock(checked);
            }
        });
        expertRow.addView(expertSwitch);
        card.addView(expertRow);

        card.addView(makeDivider(act, divColor));
        card.addView(toolActionRow(act, "本地 API 服务",
                "配置 OpenAI / Anthropic 格式、后台保活、API Key、监听地址与请求统计。",
                textColor, subColor, new View.OnClickListener() {
                    @Override public void onClick(View v) { showLocalApiEntry(act); }
                }));
        card.addView(makeDivider(act, divColor));
        card.addView(toolActionRow(act, "帮助与问题",
                "仅包含专家模式图片中继和本地 API 的说明、风险与排障。",
                textColor, subColor, new View.OnClickListener() {
                    @Override public void onClick(View v) { showExperimentalHelpPage(act); }
                }));
        card.addView(makeDivider(act, divColor));
        addBuildFooter(act, card, subColor);

        UiLanguage.localizeTree(act, root);
        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bgColor));
        }
        openWithSlide(dialog, root);
        dialog.setOnKeyListener(new Dialog.OnKeyListener() {
            @Override public boolean onKey(android.content.DialogInterface d, int code,
                                           android.view.KeyEvent event) {
                if (code == android.view.KeyEvent.KEYCODE_BACK
                        && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                    slideOutAndDismiss(dialog, root);
                    return true;
                }
                return false;
            }
        });
    }

    private static void showLocalApiEntry(final Activity act) {
        if (Main.isLocalApiBackgroundApproved(act)) {
            showLocalApiPage(act);
            return;
        }
        showLocalApiBackgroundGate(act);
    }

    private static void showLocalApiBackgroundGate(final Activity act) {
        String message = "本地 API 运行在 DeepSeek 进程内。若系统限制后台活动，Termux、"
                + "Codex 或 Claude Code 在前台时，DeepSeek 的 SSE 和上游网络会被暂停或中断。\n\n"
                + Main.localApiBackgroundStatus(act)
                + "\n\n请在系统页面把 DeepSeek 的电池使用设为“不限制/允许高耗电”，并允许后台活动。"
                + "返回后模块会自动复检；只有两项都通过才会启动监听。启用 API 后还会启动一个"
                + "前台保活任务，专门防止 Android Cached Apps Freezer 冻结监听和 SSE。";
        showCustomConfirm(act, "先允许 DeepSeek 后台运行", message,
                "打开电池设置", "校验并进入", false,
                new Runnable() {
                    @Override public void run() {
                        localApiSettingsFromPage = false;
                        if (!Main.openLocalApiBatterySettings(act)) {
                            showCustomConfirm(act, "无法打开设置",
                                    "系统没有可用的电池设置入口。请手动进入：设置 → 应用 → DeepSeek → 电池 → 不限制，然后重新点本功能。",
                                    null, "知道了", true, null, null);
                        }
                    }
                },
                new Runnable() {
                    @Override public void run() {
                        if (Main.verifyLocalApiBackground(act)) showLocalApiPage(act);
                        else showLocalApiBackgroundGate(act);
                    }
                });
    }

    static void handleLocalApiBatterySettingsResult(final Activity act) {
        final boolean fromPage = localApiSettingsFromPage;
        localApiSettingsFromPage = false;
        boolean allowed = Main.verifyLocalApiBackground(act);
        if (fromPage && localApiPageDialog != null && localApiPageDialog.isShowing()) {
            if (!allowed) {
                showCustomConfirm(act, "后台权限仍未通过",
                        Main.localApiBackgroundStatus(act)
                                + "\n\n请确认电池使用为“不限制”，且没有关闭后台活动。",
                        "再次打开设置", "知道了", true,
                        new Runnable() {
                            @Override public void run() {
                                localApiSettingsFromPage = true;
                                Main.openLocalApiBatterySettings(act);
                            }
                        }, null);
            }
            return;
        }
        if (allowed) showLocalApiPage(act);
        else showLocalApiBackgroundGate(act);
    }

    /** 独立的本地 API 控制页：所有控件与反馈均由模块手工绘制。 */
    private static void showLocalApiPage(final Activity act) {
        final boolean dark = isDark(act);
        final int bgColor = dark ? 0xFF1B1B1D : 0xFFF5F6F8;
        final int barColor = dark ? 0xFF232326 : 0xFFFFFFFF;
        final int cardColor = dark ? 0xFF2A2A2D : 0xFFFFFFFF;
        final int textColor = dark ? 0xFFECECEC : 0xFF1A1A1A;
        final int subColor = dark ? 0xFFAAAAAF : 0xFF777B82;
        final int divColor = dark ? 0xFF3A3A3D : 0xFFEEEEEE;
        final int[][] switchStates = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_checked}
        };

        final LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bgColor);
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(barColor);
        int statusTop = statusBarHeight(act);
        bar.setPadding(dp(act, 8), statusTop, dp(act, 16), 0);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 56) + statusTop));

        final Dialog dialog = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        localApiPageDialog = dialog;
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface ignored) {
                if (localApiPageDialog == dialog) localApiPageDialog = null;
            }
        });
        TextView back = new TextView(act);
        back.setText("\u2039");
        back.setTextColor(textColor);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(act, 8), 0, dp(act, 8), 0);
        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { slideOutAndDismiss(dialog, root); }
        });
        bar.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(act, 40)));
        TextView title = new TextView(act);
        title.setText("DeepSeek 本地 API");
        title.setTextColor(textColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = dp(act, 8);
        bar.addView(title, titleLp);

        android.widget.ScrollView scroll = new android.widget.ScrollView(act);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setColor(cardColor);
        cardBackground.setCornerRadius(dp(act, 12));
        card.setBackground(cardBackground);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(dp(act, 16), dp(act, 16), dp(act, 16), dp(act, 20));
        scroll.addView(card, cardLp);

        card.addView(sectionTitle(act, "后台运行校验", textColor));
        final TextView backgroundStatus = infoBox(act,
                Main.localApiBackgroundStatus(act), textColor, dark);
        card.addView(backgroundStatus, insetParams(act, 0, 6));
        final TextView keepAliveStatus = infoBox(act,
                Main.localApiKeepAliveStatus(), textColor, dark);
        card.addView(keepAliveStatus, insetParams(act, 0, 6));
        LinearLayout batteryActions = new LinearLayout(act);
        batteryActions.setOrientation(LinearLayout.HORIZONTAL);
        batteryActions.setPadding(dp(act, 16), dp(act, 6), dp(act, 16), dp(act, 12));
        final TextView openBattery = dialogAction(act, "打开电池设置", 0xFFE07A22, dark);
        batteryActions.addView(openBattery, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView verifyBattery = dialogAction(act, "重新校验", BRAND, dark);
        LinearLayout.LayoutParams verifyBatteryLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        verifyBatteryLp.leftMargin = dp(act, 8);
        batteryActions.addView(verifyBattery, verifyBatteryLp);
        card.addView(batteryActions);

        card.addView(makeDivider(act, divColor));

        LinearLayout enableRow = new LinearLayout(act);
        enableRow.setOrientation(LinearLayout.HORIZONTAL);
        enableRow.setGravity(Gravity.CENTER_VERTICAL);
        enableRow.setPadding(dp(act, 16), dp(act, 16), dp(act, 12), dp(act, 16));
        LinearLayout enableLabels = new LinearLayout(act);
        enableLabels.setOrientation(LinearLayout.VERTICAL);
        TextView enableTitle = new TextView(act);
        enableTitle.setText("启用本地 API 服务");
        enableTitle.setTextColor(textColor);
        enableTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        enableTitle.setTypeface(Typeface.DEFAULT_BOLD);
        enableLabels.addView(enableTitle);
        TextView enableDesc = new TextView(act);
        enableDesc.setText("监听本机和局域网；局域网调用同样必须携带 API Key。启用时前台保活会防止后台冻结；彻底退出 DeepSeek 后监听会停止，关闭时会清理复用的服务端会话。");
        enableDesc.setTextColor(subColor);
        enableDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        enableLabels.addView(enableDesc);
        enableRow.addView(enableLabels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final Switch serviceSwitch = new Switch(act);
        serviceSwitch.setChecked(Main.isLocalApiEnabled());
        serviceSwitch.setThumbTintList(new android.content.res.ColorStateList(switchStates,
                new int[]{BRAND, dark ? 0xFFCCCCCC : 0xFFFFFFFF}));
        serviceSwitch.setTrackTintList(new android.content.res.ColorStateList(switchStates,
                new int[]{0xFFADBFFF, dark ? 0xFF555555 : 0xFFBFBFBF}));
        serviceSwitch.setBackground(null);
        enableRow.addView(serviceSwitch);
        card.addView(enableRow);

        card.addView(makeDivider(act, divColor));
        LinearLayout protocolRow = new LinearLayout(act);
        protocolRow.setOrientation(LinearLayout.HORIZONTAL);
        protocolRow.setGravity(Gravity.CENTER_VERTICAL);
        protocolRow.setPadding(dp(act, 16), dp(act, 14), dp(act, 12), dp(act, 12));
        TextView protocolLabel = new TextView(act);
        protocolLabel.setText("格式");
        protocolLabel.setTextColor(textColor);
        protocolLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        protocolLabel.setTypeface(Typeface.DEFAULT_BOLD);
        protocolRow.addView(protocolLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView protocolValue = new TextView(act);
        protocolValue.setText(localApiProtocolDisplayName() + "  \u203A");
        protocolValue.setTextColor(BRAND);
        protocolValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        protocolValue.setTypeface(Typeface.DEFAULT_BOLD);
        protocolValue.setGravity(Gravity.CENTER);
        protocolValue.setPadding(dp(act, 12), dp(act, 9), dp(act, 10), dp(act, 9));
        GradientDrawable protocolValueBg = new GradientDrawable();
        protocolValueBg.setCornerRadius(dp(act, 10));
        protocolValueBg.setColor(dark ? 0xFF303039 : 0xFFF0F3FF);
        protocolValue.setBackground(protocolValueBg);
        protocolValue.setClickable(true);
        protocolRow.addView(protocolValue);
        card.addView(protocolRow);
        final TextView protocolDescription = infoBox(act,
                localApiProtocolDescription(), textColor, dark);
        card.addView(protocolDescription, insetParams(act, 0, 12));

        card.addView(makeDivider(act, divColor));
        TextView configTitle = sectionTitle(act, "连接配置", textColor);
        card.addView(configTitle);
        final TextView connectionInfo = infoBox(act, Main.localApiConnectionInfo(),
                textColor, dark);
        card.addView(connectionInfo, insetParams(act, 0, 12));

        LinearLayout copyActions = new LinearLayout(act);
        copyActions.setOrientation(LinearLayout.HORIZONTAL);
        copyActions.setPadding(dp(act, 16), dp(act, 10), dp(act, 16), dp(act, 4));
        final TextView copyUrl = dialogAction(act, "一键复制 URL", BRAND, dark);
        copyActions.addView(copyUrl, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView copyKey = dialogAction(act, "一键复制 API Key", BRAND, dark);
        LinearLayout.LayoutParams copyKeyLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        copyKeyLp.leftMargin = dp(act, 8);
        copyActions.addView(copyKey, copyKeyLp);
        card.addView(copyActions);

        TextView customTitle = sectionTitle(act, "自定义 API Key", textColor);
        customTitle.setPadding(dp(act, 16), dp(act, 14), dp(act, 16), dp(act, 6));
        card.addView(customTitle);
        final android.widget.EditText customKey = new android.widget.EditText(act);
        customKey.setSingleLine(true);
        customKey.setText(Main.localApiKey());
        customKey.setTextColor(textColor);
        customKey.setHintTextColor(subColor);
        customKey.setHint("8-256 位无空格 ASCII 字符");
        customKey.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        customKey.setPadding(dp(act, 12), dp(act, 10), dp(act, 12), dp(act, 10));
        GradientDrawable editBg = new GradientDrawable();
        editBg.setColor(dark ? 0xFF202024 : 0xFFF4F6FA);
        editBg.setCornerRadius(dp(act, 10));
        editBg.setStroke(dp(act, 1), dark ? 0xFF48484E : 0xFFD8DCE5);
        customKey.setBackground(editBg);
        card.addView(customKey, insetParams(act, 0, 8));
        LinearLayout keyActions = new LinearLayout(act);
        keyActions.setOrientation(LinearLayout.HORIZONTAL);
        keyActions.setPadding(dp(act, 16), dp(act, 6), dp(act, 16), dp(act, 14));
        final TextView saveKey = dialogAction(act, "保存自定义 Key", BRAND, dark);
        keyActions.addView(saveKey, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView randomKey = dialogAction(act, "生成随机 Key", 0xFFE07A22, dark);
        LinearLayout.LayoutParams randomLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        randomLp.leftMargin = dp(act, 8);
        keyActions.addView(randomKey, randomLp);
        card.addView(keyActions);

        card.addView(makeDivider(act, divColor));
        card.addView(sectionTitle(act, "Agent / Codex / Claude Code 兼容", textColor));
        final TextView agentHelp = infoBox(act, localApiAgentHelp(),
                textColor, dark);
        card.addView(agentHelp, insetParams(act, 0, 14));

        card.addView(makeDivider(act, divColor));
        card.addView(sectionTitle(act, "深度思考参数", textColor));
        TextView reasoningHelp = infoBox(act,
                "默认关闭。请求中附加任一参数即可让原生请求设置 thinking_enabled=true：\n"
                + "• \"thinking_enabled\": true\n"
                + "• \"deep_think\": true\n"
                + "• \"enable_thinking\": true\n"
                + "• \"thinking\": true 或 {\"type\":\"enabled\"}\n"
                + "• \"reasoning_effort\": \"medium\"\n"
                + "Responses 也支持 \"reasoning\": {\"effort\":\"medium\"}。"
                + "模型使用 deepseek-reasoner 时会自动开启；不附加且使用 deepseek-chat 时保持关闭。",
                textColor, dark);
        card.addView(reasoningHelp, insetParams(act, 0, 14));

        card.addView(makeDivider(act, divColor));
        card.addView(sectionTitle(act, "实时监听与请求统计", textColor));
        final TextView runtime = infoBox(act, Main.localApiRuntimeStatus(), textColor, dark);
        card.addView(runtime, insetParams(act, 0, 16));

        final TextView feedback = new TextView(act);
        feedback.setTextColor(BRAND);
        feedback.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        feedback.setGravity(Gravity.CENTER);
        feedback.setPadding(dp(act, 16), dp(act, 4), dp(act, 16), dp(act, 14));
        card.addView(feedback);

        final Runnable refresh = new Runnable() {
            @Override public void run() {
                if (!dialog.isShowing()) return;
                backgroundStatus.setText(UiLanguage.dynamic(act, Main.localApiBackgroundStatus(act)));
                keepAliveStatus.setText(UiLanguage.dynamic(act, Main.localApiKeepAliveStatus()));
                connectionInfo.setText(UiLanguage.dynamic(act, Main.localApiConnectionInfo()));
                runtime.setText(UiLanguage.dynamic(act, Main.localApiRuntimeStatus()));
                runtime.postDelayed(this, 1000L);
            }
        };
        serviceSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                boolean applied = Main.setLocalApiEnabled(checked);
                if (checked && !applied) {
                    serviceSwitch.setChecked(false);
                    feedback.setText(UiLanguage.dynamic(act, "后台运行校验未通过，监听未启动"));
                } else {
                    feedback.setText(UiLanguage.dynamic(act, checked ? "正在启动监听…"
                            : "服务已关闭，正在清理复用会话…"));
                }
                connectionInfo.setText(UiLanguage.dynamic(act, Main.localApiConnectionInfo()));
                runtime.setText(UiLanguage.dynamic(act, Main.localApiRuntimeStatus()));
                keepAliveStatus.setText(UiLanguage.dynamic(act, Main.localApiKeepAliveStatus()));
            }
        });
        openBattery.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                localApiSettingsFromPage = true;
                if (!Main.openLocalApiBatterySettings(act)) {
                    localApiSettingsFromPage = false;
                    feedback.setText(UiLanguage.dynamic(act,
                            "无法打开系统电池设置，请从系统应用设置手动进入"));
                }
            }
        });
        verifyBattery.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean allowed = Main.verifyLocalApiBackground(act);
                backgroundStatus.setText(UiLanguage.dynamic(act, Main.localApiBackgroundStatus(act)));
                feedback.setText(UiLanguage.dynamic(act, allowed
                        ? "后台运行校验通过" : "校验未通过，请设为不限制后台活动"));
                connectionInfo.setText(UiLanguage.dynamic(act, Main.localApiConnectionInfo()));
                runtime.setText(UiLanguage.dynamic(act, Main.localApiRuntimeStatus()));
                keepAliveStatus.setText(UiLanguage.dynamic(act, Main.localApiKeepAliveStatus()));
            }
        });
        protocolValue.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showProtocolPicker(act, new Runnable() {
                    @Override public void run() {
                        protocolValue.setText(localApiProtocolDisplayName() + "  \u203A");
                        protocolDescription.setText(UiLanguage.dynamic(act, localApiProtocolDescription()));
                        agentHelp.setText(UiLanguage.dynamic(act, localApiAgentHelp()));
                        connectionInfo.setText(UiLanguage.dynamic(act, Main.localApiConnectionInfo()));
                        runtime.setText(UiLanguage.dynamic(act, Main.localApiRuntimeStatus()));
                        feedback.setText(UiLanguage.dynamic(act,
                                "已切换为 " + localApiProtocolDisplayName() + " 格式"));
                    }
                });
            }
        });
        copyUrl.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                copyText(act, "API URL", Main.localApiEndpoint());
                feedback.setText(UiLanguage.dynamic(act, "URL 已复制"));
            }
        });
        copyKey.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                copyText(act, "API Key", Main.localApiKey());
                feedback.setText(UiLanguage.dynamic(act, "API Key 已复制"));
            }
        });
        saveKey.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String result = Main.setCustomLocalApiKey(act, customKey.getText().toString());
                feedback.setText(UiLanguage.dynamic(act, result));
                connectionInfo.setText(UiLanguage.dynamic(act, Main.localApiConnectionInfo()));
            }
        });
        randomKey.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Main.rotateLocalApiKey(act);
                customKey.setText(Main.localApiKey());
                connectionInfo.setText(UiLanguage.dynamic(act, Main.localApiConnectionInfo()));
                feedback.setText(UiLanguage.dynamic(act, "已生成并启用新的随机 Key"));
            }
        });

        UiLanguage.localizeTree(act, root);
        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bgColor));
        }
        openWithSlide(dialog, root);
        runtime.post(refresh);
        dialog.setOnKeyListener(new Dialog.OnKeyListener() {
            public boolean onKey(android.content.DialogInterface d, int code, android.view.KeyEvent e) {
                if (code == android.view.KeyEvent.KEYCODE_BACK
                        && e.getAction() == android.view.KeyEvent.ACTION_UP) {
                    slideOutAndDismiss(dialog, root);
                    return true;
                }
                return false;
            }
        });
    }

    private static String localApiProtocolDescription() {
        if (LocalApiGateway.PROTOCOL_ANTHROPIC.equals(Main.localApiProtocol())) {
            return "Anthropic 格式已选中。base URL 使用页面显示的本机或局域网根地址（不附加 /v1），"
                    + "提供 POST /v1/messages 与 /v1/messages/count_tokens；支持普通 JSON、"
                    + "SSE、tool_use / tool_result 和 thinking 参数。OpenAI 路由在此模式下会明确返回协议不匹配。";
        }
        return "OpenAI 格式已选中。base URL 以 /v1 结尾，提供 /models、"
                + "/chat/completions 与 /responses；支持普通 JSON、SSE 和 Agent 工具循环。"
                + "Anthropic 路由在此模式下会明确返回协议不匹配。";
    }

    private static String localApiAgentHelp() {
        if (LocalApiGateway.PROTOCOL_ANTHROPIC.equals(Main.localApiProtocol())) {
            return "Anthropic Messages API 已启用：\n"
                    + "• Claude Code：ANTHROPIC_BASE_URL 设为上方地址，ANTHROPIC_AUTH_TOKEN 设为上方 Key\n"
                    + "• 支持 message_start / content_block_* / message_delta / message_stop SSE\n"
                    + "• 支持客户端 tools、tool_use、tool_result、并行工具选择与重复副作用抑制\n"
                    + "• thinking={\"type\":\"enabled\"} 或 adaptive 会打开 DeepSeek 深度思考\n"
                    + "• 每 5 秒发送 ping、累计 token，并在正文开始前恢复 thinking 状态\n"
                    + "模型名可使用 deepseek-chat；Claude / sonnet / opus / haiku 名称会作为兼容别名映射到 DeepSeek 默认模型。";
        }
        return "OpenAI Chat Completions 与 Responses API 已启用：\n"
                + "• Chat：function tools / tool_calls / tool 结果回传\n"
                + "• Responses：function、custom、shell、apply_patch 与 previous_response_id\n"
                + "• 成功工具按名称与规范化参数去重，避免 Agent 重复执行副作用\n"
                + "• 支持 chunked 请求体、stream_options.include_usage 与 5 秒 SSE 心跳\n"
                + "Codex 自定义提供商请把 base_url 设为上方地址、wire API 设为 responses。"
                + "普通对话可用 deepseek-chat；需要 Codex 完整内建工具目录时可用兼容别名 gpt-5.4。";
    }

    private static String localApiProtocolDisplayName() {
        return LocalApiGateway.PROTOCOL_ANTHROPIC.equals(Main.localApiProtocol())
                ? "Anthropic" : "OpenAI";
    }

    private static TextView protocolPickerOption(Activity act, String title, String description,
                                                 boolean selected, boolean dark) {
        TextView option = new TextView(act);
        option.setText(UiLanguage.dynamic(act,
                title + (selected ? "   ✓" : "") + "\n" + description));
        option.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        option.setTextColor(dark ? 0xFFECECEC : 0xFF1A1A1A);
        option.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        option.setLineSpacing(dp(act, 2), 1f);
        option.setPadding(dp(act, 14), dp(act, 12), dp(act, 14), dp(act, 12));
        option.setClickable(true);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(act, 12));
        background.setColor(selected ? (dark ? 0xFF303B68 : 0xFFE9EDFF)
                : (dark ? 0xFF36363A : 0xFFF3F4F7));
        background.setStroke(dp(act, 1), selected ? BRAND
                : (dark ? 0xFF4A4A50 : 0xFFD8DCE5));
        option.setBackground(background);
        return option;
    }

    private static void showLanguagePicker(final Activity act, final Runnable onChanged) {
        if (act == null || act.isFinishing()) return;
        final boolean dark = isDark(act);
        final Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act, 18), dp(act, 16), dp(act, 18), dp(act, 18));
        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(dark ? 0xFF2A2A2D : 0xFFFFFFFF);
        rootBg.setCornerRadius(dp(act, 18));
        root.setBackground(rootBg);

        TextView title = new TextView(act);
        title.setText(UiLanguage.text(act, "选择 Deekseep 语言", "Choose Deekseep language"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(dark ? 0xFFECECEC : 0xFF1A1A1A);
        title.setPadding(0, 0, 0, dp(act, 12));
        root.addView(title);

        final String current = UiLanguage.currentMode(act);
        TextView automatic = protocolPickerOption(act,
                UiLanguage.text(act, "跟随 DeepSeek（自动）", "Follow DeepSeek (Auto)"),
                UiLanguage.text(act,
                        "DeepSeek 为中文时使用中文；其他任何语言使用英文。",
                        "Use Chinese when DeepSeek is Chinese; use English for every other language."),
                UiLanguage.MODE_AUTO.equals(current), dark);
        TextView chinese = protocolPickerOption(act, "Chinese",
                UiLanguage.text(act, "始终显示中文", "Always display Chinese"),
                UiLanguage.MODE_CHINESE.equals(current), dark);
        TextView english = protocolPickerOption(act, "English",
                UiLanguage.text(act, "始终显示英文", "Always display English"),
                UiLanguage.MODE_ENGLISH.equals(current), dark);
        root.addView(automatic, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams optionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        optionLp.topMargin = dp(act, 10);
        root.addView(chinese, optionLp);
        LinearLayout.LayoutParams englishLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        englishLp.topMargin = dp(act, 10);
        root.addView(english, englishLp);

        View.OnClickListener choose = new View.OnClickListener() {
            @Override public void onClick(View view) {
                String requested = view.getTag() instanceof String
                        ? (String) view.getTag() : UiLanguage.MODE_AUTO;
                if (!UiLanguage.setMode(act, requested)) {
                    showCustomConfirm(act,
                            UiLanguage.text(act, "语言设置保存失败", "Could not save language"),
                            UiLanguage.text(act,
                                    "DeepSeek 私有目录暂时不可写，请完整重启后重试。",
                                    "DeepSeek's private directory is temporarily unavailable. Fully restart the app and try again."),
                            null, UiLanguage.text(act, "知道了", "Got it"),
                            true, null, null);
                    return;
                }
                dialog.dismiss();
                if (onChanged != null) onChanged.run();
            }
        };
        automatic.setTag(UiLanguage.MODE_AUTO);
        chinese.setTag(UiLanguage.MODE_CHINESE);
        english.setTag(UiLanguage.MODE_ENGLISH);
        automatic.setOnClickListener(choose);
        chinese.setOnClickListener(choose);
        english.setOnClickListener(choose);

        UiLanguage.localizeTree(act, root);
        dialog.setContentView(root);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.dimAmount = 0.42f;
            window.setAttributes(attrs);
            int width = act.getResources().getDisplayMetrics().widthPixels - dp(act, 48);
            window.setLayout(Math.max(dp(act, 280), width),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static void showProtocolPicker(final Activity act, final Runnable onChanged) {
        if (act == null || act.isFinishing()) return;
        final boolean dark = isDark(act);
        final Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act, 18), dp(act, 16), dp(act, 18), dp(act, 18));
        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(dark ? 0xFF2A2A2D : 0xFFFFFFFF);
        rootBg.setCornerRadius(dp(act, 18));
        root.setBackground(rootBg);
        TextView title = new TextView(act);
        title.setText("选择 API 格式");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(dark ? 0xFFECECEC : 0xFF1A1A1A);
        title.setPadding(0, 0, 0, dp(act, 12));
        root.addView(title);
        boolean anthropic = LocalApiGateway.PROTOCOL_ANTHROPIC.equals(Main.localApiProtocol());
        TextView openAi = protocolPickerOption(act, "OpenAI",
                "Chat Completions、Responses 与 /v1/models", !anthropic, dark);
        TextView anthropicOption = protocolPickerOption(act, "Anthropic",
                "Messages、count_tokens 与 Claude Code", anthropic, dark);
        root.addView(openAi, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams anthropicLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        anthropicLp.topMargin = dp(act, 10);
        root.addView(anthropicOption, anthropicLp);
        openAi.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Main.setLocalApiProtocol(act, LocalApiGateway.PROTOCOL_OPENAI);
                dialog.dismiss();
                if (onChanged != null) onChanged.run();
            }
        });
        anthropicOption.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Main.setLocalApiProtocol(act, LocalApiGateway.PROTOCOL_ANTHROPIC);
                dialog.dismiss();
                if (onChanged != null) onChanged.run();
            }
        });
        UiLanguage.localizeTree(act, root);
        dialog.setContentView(root);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.dimAmount = 0.42f;
            window.setAttributes(attrs);
            int width = act.getResources().getDisplayMetrics().widthPixels - dp(act, 48);
            window.setLayout(Math.max(dp(act, 280), width),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static TextView sectionTitle(Context context, String value, int color) {
        TextView title = new TextView(context);
        title.setText(UiLanguage.dynamic(context, value));
        title.setTextColor(color);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 8));
        return title;
    }

    private static TextView infoBox(Context context, String value, int color, boolean dark) {
        TextView info = new TextView(context);
        info.setText(UiLanguage.dynamic(context, value));
        info.setTextColor(color);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        info.setTextIsSelectable(true);
        info.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(dark ? 0xFF202024 : 0xFFF4F6FA);
        background.setCornerRadius(dp(context, 10));
        info.setBackground(background);
        return info;
    }

    private static LinearLayout.LayoutParams insetParams(Context context, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(context, 16), dp(context, top), dp(context, 16), dp(context, bottom));
        return params;
    }

    private static void copyText(Context context, String label, String value) {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText(label, value == null ? "" : value));
    }

    /** 手工绘制的 API 连接面板，不使用 AlertDialog 或系统确认弹窗。 */
    private static void showLocalApiDialog(final Activity act, final TextView pageStatus) {
        final boolean dark = isDark(act);
        final int cardColor = dark ? 0xFF29292D : 0xFFFFFFFF;
        final int textColor = dark ? 0xFFF2F2F2 : 0xFF202124;
        final int subColor = dark ? 0xFFAAAAAF : 0xFF6F737A;
        final Dialog dialog = new Dialog(act, android.R.style.Theme_Translucent_NoTitleBar);

        FrameLayout root = new FrameLayout(act);
        root.setBackgroundColor(0x66000000);
        root.setPadding(dp(act, 20), dp(act, 24), dp(act, 20), dp(act, 24));
        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(act, 20), dp(act, 20), dp(act, 20), dp(act, 16));
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(cardColor);
        panelBg.setCornerRadius(dp(act, 16));
        panel.setBackground(panelBg);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        root.addView(panel, panelLp);

        TextView title = new TextView(act);
        title.setText("DeepSeek 本地 API");
        title.setTextColor(textColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        panel.addView(title);

        TextView warning = new TextView(act);
        warning.setText("服务绑定本机与局域网地址。API 密钥等同 DeepSeek 调用权限；仅在可信网络使用，"
                + "不要公开或转发。DeepSeek 被彻底退出后服务会随进程停止。");
        warning.setTextColor(subColor);
        warning.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams warningLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        warningLp.topMargin = dp(act, 8);
        panel.addView(warning, warningLp);

        final TextView info = new TextView(act);
        info.setText(UiLanguage.dynamic(act, Main.localApiConnectionInfo()));
        info.setTextColor(textColor);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        info.setTextIsSelectable(true);
        info.setPadding(dp(act, 12), dp(act, 12), dp(act, 12), dp(act, 12));
        GradientDrawable infoBg = new GradientDrawable();
        infoBg.setColor(dark ? 0xFF202024 : 0xFFF4F6FA);
        infoBg.setCornerRadius(dp(act, 10));
        info.setBackground(infoBg);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        infoLp.topMargin = dp(act, 14);
        panel.addView(info, infoLp);

        LinearLayout actions = new LinearLayout(act);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = dp(act, 16);
        panel.addView(actions, actionsLp);

        final TextView copy = dialogAction(act, "复制连接信息", BRAND, dark);
        copy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                            "Deekseep Local API", Main.localApiConnectionInfo()));
                    copy.setText(UiLanguage.dynamic(act, "已复制"));
                }
            }
        });
        actions.addView(copy);

        TextView rotate = dialogAction(act, "轮换密钥", 0xFFE07A22, dark);
        LinearLayout.LayoutParams rotateLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rotateLp.leftMargin = dp(act, 8);
        actions.addView(rotate, rotateLp);
        rotate.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                info.setText(UiLanguage.dynamic(act, Main.rotateLocalApiKey(act)));
                copy.setText(UiLanguage.dynamic(act, "复制连接信息"));
            }
        });

        TextView close = dialogAction(act, "关闭", subColor, dark);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.leftMargin = dp(act, 8);
        actions.addView(close, closeLp);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dialog.dismiss(); }
        });

        root.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dialog.dismiss(); }
        });
        panel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { /* consume backdrop click */ }
        });
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            public void onDismiss(android.content.DialogInterface d) {
                if (pageStatus != null) {
                    pageStatus.setText(UiLanguage.dynamic(act,
                            "监听本机与局域网地址，所有业务请求均需 API Key；支持非流式/SSE。"
                            + "点本行查看地址、密钥与连接方法。\n"
                            + Main.localApiConnectionInfo()));
                }
            }
        });
        UiLanguage.localizeTree(act, root);
        dialog.setContentView(root);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
        }
    }

    private static TextView dialogAction(Context context, String label, int color, boolean dark) {
        TextView button = new TextView(context);
        button.setText(UiLanguage.dynamic(context, label));
        button.setTextColor(color);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(context, 12), dp(context, 9), dp(context, 12), dp(context, 9));
        GradientDrawable background = new GradientDrawable();
        background.setColor(dark ? 0xFF38383D : 0xFFF0F2F7);
        background.setCornerRadius(dp(context, 30));
        button.setBackground(background);
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private static View makeDivider(Context c, int color) {
        View v = new View(c);
        v.setBackgroundColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(dp(c, 16), 0, dp(c, 16), 0);
        v.setLayoutParams(lp);
        return v;
    }

    /** 仿"编辑聊天记录"样式的可点击行（标题+说明+右箭头）。 */
    private static View toolActionRow(final Activity act, String title, String desc,
                                      int textColor, int subColor, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(act, 16), dp(act, 16), dp(act, 16), dp(act, 16));
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout labels = new LinearLayout(act);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(act);
        t.setText(UiLanguage.dynamic(act, title));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(textColor);
        labels.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView d = new TextView(act);
        d.setText(UiLanguage.dynamic(act, desc));
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        d.setTextColor(subColor);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(act, 4);
        labels.addView(d, dlp);
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = new TextView(act);
        arrow.setText("\u203A");
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        arrow.setTextColor(subColor);
        row.addView(arrow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(onClick);
        return row;
    }

    private static void showExperimentalHelpPage(final Activity act) {
        final String[][] items = {
            {"【功能】专家模式图片上传",
                "开启后，专家模式可选择相册图片。图片会先保存到 DeepSeek 私有目录，并由视觉模型生成客观描述，再把描述交给专家模型；同一会话后续轮会继续捕获图片上下文。视觉识别结果和服务器能力不作保证。"},
            {"【功能】本地 API 服务",
                "首次进入会校验 DeepSeek 已设为不限制电池优化且允许后台活动，未通过时不会启动监听。OpenAI 格式提供 /v1/models、/v1/chat/completions 和 /v1/responses；Anthropic 格式提供 /v1/messages 与 /v1/messages/count_tokens。两种格式均支持普通 JSON、SSE、深度思考和 Agent 工具结果回传。"},
            {"【问题】为什么专家模式第一轮能发图，后续轮却提示不支持？",
                "解决办法：新版会按会话捕获每一轮完整图片 fragment，并在发送点识别专家模型。安装后先冷启动，再新建专家会话测试；服务器若调整模型能力仍可能拒绝，此时可关闭功能改用普通视觉模型。"},
            {"【问题】为什么本地 API 返回 401、503 或连接被拒绝？",
                "解决办法：401 表示 Authorization: Bearer 后的密钥不匹配，可从控制页重新复制；503 表示原生传输或 PoW 尚未初始化，保持应用前台数秒后重试。连接被拒绝通常表示 DeepSeek 已被彻底退出、开关关闭或端口被占用；重新打开应用后查看控制页中的实际端口。"},
            {"【问题】为什么本地 API 遇到 429 后会等待一段时间？",
                "解决办法：这是原生上游限流。网关会串行发送并进行有限冷却；不要让客户端立即高频重试，客户端超时建议至少 180 秒。控制页和私有诊断日志会显示排队、限流与恢复原因。"},
            {"【问题】为什么 Codex 能聊天但没有完整 apply_patch 工具？",
                "解决办法：自定义 provider 的 wire_api 使用 responses；需要 Codex 完整内建工具目录时把 model 设为 gpt-5.4。它只是兼容别名，实际仍调用本机默认模型。若工具已返回但 Codex 拒绝执行，请检查工作区、sandbox 和 approval 权限。"},
            {"【问题】为什么 API 调用没有出现在聊天列表？",
                "解决办法：这是预期行为。API 复用独立隐藏会话以降低会话创建限流，但侧栏、编辑器和云目录会过滤它们；关闭服务时才集中走原生删除链清理，不会用每次创建和删除污染正常聊天。"},
            {"【问题】Claude Code 的 /clear 或 /new 为什么看起来还在旧对话？",
                "解决办法：这两个命令由 Claude Code 本地处理，不会请求 /v1/messages；API 只能隔离命令成功后的下一次请求。新版会同时按 Claude 会话 UUID 和首条用户消息指纹隔离隐藏分支。若命令后旧内容仍显示，先确认只输入命令并单独按一次回车；某些粘贴/补全场景第一次回车只是确认候选。清屏后询问一个仅旧对话知道的随机词即可判断是否真的串上下文。"},
            {"【问题】为什么请求开始后不会立刻显示 thinking？",
                "解决办法：这是修正后的正常顺序。服务会先完成排队、PoW 和原生请求启动，再发送 Anthropic message_start 与 thinking；这样等待本地处理时不会伪装成模型已经开始思考。"},
            {"【风险】怎样降低账号、聊天和文件损失风险？",
                "不要使用重要账号或保存唯一聊天记录的设备；开启前备份数据库和工作区文件，不共享 API Key，保留 Agent 沙箱与操作确认。遇到宿主更新、异常重复工具调用或数据不同步时立即关闭实验性开关。"}
        };
        showHelpItemsPage(act, "实验性功能 · 帮助与问题",
                "这里只收录专家模式图片中继和本地 API 的说明。点一下条目展开。", items);
    }

    private static void showHelpItemsPage(final Activity act, String pageTitle,
                                          String hint, String[][] items) {
        final boolean dark = isDark(act);
        final int bgColor = dark ? 0xFF1B1B1D : 0xFFF5F6F8;
        final int barColor = dark ? 0xFF232326 : 0xFFFFFFFF;
        final int cardColor = dark ? 0xFF2A2A2D : 0xFFFFFFFF;
        final int textColor = dark ? 0xFFECECEC : 0xFF1A1A1A;
        final int subColor = dark ? 0xFF9A9A9E : 0xFF888888;
        final int divColor = dark ? 0xFF3A3A3D : 0xFFEEEEEE;
        final LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bgColor);
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(barColor);
        int statusTop = statusBarHeight(act);
        bar.setPadding(dp(act, 8), statusTop, dp(act, 16), 0);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 56) + statusTop));
        final Dialog dialog = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        TextView back = new TextView(act);
        back.setText("\u2039");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        back.setTextColor(textColor);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(act, 8), 0, dp(act, 8), 0);
        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { slideOutAndDismiss(dialog, root); }
        });
        bar.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(act, 40)));
        TextView title = new TextView(act);
        title.setText(UiLanguage.dynamic(act, pageTitle));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(textColor);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = dp(act, 8);
        bar.addView(title, titleLp);
        android.widget.ScrollView scroll = new android.widget.ScrollView(act);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(cardColor);
        cardBg.setCornerRadius(dp(act, 12));
        card.setBackground(cardBg);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(dp(act, 16), dp(act, 16), dp(act, 16), dp(act, 16));
        scroll.addView(card, cardLp);
        buildAccordionItems(act, card, textColor, subColor, divColor, hint, items);
        UiLanguage.localizeTree(act, root);
        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bgColor));
        }
        openWithSlide(dialog, root);
        dialog.setOnKeyListener(new Dialog.OnKeyListener() {
            @Override public boolean onKey(android.content.DialogInterface d, int code,
                                           android.view.KeyEvent event) {
                if (code == android.view.KeyEvent.KEYCODE_BACK
                        && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                    slideOutAndDismiss(dialog, root);
                    return true;
                }
                return false;
            }
        });
    }

    private static void buildAccordionItems(final Activity act, LinearLayout card,
                                            final int textColor, final int subColor,
                                            int divColor, String hint, final String[][] items) {
        TextView headerHint = new TextView(act);
        headerHint.setText(UiLanguage.dynamic(act, hint));
        headerHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        headerHint.setTextColor(subColor);
        headerHint.setPadding(dp(act, 16), dp(act, 14), dp(act, 16), dp(act, 8));
        card.addView(headerHint);
        final java.util.List<View> bodies = new java.util.ArrayList<View>();
        final java.util.List<TextView> arrows = new java.util.ArrayList<TextView>();
        for (int i = 0; i < items.length; i++) {
            if (i > 0) card.addView(makeDivider(act, divColor));
            LinearLayout titleRow = new LinearLayout(act);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            titleRow.setPadding(dp(act, 16), dp(act, 14), dp(act, 16), dp(act, 14));
            titleRow.setClickable(true);
            TextView itemTitle = new TextView(act);
            itemTitle.setText(UiLanguage.dynamic(act, items[i][0]));
            itemTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            itemTitle.setTextColor(textColor);
            titleRow.addView(itemTitle, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView arrow = new TextView(act);
            arrow.setText("\u203A");
            arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            arrow.setTextColor(subColor);
            titleRow.addView(arrow);
            card.addView(titleRow);
            TextView itemBody = new TextView(act);
            itemBody.setText(UiLanguage.dynamic(act, items[i][1]));
            itemBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            itemBody.setTextColor(subColor);
            itemBody.setLineSpacing(dp(act, 2), 1f);
            itemBody.setPadding(dp(act, 16), 0, dp(act, 16), dp(act, 14));
            itemBody.setVisibility(View.GONE);
            card.addView(itemBody);
            final int index = i;
            bodies.add(itemBody);
            arrows.add(arrow);
            titleRow.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    boolean open = bodies.get(index).getVisibility() != View.VISIBLE;
                    for (int j = 0; j < bodies.size(); j++) {
                        if (j != index && bodies.get(j).getVisibility() == View.VISIBLE) {
                            animateExpand(bodies.get(j), false);
                            arrows.get(j).animate().rotation(0f).setDuration(200).start();
                        }
                    }
                    animateExpand(bodies.get(index), open);
                    arrows.get(index).animate().rotation(open ? 90f : 0f)
                            .setDuration(200).start();
                }
            });
        }
    }

    /** 主页仅放一行"帮助与问题"入口；点后从右滑入二级页，标题在二级页里。 */
    private static void addHelpSection(final Activity act, LinearLayout card,
                                       final int textColor, final int subColor,
                                       int divColor, boolean dark) {
        card.addView(toolActionRow(act, "帮助与问题", "功能说明、常见提示与对应解决办法",
                textColor, subColor, new View.OnClickListener() {
            public void onClick(View v) { showHelpPage(act); }
        }));
    }

    private static void addBuildFooter(Activity act, LinearLayout card, int subColor) {
        TextView info = new TextView(act);
        info.setText(UiLanguage.dynamic(act, "模块版本：" + BuildInfo.MODULE_VERSION
                + "\nlibxposed API：" + BuildInfo.API_VERSION
                + "\n编译时间：" + BuildInfo.BUILD_DATE
                + "\nDeepSeek 版本：" + installedVersion(act, act.getPackageName())));
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        info.setTextColor(subColor);
        info.setGravity(Gravity.CENTER);
        info.setPadding(dp(act, 16), dp(act, 14), dp(act, 16), dp(act, 18));
        card.addView(info, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static String installedVersion(Context context, String packageName) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager()
                    .getPackageInfo(packageName, 0);
            long code = android.os.Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode() : info.versionCode;
            String name = info.versionName == null ? "未知" : info.versionName;
            return name + " (" + code + ")";
        } catch (Throwable t) {
            return "读取失败";
        }
    }

    /** 二级页：仿 DeepSeek 子页面从右向左滑入，内容为帮助手风琴。 */
    static void showHelpPage(final Activity act) {
        boolean dark = isDark(act);
        int bgColor   = dark ? 0xFF1B1B1D : 0xFFF5F6F8;
        int barColor  = dark ? 0xFF232326 : 0xFFFFFFFF;
        int cardColor = dark ? 0xFF2A2A2D : 0xFFFFFFFF;
        int textColor = dark ? 0xFFECECEC : 0xFF1A1A1A;
        int subColor  = dark ? 0xFF9A9A9E : 0xFF888888;
        int divColor  = dark ? 0xFF3A3A3D : 0xFFEEEEEE;

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bgColor);

        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(barColor);
        int barH = dp(act, 56);
        int statusTop = statusBarHeight(act);
        bar.setPadding(dp(act, 8), statusTop, dp(act, 16), 0);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barH + statusTop));

        final Dialog dlg = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);

        TextView back = new TextView(act);
        back.setText("\u2039");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        back.setTextColor(textColor);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(act, 8), 0, dp(act, 8), 0);
        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { slideOutAndDismiss(dlg, root); }
        });
        bar.addView(back, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(act, 40)));

        TextView title = new TextView(act);
        title.setText("帮助与问题");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(textColor);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(act, 8);
        bar.addView(title, tlp);

        android.widget.ScrollView scroll = new android.widget.ScrollView(act);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout hcard = new LinearLayout(act);
        hcard.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(cardColor);
        cardBg.setCornerRadius(dp(act, 12));
        hcard.setBackground(cardBg);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(dp(act, 16), dp(act, 16), dp(act, 16), dp(act, 16));
        scroll.addView(hcard, clp);

        buildHelpAccordion(act, hcard, textColor, subColor, divColor, dark);

        UiLanguage.localizeTree(act, root);
        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bgColor));
        }
        openWithSlide(dlg, root);
        dlg.setOnKeyListener(new Dialog.OnKeyListener() {
            public boolean onKey(android.content.DialogInterface d, int code, android.view.KeyEvent e) {
                if (code == android.view.KeyEvent.KEYCODE_BACK
                        && e.getAction() == android.view.KeyEvent.ACTION_UP) {
                    slideOutAndDismiss(dlg, root);
                    return true;
                }
                return false;
            }
        });
    }

    /** 帮助手风琴：点标题行，正文向下延展展开（高度动画）+ 箭头旋转；展开一条自动收起其它。 */
    private static void buildHelpAccordion(final Activity act, LinearLayout card,
                                           final int textColor, final int subColor,
                                           int divColor, boolean dark) {
        TextView headerHint = new TextView(act);
        headerHint.setText("包含最新功能说明与常见问题。点一下条目展开；问题条目下方均给出解决办法。");
        headerHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        headerHint.setTextColor(subColor);
        headerHint.setPadding(dp(act, 16), dp(act, 14), dp(act, 16), dp(act, 8));
        card.addView(headerHint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // {标题, 正文}
        final String[][] items = {
            {"【功能】语言自动检测与手动选择",
                "默认在每次 DeepSeek 启动或回到前台时读取宿主当前语言：中文使用中文，任何其他语言使用英文。"
                + "也可在 Deekseep 首页的“语言”中固定为 Chinese 或 English；选择“跟随 DeepSeek（自动）”可恢复自动检测。"},
            {"【功能】系统提示词注入",
                "选择完整的 TXT/MD 文本后开启开关，模块会在发送请求时把它作为系统指令附加到用户原文前。"
                + "在线历史、数据库写入和旧数据迁移会清理这段包装，因此正常聊天页只应显示用户真正输入的内容。"},
            {"【功能】回复保留（去安全审查替换）",
                "开启后会识别 CONTENT_FILTER 等替换事件，保留本机已经观察到的原始回复，并在冷启动同步时继续保护。"
                + "它不能改变服务器规则，也不能恢复开关启用前已经丢失的回答。"},
            {"【功能】聊天记录多选删除",
                "打开开关后，在 DeepSeek 左侧会话列表长按进入多选，勾选后走宿主原生删除事件，同时清理本地会话表、"
                + "消息表和 Deekseep 恢复副本。关闭开关会恢复宿主原来的长按菜单。"},
            {"【功能】编辑聊天记录",
                "每次打开都会重新合并当前账号数据库、宿主已加载会话和最新内存历史。可修改标题、用户消息、AI 回复、"
                + "思考内容和思考用时；图片选择会立即保存，不必再点顶部保存。"},
            {"【功能】新建对话与追加消息",
                "手机端点编辑器左上角菜单，再点“新建对话”即可建立空白会话；进入任意会话后，底部可直接追加用户消息"
                + "或 AI 回复。追加动作属于当前会话，不是只能添加首条消息。"},
            {"【功能】编辑器相册图片",
                "在用户消息的图片管理中点“从相册选择并上传”。模块把长期副本保存到 DeepSeek files 目录，并建立"
                + "FileProvider 可读镜像；重启或缓存被清理时会尝试从长期副本重建。AI 消息不能附加用户图片。"},
            {"【功能】多账号管理与凭证导入导出",
                "多账号页可添加、切换、移除账号，也可勾选单个或多个账号导出。导入会严格解析完整 JSON，逐个请求"
                + "DeepSeek 当前用户接口，只有外层和业务层都成功时才整包写入；响应若带账号 ID 还必须一致。导出 TXT 含明文 token，绝不能分享。"},
            {"【功能】解锁 Google 登录",
                "开启后，模块只把国内登录页隐藏的 DeepSeek 原生 Google 项插回登录方式列表，微信、手机号等国内入口仍保留。"
                + "点击后继续使用宿主 Credential Manager 获取 Google ID Token，并交给 DeepSeek 官方 Google 登录接口换票；模块不会读取或记录该 token。"},
            {"【功能】解锁微信与手机号登录",
                "这是独立于 Google 的一个联合开关。开启后会在海外登录方式列表中同时补回 DeepSeek 原生微信项和短信手机号项，"
                + "保留 Google、密码和注册等已有选项；模块不接管凭证，也不会伪造登录成功。"},
            {"【功能】Markdown、搜索与统计",
                "Markdown 工具把本地会话导出到应用外部目录；全局搜索覆盖用户输入、AI 回复和思考内容；"
                + "会话统计按账号汇总会话数、消息数和字数。外部导出文件需自行保护。"},
            {"【功能】手动与自动数据库备份",
                "“立即备份”会复制聊天数据库到应用外部目录。自动备份开启后按启动时间间隔保存到应用内部备份目录，"
                + "并限制保留数量。数据库仍可能在复制时变化，重要操作前建议退出聊天页后再手动备份。"},
            {"【功能】记录服务器返回（诊断）",
                "只在排查时开启。它会记录 SSE 事件和部分网络诊断信息，日志可能包含聊天内容或服务器错误；"
                + "问题确认后应立即关闭，并在分享日志前自行脱敏。"},
            {"【问题】为什么会提示“当前显示最新内存记录，等待 DeepSeek 落库后再编辑”？",
                "解决办法：这是为了避免把不完整的内存快照强行写进数据库。先回到原生会话页等待消息加载和落库，"
                + "再重新点选该会话或关闭后重开编辑器。仍提示时，保持网络可用并冷启动 DeepSeek 后再试。"},
            {"【问题】为什么会提示“完整在线历史仍在加载”？",
                "解决办法：当前只拿到增量消息，还没有完整基线，因此禁止追加或物化。先在原生界面打开该对话并等待加载完成，"
                + "然后回到编辑器重新选择；不要连续点击添加按钮。"},
            {"【问题】为什么编辑器提示“未找到聊天数据库”或“没有本地或已加载的对话”？",
                "解决办法：确认 DeepSeek 已登录正确账号，并在原生会话列表打开一次目标对话；随后重新进入编辑器。"
                + "刚切号时需要等待宿主建立该账号数据库。不要清除 DeepSeek 应用数据。"},
            {"【问题】为什么保存或添加时提示“在线历史刚刚更新，请重新打开后再试”？",
                "解决办法：保存前服务器同步了更新版本，模块为防止覆盖新消息而回滚了整个事务。重新点选对话，核对最新内容后"
                + "再编辑；本次失败不会只写一半。"},
            {"【问题】为什么新建对话短暂出现后消失，或点开提示“对话已删除”？",
                "解决办法：新版通过 sidecar 和原生列表并集保护编辑器本地会话，同时允许服务器新增会话进入。请确认安装的是"
                + "同一最新版模块并完整冷启动；旧版本创建的异常条目可在编辑器打开并重新保存一次。"},
            {"【问题】为什么点一次“新建对话”偶尔出现两个同名对话？",
                "解决办法：新版有点击防抖和在途锁。出现时先不要重复点击，关闭编辑器再打开确认；若仍为两个真实条目，"
                + "勾选多余的一条删除。安装新版后必须完整重启 DeepSeek，不能只覆盖安装后继续旧进程。"},
            {"【问题】为什么相册图片提示保存失败、写入失败或“对话已经切换”？",
                "解决办法：保持目标对话不变，确认系统文件选择器授予了读取权限，并重新选择图片。“对话已经切换”是防止异步"
                + "上传把图片写到错误会话；文件可能已保存，但聊天记录不会被误改。"},
            {"【问题】为什么旧图片提示“图片凭证刷新失败”？",
                "解决办法：旧服务器图片可能只有短期访问凭证。先在 DeepSeek 原生聊天页打开该图片并保持网络可用，再回编辑器重试。"
                + "从新版相册入口添加的图片使用本地长期副本，不依赖服务器长期凭证。"},
            {"【问题】为什么追加 AI 回复后，用户消息的附带图片消失？",
                "解决办法：新版在图片选择时立即保存 FILE fragment，追加 USER/AI 前还会再次保存未提交选择。若是旧版本产生的数据，"
                + "重新选择图片并等待“已持久保存并附加”提示后，再追加回复。"},
            {"【问题】为什么重启后打开带图片的对话变成空白？",
                "解决办法：新版会在启动早期恢复 sidecar、消息头和图片镜像。确认未清除 DeepSeek 私有 files 目录，并安装后完整冷启动。"
                + "若旧版本已经把消息表覆盖为空，模块无法凭空恢复没有备份的数据，可检查数据库备份。"},
            {"【问题】为什么重启后系统提示词出现在用户消息里？",
                "解决办法：新版会在在线历史、仓库写入和启动迁移三层清理。保持系统提示词文件不变，完整重启一次让迁移执行；"
                + "若数据库正被占用，下一次启动会继续重试。"},
            {"【问题】为什么原回复重启后又变成“这个问题我暂时无法回答”？",
                "解决办法：必须在回复第一次生成时已开启回复保留，模块才能记录原内容并保护冷启动同步。更新后完整重启；"
                + "已经只剩模板且没有本地原文副本的旧消息无法恢复。"},
            {"【问题】为什么多选删除显示已提交，但本地删除为 0 或重启后又出现？",
                "解决办法：最新版先发送宿主真实 h61 删除事件，再按账号数据库清理会话、消息表和恢复副本。确认当前账号正确并重新打开"
                + "编辑器刷新；若服务器删除失败，云端副本仍可能重新同步，应在网络恢复后从原生列表再删一次。"},
            {"【问题】为什么账号 JSON 导入失败或提示凭证无效？",
                "解决办法：只能导入完整 UTF-8 JSON；id、token、email、mobile_number、status、chat_status、id_profiles 和"
                + "need_birthday 字段及类型必须齐全。过期 token、外层/业务 code 非 0、网络失败或服务器返回的 ID 不一致都会整包拒绝。"
                + "请从仍正常登录的设备重新导出，不要手工拼 token。"},
            {"【问题】为什么导出账号时反复提示明文凭证风险？",
                "解决办法：这是有意的安全提示，不应关闭。导出文件等同于登录钥匙；只保存到你控制的位置，不通过聊天软件或网盘分享，"
                + "导入完成后删除文件。模块不会把 token 写进诊断日志。"},
            {"【问题】为什么添加或切换账号后必须重启 DeepSeek？",
                "解决办法：宿主会在进程启动时缓存 key_user_info 和账号仓库，运行中只改文件不能保证所有页面一致。模块只重启"
                + "DeepSeek 自己的进程，让宿主按新凭证冷启动；不会停止其他应用。"},
            {"【问题】为什么开启后仍看不到 Google 登录，或点击后提示不可用？",
                "解决办法：先在已登录状态开启“解锁 Google 登录”，再从多账号页添加账号并完整重启 DeepSeek。设备需要可用的"
                + "Google Play 服务和网络环境。模块只恢复客户端原生入口，不绕过 DeepSeek 服务器的地区、账号或风控判断；"
                + "若官方接口明确拒绝，请勿反复提交，关闭开关后改用手机号、微信等正常入口。"},
            {"【问题】为什么海外环境仍看不到微信或手机号登录？",
                "解决办法：开启“解锁微信与手机号登录”后完整重启 DeepSeek，再进入登录页；它不会随 Google 开关自动开启。"
                + "微信入口还需要设备安装可用的微信客户端，短信入口需要官方服务支持当前号码与地区。服务器拒绝时模块不会绕过。"},
            {"【问题】为什么模块启动页显示“待验证”，LSPosed 明明已经启用？",
                "解决办法：现代 libxposed 不再把模块注入模块应用自身，因此无需在作用域勾选 Deekseep。最新版通过官方 XposedService"
                + "连接判断模块启用，并由 DeepSeek 目标进程回报实际注入。请只确认模块总开关已开、作用域勾选 DeepSeek，然后启动一次"
                + "DeepSeek 再返回模块页；不要用旧版的“自我 Hook”状态作为判据。"},
            {"【问题】为什么搜索、统计或编辑器显示的账号不对？",
                "解决办法：先在多账号页确认“当前”标记，完成切号重启后再打开工具。编辑器默认只显示当前账号；若启用“显示所有账号”，"
                + "保存前必须核对顶部账号和目标数据库。"},
        };

        final java.util.List<View> bodies = new java.util.ArrayList<View>();
        final java.util.List<TextView> arrows = new java.util.ArrayList<TextView>();

        for (int i = 0; i < items.length; i++) {
            if (i > 0) card.addView(makeDivider(act, divColor));

            // 标题行
            LinearLayout titleRow = new LinearLayout(act);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            titleRow.setPadding(dp(act, 16), dp(act, 14), dp(act, 16), dp(act, 14));
            titleRow.setClickable(true);
            titleRow.setFocusable(true);

            TextView t = new TextView(act);
            t.setText(UiLanguage.dynamic(act, items[i][0]));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            t.setTextColor(textColor);
            titleRow.addView(t, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView arrow = new TextView(act);
            arrow.setText("\u203A"); // › 收起态指向右，展开时旋转 90° 指向下
            arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            arrow.setTextColor(subColor);
            titleRow.addView(arrow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.addView(titleRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // 正文（默认收起）
            TextView body = new TextView(act);
            body.setText(UiLanguage.dynamic(act, items[i][1]));
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            body.setTextColor(subColor);
            body.setLineSpacing(dp(act, 2), 1f);
            body.setPadding(dp(act, 16), 0, dp(act, 16), dp(act, 14));
            body.setVisibility(View.GONE);
            card.addView(body, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            final int idx = i;
            bodies.add(body);
            arrows.add(arrow);
            titleRow.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    boolean willOpen = bodies.get(idx).getVisibility() != View.VISIBLE;
                    for (int j = 0; j < bodies.size(); j++) {
                        if (j != idx && bodies.get(j).getVisibility() == View.VISIBLE) {
                            animateExpand(bodies.get(j), false);
                            arrows.get(j).animate().rotation(0f).setDuration(200).start();
                        }
                    }
                    animateExpand(bodies.get(idx), willOpen);
                    arrows.get(idx).animate().rotation(willOpen ? 90f : 0f).setDuration(200).start();
                }
            });
        }
    }

    /** 正文向下延展/收起：动画其 layoutParams.height，0 ↔ 测量高度。 */
    private static void animateExpand(final View body, final boolean open) {
        final ViewGroup.LayoutParams lp = body.getLayoutParams();
        int parentW = ((View) body.getParent()).getWidth();
        int wSpec = View.MeasureSpec.makeMeasureSpec(
                parentW > 0 ? parentW : 0,
                parentW > 0 ? View.MeasureSpec.EXACTLY : View.MeasureSpec.UNSPECIFIED);
        int hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        body.measure(wSpec, hSpec);
        final int target = body.getMeasuredHeight();

        int start = open ? 0 : (body.getHeight() > 0 ? body.getHeight() : target);
        int end   = open ? target : 0;

        if (open) {
            lp.height = 0;
            body.setLayoutParams(lp);
            body.setVisibility(View.VISIBLE);
        }

        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofInt(start, end);
        va.setDuration(220);
        va.setInterpolator(new android.view.animation.DecelerateInterpolator());
        va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(android.animation.ValueAnimator a) {
                lp.height = (Integer) a.getAnimatedValue();
                body.setLayoutParams(lp);
            }
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            public void onAnimationEnd(android.animation.Animator a) {
                if (open) {
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    body.setLayoutParams(lp);
                } else {
                    body.setVisibility(View.GONE);
                }
            }
        });
        va.start();
    }

    /**
     * 项目统一的自绘确认弹窗。内容、圆角和按钮均由普通 View 构建，不使用系统 AlertDialog。
     * negativeText 可为 null（只显示一个确认按钮）；cancelable=false 时只能点击显式按钮。
     */
    static Dialog showCustomConfirm(final Activity act, String titleText, String messageText,
                                    String negativeText, String positiveText, boolean cancelable,
                                    final Runnable onNegative, final Runnable onPositive) {
        if (act == null || act.isFinishing()) return null;
        final boolean dark = isDark(act);
        final int cardColor = dark ? 0xFF2A2A2D : 0xFFFFFFFF;
        final int textColor = dark ? 0xFFECECEC : 0xFF1A1A1A;
        final int subColor = dark ? 0xFFB5B5B9 : 0xFF666666;

        final Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(cancelable);
        dialog.setCanceledOnTouchOutside(cancelable);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(act, 20), dp(act, 18), dp(act, 20), dp(act, 16));
        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(cardColor);
        rootBg.setCornerRadius(dp(act, 18));
        root.setBackground(rootBg);

        TextView title = new TextView(act);
        title.setText(UiLanguage.dynamic(act, titleText == null ? "提示" : titleText));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(textColor);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        android.widget.ScrollView messageScroll = new android.widget.ScrollView(act) {
            @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(
                        dp(act, 430), View.MeasureSpec.AT_MOST));
            }
        };
        TextView message = new TextView(act);
        message.setText(UiLanguage.dynamic(act, messageText == null ? "" : messageText));
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        message.setTextColor(subColor);
        message.setLineSpacing(dp(act, 2), 1f);
        message.setPadding(0, dp(act, 12), 0, dp(act, 12));
        messageScroll.addView(message, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(messageScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(act);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

        if (negativeText != null) {
            TextView negative = popupButton(act, negativeText, textColor, dark ? 0xFF38383C : 0xFFF0F1F4);
            negative.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    try { dialog.dismiss(); } catch (Throwable ignored) {}
                    if (onNegative != null) onNegative.run();
                }
            });
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, dp(act, 44), 1f);
            nlp.rightMargin = dp(act, 10);
            buttons.addView(negative, nlp);
        }

        TextView positive = popupButton(act,
                positiveText == null ? "确定" : positiveText, 0xFFFFFFFF, BRAND);
        positive.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try { dialog.dismiss(); } catch (Throwable ignored) {}
                if (onPositive != null) onPositive.run();
            }
        });
        buttons.addView(positive, new LinearLayout.LayoutParams(0, dp(act, 44), 1f));
        root.addView(buttons, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        UiLanguage.localizeTree(act, root);
        dialog.setContentView(root);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.dimAmount = 0.48f;
            window.setAttributes(attrs);
            int width = act.getResources().getDisplayMetrics().widthPixels - dp(act, 32);
            window.setLayout(Math.max(dp(act, 280), width), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    private static TextView popupButton(Activity act, String label, int textColor, int bgColor) {
        TextView button = new TextView(act);
        button.setText(UiLanguage.dynamic(act, label));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(textColor);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(bgColor);
        background.setCornerRadius(dp(act, 12));
        button.setBackground(background);
        return button;
    }

    static int statusBarHeight(Context c) {
        int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) return c.getResources().getDimensionPixelSize(id);
        return dp(c, 28);
    }

    private DeekseepUi() {}
}

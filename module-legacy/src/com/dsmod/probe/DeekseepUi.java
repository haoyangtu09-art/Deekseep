package com.dsmod.probe;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** 纯原生 View 构建入口按钮与 Deekseep 子页面，不依赖宿主 compose。 */
public final class DeekseepUi {

    static final int BRAND = 0xFF4D6BFE;

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static boolean isDark(Context c) {
        return (c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
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

        // 主卡片
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(cardColor);
        cardBg.setCornerRadius(dp(act, 12));
        card.setBackground(cardBg);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(dp(act, 16), dp(act, 16), dp(act, 16), 0);
        root.addView(card, clp);

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
        editDesc.setText("打开仿 DeepSeek 的编辑器，长按任意消息即可修改你发的或模型回复的内容"
                + "（改后重启 DeepSeek 生效）。");
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

    private static View makeDivider(Context c, int color) {
        View v = new View(c);
        v.setBackgroundColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(dp(c, 16), 0, dp(c, 16), 0);
        v.setLayoutParams(lp);
        return v;
    }

    static int statusBarHeight(Context c) {
        int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) return c.getResources().getDimensionPixelSize(id);
        return dp(c, 28);
    }

    private DeekseepUi() {}
}

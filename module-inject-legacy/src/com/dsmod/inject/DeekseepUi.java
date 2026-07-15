package com.dsmod.inject;

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
import android.widget.FrameLayout;
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

        // ── Section 4b: 解锁专家模式限制 ────────────────────────────
        LinearLayout expRow = new LinearLayout(act);
        expRow.setOrientation(LinearLayout.HORIZONTAL);
        expRow.setGravity(Gravity.CENTER_VERTICAL);
        expRow.setPadding(dp(act, 16), dp(act, 14), dp(act, 12), dp(act, 14));

        LinearLayout expLabels = new LinearLayout(act);
        expLabels.setOrientation(LinearLayout.VERTICAL);

        TextView expLabel = new TextView(act);
        expLabel.setText("解锁专家模式限制");
        expLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        expLabel.setTypeface(Typeface.DEFAULT_BOLD);
        expLabel.setTextColor(textColor);
        expLabels.addView(expLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView expDesc = new TextView(act);
        expDesc.setText("开启后允许专家模式选择图片。发送时会先在后台提取图片描述，再以纯文本交给"
                + "专家模型；重新打开对话时保留本地图片附件。");
        expDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        expDesc.setTextColor(subColor);
        LinearLayout.LayoutParams edlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        edlp2.topMargin = dp(act, 4);
        expLabels.addView(expDesc, edlp2);

        LinearLayout.LayoutParams ellp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ellp.rightMargin = dp(act, 12);
        expRow.addView(expLabels, ellp);

        Switch expSw = new Switch(act);
        expSw.setChecked(Main.isExpertUnlock());
        expSw.setThumbTintList(new android.content.res.ColorStateList(ss,
                new int[]{BRAND, dark ? 0xFFCCCCCC : 0xFFFFFFFF}));
        expSw.setTrackTintList(new android.content.res.ColorStateList(ss,
                new int[]{0xFFADBFFF, dark ? 0xFF555555 : 0xFFBFBFBF}));
        expSw.setBackground(null);
        expSw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                Main.setExpertUnlock(checked);
            }
        });
        expRow.addView(expSw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(expRow, new LinearLayout.LayoutParams(
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

        // ── Section 7: 聊天数据工具箱（导出/搜索/统计/复制/备份）──────────
        card.addView(makeDivider(act, divColor));
        card.addView(toolActionRow(act, "导出会话为 Markdown",
                "把全部本地会话导出成 .md 文件到应用外部目录，可用文件管理器查看/分享。",
                textColor, subColor, new View.OnClickListener() {
                    public void onClick(View v) { DeekseepTools.exportAll(act); }
                }));

        card.addView(makeDivider(act, divColor));
        card.addView(toolActionRow(act, "全局搜索聊天记录",
                "输入关键词，跨全部本地会话检索命中的消息片段。",
                textColor, subColor, new View.OnClickListener() {
                    public void onClick(View v) { DeekseepTools.showSearch(act); }
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

        // ── Section 9: 帮助与问题（折叠手风琴）────────────────────────────
        card.addView(makeDivider(act, divColor));
        addHelpSection(act, card, textColor, subColor, divColor, dark);

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
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(textColor);
        labels.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView d = new TextView(act);
        d.setText(desc);
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

    /** 主页仅放一行"帮助与反馈"入口；点后从右滑入二级页，标题在二级页里。 */
    private static void addHelpSection(final Activity act, LinearLayout card,
                                       final int textColor, final int subColor,
                                       int divColor, boolean dark) {
        card.addView(toolActionRow(act, "帮助与反馈", "各功能的介绍与用法，点开查看",
                textColor, subColor, new View.OnClickListener() {
            public void onClick(View v) { showHelpPage(act); }
        }));
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
        title.setText("帮助与反馈");
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
        headerHint.setText("各功能的介绍与用法，点一下条目即可展开。");
        headerHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        headerHint.setTextColor(subColor);
        headerHint.setPadding(dp(act, 16), dp(act, 14), dp(act, 16), dp(act, 8));
        card.addView(headerHint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // {标题, 正文}
        final String[][] items = {
            {"导入提示词 / 系统提示词注入",
                "把一段自定义系统提示词注入到每次对话最前面，让模型全程按你的设定说话。\n\n"
                + "用法：点“导入提示词”选择一个文本文件（.txt/.md 等），选好后下方会显示文件路径；"
                + "再打开“系统提示词注入”开关即生效。想清空已选文件点“还原设置”。"},
            {"去他妈的安全审查",
                "回答生成完后，服务端有时会追加一帧，把整段内容替换成“这个问题我暂时无法回答”这类模板。\n\n"
                + "用法：打开开关后，模块会丢弃这帧带 CONTENT_FILTER 标记的替换帧，已经生成的正文原样留在屏幕上。"},
            {"聊天记录多选",
                "在左侧会话列表批量选择并删除本地会话，替代原生的单条重命名/删除菜单。\n\n"
                + "用法：打开开关后，长按左侧任意一条会话进入多选模式；点会话右侧空白区域即可勾选，"
                + "顶部显示已选数量与“删除”“取消”。收起侧栏会自动退出多选。关闭开关则恢复 DeepSeek 原生菜单。"},
            {"解锁专家模式限制",
                "开启后允许专家模式选择图片。图片会先由视觉模型在后台生成客观描述，再把纯文本交给专家模型。\n\n"
                + "视觉中继使用临时会话和独立请求凭证；在线重开对话时，文本保持服务器同步，图片附件保留本地版本。"},
            {"记录服务器返回（诊断）",
                "把服务器返回的每条 SSE 原始事件写进日志，用来排查内容为何被替换等问题。\n\n"
                + "用法：仅在需要排查时打开。日志位于 /data/data/com.deepseek.chat/files/deekseep_srv.log"
                + "（也会尽量在 /sdcard/deekseep_srv.log 留一份）。平时请保持关闭以免产生大量日志。"},
            {"编辑聊天记录",
                "直接修改你发出的或模型回复过的历史消息内容。\n\n"
                + "用法：点“编辑聊天记录”打开仿 DeepSeek 的编辑器，进入某个会话后长按任意一条消息即可编辑，"
                + "保存后重启 DeepSeek 生效。"},
            {"导出会话为 Markdown",
                "把全部本地会话导出成 .md 文件，方便备份、查看或分享。\n\n"
                + "用法：点该条目后，模块把每个会话写成一个 .md 文件到应用外部目录 "
                + "Android/data/com.deepseek.chat/files/deekseep/，用文件管理器即可打开。"},
            {"全局搜索聊天记录",
                "跨全部本地会话按关键词检索命中的消息片段，快速找到某句话在哪个会话。\n\n"
                + "用法：点该条目后输入关键词，结果按会话列出命中的片段，点结果可定位。"},
            {"会话数据统计",
                "统计本地会话总数、消息条数、总字数，并按账号分组，一眼看清数据规模。\n\n"
                + "用法：点该条目直接弹出统计结果。"},
            {"备份 / 自动备份聊天数据库",
                "把 DeepSeek 的聊天数据库整体复制留底，重装或清数据前先备份可避免丢失。\n\n"
                + "用法：点“立即备份聊天数据库”做一次手动备份到应用外部目录。打开“自动备份”开关后，"
                + "每次启动 DeepSeek 若距上次备份超过 24 小时会自动备份到应用内部目录，仅保留最近 5 份。"},
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
            t.setText(items[i][0]);
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
            body.setText(items[i][1]);
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

    static int statusBarHeight(Context c) {
        int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) return c.getResources().getDimensionPixelSize(id);
        return dp(c, 28);
    }

    private DeekseepUi() {}
}

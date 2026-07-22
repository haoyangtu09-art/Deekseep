package com.dsmod.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Searches user messages, assistant responses, and DeepSeek reasoning fragments. */
final class ChatSearchUi {
    private static final int MAX_HITS = 200;

    private ChatSearchUi() {}

    static final class Hit {
        String sid;
        String title;
        String role;
        String source;
        String snippet;
        int highlightStart;
        int highlightLength;
    }

    static void show(final Activity act) {
        final EditText input = new EditText(act);
        input.setHint(UiLanguage.text(act, "输入关键词", "Enter a keyword"));
        input.setSingleLine(true);
        LinearLayout wrap = new LinearLayout(act);
        int pad = dp(act, 16);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(act)
                .setTitle(UiLanguage.text(act, "搜索聊天记录", "Search chat history"))
                .setView(wrap)
                .setNegativeButton(UiLanguage.text(act, "取消", "Cancel"), null)
                .setPositiveButton(UiLanguage.text(act, "搜索", "Search"),
                        new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        String keyword = input.getText().toString().trim();
                        if (keyword.length() > 0) search(act, keyword);
                    }
                })
                .show();
    }

    private static void search(final Activity act, final String keyword) {
        UiLanguage.toast(act, "搜索中…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            public void run() {
                final List<Hit> hits = new ArrayList<Hit>();
                final String needle = keyword.toLowerCase(Locale.getDefault());
                for (File file : ChatEditorUi.allDbs()) {
                    if (hits.size() >= MAX_HITS) break;
                    SQLiteDatabase db = null;
                    Cursor cursor = null;
                    try {
                        db = SQLiteDatabase.openDatabase(
                                file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
                        cursor = db.rawQuery(
                                "SELECT id,title FROM chat_session_list ORDER BY updated_at DESC", null);
                        while (cursor.moveToNext() && hits.size() < MAX_HITS) {
                            String sid = cursor.getString(0);
                            String title = cursor.getString(1);
                            if (sid == null) continue;
                            for (ChatEditorUi.Msg message : ChatEditorUi.loadThread(db, sid)) {
                                String body = message.body;
                                if ("USER".equals(message.role)) {
                                    int prefixEnd = ChatEditorUi.sysPrefixEnd(body);
                                    if (prefixEnd > 0) body = body.substring(prefixEnd);
                                }
                                addMatch(hits, sid, title, message, body,
                                        "USER".equals(message.role) ? "用户输入" : "模型回答",
                                        keyword, needle);
                                if (hits.size() >= MAX_HITS) break;
                                addMatch(hits, sid, title, message, message.think,
                                        "深度思考", keyword, needle);
                                if (hits.size() >= MAX_HITS) break;
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (cursor != null) try { cursor.close(); } catch (Throwable ignored) {}
                        if (db != null) try { db.close(); } catch (Throwable ignored) {}
                    }
                }
                act.runOnUiThread(new Runnable() {
                    public void run() { showResults(act, keyword, hits); }
                });
            }
        }).start();
    }

    private static void addMatch(List<Hit> hits, String sid, String title,
                                 ChatEditorUi.Msg message, String value, String source,
                                 String keyword, String needle) {
        if (value == null || value.length() == 0 || hits.size() >= MAX_HITS) return;
        int index = value.toLowerCase(Locale.getDefault()).indexOf(needle);
        if (index < 0) return;
        int start = Math.max(0, index - 28);
        int end = Math.min(value.length(), index + keyword.length() + 48);
        String before = value.substring(start, index).replaceAll("\\s+", " ");
        String match = value.substring(index, Math.min(value.length(), index + keyword.length()))
                .replaceAll("\\s+", " ");
        String after = value.substring(Math.min(value.length(), index + keyword.length()), end)
                .replaceAll("\\s+", " ");
        Hit hit = new Hit();
        hit.sid = sid;
        hit.title = title == null || title.length() == 0
                ? UiLanguage.text("未命名对话", "Untitled chat") : title;
        hit.role = message.role;
        hit.source = source;
        hit.snippet = before + match + after;
        hit.highlightStart = before.length();
        hit.highlightLength = match.length();
        hits.add(hit);
    }

    private static void showResults(final Activity act, String keyword, final List<Hit> hits) {
        final boolean dark = DeekseepUi.isDark(act);
        final int panelColor = dark ? 0xFF2A2A2D : 0xFFFFFFFF;
        final int rowColor = dark ? 0xFF1F1F22 : 0xFFF6F7F9;
        final int textColor = dark ? 0xFFECECEC : 0xFF1A1A1A;
        final int subColor = dark ? 0xFF9A9A9E : 0xFF777777;
        final int highlightColor = dark ? 0x669B7A27 : 0x66FFE08A;

        final Dialog dialog = new Dialog(act);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(act, 16), dp(act, 16), dp(act, 16), dp(act, 12));
        GradientDrawable panelBackground = new GradientDrawable();
        panelBackground.setColor(panelColor);
        panelBackground.setCornerRadius(dp(act, 8));
        panel.setBackground(panelBackground);

        TextView heading = new TextView(act);
        heading.setText(hits.isEmpty()
                ? UiLanguage.text(act, "未找到「" + keyword + "」",
                        "No results for “" + keyword + "”")
                : UiLanguage.text(act, "「" + keyword + "」命中 " + hits.size() + " 条",
                        hits.size() + " results for “" + keyword + "”"));
        heading.setTextColor(textColor);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        heading.setPadding(dp(act, 4), 0, dp(act, 4), dp(act, 10));
        panel.addView(heading);

        ScrollView scroll = new ScrollView(act);
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        int maxHeight = (int) (act.getResources().getDisplayMetrics().heightPixels * 0.62f);
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                hits.size() > 6 ? maxHeight : ViewGroup.LayoutParams.WRAP_CONTENT));

        for (final Hit hit : hits) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(act, 12), dp(act, 10), dp(act, 12), dp(act, 10));
            GradientDrawable rowBackground = new GradientDrawable();
            rowBackground.setColor(rowColor);
            rowBackground.setCornerRadius(dp(act, 6));
            row.setBackground(rowBackground);
            row.setClickable(true);

            TextView meta = new TextView(act);
            meta.setText(UiLanguage.dynamic(act, hit.source) + " · " + hit.title);
            meta.setTextColor(subColor);
            meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            meta.setSingleLine(true);
            meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(meta);

            TextView snippet = new TextView(act);
            SpannableStringBuilder styled = new SpannableStringBuilder(hit.snippet);
            int from = Math.max(0, Math.min(hit.highlightStart, hit.snippet.length()));
            int to = Math.max(from, Math.min(
                    hit.highlightStart + hit.highlightLength, hit.snippet.length()));
            if (to > from) {
                styled.setSpan(new BackgroundColorSpan(highlightColor), from, to,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                styled.setSpan(new StyleSpan(Typeface.BOLD), from, to,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            snippet.setText(styled);
            snippet.setTextColor(textColor);
            snippet.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            snippet.setMaxLines(3);
            snippet.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams snippetParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            snippetParams.topMargin = dp(act, 4);
            row.addView(snippet, snippetParams);

            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View ignored) {
                    if (Main.openNativeSession(hit.sid)) {
                        dialog.dismiss();
                        DeekseepUi.dismissForNativeNavigation();
                    } else {
                        UiLanguage.toast(act,
                                "当前登录账号的原生会话列表中没有该对话",
                                Toast.LENGTH_LONG).show();
                    }
                }
            });
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(act, 8);
            list.addView(row, rowParams);
        }

        TextView close = new TextView(act);
        close.setText(UiLanguage.text(act, "关闭", "Close"));
        close.setTextColor(subColor);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        close.setTypeface(Typeface.DEFAULT_BOLD);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(act, 16), dp(act, 8), dp(act, 16), dp(act, 8));
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View ignored) { dialog.dismiss(); }
        });
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeParams.gravity = Gravity.END;
        panel.addView(close, closeParams);

        UiLanguage.localizeTree(act, panel);
        dialog.setContentView(panel);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0x00000000));
            window.setLayout((int) (act.getResources().getDisplayMetrics().widthPixels * 0.92f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static int dp(Activity activity, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics()));
    }
}

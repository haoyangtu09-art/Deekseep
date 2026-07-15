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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deekseep 聊天数据工具箱（全部基于已验证的 ChatEditorUi 数据层 + 纯 Android 框架 API，
 * 不依赖任何混淆符号，因此对 build 变化免疫）。五个增强功能：
 *  1) 导出会话为 Markdown   2) 全局搜索   3) 会话数据统计
 *  4) 数据库备份(手动"立即备份" + 自动备份开关)
 */
public final class DeekseepTools {

    static final String DB_DIR = "/data/data/com.deepseek.chat/databases";
    // 自动备份写应用内部目录（无需存储权限，重装应用前用手动备份到外部目录）
    static final String AUTO_BACKUP_DIR = "/data/data/com.deepseek.chat/files/deekseep_backup";

    private DeekseepTools() {}

    // ── 通用：后台跑一个返回提示串的任务，完成后在 UI 线程 Toast ──────────
    interface Job { String run() throws Throwable; }

    private static void runBg(final Activity act, final Job job) {
        new Thread(new Runnable() {
            public void run() {
                String msg;
                try { msg = job.run(); } catch (Throwable t) { msg = "失败: " + t; }
                final String fmsg = msg;
                try {
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            try { Toast.makeText(act, fmsg, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
                        }
                    });
                } catch (Throwable ignored) {}
            }
        }).start();
    }

    private static List<File> chatDbs() {
        List<File> out = new ArrayList<File>();
        File[] fs = new File(DB_DIR).listFiles();
        if (fs != null) for (File f : fs) {
            String n = f.getName();
            if (n.startsWith("deepseek_chat_") && n.endsWith(".db")) out.add(f);
        }
        return out;
    }

    private static File exportBase(Activity act) {
        File ext = null;
        try { ext = act.getExternalFilesDir(null); } catch (Throwable ignored) {}
        File base = new File(ext != null ? ext : new File("/data/data/com.deepseek.chat/files"), "deekseep");
        base.mkdirs();
        return base;
    }

    private static String sanitize(String s) {
        if (s == null) return "untitled";
        String r = s.replaceAll("[/\\\\:*?\"<>|\\r\\n\\t]", "_").trim();
        if (r.length() == 0) r = "untitled";
        if (r.length() > 60) r = r.substring(0, 60);
        return r;
    }

    private static String shortId(String sid) {
        if (sid == null) return "x";
        return sid.length() > 8 ? sid.substring(0, 8) : sid;
    }

    private static void writeText(File f, String text) throws Throwable {
        FileOutputStream fos = new FileOutputStream(f);
        OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8");
        try { w.write(text); } finally { w.close(); }
    }

    private static String sessionMarkdown(SQLiteDatabase db, String title, String sid) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title == null || title.length() == 0 ? sid : title).append("\n\n");
        for (ChatEditorUi.Msg m : ChatEditorUi.loadThread(db, sid)) {
            sb.append("USER".equals(m.role) ? "**用户**" : "**助手**").append("\n\n");
            if (m.think != null && m.think.length() > 0) {
                for (String line : m.think.split("\n")) sb.append("> ").append(line).append("\n");
                sb.append("\n");
            }
            sb.append(m.body == null ? "" : m.body).append("\n\n---\n\n");
        }
        return sb.toString();
    }

    // ── 功能 1：导出全部会话为 Markdown ─────────────────────────────────
    static void exportAll(final Activity act) {
        Toast.makeText(act, "正在导出…", Toast.LENGTH_SHORT).show();
        runBg(act, new Job() {
            public String run() throws Throwable {
                File base = exportBase(act);
                int n = 0;
                for (File f : chatDbs()) {
                    SQLiteDatabase d = null; Cursor c = null;
                    try {
                        d = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY);
                        c = d.rawQuery("SELECT id,title FROM chat_session_list ORDER BY updated_at DESC", null);
                        while (c.moveToNext()) {
                            String sid = c.getString(0), title = c.getString(1);
                            if (sid == null) continue;
                            String md = sessionMarkdown(d, title, sid);
                            writeText(new File(base, sanitize(title != null && title.length() > 0 ? title : sid)
                                    + "_" + shortId(sid) + ".md"), md);
                            n++;
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (c != null) try { c.close(); } catch (Throwable ig) {}
                        if (d != null) try { d.close(); } catch (Throwable ig) {}
                    }
                }
                return n == 0 ? "没有可导出的本地会话" : ("已导出 " + n + " 个会话到\n" + base.getPath());
            }
        });
    }

    // ── 功能 2：全局搜索聊天记录（圆角 DeepSeek 风格 + 点击结果跳转到消息）──
    static final class Hit {
        String dbPath, sid, title, role, snippet; long msgId; int hlStart, hlLen;
    }

    private static int dp(Activity a, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics()));
    }

    static void showSearch(final Activity act) {
        final boolean dark = DeekseepUi.isDark(act);
        final int card = dark ? 0xFF2A2A2D : 0xFFFFFFFF;
        final int text = dark ? 0xFFECECEC : 0xFF1A1A1A;
        final int sub  = dark ? 0xFF9A9A9E : 0xFF888888;

        final Dialog dlg = new Dialog(act);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(act, 20), dp(act, 18), dp(act, 20), dp(act, 14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(card); bg.setCornerRadius(dp(act, 22));
        box.setBackground(bg);

        TextView h = new TextView(act);
        h.setText("搜索聊天记录");
        h.setTextColor(text); h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        box.addView(h);

        final EditText input = new EditText(act);
        input.setHint("输入关键词");
        input.setTextColor(text); input.setHintTextColor(sub);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        GradientDrawable ib = new GradientDrawable();
        ib.setColor(dark ? 0xFF1F1F22 : 0xFFF2F3F5); ib.setCornerRadius(dp(act, 12));
        input.setBackground(ib);
        input.setPadding(dp(act, 12), dp(act, 10), dp(act, 12), dp(act, 10));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(act, 14);
        box.addView(input, ilp);

        LinearLayout btns = new LinearLayout(act);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(act, 14);
        box.addView(btns, blp);

        TextView cancel = pillButton(act, "取消", sub, 0);
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dlg.dismiss(); } });
        btns.addView(cancel);

        TextView go = pillButton(act, "搜索", 0xFFFFFFFF, DeekseepUi.BRAND);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.leftMargin = dp(act, 8);
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String kw = input.getText().toString().trim();
                if (kw.length() == 0) return;
                dlg.dismiss();
                runSearch(act, kw);
            } });
        btns.addView(go, glp);

        dlg.setContentView(box);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.setLayout((int) (act.getResources().getDisplayMetrics().widthPixels * 0.86f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dlg.show();
    }

    private static TextView pillButton(Activity act, String label, int fg, int bgColor) {
        TextView tv = new TextView(act);
        tv.setText(label);
        tv.setTextColor(fg);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(act, 18), dp(act, 8), dp(act, 18), dp(act, 8));
        tv.setClickable(true);
        if (bgColor != 0) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(bgColor); g.setCornerRadius(dp(act, 18));
            tv.setBackground(g);
        }
        return tv;
    }

    private static void runSearch(final Activity act, final String kw) {
        Toast.makeText(act, "搜索中…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            public void run() {
                final List<Hit> hits = new ArrayList<Hit>();
                String low = kw.toLowerCase(Locale.getDefault());
                for (File f : chatDbs()) {
                    SQLiteDatabase d = null; Cursor c = null;
                    try {
                        d = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY);
                        c = d.rawQuery("SELECT id,title FROM chat_session_list ORDER BY updated_at DESC", null);
                        while (c.moveToNext() && hits.size() < 200) {
                            String sid = c.getString(0), title = c.getString(1);
                            if (sid == null) continue;
                            for (ChatEditorUi.Msg m : ChatEditorUi.loadThread(d, sid)) {
                                String body = m.body == null ? "" : m.body;
                                int idx = body.toLowerCase(Locale.getDefault()).indexOf(low);
                                if (idx < 0) continue;
                                int s = Math.max(0, idx - 24), e = Math.min(body.length(), idx + kw.length() + 40);
                                String snip = body.substring(s, e).replaceAll("\\s+", " ");
                                int rel = body.substring(s, idx).replaceAll("\\s+", " ").length();
                                Hit hit = new Hit();
                                hit.dbPath = f.getPath(); hit.sid = sid;
                                hit.title = (title == null || title.length() == 0) ? "未命名对话" : title;
                                hit.role = m.role; hit.msgId = m.id; hit.snippet = snip;
                                hit.hlStart = rel; hit.hlLen = kw.length();
                                hits.add(hit);
                                if (hits.size() >= 200) break;
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (c != null) try { c.close(); } catch (Throwable ig) {}
                        if (d != null) try { d.close(); } catch (Throwable ig) {}
                    }
                }
                act.runOnUiThread(new Runnable() {
                    public void run() { showSearchResults(act, kw, hits); }
                });
            }
        }).start();
    }

    private static void showSearchResults(final Activity act, String kw, final List<Hit> hits) {
        final boolean dark = DeekseepUi.isDark(act);
        final int card = dark ? 0xFF2A2A2D : 0xFFFFFFFF;
        final int rowBg = dark ? 0xFF1F1F22 : 0xFFF6F7F9;
        final int text = dark ? 0xFFECECEC : 0xFF1A1A1A;
        final int sub  = dark ? 0xFF9A9A9E : 0xFF888888;
        final int hl   = dark ? 0x66C08A2E : 0x66FFE08A;

        final Dialog dlg = new Dialog(act);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(act, 16), dp(act, 16), dp(act, 16), dp(act, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(card); bg.setCornerRadius(dp(act, 22));
        box.setBackground(bg);

        TextView h = new TextView(act);
        h.setText(hits.isEmpty() ? "未找到「" + kw + "」" : "「" + kw + "」命中 " + hits.size() + " 条");
        h.setTextColor(text); h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        h.setPadding(dp(act, 4), 0, dp(act, 4), dp(act, 10));
        box.addView(h);

        ScrollView sv = new ScrollView(act);
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        int maxH = (int) (act.getResources().getDisplayMetrics().heightPixels * 0.6f);
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                hits.size() > 6 ? maxH : ViewGroup.LayoutParams.WRAP_CONTENT);
        box.addView(sv, svlp);

        for (final Hit hit : hits) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable rb = new GradientDrawable();
            rb.setColor(rowBg); rb.setCornerRadius(dp(act, 12));
            row.setBackground(rb);
            row.setPadding(dp(act, 12), dp(act, 10), dp(act, 12), dp(act, 10));
            row.setClickable(true);

            TextView tt = new TextView(act);
            tt.setText((("USER".equals(hit.role)) ? "我 · " : "AI · ") + hit.title);
            tt.setTextColor(sub); tt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            tt.setSingleLine(true); tt.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(tt);

            TextView sn = new TextView(act);
            SpannableStringBuilder ssb = new SpannableStringBuilder(hit.snippet);
            int a = Math.max(0, Math.min(hit.hlStart, hit.snippet.length()));
            int b = Math.max(a, Math.min(hit.hlStart + hit.hlLen, hit.snippet.length()));
            if (b > a) {
                ssb.setSpan(new BackgroundColorSpan(hl), a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new StyleSpan(Typeface.BOLD), a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            sn.setText(ssb);
            sn.setTextColor(text); sn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            sn.setMaxLines(2); sn.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams snlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            snlp.topMargin = dp(act, 4);
            row.addView(sn, snlp);

            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    dlg.dismiss();
                    ChatEditorUi.showAt(act, hit.dbPath, hit.sid, hit.msgId);
                } });
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(act, 8);
            list.addView(row, rlp);
        }

        TextView close = pillButton(act, "关闭", sub, 0);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dlg.dismiss(); } });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.END; clp.topMargin = dp(act, 4);
        box.addView(close, clp);

        dlg.setContentView(box);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.setLayout((int) (act.getResources().getDisplayMetrics().widthPixels * 0.9f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dlg.show();
    }

    // ── 功能 3：数据库备份（手动 + 自动）────────────────────────────────
    static void backupNow(final Activity act) {
        Toast.makeText(act, "正在备份…", Toast.LENGTH_SHORT).show();
        runBg(act, new Job() {
            public String run() throws Throwable {
                File base = new File(exportBase(act), "backup_" + stamp());
                int n = doBackupTo(base);
                return n == 0 ? "没有可备份的数据库" : ("已备份 " + n + " 个数据库到\n" + base.getPath());
            }
        });
    }

    private static int doBackupTo(File dst) {
        dst.mkdirs();
        int n = 0;
        File[] fs = new File(DB_DIR).listFiles();
        if (fs != null) for (File f : fs) {
            String nm = f.getName();
            if (f.isFile() && nm.startsWith("deepseek_chat") && nm.endsWith(".db")) {
                try { copyFile(f, new File(dst, nm)); n++; } catch (Throwable ignored) {}
            }
        }
        return n;
    }

    private static void copyFile(File src, File dst) throws Throwable {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        } finally {
            try { in.close(); } catch (Throwable ig) {}
            try { out.close(); } catch (Throwable ig) {}
        }
    }

    /** 每次应用启动时调用（后台线程）：开启自动备份且距上次 >24h 才执行，写内部目录。 */
    static void maybeAutoBackup() {
        try {
            if (!Main.isAutoBackup()) return;
            File root = new File(AUTO_BACKUP_DIR);
            long newest = 0;
            File[] subs = root.listFiles();
            if (subs != null) for (File s : subs) if (s.lastModified() > newest) newest = s.lastModified();
            if (System.currentTimeMillis() - newest < 24L * 3600 * 1000) return;
            File dst = new File(root, stamp());
            int n = doBackupTo(dst);
            // 只保留最近 5 份
            File[] all = root.listFiles();
            if (all != null && all.length > 5) {
                java.util.Arrays.sort(all, new java.util.Comparator<File>() {
                    public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); }
                });
                for (int i = 0; i < all.length - 5; i++) deleteRec(all[i]);
            }
            Main.log("auto backup done: " + n + " dbs -> " + dst.getName());
        } catch (Throwable t) {
            try { Main.log("auto backup failed: " + t); } catch (Throwable ignored) {}
        }
    }

    private static void deleteRec(File f) {
        if (f == null) return;
        File[] cs = f.listFiles();
        if (cs != null) for (File c : cs) deleteRec(c);
        f.delete();
    }

    // ── 功能 4：会话数据统计 ────────────────────────────────────────────
    static void showStats(final Activity act) {
        Toast.makeText(act, "统计中…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            public void run() {
                Map<String, String> accounts = ChatEditorUi.loadAccountLabels();
                int sessions = 0, msgs = 0;
                long chars = 0;
                StringBuilder perAcc = new StringBuilder();
                for (File f : chatDbs()) {
                    String label = accounts.get(ChatEditorUi.uuidOf(f));
                    int accSessions = 0, accMsgs = 0;
                    SQLiteDatabase d = null; Cursor c = null;
                    try {
                        d = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY);
                        c = d.rawQuery("SELECT id FROM chat_session_list", null);
                        List<String> sids = new ArrayList<String>();
                        while (c.moveToNext()) if (c.getString(0) != null) sids.add(c.getString(0));
                        for (String sid : sids) {
                            accSessions++;
                            for (ChatEditorUi.Msg m : ChatEditorUi.loadThread(d, sid)) {
                                accMsgs++;
                                if (m.body != null) chars += m.body.length();
                                if (m.think != null) chars += m.think.length();
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (c != null) try { c.close(); } catch (Throwable ig) {}
                        if (d != null) try { d.close(); } catch (Throwable ig) {}
                    }
                    sessions += accSessions; msgs += accMsgs;
                    perAcc.append("• ").append(label == null ? ChatEditorUi.uuidOf(f).substring(0, 8) : label)
                            .append("：").append(accSessions).append(" 会话 / ").append(accMsgs).append(" 消息\n");
                }
                final String text = "本地账号数：" + chatDbs().size() + "\n"
                        + "会话总数：" + sessions + "\n"
                        + "消息总数：" + msgs + "\n"
                        + "正文+思考总字数：" + chars + "\n\n"
                        + "按账号：\n" + perAcc;
                act.runOnUiThread(new Runnable() {
                    public void run() { showScrollDialog(act, "会话数据统计", text); }
                });
            }
        }).start();
    }

    // ── 通用滚动结果对话框 ─────────────────────────────────────────────
    private static void showScrollDialog(Activity act, String title, String text) {
        try {
            ScrollView sv = new ScrollView(act);
            TextView tv = new TextView(act);
            int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16,
                    act.getResources().getDisplayMetrics());
            tv.setPadding(pad, pad, pad, pad);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tv.setTextIsSelectable(true);
            tv.setText(text);
            sv.addView(tv);
            new AlertDialog.Builder(act).setTitle(title).setView(sv)
                    .setPositiveButton("关闭", null).show();
        } catch (Throwable ignored) {}
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }
}

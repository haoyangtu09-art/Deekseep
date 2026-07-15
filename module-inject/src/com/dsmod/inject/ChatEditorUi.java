package com.dsmod.inject;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek 聊天记录编辑器（运行在 DeepSeek 进程内，直接读写其私有 SQLite）。
 *
 * 仿 DeepSeek：平板左右分栏，手机全屏+左上三条杠抽屉；用户消息淡蓝圆角气泡、助手纯文本、
 * 深度思考折叠为"已思考"灰字。长按消息/思考/标题进入编辑（高亮+光标+弹键盘），可同时编辑多处，
 * 顶部“保存”一次性提交【当前会话】所有改动（不影响其他会话）。DeepSeek 重启读库后显示新内容。
 * 左下角账号按钮可切换“只显示当前账号 / 显示所有账号”的聊天记录（默认仅当前）。
 */
public final class ChatEditorUi {

    private static final String DB_DIR = "/data/data/com.deepseek.chat/databases";

    // 注入提示词的包裹标记（见 Main.hookChatRequest：<system>\n...\n</system>\n\n + 原文）
    static final String SYS_OPEN = "<system>\n";
    static final String SYS_CLOSE = "\n</system>\n\n";

    private ChatEditorUi() {}

    // 极简 Markdown 渲染：**加粗**、****加粗****、*斜体*、***粗斜体***、`等宽`。
    // 仅用于查看态，编辑态显示源码，保存时不改写原始 Markdown。
    static CharSequence md(String s) {
        if (s == null || s.isEmpty()) return "";
        SpannableStringBuilder out = new SpannableStringBuilder();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '*') {
                int run = countStarRun(s, i);
                int j = i + run;
                if (run >= 2) {
                    int close = findStarRun(s, j, run);
                    if (close >= 0) {
                        int start = out.length();
                        out.append(md(s.substring(j, close)));
                        int style = run == 3 ? Typeface.BOLD_ITALIC : Typeface.BOLD;
                        out.setSpan(new StyleSpan(style), start, out.length(),
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        i = close + run;
                        continue;
                    }
                }
                if (run == 1) {
                    int close = findStarRun(s, j, 1);
                    if (close >= 0) {
                        int start = out.length();
                        out.append(md(s.substring(j, close)));
                        out.setSpan(new StyleSpan(Typeface.ITALIC), start, out.length(),
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        i = close + 1; continue;
                    }
                }
                out.append(s, i, j); i = j;
            } else if (c == '`') {
                int close = s.indexOf('`', i + 1);
                if (close >= 0) {
                    int start = out.length();
                    out.append(s, i + 1, close);
                    out.setSpan(new TypefaceSpan("monospace"), start, out.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = close + 1;
                } else { out.append(c); i++; }
            } else {
                out.append(c); i++;
            }
        }
        return out;
    }

    private static int countStarRun(String s, int pos) {
        int n = s.length(), i = pos;
        while (i < n && s.charAt(i) == '*') i++;
        return i - pos;
    }

    private static int findStarRun(String s, int from, int want) {
        int n = s.length();
        for (int i = from; i < n; i++) {
            if (s.charAt(i) != '*') continue;
            int run = countStarRun(s, i);
            if ((want == 1 && run == 1) || (want > 1 && run >= want)) return i;
            i += run - 1;
        }
        return -1;
    }

    // 若 user 正文以注入的 <system>…</system> 包裹开头，返回其结束下标（含 SYS_CLOSE），否则 -1
    static int sysPrefixEnd(String body) {
        if (body == null || !body.startsWith(SYS_OPEN)) return -1;
        int idx = body.indexOf(SYS_CLOSE);
        return idx < 0 ? -1 : idx + SYS_CLOSE.length();
    }

    static void show(Activity act) {
        try { new Ctrl(act).open(); }
        catch (Throwable t) {
            Toast.makeText(act, "无法打开聊天编辑器: " + t, Toast.LENGTH_LONG).show();
        }
    }

    // ── 数据模型 ─────────────────────────────────────────────
    static final class Session { String id; String title; String dbPath; String account; }
    static final class Msg { long id; String role; String think = ""; String body = ""; }

    // 可编辑字段（消息正文 / 思考 / 标题），批量保存时逐个比对提交
    static final class Field {
        EditText et; String kind; String role; long msgId; String original;
        Drawable normalBg; int normalText, hlText; int hlColor;
        boolean render;             // 查看态是否渲染 Markdown（BODY/THINK 为 true，TITLE 为 false）
    }

    // ── 数据层 ───────────────────────────────────────────────
    static List<File> allDbs() {
        List<File> out = new ArrayList<>();
        File[] fs = new File(DB_DIR).listFiles();
        if (fs != null) for (File f : fs) {
            String n = f.getName();
            if (n.startsWith("deepseek_chat_") && n.endsWith(".db")) out.add(f);
        }
        return out;
    }

    static File currentDb() {
        File best = null;
        for (File f : allDbs()) if (best == null || f.lastModified() > best.lastModified()) best = f;
        return best;
    }

    static String uuidOf(File f) {
        String n = f.getName();
        return n.substring("deepseek_chat_".length(), n.length() - ".db".length());
    }

    // uuid -> 友好账号名（id_profiles 里的 name，其次 email，最后短 uuid）
    static Map<String, String> loadAccountLabels() {
        Map<String, String> m = new HashMap<>();
        File f = new File(DB_DIR, "deepseek_chat.db");
        if (!f.exists()) return m;
        SQLiteDatabase d = null;
        Cursor c = null;
        try {
            d = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            c = d.rawQuery("SELECT id,email,id_profiles FROM app_user_info", null);
            while (c.moveToNext()) {
                String id = c.getString(0);
                String email = c.getString(1);
                String profiles = c.getString(2);
                String label = null;
                try {
                    if (profiles != null) {
                        JSONArray a = new JSONArray(profiles);
                        if (a.length() > 0) label = a.getJSONObject(0).optString("name", null);
                    }
                } catch (Throwable ignored) {}
                if (label == null || label.length() == 0) label = email;
                if (label == null || label.length() == 0) label = id != null && id.length() > 8 ? id.substring(0, 8) : id;
                if (id != null) m.put(id, label);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
            if (d != null) try { d.close(); } catch (Throwable ignored) {}
        }
        return m;
    }

    private static Long currentMessageId(SQLiteDatabase db, String sid) {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT current_message_id FROM chat_session_list WHERE id=?", new String[]{sid});
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Throwable ignored) {
        } finally { if (c != null) c.close(); }
        return null;
    }

    static List<Msg> loadThread(SQLiteDatabase db, String sid) {
        String t = "chat_session_messages_" + sid;
        Map<Long, Msg> map = new HashMap<>();
        Map<Long, Long> parent = new HashMap<>();
        long maxId = -1;
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT message_id,parent_id,role,fragments FROM '" + t + "'", null);
            while (c.moveToNext()) {
                long id = c.getLong(0);
                Long p = c.isNull(1) ? null : c.getLong(1);
                Msg m = new Msg();
                m.id = id;
                m.role = c.getString(2) == null ? "" : c.getString(2);
                parseFragments(m, c.getString(3));
                map.put(id, m);
                parent.put(id, p);
                if (id > maxId) maxId = id;
            }
        } catch (Throwable ignored) {
            return new ArrayList<>(); // 表不存在（未下载到本机）→ 空
        } finally { if (c != null) c.close(); }

        Long cur = currentMessageId(db, sid);
        if (cur == null || !map.containsKey(cur)) cur = (maxId >= 0) ? maxId : null;

        LinkedList<Msg> thread = new LinkedList<>();
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        while (cur != null && map.containsKey(cur) && !seen.contains(cur)) {
            thread.addFirst(map.get(cur));
            seen.add(cur);
            Long p = parent.get(cur);
            cur = (p != null && p > 0) ? p : null;
        }
        return thread;
    }

    private static void parseFragments(Msg m, String frag) {
        if (frag == null || frag.length() == 0) return;
        try {
            JSONArray a = new JSONArray(frag);
            StringBuilder think = new StringBuilder();
            String resp = null, tmpl = null, req = null;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String type = o.optString("type");
                String content = o.optString("content");
                if ("THINK".equals(type)) { if (think.length() > 0) think.append("\n\n"); think.append(content); }
                else if ("RESPONSE".equals(type)) resp = content;
                else if ("TEMPLATE_RESPONSE".equals(type)) tmpl = content;
                else if ("REQUEST".equals(type)) req = content;
            }
            m.think = think.toString();
            if ("USER".equals(m.role)) m.body = req != null ? req : "";
            else m.body = resp != null ? resp : (tmpl != null ? tmpl : "");
        } catch (Throwable ignored) {}
    }

    // 写回单个 fragment 的 content（kind=THINK / 否则按 role 选 REQUEST 或 RESPONSE/TEMPLATE）
    static boolean saveFragment(SQLiteDatabase db, String sid, long msgId, String role, String kind, String text) {
        String t = "chat_session_messages_" + sid;
        Cursor c = null; String frag = null;
        try {
            c = db.rawQuery("SELECT fragments FROM '" + t + "' WHERE message_id=?", new String[]{String.valueOf(msgId)});
            if (c.moveToFirst()) frag = c.getString(0);
        } catch (Throwable ignored) {
        } finally { if (c != null) c.close(); }
        if (frag == null) return false;
        try {
            JSONArray a = new JSONArray(frag);
            String[] targets = "THINK".equals(kind) ? new String[]{"THINK"}
                    : ("USER".equals(role) ? new String[]{"REQUEST"} : new String[]{"RESPONSE", "TEMPLATE_RESPONSE"});
            boolean done = false;
            outer:
            for (String target : targets) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null && target.equals(o.optString("type"))) {
                        o.put("content", text); done = true; break outer;
                    }
                }
            }
            if (!done) return false;
            db.execSQL("UPDATE '" + t + "' SET fragments=? WHERE message_id=?", new Object[]{a.toString(), msgId});
            return true;
        } catch (Throwable t2) { return false; }
    }

    static boolean saveTitle(SQLiteDatabase db, String sid, String title) {
        try { db.execSQL("UPDATE chat_session_list SET title=? WHERE id=?", new Object[]{title, sid}); return true; }
        catch (Throwable t) { return false; }
    }

    // 把本会话的 cache_version 顶到 32 位 int 上限，让 DeepSeek 的会话同步合并跳过它，
    // 从而本地改的标题/正文不会被服务器旧数据覆盖回去。
    // 依据：fm8.b 仅当 本地 cache_version(am8.a=v52.f 列 cache_version) <= 服务器 version 时才覆盖本地。
    // 之前用 1e9 无效：服务器 version 疑似 epoch 秒(~1.75e9)＞1e9，仍会覆盖；改用 Integer.MAX_VALUE(2.147e9)封顶。
    static final int FREEZE_VERSION = Integer.MAX_VALUE;
    static boolean freezeSession(SQLiteDatabase db, String sid) {
        try {
            db.execSQL("UPDATE chat_session_list SET cache_version=? WHERE id=?",
                    new Object[]{FREEZE_VERSION, sid});
            return true;
        } catch (Throwable t) { return false; }
    }

    // 把本会话所有 USER 消息里注入的 <system>…</system> 前缀从存库正文中抹掉，
    // 使真实 DeepSeek 对话界面不再显示系统提示词。系统提示词每次发送时重新注入，历史里无需保留。
    // 冻结(freezeSession)后服务器不再回刷清理本会话，故须自己清。返回清理条数。
    static int stripSysPrompts(SQLiteDatabase db, String sid) {
        String t = "chat_session_messages_" + sid;
        List<Object[]> updates = new ArrayList<>();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT message_id, fragments FROM '" + t + "' WHERE role=?",
                    new String[]{"USER"});
            while (c.moveToNext()) {
                long mid = c.getLong(0);
                String frag = c.getString(1);
                if (frag == null) continue;
                try {
                    JSONArray a = new JSONArray(frag);
                    boolean ch = false;
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.optJSONObject(i);
                        if (o == null || !"REQUEST".equals(o.optString("type"))) continue;
                        String content = o.optString("content", "");
                        int end = sysPrefixEnd(content);
                        if (end > 0) { o.put("content", content.substring(end)); ch = true; }
                    }
                    if (ch) updates.add(new Object[]{a.toString(), mid});
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        } finally { if (c != null) c.close(); }
        int n = 0;
        for (Object[] u : updates) {
            try { db.execSQL("UPDATE '" + t + "' SET fragments=? WHERE message_id=?", u); n++; }
            catch (Throwable ignored) {}
        }
        return n;
    }

    // 全库自动清理：遍历所有账号库的所有会话消息表，抹掉 USER 消息里注入的 <system> 前缀。
    // 用途：注入的系统提示词会随服务器回刷/本地持久化写进库，重开 DeepSeek 时泄露到对话界面。
    // 模块启动时（UI 加载前）跑一次，保证用户永远看不到系统提示词。返回清理条数。
    static int stripAllSessions() {
        int total = 0;
        for (File f : allDbs()) {
            SQLiteDatabase db = null;
            Cursor c = null;
            try {
                db = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READWRITE);
                List<String> sids = new ArrayList<>();
                c = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'chat_session_messages_%'", null);
                while (c.moveToNext()) {
                    String tbl = c.getString(0);
                    sids.add(tbl.substring("chat_session_messages_".length()));
                }
                c.close(); c = null;
                for (String sid : sids) total += stripSysPrompts(db, sid);
            } catch (Throwable ignored) {
            } finally {
                if (c != null) try { c.close(); } catch (Throwable ignored) {}
                if (db != null) try { db.close(); } catch (Throwable ignored) {}
            }
        }
        return total;
    }

    // ── 控制器 ───────────────────────────────────────────────
    static final class Ctrl {
        final Activity act;
        final boolean dark, tablet;
        final int bg, bar, text, sub, div;
        final int uBubble, uText, uEdit;      // 用户气泡：淡蓝
        final int aEdit;                       // 助手编辑高亮
        final int thinkBg, thinkEdit;
        final InputMethodManager imm;

        Dialog dlg;
        View root;
        SQLiteDatabase sessDb;
        File curDbFile;
        Map<String, String> accounts;
        boolean showAll = false;

        List<Session> sessions = new ArrayList<>();
        String curSid;
        Session curSession;
        List<Msg> curThread;
        final List<Field> fields = new ArrayList<>();

        LinearLayout msgContainer, historyList;
        ScrollView msgScroll;
        EditText titleEt;
        View historyPane, scrim;
        TextView accountBtn;
        boolean drawerOpen;

        Ctrl(Activity act) {
            this.act = act;
            this.dark = DeekseepUi.isDark(act);
            this.tablet = act.getResources().getConfiguration().smallestScreenWidthDp >= 600;
            this.imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
            bg   = dark ? 0xFF1B1B1D : 0xFFF5F6F8;
            bar  = dark ? 0xFF232326 : 0xFFFFFFFF;
            text = dark ? 0xFFECECEC : 0xFF1A1A1A;
            sub  = dark ? 0xFF9A9A9E : 0xFF888888;
            div  = dark ? 0xFF3A3A3D : 0xFFEEEEEE;
            // 用户气泡：淡蓝（非深蓝），深浅色分别取值
            uBubble = dark ? 0xFF2A3856 : 0xFFEEF3FF;
            uText   = dark ? 0xFFECECEC : 0xFF14223B;
            uEdit   = dark ? 0xFF35496E : 0xFFDCE7FF;
            aEdit   = dark ? 0xFF33343A : 0xFFE6E9F2;
            thinkBg   = dark ? 0xFF232326 : 0xFFEFEFF2;
            thinkEdit = dark ? 0xFF3A3A3D : 0xFFE6E9F2;
        }

        int dp(float v) { return DeekseepUi.dp(act, v); }

        void open() {
            curDbFile = currentDb();
            if (curDbFile == null) { Toast.makeText(act, "未找到聊天数据库", Toast.LENGTH_LONG).show(); return; }
            accounts = loadAccountLabels();

            dlg = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            root = tablet ? buildTablet() : buildPhone();
            dlg.setContentView(root);
            Window w = dlg.getWindow();
            if (w != null) {
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                w.setBackgroundDrawable(new ColorDrawable(bg));
                // 键盘弹出时上顶内容，避免遮住正在编辑的消息
                w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
                        | WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED);
            }
            dlg.setOnKeyListener(new Dialog.OnKeyListener() {
                public boolean onKey(DialogInterface d, int code, KeyEvent e) {
                    if (code == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) {
                        if (drawerOpen) { closeDrawer(); return true; }
                        DeekseepUi.slideOutAndDismiss(dlg, root); return true;
                    }
                    return false;
                }
            });
            dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
                public void onDismiss(DialogInterface d) {
                    try { if (sessDb != null) sessDb.close(); } catch (Throwable ignored) {}
                }
            });

            loadSessionList();
            if (!sessions.isEmpty()) selectSession(sessions.get(0));

            int wpx = act.getResources().getDisplayMetrics().widthPixels;
            root.setTranslationX(wpx);
            dlg.show();
            root.animate().translationX(0).setDuration(260).setInterpolator(new DecelerateInterpolator()).start();
        }

        // ── 布局 ─────────────────────────────────────────
        View buildTablet() {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackgroundColor(bg);
            row.addView(buildHistoryPane(), new LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.MATCH_PARENT));
            View vdiv = new View(act); vdiv.setBackgroundColor(div);
            row.addView(vdiv, new LinearLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT));
            row.addView(buildContentPane(false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            return row;
        }

        View buildPhone() {
            FrameLayout fl = new FrameLayout(act);
            fl.setBackgroundColor(bg);
            fl.addView(buildContentPane(true), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            scrim = new View(act);
            scrim.setBackgroundColor(0x88000000);
            scrim.setVisibility(View.GONE);
            scrim.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { closeDrawer(); } });
            fl.addView(scrim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            historyPane = buildHistoryPane();
            int paneW = dp(300);
            fl.addView(historyPane, new FrameLayout.LayoutParams(paneW, ViewGroup.LayoutParams.MATCH_PARENT));
            historyPane.setTranslationX(-paneW);
            return fl;
        }

        void openDrawer() {
            drawerOpen = true;
            scrim.setVisibility(View.VISIBLE); scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(200).start();
            historyPane.animate().translationX(0).setDuration(240).setInterpolator(new DecelerateInterpolator()).start();
        }

        void closeDrawer() {
            drawerOpen = false;
            final int paneW = dp(300);
            scrim.animate().alpha(0f).setDuration(200).withEndAction(new Runnable() {
                public void run() { scrim.setVisibility(View.GONE); } }).start();
            historyPane.animate().translationX(-paneW).setDuration(220).setInterpolator(new AccelerateInterpolator()).start();
        }

        View buildHistoryPane() {
            LinearLayout pane = new LinearLayout(act);
            pane.setOrientation(LinearLayout.VERTICAL);
            pane.setBackgroundColor(bar);

            TextView head = new TextView(act);
            head.setText("对话历史");
            head.setTextColor(text); head.setTypeface(Typeface.DEFAULT_BOLD);
            head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            head.setPadding(dp(16), DeekseepUi.statusBarHeight(act) + dp(14), dp(16), dp(12));
            pane.addView(head);

            ScrollView sv = new ScrollView(act);
            historyList = new LinearLayout(act);
            historyList.setOrientation(LinearLayout.VERTICAL);
            sv.addView(historyList, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            pane.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            View d2 = new View(act); d2.setBackgroundColor(div);
            pane.addView(d2, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

            // 左下角账号按钮
            accountBtn = new TextView(act);
            accountBtn.setTextColor(text);
            accountBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            accountBtn.setPadding(dp(16), dp(14), dp(16), dp(14));
            accountBtn.setClickable(true);
            accountBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showAccountMenu(); } });
            pane.addView(accountBtn, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            updateAccountBtn();
            return pane;
        }

        void updateAccountBtn() {
            String cur = accounts.get(uuidOf(curDbFile));
            if (cur == null) cur = "当前账号";
            accountBtn.setText("\uD83D\uDC64 " + cur + (showAll ? "（显示所有账号）" : "") + "  \u2304");
        }

        void showAccountMenu() {
            String[] items = {"只显示当前账号", "显示所有账号"};
            new AlertDialog.Builder(act)
                    .setTitle("聊天记录范围")
                    .setSingleChoiceItems(items, showAll ? 1 : 0, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int which) {
                            showAll = (which == 1);
                            d.dismiss();
                            updateAccountBtn();
                            loadSessionList();
                            if (!sessions.isEmpty()) selectSession(sessions.get(0));
                            else clearContent();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }

        View buildContentPane(boolean phone) {
            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setBackgroundColor(bg);

            LinearLayout top = new LinearLayout(act);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.setBackgroundColor(bar);
            int st = DeekseepUi.statusBarHeight(act);
            top.setPadding(dp(6), st, dp(8), 0);
            col.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56) + st));

            if (phone) {
                TextView burger = new TextView(act);
                burger.setText("\u2630");
                burger.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
                burger.setTextColor(text); burger.setGravity(Gravity.CENTER);
                burger.setPadding(dp(8), 0, dp(8), 0); burger.setClickable(true);
                burger.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { if (drawerOpen) closeDrawer(); else openDrawer(); } });
                top.addView(burger, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
            }

            // 标题：长按可改（EditText 伪装为标题）
            titleEt = new EditText(act);
            titleEt.setText("聊天记录");
            titleEt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            titleEt.setTypeface(Typeface.DEFAULT_BOLD);
            titleEt.setTextColor(text);
            titleEt.setSingleLine(true);
            titleEt.setEllipsize(TextUtils.TruncateAt.END);
            titleEt.setBackground(null);
            titleEt.setPadding(dp(6), 0, dp(6), 0);
            titleEt.setInputType(InputType.TYPE_CLASS_TEXT);
            titleEt.setFocusable(false); titleEt.setFocusableInTouchMode(false); titleEt.setCursorVisible(false);
            titleEt.setLongClickable(true);
            top.addView(titleEt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView save = new TextView(act);
            save.setText("保存");
            save.setTextColor(0xFFFFFFFF);
            save.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            save.setTypeface(Typeface.DEFAULT_BOLD);
            save.setGravity(Gravity.CENTER);
            save.setPadding(dp(18), dp(7), dp(18), dp(7));
            GradientDrawable sg = new GradientDrawable();
            sg.setColor(DeekseepUi.BRAND); sg.setCornerRadius(dp(18));
            save.setBackground(sg);
            save.setClickable(true);
            save.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { saveAll(); } });
            LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            svlp.rightMargin = dp(4);
            top.addView(save, svlp);

            TextView close = new TextView(act);
            close.setText("\u2715");
            close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            close.setTextColor(text); close.setGravity(Gravity.CENTER);
            close.setPadding(dp(8), 0, dp(6), 0); close.setClickable(true);
            close.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { DeekseepUi.slideOutAndDismiss(dlg, root); } });
            top.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));

            msgScroll = new ScrollView(act);
            msgScroll.setFillViewport(true);
            msgContainer = new LinearLayout(act);
            msgContainer.setOrientation(LinearLayout.VERTICAL);
            msgContainer.setPadding(dp(12), dp(10), dp(12), dp(24));
            msgScroll.addView(msgContainer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            col.addView(msgScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            // 标题长按编辑
            titleEt.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    for (Field f : fields) if (f.et == titleEt) { beginEdit(f); return true; }
                    return true;
                }
            });
            return col;
        }

        // ── 会话列表 ─────────────────────────────────────
        void loadSessionList() {
            sessions.clear();
            List<File> dbs = new ArrayList<>();
            if (showAll) dbs = allDbs(); else dbs.add(curDbFile);
            for (File f : dbs) {
                String label = accounts.get(uuidOf(f));
                SQLiteDatabase d = null; Cursor c = null;
                try {
                    d = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY);
                    c = d.rawQuery("SELECT id,title FROM chat_session_list ORDER BY updated_at DESC", null);
                    while (c.moveToNext()) {
                        Session s = new Session();
                        s.id = c.getString(0); s.title = c.getString(1);
                        s.dbPath = f.getPath(); s.account = label;
                        if (s.id != null) sessions.add(s);
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (c != null) c.close();
                    if (d != null) try { d.close(); } catch (Throwable ignored) {}
                }
            }
            rebuildHistoryList();
        }

        void rebuildHistoryList() {
            historyList.removeAllViews();
            for (final Session s : sessions) {
                LinearLayout item = new LinearLayout(act);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setPadding(dp(16), dp(12), dp(16), dp(12));
                item.setClickable(true);
                TextView t = new TextView(act);
                t.setText((s.title != null && s.title.trim().length() > 0) ? s.title : "未命名对话");
                t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END);
                t.setTextColor(text); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                item.addView(t);
                if (showAll && s.account != null) {
                    TextView a = new TextView(act);
                    a.setText(s.account);
                    a.setTextColor(sub); a.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                    item.addView(a);
                }
                item.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { selectSession(s); if (!tablet) closeDrawer(); } });
                historyList.addView(item, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }

        void clearContent() {
            msgContainer.removeAllViews();
            fields.clear();
            titleEt.setText("聊天记录");
        }

        // ── 打开会话 ─────────────────────────────────────

        void selectSession(Session s) {
            try { if (sessDb != null) sessDb.close(); } catch (Throwable ignored) {}
            sessDb = SQLiteDatabase.openDatabase(s.dbPath, null, SQLiteDatabase.OPEN_READWRITE);
            curSid = s.id; curSession = s;
            String title = (s.title != null && s.title.trim().length() > 0) ? s.title : "";
            titleEt.setText(title.length() > 0 ? title : "未命名对话");
            curThread = loadThread(sessDb, s.id);
            renderThread();
        }

        // 渲染当前会话（不重新查库）；user 正文永远切掉注入的 <system> 前缀
        void renderThread() {
            fields.clear();
            msgContainer.removeAllViews();
            String title = (curSession != null && curSession.title != null) ? curSession.title : "";
            registerField(titleEt, "TITLE", null, 0, title, null, text, text, aEdit);

            if (curThread == null || curThread.isEmpty()) {
                addPlaceholder("此对话没有本地消息\n（可能尚未下载到本机——先在 DeepSeek 里打开该对话让它同步，再回来编辑）");
                return;
            }
            for (Msg m : curThread) addMessage(m);
            msgScroll.post(new Runnable() { public void run() { msgScroll.fullScroll(View.FOCUS_DOWN); } });
        }

        void addPlaceholder(String txt) {
            TextView ph = new TextView(act);
            ph.setText(txt);
            ph.setTextColor(sub); ph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            ph.setGravity(Gravity.CENTER);
            ph.setPadding(dp(24), dp(60), dp(24), dp(24));
            msgContainer.addView(ph, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // ── 渲染单条消息 ─────────────────────────────────
        void addMessage(final Msg m) {
            boolean user = "USER".equals(m.role);

            if (!user && m.think != null && m.think.trim().length() > 0) {
                final TextView head = new TextView(act);
                head.setText("\u25B8 已思考");
                head.setTextColor(sub); head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                head.setPadding(dp(4), dp(6), dp(4), dp(4)); head.setClickable(true);

                final EditText think = new EditText(act);
                think.setText(m.think);
                think.setTextColor(sub); think.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                think.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                GradientDrawable tb = new GradientDrawable(); tb.setColor(thinkBg); tb.setCornerRadius(dp(12));
                think.setBackground(tb);
                think.setPadding(dp(12), dp(10), dp(12), dp(10));
                think.setFocusable(false); think.setFocusableInTouchMode(false); think.setCursorVisible(false);
                think.setLongClickable(true);
                think.setVisibility(View.GONE);
                final Field tf = registerField(think, "THINK", m.role, m.id, m.think, tb, sub, sub, thinkEdit);
                think.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) { beginEdit(tf); return true; } });

                head.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        boolean showing = think.getVisibility() == View.VISIBLE;
                        think.setVisibility(showing ? View.GONE : View.VISIBLE);
                        head.setText(showing ? "\u25B8 已思考" : "\u25BE 已思考");
                    }
                });
                LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                hlp.topMargin = dp(6);
                msgContainer.addView(head, hlp);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                blp.topMargin = dp(4);
                msgContainer.addView(think, blp);
            }

            // 永远隐藏 user 正文里注入的 <system>…</system> 前缀，只显示用户真实输入；
            // 系统提示词每次发送时重新注入，历史里不需要也不应保留（保留会泄露到正常聊天）。
            String rawDisplay = m.body;
            if (user) {
                int end = sysPrefixEnd(m.body);
                if (end > 0) rawDisplay = m.body.substring(end);
            }

            final EditText body = new EditText(act);
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            body.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            body.setPadding(dp(14), dp(10), dp(14), dp(10));
            body.setFocusable(false); body.setFocusableInTouchMode(false); body.setCursorVisible(false);
            body.setLongClickable(true);

            final Drawable normalBg;
            int nText, hlColor, hlText;
            if (user) {
                GradientDrawable g = new GradientDrawable(); g.setColor(uBubble); g.setCornerRadius(dp(16));
                normalBg = g; nText = uText; hlColor = uEdit; hlText = uText;
            } else {
                normalBg = null; nText = text; hlColor = aEdit; hlText = text;
            }
            body.setBackground(normalBg); body.setTextColor(nText);
            body.setMaxWidth(Math.round(act.getResources().getDisplayMetrics().widthPixels * 0.82f));
            final Field bf = registerField(body, "BODY", m.role, m.id, rawDisplay, normalBg, nText, hlText, hlColor);
            body.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) { beginEdit(bf); return true; } });

            LinearLayout wrap = new LinearLayout(act);
            wrap.setOrientation(LinearLayout.HORIZONTAL);
            wrap.setGravity(user ? Gravity.END : Gravity.START);
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wlp.topMargin = dp(8);
            wrap.addView(body, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            msgContainer.addView(wrap, wlp);
        }

        Field registerField(EditText et, String kind, String role, long msgId, String original,
                             Drawable normalBg, int normalText, int hlText, int hlColor) {
            Field f = new Field();
            f.et = et; f.kind = kind; f.role = role; f.msgId = msgId; f.original = original == null ? "" : original;
            f.normalBg = normalBg; f.normalText = normalText; f.hlText = hlText; f.hlColor = hlColor;
            f.render = "BODY".equals(kind) || "THINK".equals(kind);
            if (f.render) f.et.setText(md(f.original)); else f.et.setText(f.original);
            fields.add(f);
            return f;
        }

        // ── 编辑 / 保存 ──────────────────────────────────
        void beginEdit(Field f) {
            if (f.render) f.et.setText(f.original);   // 进入编辑态显示 Markdown 源码而非渲染结果
            GradientDrawable hl = new GradientDrawable();
            hl.setColor(f.hlColor);
            hl.setCornerRadius(dp("TITLE".equals(f.kind) ? 8 : 16));
            f.et.setBackground(hl);
            f.et.setTextColor(f.hlText);
            f.et.setFocusable(true); f.et.setFocusableInTouchMode(true); f.et.setCursorVisible(true);
            f.et.requestFocus();
            f.et.setSelection(f.et.getText().length());
            if (imm != null) imm.showSoftInput(f.et, InputMethodManager.SHOW_IMPLICIT);
        }

        void saveAll() {
            if (sessDb == null || curSid == null) return;
            int n = 0;
            for (Field f : fields) {
                String cur = f.et.getText().toString();
                if (!cur.equals(f.original)) {
                    boolean ok;
                    if ("TITLE".equals(f.kind)) ok = saveTitle(sessDb, curSid, cur);
                    else ok = saveFragment(sessDb, curSid, f.msgId, f.role, f.kind, cur);
                    if (ok) {
                        f.original = cur;
                        n++;
                        if ("TITLE".equals(f.kind) && curSession != null) {
                            curSession.title = cur;
                            rebuildHistoryList();
                        }
                    }
                }
                // 退出编辑态、还原外观；查看态重新渲染 Markdown
                f.et.setBackground(f.normalBg);
                f.et.setTextColor(f.normalText);
                f.et.setFocusable(false); f.et.setFocusableInTouchMode(false); f.et.setCursorVisible(false);
                f.et.clearFocus();
                if (f.render) f.et.setText(md(f.original));
            }
            if (n > 0) {
                stripSysPrompts(sessDb, curSid);        // 抹掉真实对话里显示的 <system> 系统提示词
                freezeSession(sessDb, curSid);          // 防止服务器同步覆盖本地改动
            }
            if (imm != null) imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
            Toast.makeText(act, n > 0 ? ("已保存 " + n + " 处，重启 DeepSeek 生效") : "无改动",
                    Toast.LENGTH_SHORT).show();
        }
    }
}

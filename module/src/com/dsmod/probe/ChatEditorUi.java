package com.dsmod.probe;

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
import android.text.Editable;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
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
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
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

    // 打开编辑器并直接定位到指定会话的指定消息（供全局搜索结果跳转用）
    static void showAt(Activity act, String dbPath, String sid, long msgId) {
        try {
            Ctrl c = new Ctrl(act);
            c.targetDbPath = dbPath; c.targetSid = sid; c.targetMsgId = msgId;
            c.open();
        } catch (Throwable t) {
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

    // 退格回调：光标在包裹标记后按退格时，交给处理器决定是否整段删除
    interface DelHandler { boolean onBackspace(EditText et); }

    // 自定义 EditText：拦截软/硬键盘退格，给智能删除整段 Markdown 一个入口。
    // 软键盘删除多走 InputConnection.deleteSurroundingText(1,0)，不触发 OnKeyListener，故需包裹 IC。
    static final class MdEditText extends EditText {
        DelHandler delHandler;
        MdEditText(Context c) { super(c); }
        @Override public InputConnection onCreateInputConnection(EditorInfo out) {
            InputConnection ic = super.onCreateInputConnection(out);
            if (ic == null) return null;
            return new InputConnectionWrapper(ic, true) {
                @Override public boolean deleteSurroundingText(int before, int after) {
                    if (before == 1 && after == 0 && delHandler != null
                            && getSelectionStart() == getSelectionEnd()
                            && delHandler.onBackspace(MdEditText.this)) return true;
                    return super.deleteSurroundingText(before, after);
                }
                @Override public boolean sendKeyEvent(KeyEvent event) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN
                            && event.getKeyCode() == KeyEvent.KEYCODE_DEL
                            && delHandler != null
                            && getSelectionStart() == getSelectionEnd()
                            && delHandler.onBackspace(MdEditText.this)) return true;
                    return super.sendKeyEvent(event);
                }
            };
        }
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

    // 本会话消息表是否存在且有行（跨账号库判定用）
    static boolean hasMessageRows(String dbPath, String sid) {
        SQLiteDatabase d = null; Cursor c = null;
        try {
            d = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY);
            c = d.rawQuery("SELECT count(*) FROM 'chat_session_messages_" + sid + "'", null);
            return c.moveToFirst() && c.getLong(0) > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) c.close();
            if (d != null) try { d.close(); } catch (Throwable ignored) {}
        }
    }

    // 找真正存放本会话消息的库：优先本会话所在库；为空则跨账号库搜同名消息表。
    // 多账号下会话列表行与消息表可能落在不同 db 文件，直接用列表库读会显示"无本地消息"。
    static String resolveMessagesDb(String sid, String preferredPath) {
        if (hasMessageRows(preferredPath, sid)) return preferredPath;
        for (File f : allDbs()) {
            String p = f.getPath();
            if (p.equals(preferredPath)) continue;
            if (hasMessageRows(p, sid)) return p;
        }
        return preferredPath;
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

    static boolean hasNumericFragmentId(JSONObject fragment) {
        if (fragment == null) return false;
        Object id = fragment.opt("id");
        return id instanceof Number;
    }

    static int nextFragmentId(JSONArray fragments) {
        long max = 0;
        for (int i = 0; i < fragments.length(); i++) {
            JSONObject fragment = fragments.optJSONObject(i);
            if (!hasNumericFragmentId(fragment)) continue;
            long id = ((Number) fragment.opt("id")).longValue();
            if (id > max && id <= Integer.MAX_VALUE) max = id;
        }
        if (max < Integer.MAX_VALUE) return (int) max + 1;

        // 极端情况下已有片段用了 int 上限，从 1 开始找一个未占用的正整数。
        for (int candidate = 1; candidate < Integer.MAX_VALUE; candidate++) {
            boolean used = false;
            for (int i = 0; i < fragments.length(); i++) {
                JSONObject fragment = fragments.optJSONObject(i);
                if (hasNumericFragmentId(fragment)
                        && ((Number) fragment.opt("id")).longValue() == candidate) {
                    used = true;
                    break;
                }
            }
            if (!used) return candidate;
        }
        return Integer.MAX_VALUE;
    }

    // DeepSeek 的 ThinkFragment 序列化器把 id 和 content 都声明为必填字段。
    // 旧版编辑器只写了 type/content；修正后宿主才能反序列化整个 fragments 数组。
    static boolean repairMissingThinkFragmentIds(JSONArray fragments) {
        boolean changed = false;
        for (int i = 0; i < fragments.length(); i++) {
            JSONObject fragment = fragments.optJSONObject(i);
            if (fragment == null || !"THINK".equals(fragment.optString("type"))
                    || hasNumericFragmentId(fragment)) continue;
            try {
                fragment.put("id", nextFragmentId(fragments));
                changed = true;
            } catch (Throwable ignored) {}
        }
        return changed;
    }

    static boolean hasThinkContent(JSONArray fragments) {
        for (int i = 0; i < fragments.length(); i++) {
            JSONObject fragment = fragments.optJSONObject(i);
            if (fragment != null && "THINK".equals(fragment.optString("type"))
                    && fragment.optString("content", "").trim().length() > 0) return true;
        }
        return false;
    }

    // 纯 JSON 变换，数据库保存和回归测试共用，避免两套“新增 THINK”规则漂移。
    static JSONArray upsertFragmentContent(JSONArray fragments, String role, String kind, String text)
            throws org.json.JSONException {
        repairMissingThinkFragmentIds(fragments);
        String value = text == null ? "" : text;
        String[] targets = "THINK".equals(kind) ? new String[]{"THINK"}
                : ("USER".equals(role) ? new String[]{"REQUEST"}
                : new String[]{"RESPONSE", "TEMPLATE_RESPONSE"});
        boolean done = false;
        outer:
        for (String target : targets) {
            for (int i = 0; i < fragments.length(); i++) {
                JSONObject fragment = fragments.optJSONObject(i);
                if (fragment != null && target.equals(fragment.optString("type"))) {
                    fragment.put("content", value);
                    done = true;
                    break outer;
                }
            }
        }

        if (!done && "THINK".equals(kind)) {
            if (value.trim().length() == 0) return null;
            JSONObject think = new JSONObject();
            think.put("id", nextFragmentId(fragments));
            think.put("type", "THINK");
            think.put("content", value);
            JSONArray withThink = new JSONArray();
            withThink.put(think);
            for (int i = 0; i < fragments.length(); i++) withThink.put(fragments.get(i));
            return withThink;
        }
        return done ? fragments : null;
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
            JSONArray a = upsertFragmentContent(new JSONArray(frag), role, kind, text);
            if (a == null) return false;
            if (hasThinkContent(a)) {
                db.execSQL("UPDATE '" + t + "' SET fragments=?, thinking_enabled=1 WHERE message_id=?",
                        new Object[]{a.toString(), msgId});
            } else {
                db.execSQL("UPDATE '" + t + "' SET fragments=? WHERE message_id=?",
                        new Object[]{a.toString(), msgId});
            }
            return true;
        } catch (Throwable t2) { return false; }
    }

    static boolean saveTitle(SQLiteDatabase db, String sid, String title) {
        try { db.execSQL("UPDATE chat_session_list SET title=? WHERE id=?", new Object[]{title, sid}); return true; }
        catch (Throwable t) { return false; }
    }

    static String quoteIdent(String name) {
        return "\"" + (name == null ? "" : name.replace("\"", "\"\"")) + "\"";
    }

    // 本地删除一个会话：从 chat_session_list 删行 + DROP 其消息表。供侧栏多选删除兜底用。
    static boolean deleteSessionLocal(SQLiteDatabase db, String sid) {
        if (db == null || sid == null || sid.length() == 0) return false;
        String t = "chat_session_messages_" + sid;
        db.beginTransaction();
        try {
            db.delete("chat_session_list", "id=?", new String[]{sid});
            db.execSQL("DROP TABLE IF EXISTS " + quoteIdent(t));
            db.setTransactionSuccessful();
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            try { db.endTransaction(); } catch (Throwable ignored) {}
        }
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

    // 修复旧版编辑器已经落库的无 id THINK。只更新确实命中该坏格式的助手消息，
    // 同时补齐消息级 thinking_enabled；原 RESPONSE 内容和已有 fragment id 均保持不变。
    static int repairMalformedThinkFragments(SQLiteDatabase db, String sid) {
        String t = "chat_session_messages_" + sid;
        List<Object[]> updates = new ArrayList<>();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT message_id, fragments FROM '" + t
                            + "' WHERE role=? AND fragments LIKE ?",
                    new String[]{"ASSISTANT", "%\"type\":\"THINK\"%"});
            while (c.moveToNext()) {
                long mid = c.getLong(0);
                String frag = c.getString(1);
                if (frag == null) continue;
                try {
                    JSONArray a = new JSONArray(frag);
                    if (repairMissingThinkFragmentIds(a)) {
                        updates.add(new Object[]{a.toString(), mid});
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        } finally { if (c != null) c.close(); }

        int n = 0;
        for (Object[] update : updates) {
            try {
                db.execSQL("UPDATE '" + t
                                + "' SET fragments=?, thinking_enabled=1 WHERE message_id=?",
                        update);
                n++;
            } catch (Throwable ignored) {}
        }
        return n;
    }

    // 模块升级后自动遍历所有账号库，恢复已经被旧版写坏的会话，无需用户再次编辑。
    static int repairMalformedThinkFragmentsAllSessions() {
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
                for (String sid : sids) {
                    int fixed = repairMalformedThinkFragments(db, sid);
                    if (fixed > 0) {
                        total += fixed;
                        freezeSession(db, sid);
                    }
                }
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
        Field activeField;                    // 当前正在编辑的字段（Markdown 工具栏插入目标）
        String targetSid, targetDbPath; long targetMsgId = -1;   // 搜索跳转目标
        final Map<Long, View> msgAnchors = new HashMap<>();      // msgId → 消息行视图（跳转定位用）
        final DelHandler delHandler = new DelHandler() {
            public boolean onBackspace(EditText et) { return smartDeleteWrap(et); }
        };

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
            Session initial = null;
            if (targetSid != null) {
                initial = findSession(targetSid, targetDbPath);
                if (initial == null) {          // 目标不在当前账号列表 → 切到显示所有账号再找
                    showAll = true; updateAccountBtn(); loadSessionList();
                    initial = findSession(targetSid, targetDbPath);
                }
            }
            if (initial == null && !sessions.isEmpty()) initial = sessions.get(0);
            if (initial != null) selectSession(initial);

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
            MdEditText titleMd = new MdEditText(act);
            titleMd.delHandler = delHandler;
            titleEt = titleMd;
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

            // Markdown 格式化展开箭头（保存左侧）：与"帮助与反馈"手风琴同款 › 箭头，旋转 90° 指向下表示向下展开
            TextView mdArrow = new TextView(act);
            mdArrow.setText("\u203A");
            mdArrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            mdArrow.setTextColor(text);
            mdArrow.setGravity(Gravity.CENTER);
            mdArrow.setPadding(dp(8), 0, dp(8), 0);
            mdArrow.setRotation(90f);
            mdArrow.setClickable(true);
            mdArrow.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { showMarkdownMenu(); } });
            LinearLayout.LayoutParams arlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
            arlp.rightMargin = dp(8);
            top.addView(mdArrow, arlp);

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
                    if (titleEt.isFocusable()) return false;   // 已进编辑态 → 放行原生选字/复制
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

        Session findSession(String sid, String dbPath) {
            if (sid == null) return null;
            for (Session s : sessions) if (sid.equals(s.id) && dbPath != null && dbPath.equals(s.dbPath)) return s;
            for (Session s : sessions) if (sid.equals(s.id)) return s;
            return null;
        }

        void selectSession(Session s) {
            try { if (sessDb != null) sessDb.close(); } catch (Throwable ignored) {}
            String msgDbPath = resolveMessagesDb(s.id, s.dbPath);
            sessDb = SQLiteDatabase.openDatabase(msgDbPath, null, SQLiteDatabase.OPEN_READWRITE);
            curSid = s.id; curSession = s;
            String title = (s.title != null && s.title.trim().length() > 0) ? s.title : "";
            titleEt.setText(title.length() > 0 ? title : "未命名对话");
            curThread = loadThread(sessDb, s.id);
            renderThread();
        }

        // 渲染当前会话（不重新查库）；user 正文永远切掉注入的 <system> 前缀
        void renderThread() {
            fields.clear();
            msgAnchors.clear();
            msgContainer.removeAllViews();
            String title = (curSession != null && curSession.title != null) ? curSession.title : "";
            registerField(titleEt, "TITLE", null, 0, title, null, text, text, aEdit);

            if (curThread == null || curThread.isEmpty()) {
                addPlaceholder("此对话没有本地消息\n（可能尚未下载到本机——先在 DeepSeek 里打开该对话让它同步，再回来编辑）");
                return;
            }
            for (Msg m : curThread) addMessage(m);
            // 有搜索跳转目标且正是本会话 → 滚到目标消息并高亮；否则滚到底
            if (targetMsgId >= 0 && curSid != null && curSid.equals(targetSid)) {
                final long mid = targetMsgId;
                targetMsgId = -1;
                msgScroll.post(new Runnable() { public void run() { scrollToMessage(mid); } });
            } else {
                msgScroll.post(new Runnable() { public void run() { msgScroll.fullScroll(View.FOCUS_DOWN); } });
            }
        }

        void scrollToMessage(long msgId) {
            final View v = msgAnchors.get(msgId);
            if (v == null) return;
            msgScroll.smoothScrollTo(0, Math.max(0, v.getTop() - dp(40)));
            final Drawable orig = v.getBackground();
            GradientDrawable g = new GradientDrawable();
            g.setColor(aEdit); g.setCornerRadius(dp(12));
            v.setBackground(g);
            v.postDelayed(new Runnable() { public void run() { v.setBackground(orig); } }, 1400);
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

            // 助手消息一律显示思考区：即使原本没有思考内容（未开深度思考），也能长按创建。
            if (!user) {
                final boolean hasThink = m.think != null && m.think.trim().length() > 0;
                final String label = hasThink ? "已思考" : "添加思考内容";
                final TextView head = new TextView(act);
                head.setText("\u25B8 " + label);
                head.setTextColor(sub); head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                head.setPadding(dp(4), dp(6), dp(4), dp(4)); head.setClickable(true);

                final MdEditText think = new MdEditText(act);
                think.delHandler = delHandler;
                think.setText(m.think == null ? "" : m.think);
                think.setHint("在此输入思考内容（长按进入编辑）");
                think.setTextColor(sub); think.setHintTextColor(sub);
                think.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                think.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                think.setMinLines(hasThink ? 1 : 2);
                GradientDrawable tb = new GradientDrawable(); tb.setColor(thinkBg); tb.setCornerRadius(dp(12));
                think.setBackground(tb);
                think.setPadding(dp(12), dp(10), dp(12), dp(10));
                think.setFocusable(false); think.setFocusableInTouchMode(false); think.setCursorVisible(false);
                think.setLongClickable(true);
                think.setVisibility(hasThink ? View.GONE : View.VISIBLE);   // 空思考默认展开，方便直接创建
                final Field tf = registerField(think, "THINK", m.role, m.id, m.think == null ? "" : m.think, tb, sub, sub, thinkEdit);
                think.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) {
                        if (tf.et.isFocusable()) return false;   // 已进编辑态 → 放行原生选字/复制
                        beginEdit(tf); return true;
                    } });

                head.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        boolean showing = think.getVisibility() == View.VISIBLE;
                        think.setVisibility(showing ? View.GONE : View.VISIBLE);
                        head.setText((showing ? "\u25B8 " : "\u25BE ") + label);
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

            final MdEditText body = new MdEditText(act);
            body.delHandler = delHandler;
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
                public boolean onLongClick(View v) {
                    if (bf.et.isFocusable()) return false;   // 已进编辑态 → 放行原生选字/复制
                    beginEdit(bf); return true;
                } });

            LinearLayout wrap = new LinearLayout(act);
            wrap.setOrientation(LinearLayout.HORIZONTAL);
            wrap.setGravity(user ? Gravity.END : Gravity.START);
            LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wlp.topMargin = dp(8);
            wrap.addView(body, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            msgContainer.addView(wrap, wlp);
            msgAnchors.put(m.id, wrap);
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
            activeField = f;
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
            activeField = null;
            if (n > 0) {
                stripSysPrompts(sessDb, curSid);        // 抹掉真实对话里显示的 <system> 系统提示词
                freezeSession(sessDb, curSid);          // 防止服务器同步覆盖本地改动
            }
            if (imm != null) imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
            Toast.makeText(act, n > 0 ? ("已保存 " + n + " 处，重启 DeepSeek 生效") : "无改动",
                    Toast.LENGTH_SHORT).show();
        }

        // ── Markdown 格式化工具栏 ─────────────────────────
        void showMarkdownMenu() {
            if (activeField == null || activeField.et == null || !activeField.et.isFocusable()) {
                Toast.makeText(act, "请先长按一条消息进入编辑，再插入格式", Toast.LENGTH_SHORT).show();
                return;
            }
            final Dialog sheet = new Dialog(act);
            sheet.requestWindowFeature(Window.FEATURE_NO_TITLE);

            ScrollView sv = new ScrollView(act);
            LinearLayout list = new LinearLayout(act);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setPadding(dp(6), dp(6), dp(6), dp(10));
            GradientDrawable sheetBg = new GradientDrawable();
            sheetBg.setColor(bar); sheetBg.setCornerRadius(dp(18));
            list.setBackground(sheetBg);

            TextView h = new TextView(act);
            h.setText("插入 Markdown 格式");
            h.setTextColor(text); h.setTypeface(Typeface.DEFAULT_BOLD);
            h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            h.setPadding(dp(14), dp(12), dp(14), dp(10));
            list.addView(h);

            addMdEntry(list, sheet, "加粗文字",     16, Typeface.BOLD,        false, false, "bold");
            addMdEntry(list, sheet, "斜体文字",     16, Typeface.ITALIC,      false, false, "italic");
            addMdEntry(list, sheet, "粗斜体文字",   16, Typeface.BOLD_ITALIC, false, false, "bolditalic");
            addMdEntry(list, sheet, "删除线文字",   16, Typeface.NORMAL,      false, true,  "strike");
            addMdEntry(list, sheet, "行内代码",     16, Typeface.NORMAL,      true,  false, "code");
            addMdEntry(list, sheet, "代码块",       16, Typeface.NORMAL,      true,  false, "codeblock");
            addMdEntry(list, sheet, "一级标题",     23, Typeface.BOLD,        false, false, "h1");
            addMdEntry(list, sheet, "二级标题",     21, Typeface.BOLD,        false, false, "h2");
            addMdEntry(list, sheet, "三级标题",     19, Typeface.BOLD,        false, false, "h3");
            addMdEntry(list, sheet, "四级标题",     17, Typeface.BOLD,        false, false, "h4");
            addMdEntry(list, sheet, "五级标题",     15, Typeface.BOLD,        false, false, "h5");
            addMdEntry(list, sheet, "六级标题",     14, Typeface.BOLD,        false, false, "h6");
            addMdEntry(list, sheet, "\u2022 无序列表", 16, Typeface.NORMAL,   false, false, "ul");
            addMdEntry(list, sheet, "1. 有序列表",  16, Typeface.NORMAL,      false, false, "ol");
            addMdEntry(list, sheet, "引用文字",     16, Typeface.ITALIC,      false, false, "quote");
            addMdEntry(list, sheet, "分割线 \u2500\u2500\u2500", 16, Typeface.NORMAL, false, false, "hr");
            addMdEntry(list, sheet, "链接",         16, Typeface.NORMAL,      false, false, "link");
            addMdEntry(list, sheet, "图片",         16, Typeface.NORMAL,      false, false, "image");

            sv.addView(list);
            sheet.setContentView(sv);
            Window w = sheet.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(0x00000000));
                w.setLayout((int) (act.getResources().getDisplayMetrics().widthPixels * 0.86f),
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                w.setGravity(Gravity.CENTER);
            }
            sheet.show();
        }

        void addMdEntry(LinearLayout parent, final Dialog sheet, String label, float sizeSp,
                        int style, boolean mono, boolean strike, final String kind) {
            TextView tv = new TextView(act);
            tv.setText(label);
            tv.setTextColor(text);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
            if (mono) tv.setTypeface(Typeface.MONOSPACE, style);
            else tv.setTypeface(Typeface.defaultFromStyle(style));
            if (strike) tv.setPaintFlags(tv.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            tv.setPadding(dp(16), dp(11), dp(16), dp(11));
            tv.setClickable(true);
            tv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { sheet.dismiss(); onMdPick(kind); } });
            parent.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void onMdPick(String kind) {
            if ("bold".equals(kind))        promptWrap("加粗",   "**",  "**");
            else if ("italic".equals(kind)) promptWrap("斜体",   "*",   "*");
            else if ("bolditalic".equals(kind)) promptWrap("粗斜体", "***", "***");
            else if ("strike".equals(kind)) promptWrap("删除线", "~~",  "~~");
            else if ("code".equals(kind))   promptWrap("行内代码", "`",  "`");
            else if ("codeblock".equals(kind)) promptCodeBlock();
            else if ("h1".equals(kind)) promptPrefix("一级标题", "# ");
            else if ("h2".equals(kind)) promptPrefix("二级标题", "## ");
            else if ("h3".equals(kind)) promptPrefix("三级标题", "### ");
            else if ("h4".equals(kind)) promptPrefix("四级标题", "#### ");
            else if ("h5".equals(kind)) promptPrefix("五级标题", "##### ");
            else if ("h6".equals(kind)) promptPrefix("六级标题", "###### ");
            else if ("ul".equals(kind)) promptPrefix("无序列表", "- ");
            else if ("ol".equals(kind)) promptPrefix("有序列表", "1. ");
            else if ("quote".equals(kind)) promptPrefix("引用", "> ");
            else if ("hr".equals(kind)) insertLine("---", "");
            else if ("link".equals(kind)) promptLink(false);
            else if ("image".equals(kind)) promptLink(true);
        }

        EditText mkInput(String hint) {
            EditText e = new EditText(act);
            e.setHint(hint);
            e.setTextColor(text);
            e.setHintTextColor(sub);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            return e;
        }

        LinearLayout dialogBox(EditText... inputs) {
            LinearLayout box = new LinearLayout(act);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(20), dp(8), dp(20), dp(4));
            for (EditText e : inputs) box.addView(e,
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
            return box;
        }

        void promptWrap(String title, final String open, final String close) {
            final EditText in = mkInput("内容");
            new AlertDialog.Builder(act).setTitle("插入" + title)
                    .setView(dialogBox(in))
                    .setPositiveButton("完成", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int wi) {
                            insertAtCursor(open + in.getText().toString() + close, -1);
                        } })
                    .setNegativeButton("取消", null).show();
        }

        void promptPrefix(final String title, final String prefix) {
            final EditText in = mkInput("内容");
            new AlertDialog.Builder(act).setTitle("插入" + title)
                    .setView(dialogBox(in))
                    .setPositiveButton("完成", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int wi) {
                            insertLine(prefix, in.getText().toString());
                        } })
                    .setNegativeButton("取消", null).show();
        }

        void promptCodeBlock() {
            final EditText lang = mkInput("语言（可留空，如 java）");
            lang.setInputType(InputType.TYPE_CLASS_TEXT);
            final EditText code = mkInput("代码");
            new AlertDialog.Builder(act).setTitle("插入代码块")
                    .setView(dialogBox(lang, code))
                    .setPositiveButton("完成", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int wi) {
                            String l = lang.getText().toString().trim();
                            String body = "```" + l + "\n" + code.getText().toString() + "\n```\n";
                            insertLine(body, "");
                        } })
                    .setNegativeButton("取消", null).show();
        }

        void promptLink(final boolean image) {
            final EditText txt = mkInput(image ? "图片描述（可留空）" : "显示文字");
            txt.setInputType(InputType.TYPE_CLASS_TEXT);
            final EditText url = mkInput("链接地址");
            url.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
            new AlertDialog.Builder(act).setTitle(image ? "插入图片" : "插入链接")
                    .setView(dialogBox(txt, url))
                    .setPositiveButton("完成", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int wi) {
                            String t = txt.getText().toString();
                            String u = url.getText().toString();
                            insertAtCursor((image ? "![" : "[") + t + "](" + u + ")", -1);
                        } })
                    .setNegativeButton("取消", null).show();
        }

        // 在当前编辑字段光标处插入文本；caret<0 则光标落到插入内容末尾
        void insertAtCursor(String s, int caret) {
            Field f = activeField;
            if (f == null || f.et == null) return;
            if (!f.et.isFocusable()) { f.et.setFocusable(true); f.et.setFocusableInTouchMode(true); }
            Editable e = f.et.getText();
            int start = Math.max(0, f.et.getSelectionStart());
            int end = Math.max(start, f.et.getSelectionEnd());
            if (start > e.length()) start = e.length();
            if (end > e.length()) end = e.length();
            e.replace(start, end, s);
            int pos = caret >= 0 ? start + caret : start + s.length();
            if (pos > f.et.getText().length()) pos = f.et.getText().length();
            f.et.requestFocus();
            f.et.setSelection(pos);
            f.et.setCursorVisible(true);
            if (imm != null) imm.showSoftInput(f.et, InputMethodManager.SHOW_IMPLICIT);
        }

        // 行级插入：若光标不在行首，先补换行，保证标题/列表/引用/代码块独占一行
        void insertLine(String prefix, String content) {
            Field f = activeField;
            if (f == null || f.et == null) return;
            Editable e = f.et.getText();
            int start = Math.max(0, Math.min(f.et.getSelectionStart(), e.length()));
            String lead = (start > 0 && e.charAt(start - 1) != '\n') ? "\n" : "";
            insertAtCursor(lead + prefix + content, -1);
        }

        // 智能删除：光标紧跟在某包裹标记右侧按退格 → 连同内容与两侧标记整段删除
        boolean smartDeleteWrap(EditText et) {
            if (et == null || !et.isFocusable()) return false;
            int s = et.getSelectionStart();
            if (s != et.getSelectionEnd() || s <= 0) return false;
            String txt = et.getText().toString();
            String[] marks = {"```", "***", "**", "~~", "`", "*"};
            for (String close : marks) {
                int cl = close.length();
                if (s < cl) continue;
                if (!txt.substring(s - cl, s).equals(close)) continue;
                int contentEnd = s - cl;
                int openStart = txt.lastIndexOf(close, contentEnd - 1);
                if (openStart >= 0 && openStart + cl <= contentEnd) {
                    et.getText().delete(openStart, s);
                    et.setSelection(openStart);
                    return true;
                }
            }
            return false;
        }
    }
}

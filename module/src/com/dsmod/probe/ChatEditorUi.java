package com.dsmod.probe;

import android.app.Activity;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    private static final String LOCAL_SESSION_DIR =
            "/data/data/com.deepseek.chat/files/deekseep_local_sessions";
    private static final Object LOCAL_SESSION_LOCK = new Object();
    private static final Object CREATE_SESSION_LOCK = new Object();
    private static final HashMap<String, Object[]> RECENT_BLANK_CREATES = new HashMap<>();
    private static final long CREATE_SESSION_DEBOUNCE_MS = 2500L;

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

    // 若 user 正文以注入的 <system>…</system> 包裹开头，返回全部连续包裹的结束下标，否则 -1。
    static int sysPrefixEnd(String body) {
        if (body == null) return -1;
        String safe = HistoryBridge.stripInjectedSystemPrompts(body);
        return safe.equals(body) ? -1 : body.length() - safe.length();
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
    static final class Session {
        String id;
        String title;
        String dbPath;
        String account;
        String model;
        int cacheVersion;
        double updatedAt;
        boolean nativeOnly;
    }

    static final class ImageAsset {
        final String key;
        final String label;
        final JSONObject file;
        final String sourceModel;

        ImageAsset(JSONObject file) {
            this(file, null);
        }

        ImageAsset(JSONObject file, String sourceModel) {
            this.file = cloneObject(file);
            this.key = imageKey(this.file);
            String name = this.file.optString("file_name", "").trim();
            this.label = name.length() > 0 ? name : "已上传图片";
            this.sourceModel = sourceModel;
        }

        ImageAsset(ImageAsset source) {
            this(source == null ? null : source.file,
                    source == null ? null : source.sourceModel);
        }
    }

    static final class Msg {
        long id;
        String role;
        String think = "";
        String body = "";
        String rawFragments = "";
        final List<ImageAsset> images = new ArrayList<>();
        Float thinkElapsedSecs;
    }

    // 可编辑字段（消息正文 / 思考 / 标题），批量保存时逐个比对提交
    static final class Field {
        EditText et; String kind; String role; long msgId; String original;
        Drawable normalBg; int normalText, hlText; int hlColor;
        boolean render;             // 查看态是否渲染 Markdown（BODY/THINK 为 true，TITLE 为 false）
        boolean edited;             // 只有真正进入过编辑态的字段才允许回写源码
    }

    static final class ImageEdit {
        long msgId;
        final List<ImageAsset> selected = new ArrayList<>();
        String originalSignature;
        TextView summary;
        boolean edited;
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

    static String currentAccountId(ClassLoader cl) {
        try {
            Class<?> mmkv = Class.forName("com.tencent.mmkv.MMKV", false, cl);
            for (java.lang.reflect.Method method : mmkv.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())
                        || method.getParameterTypes().length != 0
                        || method.getReturnType() != mmkv) continue;
                try {
                    method.setAccessible(true);
                    Object value = method.invoke(null);
                    if (!(value instanceof android.content.SharedPreferences)) continue;
                    String json = ((android.content.SharedPreferences) value)
                            .getString("key_user_info", null);
                    if (json == null) continue;
                    String id = new JSONObject(json).optString("id", null);
                    if (id != null && id.length() > 0) return id;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    static File currentDb(ClassLoader cl) {
        String currentId = currentAccountId(cl);
        if (currentId != null) {
            for (File f : allDbs()) if (currentId.equals(uuidOf(f))) return f;
        }
        File best = null;
        for (File f : allDbs()) if (best == null || f.lastModified() > best.lastModified()) best = f;
        return best;
    }

    static File currentDb() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = ChatEditorUi.class.getClassLoader();
        return currentDb(cl);
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

    // DeepSeek 2.2.2 有时只把在线历史放进 pw0/tp 内存而不建消息表。
    // 用宿主 uo.O() 生成的完整行快照重建同一条父链，供编辑器只读展示。
    static List<Msg> loadSnapshotThread(HistoryBridge.Snapshot snapshot) {
        if (snapshot == null || snapshot.rows == null) return new ArrayList<>();
        Map<Long, Msg> map = new HashMap<>();
        Map<Long, Long> parent = new HashMap<>();
        long maxId = -1;
        for (HistoryBridge.Row row : snapshot.rows) {
            Msg m = new Msg();
            m.id = row.messageId;
            m.role = row.role == null ? "" : row.role;
            parseFragments(m, row.fragments);
            map.put(m.id, m);
            parent.put(m.id, row.parentId == null ? null : row.parentId.longValue());
            if (m.id > maxId) maxId = m.id;
        }
        Long cur = snapshot.currentMessageId == null ? null : snapshot.currentMessageId.longValue();
        if (cur == null || !map.containsKey(cur)) cur = maxId >= 0 ? maxId : null;
        LinkedList<Msg> thread = new LinkedList<>();
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        while (cur != null && map.containsKey(cur) && seen.add(cur)) {
            thread.addFirst(map.get(cur));
            Long p = parent.get(cur);
            cur = p != null && p > 0 ? p : null;
        }
        return thread;
    }

    static boolean tableHasRows(SQLiteDatabase db, String sid) {
        if (db == null || !validSid(sid)) return false;
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT 1 FROM " + quoteIdent("chat_session_messages_" + sid)
                    + " LIMIT 1", null);
            return c.moveToFirst();
        } catch (Throwable ignored) {
            return false;
        } finally { if (c != null) c.close(); }
    }

    static boolean sessionRowExists(SQLiteDatabase db, String sid) {
        if (db == null || sid == null) return false;
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT 1 FROM chat_session_list WHERE id=? LIMIT 1",
                    new String[]{sid});
            return c.moveToFirst();
        } catch (Throwable ignored) {
            return false;
        } finally { if (c != null) c.close(); }
    }

    static boolean validSid(String sid) {
        if (sid == null || sid.length() == 0 || sid.length() > 128) return false;
        for (int i = 0; i < sid.length(); i++) {
            char c = sid.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_')) return false;
        }
        return true;
    }

    // 仅在用户确实保存消息修改时物化在线快照；IGNORE 保住宿主并发落库和既有本地编辑。
    static boolean materializeSnapshot(SQLiteDatabase db, String sid, HistoryBridge.Snapshot snapshot) {
        if (db == null || snapshot == null || !snapshot.complete || snapshot.version == null
                || !validSid(sid) || !sid.equals(snapshot.sid)) return false;
        if (snapshot.currentMessageId != null) {
            boolean found = false;
            for (HistoryBridge.Row row : snapshot.rows) {
                if (row.messageId == snapshot.currentMessageId.intValue()) { found = true; break; }
            }
            if (!found) return false;
        }

        String table = quoteIdent("chat_session_messages_" + sid);
        synchronized (HistoryBridge.snapshotLock()) {
            if (!HistoryBridge.isCurrentSnapshot(snapshot)) return false;
            boolean ownTransaction = !db.inTransaction();
            Cursor c = null;
            try {
            if (ownTransaction) db.beginTransaction();
            // Recheck under the write transaction. If the host won the race, the editor's UI
            // snapshot is stale and must be re-opened instead of being applied to the new table.
            if (tableHasRows(db, sid)) return false;
            c = db.rawQuery("SELECT cache_version FROM chat_session_list WHERE id=?", new String[]{sid});
            if (c.moveToFirst() && !c.isNull(0) && snapshot.version != null) {
                int localVersion = c.getInt(0);
                // FREEZE_VERSION is allowed so builds affected by the old title-only bug heal.
                if (localVersion != FREEZE_VERSION && localVersion > snapshot.version.intValue()) {
                    return false;
                }
            }
            c.close(); c = null;
            db.execSQL("CREATE TABLE IF NOT EXISTS " + table
                    + "(message_id INTEGER PRIMARY KEY NOT NULL, parent_id INTEGER, role TEXT,"
                    + " thinking_enabled INTEGER, status TEXT, inserted_at REAL, feedback_type TEXT,"
                    + " accumulated_token_usage INTEGER, ban_edit INTEGER, ban_regenerate INTEGER,"
                    + " tips TEXT, fragments TEXT, conversation_mode TEXT)");
            for (HistoryBridge.Row row : snapshot.rows) {
                db.execSQL("INSERT OR IGNORE INTO " + table
                                + "(message_id,parent_id,role,thinking_enabled,status,inserted_at,"
                                + "feedback_type,accumulated_token_usage,ban_edit,ban_regenerate,tips,"
                                + "fragments,conversation_mode) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        new Object[]{row.messageId, row.parentId, row.role,
                                row.thinkingEnabled == null ? null : (row.thinkingEnabled ? 1 : 0),
                                row.status, row.insertedAt, row.feedbackType,
                                row.accumulatedTokenUsage, row.banEdit ? 1 : 0,
                                row.banRegenerate ? 1 : 0, row.tips, row.fragments,
                                row.conversationMode});
            }
            if (snapshot.currentMessageId != null) {
                db.execSQL("UPDATE chat_session_list SET current_message_id=?"
                                + " WHERE id=?",
                        new Object[]{snapshot.currentMessageId, sid});
            }
            // Claim this session before releasing the transaction so an in-flight host writer
            // cannot replace the newly materialised baseline between here and the field edits.
            db.execSQL("UPDATE chat_session_list SET cache_version=? WHERE id=?",
                    new Object[]{FREEZE_VERSION, sid});
            if (ownTransaction) db.setTransactionSuccessful();
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
            if (ownTransaction) try { db.endTransaction(); } catch (Throwable ignored) {}
        }
        }
    }

    private static void parseFragments(Msg m, String frag) {
        if (frag == null || frag.length() == 0) return;
        m.rawFragments = frag;
        try {
            JSONArray a = new JSONArray(frag);
            StringBuilder think = new StringBuilder();
            String resp = null, tmpl = null, req = null;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String type = o.optString("type");
                String content = o.optString("content");
                if ("THINK".equals(type)) {
                    if (think.length() > 0) think.append("\n\n");
                    think.append(content);
                    if (m.thinkElapsedSecs == null && !o.isNull("elapsed_secs")) {
                        double elapsed = o.optDouble("elapsed_secs", Double.NaN);
                        if (!Double.isNaN(elapsed) && !Double.isInfinite(elapsed) && elapsed >= 0) {
                            m.thinkElapsedSecs = Float.valueOf((float) elapsed);
                        }
                    }
                }
                else if ("RESPONSE".equals(type)) resp = content;
                else if ("TEMPLATE_RESPONSE".equals(type)) tmpl = content;
                else if ("REQUEST".equals(type)) req = content;
                else if ("FILE".equals(type)) {
                    JSONArray files = o.optJSONArray("files");
                    if (files != null) for (int j = 0; j < files.length(); j++) {
                        JSONObject file = files.optJSONObject(j);
                        if (isImageFile(file)) m.images.add(new ImageAsset(file));
                    }
                }
            }
            m.think = think.toString();
            if ("USER".equals(m.role)) m.body = req != null ? req : "";
            else m.body = resp != null ? resp : (tmpl != null ? tmpl : "");
        } catch (Throwable ignored) {}
    }

    static JSONObject cloneObject(JSONObject value) {
        if (value == null) return new JSONObject();
        try { return new JSONObject(value.toString()); }
        catch (Throwable ignored) { return new JSONObject(); }
    }

    static boolean isImageFile(JSONObject file) {
        return file != null && file.optBoolean("is_image", false);
    }

    static boolean isReusableImageFile(JSONObject file) {
        return isImageFile(file)
                && "SUCCESS".equals(file.optString("status", ""))
                && file.optString("signed_path", "").length() > 0;
    }

    static String imageKey(JSONObject file) {
        if (file == null) return "";
        String signed = file.optString("signed_path", "");
        if (signed.length() > 0) return "path:" + signed;
        String id = file.optString("id", "");
        if (id.length() > 0) return "id:" + id;
        return "file:" + file.optString("file_name", "") + ":"
                + file.optLong("file_size", -1L) + ":" + file.optString("updated_at", "");
    }

    static List<ImageAsset> extractImageFiles(JSONArray fragments) {
        ArrayList<ImageAsset> out = new ArrayList<>();
        if (fragments == null) return out;
        for (int i = 0; i < fragments.length(); i++) {
            JSONObject fragment = fragments.optJSONObject(i);
            if (fragment == null || !"FILE".equals(fragment.optString("type"))) continue;
            JSONArray files = fragment.optJSONArray("files");
            if (files == null) continue;
            for (int j = 0; j < files.length(); j++) {
                JSONObject file = files.optJSONObject(j);
                if (isImageFile(file)) out.add(new ImageAsset(file));
            }
        }
        return out;
    }

    static String imageSelectionSignature(List<ImageAsset> images) {
        StringBuilder out = new StringBuilder();
        if (images != null) for (ImageAsset image : images) {
            if (out.length() > 0) out.append('\n');
            out.append(image == null ? "" : image.key);
        }
        return out.toString();
    }

    /**
     * Replaces only image descriptors inside FILE fragments. Other fragments and non-image
     * attachments stay byte-for-byte equivalent at the JSON value level, and the input array is
     * never mutated. If the message had no FILE fragment, the new one is inserted before REQUEST.
     */
    static JSONArray replaceImageFiles(JSONArray original, List<JSONObject> selected)
            throws org.json.JSONException {
        JSONArray source = original == null ? new JSONArray() : original;
        JSONArray copy = new JSONArray(source.toString());
        ArrayList<JSONObject> chosen = new ArrayList<>();
        if (selected != null) for (JSONObject file : selected) {
            if (isImageFile(file)) chosen.add(cloneObject(file));
        }

        int firstFile = -1;
        for (int i = 0; i < copy.length(); i++) {
            JSONObject fragment = copy.optJSONObject(i);
            if (fragment != null && "FILE".equals(fragment.optString("type"))) {
                firstFile = i;
                break;
            }
        }

        if (firstFile < 0 && !chosen.isEmpty()) {
            JSONObject fileFragment = new JSONObject();
            fileFragment.put("id", nextFragmentId(copy));
            fileFragment.put("type", "FILE");
            JSONArray files = new JSONArray();
            for (JSONObject image : chosen) files.put(image);
            fileFragment.put("files", files);
            JSONArray withFile = new JSONArray();
            boolean inserted = false;
            for (int i = 0; i < copy.length(); i++) {
                JSONObject fragment = copy.optJSONObject(i);
                if (!inserted && fragment != null
                        && "REQUEST".equals(fragment.optString("type"))) {
                    withFile.put(fileFragment);
                    inserted = true;
                }
                withFile.put(copy.get(i));
            }
            if (!inserted) withFile.put(fileFragment);
            return withFile;
        }

        JSONArray result = new JSONArray();
        for (int i = 0; i < copy.length(); i++) {
            JSONObject fragment = copy.optJSONObject(i);
            if (fragment == null || !"FILE".equals(fragment.optString("type"))) {
                result.put(copy.get(i));
                continue;
            }
            JSONArray oldFiles = fragment.optJSONArray("files");
            JSONArray files = new JSONArray();
            if (oldFiles != null) for (int j = 0; j < oldFiles.length(); j++) {
                JSONObject file = oldFiles.optJSONObject(j);
                if (file == null || !isImageFile(file)) files.put(oldFiles.get(j));
            }
            if (i == firstFile) for (JSONObject image : chosen) files.put(image);
            if (files.length() > 0) {
                fragment.put("files", files);
                result.put(fragment);
            }
        }
        return result;
    }

    static JSONArray initialMessageFragments(String role, String content)
            throws org.json.JSONException {
        JSONObject fragment = new JSONObject();
        fragment.put("id", 1);
        fragment.put("type", "USER".equals(role) ? "REQUEST" : "RESPONSE");
        fragment.put("content", content == null ? "" : content);
        return new JSONArray().put(fragment);
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

    static Float parseThinkElapsed(String text) {
        String value = text == null ? "" : text.trim();
        if (value.length() == 0) return null;
        float seconds = Float.parseFloat(value);
        if (Float.isNaN(seconds) || Float.isInfinite(seconds) || seconds < 0) {
            throw new NumberFormatException("elapsed_secs must be a finite non-negative number");
        }
        return Float.valueOf(seconds);
    }

    static String formatThinkElapsed(Float seconds) {
        if (seconds == null) return "";
        float value = seconds.floatValue();
        long rounded = Math.round(value);
        if (Math.abs(value - rounded) < 0.0001f) return String.valueOf(rounded);
        return String.valueOf(value);
    }

    // elapsed_secs 是 DeepSeek ThinkFragment 的可选 Float 字段，空输入表示移除自定义用时。
    static JSONArray updateThinkElapsed(JSONArray fragments, Float elapsed)
            throws org.json.JSONException {
        repairMissingThinkFragmentIds(fragments);
        for (int i = 0; i < fragments.length(); i++) {
            JSONObject fragment = fragments.optJSONObject(i);
            if (fragment == null || !"THINK".equals(fragment.optString("type"))) continue;
            if (elapsed == null) fragment.remove("elapsed_secs");
            else fragment.put("elapsed_secs", elapsed.doubleValue());
            return fragments;
        }
        return null;
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
            JSONArray a;
            if ("THINK_TIME".equals(kind)) {
                a = updateThinkElapsed(new JSONArray(frag), parseThinkElapsed(text));
            } else {
                a = upsertFragmentContent(new JSONArray(frag), role, kind, text);
            }
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

    static boolean saveImageFiles(SQLiteDatabase db, String sid, long msgId,
                                  List<ImageAsset> selected) {
        if (db == null || !validSid(sid)) return false;
        String table = quoteIdent("chat_session_messages_" + sid);
        Cursor c = null;
        String fragments = null;
        try {
            c = db.rawQuery("SELECT fragments FROM " + table + " WHERE message_id=?",
                    new String[]{String.valueOf(msgId)});
            if (c.moveToFirst()) fragments = c.getString(0);
        } catch (Throwable ignored) {
        } finally { if (c != null) c.close(); }
        if (fragments == null) return false;
        try {
            ArrayList<JSONObject> files = new ArrayList<>();
            if (selected != null) for (ImageAsset image : selected) {
                if (image != null) files.add(cloneObject(image.file));
            }
            JSONArray updated = replaceImageFiles(new JSONArray(fragments), files);
            db.execSQL("UPDATE " + table + " SET fragments=? WHERE message_id=?",
                    new Object[]{updated.toString(), msgId});
            return true;
        } catch (Throwable ignored) { return false; }
    }

    static List<ImageAsset> loadUploadedImages(SQLiteDatabase db) {
        LinkedHashMap<String, ImageAsset> images = new LinkedHashMap<>();
        if (db == null) return new ArrayList<>();
        HashMap<String, String> sessionModels = new HashMap<>();
        ArrayList<String> tables = new ArrayList<>();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT id,model_type FROM chat_session_list", null);
            while (c.moveToNext()) {
                String sid = c.getString(0);
                if (sid != null) sessionModels.put(sid, c.getString(1));
            }
        } catch (Throwable ignored) {
        } finally { if (c != null) { c.close(); c = null; } }
        try {
            c = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'"
                    + " AND name LIKE 'chat_session_messages_%' ORDER BY name", null);
            while (c.moveToNext()) tables.add(c.getString(0));
        } catch (Throwable ignored) {
        } finally { if (c != null) c.close(); }
        final String tablePrefix = "chat_session_messages_";
        for (String table : tables) {
            String sourceModel = table.startsWith(tablePrefix)
                    ? sessionModels.get(table.substring(tablePrefix.length())) : null;
            try {
                c = db.rawQuery("SELECT fragments FROM " + quoteIdent(table)
                        + " WHERE role='USER' AND fragments LIKE '%FILE%'", null);
                while (c.moveToNext()) {
                    String raw = c.getString(0);
                    if (raw == null) continue;
                    for (ImageAsset image : extractImageFiles(new JSONArray(raw))) {
                        if (isReusableImageFile(image.file) && image.key.length() > 0
                                && !images.containsKey(image.key)) {
                            images.put(image.key, new ImageAsset(image.file, sourceModel));
                        }
                    }
                }
            } catch (Throwable ignored) {
            } finally { if (c != null) { c.close(); c = null; } }
        }
        return new ArrayList<>(images.values());
    }

    static int localCacheVersion(SQLiteDatabase db, String sid) {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT cache_version FROM chat_session_list WHERE id=?",
                    new String[]{sid});
            if (c.moveToFirst() && !c.isNull(0)) return c.getInt(0);
        } catch (Throwable ignored) {
        } finally { if (c != null) c.close(); }
        return Integer.MIN_VALUE;
    }

    static long maxMessageId(List<Msg> thread) {
        long max = Long.MIN_VALUE;
        if (thread != null) for (Msg msg : thread) if (msg != null && msg.id > max) max = msg.id;
        return max;
    }

    static boolean sameThread(List<Msg> left, List<Msg> right) {
        if (left == right) return true;
        if (left == null || right == null || left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            Msg a = left.get(i), b = right.get(i);
            if (a == null || b == null || a.id != b.id
                    || !String.valueOf(a.role).equals(String.valueOf(b.role))
                    || !String.valueOf(a.rawFragments).equals(String.valueOf(b.rawFragments))) return false;
        }
        return true;
    }

    // A frozen editor-owned session always wins. Otherwise a newer/equal live snapshot wins when
    // it has a later branch head or different in-flight fragments, so opening the editor reflects
    // the message the user just sent even before Room/SQLite has flushed it.
    static boolean shouldPreferSnapshot(int localVersion, List<Msg> local,
                                        HistoryBridge.Snapshot snapshot, List<Msg> online) {
        if (snapshot == null) return false;
        boolean localEmpty = local == null || local.isEmpty();
        boolean onlineEmpty = online == null || online.isEmpty();
        if (localEmpty) return !onlineEmpty || snapshot.complete;
        if (onlineEmpty) return false;
        // A frozen conversation protects existing edited rows, but a strictly later message id is
        // an appended host turn and is still safe/useful to expose as a read-only fresh snapshot.
        if (localVersion == FREEZE_VERSION) {
            return maxMessageId(online) > maxMessageId(local);
        }
        if (snapshot.version != null && localVersion != Integer.MIN_VALUE) {
            if (snapshot.version.intValue() > localVersion) return true;
            if (snapshot.version.intValue() < localVersion) return false;
        }
        long localMax = maxMessageId(local), onlineMax = maxMessageId(online);
        if (onlineMax != localMax) return onlineMax > localMax;
        long localHead = local.get(local.size() - 1).id;
        if (snapshot.currentMessageId != null
                && snapshot.currentMessageId.longValue() != localHead) return true;
        return !sameThread(local, online);
    }

    static String defaultConversationTitle(String role, String content) {
        String value = content == null ? "" : content.trim().replace('\n', ' ');
        if (value.length() > 24) value = value.substring(0, 24) + "…";
        if (value.length() > 0) return value;
        return "USER".equals(role) ? "新建用户对话" : "新建 AI 对话";
    }

    static void createMessageTable(SQLiteDatabase db, String sid) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + quoteIdent("chat_session_messages_" + sid)
                + "(message_id INTEGER PRIMARY KEY NOT NULL, parent_id INTEGER, role TEXT,"
                + " thinking_enabled INTEGER, status TEXT, inserted_at REAL, feedback_type TEXT,"
                + " accumulated_token_usage INTEGER, ban_edit INTEGER, ban_regenerate INTEGER,"
                + " tips TEXT, fragments TEXT, conversation_mode TEXT)");
    }

    // Early development builds used a SQLite BEFORE DELETE trigger. DeepSeek performs its cloud
    // prune inside a WCDB transaction; crossing the two SQLite implementations there can leave
    // WCDB waiting in sqlite3_step. The native p68/aw hook now removes only editor-owned rows from
    // the prune input instead, so clean up that obsolete trigger before cloud sync starts.
    static int removeObsoleteLocalSessionProtection() {
        int removed = 0;
        for (File file : allDbs()) {
            SQLiteDatabase db = null;
            try {
                db = SQLiteDatabase.openDatabase(file.getPath(), null,
                        SQLiteDatabase.OPEN_READWRITE);
                if (!localSessionProtectionInstalled(db)) continue;
                db.execSQL("DROP TRIGGER IF EXISTS deekseep_preserve_local_session");
                removed++;
            } catch (Throwable ignored) {
            } finally {
                if (db != null) try { db.close(); } catch (Throwable ignored) {}
            }
        }
        return removed;
    }

    private static JSONArray cursorRow(Cursor cursor, int count) throws Throwable {
        JSONArray row = new JSONArray();
        for (int i = 0; i < count; i++) {
            if (cursor.isNull(i)) {
                row.put(JSONObject.NULL);
                continue;
            }
            int type = cursor.getType(i);
            if (type == Cursor.FIELD_TYPE_INTEGER) row.put(cursor.getLong(i));
            else if (type == Cursor.FIELD_TYPE_FLOAT) row.put(cursor.getDouble(i));
            else if (type == Cursor.FIELD_TYPE_BLOB) row.put(android.util.Base64.encodeToString(
                    cursor.getBlob(i), android.util.Base64.NO_WRAP));
            else row.put(cursor.getString(i));
        }
        return row;
    }

    private static Object jsonSqlValue(JSONArray row, int index) throws Throwable {
        return row.isNull(index) ? null : row.get(index);
    }

    static boolean backupLocalSession(SQLiteDatabase db, String sid) {
        if (db == null || !validSid(sid)) return false;
        synchronized (LOCAL_SESSION_LOCK) {
            Cursor session = null;
            Cursor messages = null;
            try {
                session = db.rawQuery("SELECT id,title,titleType,cache_version,cache_reset_at,"
                                + "inserted_at,updated_at,current_message_id,schema_version,pinned,"
                                + "model_type FROM chat_session_list WHERE id=?",
                        new String[]{sid});
                if (!session.moveToFirst() || session.isNull(3)
                        || session.getInt(3) != FREEZE_VERSION
                        || session.getDouble(5) <= 0d) return false;
                Integer currentHead = session.isNull(7) ? null
                        : Integer.valueOf(session.getInt(7));
                JSONArray sessionRow = cursorRow(session, 11);
                JSONArray messageRows = new JSONArray();
                String table = quoteIdent("chat_session_messages_" + sid);
                messages = db.rawQuery("SELECT message_id,parent_id,role,thinking_enabled,status,"
                        + "inserted_at,feedback_type,accumulated_token_usage,ban_edit,"
                        + "ban_regenerate,tips,fragments,conversation_mode FROM " + table
                        + " ORDER BY message_id", null);
                while (messages.moveToNext()) messageRows.put(cursorRow(messages, 13));

                JSONObject root = new JSONObject();
                String dbId = uuidOf(new File(db.getPath()));
                root.put("db_id", dbId);
                root.put("sid", sid);
                root.put("session", sessionRow);
                root.put("messages", messageRows);
                File dir = new File(LOCAL_SESSION_DIR);
                if (!dir.exists() && !dir.mkdirs()) return false;
                File target = new File(dir, dbId + "__" + sid + ".json");
                File temp = new File(dir, target.getName() + ".tmp");
                FileWriter writer = new FileWriter(temp, false);
                writer.write(root.toString());
                writer.close();
                if (target.exists() && !target.delete()) return false;
                boolean saved = temp.renameTo(target);
                if (saved) Main.registerEditorLocalSession(sid, currentHead);
                return saved;
            } catch (Throwable ignored) {
                return false;
            } finally {
                if (session != null) try { session.close(); } catch (Throwable ignored) {}
                if (messages != null) try { messages.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static String readText(File file) throws Throwable {
        BufferedReader reader = new BufferedReader(new FileReader(file));
        StringBuilder out = new StringBuilder();
        char[] buffer = new char[8192];
        int count;
        while ((count = reader.read(buffer)) >= 0) {
            if (count > 0) out.append(buffer, 0, count);
        }
        reader.close();
        return out.toString();
    }

    /**
     * Returns editor-owned session ids without opening WCDB/SQLite. The native conversation-list
     * hook runs on the UI thread while DeepSeek may still be replacing its cloud directory, so it
     * must never compete for the database lock merely to decide which rows need preserving.
     */
    static HashSet<String> localSessionIdsFromBackups(File database) {
        HashSet<String> out = new HashSet<>();
        if (database == null) return out;
        try {
            String prefix = uuidOf(database) + "__";
            File[] backups = new File(LOCAL_SESSION_DIR).listFiles();
            if (backups == null) return out;
            for (File backup : backups) {
                if (backup == null || !backup.isFile()) continue;
                String name = backup.getName();
                if (!name.startsWith(prefix) || !name.endsWith(".json")) continue;
                String sid = name.substring(prefix.length(), name.length() - 5);
                if (validSid(sid)) out.add(sid);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** Used by the cloud-prune hook, whose WCDB repository already scopes rows to one account. */
    static HashSet<String> localSessionIdsFromAllBackups() {
        HashSet<String> out = new HashSet<>();
        try {
            File[] backups = new File(LOCAL_SESSION_DIR).listFiles();
            if (backups == null) return out;
            for (File backup : backups) {
                if (backup == null || !backup.isFile()) continue;
                String name = backup.getName();
                int separator = name.indexOf("__");
                if (separator <= 0 || !name.endsWith(".json")) continue;
                String sid = name.substring(separator + 2, name.length() - 5);
                if (validSid(sid)) out.add(sid);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static boolean tableExists(SQLiteDatabase db, String tableName) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                    new String[]{tableName});
            return cursor.moveToFirst();
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (cursor != null) try { cursor.close(); } catch (Throwable ignored) {}
        }
    }

    private static boolean localSessionProtectionInstalled(SQLiteDatabase db) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='trigger' AND name=?",
                    new String[]{"deekseep_preserve_local_session"});
            return cursor.moveToFirst();
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (cursor != null) try { cursor.close(); } catch (Throwable ignored) {}
        }
    }

    /** Restores only editor-owned rows/tables from their private sidecars after cloud cleanup. */
    static int restoreLocalConversations() {
            File[] backups = new File(LOCAL_SESSION_DIR).listFiles();
            if (backups == null || backups.length == 0) return 0;
            HashMap<String, File> databases = new HashMap<>();
            for (File file : allDbs()) databases.put(uuidOf(file), file);
            int restored = 0;
            for (File backup : backups) {
                if (backup == null || !backup.isFile() || !backup.getName().endsWith(".json")) {
                    continue;
                }
                SQLiteDatabase db = null;
                boolean began = false;
                try {
                    JSONObject root = new JSONObject(readText(backup));
                    String sid = root.optString("sid", "");
                    String dbId = root.optString("db_id", "");
                    JSONArray session = root.optJSONArray("session");
                    JSONArray messages = root.optJSONArray("messages");
                    File database = databases.get(dbId);
                    if (!validSid(sid) || database == null || session == null
                            || session.length() != 11 || messages == null) continue;
                    db = SQLiteDatabase.openDatabase(database.getPath(), null,
                            SQLiteDatabase.OPEN_READWRITE);

                    // The common path is already intact. Stay read-only in that case so a Compose
                    // recomposition cannot take a write lock while WCDB is syncing the cloud list.
                    boolean hadRow = sessionRowExists(db, sid);
                    String rawTable = "chat_session_messages_" + sid;
                    boolean hadTable = tableExists(db, rawTable);
                    boolean hadMessages = hadTable && tableHasRows(db, sid);
                    boolean intact = hadRow && hadTable
                            && (messages.length() == 0 || hadMessages);
                    if (intact) continue;

                    db.beginTransactionNonExclusive(); began = true;
                    // Recheck after obtaining the transaction: DeepSeek may have completed its own
                    // directory transaction while this background repair was waiting.
                    hadRow = sessionRowExists(db, sid);
                    if (!hadRow) {
                        Object[] values = new Object[11];
                        for (int i = 0; i < values.length; i++) values[i] = jsonSqlValue(session, i);
                        db.execSQL("INSERT OR IGNORE INTO chat_session_list(id,title,titleType,"
                                        + "cache_version,cache_reset_at,inserted_at,updated_at,"
                                        + "current_message_id,schema_version,pinned,model_type)"
                                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                                values);
                    }
                    hadTable = tableExists(db, rawTable);
                    hadMessages = hadTable && tableHasRows(db, sid);
                    if (!hadTable || (!hadMessages && messages.length() > 0)) {
                        createMessageTable(db, sid);
                        String table = quoteIdent(rawTable);
                        for (int i = 0; i < messages.length(); i++) {
                            JSONArray row = messages.optJSONArray(i);
                            if (row == null || row.length() != 13) continue;
                            Object[] values = new Object[13];
                            for (int j = 0; j < values.length; j++) {
                                values[j] = jsonSqlValue(row, j);
                            }
                            db.execSQL("INSERT OR REPLACE INTO " + table
                                            + "(message_id,parent_id,role,thinking_enabled,status,"
                                            + "inserted_at,feedback_type,accumulated_token_usage,"
                                            + "ban_edit,ban_regenerate,tips,fragments,conversation_mode)"
                                            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                                    values);
                        }
                    }
                    db.setTransactionSuccessful();
                    if (!hadRow || !hadTable || (!hadMessages && messages.length() > 0)) restored++;
                } catch (Throwable ignored) {
                } finally {
                    if (began && db != null) try { db.endTransaction(); } catch (Throwable ignored) {}
                    if (db != null) try { db.close(); } catch (Throwable ignored) {}
                }
            }
            return restored;
    }

    /**
     * Cloud directory rows do not always carry current_message_id.  DeepSeek still updates the
     * local chat_session_list row from that directory, which can turn a perfectly intact frozen
     * message table into an apparently empty conversation after a cold start.  Repair only editor-
     * frozen rows whose head is null or no longer exists; untouched cloud rows and valid branches
     * are never changed.
     */
    static int repairFrozenCurrentMessageIds() {
        int repaired = 0;
        for (File file : allDbs()) {
            SQLiteDatabase db = null;
            Cursor sessions = null;
            try {
                db = SQLiteDatabase.openDatabase(file.getPath(), null,
                        SQLiteDatabase.OPEN_READWRITE);
                ArrayList<Object[]> updates = new ArrayList<>();
                sessions = db.rawQuery("SELECT id,current_message_id FROM chat_session_list"
                                + " WHERE cache_version=?",
                        new String[]{String.valueOf(FREEZE_VERSION)});
                while (sessions.moveToNext()) {
                    String sid = sessions.getString(0);
                    if (!validSid(sid)) continue;
                    String rawTable = "chat_session_messages_" + sid;
                    if (!tableExists(db, rawTable)) continue;

                    Long current = sessions.isNull(1) ? null : sessions.getLong(1);
                    boolean currentExists = false;
                    Cursor row = null;
                    try {
                        if (current != null) {
                            row = db.rawQuery("SELECT 1 FROM " + quoteIdent(rawTable)
                                            + " WHERE message_id=? LIMIT 1",
                                    new String[]{String.valueOf(current)});
                            currentExists = row.moveToFirst();
                        }
                    } finally {
                        if (row != null) row.close();
                    }
                    if (currentExists) continue;

                    Long head = null;
                    try {
                        row = db.rawQuery("SELECT MAX(message_id) FROM "
                                + quoteIdent(rawTable), null);
                        if (row.moveToFirst() && !row.isNull(0)) head = row.getLong(0);
                    } finally {
                        if (row != null) row.close();
                    }
                    if (head != null) updates.add(new Object[]{head, sid});
                }
                sessions.close(); sessions = null;
                if (!updates.isEmpty()) {
                    db.beginTransaction();
                    try {
                        for (Object[] update : updates) {
                            db.execSQL("UPDATE chat_session_list SET current_message_id=?"
                                    + " WHERE id=? AND cache_version=?",
                                    new Object[]{update[0], update[1], FREEZE_VERSION});
                        }
                        db.setTransactionSuccessful();
                        repaired += updates.size();
                    } finally {
                        db.endTransaction();
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (sessions != null) try { sessions.close(); } catch (Throwable ignored) {}
                if (db != null) try { db.close(); } catch (Throwable ignored) {}
            }
        }
        return repaired;
    }

    /** Snapshot used by runtime hooks; call only during package load before WCDB starts. */
    static HashMap<String, Integer> frozenCurrentMessageIds() {
        HashMap<String, Integer> heads = new HashMap<>();
        for (File file : allDbs()) {
            SQLiteDatabase db = null;
            Cursor cursor = null;
            try {
                db = SQLiteDatabase.openDatabase(file.getPath(), null,
                        SQLiteDatabase.OPEN_READONLY);
                cursor = db.rawQuery("SELECT id,current_message_id FROM chat_session_list"
                                + " WHERE cache_version=? AND current_message_id IS NOT NULL",
                        new String[]{String.valueOf(FREEZE_VERSION)});
                while (cursor.moveToNext()) {
                    String sid = cursor.getString(0);
                    if (validSid(sid)) heads.put(sid, cursor.getInt(1));
                }
            } catch (Throwable ignored) {
            } finally {
                if (cursor != null) try { cursor.close(); } catch (Throwable ignored) {}
                if (db != null) try { db.close(); } catch (Throwable ignored) {}
            }
        }
        return heads;
    }

    /**
     * Deactivates the exact sidecar for this account/SID.  A failed plain delete is renamed away
     * from the .json restore namespace, otherwise a successful native/server deletion would be
     * resurrected on the next process start.
     */
    private static boolean deleteLocalSessionBackup(SQLiteDatabase db, String sid) {
        try {
            String dbId = uuidOf(new File(db.getPath()));
            File target = new File(LOCAL_SESSION_DIR, dbId + "__" + sid + ".json");
            new File(LOCAL_SESSION_DIR, target.getName() + ".tmp").delete();
            if (!target.exists() || target.delete()) return true;
            File inactive = new File(LOCAL_SESSION_DIR, target.getName() + ".deleted");
            if (inactive.exists()) inactive.delete();
            if (!target.renameTo(inactive)) return false;
            inactive.delete();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // 新建入口只创建空白本地会话。消息由编辑器底部的 USER / AI 按钮逐条追加，
    // 因而不会再出现“标题 + 首条记录”这套容易误解的特殊流程。
    static String createBlankConversation(SQLiteDatabase db) {
        if (db == null) return null;
        synchronized (CREATE_SESSION_LOCK) {
            String dbPath = db.getPath() == null ? "" : db.getPath();
            long requestedAt = System.currentTimeMillis();
            Object[] recent = RECENT_BLANK_CREATES.get(dbPath);
            if (recent != null && recent.length == 2 && recent[0] instanceof String
                    && recent[1] instanceof Number
                    && requestedAt - ((Number) recent[1]).longValue()
                    < CREATE_SESSION_DEBOUNCE_MS) {
                String recentSid = (String) recent[0];
                if (sessionRowExists(db, recentSid) && !tableHasRows(db, recentSid)) {
                    Main.log("reused recent blank conversation sid=" + recentSid);
                    return recentSid;
                }
            }

            String sid = UUID.randomUUID().toString();
            double now = requestedAt / 1000.0d;
            boolean ownTransaction = !db.inTransaction();
            boolean created = false;
            try {
                if (ownTransaction) db.beginTransaction();
                db.execSQL("INSERT INTO chat_session_list(id,title,titleType,cache_version,"
                                + "cache_reset_at,inserted_at,updated_at,current_message_id,"
                                + "schema_version,pinned,model_type) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        new Object[]{sid, "新对话", "SYSTEM", FREEZE_VERSION, (long) now,
                                now, now, null, 5, 0, "default"});
                createMessageTable(db, sid);
                if (ownTransaction) db.setTransactionSuccessful();
                created = true;
            } catch (Throwable ignored) {
                return null;
            } finally {
                if (ownTransaction) try { db.endTransaction(); } catch (Throwable ignored) {}
            }
            if (!created) return null;
            RECENT_BLANK_CREATES.put(dbPath, new Object[]{sid, Long.valueOf(requestedAt)});
            // Create the sidecar only after SQLite has committed. This closes the brief window in
            // which the host's cloud-directory refresh could see the row but not its protection.
            if (ownTransaction && !backupLocalSession(db, sid)) {
                Main.registerEditorLocalSession(sid, null);
                Main.log("blank conversation sidecar pending retry sid=" + sid);
            }
            Main.log("created blank conversation sid=" + sid);
            return sid;
        }
    }

    // 在当前父链末端追加一条消息；图片只允许挂在 USER 消息上。调用方可把它放进
    // “物化在线快照 + 追加”的同一事务里，也可直接用于编辑器创建的空白会话。
    static long appendLocalMessage(SQLiteDatabase db, String sid, String role, String content,
                                   List<JSONObject> images) {
        if (db == null || !validSid(sid) || !sessionRowExists(db, sid)) return -1;
        String safeRole = "USER".equals(role) ? "USER" : "ASSISTANT";
        String table = quoteIdent("chat_session_messages_" + sid);
        boolean ownTransaction = !db.inTransaction();
        Cursor c = null;
        try {
            if (ownTransaction) db.beginTransaction();
            createMessageTable(db, sid);
            long max = 0;
            c = db.rawQuery("SELECT MAX(message_id) FROM " + table, null);
            if (c.moveToFirst() && !c.isNull(0)) max = c.getLong(0);
            c.close(); c = null;
            Long current = currentMessageId(db, sid);
            long parent = current != null && current.longValue() > 0
                    ? current.longValue() : max;
            long next = Math.max(max, parent) + 1;
            JSONArray fragments = initialMessageFragments(safeRole, content);
            if ("USER".equals(safeRole) && images != null && !images.isEmpty()) {
                fragments = replaceImageFiles(fragments, images);
            }
            double now = System.currentTimeMillis() / 1000.0d;
            db.execSQL("INSERT INTO " + table
                            + "(message_id,parent_id,role,thinking_enabled,status,inserted_at,"
                            + "feedback_type,accumulated_token_usage,ban_edit,ban_regenerate,tips,"
                            + "fragments,conversation_mode) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    new Object[]{next, parent > 0 ? Long.valueOf(parent) : null, safeRole, 0,
                            "FINISHED", now, null, 0, 0, 0, null, fragments.toString(), null});
            db.execSQL("UPDATE chat_session_list SET current_message_id=?,updated_at=?,"
                            + "cache_version=? WHERE id=?",
                    new Object[]{next, now, FREEZE_VERSION, sid});
            if (ownTransaction) {
                db.setTransactionSuccessful();
                backupLocalSession(db, sid);
            }
            return next;
        } catch (Throwable ignored) {
            return -1;
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
            if (ownTransaction) try { db.endTransaction(); } catch (Throwable ignored) {}
        }
    }

    // 保留旧测试/调用点的兼容语义；新界面不再走这条“首条消息”路径。
    static String createLocalConversation(SQLiteDatabase db, String role, String title, String content) {
        String sid = createBlankConversation(db);
        if (sid == null) return null;
        String safeTitle = title == null || title.trim().length() == 0
                ? defaultConversationTitle(role, content) : title.trim();
        if (!saveTitle(db, sid, safeTitle)
                || appendLocalMessage(db, sid, role, content, null) < 0) return null;
        return sid;
    }

    static boolean saveTitle(SQLiteDatabase db, String sid, String title) {
        try { db.execSQL("UPDATE chat_session_list SET title=? WHERE id=?", new Object[]{title, sid}); return true; }
        catch (Throwable t) { return false; }
    }

    static String quoteIdent(String name) {
        return "\"" + (name == null ? "" : name.replace("\"", "\"\"")) + "\"";
    }

    // 幂等清理本机会话：即使宿主原生链路已经先删掉目录行，也必须继续 DROP 消息表
    // 并撤销 sidecar，避免冷启动恢复。true 表示最终本地状态已不可恢复，而不是本次
    // DELETE 一定影响了一行。
    static boolean deleteSessionLocal(SQLiteDatabase db, String sid) {
        if (db == null || sid == null || sid.length() == 0) return false;
        String t = "chat_session_messages_" + sid;
        boolean committed = false;
        db.beginTransaction();
        try {
            // Explicit editor deletion is intentional, so clear the local-only marker first and
            // let the protection trigger permit this one row to be removed.
            db.execSQL("UPDATE chat_session_list SET inserted_at=0 WHERE id=?",
                    new Object[]{sid});
            db.delete("chat_session_list", "id=?", new String[]{sid});
            db.execSQL("DROP TABLE IF EXISTS " + quoteIdent(t));
            db.setTransactionSuccessful();
            committed = true;
        } catch (Throwable t1) {
            Main.log("delete local session storage failed sid=" + sid + ": " + t1);
        } finally {
            try { db.endTransaction(); } catch (Throwable ignored) {}
        }
        if (!committed) return false;
        if (!deleteLocalSessionBackup(db, sid)) {
            Main.log("delete local session sidecar failed sid=" + sid);
            return false;
        }
        Main.unregisterEditorLocalSession(sid);
        Main.markSessionDeletedLocally(sid);
        return true;
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
        boolean failed = false;
        try {
            c = db.rawQuery("SELECT message_id, fragments FROM '" + t
                            + "' WHERE role=? AND fragments LIKE ?",
                    new String[]{"USER", "%<system>%"});
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
                        String safe = HistoryBridge.stripInjectedSystemPrompts(content);
                        if (!safe.equals(content)) { o.put("content", safe); ch = true; }
                    }
                    if (ch) updates.add(new Object[]{a.toString(), mid});
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
            failed = true;
        } finally { if (c != null) c.close(); }
        int n = 0;
        for (Object[] u : updates) {
            try { db.execSQL("UPDATE '" + t + "' SET fragments=? WHERE message_id=?", u); n++; }
            catch (Throwable ignored) { failed = true; }
        }
        return failed ? -1 : n;
    }

    // 全库自动清理：遍历所有账号库的所有会话消息表，抹掉 USER 消息里注入的 <system> 前缀。
    // 用途：注入的系统提示词会随服务器回刷/本地持久化写进库，重开 DeepSeek 时泄露到对话界面。
    // 模块启动时（UI 加载前）跑一次，保证用户永远看不到系统提示词。返回清理条数。
    static int stripAllSessions() {
        int total = 0;
        boolean failed = false;
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
                    int cleaned = stripSysPrompts(db, sid);
                    if (cleaned < 0) failed = true; else total += cleaned;
                }
            } catch (Throwable ignored) {
                failed = true;
            } finally {
                if (c != null) try { c.close(); } catch (Throwable ignored) {}
                if (db != null) try { db.close(); } catch (Throwable ignored) {}
            }
        }
        return failed ? -1 : total;
    }

    // 修复旧版编辑器已经落库的无 id THINK。只更新确实命中该坏格式的助手消息，
    // 同时补齐消息级 thinking_enabled；原 RESPONSE 内容和已有 fragment id 均保持不变。
    static int repairMalformedThinkFragments(SQLiteDatabase db, String sid) {
        String t = "chat_session_messages_" + sid;
        List<Object[]> updates = new ArrayList<>();
        Cursor c = null;
        boolean failed = false;
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
            failed = true;
        } finally { if (c != null) c.close(); }

        int n = 0;
        for (Object[] update : updates) {
            try {
                db.execSQL("UPDATE '" + t
                                + "' SET fragments=?, thinking_enabled=1 WHERE message_id=?",
                        update);
                n++;
            } catch (Throwable ignored) { failed = true; }
        }
        return failed ? -1 : n;
    }

    // 模块升级后自动遍历所有账号库，恢复已经被旧版写坏的会话，无需用户再次编辑。
    static int repairMalformedThinkFragmentsAllSessions() {
        int total = 0;
        boolean failed = false;
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
                        if (!freezeSession(db, sid)) failed = true;
                    } else if (fixed < 0) {
                        failed = true;
                    }
                }
            } catch (Throwable ignored) {
                failed = true;
            } finally {
                if (c != null) try { c.close(); } catch (Throwable ignored) {}
                if (db != null) try { db.close(); } catch (Throwable ignored) {}
            }
        }
        return failed ? -1 : total;
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
        HistoryBridge.Snapshot curSnapshot;
        boolean curSnapshotOverLocal;
        int historyLoadToken;
        boolean createConversationInFlight;
        long createConversationGuardUntil;
        String lastCreatedConversationSid;
        String emptyHint;
        final List<Field> fields = new ArrayList<>();
        final List<ImageEdit> imageEdits = new ArrayList<>();
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
        TextView accountBtn, historyTitle, createBtn, selectBtn, deleteBtn;
        boolean drawerOpen;
        boolean selectMode;
        final HashSet<String> selectedSessionKeys = new HashSet<>();

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

        interface PopupAction { void run(); }
        interface ChoiceAction { void choose(int which); }

        final class Popup {
            final Dialog dialog;
            final LinearLayout card;
            final LinearLayout body;
            final LinearLayout actions;
            Popup(Dialog dialog, LinearLayout card, LinearLayout body, LinearLayout actions) {
                this.dialog = dialog; this.card = card; this.body = body; this.actions = actions;
            }
        }

        // 编辑器内所有提示、选择和输入框都使用这一套自绘卡片，不再依赖系统 AlertDialog。
        Popup popup(String titleValue, boolean cancelable) {
            Dialog dialog = new Dialog(act);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setCancelable(cancelable);
            dialog.setCanceledOnTouchOutside(cancelable);

            LinearLayout card = new LinearLayout(act);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(20), dp(18), dp(20), dp(14));
            GradientDrawable background = new GradientDrawable();
            background.setColor(bar); background.setCornerRadius(dp(22));
            background.setStroke(dp(1), div);
            card.setBackground(background);

            TextView titleView = new TextView(act);
            titleView.setText(titleValue == null ? "" : titleValue);
            titleView.setTextColor(text);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            titleView.setTypeface(Typeface.DEFAULT_BOLD);
            titleView.setPadding(dp(2), 0, dp(2), dp(12));
            card.addView(titleView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout body = new LinearLayout(act);
            body.setOrientation(LinearLayout.VERTICAL);
            card.addView(body, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout actions = new LinearLayout(act);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.topMargin = dp(14);
            card.addView(actions, alp);
            dialog.setContentView(card);
            return new Popup(dialog, card, body, actions);
        }

        TextView popupButton(Popup popup, String label, boolean primary,
                             final PopupAction action) {
            TextView button = new TextView(act);
            button.setText(label);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            button.setTypeface(Typeface.DEFAULT_BOLD);
            button.setGravity(Gravity.CENTER);
            button.setPadding(dp(16), dp(9), dp(16), dp(9));
            button.setTextColor(primary ? 0xFFFFFFFF : text);
            GradientDrawable background = new GradientDrawable();
            background.setColor(primary ? DeekseepUi.BRAND : (dark ? 0xFF303034 : 0xFFF0F1F4));
            background.setCornerRadius(dp(18));
            button.setBackground(background);
            button.setClickable(true);
            button.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { if (action != null) action.run(); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(8);
            popup.actions.addView(button, lp);
            return button;
        }

        void showPopup(Popup popup) {
            popup.dialog.show();
            Window window = popup.dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(0x00000000));
                window.setLayout(Math.min(dp(440), Math.round(
                                act.getResources().getDisplayMetrics().widthPixels * 0.90f)),
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                window.setGravity(Gravity.CENTER);
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        }

        TextView popupText(String value) {
            TextView message = new TextView(act);
            message.setText(value);
            message.setTextColor(sub);
            message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            message.setLineSpacing(dp(2), 1f);
            message.setPadding(dp(2), dp(2), dp(2), dp(4));
            return message;
        }

        void showMessagePopup(String titleValue, String messageValue) {
            final Popup p = popup(titleValue, true);
            p.body.addView(popupText(messageValue));
            popupButton(p, "知道了", true, new PopupAction() {
                public void run() { p.dialog.dismiss(); }
            });
            showPopup(p);
        }

        void showConfirmPopup(String titleValue, String messageValue,
                              String positive, final PopupAction confirmed) {
            final Popup p = popup(titleValue, true);
            p.body.addView(popupText(messageValue));
            popupButton(p, "取消", false, new PopupAction() {
                public void run() { p.dialog.dismiss(); }
            });
            popupButton(p, positive, true, new PopupAction() {
                public void run() { p.dialog.dismiss(); if (confirmed != null) confirmed.run(); }
            });
            showPopup(p);
        }

        void showChoicePopup(String titleValue, String[] labels, int selected,
                             final ChoiceAction choice) {
            final Popup p = popup(titleValue, true);
            for (int i = 0; i < labels.length; i++) {
                final int which = i;
                TextView row = new TextView(act);
                row.setText((i == selected ? "●  " : "○  ") + labels[i]);
                row.setTextColor(text);
                row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                row.setPadding(dp(12), dp(13), dp(12), dp(13));
                GradientDrawable rowBg = new GradientDrawable();
                rowBg.setColor(i == selected ? (dark ? 0xFF303A52 : 0xFFEAF0FF)
                        : 0x00000000);
                rowBg.setCornerRadius(dp(14));
                row.setBackground(rowBg);
                row.setClickable(true);
                row.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        p.dialog.dismiss();
                        if (choice != null) choice.choose(which);
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(4);
                p.body.addView(row, lp);
            }
            popupButton(p, "取消", false, new PopupAction() {
                public void run() { p.dialog.dismiss(); }
            });
            showPopup(p);
        }

        void open() {
            curDbFile = currentDb(act.getClassLoader());
            if (curDbFile == null) { Toast.makeText(act, "未找到聊天数据库", Toast.LENGTH_LONG).show(); return; }
            accounts = loadAccountLabels();
            // The host sidebar is often ahead of SQLite immediately after sending/creating a chat.
            // Capture it once per editor opening, as requested, then merge it with the DB directory.
            Main.refreshNativeHistorySnapshots();

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
                        if (selectMode) { exitSelectMode(); return true; }
                        if (drawerOpen) { closeDrawer(); return true; }
                        DeekseepUi.slideOutAndDismiss(dlg, root); return true;
                    }
                    return false;
                }
            });
            dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
                public void onDismiss(DialogInterface d) {
                    historyLoadToken++;
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

            LinearLayout head = new LinearLayout(act);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(dp(16), DeekseepUi.statusBarHeight(act) + dp(10), dp(10), dp(8));

            historyTitle = new TextView(act);
            historyTitle.setText("对话历史");
            historyTitle.setTextColor(text); historyTitle.setTypeface(Typeface.DEFAULT_BOLD);
            historyTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            head.addView(historyTitle, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            createBtn = new TextView(act);
            createBtn.setText("＋新建对话");
            createBtn.setTextColor(DeekseepUi.BRAND);
            createBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            createBtn.setGravity(Gravity.CENTER);
            createBtn.setPadding(dp(6), dp(7), dp(6), dp(7));
            createBtn.setClickable(true);
            createBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { createBlankConversation(); }
            });
            head.addView(createBtn, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            deleteBtn = new TextView(act);
            deleteBtn.setTextColor(0xFFE53935);
            deleteBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            deleteBtn.setTypeface(Typeface.DEFAULT_BOLD);
            deleteBtn.setGravity(Gravity.CENTER);
            deleteBtn.setPadding(dp(6), dp(7), dp(6), dp(7));
            deleteBtn.setClickable(true);
            deleteBtn.setVisibility(View.GONE);
            deleteBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { confirmDeleteSelected(); }
            });
            head.addView(deleteBtn, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            selectBtn = new TextView(act);
            selectBtn.setText("选择");
            selectBtn.setTextColor(DeekseepUi.BRAND);
            selectBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            selectBtn.setGravity(Gravity.CENTER);
            selectBtn.setPadding(dp(6), dp(7), dp(6), dp(7));
            selectBtn.setClickable(true);
            selectBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (selectMode) exitSelectMode();
                    else {
                        selectMode = true;
                        selectedSessionKeys.clear();
                        updateSelectHeader();
                        rebuildHistoryList();
                    }
                }
            });
            head.addView(selectBtn, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
            showChoicePopup("聊天记录范围", items, showAll ? 1 : 0, new ChoiceAction() {
                public void choose(int which) {
                    showAll = (which == 1);
                    selectMode = false;
                    selectedSessionKeys.clear();
                    updateSelectHeader();
                    updateAccountBtn();
                    loadSessionList();
                    if (!sessions.isEmpty()) selectSession(sessions.get(0));
                    else clearContent();
                }
            });
        }

        void showAppendMessageForm(final String role) {
            if (curSid == null || sessDb == null) {
                Toast.makeText(act, "请先新建或选择一个对话", Toast.LENGTH_SHORT).show();
                return;
            }
            final Popup p = popup("USER".equals(role) ? "添加用户消息" : "添加 AI 回复", true);
            final EditText content = mkInput("直接输入要追加到当前对话的内容（可留空）");
            content.setMinLines(3);
            p.body.addView(content, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if ("USER".equals(role)) {
                TextView hint = popupText("创建后可点消息下方的“图片”入口，直接从系统相册上传并附加。 ");
                hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                p.body.addView(hint);
            }
            popupButton(p, "取消", false, new PopupAction() {
                public void run() { p.dialog.dismiss(); }
            });
            popupButton(p, "添加", true, new PopupAction() {
                public void run() {
                    String value = content.getText().toString();
                    p.dialog.dismiss();
                    appendCurrentMessage(role, value);
                }
            });
            showPopup(p);
        }

        void createBlankConversation() {
            long now = android.os.SystemClock.elapsedRealtime();
            if (createConversationInFlight || now < createConversationGuardUntil) {
                Session existing = findSession(lastCreatedConversationSid,
                        curDbFile == null ? null : curDbFile.getPath());
                if (existing != null) selectSession(existing);
                Main.log("suppressed duplicate new-conversation click sid="
                        + lastCreatedConversationSid);
                return;
            }
            createConversationInFlight = true;
            createConversationGuardUntil = now + CREATE_SESSION_DEBOUNCE_MS;
            if (createBtn != null) createBtn.setEnabled(false);
            SQLiteDatabase db = null;
            String sid = null;
            try {
                db = SQLiteDatabase.openDatabase(curDbFile.getPath(), null,
                        SQLiteDatabase.OPEN_READWRITE);
                sid = ChatEditorUi.createBlankConversation(db);
            } catch (Throwable ignored) {
            } finally {
                if (db != null) try { db.close(); } catch (Throwable ignored) {}
                createConversationInFlight = false;
                if (createBtn != null) createBtn.postDelayed(new Runnable() {
                    public void run() {
                        if (createBtn != null) createBtn.setEnabled(true);
                    }
                }, CREATE_SESSION_DEBOUNCE_MS);
            }
            if (sid == null) {
                Toast.makeText(act, "新建对话失败，请确认数据库可写", Toast.LENGTH_LONG).show();
                return;
            }
            lastCreatedConversationSid = sid;
            loadSessionList();
            Session created = findSession(sid, curDbFile.getPath());
            if (created != null) {
                selectSession(created);
                if (!tablet && drawerOpen) closeDrawer();
            }
            Toast.makeText(act, "已新建空白对话，可在底部添加用户消息或 AI 回复",
                    Toast.LENGTH_LONG).show();
        }

        void appendCurrentMessage(String role, String content) {
            if (sessDb == null || curSid == null || curSession == null) return;
            if (curSnapshot != null && !curSnapshot.complete) {
                Toast.makeText(act, "完整在线历史仍在加载，请稍后重新点选后再添加",
                        Toast.LENGTH_LONG).show();
                return;
            }
            final ArrayList<ImageEdit> pendingImages = new ArrayList<>();
            for (ImageEdit edit : imageEdits) {
                if (edit != null && edit.edited
                        && !imageSelectionSignature(edit.selected)
                        .equals(edit.originalSignature)) {
                    pendingImages.add(edit);
                }
            }
            boolean began = false;
            try {
                synchronized (HistoryBridge.snapshotLock()) {
                    sessDb.beginTransaction(); began = true;
                    if (curSnapshot != null) {
                        if (!sessionRowExists(sessDb, curSid)) {
                            double now = System.currentTimeMillis() / 1000.0d;
                            sessDb.execSQL("INSERT OR IGNORE INTO chat_session_list(id,title,titleType,"
                                            + "cache_version,cache_reset_at,inserted_at,updated_at,"
                                            + "current_message_id,schema_version,pinned,model_type)"
                                            + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                                    new Object[]{curSid,
                                            curSession.title == null ? "新对话" : curSession.title,
                                            "SYSTEM", curSnapshot.version, (long) now, now, now,
                                            curSnapshot.currentMessageId, 5, 0,
                                            curSession.model == null ? "default" : curSession.model});
                        }
                        if (!materializeSnapshot(sessDb, curSid, curSnapshot)) {
                            throw new IllegalStateException("online snapshot changed");
                        }
                    }
                    // Upload/apply actions normally persist immediately. This is the transaction-
                    // level safety net for an older pending selection: appending the next USER/AI
                    // turn must never reload the session and silently discard its FILE fragment.
                    for (ImageEdit edit : pendingImages) {
                        if (!saveImageFiles(sessDb, curSid, edit.msgId, edit.selected)) {
                            throw new IllegalStateException("pending image update failed");
                        }
                    }
                    if (appendLocalMessage(sessDb, curSid, role, content, null) < 0) {
                        throw new IllegalStateException("append failed");
                    }
                    if (stripSysPrompts(sessDb, curSid) < 0) {
                        throw new IllegalStateException("prompt cleanup failed");
                    }
                    sessDb.setTransactionSuccessful();
                }
            } catch (Throwable ignored) {
                Toast.makeText(act, "添加失败或在线历史刚刚更新，请重新点选对话后再试",
                        Toast.LENGTH_LONG).show();
                return;
            } finally {
                if (began) try { sessDb.endTransaction(); } catch (Throwable ignored) {}
            }
            backupLocalSession(sessDb, curSid);
            String keepSid = curSid;
            String keepPath = curSession.dbPath;
            curSnapshot = null;
            curSnapshotOverLocal = false;
            loadSessionList();
            Session refreshed = findSession(keepSid, keepPath);
            if (refreshed != null) selectSession(refreshed);
            Toast.makeText(act, "已追加到当前对话", Toast.LENGTH_SHORT).show();
        }

        String sessionKey(Session session) {
            return session == null ? "" : sessionKey(session.dbPath, session.id);
        }

        boolean sameSession(Session left, Session right) {
            return left != null && right != null && sessionKey(left).equals(sessionKey(right));
        }

        boolean isSelected(Session session) {
            return selectedSessionKeys.contains(sessionKey(session));
        }

        void updateSelectHeader() {
            if (selectBtn == null || deleteBtn == null || createBtn == null) return;
            if (!selectMode) {
                selectBtn.setText("选择");
                deleteBtn.setVisibility(View.GONE);
                createBtn.setVisibility(View.VISIBLE);
                if (historyTitle != null) historyTitle.setText("对话历史");
                return;
            }
            int count = selectedSessionKeys.size();
            selectBtn.setText("取消");
            createBtn.setVisibility(View.GONE);
            deleteBtn.setVisibility(View.VISIBLE);
            deleteBtn.setText(count > 0 ? ("删除(" + count + ")") : "删除");
            if (historyTitle != null) historyTitle.setText(count > 0
                    ? ("已选择 " + count) : "选择对话");
        }

        void exitSelectMode() {
            selectMode = false;
            selectedSessionKeys.clear();
            updateSelectHeader();
            rebuildHistoryList();
        }

        void enterSelectMode(Session session) {
            selectMode = true;
            selectedSessionKeys.clear();
            selectedSessionKeys.add(sessionKey(session));
            updateSelectHeader();
            rebuildHistoryList();
        }

        void toggleSelect(Session session) {
            String key = sessionKey(session);
            if (!selectedSessionKeys.remove(key)) selectedSessionKeys.add(key);
            updateSelectHeader();
            rebuildHistoryList();
        }

        Session findSessionByKey(String key) {
            for (Session session : sessions) if (sessionKey(session).equals(key)) return session;
            return null;
        }

        void confirmDeleteSelected() {
            final int count = selectedSessionKeys.size();
            if (count <= 0) {
                Toast.makeText(act, "先选择要删除的对话", Toast.LENGTH_SHORT).show();
                return;
            }
            showConfirmPopup("删除 " + count + " 个对话",
                    "会先走 DeepSeek 原生删除链路提交服务器删除，再清理本机会话、"
                            + "消息表和 Deekseep 恢复副本。",
                    "删除", new PopupAction() {
                        public void run() { deleteSelectedSessions(); }
                    });
        }

        void deleteSelectedSessions() {
            if (selectedSessionKeys.isEmpty()) return;
            HashSet<String> doomed = new HashSet<>(selectedSessionKeys);
            String keepKey = curSession == null ? null : sessionKey(curSession);
            boolean currentDeleted = keepKey != null && doomed.contains(keepKey);
            Map<String, List<String>> byDb = new HashMap<>();
            int nativeRequested = 0;
            int matched = 0;
            for (Session session : sessions) {
                if (!doomed.contains(sessionKey(session))) continue;
                matched++;
                // Dispatch the exact host h61(tp) event before touching SQLite. The captured tp
                // remains sufficient for the asynchronous server request; local cleanup below is
                // still mandatory so a stale sidecar cannot restore the row on restart.
                if (Main.requestNativeSessionDelete(session.id)) nativeRequested++;
                List<String> ids = byDb.get(session.dbPath);
                if (ids == null) { ids = new ArrayList<>(); byDb.put(session.dbPath, ids); }
                ids.add(session.id);
            }

            try { if (sessDb != null) sessDb.close(); } catch (Throwable ignored) {}
            sessDb = null;
            int ok = 0, failed = 0;
            for (Map.Entry<String, List<String>> entry : byDb.entrySet()) {
                SQLiteDatabase db = null;
                try {
                    db = SQLiteDatabase.openDatabase(entry.getKey(), null,
                            SQLiteDatabase.OPEN_READWRITE);
                    for (String sid : entry.getValue()) {
                        if (deleteSessionLocal(db, sid)) ok++; else failed++;
                    }
                } catch (Throwable ignored) {
                    failed += entry.getValue().size();
                } finally { if (db != null) try { db.close(); } catch (Throwable ignored) {} }
            }
            failed += Math.max(0, doomed.size() - matched);
            selectedSessionKeys.clear();
            selectMode = false;
            curSid = null; curSession = null; curThread = null; curSnapshot = null;
            curSnapshotOverLocal = false;
            loadSessionList();
            Session next = !currentDeleted && keepKey != null ? findSessionByKey(keepKey) : null;
            if (next == null && !sessions.isEmpty()) next = sessions.get(0);
            if (next != null) selectSession(next); else clearContent();
            updateSelectHeader();
            String message = "已请求 DeepSeek 删除 " + nativeRequested
                    + " 个，本地已移除 " + ok + " 个";
            int nativeUnavailable = Math.max(0, matched - nativeRequested);
            if (nativeUnavailable > 0) {
                message += "，未取得原生链路 " + nativeUnavailable + " 个";
            }
            if (failed > 0) message += "，本地失败 " + failed + " 个";
            Toast.makeText(act, message, Toast.LENGTH_SHORT).show();
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

            LinearLayout appendBar = new LinearLayout(act);
            appendBar.setOrientation(LinearLayout.HORIZONTAL);
            appendBar.setGravity(Gravity.CENTER);
            appendBar.setPadding(dp(12), dp(9), dp(12), dp(10));
            appendBar.setBackgroundColor(bar);
            TextView addUser = messageActionButton("＋ 用户消息", true);
            addUser.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { showAppendMessageForm("USER"); }
            });
            LinearLayout.LayoutParams userLp = new LinearLayout.LayoutParams(0, dp(42), 1f);
            userLp.rightMargin = dp(6);
            appendBar.addView(addUser, userLp);
            TextView addAssistant = messageActionButton("＋ AI 回复", false);
            addAssistant.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { showAppendMessageForm("ASSISTANT"); }
            });
            LinearLayout.LayoutParams assistantLp = new LinearLayout.LayoutParams(0, dp(42), 1f);
            assistantLp.leftMargin = dp(6);
            appendBar.addView(addAssistant, assistantLp);
            col.addView(appendBar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

        TextView messageActionButton(String label, boolean primary) {
            TextView button = new TextView(act);
            button.setText(label);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            button.setTypeface(Typeface.DEFAULT_BOLD);
            button.setGravity(Gravity.CENTER);
            button.setTextColor(primary ? 0xFFFFFFFF : DeekseepUi.BRAND);
            button.setClickable(true);
            GradientDrawable background = new GradientDrawable();
            background.setColor(primary ? DeekseepUi.BRAND
                    : (dark ? 0xFF293044 : 0xFFEAF0FF));
            background.setCornerRadius(dp(21));
            if (!primary) background.setStroke(dp(1), DeekseepUi.BRAND);
            button.setBackground(background);
            return button;
        }

        // ── 会话列表 ─────────────────────────────────────
        void loadSessionList() {
            sessions.clear();
            LinkedHashMap<String, Session> merged = new LinkedHashMap<>();
            List<File> dbs = new ArrayList<>();
            if (showAll) dbs = allDbs(); else dbs.add(curDbFile);
            for (File f : dbs) {
                String label = accounts.get(uuidOf(f));
                SQLiteDatabase d = null; Cursor c = null;
                try {
                    d = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY);
                    c = d.rawQuery("SELECT id,title,updated_at,model_type,cache_version"
                            + " FROM chat_session_list ORDER BY updated_at DESC", null);
                    while (c.moveToNext()) {
                        Session s = new Session();
                        s.id = c.getString(0); s.title = c.getString(1);
                        s.dbPath = f.getPath(); s.account = label;
                        s.updatedAt = c.isNull(2) ? 0d : c.getDouble(2);
                        s.model = c.getString(3);
                        s.cacheVersion = c.isNull(4) ? Integer.MIN_VALUE : c.getInt(4);
                        if (s.id != null) merged.put(sessionKey(s.dbPath, s.id), s);
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (c != null) c.close();
                    if (d != null) try { d.close(); } catch (Throwable ignored) {}
                }
            }

            // Only the current account owns the captured native tp list. It may contain a brand
            // new chat before chat_session_list is flushed, or a fresher title/timestamp.
            String currentPath = curDbFile.getPath();
            String currentLabel = accounts.get(uuidOf(curDbFile));
            for (Object[] row : Main.nativeSessionDirectory()) {
                if (row == null || row.length < 4 || row[0] == null) continue;
                String sid = String.valueOf(row[0]);
                if (!validSid(sid)) continue;
                String key = sessionKey(currentPath, sid);
                Session s = merged.get(key);
                if (s == null) {
                    s = new Session();
                    s.id = sid; s.dbPath = currentPath; s.account = currentLabel;
                    s.nativeOnly = true;
                    merged.put(key, s);
                }
                String nativeTitle = row[1] == null ? "" : String.valueOf(row[1]);
                if (nativeTitle.trim().length() > 0 && s.cacheVersion != FREEZE_VERSION) {
                    s.title = nativeTitle;
                }
                if (row[2] instanceof Number) {
                    s.updatedAt = Math.max(s.updatedAt, ((Number) row[2]).doubleValue());
                }
                if (row[3] != null) s.model = String.valueOf(row[3]);
            }
            sessions.addAll(merged.values());
            Collections.sort(sessions, new Comparator<Session>() {
                public int compare(Session left, Session right) {
                    return left.updatedAt == right.updatedAt ? 0
                            : (left.updatedAt < right.updatedAt ? 1 : -1);
                }
            });
            rebuildHistoryList();
        }

        String sessionKey(String dbPath, String sid) {
            return (dbPath == null ? "" : dbPath) + "\n" + (sid == null ? "" : sid);
        }

        void rebuildHistoryList() {
            historyList.removeAllViews();
            updateSelectHeader();
            if (sessions.isEmpty()) {
                TextView empty = new TextView(act);
                empty.setText("没有本地或已加载的对话");
                empty.setTextColor(sub);
                empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(dp(16), dp(40), dp(16), dp(16));
                historyList.addView(empty, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return;
            }
            for (final Session s : sessions) {
                final boolean checked = isSelected(s);
                final boolean current = sameSession(curSession, s);
                LinearLayout item = new LinearLayout(act);
                item.setOrientation(LinearLayout.HORIZONTAL);
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setPadding(dp(12), dp(10), dp(12), dp(10));
                item.setClickable(true);
                if (checked || current) {
                    GradientDrawable selected = new GradientDrawable();
                    selected.setColor(checked ? (dark ? 0xFF3A2630 : 0xFFFFEEF2)
                            : (dark ? 0xFF2A2D3A : 0xFFEFF2FF));
                    selected.setCornerRadius(dp(10));
                    item.setBackground(selected);
                }
                if (selectMode) {
                    TextView mark = new TextView(act);
                    mark.setText(checked ? "\u2713" : "\u25CB");
                    mark.setTextColor(checked ? 0xFFE53935 : sub);
                    mark.setTypeface(Typeface.DEFAULT_BOLD);
                    mark.setGravity(Gravity.CENTER);
                    mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                    item.addView(mark, new LinearLayout.LayoutParams(dp(30),
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                LinearLayout texts = new LinearLayout(act);
                texts.setOrientation(LinearLayout.VERTICAL);
                TextView t = new TextView(act);
                t.setText((s.title != null && s.title.trim().length() > 0) ? s.title : "未命名对话");
                t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END);
                t.setTextColor(text); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                texts.addView(t);
                if (showAll && s.account != null) {
                    TextView a = new TextView(act);
                    a.setText(s.account);
                    a.setTextColor(sub); a.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                    texts.addView(a);
                }
                item.addView(texts, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                item.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        if (selectMode) { toggleSelect(s); return; }
                        selectSession(s);
                        if (!tablet) closeDrawer();
                    }
                });
                item.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) { enterSelectMode(s); return true; }
                });
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.setMargins(dp(8), dp(3), dp(8), dp(3));
                historyList.addView(item, params);
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
            final int token = ++historyLoadToken;
            try { if (sessDb != null) sessDb.close(); } catch (Throwable ignored) {}
            // 会话所属账号库是权威位置；不要把同名 SID 的编辑写入另一个账号库。
            sessDb = SQLiteDatabase.openDatabase(s.dbPath, null, SQLiteDatabase.OPEN_READWRITE);
            curSid = s.id; curSession = s;
            String title = (s.title != null && s.title.trim().length() > 0) ? s.title : "";
            titleEt.setText(title.length() > 0 ? title : "未命名对话");
            Main.refreshNativeHistorySnapshot(s.id);
            List<Msg> local = loadThread(sessDb, s.id);
            int localVersion = localCacheVersion(sessDb, s.id);
            HistoryBridge.Snapshot snapshot = HistoryBridge.snapshot(s.id);
            List<Msg> online = loadSnapshotThread(snapshot);
            boolean useOnline = shouldPreferSnapshot(localVersion,
                    local, snapshot, online);
            curSnapshot = useOnline ? snapshot : null;
            curSnapshotOverLocal = useOnline
                    && ((local != null && !local.isEmpty()) || !sessionRowExists(sessDb, s.id));
            curThread = useOnline ? online : local;
            emptyHint = null;
            if (curThread == null || curThread.isEmpty()) {
                // 编辑器创建的冻结会话可以合法地没有任何消息，不应误报成“云端记录加载失败”。
                if (localVersion == FREEZE_VERSION && sessionRowExists(sessDb, s.id)) {
                    emptyHint = "这是一个空白对话\n请用底部按钮添加用户消息或 AI 回复";
                    renderThread();
                    rebuildHistoryList();
                    return;
                }
                if (Main.openNativeSession(s.id)) {
                    emptyHint = "正在从 DeepSeek 加载该对话记录…";
                    renderThread();
                    rebuildHistoryList();
                    pollSelectedHistory(s, token, 0);
                    return;
                }
                emptyHint = "暂时无法请求该云端对话\n请先返回 DeepSeek 主界面刷新侧栏后重试";
            } else if (curSnapshot != null
                    && ((!curSnapshot.complete && Main.openNativeSession(s.id))
                    || curSnapshotOverLocal)) {
                // Show the freshest memory state immediately, while waiting for either a complete
                // pw0 snapshot or the host's SQLite writer to catch up.
                renderThread();
                rebuildHistoryList();
                pollSelectedHistory(s, token, 0);
                return;
            }
            renderThread();
            rebuildHistoryList();
        }

        // 缺少本地消息表时，复用 DeepSeek 自己的会话切换回调触发在线历史加载；
        // 编辑器只轮询目标 SID，避免每次重组都扫描整个侧栏的所有 tp 对象。
        void pollSelectedHistory(final Session expected, final int token, final int attempt) {
            msgContainer.postDelayed(new Runnable() {
                public void run() {
                    if (token != historyLoadToken || curSession != expected || curSid == null
                            || !curSid.equals(expected.id) || dlg == null || !dlg.isShowing()) return;
                    try {
                        List<Msg> local = loadThread(sessDb, expected.id);
                        HistoryBridge.Snapshot snapshot = HistoryBridge.snapshot(expected.id);
                        // tp 只用于尽快给出第一份只读内容；一旦已有快照，后续只等 pw0
                        // 将其提升为 complete，避免旧 tp 在同版本下反复覆盖刚返回的完整历史。
                        if (snapshot == null) {
                            Main.refreshNativeHistorySnapshot(expected.id);
                            snapshot = HistoryBridge.snapshot(expected.id);
                        }
                        List<Msg> online = loadSnapshotThread(snapshot);
                        boolean useOnline = shouldPreferSnapshot(
                                localCacheVersion(sessDb, expected.id), local, snapshot, online);
                        if (!useOnline && local != null && !local.isEmpty()) {
                            boolean changed = curSnapshot != null || !sameThread(curThread, local);
                            curSnapshot = null;
                            curSnapshotOverLocal = false;
                            curThread = local;
                            emptyHint = null;
                            if (changed) renderThread();
                            return;
                        }
                        if (snapshot != null && snapshot.complete && online.isEmpty()) {
                            curSnapshot = snapshot;
                            curSnapshotOverLocal = !sessionRowExists(sessDb, expected.id);
                            curThread = online;
                            emptyHint = "该对话没有消息记录";
                            renderThread();
                            return;
                        }
                        if (!online.isEmpty()) {
                            boolean overLocal = (local != null && !local.isEmpty())
                                    || !sessionRowExists(sessDb, expected.id);
                            boolean changed = curSnapshot != snapshot
                                    || curSnapshotOverLocal != overLocal
                                    || !sameThread(curThread, online);
                            curSnapshot = snapshot;
                            curSnapshotOverLocal = overLocal;
                            curThread = online;
                            emptyHint = null;
                            if (changed) renderThread();
                            if (snapshot.complete && !curSnapshotOverLocal) return;
                        }
                    } catch (Throwable ignored) {}
                    if (attempt < 39) {
                        pollSelectedHistory(expected, token, attempt + 1);
                    } else {
                        // tp 的不完整快照仍有阅读价值；超时后保留它，稍后重新点选即可再取。
                        if (curThread == null || curThread.isEmpty()) {
                            emptyHint = "未能取得该对话的在线记录\n请检查网络后重新点选此对话";
                            renderThread();
                        }
                    }
                }
            }, 250L);
        }

        // 渲染当前会话（不重新查库）；user 正文永远切掉注入的 <system> 前缀
        void renderThread() {
            fields.clear();
            imageEdits.clear();
            msgAnchors.clear();
            msgContainer.removeAllViews();
            String title = (curSession != null && curSession.title != null) ? curSession.title : "";
            registerField(titleEt, "TITLE", null, 0, title, null, text, text, aEdit);

            if (curThread == null || curThread.isEmpty()) {
                addPlaceholder(emptyHint != null ? emptyHint
                        : "该对话目前只有云端目录，尚未取得在线消息记录");
                return;
            }
            if (curSnapshot != null && !curSnapshot.complete) {
                addPlaceholder("当前显示的是 DeepSeek 内存记录（只读）\n完整在线历史返回后即可编辑保存");
            } else if (curSnapshot != null && curSnapshotOverLocal) {
                addPlaceholder("已刷新到 DeepSeek 最新内存记录（暂时只读）\n"
                        + "等待宿主落库后重新打开即可编辑");
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
                String elapsedLabel = formatThinkElapsed(m.thinkElapsedSecs);
                final String label = (hasThink ? "已思考" : "添加思考内容")
                        + (elapsedLabel.length() > 0 ? (" · " + elapsedLabel + " 秒") : "");
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
                final Field tf = registerField(think, "THINK", m.role, m.id, m.think == null ? "" : m.think, tb, sub, sub, thinkEdit);
                think.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) {
                        if (tf.et.isFocusable()) return false;   // 已进编辑态 → 放行原生选字/复制
                        beginEdit(tf); return true;
                    } });

                LinearLayout thinkArea = new LinearLayout(act);
                thinkArea.setOrientation(LinearLayout.VERTICAL);
                thinkArea.setVisibility(hasThink ? View.GONE : View.VISIBLE);
                thinkArea.addView(think, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                LinearLayout elapsedRow = new LinearLayout(act);
                elapsedRow.setOrientation(LinearLayout.HORIZONTAL);
                elapsedRow.setGravity(Gravity.CENTER_VERTICAL);
                TextView elapsedCaption = new TextView(act);
                elapsedCaption.setText("思考用时（秒）");
                elapsedCaption.setTextColor(sub);
                elapsedCaption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                elapsedRow.addView(elapsedCaption, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                final EditText elapsed = new EditText(act);
                elapsed.setHint("例如 12.5");
                elapsed.setTextColor(sub); elapsed.setHintTextColor(sub);
                elapsed.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                elapsed.setSingleLine(true);
                elapsed.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
                GradientDrawable eb = new GradientDrawable();
                eb.setColor(thinkBg); eb.setCornerRadius(dp(8));
                elapsed.setBackground(eb);
                elapsed.setPadding(dp(10), dp(6), dp(10), dp(6));
                elapsed.setFocusable(false); elapsed.setFocusableInTouchMode(false);
                elapsed.setCursorVisible(false); elapsed.setLongClickable(true);
                final Field ef = registerField(elapsed, "THINK_TIME", m.role, m.id,
                        formatThinkElapsed(m.thinkElapsedSecs), eb, sub, sub, thinkEdit);
                elapsed.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) {
                        if (ef.et.isFocusable()) return false;
                        beginEdit(ef); return true;
                    } });
                LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(dp(104),
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                elapsedRow.addView(elapsed, elp);
                LinearLayout.LayoutParams erp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                erp.topMargin = dp(6);
                thinkArea.addView(elapsedRow, erp);

                head.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        boolean showing = thinkArea.getVisibility() == View.VISIBLE;
                        thinkArea.setVisibility(showing ? View.GONE : View.VISIBLE);
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
                msgContainer.addView(thinkArea, blp);
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
            if (user) {
                LinearLayout userBlock = new LinearLayout(act);
                userBlock.setOrientation(LinearLayout.VERTICAL);
                userBlock.setGravity(Gravity.END);
                userBlock.addView(body, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                final ImageEdit imageEdit = new ImageEdit();
                imageEdit.msgId = m.id;
                for (ImageAsset image : m.images) imageEdit.selected.add(
                        new ImageAsset(image.file, curSession == null ? null : curSession.model));
                imageEdit.originalSignature = imageSelectionSignature(imageEdit.selected);
                imageEdit.summary = new TextView(act);
                imageEdit.summary.setTextColor(DeekseepUi.BRAND);
                imageEdit.summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                imageEdit.summary.setGravity(Gravity.END);
                imageEdit.summary.setPadding(dp(8), dp(5), dp(4), dp(3));
                imageEdit.summary.setClickable(true);
                imageEdit.summary.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { showImageManager(imageEdit); }
                });
                updateImageSummary(imageEdit);
                imageEdits.add(imageEdit);
                userBlock.addView(imageEdit.summary, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                wrap.addView(userBlock, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                wrap.addView(body, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            msgContainer.addView(wrap, wlp);
            msgAnchors.put(m.id, wrap);
        }

        void updateImageSummary(ImageEdit edit) {
            if (edit == null || edit.summary == null) return;
            StringBuilder names = new StringBuilder();
            for (ImageAsset image : edit.selected) {
                if (names.length() > 0) names.append("、");
                names.append(image.label);
                if (names.length() > 60) { names.append("…"); break; }
            }
            String first = edit.selected.isEmpty() ? "图片 0 张 · 从相册添加"
                    : ("图片 " + edit.selected.size() + " 张 · 相册 / 管理");
            edit.summary.setText(names.length() == 0 ? first : first + "\n" + names);
        }

        boolean isCurrentSnapshotReadOnly() {
            return curSnapshot != null && (!curSnapshot.complete || curSnapshotOverLocal);
        }

        List<ImageAsset> currentImageLibrary(ImageEdit current) {
            LinkedHashMap<String, ImageAsset> all = new LinkedHashMap<>();
            if (current != null) for (ImageAsset image : current.selected) {
                if (image.key.length() > 0) all.put(image.key, new ImageAsset(image));
            }
            if (curThread != null) for (Msg msg : curThread) for (ImageAsset image : msg.images) {
                if (isReusableImageFile(image.file) && image.key.length() > 0
                        && !all.containsKey(image.key)) {
                    all.put(image.key, new ImageAsset(image.file,
                            curSession == null ? null : curSession.model));
                }
            }
            for (ImageAsset image : loadUploadedImages(sessDb)) {
                if (image.key.length() > 0 && !all.containsKey(image.key)) all.put(image.key, image);
            }
            return new ArrayList<>(all.values());
        }

        void showImageManager(final ImageEdit edit) {
            if (isCurrentSnapshotReadOnly()) {
                Toast.makeText(act, "当前显示最新内存记录，等待 DeepSeek 落库后再修改图片",
                        Toast.LENGTH_LONG).show();
                return;
            }
            final List<ImageAsset> library = currentImageLibrary(edit);
            final boolean[] checked = new boolean[library.size()];
            LinkedHashMap<String, Boolean> selected = new LinkedHashMap<>();
            for (ImageAsset image : edit.selected) selected.put(image.key, Boolean.TRUE);
            for (int i = 0; i < library.size(); i++) {
                ImageAsset image = library.get(i);
                checked[i] = selected.containsKey(image.key);
            }
            final Popup p = popup("用户消息图片", true);
            TextView gallery = messageActionButton("从相册选择并上传", true);
            gallery.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    p.dialog.dismiss();
                    pickGalleryForMessage(edit);
                }
            });
            LinearLayout.LayoutParams galleryLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
            galleryLp.bottomMargin = dp(10);
            p.body.addView(gallery, galleryLp);

            TextView caption = popupText(library.isEmpty()
                    ? "没有旧图片。可直接从相册选择一张新图片。"
                    : "也可以勾选本机聊天记录中已上传过的图片：");
            p.body.addView(caption);
            if (!library.isEmpty()) {
                ScrollView scroll = new ScrollView(act);
                final LinearLayout rows = new LinearLayout(act);
                rows.setOrientation(LinearLayout.VERTICAL);
                for (int i = 0; i < library.size(); i++) {
                    final int which = i;
                    final TextView row = new TextView(act);
                    row.setText((checked[i] ? "☑  " : "☐  ") + library.get(i).label);
                    row.setTextColor(text);
                    row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                    row.setPadding(dp(10), dp(10), dp(10), dp(10));
                    row.setClickable(true);
                    row.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            checked[which] = !checked[which];
                            row.setText((checked[which] ? "☑  " : "☐  ")
                                    + library.get(which).label);
                        }
                    });
                    rows.addView(row, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                scroll.addView(rows);
                p.body.addView(scroll, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));
            }
            popupButton(p, "全部移除", false, new PopupAction() {
                public void run() {
                    p.dialog.dismiss();
                    edit.selected.clear();
                    edit.edited = !imageSelectionSignature(edit.selected)
                            .equals(edit.originalSignature);
                    updateImageSummary(edit);
                }
            });
            popupButton(p, "取消", false, new PopupAction() {
                public void run() { p.dialog.dismiss(); }
            });
            popupButton(p, "应用", true, new PopupAction() {
                public void run() {
                    p.dialog.dismiss();
                    ArrayList<ImageAsset> requested = new ArrayList<>();
                    for (int i = 0; i < library.size(); i++) if (checked[i]) {
                        requested.add(new ImageAsset(library.get(i)));
                    }
                    refreshAndApplyImages(edit, requested);
                }
            });
            showPopup(p);
        }

        /** Commits an image selection immediately so later message appends cannot discard it. */
        boolean persistImageSelectionNow(ImageEdit edit, List<ImageAsset> selected) {
            if (edit == null || sessDb == null || curSid == null || isCurrentSnapshotReadOnly()) {
                return false;
            }
            boolean began = false;
            try {
                synchronized (HistoryBridge.snapshotLock()) {
                    sessDb.beginTransaction(); began = true;
                    if (!saveImageFiles(sessDb, curSid, edit.msgId, selected)) {
                        throw new IllegalStateException("image update failed");
                    }
                    double now = System.currentTimeMillis() / 1000.0d;
                    sessDb.execSQL("UPDATE chat_session_list SET cache_version=?,updated_at=?"
                                    + " WHERE id=?",
                            new Object[]{FREEZE_VERSION, now, curSid});
                    sessDb.setTransactionSuccessful();
                }
            } catch (Throwable t) {
                Main.log("persist image selection failed sid=" + curSid
                        + " msg=" + edit.msgId + " err=" + t);
                return false;
            } finally {
                if (began) try { sessDb.endTransaction(); } catch (Throwable ignored) {}
            }
            boolean backedUp = backupLocalSession(sessDb, curSid);
            Main.log("persisted image selection sid=" + curSid + " msg=" + edit.msgId
                    + " count=" + (selected == null ? 0 : selected.size())
                    + " sidecar=" + backedUp);
            return true;
        }

        void pickGalleryForMessage(final ImageEdit edit) {
            if (isCurrentSnapshotReadOnly() || curSid == null) {
                Toast.makeText(act, "当前记录还不能附加图片", Toast.LENGTH_LONG).show();
                return;
            }
            final String targetSid = curSid;
            final String targetModel = curSession == null
                    || curSession.model == null || curSession.model.trim().length() == 0
                    ? "default" : curSession.model;
            Main.pickGalleryImage(act, new Main.GalleryPickCallback() {
                public void onPicked(final android.net.Uri uri) {
                    if (uri == null) return;
                    final Popup waiting = popup("正在保存图片", false);
                    waiting.body.addView(popupText("正在保存到 DeepSeek 私有目录，并同步登记图片信息…"));
                    showPopup(waiting);
                    new Thread(new Runnable() {
                        public void run() {
                            final JSONObject uploaded = Main.uploadGalleryImage(act, uri, targetModel);
                            act.runOnUiThread(new Runnable() {
                                public void run() {
                                    try { waiting.dialog.dismiss(); } catch (Throwable ignored) {}
                                    if (uploaded == null) {
                                        showMessagePopup("图片保存失败",
                                                "无法从系统相册读取或复制这张图片；聊天记录没有改变。");
                                        return;
                                    }
                                    if (!targetSid.equals(curSid)) {
                                        showMessagePopup("对话已经切换",
                                                "图片已上传，但为了避免加到错误对话，本次没有写入聊天记录。请回到目标消息重新选择。 ");
                                        return;
                                    }
                                    ImageAsset fresh = new ImageAsset(uploaded, targetModel);
                                    ArrayList<ImageAsset> next = new ArrayList<>();
                                    boolean duplicate = false;
                                    for (ImageAsset image : edit.selected) {
                                        ImageAsset copy = new ImageAsset(image);
                                        next.add(copy);
                                        if (copy.key.equals(fresh.key)) duplicate = true;
                                    }
                                    if (!duplicate) next.add(fresh);
                                    if (!persistImageSelectionNow(edit, next)) {
                                        showMessagePopup("图片写入失败",
                                                "图片文件已保存，但未能附加到这条用户消息；原聊天记录没有改变，请重新打开后再试。");
                                        return;
                                    }
                                    edit.selected.clear();
                                    edit.selected.addAll(next);
                                    edit.originalSignature = imageSelectionSignature(edit.selected);
                                    edit.edited = false;
                                    updateImageSummary(edit);
                                    Toast.makeText(act, "图片已持久保存并附加到用户消息",
                                            Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    }, "Deekseep-gallery-upload").start();
                }
            });
        }

        void refreshAndApplyImages(final ImageEdit edit,
                                   final List<ImageAsset> requested) {
            if (requested == null || requested.isEmpty()) {
                edit.selected.clear();
                edit.edited = !imageSelectionSignature(edit.selected)
                        .equals(edit.originalSignature);
                updateImageSummary(edit);
                return;
            }
            final Popup waiting = popup("正在准备图片", false);
            waiting.body.addView(popupText("正在向 DeepSeek 获取新的图片访问凭证…"));
            showPopup(waiting);
            final String targetModel = curSession == null
                    || curSession.model == null || curSession.model.trim().length() == 0
                    ? "default" : curSession.model;
            new Thread(new Runnable() {
                public void run() {
                    final ArrayList<ImageAsset> refreshed = new ArrayList<>();
                    String failedName = null;
                    for (ImageAsset image : requested) {
                        String sourceModel = image.sourceModel == null
                                || image.sourceModel.trim().length() == 0
                                ? targetModel : image.sourceModel;
                        JSONObject fresh = Main.refreshUploadedImageCredential(
                                image.file, sourceModel, targetModel);
                        if (fresh == null) {
                            failedName = image.label;
                            break;
                        }
                        refreshed.add(new ImageAsset(fresh, targetModel));
                    }
                    final String failure = failedName;
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            try { waiting.dialog.dismiss(); } catch (Throwable ignored) {}
                            if (failure != null) {
                                showMessagePopup("图片凭证刷新失败",
                                        "无法刷新“" + failure + "”。请确认网络可用，返回 DeepSeek 聊天页一次后再打开编辑器重试；原聊天记录没有改变。");
                                return;
                            }
                            if (!persistImageSelectionNow(edit, refreshed)) {
                                showMessagePopup("图片写入失败",
                                        "图片凭证已准备完成，但未能写入这条用户消息；原聊天记录没有改变。");
                                return;
                            }
                            edit.selected.clear();
                            edit.selected.addAll(refreshed);
                            edit.originalSignature = imageSelectionSignature(edit.selected);
                            edit.edited = false;
                            updateImageSummary(edit);
                            Toast.makeText(act, "图片已刷新并保存",
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }, "Deekseep-image-refresh").start();
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
            if (isCurrentSnapshotReadOnly()) {
                Toast.makeText(act, "当前显示最新内存记录，等待 DeepSeek 落库后再编辑",
                        Toast.LENGTH_LONG).show();
                return;
            }
            activeField = f;
            f.edited = true;
            if (f.render) f.et.setText(f.original);   // 进入编辑态显示 Markdown 源码而非渲染结果
            GradientDrawable hl = new GradientDrawable();
            hl.setColor(f.hlColor);
            hl.setCornerRadius(dp(("TITLE".equals(f.kind) || "THINK_TIME".equals(f.kind)) ? 8 : 16));
            f.et.setBackground(hl);
            f.et.setTextColor(f.hlText);
            f.et.setFocusable(true); f.et.setFocusableInTouchMode(true); f.et.setCursorVisible(true);
            f.et.requestFocus();
            f.et.setSelection(f.et.getText().length());
            if (imm != null) imm.showSoftInput(f.et, InputMethodManager.SHOW_IMPLICIT);
        }

        void saveAll() {
            if (sessDb == null || curSid == null) return;
            ArrayList<Field> changedFields = new ArrayList<>();
            ArrayList<String> changedValues = new ArrayList<>();
            ArrayList<ImageEdit> changedImages = new ArrayList<>();
            for (Field f : fields) {
                if (f.edited && !f.et.getText().toString().equals(f.original)) {
                    changedFields.add(f);
                    changedValues.add(f.et.getText().toString());
                }
            }
            for (ImageEdit edit : imageEdits) if (edit.edited
                    && !imageSelectionSignature(edit.selected).equals(edit.originalSignature)) {
                changedImages.add(edit);
            }
            if (isCurrentSnapshotReadOnly()
                    && (!changedFields.isEmpty() || !changedImages.isEmpty())) {
                Toast.makeText(act, "最新记录尚未落库，请重新打开编辑器后再保存",
                        Toast.LENGTH_LONG).show();
                return;
            }
            // Validate all inputs before creating a local table or changing any host state.
            for (Field f : fields) {
                if (!f.edited || !"THINK_TIME".equals(f.kind)) continue;
                String cur = f.et.getText().toString();
                if (cur.equals(f.original)) continue;
                try {
                    Float elapsed = parseThinkElapsed(cur);
                    if (elapsed != null) {
                        boolean hasContent = false;
                        for (Field candidate : fields) {
                            if ("THINK".equals(candidate.kind) && candidate.msgId == f.msgId
                                    && candidate.et.getText().toString().trim().length() > 0) {
                                hasContent = true;
                                break;
                            }
                        }
                        if (!hasContent) {
                            Toast.makeText(act, "请先输入思考内容，再设置思考用时", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                }
                catch (Throwable ignored) {
                    Toast.makeText(act, "思考用时必须是大于或等于 0 的秒数", Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            int n = changedFields.size() + changedImages.size();
            boolean materialized = false;
            if (n > 0) {
                boolean began = false;
                try {
                    synchronized (HistoryBridge.snapshotLock()) {
                        try {
                        sessDb.beginTransaction(); began = true;
                        // A cloud-only conversation must be materialised for every save,
                        // including title-only edits, inside the same all-or-nothing transaction.
                        if (curSnapshot != null) {
                            if (!materializeSnapshot(sessDb, curSid, curSnapshot))
                                throw new IllegalStateException("stale online history");
                            materialized = true;
                        }
                        for (int i = 0; i < changedFields.size(); i++) {
                            Field f = changedFields.get(i); String value = changedValues.get(i);
                            boolean ok = "TITLE".equals(f.kind)
                                    ? saveTitle(sessDb, curSid, value)
                                    : saveFragment(sessDb, curSid, f.msgId, f.role, f.kind, value);
                            if (!ok) throw new IllegalStateException("field update failed");
                        }
                        for (ImageEdit edit : changedImages) {
                            if (!saveImageFiles(sessDb, curSid, edit.msgId, edit.selected))
                                throw new IllegalStateException("image update failed");
                        }
                        if (stripSysPrompts(sessDb, curSid) < 0)
                            throw new IllegalStateException("prompt cleanup failed");
                        if (!freezeSession(sessDb, curSid))
                            throw new IllegalStateException("freeze failed");
                        sessDb.setTransactionSuccessful();
                        } finally {
                            if (began) {
                                try { sessDb.endTransaction(); } finally { began = false; }
                            }
                        }
                    }
                } catch (Throwable t) {
                    Toast.makeText(act, "保存失败或在线历史已更新，请重新打开后再试", Toast.LENGTH_LONG).show();
                    return;
                } finally {
                    if (began) try { sessDb.endTransaction(); } catch (Throwable ignored) {}
                }
                if (materialized) {
                    curSnapshot = null;
                    curSnapshotOverLocal = false;
                }
                backupLocalSession(sessDb, curSid);
            }

            boolean titleChanged = false;
            for (int i = 0; i < changedFields.size(); i++) {
                Field f = changedFields.get(i); String value = changedValues.get(i);
                f.original = value;
                if ("TITLE".equals(f.kind) && curSession != null) {
                    curSession.title = value; titleChanged = true;
                }
            }
            for (ImageEdit edit : changedImages) {
                edit.originalSignature = imageSelectionSignature(edit.selected);
                edit.edited = false;
                updateImageSummary(edit);
            }
            if (titleChanged) rebuildHistoryList();
            for (Field f : fields) {
                // 退出编辑态、还原外观；查看态重新渲染 Markdown
                f.et.setBackground(f.normalBg);
                f.et.setTextColor(f.normalText);
                f.et.setFocusable(false); f.et.setFocusableInTouchMode(false); f.et.setCursorVisible(false);
                f.et.clearFocus();
                if (f.render) f.et.setText(md(f.original));
                f.edited = false;
            }
            activeField = null;
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
            final Popup p = popup("插入" + title, true);
            p.body.addView(in);
            popupButton(p, "取消", false, new PopupAction() {
                public void run() { p.dialog.dismiss(); }
            });
            popupButton(p, "完成", true, new PopupAction() {
                public void run() {
                    p.dialog.dismiss();
                    insertAtCursor(open + in.getText().toString() + close, -1);
                }
            });
            showPopup(p);
        }

        void promptPrefix(final String title, final String prefix) {
            final EditText in = mkInput("内容");
            final Popup p = popup("插入" + title, true);
            p.body.addView(in);
            popupButton(p, "取消", false, new PopupAction() {
                public void run() { p.dialog.dismiss(); }
            });
            popupButton(p, "完成", true, new PopupAction() {
                public void run() {
                    p.dialog.dismiss();
                    insertLine(prefix, in.getText().toString());
                }
            });
            showPopup(p);
        }

        void promptCodeBlock() {
            final EditText lang = mkInput("语言（可留空，如 java）");
            lang.setInputType(InputType.TYPE_CLASS_TEXT);
            final EditText code = mkInput("代码");
            final Popup p = popup("插入代码块", true);
            p.body.addView(lang);
            p.body.addView(code);
            popupButton(p, "取消", false, new PopupAction() {
                public void run() { p.dialog.dismiss(); }
            });
            popupButton(p, "完成", true, new PopupAction() {
                public void run() {
                    p.dialog.dismiss();
                    String l = lang.getText().toString().trim();
                    String body = "```" + l + "\n" + code.getText().toString() + "\n```\n";
                    insertLine(body, "");
                }
            });
            showPopup(p);
        }

        void promptLink(final boolean image) {
            final EditText txt = mkInput(image ? "图片描述（可留空）" : "显示文字");
            txt.setInputType(InputType.TYPE_CLASS_TEXT);
            final EditText url = mkInput("链接地址");
            url.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
            final Popup p = popup(image ? "插入图片" : "插入链接", true);
            p.body.addView(txt);
            p.body.addView(url);
            popupButton(p, "取消", false, new PopupAction() {
                public void run() { p.dialog.dismiss(); }
            });
            popupButton(p, "完成", true, new PopupAction() {
                public void run() {
                    p.dialog.dismiss();
                    String t = txt.getText().toString();
                    String u = url.getText().toString();
                    insertAtCursor((image ? "![" : "[") + t + "](" + u + ")", -1);
                }
            });
            showPopup(p);
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

package com.dsmod.inject;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * 给消息长按操作菜单新增一个"编辑"项。
 *
 * ── 逆向定位（jadx-out）──────────────────────────────────────────
 *   菜单项类型  : c11  { jr1 a=icon; jr1 b; go1 c; int d=labelResId; boolean e; xa3 f=onClick }
 *   copy 项构建 : n37.q()  -> new c11(t82.h, null, R.string.message_press_copy, <lambda>, 6)
 *   share 项构建: n37.r()  -> new c11(t82.j, null, R.string.share, <lambda>, 6)
 *   菜单渲染器  : l00.m(...)  case this.a==0 —— 字段 c=g1(菜单项列表), d=uo(消息),
 *                 e=tp(仓库), h=u55(dismiss)。 循环 g1.get(i) 逐项渲染成 c11。
 *   点击包装    : m00 —— 点击时 u55.c()(收起菜单) 再 c11.f.u()(执行动作)。
 *   菜单容器链  : m65(case1) -> f00(case0) -> l00(case0)。
 *
 *   我们 hook l00.m()，在渲染前把一个自建的 "编辑" c11 追加进 g1 列表
 *   (g1 是 kotlinx PersistentList，add() 返回新列表)，并回写字段 c。
 *
 * ── 编辑落地 ─────────────────────────────────────────────────────
 *   消息对象 uo: u()=id(int), H()=parentId, C()=isModel?
 *     mv(流式): 字段 m=wv0, wv0.a=to7(SnapshotStateList<mo2 fragment>)。
 *     kv(静态): 字段 t=fragment 列表。
 *   fragment: ws7(REQUEST)/zs7(RESPONSE)/gt7(THINK)，字段 c=正文(String,final)。
 *     改正文=构造新 fragment: new ws7(id,content) / new zs7(id,content,list) /
 *     反射 set c 字段，再 to7.set(i,newFrag) 触发 Compose 重组。
 *   数据库: /data/data/com.deepseek.chat/databases/deepseek_chat_*.db
 *     表 chat_session_messages_* 列 fragments(JSON)。UPDATE ... WHERE message_id=?。
 */
public final class MessageEditHook {

    private static final int R_STRING_MESSAGE_PRESS_EDIT = 0x7f0e019c;
    private static final String DB_DIR = "/data/data/com.deepseek.chat/databases";

    interface Logger { void log(String msg); }

    @FunctionalInterface interface ActGetter { Activity get(); }

    private static Logger LOG;
    private static ActGetter ACTS;
    private static ClassLoader CL;

    // 反射缓存
    private static Field l00FieldA, l00FieldC, l00FieldD, l00FieldE;
    private static Constructor<?> c11Ctor;          // c11(jr1,jr1,go1,int,boolean,xa3)
    private static Class<?> jr1Class, go1Class, xa3Class;
    private static Object editIcon;                 // 复用 t82.h 作为图标

    static void install(XposedModule module, ClassLoader cl, Logger logger, ActGetter acts) {
        LOG = logger; ACTS = acts; CL = cl;
        try {
            final Class<?> l00 = cl.loadClass("l00");
            l00FieldA = l00.getDeclaredField("a"); l00FieldA.setAccessible(true);
            l00FieldC = l00.getDeclaredField("c"); l00FieldC.setAccessible(true);
            l00FieldD = l00.getDeclaredField("d"); l00FieldD.setAccessible(true);
            l00FieldE = l00.getDeclaredField("e"); l00FieldE.setAccessible(true);

            jr1Class = cl.loadClass("jr1");
            go1Class = cl.loadClass("go1");
            xa3Class = cl.loadClass("xa3");
            Class<?> c11 = cl.loadClass("c11");
            for (Constructor<?> ct : c11.getDeclaredConstructors()) {
                if (ct.getParameterTypes().length == 6) { c11Ctor = ct; c11Ctor.setAccessible(true); break; }
            }
            // 复用 copy 项的图标 t82.h
            try {
                Field hf = cl.loadClass("t82").getDeclaredField("h");
                hf.setAccessible(true);
                editIcon = hf.get(null);
            } catch (Throwable t) { LOG.log("[edit] icon t82.h not found: " + t); }

            if (c11Ctor == null) { LOG.log("[edit] c11 6-arg ctor not found, abort"); return; }

            // hook 菜单渲染 l00.m(Object,Object,Object,Object)
            int n = 0;
            for (Method m : l00.getDeclaredMethods()) {
                if (!m.getName().equals("m") || m.getParameterTypes().length != 4) continue;
                module.hook(m).intercept(new Hooker() {
                    @Override public Object intercept(Chain chain) throws Throwable {
                        try { injectEditItem(chain.getThisObject()); }
                        catch (Throwable t) { LOG.log("[edit] inject err: " + t); }
                        return chain.proceed();
                    }
                });
                n++;
            }
            LOG.log("[edit] hooked l00.m x" + n + " (message action menu injector)");
        } catch (Throwable t) {
            LOG.log("[edit] install failed: " + t);
        }
    }

    /** 在菜单渲染前，把"编辑"项追加进 g1 列表并回写。 */
    private static void injectEditItem(Object l00Instance) throws Throwable {
        int selector = l00FieldA.getInt(l00Instance);
        if (selector != 0) return; // 只处理消息动作菜单那一路

        Object g1List = l00FieldC.get(l00Instance);
        if (!(g1List instanceof List)) return;
        final Object uoMsg = l00FieldD.get(l00Instance);
        final Object tpRepo = l00FieldE.get(l00Instance);
        if (uoMsg == null) return;

        // 幂等：若已含我们的编辑项则跳过（比对 labelResId）
        List<?> cur = (List<?>) g1List;
        for (Object item : cur) {
            if (item == null) continue;
            try {
                Field df = item.getClass().getDeclaredField("d"); df.setAccessible(true);
                if (df.getInt(item) == R_STRING_MESSAGE_PRESS_EDIT) return; // 已经有编辑项
            } catch (Throwable ignored) {}
        }

        // 构建 onClick 代理 xa3
        Object onClick = Proxy.newProxyInstance(CL, new Class[]{xa3Class}, new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method method, Object[] args) {
                if ("u".equals(method.getName())) {
                    showEditDialog(uoMsg, tpRepo);
                }
                return null; // xa3.u() 返回 Object，null 即 Unit 语义足够
            }
        });

        // new c11(icon, null, null, R.string.message_press_edit, true, onClick)
        Object editItem = c11Ctor.newInstance(
                editIcon, null, null, R_STRING_MESSAGE_PRESS_EDIT, Boolean.TRUE, onClick);

        // g1 是 PersistentList：add(e) 返回新列表
        Object newList = persistentAdd(g1List, editItem);
        if (newList != null && newList != g1List) {
            l00FieldC.set(l00Instance, newList);
        }
    }

    /** PersistentList.add(e) -> 新列表；找不到就退回原列表(不注入)。 */
    private static Object persistentAdd(Object g1List, Object item) {
        try {
            // kotlinx immutable：add(Object) 返回 PersistentList
            Method add = g1List.getClass().getMethod("add", Object.class);
            Object r = add.invoke(g1List, item);
            if (r != null && r != Boolean.TRUE && r != Boolean.FALSE) return r;
        } catch (Throwable ignored) {}
        return null;
    }

    // ── 编辑弹窗 ─────────────────────────────────────────────────────

    private static void showEditDialog(final Object uoMsg, final Object tpRepo) {
        final Activity act = ACTS != null ? ACTS.get() : null;
        if (act == null || act.isFinishing()) { LOG.log("[edit] no activity for dialog"); return; }

        // 读取当前正文（优先内存 fragment）
        final int msgId = callInt(uoMsg, "u", -1);
        final String[] curText = { readCurrentContent(uoMsg) };
        final boolean dark = isDark(act);

        act.runOnUiThread(new Runnable() { public void run() {
            try {
                final EditText et = new EditText(act);
                et.setText(curText[0] == null ? "" : curText[0]);
                et.setInputType(InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
                et.setMinLines(3);
                et.setGravity(Gravity.TOP | Gravity.START);
                et.setTextColor(dark ? 0xFFECECEC : 0xFF1A1A1A);
                et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                int pad = dp(act, 14);
                et.setPadding(pad, pad, pad, pad);
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(act, 12));
                bg.setColor(dark ? 0xFF2A2A2D : 0xFFF2F3F5);
                bg.setStroke(dp(act, 1), 0xFF4D6BFE);
                et.setBackground(bg);

                FrameLayout wrap = new FrameLayout(act);
                int m = dp(act, 20);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(m, dp(act, 8), m, 0);
                wrap.addView(et, lp);

                AlertDialog dlg = new AlertDialog.Builder(act)
                        .setTitle("编辑消息")
                        .setView(wrap)
                        .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                String nt = et.getText().toString();
                                applyEdit(uoMsg, tpRepo, msgId, nt);
                                try { android.widget.Toast.makeText(act, "已编辑", android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
                            }
                        })
                        .setNegativeButton("取消", null)
                        .create();
                try {
                    if (dlg.getWindow() != null) {
                        GradientDrawable win = new GradientDrawable();
                        win.setCornerRadius(dp(act, 16));
                        win.setColor(dark ? 0xFF1F1F22 : 0xFFFFFFFF);
                        dlg.getWindow().setBackgroundDrawable(win);
                    }
                } catch (Throwable ignored) {}
                dlg.show();
            } catch (Throwable t) { LOG.log("[edit] dialog err: " + t); }
        }});
    }

    // ── 落地：内存 + 数据库 ──────────────────────────────────────────

    private static void applyEdit(Object uoMsg, Object tpRepo, int msgId, String newText) {
        boolean memOk = false;
        try { memOk = updateMemory(uoMsg, newText); }
        catch (Throwable t) { LOG.log("[edit] mem update err: " + t); }

        boolean dbOk = false;
        try { dbOk = updateDatabase(msgId, newText); }
        catch (Throwable t) { LOG.log("[edit] db update err: " + t); }

        LOG.log("[edit] applied id=" + msgId + " memOk=" + memOk + " dbOk=" + dbOk
                + " len=" + (newText == null ? 0 : newText.length()));
    }

    /** 改内存 fragment 的正文并 to7.set() 触发 Compose 重组。 */
    private static boolean updateMemory(Object uoMsg, String newText) throws Throwable {
        // mv：字段 m=wv0，wv0.a=to7(SnapshotStateList)
        Object frags = null;      // List of fragment
        boolean isSnapshotList = false;
        try {
            Field mf = uoMsg.getClass().getDeclaredField("m"); mf.setAccessible(true);
            Object wv0 = mf.get(uoMsg);
            if (wv0 != null) {
                Field af = wv0.getClass().getDeclaredField("a"); af.setAccessible(true);
                frags = af.get(wv0); isSnapshotList = true;
            }
        } catch (Throwable ignored) {}
        // kv：字段 t=fragment 列表
        if (frags == null) {
            try {
                Field tf = uoMsg.getClass().getDeclaredField("t"); tf.setAccessible(true);
                frags = tf.get(uoMsg);
            } catch (Throwable ignored) {}
        }
        if (!(frags instanceof List)) { LOG.log("[edit] no fragment list on " + uoMsg.getClass().getName()); return false; }

        List<?> list = (List<?>) frags;
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            Object frag = list.get(i);
            if (frag == null) continue;
            String type = fragType(frag);
            // 只编辑正文型 fragment（REQUEST/RESPONSE/THINK），跳过审查模板
            if (type == null) continue;
            if (!(type.equals("REQUEST") || type.equals("RESPONSE") || type.equals("THINK"))) continue;

            Object newFrag = buildEditedFragment(frag, newText);
            if (newFrag == null) continue;
            try {
                if (isSnapshotList) {
                    // to7.set(i, obj) 触发重组
                    Method set = frags.getClass().getMethod("set", int.class, Object.class);
                    set.invoke(frags, i, newFrag);
                } else {
                    Method set = frags.getClass().getMethod("set", int.class, Object.class);
                    set.invoke(frags, i, newFrag);
                }
                changed = true;
            } catch (Throwable t) {
                // 退化：直接改原 fragment 的 content 字段（不一定触发重组）
                try { setField(frag, "c", newText); changed = true; }
                catch (Throwable t2) { LOG.log("[edit] frag set fail: " + t2); }
            }
        }
        return changed;
    }

    /** 依据原 fragment 类型构造一个正文已替换的新 fragment。 */
    private static Object buildEditedFragment(Object frag, String newText) {
        String cls = frag.getClass().getSimpleName();
        try {
            int id = callInt(frag, "getId", -1);
            if (cls.equals("ws7")) {           // REQUEST: ws7(int id, String content)
                Constructor<?> c = frag.getClass().getConstructor(int.class, String.class);
                return c.newInstance(id, newText);
            }
            if (cls.equals("zs7")) {           // RESPONSE: zs7(int id, String content, List)
                Object srcList = callObj(frag, "c");   // c()=List d
                Constructor<?> c = frag.getClass().getConstructor(int.class, String.class, List.class);
                return c.newInstance(id, newText, srcList instanceof List ? srcList : null);
            }
            if (cls.equals("gt7")) {           // THINK: gt7(int id, String content, Float, List)
                Object flt = callObj(frag, "t");
                Object srcList = callObj(frag, "c");
                Constructor<?> c = frag.getClass().getConstructor(int.class, String.class, Float.class, List.class);
                return c.newInstance(id, newText, (Float) (flt instanceof Float ? flt : null),
                        srcList instanceof List ? srcList : null);
            }
        } catch (Throwable t) {
            LOG.log("[edit] buildEditedFragment " + cls + " err: " + t);
        }
        // 通用退化：clone 原对象并覆盖 content 字段 c
        try {
            setField(frag, "c", newText);
            return frag; // 原地改，set 回去仍能触发 SnapshotStateList
        } catch (Throwable ignored) {}
        return null;
    }

    /** 扫描所有 chat_session_messages_* 表，UPDATE 含该 message_id 行的 fragments 正文。 */
    private static boolean updateDatabase(int msgId, String newText) {
        if (msgId < 0) return false;
        File dir = new File(DB_DIR);
        File[] files = dir.listFiles();
        if (files == null) return false;
        File dbFile = null; long best = -1;
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith("deepseek_chat_") && n.endsWith(".db") && f.lastModified() > best) {
                best = f.lastModified(); dbFile = f;
            }
        }
        if (dbFile == null) { LOG.log("[edit] no deepseek_chat_*.db"); return false; }

        SQLiteDatabase db = null;
        boolean done = false;
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            Cursor tc = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'chat_session_messages_%'",
                    null);
            java.util.ArrayList<String> tables = new java.util.ArrayList<>();
            while (tc.moveToNext()) tables.add(tc.getString(0));
            tc.close();

            for (String table : tables) {
                Cursor c = db.rawQuery("SELECT fragments FROM '" + table
                        + "' WHERE message_id=?", new String[]{String.valueOf(msgId)});
                if (c.moveToFirst()) {
                    String fragJson = c.getString(0);
                    c.close();
                    String updated = replaceContentInFragments(fragJson, newText);
                    if (updated != null) {
                        db.execSQL("UPDATE '" + table + "' SET fragments=? WHERE message_id=?",
                                new Object[]{updated, msgId});
                        done = true;
                        LOG.log("[edit] db updated table=" + table + " id=" + msgId);
                    }
                    break;
                }
                c.close();
            }
        } catch (Throwable t) {
            LOG.log("[edit] db op err: " + t);
        } finally {
            if (db != null) try { db.close(); } catch (Throwable ignored) {}
        }
        return done;
    }

    /** 在 fragments JSON 数组里，把 REQUEST/RESPONSE/THINK 项的 content 替换为 newText。 */
    private static String replaceContentInFragments(String json, String newText) {
        if (json == null) return null;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            boolean changed = false;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String type = o.optString("type", "");
                if (type.equals("REQUEST") || type.equals("RESPONSE") || type.equals("THINK")) {
                    o.put("content", newText);
                    changed = true;
                }
            }
            return changed ? arr.toString() : null;
        } catch (Throwable t) {
            LOG.log("[edit] json rewrite err: " + t);
            return null;
        }
    }

    // ── 读取当前正文（内存优先，退回空） ────────────────────────────

    private static String readCurrentContent(Object uoMsg) {
        try {
            List<?> list = fragmentList(uoMsg);
            if (list != null) {
                for (Object frag : list) {
                    if (frag == null) continue;
                    String type = fragType(frag);
                    if (type != null && (type.equals("REQUEST") || type.equals("RESPONSE"))) {
                        Object content = getField(frag, "c");
                        if (content instanceof String) return (String) content;
                    }
                }
                // 没有 REQUEST/RESPONSE 就取第一个有 content 的
                for (Object frag : list) {
                    if (frag == null) continue;
                    Object content = getField(frag, "c");
                    if (content instanceof String) return (String) content;
                }
            }
        } catch (Throwable t) { LOG.log("[edit] readContent err: " + t); }
        return "";
    }

    private static List<?> fragmentList(Object uoMsg) {
        try {
            Field mf = uoMsg.getClass().getDeclaredField("m"); mf.setAccessible(true);
            Object wv0 = mf.get(uoMsg);
            if (wv0 != null) {
                Field af = wv0.getClass().getDeclaredField("a"); af.setAccessible(true);
                Object l = af.get(wv0);
                if (l instanceof List) return (List<?>) l;
            }
        } catch (Throwable ignored) {}
        try {
            Field tf = uoMsg.getClass().getDeclaredField("t"); tf.setAccessible(true);
            Object l = tf.get(uoMsg);
            if (l instanceof List) return (List<?>) l;
        } catch (Throwable ignored) {}
        return null;
    }

    /** fragment 类型：字段 a=type("REQUEST"/"RESPONSE"/"THINK"...)。 */
    private static String fragType(Object frag) {
        Object a = getField(frag, "a");
        return a instanceof String ? (String) a : null;
    }

    // ── 反射小工具 ───────────────────────────────────────────────────

    private static int callInt(Object obj, String method, int def) {
        try {
            Method m = obj.getClass().getMethod(method);
            Object r = m.invoke(obj);
            if (r instanceof Number) return ((Number) r).intValue();
        } catch (Throwable ignored) {}
        return def;
    }

    private static Object callObj(Object obj, String method) {
        try {
            Method m = obj.getClass().getMethod(method);
            return m.invoke(obj);
        } catch (Throwable ignored) { return null; }
    }

    private static Object getField(Object obj, String name) {
        try {
            Field f = obj.getClass().getDeclaredField(name); f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable ignored) { return null; }
    }

    private static void setField(Object obj, String name, Object val) throws Throwable {
        Field f = obj.getClass().getDeclaredField(name); f.setAccessible(true);
        f.setAccessible(true);
        f.set(obj, val);
    }

    private static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    private static boolean isDark(Context c) {
        return (c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private MessageEditHook() {}
}

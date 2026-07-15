package com.dsmod.probe;

import android.app.Activity;
import android.database.Cursor;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.system.Os;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class Main implements IXposedHookLoadPackage {

    private static final String TAG = "DSPROBE";
    private static final String TARGET = "com.deepseek.chat";
    static final String SELF = "com.dsmod.probe";
    private static final String LOG_PATH = "/data/data/com.deepseek.chat/files/dsprobe.log";
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    // 存储在 DeepSeek 自己的 files 目录，hook 进程和 UI 都能直接读写
    static final String PROMPT_FILE       = "/data/data/com.deepseek.chat/files/deekseep_prompt.txt";
    static final String PROMPT_LINK_FILE  = "/data/data/com.deepseek.chat/files/deekseep_prompt_link.txt";
    static final String PROMPT_SOURCE_FILE = "/data/data/com.deepseek.chat/files/deekseep_prompt_source.txt";
    static final String ENABLED_FILE      = "/data/data/com.deepseek.chat/files/deekseep_enabled";
    static final String NO_CENSOR_FILE    = "/data/data/com.deepseek.chat/files/deekseep_nocensor";
    static final String SRVLOG_FILE       = "/data/data/com.deepseek.chat/files/deekseep_srvlog";
    static final int    PICK_REQUEST      = 0xDE3E;

    // 诊断：记录服务器返回的 SSE 原始事件（受 SRVLOG_FILE 开关控制）
    static final String SRV_LOG_PATH = "/data/data/com.deepseek.chat/files/deekseep_srv.log";
    static final String SRV_LOG_EXT  = "/storage/emulated/0/deekseep_srv.log";

    // DeekseepUi 选完文件后的 UI 刷新回调
    static volatile Runnable onPickComplete;

    // 诊断：模块加载到 DeepSeek 后，首个 Activity 弹一次 Toast 确认注入生效（无需 root/日志）
    private static boolean loadToastShown = false;
    // 每个 DeepSeek 进程只向模块 StatusProvider 握手一次，写激活标记（供 SettingsActivity 判活）
    private static volatile boolean selfPinged = false;
    // 外部可见的加载标记（best-effort，宿主有存储权限时才写得进去）
    static final String LOADED_MARK_EXT = "/storage/emulated/0/deekseep_loaded.txt";

    // 首次注入 DeepSeek 时弹出的免责声明；同意后写此标记，之后不再弹
    static final String DISCLAIMER_FILE = "/data/data/com.deepseek.chat/files/deekseep_disclaimer_ok";
    private static volatile boolean disclaimerHandled = false;
    // 模块自身进程被注入后写入的激活标记，供 SettingsActivity 二次判定“已激活”
    static final String SELF_ACTIVE_MARK = "/data/data/" + SELF + "/files/deekseep_active";

    private static final String SETTINGS_CLASS = "u25";
    private static final String SETTINGS_METHOD = "i";

    private final Handler main = new Handler(Looper.getMainLooper());
    private WeakReference<Activity> curAct = new WeakReference<>(null);
    private WeakReference<TextView> btn = new WeakReference<>(null);
    private WeakReference<Object> navController = new WeakReference<>(null);

    private static synchronized void log(String msg) {
        try { XposedBridge.log(TAG + " " + msg); } catch (Throwable ignored) {}
        String line = TS.format(new Date()) + "  " + msg + "\n";
        try {
            FileWriter w = new FileWriter(LOG_PATH, true);
            w.write(line);
            w.close();
        } catch (Throwable ignored) {}
        try {
            FileWriter w = new FileWriter("/storage/emulated/0/dsprobe.log", true);
            w.write(line);
            w.close();
        } catch (Throwable ignored) {}
    }

    // 专门记录服务器返回内容的诊断日志：写 DeepSeek files 目录（root 可读），
    // 尽力也写一份到外部存储，同时镜像到框架日志（可在管理器里导出）。
    private static synchronized void srvLog(String msg) {
        String line = TS.format(new Date()) + "  " + msg + "\n";
        try {
            FileWriter w = new FileWriter(SRV_LOG_PATH, true);
            w.write(line);
            w.close();
        } catch (Throwable ignored) {}
        try {
            FileWriter w = new FileWriter(SRV_LOG_EXT, true);
            w.write(line);
            w.close();
        } catch (Throwable ignored) {}
        try { XposedBridge.log(TAG + " SRV " + msg); } catch (Throwable ignored) {}
    }

    static boolean isSrvLog() {
        return new File(SRVLOG_FILE).exists();
    }

    static void setSrvLog(boolean on) {
        try {
            File ef = new File(SRVLOG_FILE);
            if (on) overwriteTextFile(SRVLOG_FILE, "");
            else ef.delete();
        } catch (Throwable ignored) {}
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        final ClassLoader cl = lp.classLoader;
        final String pkg = lp.packageName;

        if (SELF.equals(pkg)) { markSelfActive(cl); return; }
        if (!TARGET.equals(pkg)) return;

        try { new FileWriter(LOG_PATH, false).close(); } catch (Throwable ignored) {}
        // 服务器返回诊断日志：每次应用启动清空重记（与主日志一致）
        if (isSrvLog()) {
            try { new FileWriter(SRV_LOG_PATH, false).close(); } catch (Throwable ignored) {}
            try { new FileWriter(SRV_LOG_EXT, false).close(); } catch (Throwable ignored) {}
        }
        log("module loaded (legacy), package=" + pkg);
        // 外部可见加载标记：证明模块确实被注入进了 DeepSeek 进程
        try {
            FileWriter w = new FileWriter(LOADED_MARK_EXT, false);
            w.write(TS.format(new Date()) + "  loaded into " + pkg + "\n");
            w.close();
        } catch (Throwable ignored) {}

        // 跟踪当前 Activity（并在首个 Activity 弹一次 Toast 确认注入生效）
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            XposedBridge.hookMethod(onResume, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        Activity act = (Activity) param.thisObject;
                        curAct = new WeakReference<>(act);
                        pingSelfActive(act);
                        if (!loadToastShown) {
                            loadToastShown = true;
                            try {
                                android.widget.Toast.makeText(act,
                                        "Deekseep 已注入 (v" + SettingsActivity.VERSION + ")",
                                        android.widget.Toast.LENGTH_SHORT).show();
                            } catch (Throwable ignored) {}
                        }
                        maybeShowDisclaimer(act);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) { log("hook onResume failed: " + t); }

        try {
            Method onDestroy = Activity.class.getDeclaredMethod("onDestroy");
            XposedBridge.hookMethod(onDestroy, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    try { if (curAct.get() == param.thisObject) hideButton(); } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) { log("hook onDestroy failed: " + t); }

        // 拦截 onActivityResult，捕获文件选择器结果
        try {
            Method oar = Activity.class.getDeclaredMethod("onActivityResult",
                    int.class, int.class, Intent.class);
            XposedBridge.hookMethod(oar, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        int req = (int) param.args[0];
                        int res = (int) param.args[1];
                        Object dataArg = param.args[2];
                        if (req == PICK_REQUEST) {
                            log("pick result: res=" + res + ", hasData=" + (dataArg != null));
                            if (res == Activity.RESULT_OK && dataArg != null) {
                                Intent data = (Intent) dataArg;
                                Uri uri = data.getData();
                                log("pick result uri=" + uri + ", flags=" + data.getFlags());
                                if (uri != null) {
                                    persistReadGrant((Activity) param.thisObject, data, uri);
                                    handlePickedFile((Activity) param.thisObject, uri);
                                }
                            }
                        }
                    } catch (Throwable t) { log("onActivityResult err: " + t); }
                }
            });
        } catch (Throwable t) { log("hook onActivityResult failed: " + t); }

        // hook ChatFullCompletionRequest 构造，注入系统提示词到 prompt 字段
        hookChatRequest(cl);
        // ★ 启动时清理历史注入的 <system> 前缀，避免重开后泄露到真实对话（UI 加载前跑）
        new Thread(new Runnable() { public void run() {
            try { int n = ChatEditorUi.stripAllSessions(); log("stripAllSessions cleaned=" + n); }
            catch (Throwable t) { log("stripAllSessions err: " + t); }
        }}).start();
        // hook ServerMessageHint(kb7) 构造，强制 clear_response=false
        hookSafetyRetraction(cl);
        // 诊断：抓取服务器返回的 SSE 原始事件（lv7）
        installServerCapture(cl);
        // 真正拦截点：mv.i() 应用 JSON-patch，命中 CONTENT_FILTER 就跳过
        hookContentFilterApply(cl);
        // 诊断：抓 vv7.e() 完整消息重建
        installMsgRebuildCapture(cl);
        // 第二拦截点：mv.S()/R() 直接写 status/quasi_status
        hookStatusWrite(cl);
        // 诊断：h83.h() fragment 多态反序列化选择器
        hookTemplateProbe(cl);
        // close 后整表合并 tp.u(tp, List)
        hookFinalMessageMerge(cl);
        // 单条替换 tp.q(uo)/tp.p(uo,String)/tp.a(uo,bool)（真正生效的去审查点）
        hookFinalMessageApply(cl);
        // hook 导航变化，离开设置页时移除入口按钮
        hookSettingsNavigation(cl);

        // hook 设置页主 Composable -> 显示 Deekseep 按钮
        try {
            Class<?> k = cl.loadClass(SETTINGS_CLASS);
            int n = 0;
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(SETTINGS_METHOD)) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            main.post(new Runnable() { public void run() { showButton(); } });
                        }
                    });
                    n++;
                }
            }
            log("hooked settings composable " + SETTINGS_CLASS + "." + SETTINGS_METHOD + " x" + n);
        } catch (Throwable t) { log("hook settings composable failed: " + t); }
    }

    // ── 文件操作（静态，供 DeekseepUi 调用）────────────────────────

    static void handlePickedFile(Activity act, Uri uri) {
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(act.getContentResolver().openInputStream(uri), "UTF-8"))) {
                String ln;
                while ((ln = br.readLine()) != null) sb.append(ln).append('\n');
            }
            String content = sb.toString().trim();
            overwriteTextFile(PROMPT_FILE, content);

            String displayPath = resolveDisplayPath(act, uri);
            writeText(PROMPT_SOURCE_FILE, displayPath);
            refreshPromptSymlink(displayPath);

            File promptFile = new File(PROMPT_FILE);
            log("prompt imported, length=" + content.length()
                    + ", fileExists=" + promptFile.exists()
                    + ", fileSize=" + promptFile.length()
                    + ", source=" + displayPath);
            Runnable cb = onPickComplete;
            if (cb != null) act.runOnUiThread(cb);
        } catch (Throwable t) { log("handlePickedFile err: " + t); }
    }

    static String getPromptDisplayPath() {
        try {
            String source = readSmallText(PROMPT_SOURCE_FILE);
            if (source != null && source.length() > 0) return source;
        } catch (Throwable ignored) {}
        File pf = new File(PROMPT_FILE);
        return pf.exists() && pf.length() > 0 ? pf.getAbsolutePath() : "";
    }

    static void clearPromptFiles() {
        new File(PROMPT_FILE).delete();
        new File(PROMPT_LINK_FILE).delete();
        new File(PROMPT_SOURCE_FILE).delete();
        new File(ENABLED_FILE).delete();
    }

    static boolean isEnabled() {
        return new File(ENABLED_FILE).exists();
    }

    static void setEnabled(boolean on) {
        try {
            File ef = new File(ENABLED_FILE);
            if (on) overwriteTextFile(ENABLED_FILE, "");
            else ef.delete();
        } catch (Throwable ignored) {}
    }

    static boolean isNoCensor() {
        return new File(NO_CENSOR_FILE).exists();
    }

    static void setNoCensor(boolean on) {
        try {
            File ef = new File(NO_CENSOR_FILE);
            if (on) overwriteTextFile(NO_CENSOR_FILE, "");
            else ef.delete();
        } catch (Throwable ignored) {}
    }

    private static void persistReadGrant(Activity act, Intent data, Uri uri) {
        try {
            int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (flags != 0) {
                act.getContentResolver().takePersistableUriPermission(uri, flags);
            }
        } catch (Throwable t) {
            log("takePersistableUriPermission skipped: " + t);
        }
    }

    private static String resolveDisplayPath(Activity act, Uri uri) {
        String realPath = resolveRealPath(uri);
        if (realPath != null && realPath.length() > 0) return realPath;

        String name = queryDisplayName(act, uri);
        if (name != null && name.length() > 0) return name + " (" + uri + ")";
        return uri.toString();
    }

    private static String resolveRealPath(Uri uri) {
        try {
            if ("file".equals(uri.getScheme())) return uri.getPath();
            if (!"content".equals(uri.getScheme())) return null;

            String authority = uri.getAuthority();
            if ("com.android.externalstorage.documents".equals(authority)) {
                String docId = DocumentsContract.getDocumentId(uri);
                String[] parts = docId.split(":", 2);
                String volume = parts.length > 0 ? parts[0] : "";
                String rel = parts.length > 1 ? parts[1] : "";
                if ("primary".equalsIgnoreCase(volume)) {
                    return "/storage/emulated/0/" + rel;
                }
                if ("home".equalsIgnoreCase(volume)) {
                    return "/storage/emulated/0/Documents/" + rel;
                }
                if (volume.length() > 0 && rel.length() > 0) {
                    return "/storage/" + volume + "/" + rel;
                }
            }

            if ("com.android.providers.downloads.documents".equals(authority)) {
                String docId = DocumentsContract.getDocumentId(uri);
                if (docId != null && docId.startsWith("raw:")) {
                    return docId.substring(4);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String queryDisplayName(Activity act, Uri uri) {
        Cursor c = null;
        try {
            c = act.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private static void refreshPromptSymlink(String displayPath) {
        try {
            File link = new File(PROMPT_LINK_FILE);
            link.delete();
            if (displayPath == null || !displayPath.startsWith("/")) return;
            Os.symlink(displayPath, PROMPT_LINK_FILE);
            log("prompt symlink -> " + displayPath);
        } catch (Throwable t) {
            log("prompt symlink skipped: " + t);
        }
    }

    private static void writeText(String path, String text) {
        try {
            overwriteTextFile(path, text == null ? "" : text);
        } catch (Throwable ignored) {}
    }

    private static void overwriteTextFile(String path, String text) throws Throwable {
        File file = new File(path);
        ensureWritableFile(file);
        try (FileWriter fw = new FileWriter(file, false)) {
            fw.write(text == null ? "" : text);
            fw.flush();
        }
        if (!file.exists()) {
            throw new IllegalStateException("file was not created: " + path);
        }
    }

    private static void ensureWritableFile(File file) throws Throwable {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
            throw new IllegalStateException("cannot create dir: " + parent.getAbsolutePath());
        }
        if (file.exists()) {
            if (file.isDirectory() && !file.delete()) {
                throw new IllegalStateException("path is directory and cannot delete: " + file.getAbsolutePath());
            }
            return;
        }
        if (!file.createNewFile() && !file.exists()) {
            throw new IllegalStateException("cannot create file: " + file.getAbsolutePath());
        }
    }

    private static String readSmallText(String path) {
        File f = new File(path);
        if (!f.exists() || f.length() <= 0) return null;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String ln;
            while ((ln = br.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(ln);
            }
        } catch (Throwable ignored) {
            return null;
        }
        return sb.toString().trim();
    }

    // ── ChatFullCompletionRequest 系统提示词注入 ─────────────────────

    private void hookChatRequest(ClassLoader cl) {
        try {
            Class<?> k = cl.loadClass("ew0");
            int n = 0;
            for (Constructor<?> ctor : k.getDeclaredConstructors()) {
                Class<?>[] pts = ctor.getParameterTypes();
                // 合成构造器首参为 int（kotlinx 序列化标志位），普通构造器首参为 String
                final boolean isSynthetic = pts.length > 0 && pts[0] == int.class;
                final int promptIdx = isSynthetic ? 3 : 2;
                if (pts.length <= promptIdx) continue;
                if (pts[promptIdx] != String.class) continue;
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            String sysPrompt = readPrompt();
                            if (sysPrompt != null && !sysPrompt.isEmpty()) {
                                String orig = (String) param.args[promptIdx];
                                if (orig == null) orig = "";
                                param.args[promptIdx] = "<system>\n" + sysPrompt + "\n</system>\n\n" + orig;
                                log("injected system prompt (synthetic=" + isSynthetic + ")");
                            }
                        } catch (Throwable t) { log("inject err: " + t); }
                    }
                });
                n++;
            }
            log("hooked ew0 constructors x" + n);
        } catch (Throwable t) { log("hookChatRequest failed: " + t); }
    }

    // ── 阻止内容安全审查擦除（clear_response 拦截）─────────────────
    private void hookSafetyRetraction(ClassLoader cl) {
        try {
            Class<?> k = cl.loadClass("kb7");
            int n = 0;
            for (Constructor<?> ctor : k.getDeclaredConstructors()) {
                Class<?>[] pts = ctor.getParameterTypes();
                int boolIdx = -1;
                for (int i = 0; i < pts.length; i++) {
                    if (pts[i] == boolean.class) { boolIdx = i; break; }
                }
                if (boolIdx < 0) continue;
                final int idx = boolIdx;
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            Object[] a = param.args;
                            if (isSrvLog()) {
                                StringBuilder sb = new StringBuilder("kb7(hint)");
                                for (int i = 0; i < a.length; i++) {
                                    sb.append(" arg").append(i).append('=').append(a[i]);
                                }
                                srvLog(sb.toString());
                            }
                            if (isNoCensor()) {
                                Object cur = a[idx];
                                if (Boolean.TRUE.equals(cur)) {
                                    a[idx] = Boolean.FALSE;
                                    log("blocked clear_response (kb7.arg" + idx + ")");
                                }
                            }
                        } catch (Throwable t) { log("clear_response block err: " + t); }
                    }
                });
                n++;
            }
            log("hooked kb7 constructors x" + n + " (clear_response guard)");
        } catch (Throwable t) { log("hookSafetyRetraction failed: " + t); }
    }

    // ── 诊断：抓取服务器返回的 SSE 原始事件 ─────────────────────────
    private void installServerCapture(ClassLoader cl) {
        try {
            Class<?> k = cl.loadClass("lv7");
            int n = 0;
            for (Constructor<?> ctor : k.getDeclaredConstructors()) {
                Class<?>[] pts = ctor.getParameterTypes();
                if (pts.length != 2 || pts[0] != String.class || pts[1] != String.class) continue;
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (isSrvLog()) {
                                String evt = String.valueOf(param.args[0]);
                                Object d = param.args[1];
                                String data = String.valueOf(d);
                                if (data != null && data.length() > 4000) {
                                    data = data.substring(0, 4000) + "...<truncated len=" + String.valueOf(d).length() + ">";
                                }
                                srvLog("evt=" + evt + "  data=" + data);
                            }
                        } catch (Throwable t) { srvLog("lv7 capture err: " + t); }
                    }
                });
                n++;
            }
            log("installed server capture on lv7 x" + n);
        } catch (Throwable t) { log("installServerCapture failed: " + t); }
    }

    // ── 真正的替换拦截：mv.i() JSON-patch 应用点 ────────────────────
    private void hookContentFilterApply(ClassLoader cl) {
        try {
            Class<?> k = cl.loadClass("mv");
            int n = 0;
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals("i")) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length != 4 || pts[0] != String.class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            Object a0 = param.args[0];
                            String path = a0 instanceof String ? (String) a0 : "";
                            String val = String.valueOf(param.args[1]);
                            boolean isFilter =
                                    (path.equals("fragments") && val.contains("TEMPLATE_RESPONSE"))
                                 || ((path.equals("status") || path.equals("quasi_status"))
                                        && val.contains("CONTENT_FILTER"));
                            if (isSrvLog() && (isFilter || path.equals("fragments")
                                    || path.equals("status") || path.equals("quasi_status"))) {
                                String v = val.length() > 300 ? val.substring(0, 300) + "..." : val;
                                srvLog("[CF] mv.i path=" + path + " filter=" + isFilter
                                        + " nocensor=" + isNoCensor() + " val=" + v);
                            }
                            if (isFilter) {
                                if (isSrvLog() && path.equals("fragments")) {
                                    srvLog("[CF] this.m.a@skip " + dumpMv(param.thisObject));
                                    srvLog(dumpStack());
                                }
                                if (isNoCensor()) {
                                    log("skipped CONTENT_FILTER patch mv.i(" + path + ")");
                                    if (isSrvLog()) srvLog("[CF] skipped mv.i(" + path + ")");
                                    param.setResult(null); // 跳过原 void 方法
                                }
                            }
                        } catch (Throwable t) { log("content-filter block err: " + t); }
                    }
                });
                n++;
            }
            log("hooked mv.i x" + n + " (content-filter guard)");
        } catch (Throwable t) { log("hookContentFilterApply failed: " + t); }
    }

    // 诊断：dump 当前线程调用栈
    private static String dumpStack() {
        StringBuilder sb = new StringBuilder("[CF] stack:");
        int n = 0;
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            String cn = e.getClassName();
            if (cn.startsWith("de.robv") || cn.startsWith("java.lang.reflect")
                    || cn.startsWith("io.github.libxposed") || cn.startsWith("LSPHooker")
                    || cn.startsWith("dalvik") || cn.startsWith("com.dsmod")) continue;
            sb.append("\n    ").append(cn).append('.').append(e.getMethodName());
            if (++n >= 25) break;
        }
        return sb.toString();
    }

    // 诊断：反射读取 mv 的 fragments 容器内容（mv.m = wv0, wv0.a = to7 list）
    private static String dumpMv(Object mvObj) {
        try {
            Field mf = mvObj.getClass().getDeclaredField("m");
            mf.setAccessible(true);
            Object wv0 = mf.get(mvObj);
            Field af = wv0.getClass().getDeclaredField("a");
            af.setAccessible(true);
            List<?> list = (List<?>) af.get(wv0);
            StringBuilder sb = new StringBuilder("frags=" + list.size());
            for (int i = 0; i < list.size() && i < 4; i++) {
                String s = String.valueOf(list.get(i));
                if (s.length() > 100) s = s.substring(0, 100) + "…";
                sb.append(" [").append(i).append("]").append(s);
            }
            return sb.toString();
        } catch (Throwable t) { return "dumpMv err:" + t; }
    }

    // 诊断：抓 vv7.e()（把服务端 kv 反序列化成全新 mv 消息对象）
    private void installMsgRebuildCapture(ClassLoader cl) {
        try {
            Class<?> k = cl.loadClass("vv7");
            int n = 0;
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals("e") || m.getParameterTypes().length != 1) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            Object r = param.getResult();
                            if (isSrvLog() && r != null) srvLog("[VV7] new mv " + dumpMv(r));
                        } catch (Throwable t) { srvLog("[VV7] err " + t); }
                    }
                });
                n++;
            }
            log("installed msg-rebuild capture on vv7.e x" + n);
        } catch (Throwable t) { log("installMsgRebuildCapture failed: " + t); }
    }

    // 第二拦截点：mv.S(status)/mv.R(quasi_status) 直接状态写入
    private void hookStatusWrite(ClassLoader cl) {
        try {
            Class<?> k = cl.loadClass("mv");
            int n = 0;
            for (Method m : k.getDeclaredMethods()) {
                final String mn = m.getName();
                if (!mn.equals("S") && !mn.equals("R")) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length != 1 || pts[0] != String.class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            Object a0 = param.args[0];
                            String v = a0 instanceof String ? (String) a0 : "";
                            boolean cf = v.contains("CONTENT_FILTER");
                            if (isSrvLog()) srvLog("[SR] mv." + mn + "(" + v + ") nocensor=" + isNoCensor());
                            if (cf && isNoCensor()) {
                                log("blocked mv." + mn + "(" + v + ")");
                                if (isSrvLog()) srvLog("[SR] blocked mv." + mn);
                                param.setResult(null);
                            }
                        } catch (Throwable t) { log("status-write block err: " + t); }
                    }
                });
                n++;
            }
            log("hooked mv.S/R x" + n + " (status-write guard)");
        } catch (Throwable t) { log("hookStatusWrite failed: " + t); }
    }

    // 诊断：hook h83.h(l84) fragment 反序列化选择器
    private void hookTemplateProbe(ClassLoader cl) {
        try {
            Class<?> k = cl.loadClass("h83");
            int n = 0;
            for (Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals("h")) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length != 1) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (isSrvLog()) {
                                String v = String.valueOf(param.args[0]);
                                if (v.contains("TEMPLATE_RESPONSE")) {
                                    srvLog("[TPL] h83.h TEMPLATE_RESPONSE seen");
                                    srvLog(dumpStack());
                                }
                            }
                        } catch (Throwable t) { srvLog("[TPL] err " + t); }
                    }
                });
                n++;
            }
            log("hooked h83.h x" + n + " (template probe)");
        } catch (Throwable t) { log("hookTemplateProbe failed: " + t); }
    }

    // ── close 后整表合并 tp.u(tp, List) ──────────────────
    private void hookFinalMessageMerge(ClassLoader cl) {
        try {
            final Class<?> tpk = cl.loadClass("tp");
            final Field fField = tpk.getDeclaredField("f");
            fField.setAccessible(true);
            int n = 0;
            for (Method m : tpk.getDeclaredMethods()) {
                if (!m.getName().equals("u")) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length != 2 || !List.class.isAssignableFrom(pts[1])) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            Object tp = param.args[0];
                            Object rawList = param.args[1];
                            if (tp != null && rawList instanceof List) {
                                List<?> list = (List<?>) rawList;
                                Map<?, ?> fmap = null;
                                try { fmap = (Map<?, ?>) fField.get(tp); } catch (Throwable ignored) {}
                                boolean nc = isNoCensor();
                                ArrayList<Object> copy = new ArrayList<>(list);
                                boolean changed = false;
                                for (int i = 0; i < copy.size(); i++) {
                                    Object msg = copy.get(i);
                                    if (msg == null) continue;
                                    String status = callStr(msg, "D");
                                    String quasi = callStr(msg, "x");
                                    boolean cf = (status != null && status.contains("CONTENT_FILTER"))
                                            || (quasi != null && quasi.contains("CONTENT_FILTER"));
                                    Integer id = callInt(msg, "u");
                                    if (isSrvLog()) {
                                        srvLog("[FM] merge idx=" + i + " id=" + id
                                                + " status=" + status + " quasi=" + quasi + " cf=" + cf);
                                    }
                                    if (!cf || !nc || id == null || fmap == null) continue;
                                    Object existing = fmap.get(id);
                                    if (existing == null || existing == msg) continue;
                                    String exStatus = callStr(existing, "D");
                                    String exQuasi = callStr(existing, "x");
                                    boolean exCf = (exStatus != null && exStatus.contains("CONTENT_FILTER"))
                                            || (exQuasi != null && exQuasi.contains("CONTENT_FILTER"));
                                    if (exCf) continue;
                                    copy.set(i, existing);
                                    changed = true;
                                    log("kept original msg id=" + id + " over CONTENT_FILTER");
                                    if (isSrvLog()) srvLog("[FM] kept original id=" + id
                                            + " origStatus=" + exStatus);
                                }
                                if (changed) {
                                    param.args[1] = copy;
                                }
                            }
                        } catch (Throwable t) { log("final-merge guard err: " + t); }
                    }
                });
                n++;
            }
            log("hooked tp.u x" + n + " (final-merge guard)");
        } catch (Throwable t) { log("hookFinalMessageMerge failed: " + t); }
    }

    // ── 单条消息替换拦截：tp.q(uo)/tp.p(uo,String)/tp.a(uo,bool) ─────────
    private void hookFinalMessageApply(ClassLoader cl) {
        try {
            final Class<?> tpk = cl.loadClass("tp");
            final Field fField = tpk.getDeclaredField("f");
            fField.setAccessible(true);
            final Class<?> uok = cl.loadClass("uo");
            int n = 0;
            for (Method m : tpk.getDeclaredMethods()) {
                final String mn = m.getName();
                if (!mn.equals("q") && !mn.equals("p") && !mn.equals("a")) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length < 1 || !uok.isAssignableFrom(pts[0])) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            Object tp = param.thisObject;
                            Object msg = param.args[0];
                            if (tp != null && msg != null) {
                                String status = callStr(msg, "D");
                                String quasi = callStr(msg, "x");
                                boolean cf = (status != null && status.contains("CONTENT_FILTER"))
                                        || (quasi != null && quasi.contains("CONTENT_FILTER"));
                                Integer id = callInt(msg, "u");
                                if (isSrvLog())
                                    srvLog("[FA] tp." + mn + " id=" + id + " status=" + status
                                            + " quasi=" + quasi + " cf=" + cf);
                                if (cf && isNoCensor() && id != null) {
                                    Map<?, ?> fmap = (Map<?, ?>) fField.get(tp);
                                    Object existing = fmap != null ? fmap.get(id) : null;
                                    if (existing != null && existing != msg) {
                                        String exS = callStr(existing, "D");
                                        String exQ = callStr(existing, "x");
                                        boolean exCf = (exS != null && exS.contains("CONTENT_FILTER"))
                                                || (exQ != null && exQ.contains("CONTENT_FILTER"));
                                        if (!exCf) {
                                            param.args[0] = existing;
                                            log("tp." + mn + " kept original id=" + id + " over CONTENT_FILTER");
                                            if (isSrvLog()) srvLog("[FA] kept original id=" + id + " origStatus=" + exS);
                                        }
                                    }
                                }
                            }
                        } catch (Throwable t) { log("final-apply guard err: " + t); }
                    }
                });
                n++;
            }
            log("hooked tp.q/p/a x" + n + " (final-apply guard)");
        } catch (Throwable t) { log("hookFinalMessageApply failed: " + t); }
    }

    // 反射调用无参方法返回字符串（uo.D()=status / uo.x()=quasi_status）
    private static String callStr(Object obj, String method) {
        try {
            Method m = obj.getClass().getMethod(method);
            Object r = m.invoke(obj);
            return r == null ? null : String.valueOf(r);
        } catch (Throwable t) { return null; }
    }

    // 反射调用无参方法返回 int（uo.u()=消息id）
    private static Integer callInt(Object obj, String method) {
        try {
            Method m = obj.getClass().getMethod(method);
            Object r = m.invoke(obj);
            if (r instanceof Integer) return (Integer) r;
            if (r instanceof Number) return ((Number) r).intValue();
            return null;
        } catch (Throwable t) { return null; }
    }

    private String readPrompt() {
        try {
            File ef = new File(ENABLED_FILE);
            if (!ef.exists()) return null;

            String linked = readSmallText(PROMPT_LINK_FILE);
            if (linked != null && linked.length() > 0) return linked;

            String copied = readSmallText(PROMPT_FILE);
            if (copied != null && copied.length() > 0) return copied;
        } catch (Throwable t) { return null; }
        return null;
    }

    // ── 设置页入口生命周期 ─────────────────────────────────────────

    private void hookSettingsNavigation(ClassLoader cl) {
        try {
            Class<?> nav = cl.loadClass("rm5");
            for (Method m : nav.getDeclaredMethods()) {
                if (!m.getName().equals("n") || m.getParameterTypes().length != 2) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        rememberNavController(param.thisObject);
                        scheduleRouteCheck(param.thisObject);
                    }
                });
                log("hooked nav route rm5.n");
                break;
            }
            hookNavStateMethod(nav, "b");
            hookNavStateMethod(nav, "m");
            hookNavStateMethod(nav, "q");
            hookNavStateMethod(nav, "r");
            hookNavStateMethod(nav, "u");
        } catch (Throwable t) { log("hook nav route failed: " + t); }

        try {
            Class<?> gf8 = cl.loadClass("gf8");
            for (Method m : gf8.getDeclaredMethods()) {
                if (!m.getName().equals("A0") || m.getParameterTypes().length != 1) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object nav = param.args[0];
                        if (nav != null) {
                            rememberNavController(nav);
                            scheduleRouteCheck(nav);
                        } else {
                            main.post(new Runnable() { public void run() { hideButton(); } });
                        }
                    }
                });
                log("hooked nav pop gf8.A0");
                break;
            }
        } catch (Throwable t) { log("hook nav pop failed: " + t); }
    }

    private static boolean isSettingsRootRoute(Object route) {
        if (route == null) return false;
        String n = route.getClass().getName();
        return n.endsWith(".yc7") || n.endsWith(".vc7") || n.equals("yc7") || n.equals("vc7");
    }

    private void hookNavStateMethod(Class<?> nav, String name) {
        int count = 0;
        for (Method m : nav.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    rememberNavController(param.thisObject);
                    scheduleRouteCheck(param.thisObject);
                }
            });
            count++;
        }
        log("hooked nav state rm5." + name + " x" + count);
    }

    private void rememberNavController(Object nav) {
        if (nav != null) navController = new WeakReference<>(nav);
    }

    private void scheduleRouteCheck(final Object nav) {
        main.postDelayed(new Runnable() {
            public void run() { syncButtonWithRoute(nav); }
        }, 120);
    }

    private void syncButtonWithRoute(Object nav) {
        try {
            if (btn.get() == null) return;
            String route = currentRoute(nav != null ? nav : navController.get());
            if (route == null || route.length() == 0) return;
            if (!isSettingsRootRouteName(route)) {
                log("route left settings: " + route);
                hideButton();
            } else {
                log("route still settings: " + route);
            }
        } catch (Throwable t) { log("sync route failed: " + t); }
    }

    private static boolean isSettingsRootRouteName(String route) {
        return route.contains("SettingsNestedGraph.SettingsRoute")
                || route.equals("vc7")
                || route.endsWith(".vc7")
                || route.contains(" route=vc7");
    }

    private static String currentRoute(Object nav) {
        if (nav == null) return null;
        try {
            Method i = nav.getClass().getDeclaredMethod("i");
            i.setAccessible(true);
            Object dest = i.invoke(nav);
            if (dest == null) return null;

            String route = stringField(dest, "g");
            if (route != null && route.length() > 0) return route;
            return String.valueOf(dest);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String stringField(Object obj, String name) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(obj);
            return v instanceof String ? (String) v : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ── 自我激活标记 ──────────────────────────────────────────────

    // 从 DeepSeek 进程（已确认被注入）向模块 StatusProvider 握手一次，
    // 由模块进程以自身 uid 写激活标记——FPA 场景下这是唯一无 root 可靠通道。
    private static void pingSelfActive(final Activity act) {
        if (selfPinged || act == null) return;
        selfPinged = true;
        try {
            Uri uri = Uri.parse("content://com.dsmod.probe.status");
            android.os.Bundle r = act.getContentResolver().call(uri, "ping", null, null);
            log("selfActive ping ok=" + (r != null && r.getBoolean("ok")));
        } catch (Throwable t) {
            // 多为 Android 11+ 包可见性过滤（DeepSeek 未在 <queries> 声明模块）；记录以便排查
            selfPinged = false;
            log("selfActive ping failed: " + t);
        }
    }

    private void markSelfActive(ClassLoader cl) {
        try {
            Class<?> a = cl.loadClass("com.dsmod.probe.SettingsActivity");
            for (Method m : a.getDeclaredMethods()) {
                if (m.getName().equals("isModuleActive")) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult(Boolean.TRUE);
                        }
                    });
                }
            }
        } catch (Throwable ignored) {}
        // 二次判据：在模块自身进程写一个新鲜的激活标记，SettingsActivity 读它兜底
        try {
            File mf = new File(SELF_ACTIVE_MARK);
            File dir = mf.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            FileWriter w = new FileWriter(mf, false);
            w.write(String.valueOf(System.currentTimeMillis()));
            w.close();
        } catch (Throwable ignored) {}
    }

    // 首次注入 DeepSeek 时弹出免责声明：拒绝退出，同意后写标记不再弹
    private void maybeShowDisclaimer(final Activity act) {
        if (disclaimerHandled) return;
        try {
            if (new File(DISCLAIMER_FILE).exists()) { disclaimerHandled = true; return; }
        } catch (Throwable ignored) {}
        disclaimerHandled = true;
        if (act == null || act.isFinishing()) return;
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    String msg =
                        "本模块（Deekseep）通过 Xposed 框架修改 DeepSeek 的运行行为，使用前请知悉：\n\n"
                        + "• 风险自担：因使用本模块产生的一切后果，均由你本人承担。\n"
                        + "• 封号风险：修改客户端行为可能违反 DeepSeek 用户协议，账号存在被限制或封禁的风险。\n"
                        + "• 数据风险：注入过程可能影响消息、历史记录等数据，请自行备份。\n"
                        + "• 恶意用途：本模块仅供个人学习与研究，切勿用于任何违法或恶意行为。\n\n"
                        + "点击“同意”表示你已阅读并接受上述风险；点击“拒绝”将退出 DeepSeek。";
                    new android.app.AlertDialog.Builder(act)
                        .setTitle("Deekseep 免责声明")
                        .setMessage(msg)
                        .setCancelable(false)
                        .setPositiveButton("同意", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int which) {
                                try {
                                    FileWriter w = new FileWriter(DISCLAIMER_FILE, false);
                                    w.write(String.valueOf(System.currentTimeMillis()));
                                    w.close();
                                } catch (Throwable ignored) {}
                                d.dismiss();
                            }
                        })
                        .setNegativeButton("拒绝", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int which) {
                                try { d.dismiss(); } catch (Throwable ignored) {}
                                try { act.finishAffinity(); } catch (Throwable ignored) {}
                                android.os.Process.killProcess(android.os.Process.myPid());
                                System.exit(0);
                            }
                        })
                        .show();
                } catch (Throwable t) { log("disclaimer show err: " + t); }
            }
        });
    }

    private void showButton() {
        try {
            final Activity act = curAct.get();
            if (act == null || act.isFinishing()) return;

            TextView existing = btn.get();
            if (existing != null && existing.getContext() == act && existing.getParent() != null) {
                existing.setVisibility(View.VISIBLE);
                return;
            }

            ViewGroup content = act.findViewById(android.R.id.content);
            if (content == null) return;

            TextView b = DeekseepUi.createEntryButton(act, new View.OnClickListener() {
                public void onClick(View v) {
                    try { DeekseepUi.showPage(act); }
                    catch (Throwable t) { log("showPage failed: " + t); }
                }
            });

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.topMargin = DeekseepUi.statusBarHeight(act) + DeekseepUi.dp(act, 8);
            lp.rightMargin = DeekseepUi.dp(act, 12);
            content.addView(b, lp);
            btn = new WeakReference<>(b);
            log("button added on " + act.getClass().getName());
            scheduleRouteCheck(navController.get());
        } catch (Throwable t) { log("showButton failed: " + t); }
    }

    private void hideButton() {
        try {
            TextView existing = btn.get();
            if (existing == null) return;
            ViewGroup parent = (ViewGroup) existing.getParent();
            if (parent != null) parent.removeView(existing);
            btn = new WeakReference<>(null);
            log("button removed");
        } catch (Throwable t) { log("hideButton failed: " + t); }
    }
}

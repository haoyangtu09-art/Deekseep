package com.dsmod.probe;

import android.app.Activity;
import android.app.Dialog;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.system.Os;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

public class Main extends XposedModule {

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
    static final String AUTO_BACKUP_FILE  = "/data/data/com.deepseek.chat/files/deekseep_auto_backup";
    static final String EXPERT_UNLOCK_FILE = "/data/data/com.deepseek.chat/files/deekseep_expert_unlock";
    static final String CHAT_MULTISELECT_FILE = "/data/data/com.deepseek.chat/files/deekseep_chat_multiselect";
    static final int    PICK_REQUEST      = 0xDE3E;

    // ── 侧栏聊天记录多选删除（sidebar multi-select delete）─────────────
    private static final Map<String, Object> SIDEBAR_DELETE_ACTIONS = new HashMap<>();
    private static final Map<String, Object> SIDEBAR_CLICK_ACTIONS = new HashMap<>();
    private static final HashSet<String> SIDEBAR_SELECTED = new HashSet<>();
    private static volatile View sidebarSelectOverlay;
    private static volatile boolean sidebarSelectMode = false;
    private static volatile String sidebarCurrentSid;
    private static volatile long sidebarBoundsLogAt;
    // 本次多选会话是否已确认看到行处于屏内（左坐标非负）；用于收起检测的解锁
    private static volatile boolean sidebarConfirmedOpen = false;
    // 会话行真实 Compose 坐标（decor/window 空间：left,top,right,bottom），由 onGloballyPositioned 回调写入
    private static final Map<String, int[]> SIDEBAR_ROW_BOUNDS = new ConcurrentHashMap<>();
    // 每个 sid 复用同一个 ib3 回调，保证 lw5 元素 equals 稳定，避免 Compose 节点抖动
    private static final Map<String, Object> SIDEBAR_BOUNDS_CB = new HashMap<>();
    // bm4(LayoutCoordinates) 方法：i()=isAttached, k()=size(packed long), w(long)=localToWindow
    private static volatile Method BM4_I, BM4_K, BM4_W;

    // 专家模式解锁：俘获任意"已启用"模型的真 feature 模板，回填给 expert
    private static volatile Object tplThink;
    private static volatile Object tplSearch;
    private static volatile Object tplFile;
    // sf5(模型配置) 字段：a=model_type f=enabled g=switchable j=think k=search l=file(gf5)；GF5_C=gf5.c 最大文件数
    private static Field EX_A, EX_F, EX_G, EX_J, EX_K, EX_L, GF5_C;
    private static final java.util.List<Object> expertInsts = new java.util.ArrayList<>();

    // ── 专家图片→视觉描述中继（expert-image → vision relay）────────────────
    // ★正式功能开关：expert 模式带图 → 后台视觉描述中继。存在=开启。
    static final String EXPERT_RELAY_FILE = "/data/data/com.deepseek.chat/files/deekseep_expert_relay";
    // 已成功走过中继的原会话。按 sid 落独立标记，重启后历史同步不再依赖服务端模型字段。
    static final String EXPERT_RELAY_SESSION_DIR =
            "/data/data/com.deepseek.chat/files/deekseep_expert_relay_sessions";
    static final String RELAY_PROMPT_MARKER = "【图片内容（自动识别）】";
    // 中继捕获的图片 fragment（qs7 JSON）按原会话 sid 落盘，供强杀重开后 pw0/fm8 注入。
    static final String RELAY_IMAGE_DIR =
            "/data/data/com.deepseek.chat/files/deekseep_relay_images";
    // 发给 vision 的中性描述指令（绝不能带用户越狱系统提示，否则 vision 会拒答）。
    static final String VISION_DESCRIBE_PROMPT =
            "请客观描述这张图片，100到200字：包括主要事物、颜色、场景、画面细节，以及逐字转录图中出现的所有文字。只做客观描述，不评价、不拒绝、不添加与图片无关的内容。";
    // 视觉探针诊断日志（私有目录，直写，最可靠）
    static final String RELAY_LOG_PATH = "/data/data/com.deepseek.chat/files/deekseep_vision.log";
    private static final String[] IMAGE_EXTS = {"jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif"};

    // 视觉中继状态：活着的 r92(transport 入口)、q71(PoW 管理器)、fm8(WCDB 仓库)实例
    private static volatile Object liveR92;
    private static volatile Object liveQ71;
    private static volatile Object liveFm8;
    private static final HashSet<String> expertRelaySessionIds = new HashSet<>();
    // 发送点(fu0.y/uu0.y)捕获的图片 fp 列表：主线程同栈传给紧随其后的 r92.b hook。
    private static final ThreadLocal<List> tlPendingFps = new ThreadLocal<>();
    // 把捕获到的 List<fp> 挂到对应 ew0 上（relay 在收集时/IO 线程跑，ThreadLocal 到不了）。
    private static final Map<Object, List> ew0Fps =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object, List>());
    // 已在处理中的 expert 请求（弱引用集合，防同一对象被 hook 重复处理）
    private static final java.util.Set<Object> relaySeen =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Object, Boolean>());

    // 诊断：记录服务器返回的 SSE 原始事件（受 SRVLOG_FILE 开关控制）
    static final String SRV_LOG_PATH = "/data/data/com.deepseek.chat/files/deekseep_srv.log";
    static final String SRV_LOG_EXT  = "/storage/emulated/0/deekseep_srv.log";

    // DeekseepUi 选完文件后的 UI 刷新回调
    static volatile Runnable onPickComplete;

    // 诊断：模块加载到 DeepSeek 后，首个 Activity 弹一次 Toast 确认注入生效（无需 root/日志）
    private static boolean loadToastShown = false;
    // 外部可见的加载标记（best-effort，宿主有存储权限时才写得进去）
    // 注意：旧 legacy 模块曾用另一 uid 写过同名外部文件(-rw-rw----)，modern 无法覆盖/追加，
    // 故 modern 一律用带 _m 后缀的“自己新建、自己拥有”的外部文件，Termux 可按 media_rw 组读取。
    static final String LOADED_MARK_EXT = "/storage/emulated/0/deekseep_loaded_m.txt";
    // modern 专属外部镜像日志（新文件，避免与 legacy-owned 文件权限冲突导致静默写失败）
    static final String EXT_MAIN_LOG   = "/storage/emulated/0/dsprobe_m.log";
    static final String EXT_VISION_LOG = "/storage/emulated/0/deekseep_vision_m.log";
    static final String EXT_CRASH_LOG  = "/storage/emulated/0/dsprobe_crash.log";

    // 首次注入 DeepSeek 时弹出的免责声明；同意后写此标记，之后不再弹
    static final String DISCLAIMER_FILE = "/data/data/com.deepseek.chat/files/deekseep_disclaimer_ok";
    private static volatile boolean disclaimerHandled = false;
    // 模块自身进程被注入后写入的激活标记，供 SettingsActivity 二次判定“已激活”
    static final String SELF_ACTIVE_MARK = "/data/data/" + SELF + "/files/deekseep_active";

    private static final String SETTINGS_CLASS = "u25";
    private static final String SETTINGS_METHOD = "i";

    // 现代 API：模块实例，供静态 log 走框架日志
    private static volatile Main MODULE;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WeakReference<Activity> curAct = new WeakReference<>(null);
    private WeakReference<TextView> btn = new WeakReference<>(null);
    private WeakReference<Object> navController = new WeakReference<>(null);

    static synchronized void log(String msg) {
        try { Main m = MODULE; if (m != null) m.log(Log.INFO, TAG, msg); } catch (Throwable ignored) {}
        String line = TS.format(new Date()) + "  " + msg + "\n";
        try {
            FileWriter w = new FileWriter(LOG_PATH, true);
            w.write(line);
            w.close();
        } catch (Throwable ignored) {}
        try {
            FileWriter w = new FileWriter(EXT_MAIN_LOG, true);
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
        try { Main m = MODULE; if (m != null) m.log(Log.INFO, TAG, "SRV " + msg); } catch (Throwable ignored) {}
    }

    // 视觉中继诊断日志：私有目录直写为主，同时尽力镜像一份到公共目录。
    static synchronized void extLog(String msg) {
        try { Main m = MODULE; if (m != null) m.log(Log.INFO, TAG, msg); } catch (Throwable ignored) {}
        String line = TS.format(new Date()) + "  " + msg + "\n";
        try {
            FileWriter w = new FileWriter(RELAY_LOG_PATH, true);
            w.write(line);
            w.close();
        } catch (Throwable ignored) {}
        try {
            FileWriter w = new FileWriter(EXT_VISION_LOG, true);
            w.write(line);
            w.close();
        } catch (Throwable ignored) {}
    }

    private static volatile boolean crashHandlerInstalled = false;
    static synchronized void installCrashHandler() {
        if (crashHandlerInstalled) return;
        crashHandlerInstalled = true;
        try {
            final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override public void uncaughtException(Thread t, Throwable e) {
                    try {
                        String line = TS.format(new Date()) + "  UNCAUGHT thread=" + t.getName()
                                + "\n" + android.util.Log.getStackTraceString(e) + "\n";
                        try { FileWriter w = new FileWriter(EXT_CRASH_LOG, true); w.write(line); w.close(); } catch (Throwable ignored) {}
                        try { FileWriter w = new FileWriter("/data/data/com.deepseek.chat/files/dsprobe_crash.log", true); w.write(line); w.close(); } catch (Throwable ignored) {}
                    } catch (Throwable ignored) {}
                    if (prev != null) prev.uncaughtException(t, e);
                }
            });
        } catch (Throwable ignored) {}
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
    public void onPackageLoaded(PackageLoadedParam param) {
        MODULE = this;
        final ClassLoader cl = param.getDefaultClassLoader();
        final String pkg = param.getPackageName();

        if (SELF.equals(pkg)) { markSelfActive(cl); return; }
        if (!TARGET.equals(pkg)) return;

        // 崩溃捕获：把未捕获异常栈写到 modern 自己新建的外部文件(Termux 可读)，
        // 用于诊断“上传图片点发送直接闪退”这类无 root/无 logcat 场景的崩溃。
        installCrashHandler();

        try { new FileWriter(LOG_PATH, false).close(); } catch (Throwable ignored) {}
        // 服务器返回诊断日志：每次应用启动清空重记（与主日志一致）
        if (isSrvLog()) {
            try { new FileWriter(SRV_LOG_PATH, false).close(); } catch (Throwable ignored) {}
            try { new FileWriter(SRV_LOG_EXT, false).close(); } catch (Throwable ignored) {}
        }
        log("module loaded (modern), package=" + pkg);
        // 自动备份：距上次>24h 且开关开启时后台复制数据库
        new Thread(new Runnable() { public void run() {
            try { DeekseepTools.maybeAutoBackup(); } catch (Throwable ignored) {}
        }}).start();
        // 外部可见加载标记：证明模块确实被注入进了 DeepSeek 进程
        try {
            FileWriter w = new FileWriter(LOADED_MARK_EXT, false);
            w.write(TS.format(new Date()) + "  loaded into " + pkg + "\n");
            w.close();
        } catch (Throwable ignored) {}

        // 跟踪当前 Activity（并在首个 Activity 弹一次 Toast 确认注入生效）
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            hook(onResume).intercept(new Hooker() {
                @Override public Object intercept(Chain chain) throws Throwable {
                    Object r = chain.proceed();
                    try {
                        Activity act = (Activity) chain.getThisObject();
                        curAct = new WeakReference<>(act);
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
                    return r;
                }
            });
        } catch (Throwable t) { log("hook onResume failed: " + t); }

        try {
            Method onDestroy = Activity.class.getDeclaredMethod("onDestroy");
            hook(onDestroy).intercept(new Hooker() {
                @Override public Object intercept(Chain chain) throws Throwable {
                    try { if (curAct.get() == chain.getThisObject()) hideButton(); } catch (Throwable ignored) {}
                    return chain.proceed();
                }
            });
        } catch (Throwable t) { log("hook onDestroy failed: " + t); }

        // 拦截 onActivityResult，捕获文件选择器结果
        try {
            Method oar = Activity.class.getDeclaredMethod("onActivityResult",
                    int.class, int.class, Intent.class);
            hook(oar).intercept(new Hooker() {
                @Override public Object intercept(Chain chain) throws Throwable {
                    Object r = chain.proceed();
                    try {
                        int req = (int) chain.getArg(0);
                        int res = (int) chain.getArg(1);
                        Object dataArg = chain.getArg(2);
                        if (req == PICK_REQUEST) {
                            log("pick result: res=" + res + ", hasData=" + (dataArg != null));
                            if (res == Activity.RESULT_OK && dataArg != null) {
                                Intent data = (Intent) dataArg;
                                Uri uri = data.getData();
                                log("pick result uri=" + uri + ", flags=" + data.getFlags());
                                if (uri != null) {
                                    persistReadGrant((Activity) chain.getThisObject(), data, uri);
                                    handlePickedFile((Activity) chain.getThisObject(), uri);
                                }
                            }
                        }
                    } catch (Throwable t) { log("onActivityResult err: " + t); }
                    return r;
                }
            });
        } catch (Throwable t) { log("hook onActivityResult failed: " + t); }

        // hook ChatFullCompletionRequest 构造，注入系统提示词到 prompt 字段
        hookChatRequest(cl);
        // ★ 在宿主读取当前会话前同步修复无 id THINK，避免后台线程与首屏加载竞态。
        try {
            int n = ChatEditorUi.repairMalformedThinkFragmentsAllSessions();
            log("repairMalformedThinkFragments fixed=" + n);
        } catch (Throwable t) { log("repairMalformedThinkFragments err: " + t); }
        // 历史 <system> 前缀清理仍放后台执行，避免无关的全库维护阻塞启动。
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
        // ★ 专家模式(expert)解锁 聊天/搜索/上传文件（sf5 构造后强改 final 字段）
        hookExpertUnlock(cl);
        // ★ 上传门禁兜底：在 y91.a 真正读 sf5.l 判空前，就地俘获并点亮被消费的那个 sf5 实例（诊断+修复）
        try { installExpertUploadGate(cl); } catch (Throwable t) { log("installExpertUploadGate wiring failed: " + t); }
        // ★ 专家图片→视觉描述中继：抓 transport(r92)、PoW(q71)、历史图片保留(fm8/pw0)、发送点图片(fu0/uu0)
        try { installNetworkPayloadCapture(cl); } catch (Throwable t) { log("installNetworkPayloadCapture wiring failed: " + t); }
        try { installPowManagerCapture(cl); } catch (Throwable t) { log("installPowManagerCapture wiring failed: " + t); }
        try { installExpertHistoryImagePreserver(cl); } catch (Throwable t) { log("installExpertHistoryImagePreserver wiring failed: " + t); }
        try { installExpertImageFpCapture(cl); } catch (Throwable t) { log("installExpertImageFpCapture wiring failed: " + t); }
        // hook 导航变化，离开设置页时移除入口按钮
        hookSettingsNavigation(cl);
        // ★ 侧栏聊天记录多选删除（modern Compose Hooker，手机端适配）
        try { hookSidebarMultiSelectDelete(cl); } catch (Throwable t) { log("hookSidebarMultiSelectDelete wiring failed: " + t); }
        try { hookSidebarToggleCleanup(cl); } catch (Throwable t) { log("hookSidebarToggleCleanup wiring failed: " + t); }

        // hook 设置页主 Composable -> 显示 Deekseep 按钮
        try {
            Class<?> k = cl.loadClass(SETTINGS_CLASS);
            int n = 0;
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(SETTINGS_METHOD)) {
                    hook(m).intercept(new Hooker() {
                        @Override public Object intercept(Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            main.post(new Runnable() { public void run() { showButton(); } });
                            return r;
                        }
                    });
                    n++;
                }
            }
            log("hooked settings composable " + SETTINGS_CLASS + "." + SETTINGS_METHOD + " x" + n);
        } catch (Throwable t) { log("hook settings composable failed: " + t); }
    }

    // ── 侧栏聊天记录多选删除（modern Compose Hooker 版）────────────────

    static boolean isChatMultiSelect() {
        return new File(CHAT_MULTISELECT_FILE).exists();
    }

    static void setChatMultiSelect(boolean on) {
        try {
            File ef = new File(CHAT_MULTISELECT_FILE);
            if (on) overwriteTextFile(CHAT_MULTISELECT_FILE, "");
            else {
                ef.delete();
                exitSidebarSelectMode();
            }
        } catch (Throwable ignored) {}
    }

    // 会话行渲染器 mc.e(tp,..,xa3 click,..,xa3 delete,..,qg5 modifier,..) 12 参。
    // modern：拦到后按需改 args[4]=长按代理、args[9]=追加坐标捕获的 Modifier，再一次性 proceed(args)。
    private void hookSidebarMultiSelectDelete(final ClassLoader cl) {
        try {
            final Class<?> mc = cl.loadClass("mc");
            final Class<?> tp = cl.loadClass("tp");
            final Class<?> xa3 = cl.loadClass("xa3");
            int n = 0;
            for (Method m : mc.getDeclaredMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (!m.getName().equals("e") || pts.length != 12 || pts[0] != tp) continue;
                if (!xa3.isAssignableFrom(pts[4]) || !xa3.isAssignableFrom(pts[7])) continue;
                hook(m).intercept(new Hooker() {
                    @Override public Object intercept(Chain chain) throws Throwable {
                        Object[] args = null;
                        try {
                            Object[] a = chain.getArgs().toArray();
                            final Object tpObj = a[0];
                            final String sid = String.valueOf(fieldByName(tpObj, "a"));
                            if (sid != null && sid.length() > 0 && !"null".equals(sid)) {
                                if (Boolean.TRUE.equals(a[2])) {
                                    String oldSid = sidebarCurrentSid;
                                    sidebarCurrentSid = sid;
                                    if (sidebarSelectMode && oldSid != null && !oldSid.equals(sid)) {
                                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                                            public void run() { slideOutSidebarOverlayAndExit(); }
                                        });
                                    }
                                }
                                synchronized (SIDEBAR_DELETE_ACTIONS) {
                                    if (a[3] != null) SIDEBAR_CLICK_ACTIONS.put(sid, a[3]);
                                    if (a[7] != null) SIDEBAR_DELETE_ACTIONS.put(sid, a[7]);
                                }
                                if (isChatMultiSelect()) {
                                    a[4] = buildSidebarLongPressProxy(cl, sid);
                                    if (a.length > 9 && a[9] != null) {
                                        Object wrapped = wrapModifierWithBoundsCapture(cl, sid, a[9]);
                                        if (wrapped != null) a[9] = wrapped;
                                    }
                                    args = a;
                                }
                            }
                        } catch (Throwable t) { log("sidebar multi-select hook row err: " + t); }
                        return args != null ? chain.proceed(args) : chain.proceed();
                    }
                });
                n++;
            }
            log("installed sidebar multi-select delete hook mc.e x" + n);
        } catch (Throwable t) { log("hookSidebarMultiSelectDelete failed: " + t); }
    }

    private Object buildSidebarLongPressProxy(final ClassLoader cl, final String sid) throws Exception {
        final Class<?> xa3 = cl.loadClass("xa3");
        return Proxy.newProxyInstance(cl, new Class[]{xa3}, new InvocationHandler() {
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if ("toString".equals(name)) return "DeekseepSidebarMultiSelect";
                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                if ("equals".equals(name)) return proxy == (args == null ? null : args[0]);
                if ("u".equals(name) && method.getParameterTypes().length == 0) {
                    final Activity act = curAct.get();
                    if (act != null) {
                        act.runOnUiThread(new Runnable() {
                            public void run() { enterSidebarSelectMode(act, sid); }
                        });
                    }
                    return ui8Unit(cl);
                }
                return ui8Unit(cl);
            }
        });
    }

    // 把 onGloballyPositioned(callback) 追加到会话行的 Modifier(qg5) 上：modifier.then(new lw5(cb))
    private Object wrapModifierWithBoundsCapture(ClassLoader cl, String sid, Object modifier) {
        try {
            Class<?> qg5 = cl.loadClass("qg5");
            if (!qg5.isInstance(modifier)) return null;
            Class<?> ib3 = cl.loadClass("ib3");
            Class<?> lw5 = cl.loadClass("lw5");
            Object cb;
            synchronized (SIDEBAR_BOUNDS_CB) {
                cb = SIDEBAR_BOUNDS_CB.get(sid);
                if (cb == null) { cb = buildBoundsCallback(cl, sid); SIDEBAR_BOUNDS_CB.put(sid, cb); }
            }
            java.lang.reflect.Constructor<?> ctor = lw5.getDeclaredConstructor(ib3);
            ctor.setAccessible(true);
            Object element = ctor.newInstance(cb);
            Method w = qg5.getMethod("w", qg5);
            return w.invoke(modifier, element);
        } catch (Throwable t) { log("wrap sidebar bounds capture failed: " + t); return null; }
    }

    // ib3(Function1) 代理：Compose 布局后回调 g(bm4 coords)，把行的窗口坐标写入 SIDEBAR_ROW_BOUNDS
    private Object buildBoundsCallback(final ClassLoader cl, final String sid) throws Exception {
        final Class<?> ib3 = cl.loadClass("ib3");
        final Class<?> bm4 = cl.loadClass("bm4");
        if (BM4_I == null) {
            BM4_I = bm4.getMethod("i");
            BM4_K = bm4.getMethod("k");
            BM4_W = bm4.getMethod("w", long.class);
        }
        return Proxy.newProxyInstance(cl, new Class[]{ib3}, new InvocationHandler() {
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if ("toString".equals(name)) return "DeekseepSidebarBounds";
                if ("hashCode".equals(name)) return sid.hashCode();
                if ("equals".equals(name)) return proxy == (args == null ? null : args[0]);
                if ("g".equals(name) && args != null && args.length == 1 && args[0] != null) {
                    try {
                        Object coords = args[0];
                        if (Boolean.TRUE.equals(BM4_I.invoke(coords))) {
                            long size = (Long) BM4_K.invoke(coords);
                            int wpx = (int) (size >> 32);
                            int hpx = (int) (size & 0xFFFFFFFFL);
                            // 本 build 的 bm4.w(long) 实为 windowToLocal：w(0,0) 返回行窗口坐标的负值，取负还原。
                            long pos = (Long) BM4_W.invoke(coords, 0L);
                            int x = -(int) Float.intBitsToFloat((int) (pos >> 32));
                            int y = -(int) Float.intBitsToFloat((int) (pos & 0xFFFFFFFFL));
                            if (hpx > 0) SIDEBAR_ROW_BOUNDS.put(sid, new int[]{x, y, x + wpx, y + hpx});
                        }
                    } catch (Throwable ignored) {}
                }
                return ui8Unit(cl);
            }
        });
    }

    // 从捕获到的真实坐标构造 sid→Rect（仅当前会话列表里的）
    private static Map<String, Rect> captureBoundsFor(List<ChatEditorUi.Session> sessions) {
        Map<String, Rect> out = new HashMap<>();
        for (int i = 0; i < sessions.size(); i++) {
            String id = sessions.get(i).id;
            int[] b = SIDEBAR_ROW_BOUNDS.get(id);
            if (b != null && b[3] > b[1]) out.put(id, new Rect(b[0], b[1], b[2], b[3]));
        }
        return out;
    }

    // 侧栏收起时 mq5.i 的 toggle 回调(xa3)：包一层，收起动作触发时把多选覆盖层滑出并退出。
    private void hookSidebarToggleCleanup(final ClassLoader cl) {
        try {
            Class<?> mq5 = cl.loadClass("mq5");
            final Class<?> xa3 = cl.loadClass("xa3");
            int n = 0;
            for (Method m : mq5.getDeclaredMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (!m.getName().equals("i") || pts.length != 6 || !xa3.isAssignableFrom(pts[2])) continue;
                hook(m).intercept(new Hooker() {
                    @Override public Object intercept(Chain chain) throws Throwable {
                        Object[] args = null;
                        try {
                            Object[] a = chain.getArgs().toArray();
                            if (a[2] != null) { a[2] = buildSidebarToggleProxy(cl, a[2]); args = a; }
                        } catch (Throwable t) { log("sidebar toggle cleanup row err: " + t); }
                        return args != null ? chain.proceed(args) : chain.proceed();
                    }
                });
                n++;
            }
            log("installed sidebar toggle cleanup hook mq5.i x" + n);
        } catch (Throwable t) { log("hookSidebarToggleCleanup failed: " + t); }
    }

    private Object buildSidebarToggleProxy(final ClassLoader cl, final Object original) throws Exception {
        final Class<?> xa3 = cl.loadClass("xa3");
        return Proxy.newProxyInstance(cl, new Class[]{xa3}, new InvocationHandler() {
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if ("toString".equals(name)) return "DeekseepSidebarToggleCleanup";
                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                if ("equals".equals(name)) return proxy == (args == null ? null : args[0]);
                if ("u".equals(name) && method.getParameterTypes().length == 0) {
                    if (sidebarSelectMode) slideOutSidebarOverlayAndExit();
                    return invokeXa3Returning(original, cl);
                }
                return invokeXa3Returning(original, cl);
            }
        });
    }

    // 2.2.2：Kotlin Unit 是 ui8（静态字段 a）；legacy 的 ti8 在本 build 不是 Unit。
    private static Object ui8Unit(ClassLoader cl) {
        try {
            Field f = cl.loadClass("ui8").getDeclaredField("a");
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable ignored) { return null; }
    }

    private static void enterSidebarSelectMode(final Activity act, String startSid) {
        SIDEBAR_SELECTED.clear();
        if (startSid != null && startSid.length() > 0) SIDEBAR_SELECTED.add(startSid);
        sidebarSelectMode = true;
        sidebarConfirmedOpen = false;
        showSidebarSelectOverlay(act);
    }

    private static void showSidebarSelectOverlay(final Activity act) {
        final List<ChatEditorUi.Session> sessions = loadCurrentSidebarSessions();
        if (sessions.isEmpty()) {
            Toast.makeText(act, "没有可删除的本地对话", Toast.LENGTH_SHORT).show();
            return;
        }

        removeSidebarSelectOverlay();

        final boolean dark = DeekseepUi.isDark(act);
        final int cardBg = dark ? 0xFF2A2A2D : 0xFFFFFFFF;
        final int text = dark ? 0xFFECECEC : 0xFF1A1A1A;
        final int div = dark ? 0xFF3A3A3D : 0xFFEAEAEA;
        final int brand = DeekseepUi.BRAND;
        final int danger = 0xFFE53935;
        final int checkColor = dark ? 0xFFECECEC : 0xFF1A1A1A;
        final int screenW = act.getResources().getDisplayMetrics().widthPixels;
        final float screenDp = screenW / act.getResources().getDisplayMetrics().density;
        // 手机端(<600dp)侧栏并非铺满屏宽：右侧约 1/5 仍露出聊天区，故取约 4/5 屏宽；平板/大屏限 320dp。
        final int sidebarW = screenDp < 600.0f
                ? Math.round(screenW * 0.8f)
                : Math.min(DeekseepUi.dp(act, 320), screenW);

        final FrameLayout root = new FrameLayout(act);
        root.setClickable(false);
        root.setFocusable(false);
        sidebarSelectOverlay = root;

        final FrameLayout marks = new FrameLayout(act);
        marks.setClickable(false);
        marks.setFocusable(false);
        root.addView(marks, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(DeekseepUi.dp(act, 12), 0, DeekseepUi.dp(act, 10), 0);
        top.setClickable(true);
        GradientDrawable topBg = new GradientDrawable();
        topBg.setColor(cardBg);
        topBg.setCornerRadius(DeekseepUi.dp(act, 16));
        topBg.setStroke(1, div);
        top.setBackground(topBg);
        if (android.os.Build.VERSION.SDK_INT >= 21) top.setElevation(DeekseepUi.dp(act, 8));
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(sidebarW - DeekseepUi.dp(act, 20), DeekseepUi.dp(act, 46));
        topLp.leftMargin = DeekseepUi.dp(act, 10);
        topLp.topMargin = DeekseepUi.statusBarHeight(act) + DeekseepUi.dp(act, 8);
        root.addView(top, topLp);

        TextView cancel = new TextView(act);
        cancel.setText("取消");
        cancel.setTextColor(brand);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(DeekseepUi.dp(act, 4), 0, DeekseepUi.dp(act, 10), 0);
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { exitSidebarSelectMode(); }
        });
        top.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final TextView title = new TextView(act);
        title.setTextColor(text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setSingleLine(true);
        top.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView delete = new TextView(act);
        delete.setTextColor(danger);
        delete.setTypeface(Typeface.DEFAULT_BOLD);
        delete.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        delete.setGravity(Gravity.CENTER);
        delete.setPadding(DeekseepUi.dp(act, 10), 0, DeekseepUi.dp(act, 4), 0);
        top.addView(delete, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        delete.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final int n = SIDEBAR_SELECTED.size();
                if (n <= 0) {
                    Toast.makeText(act, "先勾选要删除的对话", Toast.LENGTH_SHORT).show();
                    return;
                }
                confirmSidebarBatchDelete(act, sessions, n);
            }
        });
        updateSidebarSelectTitle(title, delete);

        ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                sidebarW,
                ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.START | Gravity.TOP;
        decor.addView(root, lp);

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = new Runnable() {
            public void run() {
                if (!sidebarSelectMode || sidebarSelectOverlay != root || root.getParent() == null) return;
                refreshSidebarMarkLayer(act, marks, sessions, title, delete, sidebarW, checkColor);
                root.postDelayed(this, 300);
            }
        };
        root.post(refresh[0]);
    }

    private static void updateSidebarSelectTitle(TextView title, TextView delete) {
        int n = SIDEBAR_SELECTED.size();
        title.setText(n > 0 ? ("已选择 " + n) : "选择对话");
        delete.setText(n > 0 ? ("删除(" + n + ")") : "删除");
    }

    private static void refreshSidebarMarkLayer(final Activity act, final FrameLayout marks,
                                                final List<ChatEditorUi.Session> sessions,
                                                final TextView title, final TextView delete,
                                                final int sidebarW, final int checkColor) {
        if (marks == null || marks.getParent() == null) return;
        marks.removeAllViews();
        Map<String, Rect> bounds = captureBoundsFor(sessions);
        if (sidebarRowsOnScreen(bounds, sidebarW)) sidebarConfirmedOpen = true;
        else if (sidebarConfirmedOpen && isSidebarCollapsed(bounds, sidebarW)) {
            logSidebarBoundsState("sidebar collapsed detected (rows off-screen) -> slide out overlay");
            slideOutSidebarOverlayAndExit();
            return;
        }
        if (bounds.isEmpty()) bounds = resolveSidebarSessionBounds(act, sessions, sidebarW);
        if (bounds.isEmpty()) {
            logSidebarBoundsState("sidebar marks fallback: no bounds (capture+a11y empty)");
            addFallbackSidebarMarks(act, marks, sessions, title, delete, sidebarW, checkColor);
            return;
        }
        StringBuilder dbg = new StringBuilder("sidebar marks: matched=" + bounds.size() + " raw=");
        for (int i = 0; i < sessions.size() && i < 4; i++) {
            Rect rr = bounds.get(sessions.get(i).id);
            if (rr != null) dbg.append("[").append(rr.left).append(",").append(rr.top)
                    .append(",").append(rr.width()).append("x").append(rr.height()).append("]");
        }
        logSidebarBoundsState(dbg.toString());
        for (int i = 0; i < sessions.size(); i++) {
            ChatEditorUi.Session s = sessions.get(i);
            Rect r = bounds.get(s.id);
            if (r == null) continue;
            int markH = Math.max(DeekseepUi.dp(act, 18), r.height());
            int top = Math.max(0, r.top + (r.height() - markH) / 2);
            // 手机端适配：勾选热区对齐到该行真实右边缘 r.right，而非全局 sidebarW。
            addSidebarCheckMark(act, marks, s, title, delete, sidebarW, checkColor, top, markH, r.right);
        }
    }

    // 侧栏收起检测：收起抽屉不走 mq5.i.u 回调，但 onGloballyPositioned 会把行左坐标从 0 平移到 -sidebarW。
    private static boolean isSidebarCollapsed(Map<String, Rect> bounds, int sidebarW) {
        if (bounds == null || bounds.isEmpty()) return false;
        int threshold = -sidebarW / 2;
        for (Rect r : bounds.values()) {
            if (r != null && r.left <= threshold) return true;
        }
        return false;
    }

    // 有任一行左坐标接近屏内（> -1/4 sidebarW）即视为侧栏已展开，用于解锁收起检测
    private static boolean sidebarRowsOnScreen(Map<String, Rect> bounds, int sidebarW) {
        if (bounds == null || bounds.isEmpty()) return false;
        int threshold = -sidebarW / 4;
        for (Rect r : bounds.values()) {
            if (r != null && r.left > threshold) return true;
        }
        return false;
    }

    private static void logSidebarBoundsState(String msg) {
        long now = System.currentTimeMillis();
        if (now - sidebarBoundsLogAt < 2500) return;
        sidebarBoundsLogAt = now;
        log(msg);
    }

    private static void addFallbackSidebarMarks(final Activity act, final FrameLayout marks,
                                                final List<ChatEditorUi.Session> sessions,
                                                final TextView title, final TextView delete,
                                                final int sidebarW, final int checkColor) {
        int rowH = DeekseepUi.dp(act, 44);
        int top = DeekseepUi.statusBarHeight(act) + DeekseepUi.dp(act, 96);
        int screenH = act.getResources().getDisplayMetrics().heightPixels;
        for (int i = 0; i < sessions.size(); i++) {
            int y = top + i * rowH;
            if (y > screenH) break;
            // 无真实坐标兜底：rowRight=0，退回对齐 sidebarW。
            addSidebarCheckMark(act, marks, sessions.get(i), title, delete, sidebarW, checkColor, y, rowH, 0);
        }
    }

    private static void addSidebarCheckMark(final Activity act, final FrameLayout marks,
                                            final ChatEditorUi.Session s,
                                            final TextView title, final TextView delete,
                                            final int sidebarW, final int checkColor,
                                            int top, int rowH, int rowRight) {
        final TextView mark = new TextView(act);
        // 行右侧透明可点击热区：对勾靠右显示，触摸区向左延伸约 40% 行宽；左侧仍可点标题切换会话。
        mark.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        mark.setIncludeFontPadding(false);
        mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        mark.setTypeface(Typeface.DEFAULT_BOLD);
        mark.setPadding(0, 0, DeekseepUi.dp(act, 19), 0);
        mark.setClickable(true);
        mark.setFocusable(false);
        updateSidebarMarkState(mark, s.id, checkColor);
        // 手机端适配：热区右缘对齐真实行右边缘（有坐标时），钳到 sidebarW；无坐标时退回全宽右缘。
        int rightEdge = rowRight > 0 ? Math.min(rowRight, sidebarW) : sidebarW;
        int touchW = Math.max(DeekseepUi.dp(act, 96), sidebarW * 2 / 5);
        if (touchW > rightEdge) touchW = rightEdge;
        FrameLayout.LayoutParams markLp = new FrameLayout.LayoutParams(touchW, rowH);
        markLp.leftMargin = Math.max(0, rightEdge - touchW);
        markLp.topMargin = top;
        marks.addView(mark, markLp);
        mark.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toggleSidebarSelection(s.id);
                updateSidebarSelectTitle(title, delete);
                updateSidebarMarkState(mark, s.id, checkColor);
            }
        });
    }

    private static Map<String, Rect> resolveSidebarSessionBounds(Activity act,
                                                                 List<ChatEditorUi.Session> sessions,
                                                                 int sidebarW) {
        Map<String, Rect> out = new HashMap<>();
        HashSet<String> wanted = new HashSet<>();
        for (int i = 0; i < sessions.size(); i++) {
            String k = sidebarTitleKey(sessions.get(i));
            if (k.length() > 0) wanted.add(k);
        }
        if (wanted.isEmpty()) return out;

        Map<String, ArrayList<Rect>> byTitle = new HashMap<>();
        AccessibilityNodeInfo root = null;
        try {
            View decor = act.getWindow().getDecorView();
            int[] decorLoc = new int[2];
            decor.getLocationOnScreen(decorLoc);
            root = decor.createAccessibilityNodeInfo();
            int minTop = DeekseepUi.statusBarHeight(act) + DeekseepUi.dp(act, 70);
            collectSidebarTitleBounds(root, decorLoc, sidebarW, minTop, wanted, byTitle, 0);
        } catch (Throwable t) {
            log("resolve sidebar a11y bounds failed: " + t);
        } finally {
            if (root != null) try { root.recycle(); } catch (Throwable ignored) {}
        }

        for (ArrayList<Rect> list : byTitle.values()) {
            Collections.sort(list, new Comparator<Rect>() {
                public int compare(Rect a, Rect b) {
                    if (a.top != b.top) return a.top - b.top;
                    return a.left - b.left;
                }
            });
        }
        for (int i = 0; i < sessions.size(); i++) {
            ChatEditorUi.Session s = sessions.get(i);
            String k = sidebarTitleKey(s);
            if (k.length() == 0) continue;
            ArrayList<Rect> list = byTitle.get(k);
            if (list == null || list.isEmpty()) continue;
            out.put(s.id, list.remove(0));
        }
        return out;
    }

    private static void collectSidebarTitleBounds(AccessibilityNodeInfo node, int[] decorLoc,
                                                  int sidebarW, int minTop, HashSet<String> wanted,
                                                  Map<String, ArrayList<Rect>> byTitle,
                                                  int depth) {
        if (node == null || depth > 80) return;
        try {
            collectSidebarTextBound(node, node.getText(), decorLoc, sidebarW, minTop, wanted, byTitle);
            collectSidebarTextBound(node, node.getContentDescription(), decorLoc, sidebarW, minTop, wanted, byTitle);
            int n = node.getChildCount();
            for (int i = 0; i < n; i++) {
                AccessibilityNodeInfo child = null;
                try {
                    child = node.getChild(i);
                    collectSidebarTitleBounds(child, decorLoc, sidebarW, minTop, wanted, byTitle, depth + 1);
                } catch (Throwable ignored) {
                } finally {
                    if (child != null) try { child.recycle(); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void collectSidebarTextBound(AccessibilityNodeInfo node, CharSequence cs,
                                                int[] decorLoc, int sidebarW, int minTop,
                                                HashSet<String> wanted,
                                                Map<String, ArrayList<Rect>> byTitle) {
        if (node == null || cs == null) return;
        String text = cs.toString().trim();
        if (!wanted.contains(text)) return;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        r.offset(-decorLoc[0], -decorLoc[1]);
        if (isLikelySidebarTitleBounds(r, sidebarW, minTop)) putSidebarTitleRect(byTitle, text, r);
    }

    private static boolean isLikelySidebarTitleBounds(Rect r, int sidebarW, int minTop) {
        if (r == null || r.isEmpty()) return false;
        if (r.top < minTop) return false;
        if (r.right <= 0 || r.left >= sidebarW) return false;
        if (r.height() <= 0 || r.height() > 80) return false;
        return r.width() > 0;
    }

    private static void putSidebarTitleRect(Map<String, ArrayList<Rect>> byTitle,
                                            String title, Rect r) {
        ArrayList<Rect> list = byTitle.get(title);
        if (list == null) {
            list = new ArrayList<>();
            byTitle.put(title, list);
        }
        for (int i = 0; i < list.size(); i++) {
            Rect old = list.get(i);
            if (Math.abs(old.centerY() - r.centerY()) <= 3 && Math.abs(old.left - r.left) <= 3) return;
        }
        list.add(new Rect(r));
    }

    private static String sidebarTitleKey(ChatEditorUi.Session s) {
        if (s == null || s.title == null) return "";
        return s.title.trim();
    }

    private static void updateSidebarMarkState(TextView mark, String sid, int checkColor) {
        boolean checked = sid != null && SIDEBAR_SELECTED.contains(sid);
        mark.setText(checked ? "\u2713" : "");
        mark.setTextColor(checkColor);
        mark.setBackground(null);
    }

    private static void toggleSidebarSelection(String sid) {
        if (sid == null) return;
        if (SIDEBAR_SELECTED.contains(sid)) SIDEBAR_SELECTED.remove(sid);
        else SIDEBAR_SELECTED.add(sid);
    }

    private static void exitSidebarSelectMode() {
        sidebarSelectMode = false;
        SIDEBAR_SELECTED.clear();
        removeSidebarSelectOverlay();
    }

    // 侧边栏收回时调用：多选覆盖层向上滑出并淡出后再移除。
    private static void slideOutSidebarOverlayAndExit() {
        sidebarSelectMode = false;
        SIDEBAR_SELECTED.clear();
        final View v = sidebarSelectOverlay;
        sidebarSelectOverlay = null;
        if (v == null) return;
        final Runnable anim = new Runnable() {
            public void run() {
                try {
                    int dist = v.getHeight() > 0 ? v.getHeight()
                            : v.getResources().getDisplayMetrics().heightPixels;
                    v.animate().translationY(-dist).alpha(0f).setDuration(220)
                            .setInterpolator(new android.view.animation.AccelerateInterpolator())
                            .withEndAction(new Runnable() {
                                public void run() {
                                    try {
                                        ViewGroup p = (ViewGroup) v.getParent();
                                        if (p != null) p.removeView(v);
                                    } catch (Throwable ignored) {}
                                }
                            }).start();
                } catch (Throwable t) {
                    try {
                        ViewGroup p = (ViewGroup) v.getParent();
                        if (p != null) p.removeView(v);
                    } catch (Throwable ignored) {}
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) anim.run();
        else new Handler(Looper.getMainLooper()).post(anim);
    }

    private static void removeSidebarSelectOverlay() {
        View v = sidebarSelectOverlay;
        sidebarSelectOverlay = null;
        if (v == null) return;
        try {
            ViewGroup p = (ViewGroup) v.getParent();
            if (p != null) p.removeView(v);
        } catch (Throwable ignored) {}
    }

    private static void confirmSidebarBatchDelete(final Activity act,
                                                  final List<ChatEditorUi.Session> sessions,
                                                  int n) {
        final Dialog dlg = new Dialog(act);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        boolean dark = DeekseepUi.isDark(act);
        int cardColor = dark ? 0xFF2A2A2D : 0xFFFFFFFF;
        int textColor = dark ? 0xFFECECEC : 0xFF1A1A1A;
        int subColor = dark ? 0xFFB0B0B4 : 0xFF666666;
        int divColor = dark ? 0xFF3A3A3D : 0xFFEAEAEA;

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(DeekseepUi.dp(act, 22), DeekseepUi.dp(act, 20),
                DeekseepUi.dp(act, 22), DeekseepUi.dp(act, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardColor);
        bg.setCornerRadius(DeekseepUi.dp(act, 18));
        card.setBackground(bg);

        TextView title = new TextView(act);
        title.setText("删除 " + n + " 个对话");
        title.setTextColor(textColor);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        card.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView msg = new TextView(act);
        msg.setText("删除后会从当前列表移除。未被原版列表加载的条目会用本地数据库删除兜底。");
        msg.setTextColor(subColor);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        msg.setLineSpacing(DeekseepUi.dp(act, 2), 1.0f);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = DeekseepUi.dp(act, 10);
        card.addView(msg, mlp);

        View line = new View(act);
        line.setBackgroundColor(divColor);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        llp.topMargin = DeekseepUi.dp(act, 18);
        card.addView(line, llp);

        LinearLayout buttons = new LinearLayout(act);
        buttons.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, DeekseepUi.dp(act, 48));
        card.addView(buttons, blp);

        TextView cancel = new TextView(act);
        cancel.setText("取消");
        cancel.setTextColor(subColor);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(DeekseepUi.dp(act, 14), 0, DeekseepUi.dp(act, 14), 0);
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dlg.dismiss(); }
        });
        buttons.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView del = new TextView(act);
        del.setText("删除");
        del.setTextColor(0xFFE53935);
        del.setTypeface(Typeface.DEFAULT_BOLD);
        del.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        del.setGravity(Gravity.CENTER);
        del.setPadding(DeekseepUi.dp(act, 14), 0, DeekseepUi.dp(act, 4), 0);
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dlg.dismiss();
                deleteSidebarSelected(act, sessions);
            }
        });
        buttons.addView(del, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        dlg.setContentView(card);
        dlg.show();
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.setDimAmount(0.32f);
            w.setLayout(Math.min(DeekseepUi.dp(act, 320),
                    act.getResources().getDisplayMetrics().widthPixels - DeekseepUi.dp(act, 48)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static List<ChatEditorUi.Session> loadCurrentSidebarSessions() {
        List<ChatEditorUi.Session> out = new ArrayList<>();
        File f = ChatEditorUi.currentDb();
        if (f == null) return out;
        SQLiteDatabase d = null;
        Cursor c = null;
        try {
            d = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            c = d.rawQuery("SELECT id,title FROM chat_session_list ORDER BY updated_at DESC", null);
            while (c.moveToNext()) {
                ChatEditorUi.Session s = new ChatEditorUi.Session();
                s.id = c.getString(0);
                s.title = c.getString(1);
                s.dbPath = f.getPath();
                if (s.id != null) out.add(s);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
            if (d != null) try { d.close(); } catch (Throwable ignored) {}
        }
        return out;
    }

    private static void deleteSidebarSelected(final Activity act, List<ChatEditorUi.Session> sessions) {
        int original = 0;
        int localOk = 0;
        int fail = 0;
        Map<String, List<String>> local = new HashMap<>();
        HashSet<String> selected = new HashSet<>(SIDEBAR_SELECTED);
        for (int i = 0; i < sessions.size(); i++) {
            ChatEditorUi.Session s = sessions.get(i);
            if (s.id == null || !selected.contains(s.id)) continue;
            Object action;
            synchronized (SIDEBAR_DELETE_ACTIONS) { action = SIDEBAR_DELETE_ACTIONS.get(s.id); }
            if (invokeXa3(action)) {
                original++;
                continue;
            }
            List<String> ids = local.get(s.dbPath);
            if (ids == null) {
                ids = new ArrayList<>();
                local.put(s.dbPath, ids);
            }
            ids.add(s.id);
        }

        for (Map.Entry<String, List<String>> e : local.entrySet()) {
            SQLiteDatabase d = null;
            try {
                d = SQLiteDatabase.openDatabase(e.getKey(), null, SQLiteDatabase.OPEN_READWRITE);
                for (String sid : e.getValue()) {
                    if (ChatEditorUi.deleteSessionLocal(d, sid)) localOk++;
                    else fail++;
                }
            } catch (Throwable ignored) {
                fail += e.getValue().size();
            } finally {
                if (d != null) try { d.close(); } catch (Throwable ignored) {}
            }
        }

        String msg;
        if (fail > 0) msg = "已提交 " + original + " 个，已本地删除 " + localOk + " 个，失败 " + fail + " 个";
        else msg = "已提交 " + original + " 个，已本地删除 " + localOk + " 个";
        exitSidebarSelectMode();
        Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() {
                try { act.recreate(); } catch (Throwable ignored) {}
            }
        }, 700);
    }

    private static boolean invokeXa3(Object action) {
        if (action == null) return false;
        try {
            for (Method m : action.getClass().getMethods()) {
                if (!m.getName().equals("u") || m.getParameterTypes().length != 0) continue;
                m.setAccessible(true);
                m.invoke(action);
                return true;
            }
            for (Method m : action.getClass().getDeclaredMethods()) {
                if (!m.getName().equals("u") || m.getParameterTypes().length != 0) continue;
                m.setAccessible(true);
                m.invoke(action);
                return true;
            }
        } catch (Throwable t) { log("invoke sidebar delete action failed: " + t); }
        return false;
    }

    private static Object invokeXa3Returning(Object action, ClassLoader cl) {
        if (action == null) return ui8Unit(cl);
        try {
            for (Method m : action.getClass().getMethods()) {
                if (!m.getName().equals("u") || m.getParameterTypes().length != 0) continue;
                m.setAccessible(true);
                return m.invoke(action);
            }
            for (Method m : action.getClass().getDeclaredMethods()) {
                if (!m.getName().equals("u") || m.getParameterTypes().length != 0) continue;
                m.setAccessible(true);
                return m.invoke(action);
            }
        } catch (Throwable t) { log("invoke sidebar toggle action failed: " + t); }
        return ui8Unit(cl);
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

    static boolean isAutoBackup() {
        return new File(AUTO_BACKUP_FILE).exists();
    }

    static void setAutoBackup(boolean on) {
        try {
            File ef = new File(AUTO_BACKUP_FILE);
            if (on) overwriteTextFile(AUTO_BACKUP_FILE, "");
            else ef.delete();
        } catch (Throwable ignored) {}
    }

    // 专家模式解锁旗标（hookExpertUnlock 读它决定是否给 expert 回填 feature 模板）
    static boolean isExpertUnlock() {
        return new File(EXPERT_UNLOCK_FILE).exists();
    }

    static void setExpertUnlock(boolean on) {
        try {
            File ef = new File(EXPERT_UNLOCK_FILE);
            if (on) overwriteTextFile(EXPERT_UNLOCK_FILE, "");
            else ef.delete();
        } catch (Throwable ignored) {}
    }

    // 视觉中继开关：与 expert 解锁同一个开关（解锁开启即中继开启）。
    private static boolean isExpertRelayEnabled() {
        return new File(EXPERT_UNLOCK_FILE).exists();
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
                hook(ctor).intercept(new Hooker() {
                    @Override public Object intercept(Chain chain) throws Throwable {
                        try {
                            String sysPrompt = readPrompt();
                            if (sysPrompt != null && !sysPrompt.isEmpty()) {
                                Object[] args = chain.getArgs().toArray();
                                String orig = (String) args[promptIdx];
                                if (orig == null) orig = "";
                                args[promptIdx] = "<system>\n" + sysPrompt + "\n</system>\n\n" + orig;
                                log("injected system prompt (synthetic=" + isSynthetic + ")");
                                return chain.proceed(args);
                            }
                        } catch (Throwable t) { log("inject err: " + t); }
                        return chain.proceed();
                    }
                });
                n++;
            }
            log("hooked ew0 constructors x" + n);
        } catch (Throwable t) { log("hookChatRequest failed: " + t); }
    }

    // ── 专家模式解锁：sf5(模型配置)构造后强改 final 字段点亮思考/搜索/上传 ──
    // 服务器默认给 expert 返回 f/g=true 但 j/k/l=null(禁思考/搜索/文件)；构造后回填真模板即本地点亮。
    private void hookExpertUnlock(ClassLoader cl) {
        try {
            final Class<?> sf5 = cl.loadClass("sf5");
            final Class<?> gf5c = cl.loadClass("gf5");
            EX_A = sf5.getDeclaredField("a"); EX_A.setAccessible(true);
            EX_F = sf5.getDeclaredField("f"); EX_F.setAccessible(true);
            EX_G = sf5.getDeclaredField("g"); EX_G.setAccessible(true);
            EX_J = sf5.getDeclaredField("j"); EX_J.setAccessible(true);
            EX_K = sf5.getDeclaredField("k"); EX_K.setAccessible(true);
            EX_L = sf5.getDeclaredField("l"); EX_L.setAccessible(true);
            try { GF5_C = gf5c.getDeclaredField("c"); GF5_C.setAccessible(true); } catch (Throwable ignored) {}
            int n = 0;
            for (Constructor<?> ctor : sf5.getDeclaredConstructors()) {
                Class<?>[] pt = ctor.getParameterTypes();
                // synthetic 反序列化构造器：sf5(int i, String a, ... , of5 j[10], lf5 k[11], gf5 l[12], ...)
                // i 是 kotlinx bitmask，位缺失时字段被置 null。构造后再反射写 final 对 App 编译读取点不可见，
                // 故改为「构造前」把模板塞进 args 并置位 bitmask → 字段出生即非空，任何读取路径都能看到。
                final boolean synth = pt.length >= 13 && pt[0] == int.class;
                hook(ctor).intercept(new Hooker() {
                    @Override public Object intercept(Chain chain) throws Throwable {
                        Object r;
                        if (synth) {
                            Object[] a = chain.getArgs().toArray();
                            try {
                                if (a != null && a.length >= 13 && "expert".equals(a[1])
                                        && new File(EXPERT_UNLOCK_FILE).exists()) {
                                    int mask = (a[0] instanceof Integer) ? (Integer) a[0] : 0;
                                    // f(32)/g(64) 位缺失时构造器默认 true，无需动；只补 j(512)/k(1024)/l(2048)
                                    if (tplThink != null)  { a[10] = tplThink;  mask |= 512; }
                                    if (tplSearch != null) { a[11] = tplSearch; mask |= 1024; }
                                    if (tplFile != null)   { a[12] = tplFile;   mask |= 2048; }
                                    a[0] = mask;
                                    log("expert ctor-inject (j=" + (tplThink!=null) + " k=" + (tplSearch!=null)
                                            + " file=" + gf5Info(tplFile) + ")");
                                }
                            } catch (Throwable t) { log("expert ctor-inject err: " + t); }
                            r = chain.proceed(a);
                        } else {
                            r = chain.proceed();
                        }
                        try { onSf5Built(chain.getThisObject()); }
                        catch (Throwable t) { log("expert unlock err: " + t); }
                        return r;
                    }
                });
                // API 102 坑：调用方若把 sf5 <init> 内联，构造 hook 不会触发 → 该实例 k/l 仍为 null。
                // deoptimize 强制运行时不内联该构造器，让所有构造路径都走进 hook。
                try { boolean d = deoptimize(ctor); log("deopt sf5 ctor ok=" + d); }
                catch (Throwable t) { log("deopt sf5 ctor err: " + t); }
                n++;
            }
            log("hooked sf5 ctors x" + n + " (expert unlock)");
            // 兜底：构造 hook 可能漏掉「模块加载前已反序列化」的实例，而 UI 门禁读的正是那个旧实例。
            // sf5.b(boolean,bu1) 是模型芯片渲染时取图标的方法，选中的模型必然被渲染 → 借此俘获真正被消费的实例并即时点亮。
            int m = 0;
            for (java.lang.reflect.Method mtd : sf5.getDeclaredMethods()) {
                if (!"b".equals(mtd.getName())) continue;
                Class<?>[] pt = mtd.getParameterTypes();
                if (pt.length != 2 || pt[0] != boolean.class) continue;
                hook(mtd).intercept(new Hooker() {
                    @Override public Object intercept(Chain chain) throws Throwable {
                        try {
                            Object self = chain.getThisObject();
                            if (self != null) {
                                // 无论哪个模型渲染，先尝试俘获模板(default/vision 的 j/k/l 真货)
                                Object j = EX_J.get(self), k = EX_K.get(self), l = EX_L.get(self);
                                if (j != null && tplThink == null) tplThink = j;
                                if (k != null && tplSearch == null) tplSearch = k;
                                if (l != null && gf5Count(l) > 0 && l != tplFile) tplFile = l;
                                if ("expert".equals(EX_A.get(self)) && new File(EXPERT_UNLOCK_FILE).exists()) {
                                    if (EX_L.get(self) == null || gf5Count(EX_L.get(self)) <= 0
                                            || EX_J.get(self) == null || EX_K.get(self) == null) {
                                        synchronized (expertInsts) {
                                            boolean has = false;
                                            for (Object e : expertInsts) if (e == self) { has = true; break; }
                                            if (!has) expertInsts.add(self);
                                        }
                                        applyExpert(self);
                                    }
                                }
                            }
                        } catch (Throwable t) { log("expert b() patch err: " + t); }
                        return chain.proceed();
                    }
                });
                m++;
            }
            log("hooked sf5.b() x" + m + " (expert gate catch)");
        } catch (Throwable t) { log("hookExpertUnlock failed: " + t); }
    }

    // 上传门禁 y91.a(Object,uz1)：事件对象里携带被 UI 消费的真实 sf5。在判空前扫描 arg0 的字段找到 sf5，
    // 打印它的 identityHashCode + l/k/j 状态（对比构造时 patch 的 @hash），并就地点亮 → 直接命中真正被读的实例。
    private void installExpertUploadGate(ClassLoader cl) {
        try {
            final Class<?> sf5 = cl.loadClass("sf5");
            final Class<?> y91 = cl.loadClass("y91");
            int n = 0;
            for (final java.lang.reflect.Method mtd : y91.getDeclaredMethods()) {
                if (!"a".equals(mtd.getName())) continue;
                Class<?>[] pt = mtd.getParameterTypes();
                if (pt.length != 2 || pt[0] != Object.class) continue;
                hook(mtd).intercept(new Hooker() {
                    @Override public Object intercept(Chain chain) throws Throwable {
                        try {
                            Object ev = chain.getArg(0);
                            if (ev != null) {
                                for (Field f : ev.getClass().getDeclaredFields()) {
                                    if (!sf5.isAssignableFrom(f.getType())) continue;
                                    f.setAccessible(true);
                                    Object s = f.get(ev);
                                    if (s == null) continue;
                                    boolean isExpert = "expert".equals(EX_A.get(s));
                                    log("[GATE] y91.a sf5 @" + Integer.toHexString(System.identityHashCode(s))
                                            + " a=" + EX_A.get(s) + " l=" + gf5Info(EX_L.get(s))
                                            + " k=" + (EX_K.get(s)!=null) + " j=" + (EX_J.get(s)!=null));
                                    if (isExpert && new File(EXPERT_UNLOCK_FILE).exists()) applyExpert(s);
                                }
                            }
                        } catch (Throwable t) { log("[GATE] err: " + t); }
                        return chain.proceed();
                    }
                });
                n++;
            }
            log("installed expert upload gate on y91.a x" + n);
        } catch (Throwable t) { log("installExpertUploadGate failed: " + t); }
    }

    // 每个 sf5(模型配置)构造后回调：俘获可用模板 + 给 expert 回填 + 事后 back-fill
    private static void onSf5Built(Object o) throws Exception {
        Object j = EX_J.get(o), k = EX_K.get(o), l = EX_L.get(o);
        if (j != null && tplThink == null) tplThink = j;
        if (k != null && tplSearch == null) tplSearch = k;
        if (l != null && gf5Count(l) > 0 && l != tplFile) {
            tplFile = l;   // c>0 才是真能上传的配置
            log("expert tplFile captured model=" + EX_A.get(o) + " " + gf5Info(l));
        }
        boolean isExpert = "expert".equals(EX_A.get(o));
        if (isExpert && new File(EXPERT_UNLOCK_FILE).exists()) {
            synchronized (expertInsts) {
                boolean has = false;
                for (Object e : expertInsts) if (e == o) { has = true; break; }
                if (!has) expertInsts.add(o);
            }
            applyExpert(o);
        }
        backfillExperts();  // 模板可能晚于 expert 才构造出来，事后统一回填
    }

    private static void applyExpert(Object o) throws Exception {
        EX_F.set(o, Boolean.TRUE);
        EX_G.set(o, Boolean.TRUE);
        if (EX_J.get(o) == null && tplThink != null) EX_J.set(o, tplThink);
        if (EX_K.get(o) == null && tplSearch != null) EX_K.set(o, tplSearch);
        Object curL = EX_L.get(o);
        if (tplFile != null && (curL == null || gf5Count(curL) <= 0)) EX_L.set(o, tplFile);
        log("expert applied @" + Integer.toHexString(System.identityHashCode(o))
                + " (j=" + (EX_J.get(o)!=null) + " k=" + (EX_K.get(o)!=null)
                + " file=" + gf5Info(EX_L.get(o)) + ")");
    }

    private static void backfillExperts() {
        if (tplFile == null && tplThink == null && tplSearch == null) return;
        synchronized (expertInsts) {
            for (Object o : expertInsts) {
                try {
                    if (EX_L.get(o) == null || gf5Count(EX_L.get(o)) <= 0
                            || EX_J.get(o) == null || EX_K.get(o) == null) applyExpert(o);
                } catch (Throwable ignored) {}
            }
        }
    }

    // 读 gf5.c(最大文件数)；读不到返回 -1，null 返回 0
    private static int gf5Count(Object gf5) {
        if (gf5 == null) return 0;
        if (GF5_C == null) return -1;
        try { Object v = GF5_C.get(gf5); return (v instanceof Integer) ? (Integer) v : -1; }
        catch (Throwable t) { return -1; }
    }

    private static String gf5Info(Object gf5) {
        if (gf5 == null) return "null";
        return "{c=" + gf5Count(gf5) + " cls=" + gf5.getClass().getName() + "}";
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
                hook(ctor).intercept(new Hooker() {
                    @Override public Object intercept(Chain chain) throws Throwable {
                        try {
                            List<Object> a = chain.getArgs();
                            if (isSrvLog()) {
                                StringBuilder sb = new StringBuilder("kb7(hint)");
                                for (int i = 0; i < a.size(); i++) {
                                    sb.append(" arg").append(i).append('=').append(a.get(i));
                                }
                                srvLog(sb.toString());
                            }
                            if (isNoCensor()) {
                                Object cur = a.get(idx);
                                if (Boolean.TRUE.equals(cur)) {
                                    Object[] args = a.toArray();
                                    args[idx] = Boolean.FALSE;
                                    log("blocked clear_response (kb7.arg" + idx + ")");
                                    return chain.proceed(args);
                                }
                            }
                        } catch (Throwable t) { log("clear_response block err: " + t); }
                        return chain.proceed();
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
                hook(ctor).intercept(new Hooker() {
                    @Override public Object intercept(Chain chain) throws Throwable {
                        Object r = chain.proceed();
                        try {
                            if (isSrvLog()) {
                                String evt = String.valueOf(chain.getArg(0));
                                Object d = chain.getArg(1);
                                String data = String.valueOf(d);
                                if (data != null && data.length() > 4000) {
                                    data = data.substring(0, 4000) + "...<truncated len=" + String.valueOf(d).length() + ">";
                                }
                                srvLog("evt=" + evt + "  data=" + data);
                            }
                        } catch (Throwable t) { srvLog("lv7 capture err: " + t); }
                        return r;
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
                hook(m).intercept(new Hooker() {
                    @Override public Object intercept(Chain chain) throws Throwable {
                        try {
                            Object a0 = chain.getArg(0);
                            String path = a0 instanceof String ? (String) a0 : "";
                            String val = String.valueOf(chain.getArg(1));
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
                                    srvLog("[CF] this.m.a@skip " + dumpMv(chain.getThisObject()));
                                    srvLog(dumpStack());
                                }
                                if (isNoCensor()) {
                                    log("skipped CONTENT_FILTER patch mv.i(" + path + ")");
                                    if (isSrvLog()) srvLog("[CF] skipped mv.i(" + path + ")");
                                    return null; // 跳过原 void 方法
                                }
                            }
                        } catch (Throwable t) { log("content-filter block err: " + t); }
                        return chain.proceed();
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
                hook(m).intercept(new Hooker() {
                    @Override public Object intercept(Chain chain) throws Throwable {
                        Object r = chain.proceed();
                        try {
                            if (isSrvLog() && r != null) srvLog("[VV7] new mv " + dumpMv(r));
                        } catch (Throwable t) { srvLog("[VV7] err " + t); }
                        return r;
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
                hook(m).intercept(new Hooker() {
                    @Override public Object intercept(Chain chain) throws Throwable {
                        try {
                            Object a0 = chain.getArg(0);
                            String v = a0 instanceof String ? (String) a0 : "";
                            boolean cf = v.contains("CONTENT_FILTER");
                            if (isSrvLog()) srvLog("[SR] mv." + mn + "(" + v + ") nocensor=" + isNoCensor());
                            if (cf && isNoCensor()) {
                                log("blocked mv." + mn + "(" + v + ")");
                                if (isSrvLog()) srvLog("[SR] blocked mv." + mn);
                                return null;
                            }
                        } catch (Throwable t) { log("status-write block err: " + t); }
                        return chain.proceed();
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
                hook(m).intercept(new Hooker() {
                    @Override public Object intercept(Chain chain) throws Throwable {
                        try {
                            if (isSrvLog()) {
                                String v = String.valueOf(chain.getArg(0));
                                if (v.contains("TEMPLATE_RESPONSE")) {
                                    srvLog("[TPL] h83.h TEMPLATE_RESPONSE seen");
                                    srvLog(dumpStack());
                                }
                            }
                        } catch (Throwable t) { srvLog("[TPL] err " + t); }
                        return chain.proceed();
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
                hook(m).intercept(new Hooker() {
                    @Override public Object intercept(Chain chain) throws Throwable {
                        try {
                            Object tp = chain.getArg(0);
                            Object rawList = chain.getArg(1);
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
                                    Object[] args = chain.getArgs().toArray();
                                    args[1] = copy;
                                    return chain.proceed(args);
                                }
                            }
                        } catch (Throwable t) { log("final-merge guard err: " + t); }
                        return chain.proceed();
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
                hook(m).intercept(new Hooker() {
                    @Override public Object intercept(Chain chain) throws Throwable {
                        try {
                            Object tp = chain.getThisObject();
                            Object msg = chain.getArg(0);
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
                                            Object[] args = chain.getArgs().toArray();
                                            args[0] = existing;
                                            log("tp." + mn + " kept original id=" + id + " over CONTENT_FILTER");
                                            if (isSrvLog()) srvLog("[FA] kept original id=" + id + " origStatus=" + exS);
                                            return chain.proceed(args);
                                        }
                                    }
                                }
                            }
                        } catch (Throwable t) { log("final-apply guard err: " + t); }
                        return chain.proceed();
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
                hook(m).intercept(new Hooker() {
                    @Override public Object intercept(Chain chain) throws Throwable {
                        Object r = chain.proceed();
                        rememberNavController(chain.getThisObject());
                        scheduleRouteCheck(chain.getThisObject());
                        return r;
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
                hook(m).intercept(new Hooker() {
                    @Override public Object intercept(Chain chain) throws Throwable {
                        Object r = chain.proceed();
                        Object nav = chain.getArg(0);
                        if (nav != null) {
                            rememberNavController(nav);
                            scheduleRouteCheck(nav);
                        } else {
                            main.post(new Runnable() { public void run() { hideButton(); } });
                        }
                        return r;
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
            hook(m).intercept(new Hooker() {
                @Override public Object intercept(Chain chain) throws Throwable {
                    Object r = chain.proceed();
                    rememberNavController(chain.getThisObject());
                    scheduleRouteCheck(chain.getThisObject());
                    return r;
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

    private void markSelfActive(ClassLoader cl) {
        try {
            Class<?> a = cl.loadClass("com.dsmod.probe.SettingsActivity");
            for (Method m : a.getDeclaredMethods()) {
                if (m.getName().equals("isModuleActive")) {
                    hook(m).intercept(new Hooker() {
                        @Override public Object intercept(Chain chain) throws Throwable {
                            return Boolean.TRUE;
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

    // ══════════════════════════════════════════════════════════════════════
    // 专家图片 → 视觉描述中继（从 legacy 移植；此段为纯反射+现代 hook API）
    // ══════════════════════════════════════════════════════════════════════

    // 1) transport 入口 r92.b：捕获活着的 r92、把发送点图片挂到 ew0、返回时包装 Flow 跑中继
    private void installNetworkPayloadCapture(ClassLoader cl) {
        try {
            Class<?> rs0 = cl.loadClass("rs0");
            int n = 0;
            // 快路径：transport 类 b(rs0,Long)。build 间该类改名(2.2.1=r92 / 2.2.2=s92)，两名都试。
            for (String txName : new String[]{"r92", "s92", "t92", "q92"}) {
                try {
                    Class<?> txc = cl.loadClass(txName);
                    for (Method m : txc.getDeclaredMethods()) {
                        Class<?>[] pts = m.getParameterTypes();
                        if (!m.getName().equals("b") || pts.length != 2 || !rs0.isAssignableFrom(pts[0])) continue;
                        hookTransport(m); n++;
                        log("installed network payload capture on " + txName + ".b");
                    }
                } catch (Throwable ignored) {}
                if (n > 0) break;
            }
            // 兜底：设备上 DeepSeek 有另一个 build（transport 类被改名），r92 变空类。
            // rs0(接口)与 Long 跨 build 稳定 → 按结构签名 (rs0,Long) 在运行时 dex 里扫出真正的 transport 方法。
            if (n == 0) {
                Method tx = findTransportByStructure(cl, rs0);
                if (tx != null) { hookTransport(tx); n = 1;
                    log("installed network payload capture via structural scan x1"); }
                else log("structural transport scan found nothing");
            }
            // 中继实现：collect 时机的 hook(见 registerRelayFlow)。返回值是 Object，不会被强转闪退。
            installExpertFlowCollectHook(cl);
        } catch (Throwable t) { log("installNetworkPayloadCapture failed: " + t); }
    }

    // 给定 transport 方法(签名 (rs0,Long)->Flow) 装上中继包装 hook
    private void hookTransport(Method m) {
        hook(m).intercept(new Hooker() {
            @Override public Object intercept(Chain chain) throws Throwable {
                Object[] args = chain.getArgs().toArray();
                try { if (liveR92 == null) liveR92 = chain.getThisObject(); } catch (Throwable ignored) {}
                try {
                    List fps = tlPendingFps.get();
                    if (fps != null) {
                        tlPendingFps.remove();
                        Object req = args != null && args.length > 0 ? args[0] : null;
                        if (req != null) ew0Fps.put(req, fps);
                    }
                } catch (Throwable ignored) {}
                Object r = chain.proceed();
                try {
                    Object reqObj = args != null && args.length > 0 ? args[0] : null;
                    // 关键：不能把返回值换成 Proxy(会被 libxposed 强转成声明返回类型 b41 而 CCE 闪退)。
                    // 改为：原样返回真实 b41，但把该 b41 实例登记下来；等它被 collect(b41.b) 时再跑中继。
                    registerRelayFlow(reqObj, r, chain.getThisObject());
                } catch (Throwable t) { extLog("[RELAY] register err " + t + "\n" + stackToString(t)); }
                return r;
            }
        });
    }

    // 运行时(app 进程内)扫描自身 dex，按结构签名 (rs0,Long)->非void 找 transport 方法。build 无关。
    private Method findTransportByStructure(ClassLoader cl, Class<?> rs0) {
        try {
            java.util.List<String> names = listDexClasses(cl);
            int scanned = 0;
            for (String nm : names) {
                if (nm.indexOf('.') >= 0) continue;   // defpackage 混淆类无包名
                if (nm.length() > 6) continue;         // 混淆名很短，跳过长名降负载
                Class<?> c;
                try { c = Class.forName(nm, false, cl); }  // false=不初始化，避免静态副作用
                catch (Throwable t) { continue; }
                scanned++;
                for (Method m : c.getDeclaredMethods()) {
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length == 2 && pt[0] == rs0 && pt[1] == Long.class
                            && m.getReturnType() != void.class && !m.getReturnType().isPrimitive()) {
                        log("[TX] found transport " + c.getName() + "." + m.getName()
                                + "(rs0,Long)->" + m.getReturnType().getName());
                        return m;
                    }
                }
            }
            log("[TX] scanned=" + scanned + "/" + names.size() + " no (rs0,Long) match");
        } catch (Throwable t) { log("[TX] scan failed: " + t); }
        return null;
    }

    @SuppressWarnings("unchecked")
    private java.util.List<String> listDexClasses(ClassLoader cl) throws Exception {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        Class<?> bdcl = Class.forName("dalvik.system.BaseDexClassLoader");
        Field plF = bdcl.getDeclaredField("pathList"); plF.setAccessible(true);
        Object pl = plF.get(cl);
        Field deF = pl.getClass().getDeclaredField("dexElements"); deF.setAccessible(true);
        Object[] els = (Object[]) deF.get(pl);
        for (Object el : els) {
            Field dfF = el.getClass().getDeclaredField("dexFile"); dfF.setAccessible(true);
            Object df = dfF.get(el);
            if (df == null) continue;
            Method entries = df.getClass().getDeclaredMethod("entries"); entries.setAccessible(true);
            java.util.Enumeration<String> en = (java.util.Enumeration<String>) entries.invoke(df);
            while (en.hasMoreElements()) out.add(en.nextElement());
        }
        return out;
    }

    // 2) 专家图片中继的历史修复：pw0(内存)/fm8(落库) 层把本地图片 fragment 合回服务器响应。
    private void installExpertHistoryImagePreserver(final ClassLoader cl) {
        try {
            Class<?> fm8 = cl.loadClass("fm8");
            int ctorCount = 0;
            for (Constructor<?> ctor : fm8.getDeclaredConstructors()) {
                hook(ctor).intercept(new Hooker() {
                    @Override public Object intercept(Chain chain) throws Throwable {
                        Object r = chain.proceed();
                        try { liveFm8 = chain.getThisObject(); } catch (Throwable ignored) {}
                        return r;
                    }
                });
                ctorCount++;
            }

            int writeCount = 0;
            for (Method m : fm8.getDeclaredMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (!"b".equals(m.getName()) || pts.length != 7
                        || pts[0] != String.class || pts[1] != int.class
                        || !ArrayList.class.isAssignableFrom(pts[4])) continue;
                hook(m).intercept(new Hooker() {
                    @Override public Object intercept(Chain chain) throws Throwable {
                        try {
                            liveFm8 = chain.getThisObject();
                            preserveImagesBeforeLocalWrite(cl, chain.getThisObject(), chain.getArgs().toArray());
                        } catch (Throwable t) {
                            extLog("[HISTORY] fm8 preserve err: " + t + "\n" + stackToString(t));
                        }
                        return chain.proceed();
                    }
                });
                writeCount++;
            }
            log("installed expert history DB preserver fm8 ctor x" + ctorCount + " write x" + writeCount);
        } catch (Throwable t) {
            log("installExpertHistoryImagePreserver fm8 failed: " + t);
        }

        try {
            Class<?> pw0 = cl.loadClass("pw0");
            int n = 0;
            for (Constructor<?> ctor : pw0.getDeclaredConstructors()) {
                hook(ctor).intercept(new Hooker() {
                    @Override public Object intercept(Chain chain) throws Throwable {
                        Object r = chain.proceed();
                        try { preserveImagesInHistoryResponse(cl, chain.getThisObject()); }
                        catch (Throwable t) {
                            extLog("[HISTORY] pw0 preserve err: " + t + "\n" + stackToString(t));
                        }
                        return r;
                    }
                });
                n++;
            }
            log("installed expert history memory preserver pw0 ctor x" + n);
        } catch (Throwable t) {
            log("installExpertHistoryImagePreserver pw0 failed: " + t);
        }
    }

    // 3) 发送点捕获完整 List<fp>（图片唯一完整来源），供中继按 sid 落盘。
    private void installExpertImageFpCapture(final ClassLoader cl) {
        hookSendPointFps(cl, "fu0", true);
        hookSendPointFps(cl, "uu0", false);
    }

    private void hookSendPointFps(final ClassLoader cl, final String cls, final boolean directList) {
        try {
            Class<?> c = cl.loadClass(cls);
            final Method y = c.getDeclaredMethod("y", Object.class);
            hook(y).intercept(new Hooker() {
                @Override public Object intercept(Chain chain) throws Throwable {
                    if (!isExpertRelayEnabled()) return chain.proceed();
                    try {
                        List fps = null;
                        if (directList) {
                            Object v = fieldByName(chain.getThisObject(), "i");   // fu0.i = List<fp>
                            if (v instanceof List) fps = (List) v;
                        } else {
                            Object kv = fieldByName(chain.getThisObject(), "f");   // uu0.f = kv 消息
                            Object v = kv == null ? null : invokeNoArg(kv, "l");    // kv.l() = List<fp>
                            if (v instanceof List) fps = (List) v;
                        }
                        if (fps != null && countImageFpList(fps) > 0) {
                            tlPendingFps.set(fps);
                        }
                    } catch (Throwable t) { extLog("[RELAY] fp capture(" + cls + ") err: " + t); }
                    return chain.proceed();
                }
            });
            log("installed send-point fp capture on " + cls + ".y");
        } catch (Throwable t) { log("hookSendPointFps " + cls + " failed: " + t); }
    }

    // 4) 捕获一个活着的 q71（completion PoW 管理器）实例
    private void installPowManagerCapture(ClassLoader cl) {
        try {
            Class<?> q71 = cl.loadClass("q71");
            int n = 0;
            for (Method m : q71.getDeclaredMethods()) {
                String nm = m.getName();
                if ((nm.equals("j") || nm.equals("b")) && m.getParameterTypes().length == 1) {
                    hook(m).intercept(new Hooker() {
                        @Override public Object intercept(Chain chain) throws Throwable {
                            try { if (liveQ71 == null) { liveQ71 = chain.getThisObject(); extLog("[VP] captured liveQ71"); } }
                            catch (Throwable ignored) {}
                            return chain.proceed();
                        }
                    });
                    n++;
                }
            }
            log("installed pow manager capture on q71 x" + n);
        } catch (Throwable t) { log("installPowManagerCapture failed: " + t); }
    }

    private static int countImageFpList(List fps) {
        if (fps == null) return 0;
        int n = 0;
        for (Object fp : fps) if (Boolean.TRUE.equals(fieldByName(fp, "k"))) n++;
        return n;
    }

    private static Object invokeNoArg(Object target, String name) {
        try {
            Method m = target.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable t) { return null; }
    }

    private void preserveImagesInHistoryResponse(ClassLoader cl, Object pw0) throws Throwable {
        if (!isExpertRelayEnabled() || pw0 == null) return;
        Object session = fieldByName(pw0, "a");
        String sid = stringField(session, "a");
        String model = stringField(session, "i");
        if (!isUsableSessionId(sid)) {
            extLog("[HISTORY] pw0 skip: sid 无效 model=" + String.valueOf(model));
            return;
        }

        Object messagesObj = fieldByName(pw0, "b");
        List messages = messagesObj instanceof List ? (List) messagesObj : null;
        boolean tracked = isTrackedExpertRelaySession(sid);
        boolean marker = historyMessagesContainRelayMarker(messages);
        extLog("[HISTORY] pw0 seen sid=" + sid + " model=" + String.valueOf(model)
                + " tracked=" + tracked + " marker=" + marker
                + " messages=" + (messages == null ? -1 : messages.size())
                + " liveFm8=" + (liveFm8 != null));
        if (!marker) {
            extLog("[HISTORY] pw0 scope skip sid=" + sid + " model=" + String.valueOf(model));
            return;
        }
        if (messages == null) {
            extLog("[HISTORY] pw0 skip: messages 不是 List sid=" + sid
                    + " actual=" + simpleName(messagesObj));
            return;
        }

        Object fm8 = liveFm8;
        if (fm8 == null) {
            extLog("[HISTORY] pw0 skip: fm8 尚未捕获 sid=" + sid);
            return;
        }
        Map<Integer, Object> localRows = indexLocalRows(readLocalRl8Rows(fm8, sid));
        boolean hasPersisted = relayImageFile(sid) != null && relayImageFile(sid).isFile();
        extLog("[HISTORY] pw0 local sid=" + sid + " rows=" + localRows.size()
                + " persistedImages=" + hasPersisted);
        if (localRows.isEmpty() && !hasPersisted) {
            extLog("[HISTORY] pw0 skip: 本地历史为空且无落盘图片 sid=" + sid);
            return;
        }

        int changed = 0;
        int imageFiles = 0;
        int candidates = 0;
        int detailLogs = 0;
        for (Object message : messages) {
            if (message == null || !"kv".equals(simpleName(message))) continue;
            Integer messageId = intField(message, "f");
            if (messageId == null) continue;
            Object serverObj = fieldByName(message, "t");
            List serverFragments = serverObj instanceof List ? (List) serverObj : Collections.emptyList();
            boolean messageMarker = fragmentListContainsRelayMarker(serverFragments);
            int serverImages = countImageFiles(serverFragments);
            if (!messageMarker) continue;

            Object oldRow = localRows.get(messageId);
            String oldJson = stringField(oldRow, "l");
            List oldFragments = decodeStaticFragments(cl, oldJson);
            int oldImages = oldFragments == null ? 0 : countImageFiles(oldFragments);
            if (oldImages == 0) {
                List persisted = loadPersistedImageFragments(cl, sid);
                if (persisted != null) { oldFragments = persisted; oldImages = countImageFiles(persisted); }
            }
            candidates++;
            if (detailLogs++ < 16) {
                extLog("[HISTORY] pw0 msg sid=" + sid + " id=" + messageId
                        + " relayMarker=" + messageMarker + " serverImages=" + serverImages
                        + " localRow=" + (oldRow != null)
                        + " localJsonLen=" + (oldJson == null ? 0 : oldJson.length())
                        + " imageSrc=" + oldImages);
            }
            if (serverImages > 0 || oldFragments == null || oldImages == 0) continue;

            ArrayList merged = mergeLocalImageFragments(serverFragments, oldFragments);
            if (forceSetObjectField(message, "t", merged)) {
                if (!tracked) {
                    rememberExpertRelaySession(sid, "pw0-verified-merge");
                    tracked = true;
                }
                changed++;
                imageFiles += oldImages;
                extLog("[HISTORY] 内存回填 sid=" + sid + " msg=" + messageId
                        + " images=" + oldImages + " fragments=" + merged.size());
            }
        }
        if (changed > 0) {
            extLog("[HISTORY] ✓ pw0 expert 图片保留完成 sid=" + sid
                    + " messages=" + changed + " images=" + imageFiles);
        } else {
            extLog("[HISTORY] pw0 done sid=" + sid + " candidates=" + candidates
                    + " changed=0");
        }
    }

    private void preserveImagesBeforeLocalWrite(ClassLoader cl, Object fm8, Object[] args) throws Throwable {
        if (!isExpertRelayEnabled() || fm8 == null || args == null || args.length < 7) return;
        Object sessionMeta = args[6];
        String model = stringField(sessionMeta, "k");
        String sid = args[0] instanceof String ? (String) args[0] : null;
        if (!isUsableSessionId(sid)) {
            extLog("[HISTORY] fm8 skip: sid 无效 model=" + String.valueOf(model));
            return;
        }
        List incomingRows = args[4] instanceof List ? (List) args[4] : null;
        boolean tracked = isTrackedExpertRelaySession(sid);
        Map<Object, List> decodedIncoming = new java.util.IdentityHashMap<>();
        boolean marker = false;
        if (incomingRows != null) {
            for (Object incoming : incomingRows) {
                if (incoming == null || !"rl8".equals(simpleName(incoming))) continue;
                String json = stringField(incoming, "l");
                if (!serializedMayContainRelayMarker(json)) continue;
                List fragments = decodeStaticFragments(cl, json);
                if (fragmentListContainsRelayMarker(fragments)) {
                    decodedIncoming.put(incoming, fragments);
                    marker = true;
                }
            }
        }
        extLog("[HISTORY] fm8 seen sid=" + sid + " model=" + String.valueOf(model)
                + " tracked=" + tracked + " marker=" + marker
                + " incoming=" + (incomingRows == null ? -1 : incomingRows.size()));
        if (incomingRows == null) {
            extLog("[HISTORY] fm8 skip: incoming 不是 List sid=" + sid
                    + " actual=" + simpleName(args[4]));
            return;
        }
        if (!marker) {
            extLog("[HISTORY] fm8 scope skip sid=" + sid + " model=" + String.valueOf(model));
            return;
        }

        Map<Integer, Object> localRows = indexLocalRows(readLocalRl8Rows(fm8, sid));
        boolean hasPersisted = relayImageFile(sid) != null && relayImageFile(sid).isFile();
        extLog("[HISTORY] fm8 local sid=" + sid + " rows=" + localRows.size()
                + " persistedImages=" + hasPersisted);
        if (localRows.isEmpty() && !hasPersisted) {
            extLog("[HISTORY] fm8 skip: 本地历史为空且无落盘图片 sid=" + sid);
            return;
        }
        int changed = 0;
        int candidates = 0;
        int detailLogs = 0;
        for (Object incoming : incomingRows) {
            if (incoming == null || !"rl8".equals(simpleName(incoming))) continue;
            Integer messageId = intField(incoming, "a");
            if (messageId == null) continue;
            List serverFragments = decodedIncoming.get(incoming);
            if (serverFragments == null) continue;
            boolean messageMarker = fragmentListContainsRelayMarker(serverFragments);
            int serverImages = countImageFiles(serverFragments);

            Object oldRow = localRows.get(messageId);
            String oldJson = stringField(oldRow, "l");
            List oldFragments = decodeStaticFragments(cl, oldJson);
            int oldImages = oldFragments == null ? 0 : countImageFiles(oldFragments);
            if (oldImages == 0) {
                List persisted = loadPersistedImageFragments(cl, sid);
                if (persisted != null) { oldFragments = persisted; oldImages = countImageFiles(persisted); }
            }
            candidates++;
            if (detailLogs++ < 16) {
                extLog("[HISTORY] fm8 msg sid=" + sid + " id=" + messageId
                        + " relayMarker=" + messageMarker + " serverImages=" + serverImages
                        + " localRow=" + (oldRow != null)
                        + " localJsonLen=" + (oldJson == null ? 0 : oldJson.length())
                        + " imageSrc=" + oldImages);
            }
            if (!messageMarker || serverImages > 0 || oldFragments == null || oldImages == 0) continue;

            ArrayList merged = mergeLocalImageFragments(serverFragments, oldFragments);
            String mergedJson = encodeStaticFragments(cl, merged);
            if (mergedJson == null || mergedJson.length() == 0) continue;
            if (forceSetObjectField(incoming, "l", mergedJson)) {
                if (!tracked) {
                    rememberExpertRelaySession(sid, "fm8-verified-merge");
                    tracked = true;
                }
                changed++;
                extLog("[HISTORY] 落库回填 sid=" + sid + " msg=" + messageId
                        + " images=" + oldImages + " jsonLen=" + mergedJson.length());
            }
        }
        if (changed > 0) {
            extLog("[HISTORY] ✓ fm8 expert 图片落库保护完成 sid=" + sid + " messages=" + changed);
        } else {
            extLog("[HISTORY] fm8 done sid=" + sid + " candidates=" + candidates
                    + " changed=0");
        }
    }

    private static boolean historyMessagesContainRelayMarker(List messages) {
        if (messages == null) return false;
        for (Object message : messages) {
            Object fragments = fieldByName(message, "t");
            if (fragments instanceof List && fragmentListContainsRelayMarker((List) fragments)) return true;
        }
        return false;
    }

    private static boolean serializedMayContainRelayMarker(String json) {
        return json != null && (json.contains(RELAY_PROMPT_MARKER) || json.contains("\\u3010"));
    }

    private static boolean fragmentListContainsRelayMarker(List fragments) {
        if (fragments == null) return false;
        for (Object fragment : fragments) {
            boolean request = "ws7".equals(simpleName(fragment))
                    || "REQUEST".equals(String.valueOf(fieldByName(fragment, "a")));
            if (!request) continue;
            Object content = fieldByName(fragment, "c");
            if (content instanceof String && ((String) content).contains(RELAY_PROMPT_MARKER)) return true;
        }
        return false;
    }

    private static boolean isUsableSessionId(String sid) {
        return sid != null && sid.length() > 0 && !"null".equals(sid);
    }

    private static File relaySessionMarkerFile(String sid) {
        if (!isUsableSessionId(sid) || ".".equals(sid) || "..".equals(sid) || sid.length() > 160
                || !sid.matches("[A-Za-z0-9._-]+")) return null;
        return new File(EXPERT_RELAY_SESSION_DIR, sid);
    }

    private static boolean isTrackedExpertRelaySession(String sid) {
        if (!isUsableSessionId(sid)) return false;
        synchronized (expertRelaySessionIds) {
            if (expertRelaySessionIds.contains(sid)) return true;
        }
        File marker = relaySessionMarkerFile(sid);
        if (marker == null || !marker.isFile()) return false;
        synchronized (expertRelaySessionIds) {
            expertRelaySessionIds.add(sid);
        }
        return true;
    }

    private static void rememberExpertRelaySession(String sid, String source) {
        if (!isUsableSessionId(sid)) return;
        synchronized (expertRelaySessionIds) {
            expertRelaySessionIds.add(sid);
        }
        File marker = relaySessionMarkerFile(sid);
        if (marker == null) {
            extLog("[HISTORY] relay sid 仅内存登记（文件名不安全） source=" + source
                    + " sid=" + truncateForLog(sid, 80));
            return;
        }
        try {
            overwriteTextFile(marker.getAbsolutePath(), sid);
            extLog("[HISTORY] relay sid 已登记 source=" + source + " sid=" + sid);
        } catch (Throwable t) {
            extLog("[HISTORY] relay sid 落盘失败 source=" + source + " sid=" + sid + ": " + t);
        }
    }

    private static File relayImageFile(String sid) {
        if (!isUsableSessionId(sid) || ".".equals(sid) || "..".equals(sid) || sid.length() > 160
                || !sid.matches("[A-Za-z0-9._-]+")) return null;
        return new File(RELAY_IMAGE_DIR, sid + ".json");
    }

    private void persistRelayImages(ClassLoader cl, String sid, Object expertReq) {
        List fps = ew0Fps.remove(expertReq);
        if (fps == null) { extLog("[HISTORY] persistImages skip: 无捕获 fp sid=" + sid); return; }
        ArrayList imageFps = new ArrayList();
        for (Object fp : fps) if (Boolean.TRUE.equals(fieldByName(fp, "k"))) imageFps.add(fp);
        if (imageFps.isEmpty()) { extLog("[HISTORY] persistImages skip: 无图片 fp sid=" + sid); return; }
        File out = relayImageFile(sid);
        if (out == null) { extLog("[HISTORY] persistImages skip: sid 文件名不安全 sid=" + truncateForLog(sid, 80)); return; }
        try {
            Class<?> qs7 = cl.loadClass("qs7");
            Constructor<?> ctor = qs7.getDeclaredConstructor(List.class);
            ctor.setAccessible(true);
            Object frag = ctor.newInstance(imageFps);
            String json = encodeStaticFragments(cl, java.util.Collections.singletonList(frag));
            if (json == null || json.length() == 0) { extLog("[HISTORY] persistImages 编码失败 sid=" + sid); return; }
            File dir = out.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            overwriteTextFile(out.getAbsolutePath(), json);
            extLog("[HISTORY] persistImages ✓ sid=" + sid + " images=" + imageFps.size()
                    + " jsonLen=" + json.length());
            for (int i = 0; i < imageFps.size(); i++) {
                extLog("[HISTORY] persistImages fp[" + i + "]=" + summarizeFp(imageFps.get(i)));
            }
        } catch (Throwable t) { extLog("[HISTORY] persistImages err sid=" + sid + ": " + t); }
    }

    private static List loadPersistedImageFragments(ClassLoader cl, String sid) {
        File f = relayImageFile(sid);
        if (f == null || !f.isFile()) return null;
        try {
            String json = readSmallText(f.getAbsolutePath());
            List frags = decodeStaticFragments(cl, json);
            if (frags == null || countImageFiles(frags) == 0) return null;
            return frags;
        } catch (Throwable t) { extLog("[HISTORY] loadPersistedImages err sid=" + sid + ": " + t); return null; }
    }

    private static ArrayList readLocalRl8Rows(Object fm8, String sid) throws Throwable {
        if (fm8 == null || sid == null) return new ArrayList();
        Method tableForSession = fm8.getClass().getDeclaredMethod("a", String.class);
        tableForSession.setAccessible(true);
        Object sl8 = tableForSession.invoke(fm8, sid);
        Object table = fieldByName(sl8, "b");
        if (table == null) return new ArrayList();

        Object binding = fieldByName(table, "d");
        if (binding == null) return new ArrayList();
        Method allColumns = binding.getClass().getDeclaredMethod("c");
        allColumns.setAccessible(true);
        Object columns = allColumns.invoke(binding);
        if (columns == null || !columns.getClass().isArray()) return new ArrayList();

        Method selectFactory = table.getClass().getDeclaredMethod("U");
        selectFactory.setAccessible(true);
        Object select = selectFactory.invoke(table);
        Method selectColumns = select.getClass().getDeclaredMethod("z", columns.getClass());
        selectColumns.setAccessible(true);
        selectColumns.invoke(select, new Object[]{columns});
        Method allRows = select.getClass().getDeclaredMethod("x");
        allRows.setAccessible(true);
        Object rows = allRows.invoke(select);
        return rows instanceof ArrayList ? (ArrayList) rows : new ArrayList();
    }

    private static Map<Integer, Object> indexLocalRows(List rows) {
        HashMap<Integer, Object> out = new HashMap<>();
        if (rows == null) return out;
        for (Object row : rows) {
            Integer id = intField(row, "a");
            if (id != null) out.put(id, row);
        }
        return out;
    }

    private static List decodeStaticFragments(ClassLoader cl, String json) {
        if (cl == null || json == null || json.trim().length() == 0) return null;
        try {
            Class<?> ch4 = cl.loadClass("ch4");
            Class<?> x94 = cl.loadClass("x94");
            Field jsonField = x94.getDeclaredField("a");
            jsonField.setAccessible(true);
            Object jsonCodec = jsonField.get(null);
            Class<?> xv0 = cl.loadClass("xv0");
            Field serializerField = xv0.getDeclaredField("a");
            serializerField.setAccessible(true);
            Object serializer = serializerField.get(null);
            Method decode = jsonCodec.getClass().getMethod("b", ch4, String.class);
            decode.setAccessible(true);
            Object wrapper = decode.invoke(jsonCodec, serializer, json);
            Object list = fieldByName(wrapper, "a");
            return list instanceof List ? (List) list : null;
        } catch (Throwable t) {
            extLog("[HISTORY] fragments decode skip: " + t + " json=" + truncateForLog(json, 180));
            return null;
        }
    }

    private static String encodeStaticFragments(ClassLoader cl, List fragments) {
        if (cl == null || fragments == null) return null;
        try {
            Class<?> ch4 = cl.loadClass("ch4");
            Class<?> x94 = cl.loadClass("x94");
            Field jsonField = x94.getDeclaredField("a");
            jsonField.setAccessible(true);
            Object jsonCodec = jsonField.get(null);
            Class<?> xv0 = cl.loadClass("xv0");
            Field serializerField = xv0.getDeclaredField("a");
            serializerField.setAccessible(true);
            Object serializer = serializerField.get(null);
            Class<?> zv0 = cl.loadClass("zv0");
            Constructor<?> wrapperCtor = zv0.getDeclaredConstructor(List.class);
            wrapperCtor.setAccessible(true);
            Object wrapper = wrapperCtor.newInstance(fragments);
            Method encode = jsonCodec.getClass().getMethod("c", ch4, Object.class);
            encode.setAccessible(true);
            return String.valueOf(encode.invoke(jsonCodec, serializer, wrapper));
        } catch (Throwable t) {
            extLog("[HISTORY] fragments encode skip: " + t);
            return null;
        }
    }

    private static ArrayList mergeLocalImageFragments(List serverFragments, List oldFragments) {
        ArrayList merged = new ArrayList();
        if (serverFragments != null) merged.addAll(serverFragments);
        stripRelayDescriptionText(merged);
        HashSet<Integer> usedIds = new HashSet<>();
        int nextId = 1;
        for (Object fragment : merged) {
            Integer id = intField(fragment, "b");
            if (id == null) continue;
            usedIds.add(id);
            if (id.intValue() >= nextId) nextId = id.intValue() + 1;
        }
        int insertAt = 0;
        while (insertAt < merged.size() && isFileFragment(merged.get(insertAt))) insertAt++;
        if (oldFragments != null) {
            for (Object fragment : oldFragments) {
                if (!retainOnlyImageFiles(fragment)) continue;
                Integer id = intField(fragment, "b");
                if (id == null || usedIds.contains(id)) {
                    while (usedIds.contains(Integer.valueOf(nextId))) nextId++;
                    id = Integer.valueOf(nextId++);
                    if (!forceSetObjectField(fragment, "b", id)) continue;
                }
                usedIds.add(id);
                merged.add(insertAt++, fragment);
            }
        }
        return merged;
    }

    private static void stripRelayDescriptionText(List fragments) {
        if (fragments == null) return;
        for (Object fragment : fragments) {
            boolean request = "ws7".equals(simpleName(fragment))
                    || "REQUEST".equals(String.valueOf(fieldByName(fragment, "a")));
            if (!request) continue;
            Object content = fieldByName(fragment, "c");
            if (!(content instanceof String)) continue;
            String text = (String) content;
            int idx = text.indexOf(RELAY_PROMPT_MARKER);
            if (idx < 0) continue;
            String kept = text.substring(0, idx);
            kept = stripInjectedSystemPrompt(kept);
            int nl = kept.length();
            while (nl > 0 && (kept.charAt(nl - 1) == '\n' || kept.charAt(nl - 1) == '\r'
                    || kept.charAt(nl - 1) == ' ')) nl--;
            kept = kept.substring(0, nl);
            forceSetObjectField(fragment, "c", kept);
        }
    }

    private static String stripInjectedSystemPrompt(String text) {
        if (text == null) return "";
        String head = text;
        int lead = 0;
        while (lead < head.length() && (head.charAt(lead) == '\n' || head.charAt(lead) == '\r'
                || head.charAt(lead) == ' ')) lead++;
        if (!head.startsWith("<system>", lead)) return text;
        int close = head.indexOf("</system>", lead);
        if (close < 0) return text;
        int after = close + "</system>".length();
        while (after < head.length() && (head.charAt(after) == '\n' || head.charAt(after) == '\r'
                || head.charAt(after) == ' ')) after++;
        return head.substring(after);
    }

    private static boolean retainOnlyImageFiles(Object fragment) {
        if (!isFileFragment(fragment)) return false;
        Object filesObj = fieldByName(fragment, "c");
        if (!(filesObj instanceof List)) return false;
        List files = (List) filesObj;
        ArrayList images = new ArrayList();
        for (Object file : files) {
            if (Boolean.TRUE.equals(fieldByName(file, "k"))) images.add(file);
        }
        if (images.isEmpty()) return false;
        return images.size() == files.size() || forceSetObjectField(fragment, "c", images);
    }

    private static int countImageFiles(List fragments) {
        if (fragments == null) return 0;
        int count = 0;
        for (Object fragment : fragments) count += countImageFilesInFragment(fragment);
        return count;
    }

    private static int countImageFilesInFragment(Object fragment) {
        if (!isFileFragment(fragment)) return 0;
        Object filesObj = fieldByName(fragment, "c");
        if (!(filesObj instanceof List)) return 0;
        int count = 0;
        for (Object file : (List) filesObj) {
            if (Boolean.TRUE.equals(fieldByName(file, "k"))) count++;
        }
        return count;
    }

    private static boolean isFileFragment(Object fragment) {
        if (fragment == null) return false;
        if ("qs7".equals(simpleName(fragment))) return true;
        return "FILE".equals(String.valueOf(fieldByName(fragment, "a")));
    }

    private static Integer intField(Object obj, String name) {
        Object value = fieldByName(obj, name);
        return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null;
    }

    private static boolean forceSetObjectField(Object obj, String name, Object value) {
        if (obj == null) return false;
        try {
            Field field = obj.getClass().getDeclaredField(name);
            field.setAccessible(true);
            try {
                if (field.getType() == int.class && value instanceof Number) {
                    field.setInt(obj, ((Number) value).intValue());
                } else {
                    field.set(obj, value);
                }
                return true;
            } catch (Throwable reflectionFailure) {
                Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
                unsafeField.setAccessible(true);
                Object unsafe = unsafeField.get(null);
                long offset = ((Number) unsafeClass.getMethod("objectFieldOffset", Field.class)
                        .invoke(unsafe, field)).longValue();
                if (field.getType() == int.class && value instanceof Number) {
                    unsafeClass.getMethod("putInt", Object.class, long.class, int.class)
                            .invoke(unsafe, obj, offset, ((Number) value).intValue());
                } else if (!field.getType().isPrimitive()) {
                    unsafeClass.getMethod("putObject", Object.class, long.class, Object.class)
                            .invoke(unsafe, obj, offset, value);
                } else {
                    extLog("[HISTORY] forceSet unsupported primitive " + field.getType().getName()
                            + " for " + simpleName(obj) + "." + name);
                    return false;
                }
                return true;
            }
        } catch (Throwable t) {
            extLog("[HISTORY] forceSet " + simpleName(obj) + "." + name + " failed: " + t);
            return false;
        }
    }

    // ── ★正式功能：expert 模式带图 → 后台视觉描述中继（同步就地改写请求）────────
    private boolean relayGateMatches(Object reqObj) {
        if (!isExpertRelayEnabled()) return false;
        if (reqObj == null || !"ew0".equals(simpleName(reqObj))) return false;
        if (!"expert".equals(String.valueOf(fieldByName(reqObj, "i")))) return false;
        Object files = fieldByName(reqObj, "d");
        return (files instanceof java.util.List) && !((java.util.List) files).isEmpty();
    }

    // 已登记待中继的冷 Flow(b41 实例) -> {expertReq, r92}。等下游 collect(b41.b) 时才跑中继。
    private final java.util.Map<Object, Object[]> relayFlowMap =
            new java.util.IdentityHashMap<Object, Object[]>();

    // 命中 expert+图片时：不改返回值(避免 libxposed 把 Proxy 强转 b41 而 CCE)，
    // 只把真实 b41 实例登记下来，交给 b41.b 的 collect hook 处理。
    private void registerRelayFlow(Object reqObj, Object flow, Object r92This) {
        if (!relayGateMatches(reqObj)) return;
        synchronized (relaySeen) {
            if (relaySeen.contains(reqObj)) return;
            relaySeen.add(reqObj);
        }
        final Object r92 = (r92This != null) ? r92This : liveR92;
        if (r92 == null || flow == null) { extLog("[RELAY] register skip: r92/flow null"); return; }
        synchronized (relayFlowMap) { relayFlowMap.put(flow, new Object[]{ reqObj, r92 }); }
        extLog("[RELAY] 已登记冷 Flow=" + System.identityHashCode(flow)
                + "，等下游 collect(b41.b) 时跑中继");
    }

    // hook b41.b(q03,uz1)=Flow.collect。返回类型是 Object，返回真实 Flow 不会触发返回值强转。
    // 仅当 this 是已登记的 expert 带图冷 Flow 时介入；否则原样放行(热路径，identity 命中开销 O(1))。
    private void installExpertFlowCollectHook(ClassLoader cl) {
        try {
            Class<?> b41 = cl.loadClass("b41");
            Class<?> q03 = cl.loadClass("q03");
            Method bColl = null;
            for (Method m : b41.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("b") && p.length == 2 && p[0] == q03) { bColl = m; break; }
            }
            if (bColl == null) { log("expert flow collect hook: b41.b(q03,uz1) 未找到"); return; }
            hook(bColl).intercept(new Hooker() {
                @Override public Object intercept(Chain chain) throws Throwable {
                    Object self = chain.getThisObject();
                    Object[] entry;
                    synchronized (relayFlowMap) { entry = relayFlowMap.remove(self); }
                    if (entry == null) return chain.proceed();          // 非中继流，原样放行
                    Object[] a = chain.getArgs().toArray();
                    Object collector = a.length > 0 ? a[0] : null;
                    Object cont = a.length > 1 ? a[1] : null;
                    final Object expertReq = entry[0];
                    final Object r92 = entry[1];
                    try {
                        if (Looper.getMainLooper() != null
                                && Looper.getMainLooper().getThread() == Thread.currentThread()) {
                            // 主线程不能阻塞跑中继(7s 网络=ANR)，直接转发原流(服务端会拒但不闪退)
                            extLog("[RELAY] collect 在主线程，跳过中继直接转发原 Flow");
                            return chain.proceed();
                        }
                        extLog("[RELAY] collect 命中(flow=" + System.identityHashCode(self)
                                + " thread=" + Thread.currentThread().getName() + ")，开始中继");
                        runExpertImageRelay(r92, expertReq);
                        // 用改写后的 expertReq 重建一个新冷 Flow，collect 它(而不是带图的原流)
                        Object freshFlow = null;
                        Method bM = null;
                        for (Method mm : r92.getClass().getDeclaredMethods()) {
                            if (mm.getName().equals("b") && mm.getParameterTypes().length == 2) { bM = mm; break; }
                        }
                        if (bM != null) { bM.setAccessible(true); freshFlow = bM.invoke(r92, expertReq, null); }
                        if (freshFlow == null) {
                            extLog("[RELAY] 重取 expert Flow 失败，转发原 Flow");
                            return chain.proceed();
                        }
                        // freshFlow 也是 b41，反射调用其 b() 会再次进本 hook；但它未登记 → 直接放行原始 collect
                        return bCollInvoke(chain, freshFlow, collector, cont);
                    } catch (Throwable t) {
                        extLog("[RELAY] collect 中继异常，转发原 Flow: " + t + "\n" + stackToString(t));
                        return chain.proceed();
                    }
                }
            });
            log("installed expert flow collect hook on b41.b x1");
        } catch (Throwable t) { log("installExpertFlowCollectHook failed: " + t); }
    }

    private Object bCollInvoke(Chain chain, Object flow, Object collector, Object cont) throws Throwable {
        Method m = (Method) chain.getExecutable();
        m.setAccessible(true);
        return m.invoke(flow, collector, cont);
    }

    private String describeOneImage(Object r92, ClassLoader cl, Object expertReq,
                                    List fileIds, String label, long t0) {
        String sid = null;
        try {
            Object pow = mintCompletionPow(cl, liveQ71);
            if (!(pow instanceof String) || ((String) pow).length() == 0) {
                extLog("[RELAY]" + label + " 铸 PoW 失败；abort"); return null;
            }
            sid = createThrowawaySession(cl, r92);
            if (sid == null) { extLog("[RELAY]" + label + " 建临时会话失败；abort"); return null; }
            extLog("[RELAY]" + label + " 临时会话=" + sid
                    + " (setup " + (System.currentTimeMillis() - t0) + "ms)");
            Object visionReq = shallowCloneEw0(expertReq);
            if (visionReq == null) { extLog("[RELAY]" + label + " clone 失败；abort"); return null; }
            setFieldByName(visionReq, "a", sid);
            setFieldByName(visionReq, "b", null);
            setFieldByName(visionReq, "c", VISION_DESCRIBE_PROMPT);
            setFieldByName(visionReq, "i", "vision");
            setFieldByName(visionReq, "e", Boolean.FALSE);
            setFieldByName(visionReq, "f", Boolean.FALSE);
            setFieldByName(visionReq, "k", pow);
            if (fileIds != null) setFieldByName(visionReq, "d", new ArrayList(fileIds));

            Method bM = null;
            for (Method m : r92.getClass().getDeclaredMethods()) {
                if (m.getName().equals("b") && m.getParameterTypes().length == 2) { bM = m; break; }
            }
            if (bM == null) { extLog("[RELAY]" + label + " r92.b 未找到；abort"); return null; }
            bM.setAccessible(true);
            Object flow = bM.invoke(r92, visionReq, null);
            if (flow == null) { extLog("[RELAY]" + label + " vision r92.b 返回 null；abort"); return null; }
            String desc = collectFlow(cl, flow);
            extLog("[RELAY]" + label + " 描述 len=" + (desc == null ? 0 : desc.length())
                    + " total=" + (System.currentTimeMillis() - t0) + "ms : "
                    + truncateForLog(String.valueOf(desc), 240));
            return desc;
        } catch (Throwable t) {
            extLog("[RELAY]" + label + " describeOneImage threw: " + t);
            return null;
        } finally {
            if (sid != null) {
                try {
                    boolean del = deleteThrowawaySession(cl, r92, sid);
                    extLog("[RELAY]" + label + " 删除临时会话 " + sid + " -> " + del);
                } catch (Throwable t) { extLog("[RELAY]" + label + " 删除临时会话失败: " + t); }
            }
        }
    }

    private String describeImagesParallel(final Object r92, final ClassLoader cl,
                                          final Object expertReq, final List<String> fileIds,
                                          final long t0) {
        final int n = fileIds.size();
        final String[] results = new String[n];
        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final String fileId = fileIds.get(i);
            threads[i] = new Thread(new Runnable() {
                public void run() {
                    results[idx] = describeOneImage(r92, cl, expertReq,
                            java.util.Collections.singletonList(fileId), " 图" + (idx + 1), t0);
                }
            });
            threads[i].start();
        }
        for (int i = 0; i < n; i++) {
            try { threads[i].join(120000); } catch (Throwable ignored) {}
        }
        StringBuilder sb = new StringBuilder();
        int ok = 0;
        for (int i = 0; i < n; i++) {
            String d = results[i];
            if (d == null || d.trim().length() == 0) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("图").append(i + 1).append("：\n").append(d.trim());
            ok++;
        }
        extLog("[RELAY] 并行描述完成 images=" + n + " ok=" + ok
                + " total=" + (System.currentTimeMillis() - t0) + "ms");
        return sb.length() > 0 ? sb.toString() : null;
    }

    private void runExpertImageRelay(Object r92, Object expertReq) throws Throwable {
        if (r92 == null) { extLog("[RELAY] no live r92; abort"); return; }
        final ClassLoader cl = r92.getClass().getClassLoader();
        long t0 = System.currentTimeMillis();

        if (liveQ71 == null) { extLog("[RELAY] liveQ71 未捕获；abort（保持带图 expert 不动）"); return; }

        Object dOld0 = fieldByName(expertReq, "d");
        ArrayList<String> fileIds = new ArrayList<String>();
        if (dOld0 instanceof List) {
            for (Object o : (List) dOld0) if (o != null) fileIds.add(String.valueOf(o));
        }

        String desc;
        if (fileIds.size() <= 1) {
            desc = describeOneImage(r92, cl, expertReq,
                    fileIds.isEmpty() ? null : fileIds, "", t0);
        } else {
            desc = describeImagesParallel(r92, cl, expertReq, fileIds, t0);
        }

        if (desc != null && desc.trim().length() > 0) {
            Object cOld = fieldByName(expertReq, "c");
            Object dOld = fieldByName(expertReq, "d");
            ArrayList filesOld = dOld instanceof List ? new ArrayList((List) dOld) : null;
            String newC = String.valueOf(cOld) + "\n\n" + RELAY_PROMPT_MARKER + "\n" + desc.trim();
            setFieldByName(expertReq, "c", newC);
            if (dOld instanceof java.util.List) {
                try { ((java.util.List) dOld).clear(); }
                catch (Throwable t) { setFieldByName(expertReq, "d", new java.util.ArrayList()); }
            } else {
                setFieldByName(expertReq, "d", new java.util.ArrayList());
            }
            Object cAfter = fieldByName(expertReq, "c");
            Object dAfter = fieldByName(expertReq, "d");
            boolean promptOk = newC.equals(cAfter);
            boolean filesOk = dAfter instanceof List && ((List) dAfter).isEmpty();
            if (promptOk && filesOk) {
                String relaySid = stringField(expertReq, "a");
                rememberExpertRelaySession(relaySid, "relay-success");
                try { persistRelayImages(cl, relaySid, expertReq); }
                catch (Throwable t) { extLog("[RELAY] persistRelayImages err: " + t); }
                extLog("[RELAY] ✓ expert 已改写为纯文本，newPromptLen=" + newC.length()
                        + " 文件已清空");
            } else {
                setFieldByName(expertReq, "c", cOld);
                if (dOld instanceof List) {
                    try {
                        ((List) dOld).clear();
                        ((List) dOld).addAll(filesOld);
                        setFieldByName(expertReq, "d", dOld);
                    } catch (Throwable ignored) {
                        setFieldByName(expertReq, "d", filesOld);
                    }
                } else {
                    setFieldByName(expertReq, "d", dOld);
                }
                extLog("[RELAY] expert 改写校验失败，已尝试恢复原请求 promptOk="
                        + promptOk + " filesOk=" + filesOk);
            }
        } else {
            extLog("[RELAY] 描述为空；保持带图 expert 不动（服务端仍会拒，与未开启前一致）");
        }
    }

    private String createThrowawaySession(ClassLoader cl, Object r92) {
        try {
            java.lang.reflect.Field bf = r92.getClass().getDeclaredField("b"); // i91
            bf.setAccessible(true);
            Object i91 = bf.get(r92);
            Method createM = null;
            for (Method m : i91.getClass().getDeclaredMethods()) {
                if (m.getName().equals("a") && m.getParameterTypes().length == 1) { createM = m; break; }
            }
            if (createM == null) { extLog("[RELAY] i91.a(create) 未找到"); return null; }
            Object res = driveSuspend(cl, createM, i91, new Object[0]);
            String body = String.valueOf(fieldByName(res, "j"));
            return extractSessionId(body);
        } catch (Throwable t) { extLog("[RELAY] createThrowawaySession err: " + t); return null; }
    }

    private static String extractSessionId(String body) {
        if (body == null) return null;
        int cs = body.indexOf("\"chat_session\"");
        if (cs < 0) return null;
        int idk = body.indexOf("\"id\":\"", cs);
        if (idk < 0) return null;
        int start = idk + 6;
        int end = body.indexOf('"', start);
        if (end < 0) return null;
        String id = body.substring(start, end);
        return id.length() > 0 ? id : null;
    }

    private boolean deleteThrowawaySession(ClassLoader cl, Object r92, String sid) {
        try {
            java.lang.reflect.Field bf = r92.getClass().getDeclaredField("b"); // i91
            bf.setAccessible(true);
            Object i91 = bf.get(r92);
            Object jb1 = cl.loadClass("jb1").getConstructor(String.class).newInstance(sid);
            Method delM = null;
            for (Method m : i91.getClass().getDeclaredMethods()) {
                if (m.getName().equals("c") && m.getParameterTypes().length == 2) { delM = m; break; }
            }
            if (delM == null) { extLog("[RELAY] i91.c(delete) 未找到"); return false; }
            driveSuspend(cl, delM, i91, new Object[]{ jb1 });
            return true;
        } catch (Throwable t) { extLog("[RELAY] deleteThrowawaySession err: " + t); return false; }
    }

    private Object mintCompletionPow(ClassLoader cl, Object q71) throws Throwable {
        Method jm = null;
        for (Method m : q71.getClass().getDeclaredMethods()) {
            if (m.getName().equals("j") && m.getParameterTypes().length == 1) { jm = m; break; }
        }
        if (jm == null) { extLog("[VP] q71.j not found"); return null; }
        Object res = driveSuspend(cl, jm, q71, new Object[0]);
        extLog("[VP] q71.j resumed: " + deepDump(res, 2));
        if (res == null) return null;
        Object a = fieldByName(res, "a");   // b36{a=base64 pow, b=error}
        return a;
    }

    private volatile Method cachedRunBlocking;
    private Object driveSuspend(ClassLoader cl, final Method m, final Object target, final Object[] preArgs) throws Throwable {
        Class<?> n02 = cl.loadClass("n02");
        Class<?> mb3 = cl.loadClass("mb3");
        // runBlocking(CoroutineContext, Function2)=静态 (n02,mb3)->Object。
        // build 间该 holder 类改名(2.2.1=t82 / 2.2.2=u82)，按候选名 + 结构签名兜底解析。
        Method K = cachedRunBlocking;
        if (K == null) {
            for (String nm : new String[]{"u82", "t82", "v82", "s82", "w82"}) {
                try {
                    Class<?> holder = cl.loadClass(nm);
                    for (Method mm : holder.getDeclaredMethods()) {
                        Class<?>[] p = mm.getParameterTypes();
                        if (java.lang.reflect.Modifier.isStatic(mm.getModifiers())
                                && p.length == 2 && p[0] == n02 && p[1] == mb3) { K = mm; break; }
                    }
                } catch (Throwable ignored) {}
                if (K != null) { extLog("[VP] runBlocking=" + nm + ".K"); break; }
            }
            if (K != null) cachedRunBlocking = K;
        }
        if (K == null) { extLog("[VP] runBlocking(n02,mb3) not found"); return null; }
        K.setAccessible(true);
        m.setAccessible(true);
        final Object ctx = emptyContextProxy(cl, n02);
        InvocationHandler blockH = new InvocationHandler() {
            public Object invoke(Object proxy, Method mm, Object[] a) throws Throwable {
                if (isObjectMethod(mm)) return objectMethod(proxy, mm, a);
                Object cont = (a != null && a.length > 0) ? a[a.length - 1] : null;
                Object[] args = new Object[preArgs.length + 1];
                System.arraycopy(preArgs, 0, args, 0, preArgs.length);
                args[preArgs.length] = cont;
                try {
                    return m.invoke(target, args);
                } catch (java.lang.reflect.InvocationTargetException ite) {
                    throw (ite.getCause() != null ? ite.getCause() : ite);
                }
            }
        };
        Object block = Proxy.newProxyInstance(cl, new Class<?>[]{mb3}, blockH);
        return K.invoke(null, ctx, block);
    }

    private Object shallowCloneEw0(Object src) {
        if (src == null) return null;
        try {
            Class<?> cls = src.getClass();
            Class<?> unsafeCls = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeCls.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Method alloc = unsafeCls.getMethod("allocateInstance", Class.class);
            Object dst = alloc.invoke(unsafe, cls);
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    try { f.set(dst, f.get(src)); } catch (Throwable ignored) {}
                }
            }
            return dst;
        } catch (Throwable t) {
            extLog("[VP] shallowCloneEw0 failed: " + t);
            return null;
        }
    }

    private static void setFieldByName(Object obj, String name, Object val) {
        if (obj == null) return;
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(obj, val);
                return;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) { return; }
        }
    }

    private String collectFlow(ClassLoader cl, Object flow) {
        final StringBuilder descBuf = new StringBuilder();
        try {
            Method collectM = null;
            for (Class<?> itf : allInterfaces(flow.getClass())) {
                Method cand = null; int two = 0;
                for (Method m : itf.getDeclaredMethods()) {
                    if (m.getParameterTypes().length == 2) { cand = m; two++; }
                }
                if (two == 1 && cand.getParameterTypes()[1].isInterface()) { collectM = cand; break; }
            }
            if (collectM == null) { extLog("[VP] Flow interface (1x 2-arg method) not found"); return null; }
            final Class<?> collectorCls = collectM.getParameterTypes()[0];
            final Class<?> contCls = collectM.getParameterTypes()[1];
            Class<?> ccTmp = null;
            for (Method m : contCls.getMethods()) {
                if (m.getParameterTypes().length == 0 && m.getReturnType().isInterface()) { ccTmp = m.getReturnType(); break; }
            }
            final Class<?> ccCls = ccTmp;
            extLog("[VP] collect=" + collectM.getName() + " collector=" + collectorCls.getName()
                    + " cont=" + contCls.getName() + " ctx=" + (ccCls == null ? "null" : ccCls.getName()));
            final Object ctx = (ccCls != null) ? emptyContextProxy(cl, ccCls) : null;

            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final int[] count = {0};
            final StringBuilder acc = new StringBuilder();

            InvocationHandler contH = new InvocationHandler() {
                public Object invoke(Object proxy, Method m, Object[] a) {
                    if (isObjectMethod(m)) return objectMethod(proxy, m, a);
                    int p = m.getParameterTypes().length;
                    if (p == 0) return ctx;                        // getContext()
                    extLog("[VP] flow completed; events=" + count[0]
                            + " resumeArg=" + (a != null && a.length > 0 ? String.valueOf(a[0]) : "?"));
                    latch.countDown();
                    return null;
                }
            };
            final Object rootCont = Proxy.newProxyInstance(cl, new Class<?>[]{contCls}, contH);

            InvocationHandler collH = new InvocationHandler() {
                public Object invoke(Object proxy, Method m, Object[] a) {
                    if (isObjectMethod(m)) return objectMethod(proxy, m, a);
                    if (m.getParameterTypes().length == 2) {       // emit(value, cont)
                        try {
                            Object value = a[0];
                            count[0]++;
                            String s = summarizeFlowEvent(value);
                            if (count[0] <= 80) extLog("[VP] emit#" + count[0] + " " + s);
                            acc.append(s).append('\n');
                            String delta = extractContentDeltaFromEvent(value);
                            if (delta != null) descBuf.append(delta);
                        } catch (Throwable t) { extLog("[VP] emit err " + t); }
                        return null;
                    }
                    return null;
                }
            };
            Object collector = Proxy.newProxyInstance(cl, new Class<?>[]{collectorCls}, collH);

            collectM.setAccessible(true);
            extLog("[VP] invoking collect on " + flow.getClass().getName());
            Object ret;
            try {
                ret = collectM.invoke(flow, collector, rootCont);
            } catch (java.lang.reflect.InvocationTargetException ite) {
                Throwable c = ite.getCause() != null ? ite.getCause() : ite;
                extLog("[VP] collect threw: " + c + "\n" + stackToString(c));
                return descBuf.toString();
            }
            extLog("[VP] collect returned: " + String.valueOf(ret));
            latch.await(90, java.util.concurrent.TimeUnit.SECONDS);
            extLog("[VP] DONE events=" + count[0] + " accLen=" + acc.length()
                    + " descLen=" + descBuf.length()
                    + " acc=" + truncateForLog(acc.toString(), 1200));
        } catch (Throwable t) {
            extLog("[VP] collectFlow failed: " + t + "\n" + stackToString(t));
        }
        return descBuf.toString();
    }

    private String extractContentDeltaFromEvent(Object value) {
        try {
            if (!"xs0".equals(simpleName(value))) return null;
            Object lv7 = fieldByName(value, "a");
            if (lv7 == null) return null;
            Object ename = fieldByName(lv7, "a");
            if (ename != null) return null;
            Object bj = fieldByName(lv7, "b");
            if (!(bj instanceof String)) return null;
            return extractContentDelta((String) bj);
        } catch (Throwable t) { return null; }
    }

    private static String extractContentDelta(String json) {
        if (json == null) return null;
        int vi = json.indexOf("\"v\":\"");
        if (vi < 0) return null;
        boolean bareDelta = json.startsWith("{\"v\":\"");
        boolean appendContent = json.contains("content") && json.contains("APPEND");
        if (!bareDelta && !appendContent) return null;
        int start = vi + 5;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '\\' && i + 1 < json.length()) {
                char nx = json.charAt(i + 1);
                switch (nx) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': break;
                    default:  sb.append(nx);
                }
                i++;
                continue;
            }
            if (ch == '"') break;
            sb.append(ch);
        }
        return sb.toString();
    }

    private static String stackToString(Throwable t) {
        if (t == null) return "";
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < st.length && i < 18; i++) sb.append("    at ").append(st[i]).append('\n');
        Throwable cause = t.getCause();
        if (cause != null && cause != t) sb.append("  caused by: ").append(cause).append('\n');
        return sb.toString();
    }

    private Object emptyContextProxy(ClassLoader cl, final Class<?> ccCls) {
        InvocationHandler h = new InvocationHandler() {
            public Object invoke(Object proxy, Method m, Object[] a) {
                if (isObjectMethod(m)) return objectMethod(proxy, m, a);
                int p = m.getParameterTypes().length;
                if (p == 2) {
                    boolean a0fn = isFunction2(a[0]);
                    boolean a1fn = isFunction2(a[1]);
                    if (a0fn && !a1fn) return a[1];
                    if (a1fn && !a0fn) return a[0];
                    return a[1];
                }
                if (p == 1) {
                    Class<?> rt = m.getReturnType();
                    if (rt == ccCls) {
                        Object arg = a[0];
                        return (arg != null && ccCls.isInstance(arg)) ? arg : proxy;
                    }
                    return null;
                }
                return null;
            }
        };
        return Proxy.newProxyInstance(cl, new Class<?>[]{ccCls}, h);
    }

    private static boolean isObjectMethod(Method m) {
        return m.getDeclaringClass() == Object.class;
    }

    private static boolean isFunction2(Object o) {
        if (o == null) return false;
        for (Class<?> itf : allInterfaces(o.getClass())) {
            for (Method m : itf.getDeclaredMethods()) {
                if (m.getParameterTypes().length == 2 && !isObjectMethod(m)) return true;
            }
        }
        return false;
    }

    private static Object objectMethod(Object proxy, Method m, Object[] a) {
        String n = m.getName();
        if ("toString".equals(n)) return "VPProxy@" + System.identityHashCode(proxy);
        if ("hashCode".equals(n)) return System.identityHashCode(proxy);
        if ("equals".equals(n)) return proxy == (a != null && a.length > 0 ? a[0] : null);
        return null;
    }

    private static java.util.List<Class<?>> allInterfaces(Class<?> cls) {
        java.util.LinkedHashSet<Class<?>> out = new java.util.LinkedHashSet<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            collectItfs(c, out);
        }
        return new java.util.ArrayList<>(out);
    }

    private static void collectItfs(Class<?> c, java.util.Set<Class<?>> out) {
        for (Class<?> i : c.getInterfaces()) {
            if (out.add(i)) collectItfs(i, out);
        }
    }

    private static String summarizeFlowEvent(Object v) {
        if (v == null) return "null";
        String n = simpleName(v);
        if ("lv7".equals(n)) {
            return "lv7{event=" + logValue(fieldByName(v, "a")) + ", data=" + logValue(fieldByName(v, "b")) + "}";
        }
        String nr = summarizeNetworkResult(v);
        if (nr != null) return n + " " + nr;
        return deepDump(v, 3);
    }

    private static String deepDump(Object v, int depth) {
        if (v == null) return "null";
        if (v instanceof String || v instanceof Number || v instanceof Boolean) return logValue(v);
        if (v instanceof java.util.List || v instanceof java.util.Map
                || v instanceof android.net.Uri) return logValue(v);
        String n = simpleName(v);
        if (depth <= 0) return n + "(" + truncateForLog(String.valueOf(v), 80) + ")";
        StringBuilder sb = new StringBuilder(n).append("{");
        int k = 0;
        for (Field f : v.getClass().getDeclaredFields()) {
            try {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object fv = f.get(v);
                if (k > 0) sb.append(", ");
                sb.append(f.getName()).append('=').append(deepDump(fv, depth - 1));
                if (++k >= 16) { sb.append(", ..."); break; }
            } catch (Throwable ignored) {}
        }
        return sb.append('}').toString();
    }

    private static String summarizeNetworkResult(Object result) {
        if (result == null) return null;
        String n = simpleName(result);
        if ("w02".equals(n)) return null;
        if ("kp5".equals(n)) {
            Object biz = fieldByName(result, "a");
            Object data = fieldByName(result, "b");
            String dataName = simpleName(data);
            String bizName = simpleName(biz);
            if ("fp".equals(dataName) || "ul6".equals(dataName) || "vx2".equals(bizName)) {
                return "ok biz=" + logValue(biz) + " data=" + logValue(data);
            }
            return null;
        }
        if ("op5".equals(n)) {
            Object biz = fieldByName(result, "a");
            if ("vx2".equals(simpleName(biz))) {
                return "err biz=" + logValue(biz)
                        + " msg=" + logValue(fieldByName(result, "b"))
                        + " detail=" + logValue(fieldByName(result, "c"));
            }
        }
        return null;
    }

    private static String summarizeFp(Object fp) {
        if (fp == null) return "null";
        return "fp{file_id=" + logValue(fieldByName(fp, "a"))
                + ", status=" + logValue(fieldByName(fp, "b"))
                + ", name=" + logValue(fieldByName(fp, "c"))
                + ", size=" + logValue(fieldByName(fp, "d"))
                + ", inserted_at=" + logValue(fieldByName(fp, "e"))
                + ", updated_at=" + logValue(fieldByName(fp, "f"))
                + ", token_usage=" + logValue(fieldByName(fp, "g"))
                + ", previewable=" + logValue(fieldByName(fp, "h"))
                + ", from_share=" + logValue(fieldByName(fp, "i"))
                + ", signed_path=" + logValue(fieldByName(fp, "j"))
                + ", is_image=" + logValue(fieldByName(fp, "k"))
                + ", audit_result=" + logValue(fieldByName(fp, "l"))
                + ", width=" + logValue(fieldByName(fp, "m"))
                + ", height=" + logValue(fieldByName(fp, "n"))
                + ", retryable=" + logValue(fieldByName(fp, "o")) + "}";
    }

    private static String summarizeUl6(Object ul6) {
        if (ul6 == null) return "null";
        return "ul6{files=" + logValue(fieldByName(ul6, "a")) + "}";
    }

    private static Object fieldByName(Object obj, String name) {
        if (obj == null) return null;
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String logValue(Object v) {
        if (v == null) return "null";
        if (v instanceof String) {
            String s = (String) v;
            return "String(len=" + s.length() + ", \"" + truncateForLog(s, 320) + "\")";
        }
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        if (v instanceof java.util.List) {
            java.util.List list = (java.util.List) v;
            StringBuilder sb = new StringBuilder("List(size=").append(list.size()).append(", [");
            for (int i = 0; i < list.size() && i < 6; i++) {
                if (i > 0) sb.append(", ");
                sb.append(logValue(list.get(i)));
            }
            if (list.size() > 6) sb.append(", ...");
            return sb.append("])").toString();
        }
        if (v instanceof java.util.Map) {
            return "Map(size=" + ((java.util.Map) v).size() + ")";
        }
        if (v instanceof android.net.Uri) return "Uri(" + truncateForLog(String.valueOf(v), 200) + ")";
        String n = simpleName(v);
        if ("fp".equals(n)) return summarizeFp(v);
        if ("ul6".equals(n)) return summarizeUl6(v);
        if ("jv0".equals(n)) return String.valueOf(v);
        String s = String.valueOf(v);
        return n + "(" + truncateForLog(s, 160) + ")";
    }

    private static String truncateForLog(String s, int max) {
        if (s == null) return "null";
        String t = s.replace('\n', ' ').replace('\r', ' ');
        if (t.length() <= max) return t;
        return t.substring(0, max) + "...<len=" + t.length() + ">";
    }

    private static String simpleName(Object obj) {
        if (obj == null) return "null";
        String n = obj instanceof Class ? ((Class<?>) obj).getName() : obj.getClass().getName();
        int idx = n.lastIndexOf('.');
        return idx >= 0 ? n.substring(idx + 1) : n;
    }
}

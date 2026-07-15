package com.dsmod.inject;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.system.Os;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class Main implements IXposedHookLoadPackage {

    private static final String TAG = "DSPROBE";
    private static final String TARGET = "com.deepseek.chat";
    static final String SELF = "com.dsmod.inject";
    private static final String LOG_PATH = "/data/data/com.deepseek.chat/files/dsprobe.log";
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    // 存储在 DeepSeek 自己的 files 目录，hook 进程和 UI 都能直接读写
    static final String PROMPT_FILE       = "/data/data/com.deepseek.chat/files/deekseep_prompt.txt";
    static final String PROMPT_LINK_FILE  = "/data/data/com.deepseek.chat/files/deekseep_prompt_link.txt";
    static final String PROMPT_SOURCE_FILE = "/data/data/com.deepseek.chat/files/deekseep_prompt_source.txt";
    static final String ENABLED_FILE      = "/data/data/com.deepseek.chat/files/deekseep_enabled";
    static final String NO_CENSOR_FILE    = "/data/data/com.deepseek.chat/files/deekseep_nocensor";
    static final String SRVLOG_FILE       = "/data/data/com.deepseek.chat/files/deekseep_srvlog";
    static final String CHAT_MULTISELECT_FILE = "/data/data/com.deepseek.chat/files/deekseep_chat_multiselect";
    // 视觉描述自发探针开关：存在=开启。开启后，拦到带图的 expert 请求会额外自发一条
    // model_type=vision 的合成请求，收集返回的 Flow，把原始 SSE/描述写进外部中继日志。
    static final String VISION_PROBE_FILE = "/data/data/com.deepseek.chat/files/deekseep_vision_probe";
    // 铸币测试开关：存在=开启。拦到任意 completion 时，仅用 driveSuspend(runBlocking)+q71.j
    // 铸一份新鲜 PoW 并写日志，**不发送任何请求、不改任何请求**——纯验证协程驱动链路是否可用。
    // 目的：在把「建会话/发视觉/删会话」大流水线堆上去之前，先隔离确认 driveSuspend 这个基石本身可用。
    static final String MINT_TEST_FILE    = "/data/data/com.deepseek.chat/files/deekseep_mint_test";
    // 建会话空跑开关：与 mint_test 同时存在时，铸币后再驱动 i91.a 建一个会话并 dump 结果（实证 session_id 路径）。
    static final String SESS_TEST_FILE    = "/data/data/com.deepseek.chat/files/deekseep_sess_test";
    // ★正式功能开关：expert 模式带图 → 后台视觉描述中继。存在=开启。
    static final String EXPERT_RELAY_FILE = "/data/data/com.deepseek.chat/files/deekseep_expert_relay";
    // 已成功走过中继的原会话。按 sid 落独立标记，重启后历史同步不再依赖服务端模型字段。
    static final String EXPERT_RELAY_SESSION_DIR =
            "/data/data/com.deepseek.chat/files/deekseep_expert_relay_sessions";
    static final String RELAY_PROMPT_MARKER = "【图片内容（自动识别）】";
    // 中继捕获的图片 fragment（qs7 JSON）按原会话 sid 落盘，供强杀重开后 pw0/fm8 注入。
    // 图片从不进本地消息表（rows=0），唯一完整来源是发送点 fu0.i / uu0(kv.l()) 里的 List<fp>。
    static final String RELAY_IMAGE_DIR =
            "/data/data/com.deepseek.chat/files/deekseep_relay_images";
    // 发给 vision 的中性描述指令（绝不能带用户越狱系统提示，否则 vision 会拒答）。
    static final String VISION_DESCRIBE_PROMPT =
            "请客观描述这张图片，100到200字：包括主要事物、颜色、场景、画面细节，以及逐字转录图中出现的所有文字。只做客观描述，不评价、不拒绝、不添加与图片无关的内容。";
    static final int    PICK_REQUEST      = 0xDE3E;

    // 视觉探针状态：活着的 r92 实例（transport 入口）与是否已触发过一次
    private static volatile Object liveR92;
    private static volatile Object liveQ71;   // completion PoW 管理器实例（q71），用于给 vision 请求铸新 PoW
    private static volatile Object liveFm8;   // 当前账号 WCDB 仓库，用于历史响应合回本地图片 fragment
    private static final HashSet<String> expertRelaySessionIds = new HashSet<>();
    // 发送点(fu0.y/uu0.y)捕获的图片 fp 列表：主线程同栈传给紧随其后的 r92.b hook。
    private static final ThreadLocal<List> tlPendingFps = new ThreadLocal<>();
    // 把捕获到的 List<fp> 挂到对应 ew0 上（relay 在收集时/IO 线程跑，ThreadLocal 到不了）。
    private static final Map<Object, List> ew0Fps =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object, List>());
    private static volatile boolean visionProbeFired = false;
    private static volatile boolean mintTestFired = false;

    // 诊断：记录服务器返回的 SSE 原始事件（受 SRVLOG_FILE 开关控制）
    static final String SRV_LOG_PATH = "/data/data/com.deepseek.chat/files/deekseep_srv.log";
    static final String SRV_LOG_EXT  = "/storage/emulated/0/deekseep_srv.log";
    // 视觉探针诊断日志（私有目录，直写，最可靠）
    static final String RELAY_LOG_PATH = "/data/data/com.deepseek.chat/files/deekseep_vision.log";

    // DeekseepUi 选完文件后的 UI 刷新回调
    static volatile Runnable onPickComplete;

    private static final Map<String, Object> SIDEBAR_DELETE_ACTIONS = new HashMap<>();
    private static final Map<String, Object> SIDEBAR_CLICK_ACTIONS = new HashMap<>();
    private static final HashSet<String> SIDEBAR_SELECTED = new HashSet<>();
    private static volatile View sidebarSelectOverlay;
    private static volatile boolean sidebarSelectMode = false;
    private static volatile String sidebarCurrentSid;
    private static volatile long sidebarBoundsLogAt;
    // 本次多选会话是否已确认看到行处于屏内（左坐标非负）；用于收起检测的解锁，避免重入时读到旧的 -sidebarW 坐标误判收起
    private static volatile boolean sidebarConfirmedOpen = false;
    // 会话行真实 Compose 坐标（decor/window 空间：left,top,right,bottom），由 onGloballyPositioned 回调写入
    private static final Map<String, int[]> SIDEBAR_ROW_BOUNDS = new java.util.concurrent.ConcurrentHashMap<>();
    // 每个 sid 复用同一个 ib3 回调，保证 lw5 元素 equals 稳定，避免 Compose 节点抖动
    private static final Map<String, Object> SIDEBAR_BOUNDS_CB = new HashMap<>();
    // bm4(LayoutCoordinates) 方法：i()=isAttached, k()=size(packed long), w(long)=localToWindow
    private static volatile Method BM4_I, BM4_K, BM4_W;

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

    // 专家模式解锁开关（Deekseep 界面里可切换）；存在=开启
    static final String EXPERT_UNLOCK_FILE = "/data/data/com.deepseek.chat/files/deekseep_expert_unlock";
    static final String AUTO_BACKUP_FILE = "/data/data/com.deepseek.chat/files/deekseep_auto_backup";
    // 从其它模型配置里俘获的真实 feature 对象模板（比伪造的更安全）
    private static volatile Object tplThink;
    private static volatile Object tplSearch;
    private static volatile Object tplFile;
    // expert 解锁用的 sf5/gf5 反射字段 + 已见到的 expert 实例（用于事后回填）
    private static Field EX_A, EX_F, EX_G, EX_J, EX_K, EX_L, GF5_C, GF5_D, GF5_E, GF5_F;
    private static final java.util.List<Object> expertInsts = new java.util.ArrayList<>();
    private static final String[] IMAGE_EXTS = {"jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif"};

    private static final String SETTINGS_CLASS = "u25";
    private static final String SETTINGS_METHOD = "i";

    private final Handler main = new Handler(Looper.getMainLooper());
    private WeakReference<Activity> curAct = new WeakReference<>(null);
    private WeakReference<TextView> btn = new WeakReference<>(null);
    private WeakReference<Object> navController = new WeakReference<>(null);

    static synchronized void log(String msg) {
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

    // 探针诊断日志：私有目录直写为主，同时尽力镜像一份到公共目录（FPA 下注入进程直写
    // /storage/emulated/0/ 实测可行；之前失效的是「模块进程中继」那套，不是直写）。
    static synchronized void extLog(String msg) {
        try { XposedBridge.log(TAG + " " + msg); } catch (Throwable ignored) {}
        String line = TS.format(new Date()) + "  " + msg + "\n";
        try {
            FileWriter w = new FileWriter(RELAY_LOG_PATH, true);
            w.write(line);
            w.close();
        } catch (Throwable ignored) {}
        try {
            FileWriter w = new FileWriter("/storage/emulated/0/deekseep_vision.log", true);
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
        log("module loaded (inject-legacy), package=" + pkg);
        log("[VP] probe build active (vision self-send probe compiled in)");
        // 旧版本的 UI 开关只写 unlock marker；升级后把它迁移到正式 relay gate。
        if (new File(EXPERT_UNLOCK_FILE).exists() && !new File(EXPERT_RELAY_FILE).exists()) {
            try { overwriteTextFile(EXPERT_RELAY_FILE, ""); }
            catch (Throwable t) { log("expert relay gate migration failed: " + t); }
        }
        // 自动备份聊天数据库（后台，开启且距上次 >24h 才执行）
        try {
            new Thread(new Runnable() {
                public void run() { DeekseepTools.maybeAutoBackup(); }
            }).start();
        } catch (Throwable ignored) {}
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
                                        "DeekseepX 已注入 (v" + SettingsActivity.VERSION + ")",
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
                    try {
                        if (curAct.get() == param.thisObject) {
                            hideButton();
                            exitSidebarSelectMode();
                        }
                    } catch (Throwable ignored) {}
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
        // hook ServerMessageHint(kb7) 构造，强制 clear_response=false
        hookSafetyRetraction(cl);
        // 诊断：抓取服务器返回的 SSE 原始事件（lv7）
        installServerCapture(cl);
        // 诊断：抓 completion/file 等网络请求对象，以及文件解析返回对象
        installNetworkPayloadCapture(cl);
        installFileParseCapture(cl);
        installNetworkDecodeCapture(cl);
        installPowManagerCapture(cl);
        // 专家图片中继的历史修复：在线历史文本走服务器，图片附件保留本地版本。
        installExpertHistoryImagePreserver(cl);
        // 发送点捕获完整 List<fp>（图片唯一完整来源），供中继按 sid 落盘。
        installExpertImageFpCapture(cl);
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
        // ★ 实验：专家模式(expert)解锁 聊天/搜索/上传文件（sf5 构造后强改 final 字段）
        hookExpertUnlock(cl);
        // 诊断：定位专家模式上传图片失败到底是扩展名校验还是发送/模型二次拦截
        hookExpertDiagnostics(cl);

        // ★ 核心实验：把 Deekseep 作为真实 Compose 设置项注入
        ComposeHook.install(cl, Main::log, () -> curAct.get());

        // ★ 消息长按菜单新增"编辑"项：改内存 fragment + 写回本地 SQLite
        MessageEditHook.install(cl, Main::log, () -> curAct.get());

        // ★ 原版左侧对话列表：长按改为批量删除面板
        hookSidebarMultiSelectDelete(cl);
        hookSidebarToggleCleanup(cl);

        // ★ 启动时清理历史注入的 <system> 前缀，避免重开后泄露到真实对话（UI 加载前跑）
        new Thread(new Runnable() { public void run() {
            try { int n = ChatEditorUi.stripAllSessions(); log("stripAllSessions cleaned=" + n); }
            catch (Throwable t) { log("stripAllSessions err: " + t); }
        }}).start();

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
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            final Object tpObj = param.args[0];
                            final String sid = String.valueOf(fieldByName(tpObj, "a"));
                            if (sid == null || sid.length() == 0 || "null".equals(sid)) return;
                            if (Boolean.TRUE.equals(param.args[2])) {
                                String oldSid = sidebarCurrentSid;
                                sidebarCurrentSid = sid;
                                if (sidebarSelectMode && oldSid != null && !oldSid.equals(sid)) {
                                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                                        public void run() { slideOutSidebarOverlayAndExit(); }
                                    });
                                }
                            }
                            synchronized (SIDEBAR_DELETE_ACTIONS) {
                                if (param.args[3] != null) SIDEBAR_CLICK_ACTIONS.put(sid, param.args[3]);
                                if (param.args[7] != null) SIDEBAR_DELETE_ACTIONS.put(sid, param.args[7]);
                            }
                            if (!isChatMultiSelect()) return;
                            param.args[4] = buildSidebarLongPressProxy(cl, sid);
                            // 给该行 Modifier 追加 onGloballyPositioned，回传真实屏幕坐标
                            if (param.args.length > 9 && param.args[9] != null) {
                                Object wrapped = wrapModifierWithBoundsCapture(cl, sid, param.args[9]);
                                if (wrapped != null) param.args[9] = wrapped;
                            }
                        } catch (Throwable t) { log("sidebar multi-select hook row err: " + t); }
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
                    return ti8Unit(cl);
                }
                return ti8Unit(cl);
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
                            // 本 build 的 bm4.w(long) 实为 windowToLocal：w(0,0) 返回行窗口坐标的负值，
                            // 故取负还原为真实窗口坐标（纯平移列表，无缩放/旋转，负号即精确逆变换）。
                            long pos = (Long) BM4_W.invoke(coords, 0L);
                            int x = -(int) Float.intBitsToFloat((int) (pos >> 32));
                            int y = -(int) Float.intBitsToFloat((int) (pos & 0xFFFFFFFFL));
                            if (hpx > 0) SIDEBAR_ROW_BOUNDS.put(sid, new int[]{x, y, x + wpx, y + hpx});
                        }
                    } catch (Throwable ignored) {}
                }
                return ti8Unit(cl);
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

    private void hookSidebarToggleCleanup(final ClassLoader cl) {
        try {
            Class<?> mq5 = cl.loadClass("mq5");
            final Class<?> xa3 = cl.loadClass("xa3");
            int n = 0;
            for (Method m : mq5.getDeclaredMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (!m.getName().equals("i") || pts.length != 6 || !xa3.isAssignableFrom(pts[2])) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (param.args[2] != null) param.args[2] = buildSidebarToggleProxy(cl, param.args[2]);
                        } catch (Throwable t) { log("sidebar toggle cleanup row err: " + t); }
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

    private static Object ti8Unit(ClassLoader cl) {
        try {
            Field f = cl.loadClass("ti8").getDeclaredField("a");
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
        final int sub = dark ? 0xFF9A9A9E : 0xFF777777;
        final int div = dark ? 0xFF3A3A3D : 0xFFEAEAEA;
        final int brand = DeekseepUi.BRAND;
        final int danger = 0xFFE53935;
        final int checkColor = dark ? 0xFFECECEC : 0xFF1A1A1A;
        final int screenW = act.getResources().getDisplayMetrics().widthPixels;
        final float screenDp = screenW / act.getResources().getDisplayMetrics().density;
        final int sidebarW = screenDp < 600.0f ? screenW : Math.min(DeekseepUi.dp(act, 320), screenW);

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
            addSidebarCheckMark(act, marks, s, title, delete, sidebarW, checkColor, top, markH);
        }
    }

    // 侧栏收起检测：本 build 收起抽屉不走 mq5.i.u 回调，但 onGloballyPositioned 会把行
    // 左边坐标从 0 一路平移到 -sidebarW（滑出左屏）。横向不会滚动，故左坐标显著为负即判定收起。
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
            addSidebarCheckMark(act, marks, sessions.get(i), title, delete, sidebarW, checkColor, y, rowH);
        }
    }

    private static void addSidebarCheckMark(final Activity act, final FrameLayout marks,
                                            final ChatEditorUi.Session s,
                                            final TextView title, final TextView delete,
                                            final int sidebarW, final int checkColor,
                                            int top, int rowH) {
        final TextView mark = new TextView(act);
        // 整个行右侧做成透明可点击热区：对勾字形靠右显示，触摸区向左延伸约 40% 行宽，
        // 这样多选时点行右边任意位置即可勾选，左侧仍可点标题切换会话。
        mark.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        mark.setIncludeFontPadding(false);
        mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        mark.setTypeface(Typeface.DEFAULT_BOLD);
        mark.setPadding(0, 0, DeekseepUi.dp(act, 19), 0);
        mark.setClickable(true);
        mark.setFocusable(false);
        updateSidebarMarkState(mark, s.id, checkColor);
        int touchW = Math.max(DeekseepUi.dp(act, 96), sidebarW * 2 / 5);
        if (touchW > sidebarW) touchW = sidebarW;
        FrameLayout.LayoutParams markLp = new FrameLayout.LayoutParams(touchW, rowH);
        markLp.leftMargin = Math.max(0, sidebarW - touchW);
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

    // 侧边栏收回时调用：多选覆盖层向上滑出并淡出后再移除（与侧栏收起动作绑定）。
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
        if (action == null) return ti8Unit(cl);
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
        return ti8Unit(cl);
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

    static boolean isExpertUnlock() {
        return isExpertRelayEnabled();
    }

    private static boolean isExpertRelayEnabled() {
        return new File(EXPERT_UNLOCK_FILE).exists() && new File(EXPERT_RELAY_FILE).exists();
    }

    static void setExpertUnlock(boolean on) {
        File ef = new File(EXPERT_UNLOCK_FILE);
        File rf = new File(EXPERT_RELAY_FILE);
        try {
            if (on) {
                overwriteTextFile(EXPERT_UNLOCK_FILE, "");
                overwriteTextFile(EXPERT_RELAY_FILE, "");
            } else {
                ef.delete();
                rf.delete();
            }
        } catch (Throwable t) {
            if (on) {
                ef.delete();
                rf.delete();
            }
            log("setExpertUnlock failed: " + t);
        }
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

    // 专家模式解锁：sf5 是模型配置，a=model_type，f=enabled，g=switchable，
    // j=think_feature(of5)，k=search_feature(lf5)，l=file_feature(gf5)。
    // 服务器默认给 expert 模型返回 f/g=true 但 j/k/l=null（禁用思考/搜索/文件）。
    // 构造后强改这些 final 字段即可在本地点亮；能否真正用取决于服务器是否二次校验。
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
            // gf5.c = 单会话最多可上传文件数；Unsafe 造的空壳 c=0 会被判定"不支持上传"
            try { GF5_C = gf5c.getDeclaredField("c"); GF5_C.setAccessible(true); } catch (Throwable ignored) {}
            try { GF5_D = gf5c.getDeclaredField("d"); GF5_D.setAccessible(true); } catch (Throwable ignored) {}
            try { GF5_E = gf5c.getDeclaredField("e"); GF5_E.setAccessible(true); } catch (Throwable ignored) {}
            try { GF5_F = gf5c.getDeclaredField("f"); GF5_F.setAccessible(true); } catch (Throwable ignored) {}
            int n = 0;
            for (Constructor<?> ctor : sf5.getDeclaredConstructors()) {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try { onSf5Built(param.thisObject); }
                        catch (Throwable t) { log("expert unlock err: " + t); }
                    }
                });
                n++;
            }
            log("hooked sf5 ctors x" + n + " (expert unlock)");
        } catch (Throwable t) { log("hookExpertUnlock failed: " + t); }
    }

    // 每个 sf5(模型配置)构造后回调：俘获可用模板 + 给 expert 回填 + 事后 back-fill
    private static void onSf5Built(Object o) throws Exception {
        // 俘获任意"已启用"模型的真实 feature 对象；file 只收 c>0 的真货
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
        // 模板可能晚于 expert 才构造出来，事后统一回填
        backfillExperts();
    }

    private static void applyExpert(Object o) throws Exception {
        EX_F.set(o, Boolean.TRUE);
        EX_G.set(o, Boolean.TRUE);
        if (EX_J.get(o) == null && tplThink != null) EX_J.set(o, tplThink);
        if (EX_K.get(o) == null && tplSearch != null) EX_K.set(o, tplSearch);
        // 文件：只有真 gf5(c>0)才有意义；空壳会导致"0 个文件"→ 被拦
        Object curL = EX_L.get(o);
        if (tplFile != null && (curL == null || gf5Count(curL) <= 0)) EX_L.set(o, tplFile);
        log("expert applied (j=" + (EX_J.get(o)!=null) + " k=" + (EX_K.get(o)!=null)
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

    private static Object fieldValue(Field f, Object owner) {
        if (f == null || owner == null) return null;
        try { return f.get(owner); } catch (Throwable ignored) { return null; }
    }

    private static String sf5Info(Object sf5) {
        if (sf5 == null) return "sf5=null";
        Object model = fieldValue(EX_A, sf5);
        Object file = fieldValue(EX_L, sf5);
        return "model=" + model + " file=" + gf5Info(file);
    }

    private static String gf5Info(Object gf5) {
        if (gf5 == null) return "null";
        Object d = fieldValue(GF5_D, gf5);
        Object f = fieldValue(GF5_F, gf5);
        Object e = fieldValue(GF5_E, gf5);
        return "{c=" + gf5Count(gf5)
                + " d=" + d
                + " f=" + f
                + " e=" + setSummary(e)
                + " cls=" + gf5.getClass().getName() + "}";
    }

    private static String setSummary(Object obj) {
        if (obj == null) return "null";
        if (!(obj instanceof java.util.Set)) return obj.getClass().getName() + ":" + String.valueOf(obj);
        java.util.Set set = (java.util.Set) obj;
        StringBuilder hit = new StringBuilder();
        for (int i = 0; i < IMAGE_EXTS.length; i++) {
            if (set.contains(IMAGE_EXTS[i])) {
                if (hit.length() > 0) hit.append(',');
                hit.append(IMAGE_EXTS[i]);
            }
        }
        StringBuilder sample = new StringBuilder();
        int n = 0;
        for (Object v : set) {
            if (n >= 25) break;
            if (sample.length() > 0) sample.append(',');
            sample.append(v);
            n++;
        }
        if (set.size() > n) sample.append(",...");
        return "Set(size=" + set.size() + " image=[" + hit + "] sample=[" + sample + "])";
    }

    private void hookExpertDiagnostics(final ClassLoader cl) {
        hookToastDiagnostics();
        hookResourceStringDiagnostics();
        hookCt0Diagnostics(cl);
        hookFileValidatorDiagnostics(cl);
        hookInputEventDiagnostics(cl);
    }

    private static boolean interestingText(String s) {
        if (s == null) return false;
        String t = s.toLowerCase(Locale.ROOT);
        return s.contains("不支持") || s.contains("不支援")
                || s.contains("上传") || s.contains("上傳")
                || s.contains("文件") || s.contains("檔案")
                || s.contains("图片") || s.contains("圖片")
                || t.contains("unsupported") || t.contains("file")
                || t.contains("upload") || t.contains("extension");
    }

    private static String shortStack() {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (int i = 0; i < st.length && kept < 9; i++) {
            String cn = st[i].getClassName();
            if (cn.equals(Thread.class.getName())) continue;
            if (cn.equals(Main.class.getName())) continue;
            if (cn.startsWith("de.robv.android.xposed.")) continue;
            if (cn.startsWith("android.widget.Toast")) continue;
            if (cn.startsWith("android.content.res.Resources")) continue;
            if (cn.startsWith("android.content.Context")) continue;
            if (sb.length() > 0) sb.append(" <- ");
            sb.append(cn).append('.').append(st[i].getMethodName()).append(':').append(st[i].getLineNumber());
            kept++;
        }
        return sb.toString();
    }

    private void hookToastDiagnostics() {
        try {
            int n = 0;
            for (Method m : android.widget.Toast.class.getDeclaredMethods()) {
                if (!"makeText".equals(m.getName())) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (param.args == null || param.args.length < 2) return;
                            String text = null;
                            Object raw = param.args[1];
                            if (raw instanceof CharSequence) {
                                text = raw.toString();
                            } else if (raw instanceof Integer && param.args[0] instanceof Context) {
                                text = ((Context) param.args[0]).getString(((Integer) raw).intValue());
                            }
                            if (interestingText(text)) {
                                log("toast diag text=" + text + " stack=" + shortStack());
                            }
                        } catch (Throwable t) { log("toast diag err: " + t); }
                    }
                });
                n++;
            }
            log("hooked Toast.makeText x" + n + " (expert diag)");
        } catch (Throwable t) { log("hook Toast diagnostics failed: " + t); }
    }

    private void hookResourceStringDiagnostics() {
        try {
            int n = 0;
            for (Method m : Resources.class.getDeclaredMethods()) {
                if (!"getString".equals(m.getName())) continue;
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                Object r = param.getResult();
                                String text = r instanceof CharSequence ? r.toString() : null;
                                if (interestingText(text)) {
                                    log("res.getString diag id=" + param.args[0] + " text=" + text
                                            + " stack=" + shortStack());
                                }
                            } catch (Throwable t) { log("res.getString diag err: " + t); }
                        }
                    });
                    n++;
                } catch (Throwable ignored) {}
            }
            for (Method m : Context.class.getDeclaredMethods()) {
                if (!"getString".equals(m.getName())) continue;
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                Object r = param.getResult();
                                String text = r instanceof CharSequence ? r.toString() : null;
                                if (interestingText(text)) {
                                    log("ctx.getString diag id=" + param.args[0] + " text=" + text
                                            + " stack=" + shortStack());
                                }
                            } catch (Throwable t) { log("ctx.getString diag err: " + t); }
                        }
                    });
                    n++;
                } catch (Throwable ignored) {}
            }
            log("hooked getString diagnostics x" + n + " (expert diag)");
        } catch (Throwable t) { log("hook getString diagnostics failed: " + t); }
    }

    private void hookCt0Diagnostics(ClassLoader cl) {
        try {
            Class<?> ct0 = cl.loadClass("ct0");
            final Field code = ct0.getDeclaredField("a");
            code.setAccessible(true);
            int n = 0;
            for (Method m : ct0.getDeclaredMethods()) {
                if (!"g".equals(m.getName()) || m.getParameterTypes().length != 1) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            Object c = code.get(param.thisObject);
                            Object r = param.getResult();
                            String text = r instanceof CharSequence ? r.toString() : String.valueOf(r);
                            boolean fileCode = (c instanceof Integer)
                                    && (((Integer) c).intValue() == 21 || ((Integer) c).intValue() == 22);
                            if (fileCode || interestingText(text)) {
                                log("ct0.g diag code=" + c + " text=" + text + " stack=" + shortStack());
                            }
                        } catch (Throwable t) { log("ct0.g diag err: " + t); }
                    }
                });
                n++;
            }
            log("hooked ct0.g x" + n + " (file toast diag)");
        } catch (Throwable t) { log("hook ct0 diagnostics failed: " + t); }
    }

    private void hookFileValidatorDiagnostics(ClassLoader cl) {
        try {
            Class<?> k31 = cl.loadClass("k31");
            int n = 0;
            for (Method m : k31.getDeclaredMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (!"x".equals(m.getName()) || pts.length != 3 || pts[0] != int.class
                        || pts[1] != String.class || !java.util.List.class.isAssignableFrom(pts[2])) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            List list = (List) param.args[2];
                            Object model = currentModelFromK31(param.thisObject);
                            log("k31.x diag enter max=" + param.args[0]
                                    + " tag=" + param.args[1]
                                    + " pickCount=" + (list == null ? -1 : list.size())
                                    + " current=" + sf5Info(model)
                                    + " stack=" + shortStack());
                        } catch (Throwable t) { log("k31.x diag err: " + t); }
                    }
                });
                n++;
            }
            log("hooked k31.x x" + n + " (file validator diag)");
        } catch (Throwable t) { log("hook k31 diagnostics failed: " + t); }
    }

    private void hookInputEventDiagnostics(ClassLoader cl) {
        try {
            Class<?> c01 = cl.loadClass("c01");
            int n = 0;
            for (Method m : c01.getDeclaredMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (!"b".equals(m.getName()) || pts.length != 1 || pts[0] != String.class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            String event = (String) param.args[0];
                            if ("model_unsupported".equals(event) || "upload_file".equals(event)
                                    || "show_keyboard".equals(event) || "user_click".equals(event)) {
                                log("c01.b diag event=" + event + " stack=" + shortStack());
                            }
                        } catch (Throwable t) { log("c01.b diag err: " + t); }
                    }
                });
                n++;
            }
            log("hooked c01.b x" + n + " (input event diag)");
        } catch (Throwable t) { log("hook c01 diagnostics failed: " + t); }
    }

    private static Object currentModelFromK31(Object k31) {
        if (k31 == null) return null;
        try {
            Field f = k31.getClass().getDeclaredField("e");
            f.setAccessible(true);
            Object state = f.get(k31);
            if (state == null) return null;
            Method u = state.getClass().getMethod("u");
            u.setAccessible(true);
            return u.invoke(state);
        } catch (Throwable ignored) {
            return null;
        }
    }

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

    // 诊断：r92.b(rs0, Long) 是网络请求对象进入 Ktor 客户端前的统一入口。
    // 这里记录 completion 请求里的 model_type、文件 id、搜索/思考状态等。
    private void installNetworkPayloadCapture(ClassLoader cl) {
        try {
            Class<?> r92 = cl.loadClass("r92");
            Class<?> rs0 = cl.loadClass("rs0");
            int n = 0;
            for (Method m : r92.getDeclaredMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (!m.getName().equals("b") || pts.length != 2 || !rs0.isAssignableFrom(pts[0])) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        // 无条件捕获活着的 r92 实例（transport 入口），供视觉探针复用
                        try { if (liveR92 == null) liveR92 = param.thisObject; } catch (Throwable ignored) {}
                        // 把发送点(fu0.y/uu0.y)刚捕获的 List<fp> 挂到本次 ew0 上，供延迟中继落盘图片。
                        try {
                            List fps = tlPendingFps.get();
                            if (fps != null) {
                                tlPendingFps.remove();
                                Object req = param.args != null && param.args.length > 0 ? param.args[0] : null;
                                if (req != null) ew0Fps.put(req, fps);
                            }
                        } catch (Throwable ignored) {}
                        try {
                            if (isSrvLog()) {
                                srvLog("[REQ] " + summarizeRequest(param.args[0])
                                        + " stack=" + shortStackForSrv());
                            }
                        } catch (Throwable t) { srvLog("[REQ] err " + t); }
                        try { maybeFireVisionProbe(param.thisObject, param.args[0]); } catch (Throwable ignored) {}
                    }

                    @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (isSrvLog()) {
                                Object r = param.getResult();
                                srvLog("[REQ_RET] " + simpleName(param.args[0])
                                        + " -> " + (r == null ? "null" : r.getClass().getName()));
                            }
                        } catch (Throwable t) { srvLog("[REQ_RET] err " + t); }
                        // ★正式功能：r92.b 在主线程只构建冷 Flow，真正 IO 在下游收集(IO线程)时才发生。
                        // 因此不在主线程阻塞，而是把返回的冷 Flow 换成一个"包装 Flow"，等下游在 IO 线程
                        // 收集它时，才跑视觉中继(建会话/发vision/收描述/删会话/改写 expert)，再转发真实 expert 响应。
                        try { maybeWrapExpertImageRelay(param); } catch (Throwable t) { extLog("[RELAY] wrap err " + t + "\n" + stackToString(t)); }
                    }
                });
                n++;
            }
            log("installed network payload capture on r92.b x" + n);
        } catch (Throwable t) { log("installNetworkPayloadCapture failed: " + t); }
    }

    // ── 专家图片中继：在线历史文本走服务器，图片附件保留本地版本 ─────────────
    // history_messages 的服务器版本不含图片，因为中继最终发给 expert 的是纯文本。
    // pw0 层先把本地 FILE fragment 合回服务器 kv，保证当前在线 UI 立即有图；fm8.b
    // 层再对序列化 rl8 做一次兜底，防整行 INSERT OR REPLACE 清掉本地附件。
    private void installExpertHistoryImagePreserver(final ClassLoader cl) {
        try {
            Class<?> fm8 = cl.loadClass("fm8");
            int ctorCount = 0;
            for (Constructor<?> ctor : fm8.getDeclaredConstructors()) {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        liveFm8 = param.thisObject;
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
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        liveFm8 = param.thisObject;
                        try { preserveImagesBeforeLocalWrite(cl, param.thisObject, param.args); }
                        catch (Throwable t) {
                            extLog("[HISTORY] fm8 preserve err: " + t + "\n" + stackToString(t));
                        }
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
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try { preserveImagesInHistoryResponse(cl, param.thisObject); }
                        catch (Throwable t) {
                            extLog("[HISTORY] pw0 preserve err: " + t + "\n" + stackToString(t));
                        }
                    }
                });
                n++;
            }
            log("installed expert history memory preserver pw0 ctor x" + n);
        } catch (Throwable t) {
            log("installExpertHistoryImagePreserver pw0 failed: " + t);
        }
    }

    // 在发送点 fu0.y / uu0.y 捕获这次发送的完整 List<fp>（含 signed_path），存 ThreadLocal，
    // 紧随其后的同线程 r92.b hook 会把它挂到构造出的 ew0 上。fu0：字段 i 就是 List<fp>；
    // uu0：字段 f 是 kv 消息，图片列表来自 kv.l()。
    private void installExpertImageFpCapture(final ClassLoader cl) {
        hookSendPointFps(cl, "fu0", true);
        hookSendPointFps(cl, "uu0", false);
    }

    private void hookSendPointFps(final ClassLoader cl, final String cls, final boolean directList) {
        try {
            Class<?> c = cl.loadClass(cls);
            final Method y = c.getDeclaredMethod("y", Object.class);
            XposedBridge.hookMethod(y, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!isExpertRelayEnabled()) return;
                    try {
                        List fps = null;
                        if (directList) {
                            Object v = fieldByName(param.thisObject, "i");   // fu0.i = List<fp>
                            if (v instanceof List) fps = (List) v;
                        } else {
                            Object kv = fieldByName(param.thisObject, "f");   // uu0.f = kv 消息
                            Object v = kv == null ? null : invokeNoArg(kv, "l"); // kv.l() = List<fp>
                            if (v instanceof List) fps = (List) v;
                        }
                        if (fps != null && countImageFpList(fps) > 0) {
                            tlPendingFps.set(fps);
                        }
                    } catch (Throwable t) { extLog("[RELAY] fp capture(" + cls + ") err: " + t); }
                }
            });
            log("installed send-point fp capture on " + cls + ".y");
        } catch (Throwable t) { log("hookSendPointFps " + cls + " failed: " + t); }
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
            // 本地行没有图片时（新中继消息 rows=0 是常态）回退到发送时落盘的 qs7 图片源。
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
            // 本地行无图（新中继消息 rows=0 常态）→ 回退发送时落盘的 qs7 图片源。
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

    private static boolean serializedHistoryContainsRelayMarker(ClassLoader cl, List rows) {
        if (rows == null) return false;
        for (Object row : rows) {
            String json = stringField(row, "l");
            if (!serializedMayContainRelayMarker(json)) continue;
            List fragments = decodeStaticFragments(cl, json);
            if (fragmentListContainsRelayMarker(fragments)) return true;
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

    // 中继成功、清空 d 之前把发送点捕获的 List<fp> 里的图片包成 qs7 fragment，编码成 JSON 落盘。
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

    // 强杀重开后读回落盘的图片 qs7 fragment（供 pw0/fm8 在本地 DB 为空时注入）。
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

    // 图片已回填，展示时把 REQUEST 文本 fragment 里 marker 及其后的自动描述截掉，只留用户原文。
    // 描述仍存在于服务器/本地的完整正文里（模型可见），仅影响渲染显示。
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

    // 剥掉 hookChatRequest 注入的 "<system>\n...\n</system>\n\n" 前缀，只留用户真正输入的原文。
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

    // 已在处理中的 expert 请求（弱引用集合，防同一对象被 hook 重复处理）
    private static final java.util.Set<Object> relaySeen =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Object, Boolean>());

    // ── ★正式功能：expert 模式带图 → 后台视觉描述中继（同步就地改写请求）────────
    // 拦到 model_type=expert 且带图片文件的请求时：后台建一个临时会话，用中性提示把图片
    // 发给 vision 模型拿到 100-200 字描述，删掉临时会话，再把本 expert 请求就地改成
    // 「原文 + 图片描述、无文件」的纯文本请求（沿用 expert 自己未消费的 PoW）。
    // 这样：不抢用户 PoW（vision 用独立新铸 PoW）、不污染用户会话（用临时会话且用完即删）。
    private boolean relayGateMatches(Object reqObj) {
        if (!isExpertRelayEnabled()) return false;
        if (reqObj == null || !"ew0".equals(simpleName(reqObj))) return false;
        if (!"expert".equals(String.valueOf(fieldByName(reqObj, "i")))) return false;
        Object files = fieldByName(reqObj, "d");
        return (files instanceof java.util.List) && !((java.util.List) files).isEmpty();
    }

    // 在 afterHookedMethod 里：命中 expert+图片时，把返回的冷 Flow 换成包装 Flow。
    private void maybeWrapExpertImageRelay(XC_MethodHook.MethodHookParam param) {
        final Object reqObj = param.args[0];
        if (!relayGateMatches(reqObj)) return;
        synchronized (relaySeen) {
            if (relaySeen.contains(reqObj)) return;
            relaySeen.add(reqObj);
        }
        final Object r92 = (param.thisObject != null) ? param.thisObject : liveR92;
        final Object origFlow = param.getResult();
        if (r92 == null || origFlow == null) { extLog("[RELAY] wrap skip: r92/origFlow null"); return; }
        Object wrapper = buildRelayFlow(r92, reqObj, origFlow);
        if (wrapper != null) {
            param.setResult(wrapper);
            extLog("[RELAY] 已装包装 Flow，等下游 IO 线程收集时跑中继");
        }
    }

    // 构建包装 Flow(实现同一 Flow 接口)：下游收集时才在收集线程(IO)跑视觉中继并改写 expert，
    // 然后重新用改写后的 expert 请求拿到真实 Flow，把其发射转发给下游 collector。
    private Object buildRelayFlow(final Object r92, final Object expertReq, final Object origFlow) {
        try {
            final ClassLoader cl = r92.getClass().getClassLoader();
            // 从冷 Flow 的接口里反推 collect(collector, cont) 方法 = kotlinx Flow
            Method cm = null;
            for (Class<?> itf : allInterfaces(origFlow.getClass())) {
                Method cand = null; int two = 0;
                for (Method m : itf.getDeclaredMethods()) {
                    if (m.getParameterTypes().length == 2) { cand = m; two++; }
                }
                if (two == 1 && cand.getParameterTypes()[1].isInterface()) { cm = cand; break; }
            }
            if (cm == null) { extLog("[RELAY] buildRelayFlow: Flow 接口未找到；不包装"); return null; }
            final Method collectM = cm;
            final Class<?> flowItf = cm.getDeclaringClass();
            collectM.setAccessible(true);
            final boolean[] done = {false};   // 中继只跑一次（Flow 可能被多次收集）

            InvocationHandler h = new InvocationHandler() {
                public Object invoke(Object proxy, Method m, Object[] a) throws Throwable {
                    if (isObjectMethod(m)) return objectMethod(proxy, m, a);
                    if (m.getParameterTypes().length == 2) {   // collect(collector, cont)
                        Object collector = a[0];
                        Object cont = a[1];
                        // 收集若发生在主线程则不阻塞，直接转发原冷 Flow（带图，服务端会拒，与未开启一致）
                        if (Looper.getMainLooper() != null
                                && Looper.getMainLooper().getThread() == Thread.currentThread()) {
                            extLog("[RELAY] 收集发生在主线程，跳过中继直接转发原 Flow");
                            return collectM.invoke(origFlow, collector, cont);
                        }
                        synchronized (done) {
                            if (!done[0]) {
                                done[0] = true;
                                try { runExpertImageRelay(r92, expertReq); }
                                catch (Throwable t) { extLog("[RELAY] runExpertImageRelay threw: " + t + "\n" + stackToString(t)); }
                            } else {
                                extLog("[RELAY] 重复收集，跳过中继，直接转发改写后 expert");
                            }
                        }
                        // 用(可能已改写为纯文本的) expert 请求拿真实 Flow，转发给下游
                        Object realFlow = origFlow;
                        try {
                            Method bM = null;
                            for (Method mm : r92.getClass().getDeclaredMethods()) {
                                if (mm.getName().equals("b") && mm.getParameterTypes().length == 2) { bM = mm; break; }
                            }
                            if (bM != null) { bM.setAccessible(true); realFlow = bM.invoke(r92, expertReq, null); }
                        } catch (Throwable t) { extLog("[RELAY] 重取 expert Flow 失败，转发原 Flow: " + t); realFlow = origFlow; }
                        if (realFlow == null) realFlow = origFlow;
                        return collectM.invoke(realFlow, collector, cont);
                    }
                    return null;
                }
            };
            return Proxy.newProxyInstance(cl, new Class<?>[]{ flowItf }, h);
        } catch (Throwable t) {
            extLog("[RELAY] buildRelayFlow failed: " + t + "\n" + stackToString(t));
            return null;
        }
    }

    // 单张图（或退化的整批）跑一遍 vision：铸独立 PoW→建临时会话→clone 请求限定 fileIds→收集描述→删会话。
    // 线程安全：每次调用用独立 session/请求/PoW，可并行；label 仅用于日志区分。
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
            setFieldByName(visionReq, "a", sid);                 // 临时 session
            setFieldByName(visionReq, "b", null);                // 无父消息
            setFieldByName(visionReq, "c", VISION_DESCRIBE_PROMPT); // ★中性提示，非越狱
            setFieldByName(visionReq, "i", "vision");            // model_type
            setFieldByName(visionReq, "e", Boolean.FALSE);       // 关搜索
            setFieldByName(visionReq, "f", Boolean.FALSE);       // 关深度思考（提速）
            setFieldByName(visionReq, "k", pow);                 // 独立新鲜 PoW
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

    // 多图并行：每张图一个线程各自开独立会话跑 vision，join 后按「图N：」顺序拼接。
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

        // 收集本次发送的文件 id（d 为 file-id 字符串列表）。多图时每张图并行开独立临时会话
        // 各自跑一遍 vision 描述，最后按「图N：」拼起来——比单会话串描多图快得多。
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

        // 5) 就地改写 expert 请求为纯文本（仅当拿到有效描述）
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

    // 驱动 i91.a 建临时会话，从返回 hb7 的原始 JSON(字段 j) 里抠出 chat_session.id
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

    // 从 /api/v0/chat_session/create 的响应 body 里提取 "chat_session":{"id":"<uuid>"...
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

    // 驱动 i91.c(jb1{chat_session_id}) 删除单个会话
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

    // ── 视觉描述自发探针 ──────────────────────────────────────────────
    // 目标验证：不经过对话机器(j71/yu0/z80)，仅复用 transport 入口 r92.b，
    // 自发一条 model_type=vision 的合成请求，收集返回的 Flow，把原始 SSE 写外部中继日志。
    // 前提：marker 文件存在、拦到的是带图 expert 请求、已捕获活着的 r92 实例。
    private void maybeFireVisionProbe(final Object r92inst, final Object reqObj) {
        // ── 铸币隔离测试（最高优先级，安全）──────────────────────────────
        // 只铸 PoW 写日志，绝不发送/改动任何请求。用于在建大流水线前确认 driveSuspend 可用。
        if (new File(MINT_TEST_FILE).exists() && !mintTestFired) {
            mintTestFired = true;
            final Object r92m = (r92inst != null) ? r92inst : liveR92;
            new Thread(new Runnable() {
                public void run() {
                    try {
                        if (liveQ71 == null) { extLog("[MINT] liveQ71 null; 先随便发一条普通消息以捕获 q71"); return; }
                        ClassLoader cl = liveQ71.getClass().getClassLoader();
                        long t0 = System.currentTimeMillis();
                        Object pow = mintCompletionPow(cl, liveQ71);
                        long dt = System.currentTimeMillis() - t0;
                        if (pow instanceof String && ((String) pow).length() > 0) {
                            extLog("[MINT] OK len=" + ((String) pow).length() + " dt=" + dt + "ms head=" + ((String) pow).substring(0, Math.min(24, ((String) pow).length())));
                        } else {
                            extLog("[MINT] returned non-string: " + pow);
                        }
                        // ── 建会话空跑：驱动 i91.a(create)，把结果整棵 dump，实证 session_id 路径 ──
                        if (new File(SESS_TEST_FILE).exists() && r92m != null) {
                            try {
                                java.lang.reflect.Field bf = r92m.getClass().getDeclaredField("b"); // i91
                                bf.setAccessible(true);
                                Object i91inst = bf.get(r92m);
                                Method createM = null;
                                for (Method m : i91inst.getClass().getDeclaredMethods()) {
                                    if (m.getName().equals("a") && m.getParameterTypes().length == 1) { createM = m; break; }
                                }
                                if (createM == null) { extLog("[SESS] i91.a(create) not found"); }
                                else {
                                    long s0 = System.currentTimeMillis();
                                    Object res = driveSuspend(cl, createM, i91inst, new Object[0]);
                                    extLog("[SESS] create dt=" + (System.currentTimeMillis() - s0) + "ms result=" + deepDump(res, 5));
                                }
                            } catch (Throwable t) { extLog("[SESS] create failed: " + t); }
                        }
                    } catch (Throwable t) { extLog("[MINT] failed: " + t); }
                }
            }).start();
            return;   // 铸币测试模式下不做任何视觉探针发送
        }
        // 开关：只认专用 marker 文件。⚠️ 当前探针会复用用户 session_id 且克隆一次性 PoW，
        // 二者都会破坏用户真实发送（抢 PoW 导致「消息未能发出」、污染会话导致「变识图模式」）。
        // 因此绝不能用 EXPERT_UNLOCK/isSrvLog 这类日常必开的开关做 gate——只在放了 marker 的
        // 显式测试场景下才触发，避免影响正常使用。真正的功能实现需换独立 session + 独立 PoW。
        boolean enabled = new File(VISION_PROBE_FILE).exists();
        if (!enabled) return;
        // 诊断：开关开着时，每条 completion 都记一行（含 model_type/文件数），
        // 这样即使不满足触发条件也能在日志里看到到底拦到了什么。
        String mt = (reqObj == null) ? "null" : String.valueOf(fieldByName(reqObj, "i"));
        Object files = (reqObj == null) ? null : fieldByName(reqObj, "d");
        int nf = (files instanceof java.util.List) ? ((java.util.List) files).size() : -1;
        extLog("[VP] seen req cls=" + simpleName(reqObj) + " model_type=" + mt + " files=" + nf
                + " fired=" + visionProbeFired);
        if (visionProbeFired) return;
        if (reqObj == null || !"ew0".equals(simpleName(reqObj))) return;
        if (!"expert".equals(mt)) { extLog("[VP] skip: model_type!=expert (" + mt + ")"); return; }
        if (!(files instanceof java.util.ArrayList) || ((java.util.ArrayList) files).isEmpty()) {
            extLog("[VP] skip: expert req has no image file (files=" + nf + ")");
            return;
        }
        visionProbeFired = true;
        final Object r92 = (r92inst != null) ? r92inst : liveR92;
        final Object expertReq = reqObj;
        extLog("[VP] trigger: expert req with " + nf + " file(s), session="
                + String.valueOf(fieldByName(reqObj, "a")));
        new Thread(new Runnable() {
            public void run() {
                try { runVisionProbe(r92, expertReq); }
                catch (Throwable t) { extLog("[VP] runVisionProbe threw: " + t); }
            }
        }).start();
    }

    private void runVisionProbe(Object r92, Object expertReq) throws Throwable {
        if (r92 == null) { extLog("[VP] no live r92 instance; abort"); return; }
        ClassLoader cl = r92.getClass().getClassLoader();
        // 不猜构造器顺序（本 build 的 ew0 构造器首参是 int，与 jadx 不符）：
        // 用 Unsafe 分配空壳 ew0，把 expert 请求的所有字段浅拷过去，只改 i=vision、e/f=false。
        Object visionReq = shallowCloneEw0(expertReq);
        if (visionReq == null) { extLog("[VP] clone ew0 failed; abort"); return; }
        setFieldByName(visionReq, "i", "vision");     // model_type
        setFieldByName(visionReq, "e", Boolean.FALSE); // search off
        setFieldByName(visionReq, "f", Boolean.FALSE); // think off

        // PoW(k)是一次性的：克隆 expert 的 k 会被服务端拒(40301 INVALID_POW_RESPONSE)。
        // 用 q71.j() 从池里铸一份新鲜的 completion PoW 装到 vision 请求上。
        if (liveQ71 != null) {
            try {
                Object pow = mintCompletionPow(cl, liveQ71);
                if (pow instanceof String && ((String) pow).length() > 0) {
                    setFieldByName(visionReq, "k", pow);
                    extLog("[VP] fresh PoW minted len=" + ((String) pow).length());
                } else {
                    extLog("[VP] mint pow returned non-string: " + pow + " ; keeping cloned k (may 40301)");
                }
            } catch (Throwable t) {
                extLog("[VP] mint pow failed: " + t + " ; keeping cloned k (may 40301)");
            }
        } else {
            extLog("[VP] liveQ71 null; reusing cloned PoW (will likely 40301)");
        }
        extLog("[VP] built vision ew0: " + summarizeRequest(visionReq));

        // 调 r92.b(visionReq, null) -> b41 (kotlin Flow)
        Method bM = null;
        for (Method m : r92.getClass().getDeclaredMethods()) {
            if (m.getName().equals("b") && m.getParameterTypes().length == 2) { bM = m; break; }
        }
        if (bM == null) { extLog("[VP] r92.b not found on instance"); return; }
        bM.setAccessible(true);
        Object flow = bM.invoke(r92, visionReq, null);
        extLog("[VP] r92.b returned: " + (flow == null ? "null" : flow.getClass().getName()));
        if (flow == null) return;
        collectFlow(cl, flow);
    }

    // 捕获一个活着的 q71（completion PoW 管理器）实例：hook 它的 j/b 方法，
    // app 每次发送都会调 j() 取 PoW，首次即可捕获后复用。
    private void installPowManagerCapture(ClassLoader cl) {
        try {
            Class<?> q71 = cl.loadClass("q71");
            int n = 0;
            for (Method m : q71.getDeclaredMethods()) {
                String nm = m.getName();
                if ((nm.equals("j") || nm.equals("b")) && m.getParameterTypes().length == 1) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            try { if (liveQ71 == null) { liveQ71 = param.thisObject; extLog("[VP] captured liveQ71"); } }
                            catch (Throwable ignored) {}
                        }
                    });
                    n++;
                }
            }
            log("installed pow manager capture on q71 x" + n);
        } catch (Throwable t) { log("installPowManagerCapture failed: " + t); }
    }

    // 调 q71.j(cont) 铸一份新鲜的 completion PoW，返回 base64 字符串（b36.a）。
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

    // 通用：从普通 Java 线程同步驱动一个 kotlin suspend 函数 m(preArgs..., Continuation)。
    // 关键：不能自己 Proxy 续体——本 build 的 suspend 参数是 vz1(抽象类，非接口)，无法 Proxy。
    // 改用 app 自带的 runBlocking(t82.K(n02 ctx, mb3 block))：block 是 Function2(mb3，接口，可 Proxy)，
    // runBlocking 会给 block.invoke(scope, cont) 传入一个**真实的续体 cont**，我们把它转交给 m。
    // 因为 m(cont) 用的是 runBlocking 自己的续体，m 完成时直接 resume runBlocking → K 返回 m 的结果。
    private Object driveSuspend(ClassLoader cl, final Method m, final Object target, final Object[] preArgs) throws Throwable {
        Class<?> t82 = cl.loadClass("t82");
        Class<?> n02 = cl.loadClass("n02");
        Class<?> mb3 = cl.loadClass("mb3");
        Method K = null;
        for (Method mm : t82.getDeclaredMethods()) {
            Class<?>[] p = mm.getParameterTypes();
            if (p.length == 2 && p[0] == n02 && p[1] == mb3) { K = mm; break; }
        }
        if (K == null) { extLog("[VP] runBlocking(t82.K) not found"); return null; }
        K.setAccessible(true);
        m.setAccessible(true);
        final Object ctx = emptyContextProxy(cl, n02);
        InvocationHandler blockH = new InvocationHandler() {
            public Object invoke(Object proxy, Method mm, Object[] a) throws Throwable {
                if (isObjectMethod(mm)) return objectMethod(proxy, mm, a);
                // Function2.r(scope, continuation)：末参是 runBlocking 提供的真实续体
                Object cont = (a != null && a.length > 0) ? a[a.length - 1] : null;
                Object[] args = new Object[preArgs.length + 1];
                System.arraycopy(preArgs, 0, args, 0, preArgs.length);
                args[preArgs.length] = cont;
                try {
                    return m.invoke(target, args);   // 返回值或 COROUTINE_SUSPENDED，均由 runBlocking 接管
                } catch (java.lang.reflect.InvocationTargetException ite) {
                    throw (ite.getCause() != null ? ite.getCause() : ite);
                }
            }
        };
        Object block = Proxy.newProxyInstance(cl, new Class<?>[]{mb3}, blockH);
        return K.invoke(null, ctx, block);
    }

    // 用 Unsafe 分配一个空 ew0，把 src 的所有非静态实例字段浅拷过去（含 final）。
    // 与构造器参数顺序无关，最稳。
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

    // 从 Java 收集一个冷 Kotlin Flow。R8 把 kotlin 协程库类名也混淆了，不能按名字 loadClass，
    // 改为从 Flow 接口唯一的 collect(FlowCollector, Continuation) 方法签名里“反推”出真实类型，
    // 再用 Proxy 造 collector / 根 Continuation / 空 CoroutineContext。emit 把事件写日志。
    // 返回累积的**描述正文**（把流里 content 的 APPEND 增量拼起来）；失败/空返回 null 或 ""。
    private String collectFlow(ClassLoader cl, Object flow) {
        final StringBuilder descBuf = new StringBuilder();
        try {
            // 1) 在 flow 实现的接口里找“恰好只有一个 2 参抽象方法”的那个 = kotlinx Flow
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
            // 2) Continuation.getContext() 的返回类型 = CoroutineContext（0 参、返回接口的方法）
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
                    // resumeWith(Object): 流结束
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
                        return null;                                // Unit 值会被丢弃，返回 null 即可
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

    // 从单个 flow 事件里抽取 content 增量文本。观察到的描述流形态：
    //   首个：xs0{a=lv7{a=null, b={"p":"response/fragments/-1/content","o":"APPEND","v":"无法"}}}
    //   后续：xs0{a=lv7{a=null, b={"v":"协助"}}}  （对同一 content 路径的连续增量）
    // 非正文事件(v 是对象/数组、SET status、title、update_session 等)一律返回 null。
    private String extractContentDeltaFromEvent(Object value) {
        try {
            if (!"xs0".equals(simpleName(value))) return null;
            Object lv7 = fieldByName(value, "a");
            if (lv7 == null) return null;
            Object ename = fieldByName(lv7, "a");   // 事件名，正文增量时为 null
            if (ename != null) return null;         // ready/title/close/update_session 等具名事件跳过
            Object bj = fieldByName(lv7, "b");
            if (!(bj instanceof String)) return null;
            return extractContentDelta((String) bj);
        } catch (Throwable t) { return null; }
    }

    // 解析形如 {"v":"文字"} 或 {"p":".../content","o":"APPEND","v":"文字"} 的 JSON，取出字符串 v。
    // v 非字符串（{"v":{...}} / {"v":[...]}）或明显非正文路径(status)时返回 null。
    private static String extractContentDelta(String json) {
        if (json == null) return null;
        int vi = json.indexOf("\"v\":\"");        // 只认字符串型 v
        if (vi < 0) return null;
        boolean bareDelta = json.startsWith("{\"v\":\"");                 // {"v":"xx"}
        boolean appendContent = json.contains("content") && json.contains("APPEND");
        if (!bareDelta && !appendContent) return null;                    // 过滤 status/SET 等
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

    // 用 Proxy 模拟 EmptyCoroutineContext（无 dispatcher/interceptor，续体在 resume 线程内联执行）。
    private Object emptyContextProxy(ClassLoader cl, final Class<?> ccCls) {
        InvocationHandler h = new InvocationHandler() {
            public Object invoke(Object proxy, Method m, Object[] a) {
                if (isObjectMethod(m)) return objectMethod(proxy, m, a);
                int p = m.getParameterTypes().length;
                if (p == 2) {
                    // fold：空 context 原样返回 initial（不调用 operation）。
                    // 本 build 参数顺序是 (operation, initial)，故不能死返 a[0]——
                    // operation 是 Function2(mb3)，会被强转 Number 崩。返回“非函数”的那个参数。
                    boolean a0fn = isFunction2(a[0]);
                    boolean a1fn = isFunction2(a[1]);
                    if (a0fn && !a1fn) return a[1];
                    if (a1fn && !a0fn) return a[0];
                    return a[1];
                }
                if (p == 1) {
                    Class<?> rt = m.getReturnType();
                    // 只有“恰好返回 CoroutineContext(ccCls)”才是 plus/minusKey。
                    // get(key) 返回的是 Element（ccCls 的子接口），不能用 isAssignableFrom 混进来，
                    // 否则会把 get 当 plus 返回 proxy 自身，被强转 Element 崩。
                    if (rt == ccCls) {                       // plus(ctx) / minusKey(key)
                        Object arg = a[0];
                        return (arg != null && ccCls.isInstance(arg)) ? arg : proxy; // empty.plus(x)=x; minusKey=self
                    }
                    return null;                             // get(key) -> Element? -> 空 context 恒为 null
                }
                return null;
            }
        };
        return Proxy.newProxyInstance(cl, new Class<?>[]{ccCls}, h);
    }

    private static boolean isObjectMethod(Method m) {
        return m.getDeclaringClass() == Object.class;
    }

    // 判断 o 是否是 kotlin Function2（fold 的 operation）：实现的某接口有 2 参抽象方法。
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

    // 收集一个类实现的所有接口（含父类、父接口）
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

    // 记录一个 Flow 事件：SSE 原始事件 lv7{a=event,b=data}，或已解码对象；否则通用摘要。
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

    // 递归展开一个 app 对象的所有非静态字段值（depth 层），用于把 vs0/ws0/hb7 等
    // completion 事件的真实内容/错误打出来。深度到 0 或基本类型即停。
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

    // 诊断：fp/ul6 是文件服务返回的文件状态/解析结果列表。
    // 注意 AppChatFile 目前只有元数据字段；如果服务端真的下发 OCR 文本，这里会暴露出新增字段或异常形态。
    private void installFileParseCapture(ClassLoader cl) {
        try {
            Class<?> fp = cl.loadClass("fp");
            int n = 0;
            for (Constructor<?> ctor : fp.getDeclaredConstructors()) {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (isSrvLog()) srvLog("[FILE_FP] " + summarizeFp(param.thisObject));
                        } catch (Throwable t) { srvLog("[FILE_FP] err " + t); }
                    }
                });
                n++;
            }
            log("installed file parse capture on fp ctors x" + n);
        } catch (Throwable t) { log("installFileParseCapture failed: " + t); }

        try {
            Class<?> ul6 = cl.loadClass("ul6");
            int n = 0;
            for (Constructor<?> ctor : ul6.getDeclaredConstructors()) {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (isSrvLog()) srvLog("[FILE_LIST] " + summarizeUl6(param.thisObject));
                        } catch (Throwable t) { srvLog("[FILE_LIST] err " + t); }
                    }
                });
                n++;
            }
            log("installed file list capture on ul6 ctors x" + n);
        } catch (Throwable t) { log("installFileListCapture failed: " + t); }
    }

    // 诊断：t40 是网络响应 biz_data 解码点。只记录文件相关响应，避免刷屏。
    private void installNetworkDecodeCapture(ClassLoader cl) {
        try {
            Class<?> t40 = cl.loadClass("t40");
            int n = 0;
            for (Method m : t40.getDeclaredMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (!"y".equals(m.getName()) || pts.length != 1) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            if (!isSrvLog()) return;
                            String s = summarizeNetworkResult(param.getResult());
                            if (s != null) srvLog("[RESP] " + s);
                        } catch (Throwable t) { srvLog("[RESP] err " + t); }
                    }
                });
                n++;
            }
            log("installed network decode capture on t40.y x" + n);
        } catch (Throwable t) { log("installNetworkDecodeCapture failed: " + t); }
    }

    private static String summarizeRequest(Object req) {
        if (req == null) return "null";
        String n = simpleName(req);
        if ("ew0".equals(n)) {
            return "ew0(completion){session=" + logValue(fieldByName(req, "a"))
                    + ", parent=" + logValue(fieldByName(req, "b"))
                    + ", prompt=" + logValue(fieldByName(req, "c"))
                    + ", files=" + logValue(fieldByName(req, "d"))
                    + ", search=" + logValue(fieldByName(req, "e"))
                    + ", think=" + logValue(fieldByName(req, "f"))
                    + ", extraG=" + logValue(fieldByName(req, "g"))
                    + ", h=" + logValue(fieldByName(req, "h"))
                    + ", model_type=" + logValue(fieldByName(req, "i"))
                    + ", action=" + logValue(fieldByName(req, "j"))
                    + ", k=" + logValue(fieldByName(req, "k")) + "}";
        }
        if ("bv0".equals(n)) {
            return "bv0(retry){session=" + logValue(fieldByName(req, "a"))
                    + ", msgId=" + logValue(fieldByName(req, "b"))
                    + ", prompt=" + logValue(fieldByName(req, "c"))
                    + ", files=" + logValue(fieldByName(req, "d"))
                    + ", search=" + logValue(fieldByName(req, "e"))
                    + ", think=" + logValue(fieldByName(req, "f"))
                    + ", extraG=" + logValue(fieldByName(req, "g"))
                    + ", action=" + logValue(fieldByName(req, "h"))
                    + ", i=" + logValue(fieldByName(req, "i")) + "}";
        }
        if ("xu0".equals(n)) {
            return "xu0(stop/update){session=" + logValue(fieldByName(req, "a"))
                    + ", msgId=" + logValue(fieldByName(req, "b"))
                    + ", c=" + logValue(fieldByName(req, "c"))
                    + ", d=" + logValue(fieldByName(req, "d")) + "}";
        }
        return n + " " + summarizeFields(req, 12);
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

    private static String summarizeFields(Object obj, int maxFields) {
        if (obj == null) return "null";
        StringBuilder sb = new StringBuilder("{");
        int n = 0;
        for (Field f : obj.getClass().getDeclaredFields()) {
            try {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                if (n > 0) sb.append(", ");
                sb.append(f.getName()).append('=').append(logValue(f.get(obj)));
                if (++n >= maxFields) break;
            } catch (Throwable ignored) {}
        }
        return sb.append('}').toString();
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

    private static String shortStackForSrv() {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (int i = 0; i < st.length && kept < 12; i++) {
            String cn = st[i].getClassName();
            if (cn.equals(Thread.class.getName())) continue;
            if (cn.equals(Main.class.getName())) continue;
            if (cn.startsWith("de.robv.android.xposed.")) continue;
            if (cn.startsWith("java.lang.reflect.")) continue;
            if (cn.startsWith("fpa.")) continue;
            if (sb.length() > 0) sb.append(" <- ");
            sb.append(cn).append('.').append(st[i].getMethodName()).append(':').append(st[i].getLineNumber());
            kept++;
        }
        return sb.toString();
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
            Uri uri = Uri.parse("content://com.dsmod.inject.status");
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
            Class<?> a = cl.loadClass("com.dsmod.inject.SettingsActivity");
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
                        "本模块（DeekseepX）通过 Xposed 框架修改 DeepSeek 的运行行为，使用前请知悉：\n\n"
                        + "• 风险自担：因使用本模块产生的一切后果，均由你本人承担。\n"
                        + "• 封号风险：修改客户端行为可能违反 DeepSeek 用户协议，账号存在被限制或封禁的风险。\n"
                        + "• 数据风险：注入过程可能影响消息、历史记录等数据，请自行备份。\n"
                        + "• 恶意用途：本模块仅供个人学习与研究，切勿用于任何违法或恶意行为。\n\n"
                        + "点击“同意”表示你已阅读并接受上述风险；点击“拒绝”将退出 DeepSeek。";
                    new android.app.AlertDialog.Builder(act)
                        .setTitle("DeekseepX 免责声明")
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

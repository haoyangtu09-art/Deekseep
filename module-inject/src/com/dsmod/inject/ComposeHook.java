package com.dsmod.inject;

import android.app.Activity;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * 尝试把 "Deekseep" 作为真实 Compose 设置项注入 DeepSeek 设置页。
 *
 * ── 历史（已知会崩溃的方案） ────────────────────────────────────
 *   Strategy A – hook u25.i() beforeHook → 在整个设置列表渲染前调用 u25.b()
 *                崩溃原因：u25.i() 尚未调用自己的 i0()(startRestartGroup)，
 *                我们注入的 restart-group 被写到 slot table 的错误位置 →
 *                gap buffer 错位 → IllegalStateException / AIOOBE。
 *   Strategy B – hook bs1.r() case 25 beforeHook → 在 "关于" 区段头前插入
 *                崩溃原因：同样在别人的 group 内部的不确定位置调用 u25.b()，
 *                并且 u25.b 内部的 ViewModel 解析分支（h0(-1614864554) →
 *                xv4.a(LocalViewModelStoreOwner)）可能中途 return，
 *                导致 group 不平衡。
 *   两者都被 **禁用**（代码保留，仅注释掉 injectRow 调用）。
 *
 * ── 当前方案 Strategy C（安全）────────────────────────────────
 *   直接 hook u25.b() 本身。当设置列表为某个真实行调用 u25.b() 时，
 *   Composer 已经处在列表内部一个合法的子槽位。我们在 beforeHook 里：
 *     1. 用一个重入标志确保我们注入的那次 u25.b 调用不会再次触发注入；
 *     2. 用 bu1.g0(STABLE_KEY) / bu1.q(false) 把额外的一行包进一个
 *        可协调的 replaceable group（startGroup/endGroup 的混淆等价物），
 *        STABLE_KEY 固定为 0xDEE5EE，Compose 会把它当成一个独立、
 *        可 reconcile 的 group，不会破坏外层槽位；
 *     3. 在 start/end 之间用**传入的同一个 composer** 和**传入的真实 rd5**
 *        再调用一次 u25.b("Deekseep", ...)，因为复用真实 rd5，
 *        u25.b 内部会走 "参数已提供" 分支，跳过 ViewModel 解析，
 *        不会中途 return。
 *   为避免每一行都插一份，只在第一个匹配到的目标行（title 命中 TRIGGER_TITLE，
 *   或退化为每次 composition 的第一行）后注入一次，用 per-frame 标志控制。
 *
 * bu1(ComposerImpl) 混淆映射（已从 bu1.java 确认）：
 *   i0(int) = startRestartGroup      g0(int) = startGroup(可协调)
 *   h0(int) = startReplaceableGroup  q(false)= endGroup / endReplaceableGroup
 *   a0()    = skipToGroupEnd         v()     = currentRecomposeScope
 *   X(int,boolean) = shouldExecute body guard（新插入的 group 恒执行）
 *
 * 所有失败都有详细日志，通过 /data/data/com.deepseek.chat/files/dsprobe.log 查看。
 */
public final class ComposeHook {

    @FunctionalInterface
    interface Logger { void log(String msg); }

    @FunctionalInterface
    interface ActGetter { Activity get(); }

    /** 我们注入的 replaceable group 的稳定 key —— 固定，Compose 才能 reconcile。 */
    private static final int STABLE_KEY = 0xDEE5EE;

    /** 注入行的标题。 */
    private static final String INJECT_TITLE = "Deekseep";

    /**
     * 触发注入的目标行标题。若为 null，则对每个 composition 帧里第一个
     * 被渲染的真实行注入一次。设为某个已知稳定的行标题可让位置更可预期。
     */
    private static final String TRIGGER_TITLE = null;

    /** 防止我们注入的那次 u25.b 调用递归再触发注入。 */
    private static final ThreadLocal<Boolean> INJECTING =
            new ThreadLocal<Boolean>() {
                @Override protected Boolean initialValue() { return Boolean.FALSE; }
            };

    /**
     * 每个 composer 实例一帧只注入一次。用 composer 的身份哈希 + 上次注入时间
     * 粗略去重；更重要的是 INJECTING 保证不递归。
     */
    private static Object lastComposer = null;

    // ── 安装所有 hook ─────────────────────────────────────────────

    static void install(XposedModule module, ClassLoader cl, Logger logger, ActGetter acts) {
        // A / B 已知会崩溃，保留但不启用（见类注释）。
        strategyA(cl, logger, acts);
        strategyB(cl, logger, acts);
        // C 是当前安全方案。
        strategyC(module, cl, logger, acts);
        probeU25B(cl, logger);
    }

    // ── Strategy A: hook u25.i() beforeHook（已禁用注入）──────────

    private static void strategyA(ClassLoader cl, Logger logger, ActGetter acts) {
        try {
            Class<?> u25 = cl.loadClass("u25");
            for (Method m : u25.getDeclaredMethods()) {
                if (!"i".equals(m.getName())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length < 13) continue;
                // NOTE: 注入被禁用。若要恢复实验，取消下面 hook 注释。
                // final int compIdx = 11;
                // final int changedIdx = 12;
                // XposedBridge.hookMethod(m, new XC_MethodHook() {
                //     @Override protected void beforeHookedMethod(MethodHookParam p) {
                //         try {
                //             Object composer = p.args[compIdx];
                //             if (composer == null) return;
                //             int changed = (p.args[changedIdx] instanceof Integer)
                //                     ? (int) p.args[changedIdx] : 0;
                //             injectRow(cl, composer, changed, logger, acts);
                //         } catch (Throwable t) {
                //             logger.log("strategyA inject err: " + t);
                //         }
                //     }
                // });
                logger.log("ComposeHook strategyA: u25.i found paramCount="
                        + pts.length + " (injection DISABLED - known crasher)");
                return;
            }
            logger.log("ComposeHook strategyA: u25.i not found");
        } catch (Throwable t) {
            logger.log("ComposeHook strategyA failed: " + t);
        }
    }

    // ── Strategy B: hook bs1.r() case 25 beforeHook（已禁用注入）──

    private static void strategyB(ClassLoader cl, Logger logger, ActGetter acts) {
        try {
            Class<?> bs1 = cl.loadClass("bs1");
            Field caseField = null;
            try {
                caseField = bs1.getDeclaredField("a");
                caseField.setAccessible(true);
            } catch (Throwable ignored) {}
            // NOTE: 注入被禁用。仅记录 bs1.r 是否可定位，便于将来诊断。
            int found = 0;
            for (Method m : bs1.getDeclaredMethods()) {
                if (!"r".equals(m.getName()) || m.getParameterCount() != 2) continue;
                found++;
            }
            logger.log("ComposeHook strategyB: bs1.r x" + found
                    + ", caseField=" + (caseField != null ? caseField.getName() : "null")
                    + " (injection DISABLED - known crasher)");
        } catch (Throwable t) {
            logger.log("ComposeHook strategyB failed: " + t);
        }
    }

    // ── Strategy C: hook u25.b() 本身，安全地包一层 replaceable group ─

    private static void strategyC(XposedModule module, ClassLoader cl, Logger logger, ActGetter acts) {
        try {
            final Method bMethod = findU25B(cl);
            if (bMethod == null) {
                logger.log("ComposeHook strategyC: u25.b not found, cannot hook");
                return;
            }
            // 预取 bu1 的 group 原语。
            final Class<?> bu1 = cl.loadClass("bu1");
            final Method g0 = bu1.getDeclaredMethod("g0", int.class);       // startGroup
            final Method qEnd = bu1.getDeclaredMethod("q", boolean.class);  // endGroup
            g0.setAccessible(true);
            qEnd.setAccessible(true);

            final Object rd5Proxy = buildRd5Proxy(cl, logger, acts); // 仅在传入 rd5 为 null 时兜底

            module.hook(bMethod).intercept(new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    // 递归保护：我们自己注入的那次调用不再触发。
                    if (Boolean.TRUE.equals(INJECTING.get())) return chain.proceed();
                    try {
                        List<Object> args = chain.getArgs();
                        // u25.b(String title, String subtitle, rd5, qt6, rm5, bu1, int)
                        if (args.size() != 7) return chain.proceed();
                        Object a0 = args.get(0);
                        String title = (a0 instanceof String) ? (String) a0 : null;
                        Object composer = args.get(5);
                        if (composer == null) return chain.proceed();

                        // 目标行判定：TRIGGER_TITLE==null 时对每帧第一行注入一次。
                        boolean trigger;
                        if (TRIGGER_TITLE != null) {
                            trigger = TRIGGER_TITLE.equals(title);
                        } else {
                            // 每个 composer 实例只注入一次（新一帧 composer 通常复用，
                            // 但我们用引用变化 + INJECTING 组合已足够避免刷屏）。
                            trigger = (composer != lastComposer);
                        }
                        if (!trigger) return chain.proceed();
                        lastComposer = composer;

                        // 别把 Deekseep 行自己当触发源。
                        if (INJECT_TITLE.equals(title)) return chain.proceed();

                        Object realRd5 = args.get(2);   // 真实 ViewModel/回调持有者
                        Object rd5ToUse = (realRd5 != null) ? realRd5 : rd5Proxy;
                        Object qt6 = args.get(3);       // NavController，复用真实值
                        Object rm5 = args.get(4);       // Modifier，复用真实值

                        logger.log("ComposeHook strategyC: trigger row title='" + title
                                + "' realRd5=" + (realRd5 != null)
                                + " -> injecting '" + INJECT_TITLE + "'");

                        INJECTING.set(Boolean.TRUE);
                        boolean opened = false;
                        try {
                            // start replaceable/reconcilable group with STABLE key
                            g0.invoke(composer, STABLE_KEY);
                            opened = true;
                            // 新插入的 group 恒执行；changed=0 让 X() 走"新插入"分支。
                            bMethod.invoke(null,
                                    INJECT_TITLE, // title
                                    null,          // subtitle
                                    rd5ToUse,      // rd5（复用真实的，跳过 ViewModel 解析）
                                    qt6,           // qt6 NavController
                                    rm5,           // rm5 Modifier
                                    composer,      // bu1 Composer
                                    0);            // changed
                            logger.log("ComposeHook strategyC: u25.b(Deekseep) rendered ok");
                        } catch (Throwable inner) {
                            logger.log("ComposeHook strategyC inner render err: " + inner);
                        } finally {
                            if (opened) {
                                try {
                                    qEnd.invoke(composer, Boolean.FALSE); // endGroup
                                } catch (Throwable endErr) {
                                    logger.log("ComposeHook strategyC endGroup err: " + endErr);
                                }
                            }
                            INJECTING.set(Boolean.FALSE);
                        }
                    } catch (Throwable t) {
                        INJECTING.set(Boolean.FALSE);
                        logger.log("ComposeHook strategyC beforeHook err: " + t);
                    }
                    return chain.proceed();
                }
            });
            logger.log("ComposeHook strategyC: hooked u25.b (safe replaceable-group inject),"
                    + " key=0x" + Integer.toHexString(STABLE_KEY)
                    + ", trigger=" + (TRIGGER_TITLE == null ? "firstRowPerComposer" : TRIGGER_TITLE));
        } catch (Throwable t) {
            logger.log("ComposeHook strategyC failed: " + t);
        }
    }

    // ── 旧的直接注入（保留供 A/B 参考，当前不再被调用）───────────

    @SuppressWarnings("unused")
    private static void injectRow(ClassLoader cl, Object composer, int changed,
                                   Logger logger, ActGetter acts) throws Throwable {
        Method bMethod = findU25B(cl);
        if (bMethod == null) {
            logger.log("ComposeHook: u25.b not found, skip inject");
            return;
        }
        Object rd5 = buildRd5Proxy(cl, logger, acts);
        bMethod.invoke(null,
                INJECT_TITLE, null, rd5, null, null, composer, changed);
        logger.log("ComposeHook: u25.b(Deekseep) called ok");
    }

    // ── 查找 u25.b() ─────────────────────────────────────────────

    private static Method cachedU25B = null;

    private static Method findU25B(ClassLoader cl) {
        if (cachedU25B != null) return cachedU25B;
        try {
            Class<?> u25 = cl.loadClass("u25");
            for (Method m : u25.getDeclaredMethods()) {
                if (!"b".equals(m.getName())) continue;
                Class<?>[] pts = m.getParameterTypes();
                // 7 params, first = String (title)
                if (pts.length == 7 && pts[0] == String.class) {
                    m.setAccessible(true);
                    cachedU25B = m;
                    return m;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ── 构建 rd5 Proxy（拦截点击回调）────────────────────────────

    private static Object buildRd5Proxy(ClassLoader cl, Logger logger, ActGetter acts) {
        try {
            Class<?> rd5Class = cl.loadClass("rd5");
            if (!rd5Class.isInterface()) {
                logger.log("ComposeHook: rd5 is not interface, passing null");
                return null;
            }
            return Proxy.newProxyInstance(cl, new Class[]{rd5Class},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            String mName = method.getName();
                            Class<?> ret  = method.getReturnType();
                            int argLen = (args == null) ? 0 : args.length;
                            logger.log("ComposeHook rd5." + mName
                                    + " called argCount=" + argLen + " retType=" + ret.getSimpleName());
                            if (argLen == 0 && (ret == void.class || ret == Void.class)) {
                                Activity act = acts.get();
                                if (act != null && !act.isFinishing()) {
                                    act.runOnUiThread(() -> {
                                        try { DeekseepUi.showPage(act); }
                                        catch (Throwable t) { logger.log("showPage err: " + t); }
                                    });
                                }
                                return null;
                            }
                            if (ret == boolean.class || ret == Boolean.class) return Boolean.FALSE;
                            if (ret == int.class    || ret == Integer.class) return 0;
                            if (ret == long.class   || ret == Long.class)    return 0L;
                            if (ret == float.class  || ret == Float.class)   return 0f;
                            if (ret == double.class || ret == Double.class)  return 0d;
                            if (ret == String.class) return "";
                            return null;
                        }
                    });
        } catch (Throwable t) {
            logger.log("ComposeHook: buildRd5Proxy err: " + t);
            return null;
        }
    }

    // ── 探针：打印 u25.b() 实际参数类型（调试用） ────────────────

    private static void probeU25B(ClassLoader cl, Logger logger) {
        try {
            Class<?> u25 = cl.loadClass("u25");
            StringBuilder sb = new StringBuilder("ComposeHook u25 methods named 'b': ");
            for (Method m : u25.getDeclaredMethods()) {
                if (!"b".equals(m.getName())) continue;
                sb.append("\n  b(");
                Class<?>[] pts = m.getParameterTypes();
                for (int i = 0; i < pts.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(pts[i].getSimpleName());
                }
                sb.append(")");
            }
            logger.log(sb.toString());
        } catch (Throwable t) {
            logger.log("ComposeHook probeU25B failed: " + t);
        }
    }

    private ComposeHook() {}
}

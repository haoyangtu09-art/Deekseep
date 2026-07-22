package com.dsmod.probe;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Compatibility adapter that runs the stable core's around-hook contract on traditional Xposed.
 *
 * <p>The stable API 102 and stable legacy APKs compile the same Main.java.  Modern libxposed
 * supplies an explicit Chain; traditional Xposed supplies before/after callbacks instead.  This
 * bridge invokes the original member from a before callback and publishes the interceptor result,
 * preserving the core's fail-open and argument-replacement behavior without maintaining a second
 * several-thousand-line fork.</p>
 */
abstract class LegacyXposedModule {

    private final ConcurrentHashMap<Member, HookTarget> targets =
            new ConcurrentHashMap<Member, HookTarget>();

    interface Hooker {
        Object intercept(Chain chain) throws Throwable;
    }

    static final class Chain {
        private final Member executable;
        private final XC_MethodHook.MethodHookParam param;
        private final List<Hooker> hookers;
        private final int nextHooker;
        private final Object[] args;
        private boolean proceeded;

        Chain(Member executable, XC_MethodHook.MethodHookParam param,
              List<Hooker> hookers, int nextHooker, Object[] args) {
            this.executable = executable;
            this.param = param;
            this.hookers = hookers;
            this.nextHooker = nextHooker;
            this.args = args == null ? new Object[0] : args;
        }

        Object getThisObject() { return param.thisObject; }

        Object getArg(int index) { return args[index]; }

        List<Object> getArgs() { return Arrays.asList(args); }

        Member getExecutable() { return executable; }

        Object proceed() throws Throwable { return proceed(args); }

        Object proceed(Object... args) throws Throwable {
            if (proceeded) {
                throw new IllegalStateException("Xposed chain proceeded more than once");
            }
            proceeded = true;
            Object[] actual = args == null ? new Object[0] : args;
            if (nextHooker < hookers.size()) {
                return hookers.get(nextHooker).intercept(new Chain(
                        executable, param, hookers, nextHooker + 1, actual));
            }
            // Keep MethodHookParam consistent for traditional after callbacks registered by
            // other modules. invokeOriginalMethod bypasses hook dispatch but uses these exact
            // arguments and thisObject, including for constructors.
            param.args = actual;
            return XposedBridge.invokeOriginalMethod(
                    executable, param.thisObject, actual);
        }
    }

    final class HookTarget {
        private final Member member;
        private final CopyOnWriteArrayList<Hooker> hookers =
                new CopyOnWriteArrayList<Hooker>();
        private boolean registered;

        HookTarget(Member member) { this.member = member; }

        synchronized void intercept(final Hooker hooker) {
            if (hooker == null) throw new NullPointerException("hooker");
            hookers.add(hooker);
            if (registered) return;
            try {
                // Run after ordinary before callbacks, then publish our result so their matching
                // after callbacks still execute. A highest-priority replacement would silently
                // suppress every other module hooked to the same host member.
                XposedBridge.hookMethod(member, new XC_MethodHook(Integer.MIN_VALUE) {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            List<Hooker> snapshot = Arrays.asList(
                                    hookers.toArray(new Hooker[hookers.size()]));
                            Chain chain = new Chain(member, param, snapshot, 0, param.args);
                            param.setResult(chain.proceed());
                        } catch (Throwable error) {
                            param.setThrowable(error);
                        }
                    }
                });
                registered = true;
            } catch (RuntimeException error) {
                hookers.remove(hooker);
                throw error;
            }
        }
    }

    HookTarget hook(Member member) {
        if (member == null) throw new NullPointerException("member");
        HookTarget existing = targets.get(member);
        if (existing != null) return existing;
        HookTarget created = new HookTarget(member);
        existing = targets.putIfAbsent(member, created);
        return existing == null ? created : existing;
    }

    boolean deoptimize(Member member) {
        try {
            Method method = XposedBridge.class.getDeclaredMethod("deoptimizeMethod", Member.class);
            method.setAccessible(true);
            method.invoke(null, member);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    void log(int priority, String tag, String message) {
        XposedBridge.log(tag + " [" + priority + "] " + message);
    }
}

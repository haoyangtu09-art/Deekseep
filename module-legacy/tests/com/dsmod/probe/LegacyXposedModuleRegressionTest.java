package com.dsmod.probe;

import java.lang.reflect.Member;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

public final class LegacyXposedModuleRegressionTest {
    private static final class Module extends LegacyXposedModule {}

    private static final class Target {
        String join(String left, String right) { return left + ":" + right; }
    }

    public static void main(String[] args) throws Throwable {
        XposedBridge.resetTestState();
        final Method method = Target.class.getDeclaredMethod(
                "join", String.class, String.class);
        method.setAccessible(true);
        XposedBridge.originalMethodInvokerForTest =
                new XposedBridge.OriginalMethodInvoker() {
                    @Override public Object invoke(Member member, Object receiver,
                                                   Object[] invokeArgs) throws Throwable {
                        return ((Method) member).invoke(receiver, invokeArgs);
                    }
                };

        Module module = new Module();
        module.hook(method).intercept(new LegacyXposedModule.Hooker() {
            @Override public Object intercept(LegacyXposedModule.Chain chain) throws Throwable {
                check("left".equals(chain.getArg(0)), "outer hook saw wrong initial args");
                return chain.proceed(new Object[] {"changed", "middle"}) + ":outer";
            }
        });
        module.hook(method).intercept(new LegacyXposedModule.Hooker() {
            @Override public Object intercept(LegacyXposedModule.Chain chain) throws Throwable {
                check("changed".equals(chain.getArg(0)),
                        "inner hook did not receive replaced args");
                Object[] next = chain.getArgs().toArray();
                next[1] = "final";
                return chain.proceed(next) + ":inner";
            }
        });

        check(XposedBridge.hookCountForTest == 1,
                "the same Member registered more than one traditional callback");
        check(XposedBridge.callbackForTest != null, "traditional callback was not registered");
        check(XposedBridge.callbackForTest.priority == Integer.MIN_VALUE,
                "adapter should run after ordinary before callbacks");

        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        param.method = method;
        param.thisObject = new Target();
        param.args = new Object[] {"left", "right"};
        XposedBridge.callbackForTest.dispatchBeforeForTest(param);

        check(!param.hasThrowable(), "adapter returned an unexpected throwable");
        check("changed:final:inner:outer".equals(param.getResult()),
                "around-hook order or return transforms changed: " + param.getResult());
        check("changed".equals(param.args[0]) && "final".equals(param.args[1]),
                "final arguments were not exposed to traditional after callbacks");
        System.out.println("LegacyXposedModuleRegressionTest OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

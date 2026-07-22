package de.robv.android.xposed;

import java.lang.reflect.Member;

// compileOnly stub — provided by the Xposed framework at runtime
public abstract class XC_MethodHook {

    public final int priority;

    public XC_MethodHook() { this(50); }
    public XC_MethodHook(int priority) { this.priority = priority; }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    // Test-only dispatcher on the compile stub. It is never packaged in the APK.
    public final void dispatchBeforeForTest(MethodHookParam param) throws Throwable {
        beforeHookedMethod(param);
    }

    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;
        public Object getResult() { return result; }
        public void setResult(Object result) { this.result = result; this.throwable = null; }
        public Throwable getThrowable() { return throwable; }
        public boolean hasThrowable() { return throwable != null; }
        public void setThrowable(Throwable throwable) { this.throwable = throwable; }
        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }
    }

    public class Unhook {
        public Member getHookedMethod() { return null; }
        public void unhook() {}
    }
}

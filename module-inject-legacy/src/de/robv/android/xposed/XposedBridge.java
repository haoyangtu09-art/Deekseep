package de.robv.android.xposed;

import java.lang.reflect.Member;

// compileOnly stub — provided by the Xposed framework at runtime
public final class XposedBridge {

    public static int XPOSED_BRIDGE_VERSION;

    private XposedBridge() {}

    public static void log(String text) {}

    public static void log(Throwable t) {}

    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        return null;
    }
}

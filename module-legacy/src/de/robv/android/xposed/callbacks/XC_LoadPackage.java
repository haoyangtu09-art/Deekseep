package de.robv.android.xposed.callbacks;

import android.content.pm.ApplicationInfo;

// compileOnly stub — provided by the Xposed framework at runtime
public class XC_LoadPackage {
    public static class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public ApplicationInfo appInfo;
        public boolean isFirstApplication;
    }
}

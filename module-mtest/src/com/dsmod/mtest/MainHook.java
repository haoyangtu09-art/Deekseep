package com.dsmod.mtest;

import android.app.Activity;
import android.util.Log;
import android.widget.Toast;

import java.io.FileWriter;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

public class MainHook extends XposedModule {

    private static final String TAG = "mtest";
    private static final String TARGET = "com.deepseek.chat";
    private static final String MARK = "/storage/emulated/0/deekseep_modern.txt";
    private static boolean shown = false;

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String pkg = param.getPackageName();
        log(Log.INFO, TAG, "onPackageLoaded " + pkg);

        // 一进来就写标记（外部存储写不进也无所谓，Toast 才是主信号）
        try {
            FileWriter w = new FileWriter(MARK, false);
            w.write(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + "  onPackageLoaded " + pkg + "\n");
            w.close();
        } catch (Throwable ignored) {}

        if (!TARGET.equals(pkg)) return;

        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            hook(onResume).intercept(new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    Object res = chain.proceed();
                    if (!shown) {
                        shown = true;
                        final Activity act = (Activity) chain.getThisObject();
                        try {
                            act.runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    try {
                                        Toast.makeText(act, "MODERN api102 OK",
                                                Toast.LENGTH_LONG).show();
                                    } catch (Throwable ignored) {}
                                }
                            });
                        } catch (Throwable ignored) {}
                    }
                    return res;
                }
            });
            log(Log.INFO, TAG, "hooked Activity.onResume");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook failed", t);
        }
    }
}

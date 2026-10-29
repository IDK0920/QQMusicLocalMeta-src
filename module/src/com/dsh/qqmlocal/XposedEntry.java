package com.dsh.qqmlocal;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 入口：只对 QQ音乐（com.tencent.qqmusic，含它的 :player 子进程）生效。
 */
public class XposedEntry implements IXposedHookLoadPackage {

    public static final String TARGET_PKG = "com.tencent.qqmusic";

    /** 版本横幅：装错版本 / 没生效时，第一行日志就能看出来 */
    public static final String BUILD = "v1.72";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null || lpparam.packageName == null) {
            return;
        }
        if (!TARGET_PKG.equals(lpparam.packageName)) {
            return;
        }
        try {
            Diag.w("==== QQMusicLocalMeta " + BUILD + " 进入 " + lpparam.processName + " ====");
            Log.i("输出目录: " + Paths.base());
            Log.i("==== QQMusicLocalMeta " + BUILD + " ==== "
                    + lpparam.packageName + " process=" + lpparam.processName
                    + " first=" + lpparam.isFirstApplication);
            ProbeHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            Log.e("初始化失败", t);
        }
    }
}

package com.dsh.qqmlocal;

/** 日志：全部走 XposedBridge.log，LSPosed 管理器 -> 日志 可直接看/复制。绝不抛异常。 */
public final class Log {

    private static final String TAG = "QQMusicLocal| ";
    private static final java.util.Set<String> SEEN =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private Log() {
    }

    public static void i(String msg) {
        try {
            de.robv.android.xposed.XposedBridge.log(TAG + msg);
        } catch (Throwable ignored) {
        }
    }

    public static void once(String key, String msg) {
        try {
            if (SEEN.add(key)) {
                i(msg);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void e(String msg, Throwable t) {
        try {
            i(msg + " !! " + android.util.Log.getStackTraceString(t));
        } catch (Throwable ignored) {
        }
    }
}

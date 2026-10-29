package com.dsh.qqmlocal;

import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 定位「暂无歌词」是被谁设上去的。
 * <p>
 * 症状：迷你歌词行有歌词，独立歌词页空白。日志里两边都解析成功（`Lrc 53`），
 * 说明数据有、判定也有，唯独页面显示空 —— 那就要看是谁把占位文案写进 TextView 的。
 * <p>
 * 钩 {@code TextView.setText}，命中「暂无歌词」就抓调用栈（限 3 次），
 * 找到那个类之后再针对性地改它。
 */
final class EmptyLyricHook {

    private static int hits;

    private EmptyLyricHook() {
    }

    static void install(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(TextView.class, "setText", CharSequence.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                onSetText(param);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            Diag.w("占位文案探针已挂 TextView.setText");
        } catch (Throwable t) {
            Diag.w("占位文案探针挂载失败: " + t.getClass().getSimpleName());
        }
    }

    private static void onSetText(XC_MethodHook.MethodHookParam param) {
        Object v = (param.args == null || param.args.length == 0) ? null : param.args[0];
        if (!(v instanceof CharSequence)) {
            return;
        }
        String s = v.toString();
        if (s == null || !s.contains("暂无歌词")) {
            return;
        }
        if (hits >= 3) {
            return;
        }
        hits++;
        StringBuilder sb = new StringBuilder("占位文案「").append(s).append("」来自 ");
        int shown = 0;
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            String c = e.getClassName();
            if (c.startsWith("com.dsh.") || c.startsWith("java.") || c.startsWith("de.robv")
                    || c.startsWith("android.") || c.startsWith("dalvik.")) {
                continue;
            }
            sb.append(c).append('#').append(e.getMethodName()).append(" <- ");
            if (++shown >= 6) {
                break;
            }
        }
        Diag.w(sb.toString());
    }
}

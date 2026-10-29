package com.dsh.qqmlocal;

/**
 * 采样调用栈：用来找出「到底是谁在不停请求歌词路径」。
 * <p>
 * 迷你歌词行工作正常、独立歌词页空白 ⇒ 说明歌词页用的是另一套取数路径。
 * 在 {@code config.a} 的路径方法上采样栈顶，就能直接看到那个类的名字。
 */
final class Stack {

    private static final int BUDGET = 10;
    private static int used;

    private Stack() {
    }

    static synchronized void sample(String label) {
        if (used >= BUDGET) {
            return;
        }
        used++;
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder("栈 ").append(label).append(" <- ");
            int shown = 0;
            for (StackTraceElement e : st) {
                String c = e.getClassName();
                if (c.startsWith("com.dsh.qqmlocal") || c.startsWith("java.")
                        || c.startsWith("de.robv") || c.startsWith("android.")) {
                    continue;
                }
                sb.append(c).append('#').append(e.getMethodName());
                if (++shown >= 6) {
                    break;
                }
                sb.append(" <- ");
            }
            if (shown > 0) {
                Diag.w(sb.toString());
            }
        } catch (Throwable ignored) {
        }
    }
}

package com.dsh.qqmlocal;

/**
 * 重入守卫。
 * <p>
 * {@link LyricHook#findAudioPath} 靠「反射调用 SongInfo 的所有 String getter」来找出本地文件路径，
 * 而 CoverHook 又挂了这些 getter。二者一撞就是无限递归：
 * <pre>
 *   getter → 钩子 → audioOf → findAudioPath → 反射调 getter → 钩子 → ...
 * </pre>
 * 凡是「我们自己去调 App 的方法」的地方，都用这个标记把钩子挡掉。
 */
final class Guard {

    private static final ThreadLocal<Boolean> BUSY = new ThreadLocal<Boolean>();

    private Guard() {
    }

    static void begin() {
        BUSY.set(Boolean.TRUE);
    }

    static void end() {
        BUSY.remove();
    }

    static boolean busy() {
        return Boolean.TRUE.equals(BUSY.get());
    }
}

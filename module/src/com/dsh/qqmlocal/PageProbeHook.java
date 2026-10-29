package com.dsh.qqmlocal;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 歌词页：切歌后让页面在主线程上重绑一次。
 * <p>
 * 失败场景（云端歌 → 上一首到本地歌）里，歌词页在切歌瞬间绑定了空值，
 * 之后就停在「歌曲信息」页。重绑必须投递到<b>主线程</b>——
 * 早期用后台线程，{@code x3} 直接抛 {@code CalledFromWrongThreadException}，一直在空转。
 */
final class PageProbeHook {

    private static final String FRAG =
            "com.tencent.qqmusic.business.playernew.view.playerlyric.PlayerLyricFragment";
    private static final String[] VIEW_CLASSES = {
            "com.tencent.qqmusic.business.playernew.view.playerlyric.mi",
            "com.tencent.qqmusic.business.playernew.view.playerlyric.listeningguide.q",
    };

    private static final java.util.Set<String> REBOUND =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static final java.util.Set<String> HOOKED_K =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** 是否已出现「定位到某行」的调用（本次线程） */
    private static final ThreadLocal<Boolean> POSITION_CALLED = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    private PageProbeHook() {
    }

    static void install(ClassLoader cl) {
        installViews(cl);
        Class<?> c = XposedHelpers.findClassIfExists(FRAG, cl);
        if (c == null) {
            Diag.w("找不到 PlayerLyricFragment");
            return;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            final String label = c.getSimpleName() + "#" + m.getName()
                    + "/" + m.getParameterTypes().length;
            try {
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        // 逐方法日志已关（只在需要排查时打开）
                        if ("Z2".equals(m.getName()) || "x3".equals(m.getName())) {
                            StringBuilder v = new StringBuilder(label).append(" line=");
                            if (param.args != null && param.args.length > 1) {
                                v.append(param.args[1]);
                            }
                            Diag.w(v.toString());
                            if (param.args != null && param.args.length > 0
                                    && param.args[0] != null) {
                                POSITION_CALLED.set(Boolean.TRUE);
                            }
                        }
                        if ("h3".equals(m.getName())) {
                            if (param.args != null && param.args.length > 0) {
                            }
                            maybeRebind(param, m);
                        }
                    }
                });
                n++;
            } catch (Throwable ignored) {
            }
        }
        Diag.w("歌词页探针挂了 " + n + " 个方法");
    }

    /** 进程启动就挂歌词视图，避免错过注入时机。 */
    private static void installViews(ClassLoader cl) {
        for (String name : VIEW_CLASSES) {
            Class<?> c = XposedHelpers.findClassIfExists(name, cl);
            if (c == null) {
                continue;
            }
            for (Method m : c.getDeclaredMethods()) {
                for (Class<?> ps : m.getParameterTypes()) {
                    if (ps.getName().equals("com.lyricengine.base.k")) {
                        hookLyricViewSetter(c, m);
                        break;
                    }
                }
            }
        }
    }

    private static void hookLyricViewSetter(Class<?> viewCls, Method m) {
        String key = viewCls.getName() + "#" + m.getName() + m.getParameterTypes().length;
        if (!HOOKED_K.add(key)) {
            return;
        }
        try {
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object[] a = param.args;
                        StringBuilder st = new StringBuilder();
                        for (Object x : a) {
                            st.append(x == null ? '0' : '1');
                        }
                        Diag.once("kcall:" + m.getName() + st,
                                "歌词视图被调用 " + m.getName() + "/" + a.length + " 空值=" + st);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 页面绑定时：主线程多次重绑，并补一次定位。 */
    private static void maybeRebind(XC_MethodHook.MethodHookParam param, Method m) {
        if (param.args == null || param.args.length == 0) {
            return;
        }
        final Object song = param.args[0];
        final Object target = param.thisObject;
        final Object[] args = param.args.clone();
        String dedup = String.valueOf(System.identityHashCode(target))
                + (song == null ? "null" : song.toString().hashCode());
        if (!REBOUND.add(dedup)) {
            return;
        }
        String audio = LyricHook.resolveAudio(song, null);
        if (audio == null) {
            return;
        }
        MetaExtractor.Meta meta = MetaCache.get(audio);
        if (meta == null || meta.lyrics == null || meta.lyrics.length() < 8) {
            return;
        }
        Diag.w("页面已绑定 " + new java.io.File(audio).getName() + "，主线程多次重绑");

        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final long[] delays = {0L, 700L, 1600L, 2800L, 4200L};
        for (int i = 0; i < delays.length; i++) {
            final int idx = i;
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        m.setAccessible(true);
                        m.invoke(target, args);
                        Diag.w("重绑 #" + (idx + 1) + " " + m.getName() + " (主线程)");
                        if (!Boolean.TRUE.equals(POSITION_CALLED.get())) {
                            forcePosition(target);
                        }
                        if (idx == 2) {          // 1.6s 时再试一次「切到歌词形态」
                            switchToLyricMode(target);
                        }
                    } catch (Throwable e) {
                        Diag.w("重绑 #" + (idx + 1) + " 失败 " + e.getClass().getSimpleName()
                                + (e.getCause() == null ? ""
                                        : "/" + e.getCause().getClass().getSimpleName()));
                    }
                }
            }, delays[i]);
        }
    }

    /** 尝试把页面从「歌曲信息」形态切回「歌词」形态。 */
    private static void switchToLyricMode(Object frag) {
        for (String n : new String[]{"V2", "y3"}) {
            try {
                for (Method mm : frag.getClass().getDeclaredMethods()) {
                    if (!n.equals(mm.getName()) || mm.getParameterTypes().length != 1) {
                        continue;
                    }
                    mm.setAccessible(true);
                    mm.invoke(frag, frag);
                    Diag.w("切歌词形态 " + n + "(frag) 成功");
                    return;
                }
            } catch (Throwable e) {
                Diag.w("切歌词形态 " + n + " 失败 " + e.getClass().getSimpleName()
                        + (e.getCause() == null ? ""
                                : "/" + e.getCause().getClass().getSimpleName()));
            }
        }
    }

    /** 失败路径上「定位当前行」没被触发，这里补一次（必须在主线程）。 */
    private static void forcePosition(Object frag) {
        for (String n : new String[]{"x3", "Z2"}) {
            try {
                for (Method mm : frag.getClass().getDeclaredMethods()) {
                    if (!n.equals(mm.getName()) || mm.getParameterTypes().length != 2) {
                        continue;
                    }
                    mm.setAccessible(true);
                    mm.invoke(frag, frag, 0);
                    Diag.w("补定位 " + n + "(frag,0) 成功");
                    return;
                }
            } catch (Throwable e) {
                Diag.w("补定位 " + n + " 失败 " + e.getClass().getSimpleName()
                        + (e.getCause() == null ? ""
                                : "/" + e.getCause().getClass().getSimpleName()));
            }
        }
    }
}

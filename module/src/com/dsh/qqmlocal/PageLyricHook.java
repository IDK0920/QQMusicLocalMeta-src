package com.dsh.qqmlocal;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 最后一环：把我们的歌词对象塞进「歌词交付」参数。
 * <p>
 * 反查 {@code com.tencent.qqmusic.business.lyricnew.load.manager.m}：
 * <pre>
 *   g(listener.c, model.a, k, k, k, int, k, k, k)   ← 把多个歌词对象交给监听器
 *   h(SongInfo) / i(SongInfo, Z)                    ← 为某首歌开始加载歌词
 * </pre>
 * 迷你歌词行能显示，说明这些对象里至少有一个被消费了；独立歌词页空，
 * 说明它依赖的那个槽位是 null。
 * <p>
 * 所以：交付时如果主歌词槽位为空，而我们有这首歌的内嵌歌词，
 * 就用 App 自己的解析器把它变成 {@code k} 填进去。
 */
final class PageLyricHook {

    private static final String MGR = "com.tencent.qqmusic.business.lyricnew.load.manager.m";
    private static final String PARSER = "com.tencent.qqmusic.business.lyricnew.load.helper.c";

    private PageLyricHook() {
    }

    static void install(ClassLoader cl) {
        Class<?> mgr = XposedHelpers.findClassIfExists(MGR, cl);
        if (mgr == null) {
            Diag.w("找不到 " + MGR);
            return;
        }
        int n = 0;
        for (Method m : mgr.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 9) {
                continue;
            }
            int kCount = 0;
            for (Class<?> p : ps) {
                if (p.getName().equals("com.lyricengine.base.k")) {
                    kCount++;
                }
            }
            if (kCount < 3) {
                continue;
            }
            try {
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            fill(param, ps);
                        } catch (Throwable t) {
                            Log.e("pageLyric", t);
                        }
                    }
                });
                n++;
                Diag.w("已挂歌词交付 " + m.getName() + "/9（k 槽 " + kCount + " 个）");
            } catch (Throwable t) {
                Diag.w("挂歌词交付失败: " + t.getClass().getSimpleName());
            }
        }
        // 把管理器的所有方法都盯上：看歌词页打开时它到底走了哪几步
        int all = 0;
        for (Method m : mgr.getDeclaredMethods()) {
            final String label = m.getName() + "/" + m.getParameterTypes().length;
            try {
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Diag.once("mgr:" + label, "管理器调用 " + label);
                    }
                });
                all++;
            } catch (Throwable ignored) {
            }
        }
        Diag.w("管理器全部方法已盯上 " + all + " 个");

        Log.i("歌词交付钩子：" + n + " 个");
        Diag.w("歌词交付钩子：" + n + " 个");
    }

    private static void fill(XC_MethodHook.MethodHookParam param, Class<?>[] ps) {
        Object[] args = param.args;
        if (args == null || args.length != 9) {
            return;
        }
        Object song = songOf(args[1]);
        String audio = LyricHook.resolveAudio(song, null);
        if (audio == null) {
            return;
        }
        MetaExtractor.Meta meta = MetaCache.get(audio);
        if (meta == null || meta.lyrics == null || meta.lyrics.length() < 8) {
            return;
        }
        // 主歌词槽位：第一个 k 参数
        int mainIdx = -1;
        for (int i = 0; i < ps.length; i++) {
            if (ps[i].getName().equals("com.lyricengine.base.k")) {
                mainIdx = i;
                break;
            }
        }
        StringBuilder state = new StringBuilder();
        for (int i = 0; i < ps.length; i++) {
            if (ps[i].getName().equals("com.lyricengine.base.k")) {
                state.append(args[i] == null ? '0' : '1');
            }
        }
        Diag.once("deliver:" + state, "歌词交付槽位 " + state + "（1=有对象）");
        if (mainIdx < 0 || args[mainIdx] != null) {
            return;                                  // App 自己已经有歌词，别动
        }
        Object k = buildK(meta);
        if (k == null) {
            Diag.once("buildK:fail", "自建歌词对象失败");
            return;
        }
        args[mainIdx] = k;
        Diag.once("fill:" + audio, "■■ 填入主歌词槽位 " + mainIdx + " -> "
                + AdHoc.truncate(String.valueOf(k), 60));
    }

    /** 用 App 自己的解析器把我们的 LRC 变成 k 对象。 */
    static Object buildKFor(MetaExtractor.Meta meta) {
        return buildK(meta);
    }

    private static Object buildK(MetaExtractor.Meta meta) {
        try {
            String lrc = MetaExtractor.ensureLrc(meta);
            Object r = XposedHelpers.callStaticMethod(PARSER_CLASS(),
                    "g", lrc, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE);
            if (r != null) {
                return r;
            }
            return XposedHelpers.callStaticMethod(PARSER_CLASS(),
                    "d", lrc, Boolean.FALSE, Boolean.FALSE);
        } catch (Throwable t) {
            Diag.once("buildK:" + t.getClass().getSimpleName(),
                    "自建歌词对象异常 " + t.getClass().getSimpleName());
            return null;
        }
    }

    private static Class<?> PARSER_CLASS() {
        return XposedHelpers.findClass(PARSER,
                PageLyricHook.class.getClassLoader() != null
                        ? PageLyricHook.class.getClassLoader() : null);
    }

    /** model.a 里找出 SongInfo。 */
    private static Object songOf(Object model) {
        if (model == null) {
            return null;
        }
        try {
            for (Field f : model.getClass().getDeclaredFields()) {
                Class<?> t = f.getType();
                if (t != null && t.getName().endsWith("SongInfo")) {
                    f.setAccessible(true);
                    Object v = f.get(model);
                    if (v != null) {
                        return v;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}

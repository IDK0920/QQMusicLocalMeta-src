package com.dsh.qqmlocal;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 独立歌词页的「有没有歌词」判定。
 * <p>
 * 从调用栈抓到歌词页的请求链：
 * <pre>
 *   config.a#f &lt;- config.a#n &lt;- fragment.producer.model.l#x(SongInfo)Z
 * </pre>
 * {@code l.x(SongInfo)} 是**静态方法返回 boolean**，内部拿 {@code .producer} 路径，
 * 判定「这首歌有没有可显示的歌词文件」，并被约 3 次/秒轮询。
 * 它返回 false ⇒ 歌词页空白（迷你歌词行不受这个判定影响，所以那边正常）。
 * <p>
 * 所以：只要我们为这首歌解出了内嵌歌词，就把这个判定翻成 true。
 */
final class LyricPageHook {

    private LyricPageHook() {
    }

    static void install(ClassLoader cl) {
        Class<?> cls = XposedHelpers.findClassIfExists(
                "com.tencent.qqmusic.fragment.producer.model.l", cl);
        if (cls == null) {
            Log.i("找不到 fragment.producer.model.l，歌词页判定钩子不可用");
            Diag.w("找不到 fragment.producer.model.l");
            return;
        }
        int n = 0;
        for (Method m : cls.getDeclaredMethods()) {
            if (!"x".equals(m.getName()) || m.getReturnType() != boolean.class
                    || m.getParameterTypes().length != 1) {
                continue;
            }
            try {
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            onCheck(param);
                        } catch (Throwable t) {
                            Log.e("l.x", t);
                        }
                    }
                });
                n++;
            } catch (Throwable t) {
                Diag.w("挂载 l.x 失败: " + t.getClass().getSimpleName());
            }
        }
        Log.i("歌词页判定钩子：挂了 " + n + " 个 l.x");
        Diag.w("歌词页判定钩子：挂了 " + n + " 个 l.x");
    }

    private static void onCheck(XC_MethodHook.MethodHookParam param) {
        Object original = param.getResult();
        boolean orig = Boolean.TRUE.equals(original);
        Object song = (param.args == null || param.args.length == 0) ? null : param.args[0];
        String audio = songAudio(song);
        if (audio == null) {
            Diag.once("lx:noaudio", "l.x 原值=" + orig + " 拿不到本地文件");
            return;
        }
        MetaExtractor.Meta meta = MetaCache.get(audio);
        if (meta == null || meta.lyrics == null || meta.lyrics.length() < 8) {
            Diag.once("lx:nolyric", "l.x 原值=" + orig + " 本地文件无内嵌歌词");
            return;
        }
        Diag.once("lx:" + orig, "l.x 原值=" + orig + "（观察，不再强制）");
        // ★ 不再强制 true：强制会让歌词页把「制作信息(credits)」面板顶上来，
        //   结果歌词被挤掉、页面显示成 作曲/语种/流派 那一堆（实测截图证实）。
    }

    private static String songAudio(Object song) {
        return LyricHook.resolveAudio(song, null);
    }
    /** SongInfo.toString() 里的 name= */
    private static String nameOf(Object song) {
        if (song == null) {
            return "";
        }
        String s = String.valueOf(song);
        int i = s.indexOf("name=");
        if (i < 0) {
            return "";
        }
        int j = s.indexOf(',', i);
        return j < 0 ? s.substring(i + 5) : s.substring(i + 5, j);
    }
}

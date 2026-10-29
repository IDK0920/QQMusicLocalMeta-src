package com.dsh.qqmlocal;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * v0.9：把整条歌词解析链都挂一遍，定位「到底在哪一步变成暂无歌词」。
 * <p>
 * 反汇编 {@code business.lyricnew.load.helper.a} 得到的日志串：
 * <pre>
 *   [getLyricString] lyricLoadTask= / bytes=null / bytes length=
 *   ", needDecryption = "
 *   [parse lrcLyricContent] content length = / [parse qrcLyricContent] content length =
 *   [LyricParseHelper->parseTextToLyric]->directly return null
 * </pre>
 * 说明流程是：加载任务(helper.a) 拿字节 → 解密 → 解析助手(helper.c) 解析成 com.lyricengine.base.k。
 * <p>
 * 两个类所有相关方法都挂上并打日志；只在「内容明显不像歌词 / 为空」时才替换，不覆盖真歌词。
 */
final class ContentHook {

    /** 重入守卫：在钩子里反射调用同一个方法会再次触发钩子，用这个挡掉。 */
    private static final ThreadLocal<Boolean> RETRYING = new ThreadLocal<Boolean>();

    private static final java.util.Set<String> LOGGED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private ContentHook() {
    }

    static void install(ClassLoader cl) {
        Class<?> helperA = XposedHelpers.findClassIfExists(
                "com.tencent.qqmusic.business.lyricnew.load.helper.a", cl);
        Class<?> helperC = XposedHelpers.findClassIfExists(
                "com.tencent.qqmusic.business.lyricnew.load.helper.c", cl);
        if (helperA == null && helperC == null) {
            Log.i("找不到歌词加载/解析类，内容级注入不可用");
            return;
        }
        int n = 0;
        if (helperA != null) {
            n += hookLoader(helperA);
            hookResultCallback(helperA);
        }
        if (helperC != null) {
            n += hookParser(helperC);
        }
        Log.i("内容级注入挂载完成，共 " + n + " 处");
    }

    // ------------------------------------------------------------ helper.a（加载任务）

    private static int hookLoader(Class<?> helperA) {
        int n = 0;
        for (Method m : helperA.getDeclaredMethods()) {
            if (m.getName().startsWith("access$")) {
                continue;
            }
            final String label = "load.a." + m.getName() + "/" + m.getParameterTypes().length;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            String msg = describeCall(label + " 调用", param);
                            Log.once("ld:" + msg, msg);
                            Diag.w(msg);
                        } catch (Throwable ignored) {
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object r = param.getResult();
                            String msg = describeCall(label + " 返回",
                                    param);
                            Log.once("ldr:" + msg, msg);
                            Diag.w(msg);
                            if (m.getReturnType() == String.class) {
                                trySubstitute(param, label, r);
                            }
                        } catch (Throwable t) {
                            Log.e("hookLoader after", t);
                        }
                    }
                });
                n++;
            } catch (Throwable t) {
                Log.i("跳过 " + label + "(" + t.getClass().getSimpleName() + ")");
            }
        }
        return n;
    }

    /**
     * 歌词加载结果回调 {@code listener/d.c(int, boolean×5, SongInfo)} 是**接口**，挂不了；
     * 但 {@code helper.a} 的构造器接收它的实现，所以先挂构造器拿到实现类，再动态挂那个类。
     * 只记日志 —— 用来对比「有歌词的云歌曲」和「我们这首本地歌」的标志位差异。
     */
    private static void hookResultCallback(Class<?> helperA) {
        try {
            XposedBridge.hookAllConstructors(helperA, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null) {
                            return;
                        }
                        for (Object a : param.args) {
                            if (a != null && a.getClass().getName().contains("listener")) {
                                hookListenerImpl(a.getClass());
                            }
                        }
                    } catch (Throwable t) {
                        Log.e("hookResultCallback", t);
                    }
                }
            });
            Log.i("已挂载 load.helper.a<init>（用于抓结果回调实现类）");
            Diag.w("已挂载 helper.a<init>");
        } catch (Throwable t) {
            Log.i("跳过 helper.a<init> 钩子: " + t.getClass().getSimpleName());
            Diag.w("跳过 helper.a<init>: " + t.getClass().getSimpleName());
        }
    }

    private static final java.util.Set<String> HOOKED_LISTENERS =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static void hookListenerImpl(Class<?> impl) {
        if (impl == null || !HOOKED_LISTENERS.add(impl.getName())) {
            return;
        }
        Log.i("结果回调实现类 = " + impl.getName());
        Diag.w("结果回调实现类 = " + impl.getName());
        for (Method m : impl.getMethods()) {
            if (!"c".equals(m.getName()) || m.getParameterTypes().length != 6) {
                continue;
            }
            try {
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            StringBuilder sb = new StringBuilder("结果回调 c(int=");
                            Object[] a = param.args;
                            if (a != null && a.length > 0) {
                                sb.append(a[0]);
                            }
                            sb.append(" flags=").append(flagsOf(a));
                            if (a != null && a.length > 6 && a[6] != null) {
                                sb.append(" song=").append(AdHoc.truncate(String.valueOf(a[6]), 90));
                            }
                            sb.append(")");
                            Log.once("cb:" + sb.toString().hashCode(), sb.toString());
                            Diag.w(sb.toString());
                        } catch (Throwable t) {
                            Log.e("listener.c", t);
                        }
                    }
                });
                Log.i("已挂载结果回调 " + impl.getSimpleName() + ".c");
            } catch (Throwable t) {
                Log.i("跳过结果回调: " + t.getClass().getSimpleName());
            }
        }
    }

    // ------------------------------------------------------------ helper.c（解析助手）

    private static int hookParser(Class<?> helperC) {
        int n = 0;
        for (Method m : helperC.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            final int strIdx = firstStringIndex(ps);
            final String label = "parse.c." + m.getName() + "/" + ps.length;
            try {
                final Method target = m;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(RETRYING.get())) {
                            return;                      // 是重试调用，跳过替换逻辑
                        }
                        try {
                            if (strIdx >= 0 && strIdx < param.args.length) {
                                Object a = param.args[strIdx];
                                String s = (a instanceof String) ? (String) a : null;
                                boolean use = looksLikeLyric(s);
                                String key = "pi:" + label + (s == null ? 0 : s.length()) + use;
                                if (LOGGED.add(key)) {
                                    Log.i("解析入参 " + label + " " + desc(s) + " 可用=" + use
                                            + " flags=" + flagsOf(param.args));
                                    Diag.w("解析入参 " + label + " " + desc(s) + " 可用=" + use
                                            + " flags=" + flagsOf(param.args));
                                }
                                if (!use) {
                                    // 入参是空 / 乱码：换成我们的 LRC
                                    String lrc = ourLrc(param.thisObject, param.args);
                                    if (lrc != null) {
                                        param.args[strIdx] = lrc;
                                        Log.once("sub:" + label, "■■ 替换解析入参 " + label
                                                + " (" + lrc.length() + " 字) 原值="
                                                + desc(s));
                                    }
                                } else {
                                    // 内容已经是我们的歌词，但原调用仍可能因为标志位返回 null。
                                    // 这类方法很可能「把结果存进内部字段，再从字段取值」——
                                    // 光改返回值不够，得先用正确标志位跑一遍把状态喂好。
                                    prewarm(param, target, label, strIdx);
                                }
                            }
                        } catch (Throwable t) {
                            Log.e("hookParser before", t);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(RETRYING.get())) {
                            return;
                        }
                        try {
                            Object r = param.getResult();
                            String key = "po:" + label + (r == null ? "null" : "obj");
                            if (LOGGED.add(key)) {
                                Log.i("解析结果 " + label + " -> " + (r == null ? "null" : "非 null"));
                                Diag.w("解析结果 " + label + " -> " + (r == null ? "null" : "非 null"));
                            }
                            if (r != null) {
                                return;
                            }
                            // 解析失败：换一组布尔标志再试一次（主进程很可能是按 qrc/解密 分支走的）
                            retryWithFlippedFlags(param, target, label);
                        } catch (Throwable t) {
                            Log.e("hookParser after", t);
                        }
                    }
                });
                n++;
            } catch (Throwable t) {
                Log.i("跳过 " + label + "(" + t.getClass().getSimpleName() + ")");
            }
        }
        return n;
    }

    /**
     * 解析返回 null 时，把布尔参数分别置全 false / 全 true 再调一次。
     * 成功就顶掉返回值 —— 这样不依赖我对那几个 Z 语义的猜测。
     */
    private static void retryWithFlippedFlags(XC_MethodHook.MethodHookParam param,
                                              Method m, String label) {
        Object[] orig = param.args;
        if (orig == null) {
            return;
        }
        boolean hasBool = false;
        for (Object a : orig) {
            if (a instanceof Boolean) {
                hasBool = true;
                break;
            }
        }
        if (!hasBool) {
            return;
        }
        try {
            m.setAccessible(true);          // helper.c 是包私有类，不设这个会 IllegalAccessException
        } catch (Throwable ignored) {
        }
        boolean[] tries = {Boolean.FALSE.booleanValue(), Boolean.TRUE.booleanValue()};
        for (boolean v : tries) {
            Object[] args = new Object[orig.length];
            System.arraycopy(orig, 0, args, 0, orig.length);
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Boolean) {
                    args[i] = Boolean.valueOf(v);
                }
            }
            RETRYING.set(Boolean.TRUE);
            try {
                Object ret = m.invoke(param.thisObject, args);
                if (ret != null) {
                    param.setResult(ret);
                    String preview;
                    try {
                        preview = AdHoc.truncate(String.valueOf(ret), 90);
                    } catch (Throwable t) {
                        preview = ret.getClass().getSimpleName();
                    }
                    Diag.w("■■ 布尔翻转成功 " + label + " 原flags=" + flagsOf(orig)
                            + " 改为全" + v + " 内容=" + AdHoc.truncate(String.valueOf(ret), 60));
                    Log.once("rt:" + label + v, "■■ 布尔翻转成功 " + label
                            + " 原flags=" + flagsOf(orig) + " 改为全" + v + " -> 非 null"
                            + " 内容=" + preview);
                    return;
                }
                Log.once("rtf:" + label + v, "布尔翻转仍 null " + label
                        + " 全" + v + " 原flags=" + flagsOf(orig));
            } catch (Throwable t) {
                Log.once("rte:" + label + v, "布尔翻转异常 " + label + " 全" + v
                        + ": " + t.getClass().getSimpleName());
            } finally {
                RETRYING.remove();
            }
        }
    }

    /**
     * 预热：用「全 false」标志先调一次同方法，让内部缓存/字段先拿到歌词对象。
     * 调用方随后走原逻辑时，通常就能命中缓存拿到非 null，而不是看我们的返回值。
     */
    private static void prewarm(XC_MethodHook.MethodHookParam param, Method m,
                                String label, int strIdx) {
        Object[] orig = param.args;
        if (orig == null || strIdx < 0 || strIdx >= orig.length) {
            return;
        }
        boolean hasTrue = false;
        for (Object a : orig) {
            if (a instanceof Boolean && ((Boolean) a).booleanValue()) {
                hasTrue = true;
                break;
            }
        }
        if (!hasTrue) {
            return;                     // flags 已经全 false，没必要预热
        }
        Object[] args = new Object[orig.length];
        System.arraycopy(orig, 0, args, 0, orig.length);
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Boolean) {
                args[i] = Boolean.FALSE;
            }
        }
        RETRYING.set(Boolean.TRUE);
        try {
            m.setAccessible(true);
            Object ret = m.invoke(param.thisObject, args);
            Diag.w("预热 " + label + " 原flags=" + flagsOf(orig) + " -> "
                    + (ret == null ? "null" : AdHoc.truncate(String.valueOf(ret), 50)));
        } catch (Throwable t) {
            Diag.w("预热失败 " + label + ": " + t.getClass().getSimpleName());
        } finally {
            RETRYING.remove();
        }
    }

    private static String flagsOf(Object[] args) {
        if (args == null) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (Object a : args) {
            if (a instanceof Boolean) {
                sb.append(((Boolean) a).booleanValue() ? 'T' : 'F');
            }
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    // ------------------------------------------------------------ 注入

    private static void trySubstitute(XC_MethodHook.MethodHookParam param, String label, Object r) {
        String content = (r instanceof String) ? (String) r : null;
        if (looksLikeLyric(content)) {
            return;
        }
        String lrc = ourLrc(param.thisObject, param.args);
        if (lrc == null) {
            return;
        }
        param.setResult(lrc);
        Log.once("ci:" + label + lrc.length(), "■■ 内容级注入 " + label + " (" + lrc.length()
                + " 字) 原值=" + desc(content));
    }

    /** 找当前歌曲的本地文件 → 内嵌歌词 → LRC。 */
    private static String ourLrc(Object thisObj, Object[] args) {
        Object song = songOf(thisObj, args);
        String audio = audioOf(song);
        if (audio == null) {
            Log.once("nl:" + System.identityHashCode(thisObj), "内容级注入跳过：拿不到本地文件");
            return null;
        }
        MetaExtractor.Meta meta = MetaCache.get(audio);
        if (meta == null || !meta.hasLyrics()) {
            return null;
        }
        return MetaExtractor.ensureLrc(meta);
    }

    private static Object songOf(Object thisObj, Object[] args) {
        try {
            Object s = XposedHelpers.getObjectField(thisObj, "a");
            if (s != null && s.getClass().getName().endsWith("SongInfo")) {
                return s;
            }
            if (s != null) {
                return s;
            }
        } catch (Throwable ignored) {
        }
        if (args != null) {
            for (Object a : args) {
                if (a != null && a.getClass().getName().endsWith("SongInfo")) {
                    return a;
                }
            }
        }
        return null;
    }

    private static String audioOf(Object song) {
        // 统一入口：必须歌名对得上才允许注入，避免污染云歌曲的歌词
        return LyricHook.resolveAudio(song, null);
    }
    private static String extractName(Object song) {
        try {
            String s = String.valueOf(song);
            int i = s.indexOf("name=");
            if (i < 0) {
                return "";
            }
            int j = s.indexOf(',', i);
            return (j > i ? s.substring(i + 5, j) : s.substring(i + 5)).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------ 工具

    private static int firstStringIndex(Class<?>[] ps) {
        for (int i = 0; i < ps.length; i++) {
            if (ps[i] == String.class) {
                return i;
            }
        }
        return -1;
    }

    private static String desc(String s) {
        if (s == null) {
            return "null";
        }
        if (s.length() == 0) {
            return "空串";
        }
        String t = s.replace('\n', '|').replace('\r', ' ');
        return s.length() + "字[" + AdHoc.truncate(t, 32) + "]";
    }

    private static boolean looksLikeLyric(String s) {
        if (s == null || s.trim().length() < 6) {
            return false;
        }
        if (s.indexOf('[') < 0 || s.indexOf(']') < 0) {
            return false;
        }
        int bad = 0;
        int len = Math.min(s.length(), 200);
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c < 0x09 || (c > 0x0D && c < 0x20) || c == 0x7F) {
                bad++;
            }
        }
        return bad * 10 < len;
    }

    private static String describeCall(String label, XC_MethodHook.MethodHookParam param) {
        StringBuilder sb = new StringBuilder(label).append(" args=");
        if (param.args == null || param.args.length == 0) {
            sb.append("(none)");
        } else {
            for (Object a : param.args) {
                if (a instanceof String) {
                    sb.append("[String ").append(desc((String) a)).append("] ");
                } else {
                    sb.append("[").append(a == null ? "null"
                            : a.getClass().getSimpleName()).append("] ");
                }
            }
        }
        Object r = param.getResult();
        if (r instanceof String) {
            sb.append("-> ").append(desc((String) r));
        }
        return sb.toString();
    }
}

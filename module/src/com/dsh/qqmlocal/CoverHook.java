package com.dsh.qqmlocal;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 封面数据层探测：看 {@code newimagemodule.k} 到底怎么被调用。
 * <p>
 * 之前只挂「第一个参数是 String」的方法 → 挂了 0 个，说明它的签名不是 URL 形态，
 * 更可能是 {@code (ImageView, int resId)} 或 {@code (View, Drawable)}。
 * <p>
 * 本版只观察，不改任何东西。
 */
final class CoverHook {

    private static int budget = 40;

    /** 当前播放歌曲名（歌词链路顺手记录，供其他模块使用） */
    private static volatile String sCurrentName;

    static void noteCurrentSong(Object song) {
        if (song == null) {
            return;
        }
        for (String f : new String[]{"O3", "j3", "name", "songName"}) {
            try {
                Method m = song.getClass().getMethod(f);
                if (m.getParameterTypes().length != 0 || m.getReturnType() != String.class) {
                    continue;
                }
                m.setAccessible(true);
                Object v = m.invoke(song);
                if (v instanceof String && ((String) v).length() > 0) {
                    sCurrentName = (String) v;
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    static String currentName() {
        return sCurrentName;
    }

    private CoverHook() {
    }

    static void install(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists("com.tencent.image.newimagemodule.k", cl);
        if (c == null) {
            Diag.w("找不到 newimagemodule.k");
            return;
        }
        int n = 0;
        Class<?> k = c;
        while (k != null && k != Object.class) {
            for (Method m : k.getDeclaredMethods()) {
                final String label = m.getName() + "/" + m.getParameterTypes().length;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args == null || param.args.length == 0) {
                                    return;
                                }
                                Object m0 = param.args[0];
                                if (m0 instanceof String && ((String) m0).contains("T002")
                                        && budget > 30) {
                                    budget = 30;                 // 只用 3 次额度抓栈
                                    stackProbe((String) m0);
                                }
                                for (Object a : param.args) {
                                    if (a == null) {
                                        continue;
                                    }
                                    String cn = a.getClass().getSimpleName();
                                    if (!cn.contains("AsyncEffectImageView")) {
                                        continue;
                                    }
                                    android.view.View v = (android.view.View) a;
                                    Diag.w("封面控件出现 k#" + label + " " + cn
                                            + " " + v.getWidth() + "x" + v.getHeight());
                                    applyTo(v);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    n++;
                } catch (Throwable ignored) {
                }
            }
            k = k.getSuperclass();
        }
        Diag.w("封面探测：newimagemodule.k 挂了 " + n + " 个方法");
        hookView(cl);
    }

    /** 抓「谁提供了封面 URL」的较深调用栈。 */
    private static void stackProbe(String url) {
        StringBuilder sb = new StringBuilder("封面URL提供者 url="
                + AdHoc.truncate(url, 50) + " <- ");
        int shown = 0;
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            String c = e.getClassName();
            if (c.startsWith("java.") || c.startsWith("de.robv") || c.startsWith("android.")
                    || c.startsWith("dalvik.") || c.startsWith("com.dsh.")
                    || c.startsWith("com.bumptech")) {
                continue;
            }
            sb.append(c).append('#').append(e.getMethodName()).append(" <- ");
            if (++shown >= 12) {
                break;
            }
        }
        Diag.w(sb.toString());
    }

    /** 对封面控件设我们解出的图（只在当前歌是我们的本地歌时）。 */
    private static void applyTo(android.view.View v) {
        try {
            String cur = sCurrentName;
            String audio = ProbeHook.readSharedLastPath();
            Diag.once("cv:state", "封面判定 cur=[" + cur + "] audio=[" + audio + "]");
            if (cur == null || audio == null) {
                return;
            }
            String want = AudioFinder.normalize(AudioFinder.stripExt(new java.io.File(audio).getName()));
            String cn = AudioFinder.normalize(cur);
            if (cn.length() < 3 || !(want.contains(cn) || cn.contains(want))) {
                Diag.once("cv:skip", "不是本歌（" + cn + "），不动封面控件");
                return;
            }
            MetaExtractor.Meta meta = MetaCache.get(audio);
            Diag.once("cv:meta", "meta=" + (meta == null ? "null"
                    : ("cover=" + (meta.cover == null ? 0 : meta.cover.length))));
            if (meta == null || !meta.hasCover()) {
                return;
            }
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory
                    .decodeByteArray(meta.cover, 0, meta.cover.length);
            if (bmp == null) {
                return;
            }
            ((android.widget.ImageView) v).setImageBitmap(bmp);
            Diag.once("cv:" + v.getWidth(), "■■ 设置封面控件 " + v.getWidth()
                    + "x" + v.getHeight() + " 歌=" + cn);
        } catch (Throwable t) {
            Diag.once("cv:err", "设置封面控件异常 " + t.getClass().getSimpleName());
        }
    }

    /** 抓「谁提供了封面 URL」的较深调用栈（挂在 wrap.d#load 上）。 */
    private static void hookWrapD(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists("com.tencent.image.newimage.wrap.d", cl);
        if (c == null) {
            Diag.w("找不到 wrap.d");
            return;
        }
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (m.getParameterTypes().length != 1 || m.getParameterTypes()[0] != String.class) {
                continue;
            }
            try {
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object a = param.args[0];
                            if (!(a instanceof String)) {
                                return;
                            }
                            String s = (String) a;
                            if (s.contains("T002") && probeLeft > 0) {
                                probeLeft--;
                                stackProbe(s);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                n++;
            } catch (Throwable ignored) {
            }
        }
        Diag.w("wrap.d 深栈探测挂了 " + n + " 个方法");
    }

    private static int probeLeft = 3;

    /** 直接看 AsyncEffectImageView 自己有哪些设图方法，并记录调用参数。 */
    /** 封面控件是 URL 驱动的：拦 setPendingUrl，把本地歌的空 URL 换成内嵌封面。 */
    private static void hookView(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists(
                "com.tencent.component.widget.AsyncEffectImageView", cl);
        if (c == null) {
            Diag.w("找不到 AsyncEffectImageView");
            return;
        }
        int n = 0;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (!"setPendingUrl".equals(m.getName())
                        || m.getParameterTypes().length != 1
                        || m.getParameterTypes()[0] != String.class) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String url = (String) param.args[0];
                                String cur = sCurrentName;
                                Diag.once("pu:" + (url == null ? "null" : String.valueOf(url.hashCode())),
                                        "setPendingUrl=[" + AdHoc.truncate(String.valueOf(url), 60)
                                                + "] 歌=" + cur);
                                String audio = ProbeHook.readSharedLastPath();
                                if (audio == null || cur == null) {
                                    return;
                                }
                                String want = AudioFinder.normalize(
                                        AudioFinder.stripExt(new java.io.File(audio).getName()));
                                String cn = AudioFinder.normalize(cur);
                                if (cn.length() < 3 || !(want.contains(cn) || cn.contains(want))) {
                                    return;
                                }
                                String path = coverPath(audio);
                                if (path != null) {
                                    param.args[0] = path;
                                    Diag.once("pufill", "■■ 封面 URL 换成 " + path);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    n++;
                } catch (Throwable ignored) {
                }
            }
        }
        Diag.w("setPendingUrl 钩子挂了 " + n + " 个");
    }

    /** 把内嵌封面写成文件，返回绝对路径。 */
    private static String coverPath(String audio) {
        try {
            MetaExtractor.Meta meta = MetaCache.get(audio);
            if (meta == null || !meta.hasCover()) {
                return null;
            }
            java.io.File dir = Paths.covers();
            if (dir == null) {
                return null;
            }
            java.io.File out = new java.io.File(dir, Integer.toHexString(audio.hashCode())
                    + "." + MetaExtractor.imageExt(meta.cover));
            if (!(out.isFile() && out.length() > 0)) {
                java.io.FileOutputStream fos = null;
                try {
                    fos = new java.io.FileOutputStream(out);
                    fos.write(meta.cover);
                    fos.flush();
                } finally {
                    if (fos != null) {
                        try {
                            fos.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            return out.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 更上一层：newimageglide.f 的所有带参方法，扫参数里的 T002 URL 并抓栈。 */
    private static void hookGlideF(ClassLoader cl) {
        Class<?> c = XposedHelpers.findClassIfExists("com.tencent.image.newimageglide.f", cl);
        if (c == null) {
            Diag.w("找不到 newimageglide.f");
            return;
        }
        int n = 0;
        Class<?> k = c;
        while (k != null && k != Object.class) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getParameterTypes().length == 0) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args == null || probeLeft <= 0) {
                                    return;
                                }
                                for (Object a : param.args) {
                                    if (a instanceof String
                                            && ((String) a).contains("T002")) {
                                        probeLeft--;
                                        stackProbe((String) a);
                                        break;
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    n++;
                } catch (Throwable ignored) {
                }
            }
            k = k.getSuperclass();
        }
        Diag.w("newimageglide.f 探测挂了 " + n + " 个方法");
    }

    private static String describe(Object[] a) {
        if (a == null) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder();
        for (Object o : a) {
            if (o == null) {
                sb.append("null");
            } else if (o instanceof String) {
                String s = (String) o;
                sb.append(s.length() == 0 ? "空串" : AdHoc.truncate(s, 50));
            } else if (o instanceof Integer) {
                sb.append("int:").append(o);
            } else {
                String cn = o.getClass().getSimpleName();
                sb.append(cn);
                if (cn.endsWith("ImageView") || cn.endsWith("View")) {
                    try {
                        android.view.View v = (android.view.View) o;
                        sb.append('(').append(v.getWidth()).append('x').append(v.getHeight()).append(')');
                    } catch (Throwable ignored) {
                    }
                }
            }
            sb.append(" | ");
        }
        return sb.toString();
    }
}

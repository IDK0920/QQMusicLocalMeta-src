package com.dsh.qqmlocal;

import java.io.File;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 从播放器内部拿「当前正在播放的本地文件」。
 * <p>
 * 之前依赖 {@code MediaMetadataRetriever.setDataSource} 抓路径，但 QQ音乐 播放走
 * <b>ExoPlayer</b>，那个钩子从未触发过 —— 这也是切歌后 {@code audio=null} 的根源：
 * SongInfo 实例被复用时我们拿不到可信的本地路径。
 * <p>
 * ExoPlayer 打开任何媒体都要经过 DataSource.open(DataSpec)，
 * DataSpec.uri 就是真实来源。本地文件是 {@code file://} 或绝对路径，云歌曲是 http —— 一筛就区分开。
 */
final class PlayingHook {

    private static final int LOG_BUDGET = 20;
    private static int logged;

    private PlayingHook() {
    }

    static void install(ClassLoader cl) {
        int n = 0;
        n += hookDataSource(cl, "com.google.android.exoplayer2.upstream.DefaultDataSource");
        n += hookDataSource(cl, "com.google.android.exoplayer2.upstream.FileDataSource");
        n += hookDataSource(cl, "com.google.android.exoplayer2.upstream.AssetDataSource");
        n += hookDataSource(cl, "com.google.android.exoplayer2.upstream.ContentDataSource");
        n += hookDataSource(cl, "com.google.android.exoplayer2.upstream.DefaultHttpDataSource");
        n += hookMediaItem(cl, "com.google.android.exoplayer2.ExoPlayerImpl");
        n += hookMediaItem(cl, "com.google.android.exoplayer2.SimpleExoPlayer");
        Log.i("播放器抓取就绪：共挂 " + n + " 个（含 setMediaItem*）");
    }

    private static int hookDataSource(ClassLoader cl, String className) {
        Class<?> c = XposedHelpers.findClassIfExists(className, cl);
        if (c == null) {
            return 0;
        }
        int n = 0;
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
            if (!"open".equals(m.getName()) || m.getParameterTypes().length != 1) {
                continue;
            }
            try {
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            onOpen(param);
                        } catch (Throwable t) {
                            Log.e("DataSource.open", t);
                        }
                    }
                });
                n++;
            } catch (Throwable ignored) {
            }
        }
        return n;
    }

    /**
     * 更高一层：ExoPlayer 被喂进去的 MediaItem 就是真实来源。
     * 这条路比 DataSource 稳 —— 即使它自己实现了 DataSource，也绕不开 setMediaItem。
     */
    private static int hookMediaItem(ClassLoader cl, String className) {
        Class<?> c = XposedHelpers.findClassIfExists(className, cl);
        if (c == null) {
            return 0;
        }
        int n = 0;
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
            String name = m.getName();
            if (!"setMediaItem".equals(name) && !"setMediaItems".equals(name)
                    && !"setMediaSource".equals(name)) {
                continue;
            }
            try {
                m.setAccessible(true);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            scanArgsForUri(param.args);
                        } catch (Throwable t) {
                            Log.e("setMediaItem", t);
                        }
                    }
                });
                n++;
            } catch (Throwable ignored) {
            }
        }
        return n;
    }

    private static void scanArgsForUri(Object[] args) {
        if (args == null) {
            return;
        }
        for (Object a : args) {
            if (a == null) {
                continue;
            }
            if (a instanceof java.util.List) {
                for (Object o : (java.util.List<?>) a) {
                    handleUriOf(o);
                }
            } else {
                handleUriOf(a);
            }
        }
    }

    private static void handleUriOf(Object mediaItem) {
        String uri = uriOf(mediaItem);
        if (uri == null) {
            return;
        }
        String path = pathOf(uri);
        if (path != null && new File(path).isFile()) {
            LyricHook.noteLocalFile(path);
            Log.once("mi:" + path, "♪ 播放器 MediaItem 指向本地文件 " + path);
        } else if (logged++ < LOG_BUDGET) {
            ProbeHook.writeSharedKind(false);
            Log.i("播放器 MediaItem(非本地) " + AdHoc.truncate(uri, 80));
        }
    }

    /** 从 MediaItem 里取 uri：优先反射字段，其次从 toString 正则抓。 */
    private static String uriOf(Object mediaItem) {
        if (mediaItem == null) {
            return null;
        }
        try {
            Object lc = XposedHelpers.getObjectField(mediaItem, "localConfiguration");
            if (lc != null) {
                Object uri = XposedHelpers.getObjectField(lc, "uri");
                if (uri != null) {
                    return String.valueOf(uri);
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            String s = String.valueOf(mediaItem);
            java.util.regex.Matcher mm = java.util.regex.Pattern
                    .compile("(file://[^,}\\s]+|/[^,}\\s]+|https?://[^,}\\s]+)")
                    .matcher(s);
            if (mm.find()) {
                return mm.group(1);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void onOpen(XC_MethodHook.MethodHookParam param) {
        Object spec = (param.args == null || param.args.length == 0) ? null : param.args[0];
        if (spec == null) {
            return;
        }
        Object uri;
        try {
            uri = XposedHelpers.getObjectField(spec, "uri");
        } catch (Throwable t) {
            return;
        }
        if (uri == null) {
            return;
        }
        String path = pathOf(uri);
        if (path == null) {
            ProbeHook.writeSharedKind(false);        // 网络源：云歌曲
            if (logged++ < LOG_BUDGET) {
                Log.i("播放源(非本地) " + AdHoc.truncate(String.valueOf(uri), 80));
            }
            return;
        }
        if (!new File(path).isFile()) {
            return;
        }
        LyricHook.noteLocalFile(path);
        Log.once("play:" + path, "♪ 正在播放本地文件 " + path);
    }

    /** 把 Uri 变成本地绝对路径；不是本地文件返回 null。 */
    private static String pathOf(Object uri) {
        String s = String.valueOf(uri);
        if (s == null || s.length() == 0) {
            return null;
        }
        if (s.startsWith("file://")) {
            s = s.substring(7);
            // file:///storage/... -> /storage/...
            return s.startsWith("/") ? s : "/" + s;
        }
        if (s.startsWith("/")) {
            return s;
        }
        return null;
    }
}

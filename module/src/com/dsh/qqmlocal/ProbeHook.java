package com.dsh.qqmlocal;

import java.io.File;
import java.io.FileOutputStream;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * v0.2 钩子层。
 * <ul>
 *   <li>{@code MediaMetadataRetriever.setDataSource}：抓到它打开哪个本地文件，顺手把内嵌封面/歌词解出来</li>
 *   <li>{@link LyricHook}：在它算出歌词缓存路径后，把解出的歌词写到那个路径上</li>
 * </ul>
 */
public final class ProbeHook {

    /** 落盘目录，方便直接确认「解析这半边」是通的。 */
    /** 自己的输出目录（必须是应用私有外部目录，公共同级目录写不进去） */
    private static String OUT_DIR() {
        return Paths.base();
    }

    private static volatile String lastMediaPath;

    private ProbeHook() {
    }

    public static void install(ClassLoader cl) {
        Log.i("挂载钩子…");
        try {
            Janitor.cleanOnce();
        } catch (Throwable t) {
            Log.e("Janitor", t);
        }
        hookMediaRetriever();
        LyricHook.install(cl);
        PlayingHook.install(cl);
        ContentHook.install(cl);
        LyricPageHook.install(cl);
        PageLyricHook.install(cl);
        PageProbeHook.install(cl);
        // CoverHook.install(cl);   // 封面：播放页圆盘是自绘控件，无可拦截的参数接缝（见 README 第 42 节）   // 只做「切歌后让歌词页重绑一次」
        // v1.10：封面钩子全部停用。基线优先——先确认歌词稳定可用，再单独攻封面。
        // CoverHook.install(cl);   // 封面数据层：已确认无注入点
        // PlayerCoverHook.install(cl);   // 视图层封面：会误伤播放器机体 UI，停用
        Log.i("钩子挂载结束");
    }

    // ------------------------------------------------------------ 抓本地文件

    private static void hookMediaRetriever() {
        XC_MethodHook cb = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    String path = null;
                    if (param.args != null && param.args.length > 0) {
                        Object a0 = param.args[0];
                        if (a0 instanceof String) {
                            path = (String) a0;
                        } else if (a0 instanceof File) {
                            path = ((File) a0).getAbsolutePath();
                        } else if (a0 != null) {
                            path = a0.toString();
                        }
                    }
                    if (path != null && AdHoc.looksAudio(path)) {
                        lastMediaPath = path;
                        writeSharedLastPath(path);
                        Log.once("mmr:" + path, "打开本地文件 " + path);
                        inspectFile(path);
                    }
                } catch (Throwable t) {
                    Log.e("hookMediaRetriever", t);
                }
            }
        };
        // setDataSource(String) 必须显式写参数类型，否则会去找零参重载而报 NoSuchMethodError
        tryHook(android.media.MediaMetadataRetriever.class, "setDataSource",
                new Object[]{String.class, cb}, "setDataSource(String)");
        tryHook(android.media.MediaMetadataRetriever.class, "setDataSource",
                new Object[]{android.content.Context.class, android.net.Uri.class, cb},
                "setDataSource(Context,Uri)");
        tryHook(android.media.MediaMetadataRetriever.class, "setDataSource",
                new Object[]{String.class, java.util.Map.class, cb}, "setDataSource(String,Map)");
        tryHook(android.media.MediaMetadataRetriever.class, "setDataSource",
                new Object[]{java.io.FileDescriptor.class, cb}, "setDataSource(FileDescriptor)");
    }

    /** 解它打开的文件，落盘一份便于确认；同时写进缓存给 LyricHook 用。 */
    private static void inspectFile(String path) {
        try {
            File f = new File(path);
            if (!f.isFile() || f.length() <= 0) {
                return;
            }
            MetaExtractor.Meta meta = MetaCache.get(path);
            if (meta == null) {
                return;
            }
            if (meta.diag.length() > 0) {
                Log.i("标签结构 " + f.getName() + " -> " + AdHoc.truncate(meta.diag, 320));
                writeDiag(f, meta);
            }
            if (meta.hasLyrics() || meta.hasCover()) {
                dump(f, meta);
            }
        } catch (Throwable t) {
            Log.e("inspectFile " + path, t);
        }
    }

    /** 把标签结构写成一个文件，跨进程/跨会话都能查。 */
    private static void writeDiag(File src, MetaExtractor.Meta meta) {
        try {
            File dir = new File(OUT_DIR());
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            File f = new File(dir, "tags.txt");
            String line = src.getName() + " || " + meta + " || " + meta.diag + "\n";
            java.io.FileOutputStream out = new java.io.FileOutputStream(f, true);
            try {
                out.write(line.getBytes("UTF-8"));
                out.flush();
            } finally {
                try { out.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            Log.e("writeDiag", t);
        }
    }

    private static void dump(File src, MetaExtractor.Meta meta) {
        try {
            File dir = new File(OUT_DIR());
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            String key = Integer.toHexString(src.getAbsolutePath().hashCode());
            String base = src.getName().replaceAll("[\\\\/:*?\"<>|]", "_") + "_" + key;
            if (meta.hasLyrics()) {
                File lrc = new File(dir, base + ".lrc");
                if (!lrc.exists()) {
                    write(lrc, MetaExtractor.ensureLrc(meta).getBytes("UTF-8"));
                    Log.i("导出歌词 " + lrc.getName());
                }
            }
            if (meta.hasCover()) {
                File img = new File(dir, base + "." + MetaExtractor.imageExt(meta.cover));
                if (!img.exists()) {
                    write(img, meta.cover);
                    Log.i("导出封面 " + img.getName() + " (" + meta.cover.length + "B)");
                }
            }
        } catch (Throwable t) {
            Log.e("dump " + src, t);
        }
    }

    private static void write(File f, byte[] data) throws Exception {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f);
            fos.write(data);
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

    private static void tryHook(Class<?> cls, String name, Object[] params, String label) {
        try {
            XposedHelpers.findAndHookMethod(cls, name, params);
        } catch (Throwable t) {
            Log.i("跳过 " + label + " (" + t.getClass().getSimpleName() + ")");
        }
    }

    static String lastMediaPath() {
        return lastMediaPath;
    }

    /**
     * 跨进程共享「最近播放的本地文件」。
     * setDataSource 发生在 :QQPlayerService 进程，而歌词页在主进程，内存不共享，
     * 所以落一个小文件给另一个进程读。
     */
    static void writeSharedLastPath(String path) {
        try {
            File dir = new File(OUT_DIR());
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            File f = new File(dir, "last.txt");
            if (!f.exists() || f.length() == 0) {
                write(f, path.getBytes("UTF-8"));
            } else {
                // 内容变了才重写（文件不可原地改写时用删除后新建）
                String old = readAll(f);
                if (!path.equals(old)) {
                    f.delete();
                    write(f, path.getBytes("UTF-8"));
                }
            }
        } catch (Throwable t) {
            Log.e("writeSharedLastPath", t);
        }
    }



    /** 记录最近一次被播放器打开的媒体源类型，避免「本地歌词写进云歌缓存」。 */
    static void writeSharedKind(boolean local) {
        try {
            File dir = new File(OUT_DIR());
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            File f = Paths.file("last_kind.txt");
            if (f == null) {
                return;
            }
            String want = local ? "local" : "remote";
            String cur = readAll(f);
            if (want.equals(cur)) {
                return;
            }
            f.delete();
            write(f, want.getBytes("UTF-8"));
        } catch (Throwable t) {
            Log.e("writeSharedKind", t);
        }
    }

    /** true=最近打开的是本地文件；false=网络；null=未知 */
    static Boolean readSharedKind() {
        try {
            File f = Paths.file("last_kind.txt");
            if (f == null || !f.isFile()) {
                return null;
            }
            String s = readAll(f);
            if ("local".equals(s)) {
                return Boolean.TRUE;
            }
            if ("remote".equals(s)) {
                return Boolean.FALSE;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static String readSharedLastPath() {
        try {
            File f = Paths.file("last.txt");
            if (!f.isFile()) {
                return null;
            }
            String s = readAll(f);
            return (s != null && s.length() > 0 && AdHoc.looksAudio(s)) ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readAll(File f) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), 4096)];
            int n = in.read(buf);
            return n <= 0 ? null : new String(buf, 0, n, "UTF-8").trim();
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) { }
            }
        }
    }
}

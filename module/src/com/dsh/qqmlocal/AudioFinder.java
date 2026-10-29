package com.dsh.qqmlocal;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按文件名反查本地音频。
 * <p>
 * QQ音乐 对「导入的本地歌曲」用的歌词路径是
 * {@code files/qqmusic/import/<文件名>.qrc} —— 路径里就带着歌名，
 * 所以即使拿不到 SongInfo，也能靠它反查出真实的音频文件。
 */
final class AudioFinder {

    private static final String ROOT = "/storage/emulated/0";
    private static final int MAX_DEPTH = 3;
    private static final int MAX_FILES = 4000;

    /** 文件名(不含后缀) -> 完整路径 */
    private static final Map<String, String> INDEX = new HashMap<String, String>();
    private static volatile boolean built;

    private AudioFinder() {
    }

    /** @param base 不含后缀的文件名，例如 "鹿乃(かの) - 高嶺の花子さん" */
    static synchronized String byBaseName(String base) {
        if (!built) {
            build(ROOT);
        }
        return lookup(base);
    }

    /** 可注入根目录，便于在电脑上测试。 */
    static synchronized String byBaseNameIn(String root, String base) {
        build(root);
        return lookup(base);
    }

    /**
     * 按「包含」匹配：歌名只有一个片段时也能找到。
     * 例如 SongInfo.name = "高嶺の花子さん"，文件名为 "鹿乃(かの) - 高嶺の花子さん.flac"。
     */
    static synchronized String byContainsName(String name) {
        if (name == null || name.trim().length() < 2) {
            return null;
        }
        if (!built) {
            build(ROOT);
        }
        String needle = normalize(name);
        if (needle.length() < 2) {
            return null;
        }
        String best = null;
        for (java.util.Map.Entry<String, String> e : INDEX.entrySet()) {
            String k = normalize(e.getKey());
            if (k.length() == 0) {
                continue;
            }
            if (k.contains(needle) || needle.contains(k)) {
                String path = e.getValue();
                if (new File(path).isFile()) {
                    // 优先取名字最短的（最贴近歌名本身）
                    if (best == null || path.length() < best.length()) {
                        best = path;
                    }
                }
            }
        }
        return best;
    }

    private static String lookup(String base) {
        if (base == null || base.length() == 0) {
            return null;
        }
        String hit = INDEX.get(base);
        if (hit == null) {
            // 归一化再试一次：空格/连接符差异（"歌手 - 歌名" vs "歌手-歌名"）
            hit = INDEX.get(normalize(base));
        }
        return (hit != null && new File(hit).isFile()) ? hit : null;
    }

    private static void build(String root) {
        built = true;
        INDEX.clear();
        int[] count = {0};
        try {
            scan(new File(root), 0, count);
        } catch (Throwable ignored) {
        }
    }

    /** 去掉空白与常见连接符，用于容忍「歌手 - 歌名」和「歌手-歌名」的差异。 */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '\u3000' || c == '-' || c == '\u2013'
                    || c == '\u2014' || c == '\uff0d' || c == '_') {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static void scan(File dir, int depth, int[] count) {
        if (dir == null || depth > MAX_DEPTH || count[0] > MAX_FILES) {
            return;
        }
        File[] list;
        try {
            list = dir.listFiles();
        } catch (Throwable t) {
            return;
        }
        if (list == null) {
            return;
        }
        for (File f : list) {
            if (count[0] > MAX_FILES) {
                return;
            }
            String name = f.getName();
            if (name.startsWith(".")) {
                continue;
            }
            try {
                if (f.isDirectory()) {
                    // 跳过别的 App 的私有目录，只进 QQ音乐 自己的；Android/obb 也跳过
                    if ("Android".equals(name)) {
                        File qq = new File(f, "data/com.tencent.qqmusic");
                        if (qq.isDirectory()) {
                            scan(qq, depth + 1, count);
                        }
                        continue;
                    }
                    if ("obb".equals(name)) {
                        continue;
                    }
                    scan(f, depth + 1, count);
                } else if (AdHoc.looksAudio(name)) {
                    count[0]++;
                    String base = stripExt(name);
                    if (!INDEX.containsKey(base)) {
                        INDEX.put(base, f.getAbsolutePath());
                    }
                    String norm = normalize(base);
                    if (!INDEX.containsKey(norm)) {
                        INDEX.put(norm, f.getAbsolutePath());
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    static int size() {
        synchronized (AudioFinder.class) {
            return INDEX.size();
        }
    }

    /** 给日志用：列出索引里前几个 key，确认索引不是空的。 */
    static List<String> sampleKeys(int n) {
        List<String> out = new ArrayList<String>();
        synchronized (AudioFinder.class) {
            for (String k : INDEX.keySet()) {
                out.add(k);
                if (out.size() >= n) {
                    break;
                }
            }
        }
        return out;
    }
}

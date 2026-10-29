package com.dsh.qqmlocal;

import java.io.File;
import java.io.FileOutputStream;

/** 验证「按文件名反查本地音频」：空格/连接符不同也要能找到。 */
public class TestAudioFinder {
    private static int fail = 0;

    private static void check(String what, boolean ok, String detail) {
        System.out.printf("%s %-46s %s%n", ok ? "ok  " : "FAIL", what, detail);
        if (!ok) { fail++; }
    }

    private static void touch(File f) throws Exception {
        f.getParentFile().mkdirs();
        FileOutputStream o = new FileOutputStream(f);
        o.write(new byte[]{0x66, 0x4c, 0x61, 0x43});
        o.close();
    }

    public static void main(String[] a) throws Exception {
        File root = new File(a.length > 0 ? a[0] : "work/fixture/tree");
        delete(root);
        touch(new File(root, "Music/鹿乃(かの)-高嶺の花子さん.flac"));
        touch(new File(root, "Music/Artist - Song Name.mp3"));
        touch(new File(root, "Download/nested/Deep Track.flac"));
        touch(new File(root, "Android/data/com.tencent.qqmusic/files/qqmusic/x.flac"));
        new File(root, "Music/歌词不是音频.txt").getParentFile().mkdirs();
        new FileOutputStream(new File(root, "Music/歌词不是音频.txt")).close();

        System.out.println("== 按文件名反查 ==");
        check("空格差异也能命中（带空格找不带）",
                AudioFinder.byBaseNameIn(root.getPath(), "鹿乃(かの) - 高嶺の花子さん") != null,
                String.valueOf(AudioFinder.byBaseNameIn(root.getPath(), "鹿乃(かの) - 高嶺の花子さん")));
        check("连接符差异也能命中（- 找 空格-空格）",
                AudioFinder.byBaseNameIn(root.getPath(), "Artist-Song Name") != null,
                String.valueOf(AudioFinder.byBaseNameIn(root.getPath(), "Artist-Song Name")));
        check("子目录里也能找到", AudioFinder.byBaseNameIn(root.getPath(), "Deep Track") != null,
                String.valueOf(AudioFinder.byBaseNameIn(root.getPath(), "Deep Track")));
        check("QQ音乐 私有目录也能找到", AudioFinder.byBaseNameIn(root.getPath(), "x") != null,
                String.valueOf(AudioFinder.byBaseNameIn(root.getPath(), "x")));
        check("非音频文件不进索引", AudioFinder.byBaseNameIn(root.getPath(), "歌词不是音频") == null,
                "null");
        check("不存在的名字返回 null", AudioFinder.byBaseNameIn(root.getPath(), "根本没有这首歌") == null,
                "null");
        check("null / 空串不崩",
                AudioFinder.byBaseNameIn(root.getPath(), null) == null
                        && AudioFinder.byBaseNameIn(root.getPath(), "") == null, "ok");

        System.out.println("== 按歌名片段反查（SongInfo.name 只是文件名的一部分）==");
        check("歌名片段命中 鹿乃(かの)-高嶺の花子さん.flac",
                AudioFinder.byContainsName("高嶺の花子さん") != null,
                String.valueOf(AudioFinder.byContainsName("高嶺の花子さん")));
        check("歌名片段命中 Artist - Song Name.mp3",
                AudioFinder.byContainsName("Song Name") != null,
                String.valueOf(AudioFinder.byContainsName("Song Name")));
        check("不相干的歌名返回 null",
                AudioFinder.byContainsName("完全不相干的名字") == null, "null");
        check("太短的歌名不误配", AudioFinder.byContainsName("x") == null, "null");

        System.out.println();
        System.out.println(fail == 0 ? "全部通过" : "失败 " + fail + " 项");
        if (fail != 0) { System.exit(1); }
    }

    private static void delete(File f) {
        if (f.isDirectory()) {
            File[] cs = f.listFiles();
            if (cs != null) { for (File c : cs) { delete(c); } }
        }
        f.delete();
    }
}

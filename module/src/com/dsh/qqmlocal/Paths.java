package com.dsh.qqmlocal;

import java.io.File;

/**
 * 存放我们自己的缓存/诊断文件。
 * <p>
 * <b>不能</b>写 {@code /sdcard/QQMusicLocalMeta/} —— QQ音乐 targetSdk 30，
 * 在 Android 11+ 的存储沙箱下写不了公共目录的任意路径（会静默失败）。
 * 但写**应用自己的外部目录**是允许的，也就是它自己的
 * {@code Android/data/com.tencent.qqmusic/files/} 下。
 * <p>
 * 顺带说明：之前 {@code last.txt} / 封面 / diag 全部没生成，
 * 就是因为这个 —— 而歌词写入一直成功，因为目标本来就在应用目录里。
 */
final class Paths {

    /** QQ音乐 自己的外部目录根：Android/data/com.tencent.qqmusic/files/ */
    private static final String[] APP_ROOTS = {
            "/storage/emulated/0/Android/data/com.tencent.qqmusic/files",
            "/sdcard/Android/data/com.tencent.qqmusic/files",
    };

    private static volatile String base;

    private Paths() {
    }

    /** 我们自己的工作目录：<app files>/qqmusic/qqmlocal/ */
    static synchronized String base() {
        if (base != null) {
            return base;
        }
        for (String root : APP_ROOTS) {
            File dir = new File(root, "qqmusic/qqmlocal");
            try {
                if (dir.isDirectory() || dir.mkdirs()) {
                    base = dir.getAbsolutePath();
                    return base;
                }
            } catch (Throwable ignored) {
            }
        }
        base = "";
        return base;
    }

    static File file(String name) {
        String b = base();
        if (b.length() == 0) {
            return null;
        }
        return new File(b, name);
    }

    /** 封面目录 */
    static File covers() {
        String b = base();
        if (b.length() == 0) {
            return null;
        }
        File d = new File(b, "covers");
        if (!d.isDirectory()) {
            d.mkdirs();
        }
        return d;
    }
}

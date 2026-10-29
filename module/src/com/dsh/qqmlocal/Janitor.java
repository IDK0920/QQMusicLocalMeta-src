package com.dsh.qqmlocal;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * 清理我们自己写坏的缓存文件。
 * <p>
 * 历史遗留：v1.0~v1.14 曾把**纯文本 LRC** 写进 `.qrc` 文件。
 * 但 `.qrc` 是腾讯的加密格式，QQ音乐 读到会解密失败 → 显示「暂无歌词」；
 * 更糟的是文件「存在」会让我们的「缺失才注入」逻辑短路。
 * <p>
 * 所以启动时把「内容看起来是我们写的 LRC」的 `.qrc` 删掉，让流程回到
 * 「文件不存在 → 走解析层注入」那条已经验证可用的路。
 */
final class Janitor {

    private static final String[] DIRS = {
            "/storage/emulated/0/Android/data/com.tencent.qqmusic/files/qqmusic/qrc",
            "/storage/emulated/0/Android/data/com.tencent.qqmusic/files/qqmusic/import",
    };

    private Janitor() {
    }

    static void cleanOnce() {
        int removed = 0;
        int scanned = 0;
        for (String dirPath : DIRS) {
            File dir = new File(dirPath);
            File[] files = dir.listFiles();
            if (files == null) {
                continue;
            }
            for (File f : files) {
                try {
                    if (!f.isFile() || !f.getName().endsWith(".qrc")) {
                        continue;
                    }
                    scanned++;
                    if (isOurPlainLrc(f)) {
                        if (f.delete()) {
                            removed++;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        Log.i("清道夫：扫描 " + scanned + " 个 .qrc，删除 " + removed + " 个纯文本的");
        Diag.w("清道夫：扫描 " + scanned + " 个 .qrc，删除 " + removed + " 个纯文本的");
    }

    /** 头部是不是我们生成的 LRC（[ti:] / [ar:] / [by:qqmlocal] / [mm:ss]）。 */
    private static boolean isOurPlainLrc(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[64];
            int n = in.read(buf);
            if (n <= 0) {
                return false;
            }
            String head = new String(buf, 0, n, "UTF-8");
            if (head.contains("[by:qqmlocal]")) {
                return true;
            }
            String t = head.trim();
            return t.startsWith("[ti:") || t.startsWith("[ar:")
                    || t.startsWith("[00:") || t.startsWith("[al:");
        } catch (Throwable t) {
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}

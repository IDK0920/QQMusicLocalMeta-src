package com.dsh.qqmlocal;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 把关键事件写成一个自查文件，避免靠截图逐张读日志。
 * <p>
 * 路径：{@code /sdcard/QQMusicLocalMeta/diag.log}
 * 内容示例：
 * <pre>
 * 20:31:02.123 [com.tencent.qqmusic] 歌词路径 suffix=.qrc exists=false base=&lt;hash&gt; audio=&lt;null|路径&gt;(来源) meta=600字
 * 20:31:02.456 [com.tencent.qqmusic] 解析入参 parse.c.g/5 1119字 可用=true flags=FTTF
 * </pre>
 */
final class Diag {


    private static final int MAX_BYTES = 512 * 1024;
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final String PROC = shortProc();

    private Diag() {
    }

    private static final java.util.Set<String> ONCE =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** 同样内容只写一次 */
    static void once(String key, String msg) {
        if (ONCE.add(key)) {
            w(msg);
        }
    }

    static void w(String msg) {
        try {
            File f = Paths.file("diag.txt");
            if (f == null) {
                return;
            }
            File dir = f.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                return;
            }
            if (f.length() > MAX_BYTES) {
                f.delete();                 // 超限就重来，别把存储写满
            }
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                out.write((FMT.format(new Date()) + " [" + PROC + "] " + msg + "\n").getBytes("UTF-8"));
                out.flush();
            } finally {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 进程名（:sub 部分），用于区分是主进程还是播放器进程。 */
    private static String shortProc() {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream("/proc/self/cmdline");
            byte[] buf = new byte[128];
            int n = in.read(buf);
            if (n <= 0) {
                return "pid" + android.os.Process.myPid();
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                if (buf[i] == 0) {
                    break;
                }
                sb.append((char) buf[i]);
            }
            String p = sb.toString();
            int c = p.indexOf(':');
            return c < 0 ? "main" : p.substring(c + 1);
        } catch (Throwable t) {
            return "pid" + android.os.Process.myPid();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}

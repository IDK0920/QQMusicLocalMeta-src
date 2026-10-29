package com.dsh.qqmlocal;

/** 小工具。 */
final class AdHoc {

    private static final String[] AUDIO_EXT = {
            ".flac", ".mp3", ".m4a", ".wav", ".ogg", ".ape", ".aac", ".wma", ".dsf", ".dff"
    };

    private AdHoc() {
    }

    static String truncate(String s, int max) {
        if (s == null) {
            return "null";
        }
        String t = s.replace('\n', ' ').replace('\r', ' ');
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /** 是不是音频文件路径（只看后缀，不碰文件系统）。 */
    static boolean looksAudio(String path) {
        if (path == null) {
            return false;
        }
        String p = path.toLowerCase();
        for (int i = 0; i < AUDIO_EXT.length; i++) {
            if (p.endsWith(AUDIO_EXT[i])) {
                return true;
            }
        }
        return false;
    }
}

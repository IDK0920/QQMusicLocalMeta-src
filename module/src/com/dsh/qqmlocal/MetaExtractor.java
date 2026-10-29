package com.dsh.qqmlocal;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;

/**
 * 纯 Java 的音频内嵌元数据解析（不依赖 Android，可在电脑上直接跑测试）。
 * <p>
 * 支持：
 * <ul>
 *   <li>FLAC：VORBIS_COMMENT（标题/艺术家/专辑/歌词）+ PICTURE（封面）</li>
 *   <li>ID3v2.3 / ID3v2.4（MP3）：TIT2/TPE1/TALB + APIC（封面）+ USLT（歌词）</li>
 * </ul>
 * 只读文件头部的元数据区，不解码音频。
 */
public final class MetaExtractor {

    /** 解析结果。cover 可能为 null（无封面），lyrics 可能为空串（无歌词）。 */
    public static final class Meta {
        public byte[] cover;
        public String lyrics = "";
        public String title = "";
        public String artist = "";
        public String album = "";
        /** 解析过程中遇到的问题，给日志用 */
        public String note = "";
        /** 当前 cover 是否是 FLAC 的「封面(3)」类型 */
        public boolean coverIsFront;
        /** 时长毫秒（FLAC 能从 STREAMINFO 算，其他容器暂为 0） */
        public long durationMs;
        /** 标签结构诊断信息（容器 / 块类型 / 所有标签键 / ID3 帧），排查「明明有却解不出」用 */
        public String diag = "";

        public boolean hasLyrics() {
            return lyrics != null && lyrics.trim().length() > 0;
        }

        public boolean hasCover() {
            return cover != null && cover.length > 0;
        }

        @Override
        public String toString() {
            return "title=" + title + " artist=" + artist + " album=" + album
                    + " cover=" + (hasCover() ? cover.length + "B" : "none")
                    + " lyrics=" + (hasLyrics() ? lyrics.length() + "chars" : "none")
                    + (note.isEmpty() ? "" : " note=" + note);
        }
    }

    private static final int FLAC_TYPE_VORBIS_COMMENT = 4;
    private static final int FLAC_TYPE_PICTURE = 6;
    private static final int PICTURE_FRONT_COVER = 3;

    private MetaExtractor() {
    }

    public static Meta parse(File file) {
        Meta meta = new Meta();
        if (file == null || !file.isFile()) {
            meta.note = "文件不存在";
            return meta;
        }
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            byte[] magic = new byte[4];
            raf.readFully(magic);
            String head = new String(magic, "ISO-8859-1");
            raf.seek(0);
            if ("fLaC".equals(head)) {
                parseFlac(raf, meta);
            } else if (head.startsWith("ID3")) {
                parseId3(raf, meta);
                // ID3 头 + 尾部 fLaC
                long after = id3TotalSize(raf);
                if (after > 0) {
                    raf.seek(after);
                    byte[] m2 = new byte[4];
                    if (raf.read(m2) == 4 && "fLaC".equals(new String(m2, "ISO-8859-1"))) {
                        parseFlac(raf, meta);
                    }
                }
            } else {
                byte[] b = new byte[8];
                raf.seek(4);
                if (raf.read(b) == 8 && new String(b, "ISO-8859-1").contains("ftyp")) {
                    meta.note = "MP4/M4A 暂未支持";
                } else {
                    meta.note = "未知容器: " + head;
                }
            }
        } catch (Throwable t) {
            meta.note = "解析异常: " + t;
        } finally {
            closeQuietly(raf);
        }
        return meta;
    }

    // ------------------------------------------------------------------ FLAC

    private static void parseFlac(RandomAccessFile raf, Meta meta) throws IOException {
        raf.seek(4); // 跳过 fLaC
        while (true) {
            int b = raf.read();
            if (b < 0) {
                break;
            }
            boolean last = (b & 0x80) != 0;
            int type = b & 0x7F;
            int len = (raf.read() << 16) | (raf.read() << 8) | raf.read();
            if (len < 0) {
                break;
            }
            long start = raf.getFilePointer();
            meta.diag += "flac块" + type + "(" + len + ") ";
            if (type == 0 && len >= 18) {
                byte[] si = new byte[len];
                raf.readFully(si);
                long dur = streamInfoDuration(si);
                if (dur > 0) {
                    meta.durationMs = dur;
                }
                raf.seek(start);
            }
            if (type == FLAC_TYPE_VORBIS_COMMENT) {
                byte[] data = new byte[len];
                raf.readFully(data);
                parseVorbisComment(data, meta);
            } else if (type == FLAC_TYPE_PICTURE) {
                byte[] data = new byte[len];
                raf.readFully(data);
                parseFlacPicture(data, meta);
            }
            // 必须精确跳到块尾（有的实现 len 与实际不符时以 len 为准）
            raf.seek(start + len);
            if (last) {
                break;
            }
        }
    }

    private static void parseVorbisComment(byte[] data, Meta meta) {
        try {
            int[] pos = {0};
            byte[] vendor = readVcString(data, pos);
            if (vendor == null) {
                return;
            }
            int count = readIntLe(data, pos);
            if (count < 0 || count > 100000) {
                return;
            }
            for (int i = 0; i < count; i++) {
                byte[] raw = readVcString(data, pos);
                if (raw == null) {
                    break;
                }
                String kv = new String(raw, "UTF-8");
                int eq = kv.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = kv.substring(0, eq).toUpperCase();
                String value = kv.substring(eq + 1);
                meta.diag += key + "(" + value.length() + ") ";
                if (isLyricKey(key)) {
                    if (meta.lyrics.isEmpty() || looksLrc(value)) {
                        meta.lyrics = value;
                    }
                } else if ("TITLE".equals(key) && meta.title.isEmpty()) {
                    meta.title = value;
                } else if ("ARTIST".equals(key) && meta.artist.isEmpty()) {
                    meta.artist = value;
                } else if ("ALBUM".equals(key) && meta.album.isEmpty()) {
                    meta.album = value;
                } else if (looksLrc(value)) {
                    // 键名不认识，但内容明显是带时间轴的歌词 —— 收下
                    if (meta.lyrics.isEmpty()) {
                        meta.lyrics = value;
                    }
                } else if ("METADATA_BLOCK_PICTURE".equals(key) && !meta.hasCover()) {
                    // 有些写入器把封面塞在 VORBIS_COMMENT 里，base64 编码
                    byte[] img = decodeBase64(value);
                    if (img != null && img.length > 0) {
                        byte[] pic = extractPictureData(img);
                        meta.cover = (pic != null) ? pic : img;
                    }
                }
            }
        } catch (Throwable t) {
            meta.note = "vorbis comment: " + t;
        }
    }

    /** 从 STREAMINFO 算时长：sampleRate(20) + channels(3) + bps(5) + totalSamples(36)。 */
    private static long streamInfoDuration(byte[] b) {
        try {
            if (b.length < 18) {
                return 0;
            }
            int i = 10;
            long sampleRate = ((b[i] & 0xFFL) << 12) | ((b[i + 1] & 0xFFL) << 4)
                    | ((b[i + 2] & 0xFFL) >> 4);
            long totalSamples = ((b[i + 3] & 0x0FL) << 32)
                    | ((b[i + 4] & 0xFFL) << 24) | ((b[i + 5] & 0xFFL) << 16)
                    | ((b[i + 6] & 0xFFL) << 8) | (b[i + 7] & 0xFFL);
            if (sampleRate <= 0 || totalSamples <= 0) {
                return 0;
            }
            return totalSamples * 1000L / sampleRate;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 常见歌词键。中文标签软件五花八门，这里尽量放宽。 */
    private static boolean isLyricKey(String key) {
        String k = key.replace(" ", "").replace("_", "").toUpperCase();
        return k.equals("LYRICS") || k.equals("LYRIC") || k.equals("UNSYNCEDLYRICS")
                || k.equals("SYNCEDLYRICS") || k.equals("LRC") || k.equals("LYRICTEXT")
                || k.equals("LYRICSCONTENT") || k.equals("\u6b4c\u8bcd") || k.equals("\u6b4c\u8a5e")
                || k.startsWith("LYRICS:") || k.startsWith("LYRIC:");
    }

    /** 看起来是不是 LRC（有时间轴）。用于「键名不认识但内容明显是歌词」的兜底。 */
    private static boolean looksLrc(String v) {
        if (v == null || v.length() < 8) {
            return false;
        }
        int i = v.indexOf('[');
        return i >= 0 && i + 7 < v.length() && Character.isDigit(v.charAt(i + 1))
                && v.indexOf(':', i) > 0;
    }

    /** FLAC PICTURE 块：取「封面(3)」优先，否则取第一张图。 */
    private static void parseFlacPicture(byte[] data, Meta meta) {
        try {
            int[] pos = {0};
            int picType = readIntBe(data, pos);
            int mimeLen = readIntBe(data, pos);
            pos[0] += mimeLen;
            int descLen = readIntBe(data, pos);
            pos[0] += descLen;
            pos[0] += 16; // width/height/depth/colors
            int dataLen = readIntBe(data, pos);
            if (dataLen <= 0 || pos[0] + dataLen > data.length) {
                return;
            }
            byte[] img = new byte[dataLen];
            System.arraycopy(data, pos[0], img, 0, dataLen);
            // 有 front cover(3) 就用它，否则保留第一张
            if (!meta.hasCover() || (picType == PICTURE_FRONT_COVER && !meta.coverIsFront)) {
                meta.cover = img;
                meta.coverIsFront = (picType == PICTURE_FRONT_COVER);
            }
        } catch (Throwable t) {
            meta.note = "flac picture: " + t;
        }
    }

    /** 从完整 PICTURE 块字节里再切一次图片数据（供 base64 那条路用）。 */
    private static byte[] extractPictureData(byte[] block) {
        try {
            int[] pos = {0};
            readIntBe(block, pos);
            int mimeLen = readIntBe(block, pos);
            pos[0] += mimeLen;
            int descLen = readIntBe(block, pos);
            pos[0] += descLen;
            pos[0] += 16;
            int dataLen = readIntBe(block, pos);
            if (dataLen > 0 && pos[0] + dataLen <= block.length) {
                byte[] img = new byte[dataLen];
                System.arraycopy(block, pos[0], img, 0, dataLen);
                return img;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------ ID3

    private static long id3TotalSize(RandomAccessFile raf) throws IOException {
        raf.seek(0);
        byte[] h = new byte[10];
        if (raf.read(h) != 10) {
            return -1;
        }
        int size = ((h[6] & 0x7F) << 21) | ((h[7] & 0x7F) << 14)
                | ((h[8] & 0x7F) << 7) | (h[9] & 0x7F);
        boolean footer = (h[3] == 4) && ((h[5] & 0x10) != 0);
        return 10L + size + (footer ? 10 : 0);
    }

    private static void parseId3(RandomAccessFile raf, Meta meta) throws IOException {
        long total = id3TotalSize(raf);
        if (total <= 10) {
            meta.note = "ID3 头异常";
            return;
        }
        raf.seek(0);
        byte[] all = new byte[(int) Math.min(total, 16 * 1024 * 1024)];
        raf.readFully(all);
        int major = all[3] & 0xFF;
        boolean syncsafe = major >= 4;
        int p = 10;
        while (p + 10 <= all.length) {
            String id = new String(all, p, 4, "ISO-8859-1");
            if (id.charAt(0) == 0) {
                break;
            }
            int size;
            if (syncsafe) {
                size = ((all[p + 4] & 0x7F) << 21) | ((all[p + 5] & 0x7F) << 14)
                        | ((all[p + 6] & 0x7F) << 7) | (all[p + 7] & 0x7F);
            } else {
                size = ((all[p + 4] & 0xFF) << 24) | ((all[p + 5] & 0xFF) << 16)
                        | ((all[p + 6] & 0xFF) << 8) | (all[p + 7] & 0xFF);
            }
            if (size <= 0 || p + 10 + size > all.length) {
                break;
            }
            int body = p + 10;
            meta.diag += "id3帧" + id + "(" + size + ") ";
            try {
                if ("APIC".equals(id) && !meta.hasCover()) {
                    meta.cover = parseApic(all, body, size);
                } else if ("USLT".equals(id) && !meta.hasLyrics()) {
                    meta.lyrics = parseUslt(all, body, size);
                } else if ("TIT2".equals(id) && meta.title.isEmpty()) {
                    meta.title = parseTextFrame(all, body, size);
                } else if ("TPE1".equals(id) && meta.artist.isEmpty()) {
                    meta.artist = parseTextFrame(all, body, size);
                } else if ("TALB".equals(id) && meta.album.isEmpty()) {
                    meta.album = parseTextFrame(all, body, size);
                } else if ("TXXX".equals(id) && meta.lyrics.isEmpty()) {
                    String v = parseTxxx(all, body, size);
                    if (v != null && v.length() > 0) {
                        meta.lyrics = v;
                    }
                }
            } catch (Throwable t) {
                meta.note = "frame " + id + ": " + t;
            }
            p = body + size;
        }
    }

    /**
     * TXXX：一个「描述 + 值」的扩展文本帧。很多标签软件把歌词塞在
     * TXXX(desc=LYRICS) 里，标准字段反而不写。
     */
    private static String parseTxxx(byte[] all, int off, int len) {
        try {
            int enc = all[off] & 0xFF;
            int p = skipString(all, off + 1, off + len, enc);
            if (p < 0) {
                return null;
            }
            int descEnd = p - 1;
            int valStart = p;
            // 描述串在 enc 为 0/3 时以单个 0 结尾；1/2 时以 00 00 结尾
            String desc = decode(all, off + 1, Math.max(0, descEnd - (off + 1)), enc);
            String value = decode(all, valStart, off + len - valStart, enc);
            if (desc == null) {
                return null;
            }
            String d = desc.replace("\u0000", "").trim().toUpperCase();
            if (d.contains("LYRIC") || d.contains("LRC")) {
                return value;
            }
            // 描述不认识但内容像 LRC 也收
            return looksLrc(value) ? value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] parseApic(byte[] all, int off, int len) {
        int enc = all[off] & 0xFF;
        int p = off + 1;
        int mimeEnd = indexOf(all, p, off + len, (byte) 0);
        if (mimeEnd < 0) {
            return null;
        }
        p = mimeEnd + 1;
        if (p >= off + len) {
            return null;
        }
        int picType = all[p] & 0xFF;
        p++;
        p = skipString(all, p, off + len, enc);
        if (p < 0) {
            return null;
        }
        byte[] img = new byte[off + len - p];
        System.arraycopy(all, p, img, 0, img.length);
        return picType == PICTURE_FRONT_COVER || img.length > 0 ? img : null;
    }

    private static String parseUslt(byte[] all, int off, int len) {
        int enc = all[off] & 0xFF;
        int p = off + 4; // 跳过 encoding + 3 字节语言
        p = skipString(all, p, off + len, enc);
        if (p < 0) {
            return "";
        }
        return decode(all, p, off + len - p, enc);
    }

    private static String parseTextFrame(byte[] all, int off, int len) {
        int enc = all[off] & 0xFF;
        return decode(all, off + 1, len - 1, enc);
    }

    // ------------------------------------------------------------------ 工具

    private static int skipString(byte[] d, int p, int end, int enc) {
        if (enc == 1 || enc == 2) {
            while (p + 1 < end) {
                if (d[p] == 0 && d[p + 1] == 0) {
                    return p + 2;
                }
                p += 2;
            }
            return -1;
        }
        int q = indexOf(d, p, end, (byte) 0);
        return q < 0 ? -1 : q + 1;
    }

    private static int indexOf(byte[] d, int from, int end, byte needle) {
        for (int i = from; i < end; i++) {
            if (d[i] == needle) {
                return i;
            }
        }
        return -1;
    }

    private static String decode(byte[] d, int off, int len, int enc) {
        if (len <= 0) {
            return "";
        }
        try {
            if (enc == 1) {
                // UTF-16 with BOM
                if (len >= 2) {
                    if ((d[off] & 0xFF) == 0xFE && (d[off + 1] & 0xFF) == 0xFF) {
                        return new String(d, off + 2, len - 2, "UTF-16BE");
                    }
                    if ((d[off] & 0xFF) == 0xFF && (d[off + 1] & 0xFF) == 0xFE) {
                        return new String(d, off + 2, len - 2, "UTF-16LE");
                    }
                }
                return new String(d, off, len, "UTF-16LE");
            }
            if (enc == 2) {
                return new String(d, off, len, "UTF-16BE");
            }
            if (enc == 3) {
                return new String(d, off, len, "UTF-8");
            }
            return new String(d, off, len, "ISO-8859-1");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    private static byte[] readVcString(byte[] d, int[] pos) {
        int p = pos[0];
        if (p + 4 > d.length) {
            return null;
        }
        int len = readIntLe(d, pos);
        if (len < 0 || pos[0] + len > d.length) {
            return null;
        }
        byte[] out = new byte[len];
        System.arraycopy(d, pos[0], out, 0, len);
        pos[0] += len;
        return out;
    }

    private static int readIntLe(byte[] d, int[] pos) {
        int p = pos[0];
        if (p + 4 > d.length) {
            pos[0] = d.length;
            return -1;
        }
        int v = (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8)
                | ((d[p + 2] & 0xFF) << 16) | ((d[p + 3] & 0xFF) << 24);
        pos[0] = p + 4;
        return v;
    }

    private static int readIntBe(byte[] d, int[] pos) {
        int p = pos[0];
        if (p + 4 > d.length) {
            pos[0] = d.length;
            return -1;
        }
        int v = ((d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16)
                | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
        pos[0] = p + 4;
        return v;
    }

    private static final String B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    /** 纯 Java base64 解码（不依赖 android.util.Base64，保证电脑上能测）。 */
    private static byte[] decodeBase64(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder clean = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=' || B64.indexOf(c) >= 0) {
                clean.append(c);
            }
        }
        int n = clean.length();
        if (n < 4 || n % 4 != 0) {
            return null;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(n * 3 / 4);
        int buf = 0;
        int bits = 0;
        for (int i = 0; i < n; i++) {
            char c = clean.charAt(i);
            if (c == '=') {
                break;
            }
            buf = (buf << 6) | B64.indexOf(c);
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buf >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    private static void closeQuietly(RandomAccessFile raf) {
        if (raf != null) {
            try {
                raf.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 给上层用：没有时长信息时的兜底（按 200 秒铺）。 */
    public static String ensureLrc(String lyrics) {
        return ensureLrc(lyrics, 0);
    }

    /**
     * 把「一句一行」的纯文本歌词补成 LRC。已有时间轴则原样返回。
     * <p>
     * 没有时间轴时按总时长均匀铺开 —— 比固定「2 行/秒」靠谱得多，
     * 至少歌词会和歌曲长度对齐。
     */
    public static String ensureLrc(String lyrics, long durationMs) {
        return ensureLrc(lyrics, durationMs, "", "", "");
    }

    /** 带元数据头的版本：很多解析器要先看到 [ti:]/[ar:]/[al:] 才肯认。 */
    public static String ensureLrc(Meta meta) {
        if (meta == null) {
            return "";
        }
        return ensureLrc(meta.lyrics, meta.durationMs, meta.title, meta.artist, meta.album);
    }

    private static String ensureLrc(String lyrics, long durationMs,
                                   String title, String artist, String album) {
        if (lyrics == null || lyrics.length() == 0) {
            return "";
        }
        String t = lyrics.replace("\r\n", "\n").replace('\r', '\n');
        if (t.matches("(?s).*\\[\\d{1,2}:\\d{1,2}(\\.\\d+)?\\].*")) {
            return t; // 已经是 LRC
        }
        StringBuilder h = new StringBuilder();
        if (title != null && title.length() > 0) {
            h.append("[ti:").append(title.replace(']', ' ')).append("]\n");
        }
        if (artist != null && artist.length() > 0) {
            h.append("[ar:").append(artist.replace(']', ' ')).append("]\n");
        }
        if (album != null && album.length() > 0) {
            h.append("[al:").append(album.replace(']', ' ')).append("]\n");
        }
        h.append("[by:qqmlocal]\n");
        final String head = h.toString();
        String[] lines = t.split("\n");
        int total = 0;
        for (String line : lines) {
            if (line.trim().length() > 0) {
                total++;
            }
        }
        if (total == 0) {
            return "";
        }
        long dur = durationMs > 1000 ? durationMs : 200000L;
        // 留 5% 尾巴，避免最后一行刚好卡在结束点
        long span = (long) (dur * 0.95);
        long step = Math.max(1L, span / total);
        StringBuilder sb = new StringBuilder();
        sb.append(head);
        int i = 0;
        for (String line : lines) {
            if (line.trim().length() == 0) {
                continue;
            }
            long at = i * step;
            sb.append(String.format("[%02d:%02d.%02d]",
                    at / 60000, (at / 1000) % 60, (at % 1000) / 10));
            sb.append(line).append('\n');
            i++;
        }
        return sb.toString();
    }

    /** 简单工具：推断图片 MIME，写缓存文件时用。 */
    public static String imageExt(byte[] b) {
        if (b == null || b.length < 4) {
            return "jpg";
        }
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "png";
        }
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') {
            return "gif";
        }
        if (b.length > 12 && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "webp";
        }
        return "jpg";
    }
}

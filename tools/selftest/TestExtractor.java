import com.dsh.qqmlocal.MetaExtractor.Meta;
import com.dsh.qqmlocal.MetaExtractor;

import java.io.File;

/** 用真实结构的 FLAC / ID3v2 文件验证 MetaExtractor。 */
public class TestExtractor {

    private static int fail = 0;

    private static void check(String what, boolean ok, String detail) {
        System.out.printf("%s %-42s %s%n", ok ? "ok  " : "FAIL", what, detail);
        if (!ok) {
            fail++;
        }
    }

    private static String AdHocTrunc(String s) {
        return s == null ? "null" : (s.length() > 90 ? s.substring(0, 90) + "…" : s);
    }

    private static boolean isPng(byte[] b) {
        return b != null && b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P';
    }

    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0] : "work/fixture";

        System.out.println("== FLAC（封面 + 歌词 + 标题）==");
        Meta m = MetaExtractor.parse(new File(dir, "flac_ok.flac"));
        System.out.println("   " + m);
        check("标题解析", "测试曲目".equals(m.title), m.title);
        check("艺术家解析", "测试歌手".equals(m.artist), m.artist);
        check("专辑解析", "测试专辑".equals(m.album), m.album);
        check("歌词解析", m.hasLyrics() && m.lyrics.contains("第二行歌词"),
                m.hasLyrics() ? m.lyrics.length() + " chars" : "none");
        check("封面解析（PNG 魔数）", isPng(m.cover),
                m.hasCover() ? m.cover.length + "B" : "none");
        check("封面被识别为 front cover", m.coverIsFront, String.valueOf(m.coverIsFront));

        System.out.println("== FLAC（无歌词）==");
        Meta m2 = MetaExtractor.parse(new File(dir, "flac_nolyric.flac"));
        System.out.println("   " + m2);
        check("无歌词时 hasLyrics=false", !m2.hasLyrics(), "lyrics=" + m2.lyrics);
        check("封面仍然解析", isPng(m2.cover), m2.hasCover() ? m2.cover.length + "B" : "none");

        System.out.println("== ID3v2.3 MP3（APIC + USLT）==");
        Meta m3 = MetaExtractor.parse(new File(dir, "id3_ok.mp3"));
        System.out.println("   " + m3);
        check("标题解析", "测试曲目".equals(m3.title), m3.title);
        check("艺术家解析", "测试歌手".equals(m3.artist), m3.artist);
        check("歌词(USLT)解析", m3.hasLyrics() && m3.lyrics.contains("第三行歌词"),
                m3.hasLyrics() ? m3.lyrics.length() + " chars" : "none");
        check("封面(APIC)解析", isPng(m3.cover), m3.hasCover() ? m3.cover.length + "B" : "none");

        System.out.println("== 非标准标签键（现实里常见）==");
        Meta m6 = MetaExtractor.parse(new File(dir, "flac_lrckey.flac"));
        check("键名 LRC 也能认", m6.hasLyrics() && m6.lyrics.contains("第二行歌词"),
                m6.hasLyrics() ? "ok" : "none");
        Meta m7 = MetaExtractor.parse(new File(dir, "flac_unknownkey.flac"));
        check("未知键名但内容像 LRC 也认", m7.hasLyrics() && m7.lyrics.contains("第二行歌词"),
                m7.hasLyrics() ? "ok" : "none");
        Meta m8 = MetaExtractor.parse(new File(dir, "id3_txxx.mp3"));
        check("ID3 的 TXXX(desc=LYRICS) 也认", m8.hasLyrics() && m8.lyrics.contains("第二行歌词"),
                m8.hasLyrics() ? "ok" : "none");

        check("纯文本生成时带 [by:] 头",
                MetaExtractor.ensureLrc("甲\n乙", 1000L).startsWith("[by:"),
                MetaExtractor.ensureLrc("甲\n乙", 1000L).split("\n")[0]);
        check("已有时间轴时原样返回（不硬加头）",
                MetaExtractor.ensureLrc("[00:01.00]a\n[00:02.00]b", 1000L).startsWith("[00:01.00]"),
                "ok");

        System.out.println("== 标签结构诊断（排查用）==");
        check("FLAC 诊断含块类型", m.diag.contains("flac块"), AdHocTrunc(m.diag));
        check("ID3 诊断含帧", m3.diag.contains("id3帧"), AdHocTrunc(m3.diag));

        System.out.println("== 纯文本歌词补 LRC 时间轴 ==");
        String lrc = MetaExtractor.ensureLrc("第一句\n第二句");
        check("已是 LRC 则不动",
                MetaExtractor.ensureLrc("[00:01.00]a\n[00:02.00]b").startsWith("[00:01.00]"),
                "ok");
        check("纯文本被补上时间戳", lrc.contains("[00:00.00]第一句"), lrc.replace("\n", " | "));

        System.out.println("== 纯文本按总时长铺时间轴 ==");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 53; i++) { many.append("第").append(i).append("行\n"); }
        String spread = MetaExtractor.ensureLrc(many.toString(), 240000L);
        String lastLine = spread.trim().substring(spread.trim().lastIndexOf('['));
        check("53 行铺满 240 秒（末行应在 220s 之后）", lastLine.compareTo("[03:40") > 0, lastLine);

        System.out.println("== 异常输入不崩 ==");
        Meta m4 = MetaExtractor.parse(new File(dir, "不存在文件.flac"));
        check("不存在文件返回空结果", !m4.hasLyrics() && !m4.hasCover(), m4.note);
        Meta m5 = MetaExtractor.parse(null);
        check("null 输入不崩", m5 != null, m5.note);

        System.out.println();
        System.out.println(fail == 0 ? "全部通过" : "失败 " + fail + " 项");
        if (fail != 0) {
            System.exit(1);
        }
    }
}

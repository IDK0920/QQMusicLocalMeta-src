#!/usr/bin/env bash
# 电脑上验证核心逻辑（不需要手机）：
#   1. MetaExtractor：造真实结构的 FLAC/ID3 文件，验证内嵌封面/歌词解析
#   2. AudioFinder：验证「按文件名反查本地音频」，含空格/连接符差异
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${ROOT}/.selftest"
rm -rf "$OUT"; mkdir -p "$OUT"
python3 "$ROOT/tools/mkfixture.py" >/dev/null

SRC="$ROOT/module/src/com/dsh/qqmlocal"

echo "########## MetaExtractor ##########"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  "${ROOT}/tools/selftest/TestExtractor.java" \
  "$SRC/MetaExtractor.java"
java -cp "$OUT" TestExtractor "${ROOT}/work/fixture"

echo
echo "########## AudioFinder ##########"
javac -nowarn -encoding UTF-8 -d "$OUT" \
  "${ROOT}/tools/selftest/TestAudioFinder.java" \
  "$SRC/AudioFinder.java" "$SRC/AdHoc.java"
java -cp "$OUT" com.dsh.qqmlocal.TestAudioFinder "${ROOT}/work/fixture/tree"

echo
echo "########## 真实文件（other/ 下用户上传的那首）##########"
REAL="$ROOT/other/鹿乃(かの)-高嶺の花子さん.flac"
if [ -f "$REAL" ]; then
  cat > "$OUT/Real.java" <<'JAVA'
import com.dsh.qqmlocal.MetaExtractor;
import java.io.File;
public class Real {
    public static void main(String[] a) {
        MetaExtractor.Meta m = MetaExtractor.parse(new File(a[0]));
        System.out.println("  title=" + m.title + " artist=" + m.artist);
        System.out.println("  cover=" + (m.hasCover() ? m.cover.length + "B" : "none")
            + " lyrics=" + (m.hasLyrics() ? m.lyrics.length() + " 字" : "NONE"));
        if (!m.hasCover() || !m.hasLyrics()) { System.exit(1); }
    }
}
JAVA
  javac -nowarn -encoding UTF-8 -d "$OUT" "$OUT/Real.java" "$SRC/MetaExtractor.java"
  java -cp "$OUT" Real "$REAL"
else
  echo "  (跳过：没有 other/*.flac)"
fi

echo
echo "全部测试通过"

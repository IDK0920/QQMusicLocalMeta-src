#!/usr/bin/env bash
# 构建「QQ音乐本地元数据」LSPosed 模块（不依赖 gradle）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ROOT}/.toolchain/sdk"
BT="${SDK}/build-tools/34.0.0"
AJAR="${SDK}/platforms/android-34/android.jar"
XAPI="${ROOT}/libs/api-82.jar"
R8="${ROOT}/libs/r8.jar"
MOD="${ROOT}/module"
BUILD="${ROOT}/build"
OUT="${ROOT}/dist"
NAME="QQMusicLocalMeta"
VER="1.72"
MIN_SDK=26
TARGET_SDK=34

for f in "$BT/aapt2" "$BT/zipalign" "$BT/apksigner" "$AJAR" "$XAPI"; do
  [ -e "$f" ] || { echo "[x] 缺少 $f，先跑 tools/setup.sh" >&2; exit 1; }
done

rm -rf "$BUILD" "$OUT"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$OUT"

echo "[1/6] aapt2 link"
"$BT/aapt2" link -o "$BUILD/base.apk" -I "$AJAR" \
  --manifest "$MOD/AndroidManifest.xml" \
  --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
  --version-code 1 --version-name "$VER"

echo "[2/6] javac"
find "$MOD/src" -name '*.java' > "$BUILD/sources.txt"
javac -nowarn -source 8 -target 8 -encoding UTF-8 \
  -classpath "$AJAR:$XAPI" -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "[3/6] d8 -> classes.dex"
CLASSES=$(find "$BUILD/classes" -name '*.class')
# shellcheck disable=SC2086
if [ -f "$R8" ]; then
  java -cp "$R8" com.android.tools.r8.D8 --release --min-api $MIN_SDK \
    --lib "$AJAR" --output "$BUILD/dex" $CLASSES
else
  "$BT/d8" --release --min-api $MIN_SDK --lib "$AJAR" --output "$BUILD/dex" $CLASSES
fi

echo "[4/6] 打包"
python3 "$ROOT/tools/pack.py" "$BUILD/base.apk" "$BUILD/unsigned.apk" \
  "$BUILD/dex/classes.dex" "$MOD/assets"

echo "[5/6] zipalign"
"$BT/zipalign" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "[6/6] 签名"
KS="$ROOT/keystore/qmc.jks"
mkdir -p "$ROOT/keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias qmc -keyalg RSA -keysize 2048 \
    -validity 36500 -storepass android -keypass android \
    -dname "CN=QQMusicLocalMeta, OU=dsh, O=dsh, L=NA, ST=NA, C=CN" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT/${NAME}-v${VER}.apk" "$BUILD/aligned.apk"

echo
ls -l "$OUT"
"$BT/apksigner" verify "$OUT/${NAME}-v${VER}.apk" && echo "签名校验通过"
unzip -l "$OUT/${NAME}-v${VER}.apk"

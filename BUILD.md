# 自行编译

需要：`aapt2`、`javac`（JDK 8+）、`zipalign`、`apksigner`（Android SDK build-tools），
以及 `r8.jar`（跑 `d8` 用，**未随仓库提供**，从 Android SDK build-tools 里取）。

```bash
# 改 build.sh 里的 AAPT2 / R8_JAR 指向你的 SDK
bash build.sh        # 产物: dist/QQMusicLocalMeta-v<VER>.apk
bash tools/test.sh   # 自测（纯 Java，无需手机）
```

签名用 `keystore/qmc.jks`（自签名，仅供自用；转发或上架请换自己的密钥）。

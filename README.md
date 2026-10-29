# QQMusicLocalMeta

让 **QQ音乐 Android** 显示你自己导入的本地音乐中**内嵌的歌词**——即使这首歌在它的云端曲库里根本查不到。

> Xposed / LSPosed 模块 · 目标：`com.tencent.qqmusic` 20.9.5.8（versionCode 7508）
> 只读取你自己的本地音频文件，**不涉及 VIP / 付费 / DRM 内容**。

![歌词页](docs/images/lyrics.jpg)

---

## 目录

- [它解决什么问题](#它解决什么问题)
- [功能](#功能)
- [安装](#安装)
- [歌词要求](#歌词要求)
- [已知限制](#已知限制)
- [原理简述](#原理简述)
- [目录结构](#目录结构)
- [自行构建](#自行构建)
- [常见问题](#常见问题)
- [免责声明](#免责声明)

---

## 它解决什么问题

QQ音乐 识别本地歌曲靠**文件名**（`歌手 - 歌名.flac`），能正确显示歌名歌手；但歌词是它去云端曲库拉的。

如果你导入的歌**不在云端曲库**（同人曲、稀有版本、自制混音、无损转录……），结果就是：

- 歌名歌手正常
- **歌词一片空白**，歌词页只剩一个「作曲 / 语种 / 流派」的信息面板

QQ音乐 自己其实**已经读了**文件里内嵌的歌词标签——但它解析完就丢掉，因为「云端没有这首歌」。
本模块做的事就是：**在它解析成功的那一刻把歌词接住，再塞回它的歌词管线**。

## 功能

| 内容 | 来源 | 状态 |
|---|---|---|
| 歌名 / 歌手 / 专辑名 | QQ音乐 自己按文件名解析 | ✅ 原生支持，模块不参与 |
| **歌词** | 音频文件内嵌的歌词标签 | ✅ 本模块提供 |
| 封面 | — | ❌ 未支持（见[已知限制](#已知限制)） |

歌词出现在两处，均已验证：

- **迷你歌词**：播放页下方那一行，跟随播放滚动
- **独立歌词页**：播放页左右滑动切换的整页歌词

支持两种歌词形态：

- **带时间轴的 LRC** → 原样使用
- **纯文本（一行一句，无时间戳）** → 按歌曲时长自动均匀铺时间轴，并补上 `[ti:][ar:][al:]` 头

## 安装

前提：手机已 root 并装好 **Magisk + Zygisk + LSPosed**。

1. 安装 `dist/QQMusicLocalMeta-v1.72.apk`
2. LSPosed 里**启用**本模块，作用域**只勾选「QQ音乐」**
3. **强行停止 QQ音乐**后重新打开（不重启手机的话这一步必须做）
4. 播放任意本地 FLAC，进歌词页确认

## 歌词要求

模块读取音频文件内嵌的歌词标签：

| 容器 | 支持的标签 |
|---|---|
| **FLAC** | `VORBIS_COMMENT` 的 `LYRICS`（也认 `LYRIC` / `UNSYNCEDLYRICS` / `LRC` / `歌词` 等常见写法）、`TXXX(desc=LYRICS)` |
| **MP3 / ID3** | `USLT`（非同步歌词） |

写入工具不限（foobar2000、Mp3tag、metaflac、MusicBrainz Picard 都行）。示例：

```bash
metaflac --set-tag="LYRICS=$(cat song.lrc)" "歌手 - 歌名.flac"
```

## 已知限制

1. **「云端歌 → 上一首」这条路径**，独立歌词页会显示成「歌曲信息」面板（作曲/语种/流派）。
   此时**迷你歌词行仍然正常**。其它路径（点歌播放、下一首、启动后直接播本地歌）都正常。
2. **封面不显示**，播放页是空唱盘。原因与所有尝试记录见
   [docs/TECHNICAL_NOTES.md](docs/TECHNICAL_NOTES.md) 第 38~44 节。
3. 只处理**本地文件**；云歌曲完全不受影响（包括它自己的双语 / 逐色歌词）。
4. 只在 `com.tencent.qqmusic` **20.9.5.8** 上验证过。类名混淆可能随版本变化。

## 原理简述

QQ音乐 的本地歌会被分配一个「伪歌曲 ID」，歌词走 `business.lyricnew` 这条管线：

```
路径解析(config.a)  →  加载任务(load.helper.a)  →  解析器(load.helper.c)
                                                        ↓
                                            返回 lyricengine.base.k 对象
                                                        ↓
                                                  歌词视图 / 歌词页
```

模块做三件事：

1. **路径钩子**：在 `config.a` 的路径方法上，若当前歌匹配到我们的本地音频文件，
   把内嵌歌词写成 `.lrc` 落到 QQ音乐 自己的缓存目录
2. **解析注入**：`load.helper.c` 的解析方法在带原生 flags 时会返回 `null`，
   模块改成用「全 false」的 flags 再解析一次，拿到可用的 `k` 对象并替换返回值
3. **空值补救**：歌词页可能先把空值绑定了（切歌瞬间），模块在主线程上重绑几次

其中第 3 点走过弯路：早期在后台线程调用 UI 方法只会抛 `CalledFromWrongThreadException`，
看起来「触发了」其实一直在空转。

> 完整逆向过程、每一次失败的路径和结论都记在 [docs/TECHNICAL_NOTES.md](docs/TECHNICAL_NOTES.md)。

## 目录结构

```
.
├── module/
│   ├── AndroidManifest.xml
│   ├── assets/xposed_init          Xposed 入口声明
│   └── src/com/dsh/qqmlocal/       21 个 Java 源文件
│       ├── XposedEntry.java        入口 / 版本横幅
│       ├── ProbeHook.java          钩子装配
│       ├── LyricHook.java          路径钩子 + 音频文件匹配
│       ├── ContentHook.java        解析器注入（核心）
│       ├── PageLyricHook.java      歌词页投递
│       ├── PageProbeHook.java      切歌后主线程重绑
│       ├── MetaExtractor.java      FLAC / ID3 标签解析（纯 Java）
│       ├── MetaCache.java          解析缓存
│       ├── AudioFinder.java        本地音频扫描与匹配
│       ├── Janitor.java            清理历史上被污染的文件
│       └── ...                     诊断 / 路径 / 日志工具
├── tools/
│   ├── pack.py / dumpclass.py / mkfixture.py
│   └── selftest/                   纯 Java 自测（真实 FLAC 解析）
├── docs/
│   ├── TECHNICAL_NOTES.md          44 节完整逆向记录
│   └── images/
├── build.sh
└── dist/                           构建产物
```

## 自行构建

需要 `aapt2`、`javac`（JDK 8+）、`zipalign`、`apksigner`（Android SDK build-tools），
以及 `r8.jar`（跑 `d8` 用，从 build-tools 里取，仓库未附）。

```bash
# 1) 在 build.sh 里改这两行指向你的 SDK
#    AAPT2=...  R8_JAR=...

bash build.sh        # → dist/QQMusicLocalMeta-v<VER>.apk
bash tools/test.sh   # 自测（无需手机，纯 Java）
```

签名用仓库里的 `keystore/qmc.jks`（自签名，仅供自用）。转发给别人或上架请换自己的密钥。

## 常见问题

**Q：装了没效果？**
先确认三件事：LSPosed 作用域勾了 QQ音乐、装完**强停过** App、日志目录里能看到版本横幅：

```
/sdcard/Android/data/com.tencent.qqmusic/files/qqmusic/qqmlocal/diag.txt
```

**Q：为什么文件写到 `Android/data/...` 而不是 `/sdcard`？**
目标 App 是 `targetSdk 30`，在 Android 11+ 上无法写任意 `/sdcard` 路径（会静默失败）。
模块早期就踩过这个坑，现在统一走 App 私有目录。

**Q：会不会影响云歌曲的歌词？**
不会。每次匹配都要求「本地文件名 == 当前歌曲名」，不匹配就完全不介入。

**Q：能显示双语歌词吗？**
内嵌标签里有 `LYRICS_ZH` 之类时，理论上可以写到 `.translrc`，但尚未实现。

## 免责声明

- 仅供**个人学习与自用**，请勿用于商业用途
- 只读取用户自己的本地文件，**不绕过任何付费、会员或 DRM 保护**
- 修改的是 App 运行时行为，可能随 App 版本更新失效
- 使用本模块产生的任何后果由使用者自行承担

## 致谢

- [LSPosed](https://github.com/LSPosed/LSPosed) / [XposedBridge](https://api.xposed.info/)
- 逆向分析：`adb`、`baksmali`、REA MCP 工具链
- 测试素材：各同人社团的无损转录作品

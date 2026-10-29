# QQ音乐本地元数据（QQMusicLocalMeta）

让 QQ音乐 显示**你自己导入的本地音乐**里的**内嵌歌词**——即使这首歌在它的云端曲库里没有。

## 一、它能做什么

| 内容 | 来源 | 状态 |
|---|---|---|
| 歌名 / 歌手 / 专辑名 | QQ音乐 自己按文件名解析（`歌手 - 歌名.flac`） | ✅ 原生支持，模块不参与 |
| **歌词** | 音频文件里**内嵌的歌词标签** | ✅ 本模块提供 |
| 封面 | — | ❌ 未支持（见第五节） |

歌词会出现在两个地方，都验证可用：

- 播放页下方那**一行迷你歌词**
- **独立歌词页**（在播放页左右滑动切换）

## 二、安装

1. 手机需要已装 **LSPosed**（Magisk + Zygisk 环境）
2. 安装 `dist/QQMusicLocalMeta-v1.72.apk`
3. 在 LSPosed 里启用本模块，**作用域只勾选「QQ音乐」**
4. **强行停止 QQ音乐**后重新打开（不重启手机的话这一步必须做）

## 三、歌词要求

模块读取音频文件里内嵌的歌词标签：

- **FLAC**：`VORBIS_COMMENT` 里的 `LYRICS`（也认 `LYRIC` / `UNSYNCEDLYRICS` / `LRC` / `歌词` 等常见 key），
  以及 `TXXX(desc=LYRICS)`
- **MP3 / 其他 ID3**：`USLT`（非同步歌词）

两种情况都能用：

- **带时间轴的 LRC** → 原样使用
- **不带时间轴的纯文本**（一行一句）→ 模块按歌曲时长自动均匀铺时间轴，
  并补上 `[ti:][ar:][al:]` 头

## 四、已知限制

1. **「云端歌 → 上一首」这一条路径**里，独立歌词页会显示成「歌曲信息」页（作曲/语种/流派），
   迷你歌词行仍然正常。其它路径（点歌播放、下一首、启动后直接播本地歌）都正常。
2. **封面不显示**：播放页是空唱盘。原因见下节。
3. 只处理**本地文件**；云歌曲完全不受影响（包括它自己的双语/逐色歌词）。
4. 不支持 VIP / 付费 / DRM 内容，本模块只读你自己的本地文件。

## 五、为什么封面做不了（技术说明）

云歌曲的封面 URL 形如 `.../photo_new/T002R150x150M000<albumMid>_1.jpg`，是用
`albumMid` 拼出来的。**未匹配云端的本地歌没有 albumMid，App 根本不会发起封面请求**，
所以「替换 URL / 替换 model」没有落点。三条路都试过：

| 思路 | 结果 |
|---|---|
| 伪造 `albumMid`（`A1` 字段/getter）让 App 发起请求 | 标记成功，但 App 拼 URL 时不读它，无请求产生 |
| 视图层按「近方形最大 ImageView」定位 | 命中的是**播放器机体 UI**，把机体画坏了 |
| 视图层按类名 `AsyncEffectImageView` 定位 | 47 个实例都设上了图，**界面无变化** |
| 拦 `AsyncEffectImageView#setPendingUrl`（URL 入口） | 挂上了，**运行期从未被调用** |
| 拦 `wrap.d#load` / `newimageglide.f` 抓 URL 提供者 | 只有桌面小组件的请求，播放页封面不走这里 |

最终判断：播放页唱盘是**自绘控件**，封面位图由内部状态直接在 `onDraw` 里画，
链路上没有可拦截的参数接缝，因此 Xposed 层无法注入封面。

如果以后要做，正确入口是从 `com.tencent.image.newimage.wrap.d#load` 的调用栈继续上溯，
定位 `playernew` 包里**接收封面 bitmap/drawable 的那个方法**，在那里注入。

## 六、调试（一般用不到）

模块会把关键事件写到自己目录（**不是** `/sdcard` 根目录，Android 11+ 写不进去）：

```
/sdcard/Android/data/com.tencent.qqmusic/files/qqmusic/qqmlocal/diag.txt
```

文件管理器进 `Android/data/com.tencent.qqmusic/files/qqmusic/`，`qrc` 文件夹的同级就能看到 `qqmlocal`。

package com.dsh.qqmlocal;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * v0.3：在 QQ音乐 算出歌词缓存路径后，把内嵌歌词放到它要找的位置。
 * <p>
 * 依据（实测 + 反汇编 lyricnew.config.a）：
 * <pre>
 *   f(J songId, s1, s2, s3, suffix):
 *       key  = a(songId,s1,s2,s3)
 *       p1   = d(hash, suffix)              // <qqmusic/qrc>/ + hash + suffix
 *       if (p1 存在 && 长度>0) return p1;     // ★ 缓存优先
 *       p2   = d(key.hashCode(), suffix)
 *       if (p2 存在 && 长度>0) return p2;
 *       return p1;
 * </pre>
 * 各后缀的负责人：
 * <pre>
 *   g(J,S,S,S,Z) → .lrc / .qrc   ← 主歌词（走 f）
 *   m(S) → .qrc      l/s/t/u(S) → .lrc
 *   j(SongInfo) → .multiex (翻译)   n → .producer   c → .ex   b → .dict
 *   k(SongInfo,I) → .multiqrc   e/o/p/r → listeningqrc/romaqrc/assistqrc/translrc
 * </pre>
 * 策略：主歌词（.lrc/.qrc）缺失时，把我们的 LRC 写到同目录同名 .lrc，
 * 并把返回值改成这个 .lrc（.qrc 是腾讯加密格式，写纯文本没用，换路径才有戏）。
 */
final class LyricHook {

    /** 主歌词后缀：只有这两个值得动手 */
    private static final String[] MAIN_SUFFIX = {".lrc", ".qrc"};

    private static Class<?> sCfgA;
    /** 最近一次成功解析到的本地音频（兜底用；SongInfo 不可用或状态被污染时救场） */
    private static volatile String LAST_AUDIO;

    /** 降噪用：记录每个 base+suffix 上一次报告过的状态 */
    private static final Map<String, String> LAST_STATE = new HashMap<String, String>();

    private static final ThreadLocal<Object> CURRENT_SONG = new ThreadLocal<Object>();
    /**
     * SongInfo -> 本地路径，**带时间戳的短 TTL 缓存**。
     * 早期是按实例永久缓存，结果 QQ音乐 复用 SongInfo 实例时，
     * 拿到的还是上一首（甚至 null）的答案 —— 切歌后 audio=null 正是这么来的。
     * 现在 1.5 秒内复用，过期就重扫，语义上「跟着当前播放走」。
     */
    private static final Map<Object, CachedPath> AUDIO_PATH = new HashMap<Object, CachedPath>();

    private static final class CachedPath {
        final String path;
        final long at;

        CachedPath(String path) {
            this.path = path;
            this.at = System.currentTimeMillis();
        }

        boolean fresh() {
            return System.currentTimeMillis() - at < 1500L;
        }
    }
    private static final java.util.Set<String> DONE =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private LyricHook() {
    }

    static void install(final ClassLoader cl) {
        Class<?> cfgA = XposedHelpers.findClassIfExists(
                "com.tencent.qqmusic.business.lyricnew.config.a", cl);
        if (cfgA == null) {
            Log.i("找不到 lyricnew.config.a，歌词注入不可用");
            return;
        }
        sCfgA = cfgA;
        hookSongWrappers(cfgA);
        // 路径汇入点：f 内部两次调用
        hookPath(cfgA, "d", new Class<?>[]{String.class, String.class}, 1);
        // 各后缀的直接构造者
        hookPath(cfgA, "g", new Class<?>[]{long.class, String.class, String.class,
                String.class, boolean.class}, -1);
        hookPath(cfgA, "m", new Class<?>[]{String.class}, -1);
        hookPath(cfgA, "l", new Class<?>[]{String.class}, -1);
        hookPath(cfgA, "s", new Class<?>[]{String.class}, -1);
        hookPath(cfgA, "t", new Class<?>[]{String.class}, -1);
        hookPath(cfgA, "u", new Class<?>[]{String.class}, -1);
    }

    /**
     * @param suffixArg 后缀所在参数下标；-1 表示后缀写死在方法里，从返回值判断
     */
    private static void hookPath(Class<?> cfgA, String name, Class<?>[] sig, final int suffixArg) {
        Object[] args = new Object[sig.length + 1];
        System.arraycopy(sig, 0, args, 0, sig.length);
        args[sig.length] = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Stack.sample(name);      // 采样：谁在请求歌词路径（找独立歌词页的加载器）
                    Object r = param.getResult();
                    if (!(r instanceof String)) {
                        return;
                    }
                    String path = (String) r;
                    String suffix = (suffixArg >= 0)
                            ? String.valueOf(param.args[suffixArg]) : suffixOf(path);
                    onPathResolved(param, path, suffix);
                } catch (Throwable t) {
                    Log.e("hookPath after", t);
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(cfgA, name, args);
        } catch (Throwable t) {
            Log.i("跳过 " + name + " (" + t.getClass().getSimpleName() + ")");
        }
    }

    private static void onPathResolved(XC_MethodHook.MethodHookParam param, String path, String suffix) {
        if (!isMainLyric(suffix)) {
            return;
        }
        File f = new File(path);
        boolean exists = f.isFile() && f.length() > 0;
        String audio = currentAudio(path);
        MetaExtractor.Meta meta = (audio == null) ? null : MetaCache.get(audio);
        boolean haveLyric = meta != null && meta.hasLyrics();
        String why;
        if (audio == null) {
            why = "未取到本地文件";
        } else if (meta == null) {
            why = "解析失败";
        } else if (!haveLyric) {
            why = "文件里无歌词(" + meta + ")";
        } else {
            why = "有内嵌歌词 " + meta.lyrics.length() + "字";
        }
        Log.once("p:" + path, "歌词路径 " + suffix + " exists=" + exists + " " + why
                + " | audio=" + audio + " | " + path);
        // 降噪：同一个 base+suffix 的同一状态只记一次（之前 5 分钟刷了 963 行）
        String diagBase = AudioFinder.stripExt(new File(path).getName());
        String state = exists + "|" + why + "|" + (audio == null ? "null" : "ok");
        synchronized (LAST_STATE) {
            if (!state.equals(LAST_STATE.get(diagBase + suffix))) {
                LAST_STATE.put(diagBase + suffix, state);
                Diag.w("歌词路径 suffix=" + suffix + " exists=" + exists + " base=" + diagBase
                        + " " + why + " audio=" + (audio == null ? "null" : audio));
            }
        }
        if (exists || !haveLyric) {
            return;
        }
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            // 一律写 .lrc（纯文本标准），.qrc 是加密格式，塞文本没用
            String base = f.getName();
            int dot = base.lastIndexOf('.');
            if (dot > 0) {
                base = base.substring(0, dot);
            }
            byte[] data = MetaExtractor.ensureLrc(meta).getBytes("UTF-8");
            for (String ext : new String[]{".lrc"}) {
                File out = new File(dir, base + ext);
                if (!(out.isFile() && out.length() > 0)) {
                    writeFile(out, data);
                }
            }
            File lrc = new File(dir, base + ".lrc");
            if (DONE.add(path)) {
                Log.i("★ 注入歌词 " + base + ".lrc/.qrc (" + data.length + " 字节)"
                        + " 原请求=" + suffix + " 本地文件=" + new File(audio).getName());
            }
            // 只对「主歌词」后缀做重定向。
            // .producer / .translrc / .multiex 等是**别的格式**，把 LRC 文本硬塞进去
            // 只会让它们的解析器失败（独立歌词页读的就是 .producer）。
            if (".qrc".equals(suffix) || ".lrc".equals(suffix)) {
                if (!".lrc".equals(suffix)) {
                    param.setResult(lrc.getAbsolutePath());
                }
            }
        } catch (Throwable t) {
            Log.e("写歌词 " + path, t);
        }
    }

    /**
     * 一见到 SongInfo 就把它所有能算出来的歌词路径都铺好。
     * <p>
     * 为什么要「主动」：hash 命名那条路（qrc/&lt;hash&gt;.lrc）请求得很早，
     * 那时还没拿到 SongInfo，等拿到时请求已经过去了 —— 只能由先拿到 SongInfo 的进程
     * 提前把文件放好，另一个进程再请求时就能命中（f() 是缓存优先）。
     */
    private static void materialize(Object song) {
        if (song == null || sCfgA == null) {
            return;
        }
        String audio = findAudioPath(song);
        String how = "SongInfo";
        if (audio == null) {
            // SongInfo 上没暴露路径：用歌名去文件索引里找（名字通常只是文件名的一部分）
            String name = songName(song);
            audio = AudioFinder.byContainsName(name);
            how = "歌名反查(" + name + ")";
        }
        if (audio == null) {
            audio = ProbeHook.lastMediaPath();
            how = "lastMediaPath";
        }
        if (audio == null) {
            audio = ProbeHook.readSharedLastPath();
            how = "last.txt";
        }
        if (audio == null) {
            Log.once("mz:none:" + System.identityHashCode(song), "预写入跳过：拿不到本地文件 song="
                    + AdHoc.truncate(String.valueOf(song), 120));
            return;
        }
        MetaExtractor.Meta meta = MetaCache.get(audio);
        if (meta == null || !meta.hasLyrics()) {
            return;
        }
        long id = 0L;
        Object v = callOptional(song, "H2");
        if (v instanceof Long) {
            id = (Long) v;
        } else if (v instanceof Integer) {
            id = ((Integer) v).longValue();
        }
        String s1 = str(callOptional(song, "j3"));
        String s2 = str(callOptional(song, "X3"));
        String s3 = str(callOptional(song, "x1"));

        // 1) 主路径：f() 会优先返回「真名(hash)」路径
        String[] suffixes = {".lrc", ".qrc", ".translrc", ".multiex", ".producer"};
        for (String suffix : suffixes) {
            try {
                Object r = XposedHelpers.callStaticMethod(sCfgA, "f", id, s1, s2, s3, suffix);
                writeSiblingLrc((r instanceof String) ? (String) r : null, meta, audio, how, "f" + suffix);
            } catch (Throwable t) {
                Log.e("materialize f " + suffix, t);
            }
        }

        // 2) 退路路径：d(String.valueOf(key.hashCode()), suffix)
        //    实测日志里出现过 ".../qrc/498924962.lrc" 这种纯数字命名，就是这条路。
        try {
            Object keyObj = XposedHelpers.callStaticMethod(sCfgA, "a", id, s1, s2, s3);
            if (keyObj instanceof String) {
                String hc = String.valueOf(((String) keyObj).hashCode());
                for (String suffix : new String[]{".lrc", ".qrc"}) {
                    Object r = XposedHelpers.callStaticMethod(sCfgA, "d", hc, suffix);
                    writeSiblingLrc((r instanceof String) ? (String) r : null, meta, audio, how,
                            "hashCode" + suffix);
                }
            }
        } catch (Throwable t) {
            Log.e("materialize hashCode路径", t);
        }
    }

    /** 在 path 对应的 .lrc 上写入歌词（path 本身不是 .lrc 时取同名 .lrc）。 */
    private static void writeSiblingLrc(String path, MetaExtractor.Meta meta, String audio,
                                        String how, String tag) {
        if (path == null || path.length() == 0) {
            return;
        }
        try {
            File target = new File(path);
            File dir = target.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            byte[] data = MetaExtractor.ensureLrc(meta).getBytes("UTF-8");
            String base = AudioFinder.stripExt(target.getName());
            // 实测：解析器 parse.c.d 能直接吃纯文本 LRC（日志里 "可用=true"、"-> 非 null"），
            // 但歌词页(主进程)请求的是 .qrc，所以两种后缀都写一份。
            String[] exts = {".lrc"};
            for (String ext : exts) {
                File out = new File(dir, base + ext);
                if (out.isFile() && out.length() > 0) {
                    continue;
                }
                writeFile(out, data);
                if (DONE.add(out.getAbsolutePath())) {
                    Log.i("★★ 预写入 " + out.getName() + " (" + data.length + " 字节)"
                            + " 来源=" + tag + " audio由=" + how
                            + " 歌曲=" + new File(audio).getName());
                }
            }
        } catch (Throwable t) {
            Log.e("writeSiblingLrc " + path, t);
        }
    }

    /** 从 SongInfo.toString() 里取 name=（实测格式稳定：name=高嶺の花子さん,key=...）。 */
    private static String songName(Object song) {
        try {
            String s = String.valueOf(song);
            int i = s.indexOf("name=");
            if (i < 0) {
                return "";
            }
            int j = s.indexOf(',', i);
            String v = (j > i) ? s.substring(i + 5, j) : s.substring(i + 5);
            // 有的版本 name 里带逗号，只在明显截断时回退
            return v.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    static Object currentSongRef() {
        return CURRENT_SONG.get();
    }

    /** 播放器告诉我们「现在放的是这个本地文件」——这是最可信的来源。 */
    static void noteLocalFile(String path) {
        if (path == null) {
            return;
        }
        LAST_AUDIO = path;
        ProbeHook.writeSharedKind(true);
        ProbeHook.writeSharedLastPath(path);
        MetaCache.get(path);            // 预热解析，后面注入时直接用
    }

    /**
     * 唯一的音频解析入口。**所有兜底都要求歌名对得上**，
     * 否则云歌曲会被套上上一次本地歌的歌词（真实发生过的回归）。
     *
     * @param song     SongInfo，可为 null
     * @param pathHint 歌词路径，可为 null
     */
    static String resolveAudio(Object song, String pathHint) {
        String name = songName(song);
        String hintBase = null;
        if (pathHint != null) {
            hintBase = AudioFinder.stripExt(new File(pathHint).getName());
        }

        // 1) SongInfo 自己暴露了存在的音频文件路径 —— 强证据，直接用
        if (song != null) {
            String p = findAudioPath(song);
            if (p != null) {
                return p;
            }
        }

        // 2) 按歌名在索引里匹配
        if (name != null && name.length() >= 2) {
            String p = AudioFinder.byContainsName(name);
            if (p != null) {
                return p;
            }
        }

        // 3) 用歌词路径里的文件名（import/<歌名>.qrc 这条路）
        if (hintBase != null) {
            String p = AudioFinder.byBaseName(hintBase);
            if (p != null && (name == null || name.length() < 2 || nameMatches(p, name))) {
                return p;
            }
        }

        // 4) 最近播放的本地文件：**必须歌名对得上**
        if (name != null && name.length() >= 2) {
            String p = lastAudioIfNameMatches(song);
            if (p != null) {
                return p;
            }
            if (Boolean.TRUE.equals(ProbeHook.readSharedKind())) {
                p = ProbeHook.readSharedLastPath();
                if (p != null && nameMatches(p, name)) {
                    return p;
                }
            }
        }

        // 歌名拿不到、又没有别的证据 → 宁可不注入，也不要污染别的歌
        return null;
    }

    /** 音频文件名与歌名是否匹配（双向包含，忽略空格/连字符） */
    private static boolean nameMatches(String audioPath, String name) {
        try {
            String base = AudioFinder.stripExt(new File(audioPath).getName());
            String nb = AudioFinder.normalize(base);
            String nn = AudioFinder.normalize(name);
            if (nb.length() == 0 || nn.length() == 0) {
                return false;
            }
            return nb.contains(nn) || nn.contains(nb);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 兜底：拿最近一次成功解析的本地文件，但要求歌名对得上，
     * 避免「切歌后仍用上一首的封面/歌词」。
     */
    static String lastAudioIfNameMatches(Object song) {
        String p = LAST_AUDIO;
        if (p == null || !new File(p).isFile()) {
            return null;
        }
        String name = songName(song);
        if (name == null || name.length() < 2) {
            return null;                    // 歌名拿不到就不猜，避免把 A 歌的歌词写进 B 歌缓存
        }
        String base = AudioFinder.stripExt(new File(p).getName());
        String nb = AudioFinder.normalize(base);
        String nn = AudioFinder.normalize(name);
        if (nb.contains(nn) || nn.contains(nb)) {
            return p;
        }
        return null;                        // 名字不对，不能乱用
    }

    private static Object callOptional(Object target, String method) {
        try {
            return XposedHelpers.callMethod(target, method);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String str(Object o) {
        return (o instanceof String) ? (String) o : "";
    }

    private static boolean isMainLyric(String suffix) {
        for (String s : MAIN_SUFFIX) {
            if (s.equals(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static String suffixOf(String path) {
        int i = path.lastIndexOf('.');
        return i < 0 ? "" : path.substring(i);
    }

    // ------------------------------------------------------------ 当前歌曲 / 本地文件

    private static void hookSongWrappers(Class<?> cfgA) {
        for (Method m : cfgA.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 1 || !ps[0].getName().endsWith("SongInfo")) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        CURRENT_SONG.set(param.args[0]);
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            CoverHook.noteCurrentSong(param.args[0]);
                            materialize(param.args[0]);
                        } catch (Throwable t) {
                            Log.e("materialize", t);
                        } finally {
                            CURRENT_SONG.remove();
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 拿「这首歌的本地音频路径」，四级兜底：
     * <ol>
     *   <li>当前 SongInfo 上暴露的真实路径</li>
     *   <li>本进程最近一次 setDataSource 打开的文件</li>
     *   <li>另一进程写下的 last.txt（跨进程共享）</li>
     *   <li><b>用歌词路径里的文件名反查</b> —— import/&lt;歌名&gt;.qrc 这条路的唯一办法</li>
     * </ol>
     */
    private static String currentAudio(String lyricPath) {
        String p = findAudioPath(CURRENT_SONG.get());
        if (p != null) {
            return p;
        }
        p = ProbeHook.lastMediaPath();
        if (p != null) {
            return p;
        }
        p = lastAudioIfNameMatches(CURRENT_SONG.get());
        if (p != null) {
            return p;
        }
        Boolean kind = ProbeHook.readSharedKind();
        if (Boolean.TRUE.equals(kind)) {          // 只有「最近放的确实是本地文件」才敢用
            p = ProbeHook.readSharedLastPath();
            if (p != null) {
                return p;
            }
        }
        // 从歌词路径反查：<...>/import/<歌名>.qrc -> <歌名>.flac
        try {
            String base = AudioFinder.stripExt(new File(lyricPath).getName());
            p = AudioFinder.byBaseName(base);
            if (p != null) {
                Log.once("af:" + base, "按文件名反查到本地文件: " + base + " -> " + p);
                return p;
            }
            // 「最近文件」兜底：只有当歌词路径的文件名和它一致时才敢用
            String last = LAST_AUDIO;
            if (last != null && new File(last).isFile()) {
                String lb = AudioFinder.stripExt(new File(last).getName());
                if (AudioFinder.normalize(lb).equals(AudioFinder.normalize(base))) {
                    Log.once("last:" + base, "用最近解析的本地文件: " + base + " -> " + last);
                    return last;
                }
            }
            Log.once("afmiss:" + base, "按文件名反查失败: " + base
                    + "（索引 " + AudioFinder.size() + " 个文件）");
        } catch (Throwable t) {
            Log.e("currentAudio byName", t);
        }
        return null;
    }

    /** 不猜方法名：SongInfo 上所有返回 String 的方法里，谁给出真实存在的音频路径就是它。 */
    static String findAudioPath(Object song) {
        if (song == null) {
            return null;
        }
        synchronized (AUDIO_PATH) {
            CachedPath hit = AUDIO_PATH.get(song);
            if (hit != null && hit.fresh() && new File(hit.path).isFile()) {
                return hit.path;
            }
            AUDIO_PATH.remove(song);          // 过期或文件没了 → 重扫
        }
        String found = null;
        StringBuilder dump = new StringBuilder();
        try {
            for (Method m : song.getClass().getMethods()) {
                if (m.getParameterTypes().length != 0 || m.getReturnType() != String.class) {
                    continue;
                }
                String v;
                try {
                    v = (String) m.invoke(song);
                } catch (Throwable ignored) {
                    continue;
                }
                if (v == null || v.length() == 0) {
                    continue;
                }
                if (dump.length() < 600) {      // 失败时用来定位「它到底暴露了什么」
                    dump.append(m.getName()).append('=').append(AdHoc.truncate(v, 42)).append("; ");
                }
                if (AdHoc.looksAudio(v) && new File(v).isFile()) {
                    found = v;
                    break;
                }
            }
        } catch (Throwable t) {
            Log.e("findAudioPath", t);
        }
        if (found == null && dump.length() > 0) {
            Log.once("dump:" + dump.toString().hashCode(),
                    "SongInfo 非空字段: " + AdHoc.truncate(dump.toString(), 560));
        }
        if (found == null) {
            // 明确记成「这一瞬间没有」，但立刻过期 —— 下次（切歌后）会重新扫描
            synchronized (AUDIO_PATH) {
                AUDIO_PATH.remove(song);
            }
        }
        if (found != null) {
            synchronized (AUDIO_PATH) {
                AUDIO_PATH.put(song, new CachedPath(found));
            }
            LAST_AUDIO = found;
            ProbeHook.writeSharedKind(true);
            ProbeHook.writeSharedLastPath(found);   // 跨进程共享，别再依赖 MMR 钩子
        }
        return found;
    }

    // ------------------------------------------------------------ 工具

    private static void writeFile(File f, byte[] data) throws Exception {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f);
            fos.write(data);
            fos.flush();
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}

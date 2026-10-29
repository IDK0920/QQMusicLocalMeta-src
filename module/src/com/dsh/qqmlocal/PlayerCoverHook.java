package com.dsh.qqmlocal;

import java.io.File;
import java.lang.reflect.Method;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 封面 —— 视图层手术。
 * <p>
 * 数据层已确认无注入点（本地未匹配歌不发起封面请求），所以直接改播放页的控件：
 * 在播放页可见时遍历视图树，找出「接近正方形、面积最大的 ImageView」当作封面，
 * 若当前播放的是我们的本地歌，就把内嵌封面直接 setImageBitmap 上去。
 * <p>
 * 完全不碰 App 的数据模型，只在 UI 上覆盖。
 */
final class PlayerCoverHook {

    private static final String ACTIVITY =
            "com.tencent.qqmusic.business.playernew.view.NewPlayerActivity";

    private PlayerCoverHook() {
    }

    static void install(ClassLoader cl) {
        Class<?> act = XposedHelpers.findClassIfExists(ACTIVITY, cl);
        if (act == null) {
            Diag.w("找不到 NewPlayerActivity");
            return;
        }
        int n = 0;
        for (String name : new String[]{"onResume", "onWindowFocusChanged"}) {
            try {
                Method m = "onResume".equals(name)
                        ? act.getMethod("onResume")
                        : act.getMethod("onWindowFocusChanged", boolean.class);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        schedule((Activity) param.thisObject);
                    }
                });
                n++;
            } catch (Throwable ignored) {
            }
        }
        Diag.w("封面视图钩子挂了 " + n + " 个");
    }

    private static void schedule(final Activity act) {
        if (act == null) {
            return;
        }
        final Handler h = new Handler(Looper.getMainLooper());
        for (long d : new long[]{300L, 1200L, 2600L}) {
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        apply(act);
                    } catch (Throwable t) {
                        Diag.once("pc:" + t.getClass().getSimpleName(),
                                "封面设置异常 " + t.getClass().getSimpleName());
                    }
                }
            }, d);
        }
    }

    private static boolean sBgDone;
    private static boolean sDiscDone;

    private static final java.util.List<Object[]> TOUCHED =
            new java.util.ArrayList<Object[]>();

    private static void remember(ImageView iv, Drawable d) {
        for (Object[] row : TOUCHED) {
            if (row[0] == iv) {
                return;
            }
        }
        TOUCHED.add(new Object[]{iv, d});
    }

    private static void restoreAll() {
        for (Object[] row : TOUCHED) {
            try {
                ((ImageView) row[0]).setImageDrawable((Drawable) row[1]);
            } catch (Throwable ignored) {
            }
        }
        TOUCHED.clear();
        sBgDone = false;
        sDiscDone = false;
        Diag.once("pc:restore", "已还原之前设置的封面层");
    }

    private static void apply(Activity act) {
        Bitmap bmp = coverBitmap();
        if (bmp == null) {
            return;
        }
        View root = act.getWindow() == null ? null : act.getWindow().getDecorView();
        if (root == null) {
            return;
        }
        int sw = root.getWidth();
        int sh = root.getHeight();
        int st = titleState(root);
        if (st < 0) {
            Diag.once("pc:unknown", "认不出标题，保持现状");
            return;                       // 标题还没渲染出来：什么都别做
        }
        if (st == 0) {
            if (!TOUCHED.isEmpty()) {
                restoreAll();             // 明确是别的歌：还原我们设过的层
            }
            return;
        }
        ImageView bg = sBgDone ? null : findSquare(root, sw, sh, 0.45, 1.0);
        if (bg == null && sDiscDone) {
            return;                       // 两层都设好了
        }
        if (bg == null && !sBgDone) {
            // 容器可能还没布局好，等下次重试
        }
        if (bg != null) {
            remember(bg, bg.getDrawable());
            bg.setImageBitmap(bmp);
            sBgDone = true;
            Diag.once("pc:bg" + bg.getWidth(), "设置封面容器 " + bg.getWidth()
                    + "x" + bg.getHeight());
        }
        // 圆盘中心图：全树搜索方形、宽度 25%~55% 屏幕的图
        java.util.List<ImageView> all = new java.util.ArrayList<ImageView>();
        collect(root, all);
        int hit = 0;
        if (sDiscDone) {
            return;
        }
        for (ImageView iv : all) {
            if (iv == bg) {
                continue;
            }
            int w = iv.getWidth();
            int h = iv.getHeight();
            if (w <= 0 || h <= 0) {
                continue;
            }
            int[] l = new int[2];
            iv.getLocationOnScreen(l);
            boolean square = Math.abs(w - h) <= Math.max(10, w / 8);
            boolean sizeOk = w >= sw * 0.22 && w <= sw * 0.58;
            Diag.once("iv:" + w + "x" + h + "@" + l[0] + "," + l[1],
                    "候选图 " + w + "x" + h + " pos=" + l[0] + "," + l[1]
                            + " square=" + square + " sizeOk=" + sizeOk);
            if (square && sizeOk && hit < 1) {
                remember(iv, iv.getDrawable());
                iv.setImageBitmap(circleBitmap(bmp));
                sDiscDone = true;
                hit++;
                Diag.once("pc:disc" + w, "设置圆盘层 " + w + "x" + h + " pos=" + l[0] + "," + l[1]);
            }
        }
        if (hit == 0) {
            Diag.once("pc:nodisc", "没找到圆盘层");
        }
    }

    /**
     * 1 = 是我们这首；0 = 明确是别的歌；-1 = 认不出来（标题还没渲染等）。
     * 用三态是为了避免「一次没认出来就把刚设好的封面还原掉」。
     */
    private static int titleState(View root) {
        String audio = ProbeHook.readSharedLastPath();
        if (audio == null) {
            return -1;
        }
        String want = AudioFinder.normalize(AudioFinder.stripExt(new File(audio).getName()));
        String cur = CoverHook.currentName();
        if (cur == null || cur.length() < 2) {
            Diag.once("ts:noname", "还没有当前歌曲名，保持现状");
            return -1;
        }
        String cn = AudioFinder.normalize(cur);
        boolean same = cn.length() >= 3 && (want.contains(cn) || cn.contains(want));
        Diag.once("ts:" + same, "当前歌名=[" + cn + "] 本歌=[" + want + "] -> " + same);
        return same ? 1 : 0;
    }

    private static void checkTexts(View v, String want, boolean[] hit, boolean[] any) {
        if (hit[0]) {
            return;
        }
        if (v instanceof android.widget.TextView && v.isShown()) {
            CharSequence t = ((android.widget.TextView) v).getText();
            if (t != null && t.length() > 1) {
                any[0] = true;
                String n = AudioFinder.normalize(t.toString());
                // 关键：要求占比够大，否则「鹿乃(かの)」这种歌手名也会命中我们的文件名
                if (n.length() >= 3 && want.contains(n)
                        && n.length() >= want.length() * 0.55) {
                    hit[0] = true;
                    Diag.once("title:" + n, "标题命中: [" + n + "]");
                    return;
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                checkTexts(g.getChildAt(i), want, hit, any);
            }
        }
    }

    /** 找接近正方形、宽度在屏幕 45%~100% 的最大 ImageView。 */
    private static ImageView findSquare(View root, int sw, int sh, double min, double max) {
        java.util.List<ImageView> list = new java.util.ArrayList<ImageView>();
        collect(root, list);
        ImageView best = null;
        double bestArea = 0;
        for (ImageView iv : list) {
            int w = iv.getWidth();
            int h = iv.getHeight();
            if (w <= 0 || h <= 0 || Math.abs(w - h) > Math.max(10, w / 10)) {
                continue;
            }
            if (w < sw * min || w > sw * max) {
                continue;
            }
            double area = (double) w * h;
            if (area > bestArea) {
                bestArea = area;
                best = iv;
            }
        }
        return best;
    }

    /** 在容器内部找比它小的方形 ImageView（唱片）。 */
    private static ImageView findInnerSquare(View container, double maxSize) {
        java.util.List<ImageView> list = new java.util.ArrayList<ImageView>();
        collect(container, list);
        ImageView best = null;
        double bestArea = 0;
        int[] cLoc = new int[2];
        container.getLocationOnScreen(cLoc);
        for (ImageView iv : list) {
            if (iv == container) {
                continue;
            }
            int w = iv.getWidth();
            int h = iv.getHeight();
            if (w <= 0 || h <= 0 || Math.abs(w - h) > Math.max(10, w / 10) || w > maxSize) {
                continue;
            }
            int[] l = new int[2];
            iv.getLocationOnScreen(l);
            if (l[0] < cLoc[0] - 20 || l[1] < cLoc[1] - 20) {
                continue;
            }
            double area = (double) w * h;
            if (area > bestArea) {
                bestArea = area;
                best = iv;
            }
        }
        return best;
    }

    private static void collect(View v, java.util.List<ImageView> out) {
        if (v instanceof ImageView && v.isShown()) {
            out.add((ImageView) v);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collect(g.getChildAt(i), out);
            }
        }
    }

    /** 圆形裁切（圆盘那层 App 原本是圆形遮罩，直接设方图会变方）。 */
    private static Bitmap circleBitmap(Bitmap src) {
        try {
            int size = Math.min(src.getWidth(), src.getHeight());
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(out);
            android.graphics.Paint p = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            android.graphics.BitmapShader sh = new android.graphics.BitmapShader(
                    src, android.graphics.Shader.TileMode.CLAMP,
                    android.graphics.Shader.TileMode.CLAMP);
            p.setShader(sh);
            c.drawCircle(size / 2f, size / 2f, size / 2f, p);
            return out;
        } catch (Throwable t) {
            return src;
        }
    }

    /** 当前本地歌的内嵌封面位图。 */
    private static Bitmap coverBitmap() {
        try {
            String audio = Boolean.TRUE.equals(ProbeHook.readSharedKind())
                    ? ProbeHook.readSharedLastPath() : null;
            if (audio == null || !new File(audio).isFile()) {
                return null;
            }
            MetaExtractor.Meta meta = MetaCache.get(audio);
            if (meta == null || !meta.hasCover()) {
                return null;
            }
            return BitmapFactory.decodeByteArray(meta.cover, 0, meta.cover.length);
        } catch (Throwable t) {
            return null;
        }
    }
}

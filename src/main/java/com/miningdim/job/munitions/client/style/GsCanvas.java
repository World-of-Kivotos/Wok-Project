package com.miningdim.job.munitions.client.style;

/**
 * 枪匠界面的矩形绘制原语, 坐标一律是 GUI 像素、以界面左上角为原点 (360x240)。与设计预览里的
 * R / box / dbox / para / paraFit / paraR / corners 一一对应, 两个实现:
 * {@link GsPainter} (逐帧画到 GuiGraphics) 和 {@link PixelCanvas} (一次性画进底图贴图)。
 *
 * 颜色是 ARGB int ({@code 0xAARRGGBB}); 预览里的 '#RRGGBB' 写成 {@code 0xFFRRGGBB},
 * rgba(r,g,b,a) 用 {@link #rgba(int, int, int, double)} 换算。
 */
public interface GsCanvas {

    /** 斜切率: 行 r (自上而下) 向右平移 round((h-1-r) * K)。与学园标签铭牌同一套 14° 语言。 */
    double K = 0.25D;

    /**
     * 填充 (x, y) 起 w x h 的矩形 (预览 R)。w 或 h 非正时什么也不画 —— 注意 GuiGraphics.fill
     * 会把反向坐标自动交换, 实现方必须自己挡住, 否则负宽会画出一块错位的矩形。
     */
    void rect(int x, int y, int w, int h, int argb);

    /** 1px 空心框 (预览 box)。 */
    default void box(int x, int y, int w, int h, int argb) {
        rect(x, y, w, 1, argb);
        rect(x, y + h - 1, w, 1, argb);
        rect(x, y + 1, 1, h - 2, argb);
        rect(x + w - 1, y + 1, 1, h - 2, argb);
    }

    /** 1px 虚线框, 画 2 空 1 (预览 dbox; 蓝图风格的"未解锁")。 */
    default void dbox(int x, int y, int w, int h, int argb) {
        for (int i = 0; i < w; i += 3) {
            rect(x + i, y, Math.min(2, w - i), 1, argb);
            rect(x + i, y + h - 1, Math.min(2, w - i), 1, argb);
        }
        for (int j = 3; j < h - 1; j += 3) {
            rect(x, y + j, 1, Math.min(2, h - 1 - j), argb);
            rect(x + w - 1, y + j, 1, Math.min(2, h - 1 - j), argb);
        }
    }

    /** 高 h 的斜切偏移量 (预览 slant)。 */
    static int slant(int h) {
        return (int) Math.round((h - 1) * K);
    }

    /** 平行四边形: 每行宽 w, 上端右移 (预览 para)。总外接宽 w + slant(h)。 */
    default void para(int x, int y, int w, int h, int argb) {
        for (int r = 0; r < h; r++) {
            rect(x + (int) Math.round((h - 1 - r) * K), y + r, w, 1, argb);
        }
    }

    /** 外接宽恰为 w 的平行四边形 (预览 paraFit)。 */
    default void paraFit(int x, int y, int w, int h, int argb) {
        para(x, y, w - slant(h), h, argb);
    }

    /** 左边竖直、右边斜切的梯形 (预览 paraR; 学园风格标题块)。 */
    default void paraR(int x, int y, int w, int h, int argb) {
        for (int r = 0; r < h; r++) {
            rect(x, y + r, w + (int) Math.round((h - 1 - r) * K), 1, argb);
        }
    }

    /** 四角 L 形角标, 臂长 n (预览 corners)。 */
    default void corners(int x, int y, int w, int h, int argb, int n) {
        rect(x, y, n, 1, argb);
        rect(x, y, 1, n, argb);
        rect(x + w - n, y, n, 1, argb);
        rect(x + w - 1, y, 1, n, argb);
        rect(x, y + h - 1, n, 1, argb);
        rect(x, y + h - n, 1, n, argb);
        rect(x + w - n, y + h - 1, n, 1, argb);
        rect(x + w - 1, y + h - n, 1, n, argb);
    }

    /** 臂长 3 的角标。 */
    default void corners(int x, int y, int w, int h, int argb) {
        corners(x, y, w, h, argb, 3);
    }

    /**
     * 斜向警示条纹: 像素 (c, r) 在 ((c + r + phase) mod period) &lt; on 时取 colorA, 否则 colorB。
     * 按行合并同色像素成段画, 等价于预览里逐像素的 R(…,1,1,…) 循环, 但填充次数少 period/on 倍。
     */
    default void stripes(int x, int y, int w, int h, int colorA, int colorB, int period, int on, int phase) {
        for (int r = 0; r < h; r++) {
            int runStart = 0;
            boolean runA = Math.floorMod(r + phase, period) < on;
            for (int c = 1; c <= w; c++) {
                boolean a = c < w && Math.floorMod(c + r + phase, period) < on;
                if (c == w || a != runA) {
                    rect(x + runStart, y + r, c - runStart, 1, runA ? colorA : colorB);
                    runStart = c;
                    runA = a;
                }
            }
        }
    }

    /** 预览 rgba(r,g,b,a) -> ARGB (a 为 0..1, 四舍五入到 0..255)。 */
    static int rgba(int r, int g, int b, double a) {
        int alpha = (int) Math.round(Math.max(0.0D, Math.min(1.0D, a)) * 255.0D);
        return (alpha << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    /** 保证不透明 (0xRRGGBB -> 0xFFRRGGBB)。 */
    static int opaque(int rgb) {
        return 0xFF000000 | (rgb & 0xFFFFFF);
    }
}

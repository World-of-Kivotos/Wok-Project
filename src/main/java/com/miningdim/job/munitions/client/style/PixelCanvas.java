package com.miningdim.job.munitions.client.style;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * 软件画布: 把静态底图 (边框、底纹、标题栏底色) 按 GUI 像素画进一张 ARGB 数组, 再转成 NativeImage 交给
 * DynamicTexture。每种风格只画一次, 之后每帧 1:1 blit —— 学园风格的斜纹底有上万个 1px 点,
 * 逐帧 fill 会把 GUI 渲染拖成几千次 draw call。
 *
 * 混色按 src-over (与 GuiGraphics.fill 的半透明混合一致), 超出画布的部分直接裁掉。
 */
public final class PixelCanvas implements GsCanvas {

    private final int width;
    private final int height;
    private final int[] argb;

    public PixelCanvas(int width, int height) {
        this.width = width;
        this.height = height;
        this.argb = new int[width * height];
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    @Override
    public void rect(int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(width, x + w);
        int y1 = Math.min(height, y + h);
        int alpha = color >>> 24;
        if (alpha == 0 || x0 >= x1 || y0 >= y1) {
            return;
        }
        for (int py = y0; py < y1; py++) {
            int row = py * width;
            for (int px = x0; px < x1; px++) {
                int i = row + px;
                argb[i] = alpha == 0xFF ? color : blend(argb[i], color);
            }
        }
    }

    /** 读回一个像素 (ARGB), 越界返回 0。 */
    public int pixel(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return 0;
        }
        return argb[y * width + x];
    }

    /**
     * 转成 NativeImage。NativeImage.setPixelRGBA 的 int 实际按 ABGR 打包 (小端内存里的 R,G,B,A 字节序),
     * 所以要把 ARGB 的 R/B 两个字节对调。调用方负责 close (交给 DynamicTexture 后由贴图管理)。
     */
    public NativeImage toNativeImage() {
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setPixelRGBA(x, y, argbToAbgr(argb[y * width + x]));
            }
        }
        return image;
    }

    static int argbToAbgr(int c) {
        return (c & 0xFF00FF00) | ((c >>> 16) & 0xFF) | ((c & 0xFF) << 16);
    }

    private static int blend(int dst, int src) {
        int sa = src >>> 24;
        int da = dst >>> 24;
        int inv = 255 - sa;
        int outA = sa + da * inv / 255;
        if (outA == 0) {
            return 0;
        }
        int r = channel(src >>> 16, dst >>> 16, sa, da, inv, outA);
        int g = channel(src >>> 8, dst >>> 8, sa, da, inv, outA);
        int b = channel(src, dst, sa, da, inv, outA);
        return (outA << 24) | (r << 16) | (g << 8) | b;
    }

    private static int channel(int s, int d, int sa, int da, int inv, int outA) {
        int sc = s & 0xFF;
        int dc = d & 0xFF;
        int v = (sc * sa * 255 + dc * da * inv) / (outA * 255);
        return Math.max(0, Math.min(255, v));
    }
}

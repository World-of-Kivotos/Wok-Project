package com.miningdim.client.social.tablet;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * 社交平板的设备外观（Slate 11）：正面贴图、挤出网格与屏幕占比。
 *
 * <p>与步战版 {@code TabletTextures}/{@code DeviceArt} 的区别：这里不捕捉屏幕帧，也不画键位，
 * 正面是一张静态的银色机身贴图 {@code slate11_shell.png}（屏幕开窗已在图里挖空成深色玻璃），
 * 浏览器贴图作为单独一层盖在开窗上。设备外形尺寸由“屏幕开窗 = 整个页面平面”反推：
 * 贴图里开窗占 960/1024 × 600/640 = 93.75%，所以机身 = 页面平面 / 0.9375，页面正好铺满开窗。</p>
 *
 * <p>挤出网格来自贴图 alpha 轮廓（{@link TabletDeviceModel#mesh}），因此圆角、侧壁、背板与
 * 正面图始终是同一套形状，不需要再手写一遍圆角矩形。</p>
 */
public final class SocialDeviceArt {
    /** 正面：屏幕开窗已挖空的银色机身。 */
    public static final ResourceLocation FRONT = new ResourceLocation("miningdim",
            "textures/gui/halolink/slate11_shell.png");
    /** 纯白贴图，用于侧壁/背板与手套的顶点色（1.20.1 原版没有 misc/white.png，自带一张最稳）。 */
    public static final ResourceLocation WHITE = new ResourceLocation("miningdim",
            "textures/gui/halolink/white.png");

    private static final int IMAGE_W = 1024;
    private static final int IMAGE_H = 640;
    /** 屏幕开窗在正面图里占的宽/高比例（960×600 于 1024×640）。 */
    public static final double SCREEN_FRAC = 960.0D / 1024.0D;

    private static TabletDeviceModel.Mesh mesh;
    private static boolean meshFailed;

    private SocialDeviceArt() {
    }

    /** 机身相对页面平面的放大倍数：页面铺满开窗。 */
    public static double deviceScale() {
        return 1.0D / SCREEN_FRAC;
    }

    /** 银色侧壁与背板颜色（Slate 11 的阳极氧化银）。 */
    public static int caseColor() {
        return 0xFFC8CDD4;
    }

    public static int caseLo() {
        return 0xFFAAB0B8;
    }

    /** 正面贴图 alpha 轮廓挤出的侧壁与背板；读不到资源时为 {@code null}（只画正面）。 */
    public static synchronized TabletDeviceModel.Mesh mesh() {
        if (mesh != null || meshFailed) {
            return mesh;
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            Optional<Resource> resource = minecraft.getResourceManager().getResource(FRONT);
            if (resource.isEmpty()) {
                meshFailed = true;
                return null;
            }
            int[] argb;
            try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
                int w = image.getWidth();
                int h = image.getHeight();
                argb = new int[w * h];
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        argb[y * w + x] = image.getPixelRGBA(x, y);
                    }
                }
                if (w != IMAGE_W || h != IMAGE_H) {
                    // 贴图换了尺寸就按实际尺寸建网格，平面映射仍然按比例走。
                    mesh = TabletDeviceModel.mesh(argb, w, h, new UiRect(0, 0, w, h));
                } else {
                    mesh = TabletDeviceModel.mesh(argb, IMAGE_W, IMAGE_H,
                            new UiRect(0, 0, IMAGE_W, IMAGE_H));
                }
            }
            return mesh;
        } catch (IOException | RuntimeException exception) {
            meshFailed = true;
            return null;
        }
    }

    /** 贴图像素 x → 机身空间 x。 */
    public static double deviceX(TabletPose3D.Face face, double imageX) {
        double devW = face.wf() * deviceScale();
        return (imageX / IMAGE_W - 0.5D) * devW;
    }

    /** 贴图像素 y（向下）→ 机身空间 y（向上）。 */
    public static double deviceY(TabletPose3D.Face face, double imageY) {
        double devH = face.hf() * deviceScale();
        return (0.5D - imageY / IMAGE_H) * devH;
    }
}

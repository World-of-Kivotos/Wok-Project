package com.miningdim.client.social.tablet;

/**
 * 社交平板的 D2 设备几何（布局逻辑像素）：
 * D = 机身外轮廓、S = 玻璃开窗、P = 点亮显示区、E = D 向外扩 bump。
 *
 * <p>与步战版本的区别：这里不再从 {@code TacticalShellLayout} 的战术排版推导，而是直接按
 * {@code slate11_front.png}（1024×640）的银色机身/屏幕开窗等比映射到当前布局尺寸。
 * 这样 3D 挤出、屏幕贴图和 UI 布局用的是同一套矩形。</p>
 */
public record TabletD2Geometry(int w, int h, SocialDeviceMetrics k,
                               UiRect d, UiRect s, UiRect p, UiRect e) {

    private static final int IMAGE_W = 1024;
    private static final int IMAGE_H = 640;
    private static final int IMAGE_OUTER_L = 8;
    private static final int IMAGE_OUTER_T = 8;
    private static final int IMAGE_OUTER_R = 1016;
    private static final int IMAGE_OUTER_B = 632;
    private static final int IMAGE_SCREEN_L = 32;
    private static final int IMAGE_SCREEN_T = 20;
    private static final int IMAGE_SCREEN_R = 992;
    private static final int IMAGE_SCREEN_B = 620;

    public static TabletD2Geometry of(int lpW, int lpH) {
        int w = Math.max(1, lpW);
        int h = Math.max(1, lpH);
        UiRect d = new UiRect(
                scaleX(IMAGE_OUTER_L, w), scaleY(IMAGE_OUTER_T, h),
                scaleX(IMAGE_OUTER_R, w), scaleY(IMAGE_OUTER_B, h));
        UiRect p = new UiRect(
                scaleX(IMAGE_SCREEN_L, w), scaleY(IMAGE_SCREEN_T, h),
                scaleX(IMAGE_SCREEN_R, w), scaleY(IMAGE_SCREEN_B, h));
        int glass = Math.max(2, Math.round(6.0f * w / IMAGE_W));
        UiRect s = p.inset(-glass);
        UiRect e = d;
        return new TabletD2Geometry(w, h, new SocialDeviceMetrics(0, 0, 0), d, s, p, e);
    }

    private static int scaleX(int imageX, int w) {
        return Math.round((float) imageX * w / IMAGE_W);
    }

    private static int scaleY(int imageY, int h) {
        return Math.round((float) imageY * h / IMAGE_H);
    }
}

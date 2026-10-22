package com.miningdim.client.social.tablet;

/**
 * 步战 {@code DeviceArt} 里被动作核心引用的四个颜色常量；社交版本只保留纯数据，不依赖步战 screen 包。
 */
public final class SocialTabletColors {

    /** Glass in the opening and the camera well. */
    public static final int GLASS = 0xFF040606;
    /** Screen dim over the world while the tablet is out. */
    public static final int WORLD_DIM = 0x880B0F11;
    /** LED off. */
    public static final int LED_OFF = 0xFF0E1313;
    /** Drop shadow of the case on the world. */
    public static final int SHADOW = 0x78000000;

    private SocialTabletColors() {
    }
}

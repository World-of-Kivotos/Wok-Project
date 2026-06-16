package com.miningdim.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * CLIENT 级配置 spec (设计文档 16.5)。仅影响本机渲染, 不参与任何世界/平衡逻辑, 故置于 CLIENT 层。
 *
 * dangerVisualMode 决定 15.4.2 中 DangerSyncS2C.lightDimFactor 是否驱动屏幕压暗滤镜:
 *   OFF                 不显示任何 danger 视觉;
 *   HUD_ONLY            仅显示 HUD, 不压暗屏幕;
 *   HUD_AND_SCREEN_DIM  HUD + 按 lightDimFactor 压暗 (默认)。
 * 这些值由后续 HUD/渲染子系统在客户端消费; 本期 (配置子系统) 仅提供 spec 与读取门面。
 */
public final class MiningClientConfig {

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.EnumValue<DangerVisualMode> DANGER_VISUAL_MODE;
    public static final ForgeConfigSpec.BooleanValue SHOW_INSTANCE_HUD;
    public static final ForgeConfigSpec.DoubleValue DANGER_HUD_SCALE;

    /** 16.5 client.dangerVisualMode 枚举。 */
    public enum DangerVisualMode {
        OFF,
        HUD_ONLY,
        HUD_AND_SCREEN_DIM
    }

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("client");
        DANGER_VISUAL_MODE = b.comment("Whether DangerSyncS2C.lightDimFactor drives a screen-dim overlay")
                .defineEnum("dangerVisualMode", DangerVisualMode.HUD_AND_SCREEN_DIM);
        SHOW_INSTANCE_HUD = b.comment("Show the instance/danger HUD")
                .define("showInstanceHud", true);
        DANGER_HUD_SCALE = b.comment("HUD scale factor")
                .defineInRange("dangerHudScale", 1.0, 0.25, 4.0);
        b.pop();

        SPEC = b.build();
    }

    private MiningClientConfig() {
    }
}

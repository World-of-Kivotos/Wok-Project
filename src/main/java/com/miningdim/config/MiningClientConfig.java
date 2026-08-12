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

    public static final ForgeConfigSpec.ConfigValue<String> WEBUI_URL;

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

        b.push("webui");
        // 单一 URL (决策 J4): 面板路由由页面自身的 hash router 承担, Java 侧不按面板切 URL ——
        // 换 URL 会丢弃已加载的单例浏览器实例, 与架构文档 10.5 的预加载复用策略冲突。
        // 默认指向本地 vite dev server; 生产环境改为远端托管地址 (架构文档第二章第 2 条, 路线 A)。
        WEBUI_URL = b.comment("Single URL of the in-game Web UI front-end; panel routing is handled by the page's own hash router")
                .define("url", "http://localhost:5173/", MiningClientConfig::isHttpUrl);
        b.pop();

        SPEC = b.build();
    }

    /**
     * webui.url 取值校验: 必须是 http/https 绝对地址。拒 file:// 与 data: 是有意为之 —— 前端走远端托管
     * (架构文档第二章第 2 条), 本地文件加载既不在分发路线内, 也会让页面落进 CEF 的非安全上下文。
     * 校验失败时 ForgeConfigSpec 自动回退到默认值并在日志留痕, 不崩客户端。
     */
    private static boolean isHttpUrl(Object raw) {
        if (!(raw instanceof String s)) {
            return false;
        }
        return s.startsWith("http://") || s.startsWith("https://");
    }

    private MiningClientConfig() {
    }
}

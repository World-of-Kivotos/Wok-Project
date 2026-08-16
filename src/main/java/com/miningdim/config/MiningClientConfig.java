package com.miningdim.config;

import net.minecraftforge.common.ForgeConfigSpec;

import com.miningdim.webui.WebUiPageUrl;

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
    public static final ForgeConfigSpec.IntValue WEBUI_ZOOM_PERCENT;
    public static final ForgeConfigSpec.IntValue WEBUI_COVERAGE_PERCENT;

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
        // 页面缩放走 CEF 自己的 zoom (setZoomLevel) 而不是前端 CSS transform: CEF 的 zoom 参与布局,
        // 媒体查询与滚动条都按缩放后的 CSS 视口重算; CSS transform 只是把已经排好版的画面拉大, 会在
        // 窄视口下把响应式断点卡在错误的一档。
        WEBUI_ZOOM_PERCENT = b.comment(
                        "In-game Web UI page zoom, in percent. Applied through CEF's own zoom so layout and media "
                                + "queries recompute; 100 = no zoom")
                .defineInRange("zoomPercent", 125, 50, 300);
        // 覆盖比例是"每条边占屏幕的百分比", 不是面积百分比: 70 表示宽高各取 70% (面积约 49%)。
        // 按边长定义才能让面板在任何宽高比下都保持与屏幕同形, 按面积定义则要开方, 换算出来的边长是个
        // 没人能一眼验算的数。
        WEBUI_COVERAGE_PERCENT = b.comment(
                        "How much of the screen the in-game Web UI covers, as a percentage of each edge "
                                + "(70 = 70% of width and 70% of height, centered). 100 = fullscreen")
                .defineInRange("coveragePercent", 70, 30, 100);
        b.pop();

        SPEC = b.build();
    }

    /**
     * webui.url 取值校验: 必须是 http/https 绝对地址。拒 file:// 与 data: 是有意为之 —— 前端走远端托管
     * (架构文档第二章第 2 条), 本地文件加载既不在分发路线内, 也会让页面落进 CEF 的非安全上下文。
     * data: 这一条由本方法挡住 (WebUiPageUrl.normalize 会把 data: 原样放行, 但本配置项刻意只收 http(s))。
     * 校验失败时 ForgeConfigSpec 自动回退到默认值并在日志留痕, 不崩客户端。
     */
    private static boolean isHttpUrl(Object raw) {
        if (!(raw instanceof String s)) {
            return false;
        }
        String normalized;
        try {
            normalized = WebUiPageUrl.normalize(s);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return normalized.startsWith("http://") || normalized.startsWith("https://");
    }

    /**
     * webui.url 的唯一读取口径。配置里存的是运维手打的字面量, 宿主要拿它去和 Chromium 归一化后的
     * 文档 URL 比对 (WebUiBridge.onQuery), 两侧必须过同一套归一化, 否则尾斜杠/大小写/默认端口这类
     * 字面差异会把合法页面判成不可信。
     *
     * 这里的 normalize 理论上不会抛: 上面的校验器 (isHttpUrl) 已经保证了 SPEC 里存的值合法,
     * 非法值会被 ForgeConfigSpec 自动回退成默认值。真抛出说明校验器与本读取口径已经失步 (例如
     * 校验器改了但这里没跟上), 属于配置子系统自身的 bug, 必须让它痛, 不在这里 try/catch 兜底。
     */
    public static String webUiUrl() {
        return WebUiPageUrl.normalize(WEBUI_URL.get());
    }

    private MiningClientConfig() {
    }
}

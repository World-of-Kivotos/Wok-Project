package com.miningdim.job.munitions;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 军火商模块自己的 CLIENT 配置 (miningdim-munitions-client.toml), 由 {@link MunitionsSystem} 注册。
 *
 * 为什么不放进 com.miningdim.config.MiningClientConfig: 那个类属于核心模块, 引用本模块的
 * {@link GunsmithUiStyle} 会造成核心 -> 军火商的反向依赖 (verifyModuleBoundaries 会拒)。按模块化铁律,
 * 军火商自己的本机偏好由军火商自己注册一份 CLIENT spec, 与 SERVER spec (miningdim-munitions.toml) 同一套做法。
 *
 * 专用服务端也会加载本类 (spec 在 register 期构建), 所以这里只引用纯数据枚举, 不引用任何客户端类。
 * CLIENT 配置只在客户端加载; 服务端永远不读这里的值。
 */
public final class MunitionsClientConfig {

    public static final ForgeConfigSpec SPEC;

    /** 军火台 / 机械冲压机界面风格 (玩家在界面里点右上角的三色小按钮切换, 选中即写入并保存)。 */
    public static final ForgeConfigSpec.EnumValue<GunsmithUiStyle> GUNSMITH_UI_STYLE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("gunsmith_ui");
        GUNSMITH_UI_STYLE = b.comment("Visual style of the munitions bench and gunsmith press screens. "
                        + "Only changes what this client sees; slots, buttons and server behaviour are identical "
                        + "for every style. Normally changed in-game via the style button in the screen header.")
                .defineEnum("style", GunsmithUiStyle.DEFAULT);
        b.pop();
        SPEC = b.build();
    }

    private MunitionsClientConfig() {
    }

    /**
     * 当前界面风格。配置尚未加载 (理论上只在极早期或专用服务端出现) 时回退默认风格, 不抛 —— 这是渲染路径上
     * 的读取, 读不到偏好不该把界面炸掉。
     */
    public static GunsmithUiStyle gunsmithUiStyle() {
        try {
            GunsmithUiStyle style = GUNSMITH_UI_STYLE.get();
            return style != null ? style : GunsmithUiStyle.DEFAULT;
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return GunsmithUiStyle.DEFAULT;
        }
    }

    /**
     * 写入界面风格并立即保存到 toml (下次进游戏仍是这一种)。与当前值相同则什么也不做。
     * 配置未加载时静默忽略: 那种状态下本来也打不开相关界面。
     */
    public static void setGunsmithUiStyle(GunsmithUiStyle style) {
        if (style == null || style == gunsmithUiStyle()) {
            return;
        }
        try {
            GUNSMITH_UI_STYLE.set(style);
            GUNSMITH_UI_STYLE.save();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            // 配置对象不存在 (未加载): 没有可写的文件, 放弃即可。
        }
    }
}

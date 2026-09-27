package com.miningdim.job.munitions;

import java.util.Locale;

/**
 * 军火台 / 机械冲压机界面的三种风格。只是本机显示偏好 (每个玩家自己选, 存在客户端配置
 * {@link MunitionsClientConfig}), 不参与任何服务端逻辑: 三种风格共用同一套槽位坐标、点击区域和按钮编号。
 *
 * 本枚举被 CLIENT 配置类引用, 而配置类在专用服务端也会被类加载, 所以这里只能放纯数据, 不得引用任何
 * 客户端类 (绘制实现在 {@code com.miningdim.job.munitions.client.style})。
 */
public enum GunsmithUiStyle {

    /** A 学园终端: 白底卡片 + 斜切页签 (默认)。 */
    ACADEMY("academy"),

    /** B 产线工控: 深色钢板 + 琥珀仪表。 */
    INDUSTRIAL("industrial"),

    /** C 蓝图工程: 图纸网格 + 线稿标注。 */
    BLUEPRINT("blueprint");

    public static final GunsmithUiStyle DEFAULT = ACADEMY;

    private final String id;

    GunsmithUiStyle(String id) {
        this.id = id;
    }

    /** 稳定的小写 id (贴图名 / 翻译键段用; 与枚举名解耦, 改名不影响已有资源)。 */
    public String id() {
        return id;
    }

    /** 风格名翻译键 (如 "学园终端")。 */
    public String nameKey() {
        return "gui.miningdim.gunsmith_ui.style." + id + ".name";
    }

    /** 一行说明翻译键 (如 "白底卡片 · 斜切页签")。 */
    public String descriptionKey() {
        return "gui.miningdim.gunsmith_ui.style." + id + ".desc";
    }

    /** 按 id 还原, 未知值回退默认风格。 */
    public static GunsmithUiStyle byId(String id) {
        if (id != null) {
            String normalized = id.trim().toLowerCase(Locale.ROOT);
            for (GunsmithUiStyle style : values()) {
                if (style.id.equals(normalized)) {
                    return style;
                }
            }
        }
        return DEFAULT;
    }
}

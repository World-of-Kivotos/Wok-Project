package com.miningdim.client.social.tablet;

/**
 * 掏出平板时手部的外观：白手套（设计稿默认）或玩家自己的皮肤手臂。
 *
 * <p>皮肤模式直接用玩家的 64×64 皮肤贴图渲染一只 4×12×4（slim 为 3×12×4）的手臂方块，
 * 手端另补一条皮肤色的指节和一根拇指。贴图在近景会被放大，皮肤像素比手套的纯色块更明显；
 * 这是“用自己皮肤”换来的代价，所以保留手套档位可切回。</p>
 */
public enum TabletHandStyle {
    /** 白手套：深色制服袖 + 白色掌部 + 指节/拇指。 */
    GLOVE("glove"),
    /** 玩家皮肤：直接用皮肤贴图的手臂方块。 */
    SKIN("skin");

    private final String id;

    TabletHandStyle(String id) {
        this.id = id;
    }

    /** 配置/命令里的字面量。 */
    public String id() {
        return id;
    }

    /** 解析配置或命令参数；未知值按手套处理（配置项不崩客户端）。 */
    public static TabletHandStyle parse(String value) {
        if (value != null) {
            String trimmed = value.trim();
            for (TabletHandStyle style : values()) {
                if (style.id.equalsIgnoreCase(trimmed)) {
                    return style;
                }
            }
        }
        return GLOVE;
    }
}

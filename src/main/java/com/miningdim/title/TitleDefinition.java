package com.miningdim.title;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 一条称号定义: 数据包称号来自 {@code data/<ns>/titles/<path>.json} (id 即资源路径); 专属称号
 * (Title_System_DesignSpec 第十三章) 由 {@link #custom} 按玩家的记录动态生成, 不进数据包定义集合。
 *
 * @param id            称号 id, 例如 {@code miningdim:mining/ore_codex}
 * @param text          称号文字 (数据包称号按约定用 translate, 键名 {@code title.miningdim.*}; 专属称号是字面量)
 * @param rarity        稀有度 (七档之一); 专属称号没有稀有度, 为 null
 * @param colorOverride style 覆盖的色标: 空表示沿用稀有度色板, 1 个为单色, 2 ~ 3 个为渐变 (专属称号必有)
 * @param boldOverride  style 覆盖的粗体; null 表示沿用稀有度 (专属称号必有)
 * @param description   获取说明; 可空
 * @param sort          排序权重; 缺省取稀有度默认值
 */
public record TitleDefinition(ResourceLocation id, Component text, @Nullable TierPalette rarity,
                              List<Integer> colorOverride, @Nullable Boolean boldOverride,
                              @Nullable Component description, int sort) {

    /** 专属称号的排序权重: 固定排在最前面, 比传说档还高 (13.2)。 */
    public static final int CUSTOM_SORT = Integer.MAX_VALUE;

    public TitleDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(text, "text");
        colorOverride = List.copyOf(colorOverride);
        if (colorOverride.size() > TierPalette.MAX_GRADIENT_STOPS) {
            throw new IllegalArgumentException("title " + id + " has more than "
                    + TierPalette.MAX_GRADIENT_STOPS + " color stops");
        }
        if (rarity == null && (colorOverride.isEmpty() || boldOverride == null)) {
            throw new IllegalArgumentException("title " + id
                    + " has no rarity, so it must carry its own colors and bold");
        }
    }

    /** 专属称号的动态定义: 没有稀有度, 文字原样显示不加方括号, 描述统一为"赞助专属称号"。 */
    static TitleDefinition custom(ResourceLocation id, CustomTitleStyle style) {
        return new TitleDefinition(id, Component.literal(style.text()), null, style.colors(), style.bold(),
                Component.translatable("title.miningdim.custom.desc"), CUSTOM_SORT);
    }

    /** 是否为专属称号 (没有稀有度、原样显示)。 */
    public boolean isCustom() {
        return rarity == null;
    }

    /** 最终生效的色标 (0xRRGGBB): style 覆盖优先, 否则取稀有度色板。 */
    public int[] colors() {
        if (colorOverride.isEmpty()) {
            return rarity.stops();
        }
        int[] colors = new int[colorOverride.size()];
        for (int i = 0; i < colors.length; i++) {
            colors[i] = colorOverride.get(i);
        }
        return colors;
    }

    /** 最终生效的粗体: style 覆盖优先, 否则取稀有度。 */
    public boolean bold() {
        return boldOverride != null ? boldOverride : rarity.bold();
    }

    /** 最终生效的样式是否需要逐字渐变。 */
    public boolean isGradient() {
        return colorOverride.isEmpty() ? rarity.isGradient() : colorOverride.size() > 1;
    }
}

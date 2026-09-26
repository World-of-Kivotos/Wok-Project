package com.miningdim.title;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 一条称号定义 (数据包 {@code data/<ns>/titles/<path>.json}, id 即资源路径)。
 *
 * @param id            称号 id, 例如 {@code miningdim:mining/ore_codex}
 * @param text          称号文字 (Component, 按约定用 translate, 键名 {@code title.miningdim.*})
 * @param rarity        稀有度 (七档之一)
 * @param colorOverride style 覆盖的色标: 空表示沿用稀有度色板, 1 个为单色, 2 ~ 3 个为渐变
 * @param boldOverride  style 覆盖的粗体; null 表示沿用稀有度
 * @param description   获取说明; 可空
 * @param sort          排序权重; 缺省取稀有度默认值
 */
public record TitleDefinition(ResourceLocation id, Component text, TierPalette rarity,
                              List<Integer> colorOverride, @Nullable Boolean boldOverride,
                              @Nullable Component description, int sort) {

    public TitleDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(rarity, "rarity");
        colorOverride = List.copyOf(colorOverride);
        if (colorOverride.size() > TierPalette.MAX_GRADIENT_STOPS) {
            throw new IllegalArgumentException("title " + id + " has more than "
                    + TierPalette.MAX_GRADIENT_STOPS + " color stops");
        }
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

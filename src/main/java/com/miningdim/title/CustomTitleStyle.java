package com.miningdim.title;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 已通过校验的专属称号外观 (Title_System_DesignSpec 13.3): 文字原样保存 (含玩家自选的外框符号), 1 个色标为单色,
 * 2 ~ 3 个为逐字渐变, 外加粗体开关。只由 {@link CustomTitleValidator} 产出或从库里读回, 不直接接收玩家输入。
 *
 * @param text   称号文字, 原样显示, 不加方括号
 * @param colors 1 ~ {@value TierPalette#MAX_GRADIENT_STOPS} 个 0xRRGGBB 色标
 * @param bold   是否加粗
 */
public record CustomTitleStyle(String text, List<Integer> colors, boolean bold) {

    public CustomTitleStyle {
        Objects.requireNonNull(text, "text");
        colors = List.copyOf(colors);
        if (colors.isEmpty() || colors.size() > TierPalette.MAX_GRADIENT_STOPS) {
            throw new IllegalArgumentException("custom title needs 1.." + TierPalette.MAX_GRADIENT_STOPS
                    + " color stops, got " + colors.size());
        }
    }

    /** 落库与审计日志用的色标写法: 逗号分隔的大写 {@code #RRGGBB} (title_custom.colors 列)。 */
    public String colorsText() {
        return colors.stream()
                .map(color -> String.format(Locale.ROOT, "#%06X", color))
                .collect(Collectors.joining(","));
    }
}

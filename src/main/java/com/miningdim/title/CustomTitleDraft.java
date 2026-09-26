package com.miningdim.title;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 玩家 (或管理员代为) 提交的专属称号草稿, 尚未校验 (Title_System_DesignSpec 13.3 / 13.7)。
 * 颜色保留原始写法, 是否合法交给 {@link CustomTitleValidator} 逐条报告。
 *
 * @param text   称号文字, 原样保留 (含空格与外框符号)
 * @param colors 色标原文, 每项应为 {@code #RRGGBB}
 * @param bold   是否加粗
 */
public record CustomTitleDraft(String text, List<String> colors, boolean bold) {

    public CustomTitleDraft {
        Objects.requireNonNull(text, "text");
        colors = List.copyOf(colors);
    }

    /**
     * 解析命令参数 {@code <颜色> <粗体> <文字>}: 颜色是第一个空格前的整段, 用逗号分隔色标; 粗体是 true 或 false
     * (不分大小写); 文字是第二个空格之后的全部剩余输入, 原样保留 —— 首尾或连续空格交给校验器报告, 这里不替玩家修剪。
     *
     * <p>三段合在一个贪婪字符串参数里由这里拆分, 而不是拆成三个 Brigadier 参数: {@code #} 与 {@code ,} 都不是
     * Brigadier 不加引号字符串允许的字符, 要让玩家照着 13.7 的写法直接输入, 只能整段收下再解析。
     *
     * @return 缺段或粗体不是 true / false 时为空
     */
    public static Optional<CustomTitleDraft> parse(String input) {
        int colorsEnd = input.indexOf(' ');
        if (colorsEnd <= 0) {
            return Optional.empty();
        }
        int boldEnd = input.indexOf(' ', colorsEnd + 1);
        if (boldEnd < 0) {
            return Optional.empty();
        }
        String boldToken = input.substring(colorsEnd + 1, boldEnd);
        boolean bold;
        if ("true".equalsIgnoreCase(boldToken)) {
            bold = true;
        } else if ("false".equalsIgnoreCase(boldToken)) {
            bold = false;
        } else {
            return Optional.empty();
        }
        List<String> colors = List.of(input.substring(0, colorsEnd).split(",", -1));
        return Optional.of(new CustomTitleDraft(input.substring(boldEnd + 1), colors, bold));
    }
}

package com.miningdim.title;

import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 专属称号校验的一条不合格项 (Title_System_DesignSpec 13.3: 逐条告诉玩家哪里不合格)。
 *
 * @param rule 违反的规则, 决定提示文案的翻译键
 * @param args 文案参数 (位置、字符、颜色、计数等), 已格式化为字符串
 */
public record CustomTitleViolation(Rule rule, List<String> args) {

    public CustomTitleViolation {
        Objects.requireNonNull(rule, "rule");
        args = List.copyOf(args);
    }

    static CustomTitleViolation of(Rule rule, String... args) {
        return new CustomTitleViolation(rule, List.of(args));
    }

    /** 给玩家看的一行说明, 翻译键 {@code title.miningdim.custom.invalid.<规则>}。 */
    public Component message() {
        return Component.translatable(rule.translationKey(), args.toArray());
    }

    /** 校验规则; 每条规则在一次校验里最多报告一次 (字符类规则报告第一个出问题的位置)。 */
    public enum Rule {
        /** 文字为空。 */
        EMPTY,
        /** 总长 (码点) 超过上限。参数: 实际长度、上限。 */
        TOO_LONG,
        /** 没有任何内容字符, 只有符号。 */
        CONTENT_MISSING,
        /** 内容字符超过上限。参数: 实际个数、上限。 */
        CONTENT_TOO_MANY,
        /** 含 § 格式码。参数: 位置。 */
        FORMAT_CODE,
        /** 含换行、控制字符、零宽字符或变体选择符。参数: 位置、码点十六进制。 */
        INVISIBLE_CHAR,
        /** 含私用区字符。参数: 位置、码点十六进制。 */
        PRIVATE_USE,
        /** 含 emoji (且不在符号白名单里)。参数: 位置、码点十六进制。 */
        EMOJI,
        /** 字符不在允许范围内。参数: 位置、该字符。 */
        CHAR_NOT_ALLOWED,
        /** 首尾是空格。 */
        SPACE_EDGE,
        /** 连续空格。参数: 第二个空格的位置。 */
        SPACE_DOUBLE,
        /** 含违禁词 (归一化后按子串匹配)。参数: 配置里的违禁词原文。 */
        BANNED_WORD,
        /** 颜色写法不是 #RRGGBB。参数: 原文。 */
        COLOR_FORMAT,
        /** 色标数不是 1 ~ 3。参数: 实际个数。 */
        COLOR_COUNT,
        /** 色标太暗。参数: 颜色、相对亮度、下限。 */
        COLOR_TOO_DARK,
        /** 色标本身都够亮, 渐变分给某个字的过渡色却太暗。参数: 位置、该字的颜色、相对亮度、下限。 */
        GRADIENT_TOO_DARK;

        public String translationKey() {
            return "title.miningdim.custom.invalid." + name().toLowerCase(Locale.ROOT);
        }
    }
}

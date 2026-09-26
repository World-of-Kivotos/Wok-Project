package com.miningdim.title;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 专属称号的校验阈值与修改冷却 (Title_System_DesignSpec 13.3 / 13.4), 是 {@link TitleConfig} 某一时刻的快照。
 *
 * 校验器只认这个值对象, 不直接读配置: 规则本身因此是纯函数, GameTest 可以逐条构造阈值, 不必改写磁盘上的 toml。
 * 服务端每次校验前经 {@link #fromConfig()} 重新取一次, 运营热改配置后下一次提交即生效。
 *
 * @param maxLength          总长上限 (码点)
 * @param maxContentChars    内容字符上限 (汉字、假名、字母、数字)
 * @param allowedSymbols     允许的符号码点 (半角空格另有专门规则, 不在此列)
 * @param bannedWords        违禁词原文 (归一化在匹配时进行)
 * @param minLuminance       每个色标的 WCAG 相对亮度下限
 * @param editCooldownMillis 两次成功修改之间的冷却 (毫秒)
 */
public record CustomTitleRules(int maxLength, int maxContentChars, Set<Integer> allowedSymbols,
                               List<String> bannedWords, double minLuminance, long editCooldownMillis) {

    public CustomTitleRules {
        allowedSymbols = Set.copyOf(allowedSymbols);
        bannedWords = List.copyOf(bannedWords);
        if (maxLength < 1 || maxContentChars < 1 || editCooldownMillis < 0) {
            throw new IllegalArgumentException("invalid custom title limits");
        }
    }

    /** 读取当前生效的配置。 */
    public static CustomTitleRules fromConfig() {
        return new CustomTitleRules(
                TitleConfig.CUSTOM_MAX_LENGTH.get(),
                TitleConfig.CUSTOM_MAX_CONTENT_CHARS.get(),
                symbols(TitleConfig.CUSTOM_ALLOWED_SYMBOLS.get()),
                List.copyOf(TitleConfig.CUSTOM_BANNED_WORDS.get()),
                TitleConfig.CUSTOM_MIN_LUMINANCE.get(),
                Duration.ofDays(TitleConfig.CUSTOM_EDIT_COOLDOWN_DAYS.get()).toMillis());
    }

    /** 把白名单字符串拆成码点集合; 空白字符不算白名单 (半角空格有自己的首尾与连续规则)。 */
    public static Set<Integer> symbols(String symbols) {
        Set<Integer> codePoints = new LinkedHashSet<>();
        Objects.requireNonNull(symbols, "symbols").codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint))
                .forEach(codePoints::add);
        return codePoints;
    }
}

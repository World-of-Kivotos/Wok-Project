package com.miningdim.title;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * 称号系统服务端配置 (miningdim-title.toml), 目前只有赞助专属称号 (Title_System_DesignSpec 13.3 / 13.4) 的阈值。
 *
 * 全部阈值都是服主口径而不是代码常量: 违禁词表要随社区情况随时补, 长度、亮度、冷却也可能调。由
 * {@link TitleSystem} 走标准的 registerConfig 注册, 与其他模块的 toml 一样支持运行期热改 (每次校验都实时读取,
 * 不在启动时快照), GameTest 下则由 GameTestConfigWatchGuard 统一摘掉监视器。
 */
public final class TitleConfig {

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.IntValue CUSTOM_MAX_LENGTH;
    public static final ForgeConfigSpec.IntValue CUSTOM_MAX_CONTENT_CHARS;
    public static final ForgeConfigSpec.ConfigValue<String> CUSTOM_ALLOWED_SYMBOLS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> CUSTOM_BANNED_WORDS;
    public static final ForgeConfigSpec.DoubleValue CUSTOM_MIN_LUMINANCE;
    public static final ForgeConfigSpec.IntValue CUSTOM_EDIT_COOLDOWN_DAYS;

    /** 默认符号白名单 (13.3): 各种括号与常用装饰符号, 供玩家自己写外框。 */
    static final String DEFAULT_ALLOWED_SYMBOLS = "[]【】〔〕「」『』《》〈〉()（）<>★☆◆◇♦♥♠♣✦✧·・~-_!?！？";

    /** 默认违禁词 (13.3); 完整词表待服主补充 (第十二章待定项 4)。 */
    static final List<String> DEFAULT_BANNED_WORDS = List.of("管理", "服主", "官方", "客服", "OP", "GM", "admin", "owner");

    private TitleConfig() {
    }

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("custom");
        CUSTOM_MAX_LENGTH = builder.comment(
                        "Maximum length of a sponsor custom title, counted in Unicode code points, frame and "
                                + "decoration symbols included")
                .defineInRange("maxLength", 10, 1, 32);
        CUSTOM_MAX_CONTENT_CHARS = builder.comment(
                        "Maximum number of content characters (Han, Hiragana, Katakana, ASCII/fullwidth Latin letters "
                                + "and digits) in a custom title; the remaining length is left for frames and symbols")
                .defineInRange("maxContentChars", 8, 1, 32);
        CUSTOM_ALLOWED_SYMBOLS = builder.comment(
                        "Every non-content character a custom title may use besides a single inner half-width space. "
                                + "The section sign (format codes), control, zero-width/format and line/paragraph "
                                + "separator characters, variation selectors and private-use characters stay "
                                + "forbidden even if listed here. Any other symbol listed here is allowed, including "
                                + "ones inside emoji ranges (the default stars and card suits are), so only list "
                                + "symbols the vanilla font can draw")
                .define("allowedSymbols", DEFAULT_ALLOWED_SYMBOLS);
        CUSTOM_BANNED_WORDS = builder.comment(
                        "Banned words. Before matching, both the title and each word are normalized: NFKC (fullwidth "
                                + "to halfwidth), lower case, and every space and symbol removed; then a substring "
                                + "match is used. Short Latin words such as 'op' or 'gm' therefore also block words "
                                + "that merely contain those letters (e.g. 'shop'); adjust the list if that is too "
                                + "strict. Admin-set titles skip this check")
                .defineListAllowEmpty(List.of("bannedWords"), () -> DEFAULT_BANNED_WORDS,
                        word -> word instanceof String);
        CUSTOM_MIN_LUMINANCE = builder.comment(
                        "Minimum WCAG relative luminance of every color stop. 0.18 is roughly a 4.6:1 contrast "
                                + "ratio against black, readable on the dark chat and tab list backgrounds")
                .defineInRange("minLuminance", 0.18D, 0.0D, 1.0D);
        CUSTOM_EDIT_COOLDOWN_DAYS = builder.comment(
                        "Days a player has to wait after a successful change before changing the custom title "
                                + "again. The first setting is always free; previews never consume the cooldown")
                .defineInRange("customEditCooldownDays", 7, 0, 365);
        builder.pop();

        SPEC = builder.build();
    }
}

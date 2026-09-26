package com.miningdim.title;

import com.miningdim.title.CustomTitleViolation.Rule;
import org.jetbrains.annotations.Nullable;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 专属称号的服务端校验 (Title_System_DesignSpec 13.3), 纯函数: 输入草稿与阈值, 输出全部不合格项或校验后的外观。
 *
 * <p>字符按 Unicode 码点逐个判定, 顺序固定:
 * <ol>
 *   <li>{@code §} 格式码、控制 / 格式 (含零宽) / 行段分隔字符与变体选择符、私用区字符 —— 无条件禁止,
 *       写进符号白名单也不放行;</li>
 *   <li>半角空格: 只允许出现在中间, 且不能连续;</li>
 *   <li>内容字符 (汉字、平假名、片假名、ASCII 与全角拉丁字母和数字) 与白名单符号放行, 前者计入内容字符数;</li>
 *   <li>其余字符若落在 emoji 区段报 emoji, 否则报"不在允许范围内"。</li>
 * </ol>
 * 拉丁字母只收 ASCII 与全角两套: 带附加符号的字母、小型大写、上标之类的变体与 ASCII 字母形近, 而 NFKC 并不都能
 * 把它们归一, 收下就给违禁词留了后门。同理, 西里尔、希腊等形近字母一律不收。
 *
 * <p>违禁词匹配前, 称号与词表两边都做同一套归一化 ({@link #normalizeForBannedWords}): NFKC (全角转半角、兼容字
 * 转标准字)、转小写、去掉一切空格与符号, 然后按子串匹配。"管 理""ＯＰ""服★主"这类写法因此都能识破。有一类字本身
 * 算内容字符、看上去却只是一笔或一个记号 (长音符、单笔画汉字、重复记号, 见 {@link #SEPARATOR_LIKE_LETTERS}),
 * 夹在词中间起的也是分隔作用, 所以称号再去掉这些字比一次 ("管ー理""服一主""OーP")。
 *
 * <p>亮度下限既看每个色标, 也看渐变按 {@link TierPalette#gradientColors} 实际分给每个字的颜色: 两个各自够亮的色标
 * 之间, 线性插值出来的过渡色可能暗得多。
 *
 * <p>每条规则一次最多报告一次 (字符类规则报第一个出问题的位置), 多条规则同时不合格时全部报告。
 */
public final class CustomTitleValidator {

    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");
    private static final int SECTION_SIGN = 0x00A7;
    private static final int SPACE = ' ';
    /** 片假名长音符 "ー": 脚本属性是 Common, 但它是片假名词里的常规字母, 按内容字符计。 */
    private static final int KATAKANA_PROLONGED_SOUND_MARK = 0x30FC;

    /**
     * 本身算内容字符、看上去却只是一笔横竖撇点或一个重复记号的字。它们照常可以写进称号, 但夹在违禁词中间时起的是
     * 分隔作用, 匹配违禁词时称号这一侧要再去掉它们比一次。半角的ﾉ经 NFKC 先变成全角, 一并覆盖。
     * 词表一侧不去掉: 服主把含这些字的词 (如"一哥") 写进词表时仍按原样匹配, 不会被放宽成更短的词。
     */
    private static final Set<Integer> SEPARATOR_LIKE_LETTERS = Set.of(
            KATAKANA_PROLONGED_SOUND_MARK,
            // 单笔画汉字 一 丨 丶 丿 乀 乁 乛 亅, 与同为一撇的片假名 ノ
            0x4E00, 0x4E28, 0x4E36, 0x4E3F, 0x4E40, 0x4E41, 0x4E5B, 0x4E85, 0x30CE,
            // 重复记号 々 〻 ゝ ゞ ヽ ヾ
            0x3005, 0x303B, 0x309D, 0x309E, 0x30FD, 0x30FE);

    private CustomTitleValidator() {
    }

    /**
     * 校验一份草稿。
     *
     * @param checkBannedWords 是否检查违禁词; 管理员代设置时为 false (13.7), 其余规则照常检查
     */
    public static Validation validate(CustomTitleDraft draft, CustomTitleRules rules, boolean checkBannedWords) {
        List<CustomTitleViolation> violations = new ArrayList<>();
        checkText(draft.text(), rules, checkBannedWords, violations);
        List<Integer> colors = checkColors(draft.text(), draft.colors(), rules, violations);
        if (!violations.isEmpty()) {
            return new Validation(violations, null);
        }
        return new Validation(List.of(), new CustomTitleStyle(draft.text(), colors, draft.bold()));
    }

    private static void checkText(String text, CustomTitleRules rules, boolean checkBannedWords,
                                  List<CustomTitleViolation> violations) {
        int[] codePoints = text.codePoints().toArray();
        if (codePoints.length == 0) {
            violations.add(CustomTitleViolation.of(Rule.EMPTY));
            return;
        }
        if (codePoints.length > rules.maxLength()) {
            violations.add(CustomTitleViolation.of(Rule.TOO_LONG,
                    String.valueOf(codePoints.length), String.valueOf(rules.maxLength())));
        }
        EnumSet<Rule> reported = EnumSet.noneOf(Rule.class);
        int contentChars = 0;
        for (int i = 0; i < codePoints.length; i++) {
            int codePoint = codePoints[i];
            String position = String.valueOf(i + 1);
            if (codePoint == SPACE) {
                if (i == 0 || i == codePoints.length - 1) {
                    reportOnce(reported, violations, CustomTitleViolation.of(Rule.SPACE_EDGE));
                }
                if (i > 0 && codePoints[i - 1] == SPACE) {
                    reportOnce(reported, violations, CustomTitleViolation.of(Rule.SPACE_DOUBLE, position));
                }
                continue;
            }
            Rule rule = classify(codePoint, rules.allowedSymbols());
            if (rule == null) {
                if (isContentChar(codePoint)) {
                    contentChars++;
                }
                continue;
            }
            String hex = String.format(Locale.ROOT, "%04X", codePoint);
            CustomTitleViolation violation = switch (rule) {
                case FORMAT_CODE -> CustomTitleViolation.of(rule, position);
                case CHAR_NOT_ALLOWED -> CustomTitleViolation.of(rule, position, Character.toString(codePoint));
                default -> CustomTitleViolation.of(rule, position, hex);
            };
            reportOnce(reported, violations, violation);
        }
        if (contentChars == 0) {
            violations.add(CustomTitleViolation.of(Rule.CONTENT_MISSING));
        } else if (contentChars > rules.maxContentChars()) {
            violations.add(CustomTitleViolation.of(Rule.CONTENT_TOO_MANY,
                    String.valueOf(contentChars), String.valueOf(rules.maxContentChars())));
        }
        if (checkBannedWords) {
            String normalized = normalizeForBannedWords(text);
            String unseparated = withoutSeparatorLetters(normalized);
            for (String word : rules.bannedWords()) {
                String needle = normalizeForBannedWords(word);
                if (!needle.isEmpty() && (normalized.contains(needle) || unseparated.contains(needle))) {
                    violations.add(CustomTitleViolation.of(Rule.BANNED_WORD, word));
                    break;
                }
            }
        }
    }

    /** 单个非空格码点的判定, 顺序见类注释; 放行返回 null。 */
    @Nullable
    private static Rule classify(int codePoint, Set<Integer> allowedSymbols) {
        if (codePoint == SECTION_SIGN) {
            return Rule.FORMAT_CODE;
        }
        if (isInvisible(codePoint)) {
            return Rule.INVISIBLE_CHAR;
        }
        if (Character.getType(codePoint) == Character.PRIVATE_USE) {
            return Rule.PRIVATE_USE;
        }
        if (isContentChar(codePoint) || allowedSymbols.contains(codePoint)) {
            return null;
        }
        return isEmoji(codePoint) ? Rule.EMOJI : Rule.CHAR_NOT_ALLOWED;
    }

    private static void reportOnce(EnumSet<Rule> reported, List<CustomTitleViolation> violations,
                                   CustomTitleViolation violation) {
        if (reported.add(violation.rule())) {
            violations.add(violation);
        }
    }

    private static List<Integer> checkColors(String text, List<String> raw, CustomTitleRules rules,
                                             List<CustomTitleViolation> violations) {
        List<Integer> parsed = new ArrayList<>(raw.size());
        boolean formatReported = false;
        for (String color : raw) {
            OptionalInt value = parseColor(color);
            if (value.isPresent()) {
                parsed.add(value.getAsInt());
            } else if (!formatReported) {
                violations.add(CustomTitleViolation.of(Rule.COLOR_FORMAT, color));
                formatReported = true;
            }
        }
        if (raw.isEmpty() || raw.size() > TierPalette.MAX_GRADIENT_STOPS) {
            violations.add(CustomTitleViolation.of(Rule.COLOR_COUNT, String.valueOf(raw.size())));
            return parsed;
        }
        // 亮度逐个色标报告: 最多 3 条, 玩家一次就能知道要换掉哪几个颜色。
        boolean stopTooDark = false;
        for (int color : parsed) {
            double luminance = relativeLuminance(color);
            if (luminance < rules.minLuminance()) {
                violations.add(CustomTitleViolation.of(Rule.COLOR_TOO_DARK, hex(color),
                        luminanceText(luminance), luminanceText(rules.minLuminance())));
                stopTooDark = true;
            }
        }
        // 过渡色只在色标都合格时再看: 有色标过暗时玩家先换色标, 不再叠一条由它插值出来的过渡色报告。
        if (!stopTooDark && parsed.size() > 1 && parsed.size() == raw.size()) {
            checkGradient(text, parsed, rules, violations);
        }
        return parsed;
    }

    /**
     * 渐变逐字渲染出的过渡色也要过亮度下限: 两个各自够亮的色标之间, sRGB 线性插值出来的颜色可能暗得多 (例如红到绿
     * 的中段发褐)。按 {@link TierPalette#gradientColors} 核对这段文字实际分到的每一个颜色 (与渲染共用同一份插值),
     * 空格看不见, 不计; 只报第一个过暗的字。首尾两个字恰为首尾色标, 已在上一步查过, 不会重复报告。
     */
    private static void checkGradient(String text, List<Integer> stops, CustomTitleRules rules,
                                      List<CustomTitleViolation> violations) {
        int[] codePoints = text.codePoints().toArray();
        int[] colors = TierPalette.gradientColors(stops.stream().mapToInt(Integer::intValue).toArray(),
                codePoints.length);
        for (int i = 0; i < codePoints.length; i++) {
            if (codePoints[i] == SPACE) {
                continue;
            }
            double luminance = relativeLuminance(colors[i]);
            if (luminance < rules.minLuminance()) {
                violations.add(CustomTitleViolation.of(Rule.GRADIENT_TOO_DARK, String.valueOf(i + 1),
                        hex(colors[i]), luminanceText(luminance), luminanceText(rules.minLuminance())));
                return;
            }
        }
    }

    private static String hex(int color) {
        return String.format(Locale.ROOT, "#%06X", color);
    }

    private static String luminanceText(double luminance) {
        return String.format(Locale.ROOT, "%.3f", luminance);
    }

    /** 解析 {@code #RRGGBB} (不分大小写); 其余写法 (颜色名、3 位简写、缺井号) 一律为空。 */
    public static OptionalInt parseColor(@Nullable String raw) {
        if (raw == null || !HEX_COLOR.matcher(raw).matches()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(Integer.parseInt(raw.substring(1), 16));
    }

    /** WCAG 2.x 相对亮度 (0 为纯黑, 1 为纯白), 输入 0xRRGGBB。 */
    public static double relativeLuminance(int rgb) {
        return 0.2126D * linearChannel(rgb >> 16 & 0xFF)
                + 0.7152D * linearChannel(rgb >> 8 & 0xFF)
                + 0.0722D * linearChannel(rgb & 0xFF);
    }

    private static double linearChannel(int channel) {
        double srgb = channel / 255.0D;
        return srgb <= 0.03928D ? srgb / 12.92D : Math.pow((srgb + 0.055D) / 1.055D, 2.4D);
    }

    /** 违禁词匹配用的归一化: NFKC、转小写、只保留字母与数字 (去掉一切空格与符号)。 */
    public static String normalizeForBannedWords(String text) {
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder kept = new StringBuilder(folded.length());
        folded.codePoints().filter(Character::isLetterOrDigit).forEach(kept::appendCodePoint);
        return kept.toString();
    }

    /** 归一化后的称号再去掉起分隔作用的笔画字与重复记号 ({@link #SEPARATOR_LIKE_LETTERS}), 只用于称号一侧。 */
    private static String withoutSeparatorLetters(String normalized) {
        StringBuilder kept = new StringBuilder(normalized.length());
        normalized.codePoints().filter(codePoint -> !SEPARATOR_LIKE_LETTERS.contains(codePoint))
                .forEach(kept::appendCodePoint);
        return kept.toString();
    }

    /** 内容字符: 汉字、平假名、片假名 (含长音符)、ASCII 与全角的拉丁字母和数字。 */
    static boolean isContentChar(int codePoint) {
        if (codePoint >= '0' && codePoint <= '9' || codePoint >= 'A' && codePoint <= 'Z'
                || codePoint >= 'a' && codePoint <= 'z') {
            return true;
        }
        if (codePoint >= 0xFF10 && codePoint <= 0xFF19 || codePoint >= 0xFF21 && codePoint <= 0xFF3A
                || codePoint >= 0xFF41 && codePoint <= 0xFF5A) {
            return true;
        }
        if (codePoint == KATAKANA_PROLONGED_SOUND_MARK) {
            return true;
        }
        if (!Character.isLetter(codePoint)) {
            return false;
        }
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA;
    }

    /** 看不见或会改变排版的字符: 控制字符 (含换行)、格式字符 (零宽空格 / 连接符、BOM、方向标记等)、行段分隔符、变体选择符。 */
    private static boolean isInvisible(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                || codePoint >= 0xFE00 && codePoint <= 0xFE0F
                || codePoint >= 0xE0100 && codePoint <= 0xE01EF;
    }

    /**
     * emoji 所在区段 (Java 17 没有 emoji 属性查询): 补充平面的图形符号整段 (U+1F000..U+1FAFF, 含国旗用的区域指示符)、
     * 杂项符号与装饰符号 (U+2600..U+27BF), 以及散落在其他区块里的常见 emoji。白名单里的 ★♥ 等在此之前已放行。
     */
    private static boolean isEmoji(int codePoint) {
        return codePoint >= 0x1F000 && codePoint <= 0x1FAFF
                || codePoint >= 0x2600 && codePoint <= 0x27BF
                || codePoint == 0x231A || codePoint == 0x231B
                || codePoint >= 0x23E9 && codePoint <= 0x23F3
                || codePoint >= 0x23F8 && codePoint <= 0x23FA
                || codePoint == 0x2B1B || codePoint == 0x2B1C || codePoint == 0x2B50 || codePoint == 0x2B55
                || codePoint == 0x20E3;
    }

    /**
     * 一次校验的结果。
     *
     * @param violations 全部不合格项; 合格时为空
     * @param style      合格时为校验后的外观 (颜色已解析), 否则为 null
     */
    public record Validation(List<CustomTitleViolation> violations, @Nullable CustomTitleStyle style) {

        public Validation {
            violations = List.copyOf(violations);
        }

        public boolean valid() {
            return style != null;
        }
    }
}

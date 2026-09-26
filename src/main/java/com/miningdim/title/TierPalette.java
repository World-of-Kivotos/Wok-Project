package com.miningdim.title;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 七档色板, 称号稀有度与成就档位共用的唯一真源 (Title_System_DesignSpec 3.1 / Achievement_System_DesignSpec 第四章)。
 *
 * 前三档单色、不加粗; 白金起为三色标逐字渐变并加粗。16 色降级码给不支持 RGB 的场合 (如纯文本日志、旧式 § 文本)
 * 使用。成就模块直接引用本枚举, 不得各自复制色值 —— 色值一旦出现第二份, 两边改一边漏一边就是必然结局。
 *
 * 渐变的做法是把文字按码点逐字拆成带 RGB 颜色的 Component 片段, 原版客户端原生支持, 聊天、Tab 列表、
 * L 键进度界面都能显示, 不需要客户端改渲染。
 */
public enum TierPalette {
    BRONZE("bronze", false, 100, List.of(ChatFormatting.GOLD), 0xC8834A),
    SILVER("silver", false, 200, List.of(ChatFormatting.GRAY), 0xD0D7DE),
    GOLD("gold", false, 300, List.of(ChatFormatting.YELLOW), 0xFFCC33),
    PLATINUM("platinum", true, 400, List.of(ChatFormatting.WHITE, ChatFormatting.BOLD),
            0xFFFFFF, 0xFFF1C9, 0xFFD66B),
    DIAMOND("diamond", true, 500, List.of(ChatFormatting.AQUA, ChatFormatting.BOLD),
            0x6FF2FF, 0x7FB0FF, 0xD59CFF),
    MASTER("master", true, 600, List.of(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD),
            0xA55CFF, 0xE05CFF, 0xFF5CB8),
    LEGEND("legend", true, 700, List.of(ChatFormatting.RED, ChatFormatting.BOLD),
            0xFF3D3D, 0xFF8A1F, 0xFFD23F);

    /** 自定义渐变允许的色标数上限 (与内置档位一致, 最多三色)。 */
    public static final int MAX_GRADIENT_STOPS = 3;

    private final String id;
    private final boolean bold;
    private final int defaultSort;
    private final List<ChatFormatting> legacyFormatting;
    private final int[] stops;

    TierPalette(String id, boolean bold, int defaultSort, List<ChatFormatting> legacyFormatting, int... stops) {
        this.id = id;
        this.bold = bold;
        this.defaultSort = defaultSort;
        this.legacyFormatting = legacyFormatting;
        this.stops = stops;
    }

    /** 数据包与存储里使用的小写 id (bronze ... legend)。 */
    public String id() {
        return id;
    }

    /** 该档默认是否加粗 (白金起加粗)。 */
    public boolean bold() {
        return bold;
    }

    /** 是否为渐变档 (色标多于一个)。 */
    public boolean isGradient() {
        return stops.length > 1;
    }

    /** 色标 (0xRRGGBB), 单色档只有一个元素; 返回副本。 */
    public int[] stops() {
        return stops.clone();
    }

    /** 主色: 单色档即其颜色, 渐变档取第一个色标。只能整段上色的场合 (如整条进度标题) 用它。 */
    public int primaryColor() {
        return stops[0];
    }

    /** 称号未写 sort 时的默认排序权重, 档位越高越大。 */
    public int defaultSort() {
        return defaultSort;
    }

    /** 16 色降级码字符串 (颜色 + 可选粗体), 例如 {@code §b§l}。 */
    public String legacyCode() {
        StringBuilder code = new StringBuilder();
        for (ChatFormatting formatting : legacyFormatting) {
            code.append(formatting);
        }
        return code.toString();
    }

    /** 只能整段上色时使用的样式: 主色 + 该档粗体。 */
    public Style baseStyle() {
        return Style.EMPTY.withColor(TextColor.fromRgb(primaryColor())).withBold(bold);
    }

    /** 按 id 查档位 (大小写不敏感); 未知 id 返回空。 */
    public static Optional<TierPalette> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalized = id.toLowerCase(Locale.ROOT);
        for (TierPalette tier : values()) {
            if (tier.id.equals(normalized)) {
                return Optional.of(tier);
            }
        }
        return Optional.empty();
    }

    /**
     * 逐字渐变: 按 Unicode 码点拆分 (代理对不会被劈成两半), 第 i 个字取渐变轴上 i/(n-1) 处的颜色, 因此首字恰为
     * 首色标、末字恰为末色标。只有一个色标时整段同色 (仍逐字拆分, 结构与渐变一致, 调用方无需区分)。
     *
     * @param text  要上色的文字 (已解析好的纯文本)
     * @param stops 1 ~ {@value #MAX_GRADIENT_STOPS} 个 0xRRGGBB 色标
     * @param bold  是否加粗
     * @return 空样式根节点 + 每个字一个带颜色的子片段
     */
    public static MutableComponent gradient(String text, int[] stops, boolean bold) {
        if (stops == null || stops.length == 0 || stops.length > MAX_GRADIENT_STOPS) {
            throw new IllegalArgumentException("gradient needs 1.." + MAX_GRADIENT_STOPS + " color stops");
        }
        MutableComponent root = Component.empty();
        int[] codePoints = text.codePoints().toArray();
        int count = codePoints.length;
        for (int i = 0; i < count; i++) {
            float position = count == 1 ? 0.0F : (float) i / (count - 1);
            Style style = Style.EMPTY.withColor(TextColor.fromRgb(colorAt(stops, position))).withBold(bold);
            root.append(Component.literal(new String(Character.toChars(codePoints[i]))).withStyle(style));
        }
        return root;
    }

    /** 渐变轴上 position (0..1) 处的颜色; 多色标时在相邻两个色标之间线性插值。 */
    private static int colorAt(int[] stops, float position) {
        if (stops.length == 1) {
            return stops[0];
        }
        float clamped = Math.max(0.0F, Math.min(1.0F, position));
        float scaled = clamped * (stops.length - 1);
        int segment = Math.min((int) scaled, stops.length - 2);
        return lerpColor(stops[segment], stops[segment + 1], scaled - segment);
    }

    /** 两个 0xRRGGBB 颜色按通道线性插值 (四舍五入)。 */
    private static int lerpColor(int from, int to, float t) {
        int r = lerpChannel(from >> 16 & 0xFF, to >> 16 & 0xFF, t);
        int g = lerpChannel(from >> 8 & 0xFF, to >> 8 & 0xFF, t);
        int b = lerpChannel(from & 0xFF, to & 0xFF, t);
        return r << 16 | g << 8 | b;
    }

    private static int lerpChannel(int from, int to, float t) {
        return Math.round(from + (to - from) * t);
    }
}

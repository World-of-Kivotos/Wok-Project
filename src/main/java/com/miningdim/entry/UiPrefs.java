package com.miningdim.entry;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.regex.Pattern;

/**
 * 账号级 WebUI 界面偏好 (WebUI 接线 W1 决策 D1)。落玩家 capability 随 player.dat 走, 因此换机器 / 清浏览器
 * 缓存都不丢 —— 这是把它从前端 localStorage 搬进存档的唯一理由。
 *
 * 只收前端真有控件在改的四项。像素风时代遗留的 uiScale / layout 已砍: 前端零控件零读取, 而字段一旦写进
 * player.dat, 想删就要永久背一个 deserialize 兼容分支。
 *
 * 读写两条路径刻意分开, 不许合并 (W1 对抗复核 M6):
 *  - 写路径 (player.prefs.set) 走本 record 的规范构造器, 任一取值域不满足直接抛 —— 写入侧静默钳制等于让契约
 *    声明的取值域失效, 前端再也无法发现自己发了非法值;
 *  - 读路径 (存档反序列化) 走 {@link #sanitized(CompoundTag)}, 逐字段独立回退且绝不抛 —— 这条路径跑在玩家
 *    capability 反序列化时, 上游没有 Gateway 兜底, 抛出去的症状是玩家进不来或 capability 静默失效。
 *
 * 默认值必须与前端首帧默认值同值 (webui 的 lib/theme.ts 默认 dark、lib/brand.ts DEFAULT_BRAND.hue=250、
 * index.css 的 --brand-h), 否则服务端偏好到达前后会闪一次色。
 */
public record UiPrefs(boolean muteToasts, String language, String theme, int brandHue) {

    /** 存档子标签内的键名, 与 TS 侧 PlayerPrefs 字段名逐字一致 (前后端对读 NBT dump 时免翻译)。 */
    private static final String K_MUTE_TOASTS = "muteToasts";
    private static final String K_LANGUAGE = "language";
    private static final String K_THEME = "theme";
    private static final String K_BRAND_HUE = "brandHue";

    /** 亮暗档的完整取值域 (二值)。 */
    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";

    /**
     * 语言码形状约束 (MC lang code 形态)。刻意不硬编码语言白名单: 否则每支持一种新语言都要发一次 mod jar,
     * 而语言包本身是纯前端资源。
     */
    private static final Pattern LANGUAGE_PATTERN = Pattern.compile("[a-z0-9_]{1,16}");

    /** 强调色 oklch 色相角的闭区间端点。 */
    public static final int BRAND_HUE_MIN = 0;
    public static final int BRAND_HUE_MAX = 360;

    /** 当前界面唯一真能生效的语言档。 */
    public static final String DEFAULT_LANGUAGE = "zh_cn";
    public static final int DEFAULT_BRAND_HUE = 250;

    /** 从未设置过 / 存档缺键 / 存档值非法时的整份回退。 */
    public static final UiPrefs DEFAULT =
            new UiPrefs(false, DEFAULT_LANGUAGE, THEME_DARK, DEFAULT_BRAND_HUE);

    public UiPrefs {
        if (!isValidLanguage(language)) {
            throw new IllegalArgumentException(
                    "language must match " + LANGUAGE_PATTERN.pattern() + ", got " + language);
        }
        if (!isValidTheme(theme)) {
            throw new IllegalArgumentException(
                    "theme must be " + THEME_DARK + " or " + THEME_LIGHT + ", got " + theme);
        }
        if (!isValidBrandHue(brandHue)) {
            throw new IllegalArgumentException(
                    "brandHue must be within [" + BRAND_HUE_MIN + "," + BRAND_HUE_MAX + "], got " + brandHue);
        }
    }

    // ---- 逐字段校验 (public: 写路径的调用方需要知道是哪一个字段非法才能把 field 名填进错误回执的 params,
    //      靠 catch IllegalArgumentException 解析异常文本是不可维护的) ----

    public static boolean isValidLanguage(String language) {
        return language != null && LANGUAGE_PATTERN.matcher(language).matches();
    }

    public static boolean isValidTheme(String theme) {
        return THEME_DARK.equals(theme) || THEME_LIGHT.equals(theme);
    }

    public static boolean isValidBrandHue(int brandHue) {
        return brandHue >= BRAND_HUE_MIN && brandHue <= BRAND_HUE_MAX;
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(K_MUTE_TOASTS, muteToasts);
        tag.putString(K_LANGUAGE, language);
        tag.putString(K_THEME, theme);
        tag.putInt(K_BRAND_HUE, brandHue);
        return tag;
    }

    /**
     * 从存档子标签还原, 逐字段独立回退到默认值, 绝不抛 (读路径契约)。
     *
     * 三类脏数据都走同一条回退: 缺键 (旧存档没有这个子标签)、类型不符 (手改存档 / 跨版本改了字段类型)、
     * 取值域外 (服务端后来收窄了取值域, 例如删掉某个主题档)。一个字段非法不牵连其余三个 —— 玩家不该因为
     * 主题名过期就把语言和强调色一起丢掉。
     *
     * brandHue 越界刻意回退到默认值而不是钳到端点: 999 这种值说明写入方口径已经错了 (可能是角度制/百分比混用),
     * 钳成 360 会得到一个看似合理实则不是玩家选过的颜色, 回默认色至少是个玩家认得出的"被重置了"信号。
     */
    public static UiPrefs sanitized(CompoundTag tag) {
        // putBoolean 落的是 ByteTag, 故存在性判定用 TAG_BYTE。
        boolean muteToasts = tag.contains(K_MUTE_TOASTS, Tag.TAG_BYTE) && tag.getBoolean(K_MUTE_TOASTS);

        String language = DEFAULT_LANGUAGE;
        if (tag.contains(K_LANGUAGE, Tag.TAG_STRING)) {
            String stored = tag.getString(K_LANGUAGE);
            if (isValidLanguage(stored)) {
                language = stored;
            }
        }

        String theme = THEME_DARK;
        if (tag.contains(K_THEME, Tag.TAG_STRING)) {
            String stored = tag.getString(K_THEME);
            if (isValidTheme(stored)) {
                theme = stored;
            }
        }

        int brandHue = DEFAULT_BRAND_HUE;
        if (tag.contains(K_BRAND_HUE, Tag.TAG_INT)) {
            int stored = tag.getInt(K_BRAND_HUE);
            if (isValidBrandHue(stored)) {
                brandHue = stored;
            }
        }

        return new UiPrefs(muteToasts, language, theme, brandHue);
    }
}

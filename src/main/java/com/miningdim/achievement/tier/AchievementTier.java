package com.miningdim.achievement.tier;

import com.miningdim.title.TierPalette;
import net.minecraft.advancements.FrameType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;
import java.util.Optional;

/**
 * 成就的七个档位 (Achievement_System_DesignSpec 第四章), 档位是原版框体、全服公告、默认成就点与标题颜色的唯一真源。
 *
 * 原版只有三种框体, "档位"是本模块自己的概念: datagen 从这里推导进度 JSON 的框体、公告开关与标题颜色, 运行时的一致性
 * 校验再拿同一张表去核对服务端实际加载的进度 (4.1)。颜色不在本类重复定义, 一律引用称号模块的
 * {@link TierPalette} —— 称号稀有度与成就档位共用同一份色板, 色值出现第二份就迟早会漂移。
 *
 * <p>隐藏成就 ({@code hidden: true}) 可以叠加在任意档位上, 隐藏成就一律开启全服公告 ({@link #announcesToChat(boolean)})。
 */
public enum AchievementTier {
    BRONZE(TierPalette.BRONZE, FrameType.TASK, false, 10),
    SILVER(TierPalette.SILVER, FrameType.TASK, false, 25),
    GOLD(TierPalette.GOLD, FrameType.GOAL, true, 50),
    PLATINUM(TierPalette.PLATINUM, FrameType.GOAL, true, 100),
    DIAMOND(TierPalette.DIAMOND, FrameType.CHALLENGE, true, 200),
    MASTER(TierPalette.MASTER, FrameType.CHALLENGE, true, 400),
    LEGEND(TierPalette.LEGEND, FrameType.CHALLENGE, true, 800);

    private final TierPalette palette;
    private final FrameType frame;
    private final boolean announce;
    private final int defaultPoints;

    AchievementTier(TierPalette palette, FrameType frame, boolean announce, int defaultPoints) {
        this.palette = palette;
        this.frame = frame;
        this.announce = announce;
        this.defaultPoints = defaultPoints;
    }

    /** 元数据、存储与命令里使用的小写 id, 与称号稀有度 id 相同 (bronze ... legend)。 */
    public String id() {
        return palette.id();
    }

    /** 共用色板的对应档。 */
    public TierPalette palette() {
        return palette;
    }

    /** 原版框体: 铜银 task、金白金 goal、钻石以上 challenge。 */
    public FrameType frame() {
        return frame;
    }

    /** 进度 JSON 的 announce_to_chat: 档位本身公告 (金档起), 或者是隐藏成就。 */
    public boolean announcesToChat(boolean hidden) {
        return announce || hidden;
    }

    /** 默认成就点 (元数据未覆盖时使用)。 */
    public int defaultPoints() {
        return defaultPoints;
    }

    /** 是否为逐字渐变档 (白金起)。 */
    public boolean isGradient() {
        return palette.isGradient();
    }

    /**
     * 进度标题 Component。单色档保留 translate、整段套档位颜色, 仍由各客户端按自己的语言显示; 渐变档要逐字上色,
     * 必须先拿到纯文本, 所以用调用方给的已解析文字 (datagen 取自模组自带的 zh_cn 语言表) 按
     * {@link TierPalette#gradient} 拆成逐字字面量, 代价是英文客户端看到的渐变标题也是中文 (与称号模块同一取舍)。
     *
     * @param translationKey 标题的翻译键
     * @param resolvedText   渐变档使用的纯文本; 单色档忽略
     */
    public MutableComponent title(String translationKey, String resolvedText) {
        if (isGradient()) {
            return TierPalette.gradient(resolvedText, palette.stops(), palette.bold());
        }
        return Component.translatable(translationKey).withStyle(palette.baseStyle());
    }

    /** 按 id 查档位 (大小写不敏感); 未知 id 返回空。 */
    public static Optional<AchievementTier> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalized = id.toLowerCase(Locale.ROOT);
        for (AchievementTier tier : values()) {
            if (tier.id().equals(normalized)) {
                return Optional.of(tier);
            }
        }
        return Optional.empty();
    }
}

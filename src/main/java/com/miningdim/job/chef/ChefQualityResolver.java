package com.miningdim.job.chef;

/**
 * 综合分 -> 达成品质档 (Chef_Job_DesignSpec 第四章 "品质档 = 火候精度 + 调味命中 的综合分", 受台档 +
 * 厨师等级双重封顶)。
 *
 * 综合分: 火候精度 (0-1) 与调味命中比 (命中/总时机点, 0-1) 各占一半, 得 [0,1] 综合分; 映射到 0-4 档原始档,
 * 再 min(台档上限, 厨师等级上限) 双重封顶 (第四章: 台档 + 厨师等级决定能出的最高品质)。
 *
 * 厨师等级上限 ({@link #qualityCapForLevel}): 低级厨师即便满分也封顶 (第七章 "L1 只能做低级菜")。
 */
public final class ChefQualityResolver {

    private ChefQualityResolver() {
    }

    /**
     * 厨师等级 -> 可达成的最高品质档 (第七章节奏: 等级越高解锁越高档)。
     * L1-2 低, L3-4 中, L5-6 高, L7-8 超凡, L9-10 闪耀。
     */
    public static ChefQuality qualityCapForLevel(int chefLevel) {
        if (chefLevel >= 9) {
            return ChefQuality.RADIANT;
        }
        if (chefLevel >= 7) {
            return ChefQuality.EXTRAORDINARY;
        }
        if (chefLevel >= 5) {
            return ChefQuality.HIGH;
        }
        if (chefLevel >= 3) {
            return ChefQuality.MEDIUM;
        }
        return ChefQuality.LOW;
    }

    /**
     * 解析达成品质档 (双重封顶)。
     *
     * @param heatAccuracy 火候精度 [0,1] ({@link ChefHeatGame#accuracyScore()})
     * @param hits         调味命中数
     * @param totalCues    调味时机点总数 (>0; 命中比 = hits/totalCues)
     * @param tableCap     台档上限 ({@link SeasoningTableBlock#tierCap()})
     * @param chefLevel    操作厨师等级
     * @return 达成品质档 (min(综合分档, 台档, 等级档))
     */
    public static ChefQuality resolve(double heatAccuracy, int hits, int totalCues,
                                      ChefQuality tableCap, int chefLevel) {
        double hitRatio = totalCues > 0 ? Math.min(1.0D, (double) hits / totalCues) : 0.0D;
        double composite = 0.5D * clamp01(heatAccuracy) + 0.5D * hitRatio;
        // 综合分 -> 原始档 (0-4): 阈值 [0,.35)->低 [.35,.55)->中 [.55,.75)->高 [.75,.9)->超凡 [.9,1]->闪耀。
        ChefQuality rawTier;
        if (composite >= 0.90D) {
            rawTier = ChefQuality.RADIANT;
        } else if (composite >= 0.75D) {
            rawTier = ChefQuality.EXTRAORDINARY;
        } else if (composite >= 0.55D) {
            rawTier = ChefQuality.HIGH;
        } else if (composite >= 0.35D) {
            rawTier = ChefQuality.MEDIUM;
        } else {
            rawTier = ChefQuality.LOW;
        }
        ChefQuality levelCap = qualityCapForLevel(chefLevel);
        // 双重封顶: min(原始档, 台档, 等级档)。
        ChefQuality capped = ChefQuality.min(rawTier, tableCap);
        return ChefQuality.min(capped, levelCap);
    }

    private static double clamp01(double v) {
        if (v < 0.0D) {
            return 0.0D;
        }
        return Math.min(v, 1.0D);
    }
}

package com.miningdim.job.chef;

import net.minecraft.util.RandomSource;

/**
 * 目标品质挑战结算。台档与厨师等级决定可选上限，控火精度决定动态加成，每次正确 QTE 直接增加成功率。
 * 服务端只掷一次；成功产出目标品质，未达成则降一档，避免完成小游戏后吞菜或空结算。
 */
public final class ChefQualityResolver {

    private ChefQualityResolver() {
    }

    /**
     * 厨师等级 -> 可达成的最高品质档 (第七章节奏: 等级越高解锁越高档)。
     * L1-2 低, L3-4 中, L5-6 高, L7-8 超凡, L9-10 闪耀。
     */
    public static ChefQuality qualityCapForLevel(int chefLevel) {
        int mediumUnlock = ChefConfig.qualityMediumUnlockLevel();
        int highUnlock = ChefConfig.qualityHighUnlockLevel();
        int extraordinaryUnlock = ChefConfig.qualityExtraordinaryUnlockLevel();
        int radiantUnlock = ChefConfig.qualityRadiantUnlockLevel();
        validateUnlockLevels(mediumUnlock, highUnlock, extraordinaryUnlock, radiantUnlock);
        if (chefLevel >= radiantUnlock) {
            return ChefQuality.RADIANT;
        }
        if (chefLevel >= extraordinaryUnlock) {
            return ChefQuality.EXTRAORDINARY;
        }
        if (chefLevel >= highUnlock) {
            return ChefQuality.HIGH;
        }
        if (chefLevel >= mediumUnlock) {
            return ChefQuality.MEDIUM;
        }
        return ChefQuality.LOW;
    }

    public static ChefQuality selectableCap(ChefQuality tableCap, int chefLevel) {
        return ChefQuality.min(tableCap, qualityCapForLevel(chefLevel));
    }

    public static int successChancePerMille(ChefQuality target, double heatAccuracy, int hits, int totalCues) {
        if (heatAccuracy < 0.0D || heatAccuracy > 1.0D) {
            throw new IllegalArgumentException("heatAccuracy must be in [0,1], got " + heatAccuracy);
        }
        if (totalCues <= 0 || hits < 0 || hits > totalCues) {
            throw new IllegalArgumentException("QTE hits must be in [0,totalCues], got hits=" + hits
                    + ", totalCues=" + totalCues);
        }
        int chance = ChefConfig.targetBaseChancePerMille(target)
                + (int) Math.round(heatAccuracy * ChefConfig.targetHeatBonusPerMille())
                + hits * ChefConfig.targetQteHitBonusPerMille();
        return Math.min(1000, chance);
    }

    public static ChefQuality resolveTarget(RandomSource random, ChefQuality target, double heatAccuracy,
                                            int hits, int totalCues) {
        int chance = successChancePerMille(target, heatAccuracy, hits, totalCues);
        return resolveTargetRoll(target, chance, random.nextInt(1000));
    }

    static ChefQuality resolveTargetRoll(ChefQuality target, int chancePerMille, int roll) {
        if (chancePerMille == 1000 || roll < chancePerMille) {
            return target;
        }
        return ChefQuality.byTier(target.tier() - 1);
    }

    private static void validateUnlockLevels(int medium, int high, int extraordinary, int radiant) {
        if (medium < 1 || medium >= high || high >= extraordinary || extraordinary >= radiant) {
            throw new IllegalStateException("Chef quality unlock levels must strictly increase: medium="
                    + medium + ", high=" + high + ", extraordinary=" + extraordinary + ", radiant=" + radiant);
        }
    }
}

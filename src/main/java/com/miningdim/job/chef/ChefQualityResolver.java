package com.miningdim.job.chef;

import net.minecraft.util.RandomSource;

/**
 * 目标品质挑战结算。台档与厨师等级决定可选上限；控火精度与正确 QTE 形成表现概率，
 * 再依次应用目标品质难度与厨师熟练度倍率。
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

    public static int successChancePerMille(ChefQuality target, double heatAccuracy, int hits, int totalCues,
                                            int chefLevel) {
        if (heatAccuracy < 0.0D || heatAccuracy > 1.0D) {
            throw new IllegalArgumentException("heatAccuracy must be in [0,1], got " + heatAccuracy);
        }
        if (totalCues <= 0 || hits < 0 || hits > totalCues) {
            throw new IllegalArgumentException("QTE hits must be in [0,totalCues], got hits=" + hits
                    + ", totalCues=" + totalCues);
        }
        int performanceChance = ChefConfig.targetBaseChancePerMille(target)
                + (int) Math.round(heatAccuracy * ChefConfig.targetHeatBonusPerMille())
                + hits * ChefConfig.targetQteHitBonusPerMille();
        performanceChance = Math.min(1000, performanceChance);
        int qualityAdjustedChance = (int) Math.round(performanceChance
                * ChefConfig.targetDifficultyMultiplierPerMille(target) / 1000.0D);
        if (target == ChefQuality.LOW) {
            return qualityAdjustedChance;
        }
        return (int) Math.round(qualityAdjustedChance
                * levelSuccessMultiplierPerMille(chefLevel) / 1000.0D);
    }

    public static int levelSuccessMultiplierPerMille(int chefLevel) {
        if (chefLevel < 1 || chefLevel > 10) {
            throw new IllegalArgumentException("chefLevel must be in [1,10], got " + chefLevel);
        }
        int level1 = ChefConfig.level1SuccessMultiplierPerMille();
        int level10 = ChefConfig.level10SuccessMultiplierPerMille();
        if (level1 > level10) {
            throw new IllegalStateException("Chef level success multiplier must not decrease: level1="
                    + level1 + ", level10=" + level10);
        }
        return level1 + (int) Math.round((level10 - level1) * (chefLevel - 1) / 9.0D);
    }

    public static ChefQuality resolveTarget(RandomSource random, ChefQuality target, double heatAccuracy,
                                            int hits, int totalCues, int chefLevel) {
        int chance = successChancePerMille(target, heatAccuracy, hits, totalCues, chefLevel);
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

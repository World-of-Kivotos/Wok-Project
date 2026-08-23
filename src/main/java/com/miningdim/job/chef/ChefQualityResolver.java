package com.miningdim.job.chef;

import net.minecraft.util.RandomSource;

/**
 * 目标品质挑战结算。所有台档均可选择全部品质；控火精度与正确 QTE 形成表现概率，
 * 再依次应用目标品质难度、厨师熟练度与调味台倍率，高档台只提供正向增益。
 * 服务端只掷一次；成功产出目标品质，未达成则降一档，避免完成小游戏后吞菜或空结算。
 */
public final class ChefQualityResolver {

    private ChefQualityResolver() {
    }

    public static int successChancePerMille(ChefQuality target, double heatAccuracy, int hits, int totalCues,
                                            int chefLevel, ChefQuality tableTier) {
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
        int chefAdjustedChance = target == ChefQuality.LOW ? qualityAdjustedChance
                : (int) Math.round(qualityAdjustedChance
                * levelSuccessMultiplierPerMille(chefLevel) / 1000.0D);
        int tableMultiplier = 1000
                + tableTier.tier() * ChefConfig.tableSuccessBonusPerTierPerMille();
        return Math.min(1000, (int) Math.round(chefAdjustedChance * tableMultiplier / 1000.0D));
    }

    public static int levelSuccessMultiplierPerMille(int chefLevel) {
        if (chefLevel < 1 || chefLevel > 10) {
            throw new IllegalArgumentException("chefLevel must be in [1,10], got " + chefLevel);
        }
        int level1 = ChefConfig.level1OpenQualitySuccessMultiplierPerMille();
        int level10 = ChefConfig.level10SuccessMultiplierPerMille();
        if (level1 > level10) {
            throw new IllegalStateException("Chef level success multiplier must not decrease: level1="
                    + level1 + ", level10=" + level10);
        }
        return level1 + (int) Math.round((level10 - level1) * (chefLevel - 1) / 9.0D);
    }

    public static ChefQuality resolveTarget(RandomSource random, ChefQuality target, double heatAccuracy,
                                            int hits, int totalCues, int chefLevel, ChefQuality tableTier) {
        int chance = successChancePerMille(target, heatAccuracy, hits, totalCues, chefLevel, tableTier);
        return resolveTargetRoll(target, chance, random.nextInt(1000));
    }

    static ChefQuality resolveTargetRoll(ChefQuality target, int chancePerMille, int roll) {
        if (chancePerMille == 1000 || roll < chancePerMille) {
            return target;
        }
        return ChefQuality.byTier(target.tier() - 1);
    }
}

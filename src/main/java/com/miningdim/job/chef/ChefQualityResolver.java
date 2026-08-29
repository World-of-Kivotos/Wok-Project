package com.miningdim.job.chef;

import net.minecraft.util.RandomSource;

import java.util.function.ToIntFunction;

/**
 * 目标品质挑战结算。所有台档均可选择全部品质；控火精度与正确 QTE 形成表现概率，
 * 再依次应用目标品质难度、厨师熟练度与调味台倍率，高档台只提供正向增益。
 * 服务端只生成一个随机数，并从目标品质向下依次对照各档阈值；首个命中的档位成为成品品质，
 * 全部高档阈值均未命中时回落到低品质，避免目标失败固定保底相邻高档。
 * 下探每跌一档都要再乘一次衰减系数：这是「瞄更高」的代价，没有它时任一档的到手概率与所选目标无关，
 * 中/高/超凡三个目标就成了永远不该点的死选项。
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
        ChefConfig.validateBalanceConsistency();
        // QTE 加成按命中率而非命中数计: cue 数会随台档不足/等级不足上浮, 若按命中数发固定加成,
        // 多派的 cue 就成了净收益, 低一档的台子反而比高档台更容易出高品质 (台档倒挂)。
        int performanceChance = ChefConfig.targetBaseChancePerMille(target)
                + (int) Math.round(heatAccuracy * ChefConfig.targetHeatBonusPerMille())
                + (int) Math.round((double) hits / totalCues * ChefConfig.targetQtePerfectBonusPerMille());
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
        int roll = random.nextInt(1000);
        return resolveTargetRoll(target, roll, candidate -> successChancePerMille(
                candidate, heatAccuracy, hits, totalCues, chefLevel, tableTier));
    }

    static ChefQuality resolveTargetRoll(ChefQuality target, int roll,
                                         ToIntFunction<ChefQuality> chancePerMille) {
        int decayPerMille = ChefConfig.targetDowngradeDecayPerMille();
        ChefQuality candidate = target;
        while (candidate != ChefQuality.LOW) {
            // 每比目标低一档就把该档阈值再乘一次衰减。不收这份代价时 P(成品>=Q) 只由 Q 决定、与所选目标无关,
            // 于是永远该点最高目标, 中间三档目标退化成死选项。
            int effective = chancePerMille.applyAsInt(candidate);
            for (int step = target.tier() - candidate.tier(); step > 0; step--) {
                effective = (int) Math.round(effective * decayPerMille / 1000.0D);
            }
            if (roll < effective) {
                return candidate;
            }
            candidate = ChefQuality.byTier(candidate.tier() - 1);
        }
        return ChefQuality.LOW;
    }
}

package com.miningdim.job.chef;

/** 服务端按目标品质、开局厨师等级与调味台档位锁定的 QTE 数量、节奏与客户端命中条速度。 */
record ChefQteTiming(int cueCount, int windowTicks, int minGapTicks, int maxGapTicks,
                     int swayPeriodTicks) {

    static ChefQteTiming forChallenge(ChefQuality target, int chefLevel, ChefQuality tableTier) {
        int chefLevelDeficit = chefLevelDeficit(target, chefLevel);
        int tableTierDeficit = tableTierDeficit(target, tableTier);
        int timingAdjustment = (chefLevel - 1) * ChefConfig.qteEaseTicksPerChefLevel()
                - target.tier() * ChefConfig.qteDifficultyTicksPerQualityTier()
                - chefLevelDeficit * ChefConfig.qteNoviceTimingPenaltyTicksPerLevel()
                - tableTierDeficit * ChefConfig.qteTimingPenaltyTicksPerMissingTableTier();
        int window = Math.max(ChefConfig.qteMinWindowTicks(),
                ChefConfig.qteWindowTicks() + timingAdjustment);
        int minGap = Math.max(ChefConfig.qteMinGapTicks(),
                ChefConfig.qteMinIntervalTicks() + timingAdjustment);
        int maxGap = Math.max(ChefConfig.qteMinGapTicks(),
                ChefConfig.qteMaxIntervalTicks() + timingAdjustment);
        int cueCount = cueCountFor(target, chefLevel, tableTier);
        if (maxGap < minGap) {
            throw new IllegalStateException("Invalid chef QTE timing target=" + target.id()
                    + ", table=" + tableTier.id() + ", chefLevel=" + chefLevel + ", window=" + window
                    + ", gap=" + minGap + "-" + maxGap);
        }
        // 一个命中窗口内往返约两次；最低 4 tick 保证高难配置下仍能看清标记。
        int swayPeriod = Math.max(4, (window + 1) / 2);
        return new ChefQteTiming(cueCount, window, minGap, maxGap, swayPeriod);
    }

    static int cueCountFor(ChefQuality target, int chefLevel, ChefQuality tableTier) {
        int chefLevelDeficit = chefLevelDeficit(target, chefLevel);
        int noviceExtraCues = (chefLevelDeficit + ChefConfig.qteNoviceLevelsPerExtraCue() - 1)
                / ChefConfig.qteNoviceLevelsPerExtraCue();
        int missingTableExtraCues = tableTierDeficit(target, tableTier)
                * ChefConfig.qteExtraCuesPerMissingTableTier();
        int tableReduction = tableTier.tier() / ChefConfig.qteTableTiersPerReduction();
        return ChefConfig.qteCount() + target.tier() * ChefConfig.qteCountPerQualityTier()
                + noviceExtraCues + missingTableExtraCues - tableReduction;
    }

    private static int chefLevelDeficit(ChefQuality target, int chefLevel) {
        if (chefLevel < 1 || chefLevel > 10) {
            throw new IllegalArgumentException("chefLevel must be in [1,10], got " + chefLevel);
        }
        int recommendedLevel = 1 + target.tier() * ChefConfig.qteRecommendedLevelsPerQualityTier();
        return chefLevel < recommendedLevel ? recommendedLevel - chefLevel : 0;
    }

    private static int tableTierDeficit(ChefQuality target, ChefQuality tableTier) {
        return tableTier.tier() < target.tier() ? target.tier() - tableTier.tier() : 0;
    }
}

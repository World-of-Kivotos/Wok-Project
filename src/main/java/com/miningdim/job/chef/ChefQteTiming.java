package com.miningdim.job.chef;

/** 服务端按目标品质与开局厨师等级锁定的 QTE 节奏。 */
record ChefQteTiming(int windowTicks, int minGapTicks, int maxGapTicks) {

    static ChefQteTiming forChallenge(ChefQuality target, int chefLevel) {
        if (chefLevel < 1 || chefLevel > 10) {
            throw new IllegalArgumentException("chefLevel must be in [1,10], got " + chefLevel);
        }
        int timingAdjustment = (chefLevel - 1) * ChefConfig.qteEaseTicksPerChefLevel()
                - target.tier() * ChefConfig.qteDifficultyTicksPerQualityTier();
        int window = ChefConfig.qteWindowTicks() + timingAdjustment;
        int minGap = ChefConfig.qteMinIntervalTicks() + timingAdjustment;
        int maxGap = ChefConfig.qteMaxIntervalTicks() + timingAdjustment;
        if (window <= 0 || minGap <= 0 || maxGap < minGap) {
            throw new IllegalStateException("Invalid chef QTE timing target=" + target.id()
                    + ", chefLevel=" + chefLevel + ", window=" + window
                    + ", gap=" + minGap + "-" + maxGap);
        }
        return new ChefQteTiming(window, minGap, maxGap);
    }
}

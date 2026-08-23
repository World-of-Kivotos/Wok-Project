package com.miningdim.job.chef;

/** 服务端按目标品质与开局厨师等级锁定的 QTE 数量、节奏与客户端命中条速度。 */
record ChefQteTiming(int cueCount, int windowTicks, int minGapTicks, int maxGapTicks,
                     int swayPeriodTicks) {

    static ChefQteTiming forChallenge(ChefQuality target, int chefLevel) {
        if (chefLevel < 1 || chefLevel > 10) {
            throw new IllegalArgumentException("chefLevel must be in [1,10], got " + chefLevel);
        }
        int timingAdjustment = (chefLevel - 1) * ChefConfig.qteEaseTicksPerChefLevel()
                - target.tier() * ChefConfig.qteDifficultyTicksPerQualityTier();
        int window = ChefConfig.qteWindowTicks() + timingAdjustment;
        int minGap = ChefConfig.qteMinIntervalTicks() + timingAdjustment;
        int maxGap = ChefConfig.qteMaxIntervalTicks() + timingAdjustment;
        int cueCount = cueCountFor(target);
        if (window <= 0 || minGap <= 0 || maxGap < minGap) {
            throw new IllegalStateException("Invalid chef QTE timing target=" + target.id()
                    + ", chefLevel=" + chefLevel + ", window=" + window
                    + ", gap=" + minGap + "-" + maxGap);
        }
        // 一个命中窗口内往返约两次；最低 4 tick 保证高难配置下仍能看清标记。
        int swayPeriod = Math.max(4, (window + 1) / 2);
        return new ChefQteTiming(cueCount, window, minGap, maxGap, swayPeriod);
    }

    static int cueCountFor(ChefQuality target) {
        return ChefConfig.qteCount() + target.tier() * ChefConfig.qteCountPerQualityTier();
    }
}

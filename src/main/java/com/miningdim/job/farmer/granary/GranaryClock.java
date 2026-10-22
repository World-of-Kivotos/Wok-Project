package com.miningdim.job.farmer.granary;

import com.miningdim.freshness.FreshnessClockData;
import net.minecraft.server.level.ServerLevel;

/**
 * 粮仓用的挂钟换算 (Spoilage_Granary_DesignSpec「时间」)。
 *
 * 腐烂底座的时钟按**分钟**走 (`FreshnessClockData.nowMinute`), 而粮仓的账目 (入仓时刻、停表小时、
 * 出事时刻、宽限) 一律按**小时**记。换算只此一处: 几处各写一遍 `nowMinute() / 60` 迟早会出现
 * 一处漏了除、或者一处用了游戏刻。
 */
public final class GranaryClock {

    private GranaryClock() {
    }

    /** 现在的小时序号 (与停表、宽限同一把挂钟)。 */
    public static long nowHour(ServerLevel level) {
        return FreshnessClockData.nowMinute(level) / 60L;
    }

    /**
     * 现在的分钟序号。
     *
     * 判腐烂 (剩余 ≤ 0) 与「烂满 30 天」这类**跨小时的**判定必须按分钟算, 按小时算会把不到一小时
     * 的腐烂抹平; 记入仓时刻与停表仍旧一律按小时。
     */
    public static long nowMinute(ServerLevel level) {
        return FreshnessClockData.nowMinute(level);
    }
}

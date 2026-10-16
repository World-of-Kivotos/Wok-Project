package com.miningdim.freshness;

import java.time.Instant;

/**
 * 腐烂时钟的纯逻辑: 只增不减, 任何挂钟异常都按「停服补偿」计入偏移, **不冻结** (规格「时钟」与安全阀)。
 *
 * 「现在」= 挂钟 (UTC 小时序号 × 60 + 分钟) 减去 {@code offsetHours × 60}。偏移只增不减, 因此:
 * <ul>
 *   <li>服务器没跑的整小时 (停服、进程挂起、宿主机休眠、对时跳变) 一律补进偏移, 期间食物不腐烂;</li>
 *   <li>挂钟往回跳照旧只增不减, 不会把已经过去的腐烂时间退回去。</li>
 * </ul>
 * 运行中的分钟由挂钟直接给出, 只在**跨小时**时调用 {@link #observeHour} 做一次裁决; 心跳另有
 * {@code FreshnessClockData} 每 5 分钟写一次, 开服时按心跳算停服时长。
 */
public final class FreshnessClock {

    /** 尚未记录过任何挂钟时刻。 */
    public static final long UNSET_HOUR = Long.MIN_VALUE;

    private long lastSeenHour = UNSET_HOUR;
    private long offsetHours;

    /** 当前 UTC 小时序号 (挂钟, 未减偏移)。 */
    public static long currentWallHour() {
        return Instant.now().getEpochSecond() / 3600L;
    }

    /** 当前小时内的分钟数 (0–59)。 */
    public static long currentWallMinute() {
        return (Instant.now().getEpochSecond() % 3600L) / 60L;
    }

    /** 上次被观察到的挂钟小时序号 (跳变裁决用)。 */
    public long lastSeenHour() {
        return lastSeenHour;
    }

    /** 累计补回的小时数 (停服 + 挂钟跳变)。 */
    public long offsetHours() {
        return offsetHours;
    }

    /** 从存档恢复。偏移只增不减, 反序列化出的负值一律当 0。 */
    public void restore(long lastSeenHour, long offsetHours) {
        this.lastSeenHour = lastSeenHour;
        this.offsetHours = Math.max(0L, offsetHours);
    }

    /**
     * 开服时的停服补偿: 距心跳满 1 小时就按整小时补进偏移。
     *
     * @param heartbeatHour 上次心跳写下的挂钟小时序号
     * @param wallHour      本次开服的挂钟小时序号
     * @return 本次补进偏移的小时数 (0 表示没补)
     */
    public long compensateStartupFrom(long heartbeatHour, long wallHour) {
        lastSeenHour = wallHour;
        if (heartbeatHour == UNSET_HOUR) {
            return 0L;
        }
        long downtime = wallHour - heartbeatHour;
        if (downtime <= 0L) {
            // 挂钟往回跳 (回档 / NTP 回拨): 只增不减, 不动偏移。
            return 0L;
        }
        offsetHours += downtime;
        return downtime;
    }

    /** 只有时钟自己的心跳可用时的简化入口 (无独立心跳记录的场景与测试)。 */
    public long compensateStartup(long wallHour) {
        return compensateStartupFrom(lastSeenHour, wallHour);
    }

    /**
     * 运行中跨小时的裁决: 差值 1 正常前进; 差值 ≥ 2 (进程挂起或挂钟跳变) 把「差值 − 1」补进偏移。
     *
     * @return 本次补进偏移的小时数 (0 表示正常前进)
     */
    public long observeHour(long wallHour) {
        if (lastSeenHour == UNSET_HOUR) {
            lastSeenHour = wallHour;
            return 0L;
        }
        long delta = wallHour - lastSeenHour;
        if (delta <= 0L) {
            return 0L;
        }
        lastSeenHour = wallHour;
        if (delta == 1L) {
            return 0L;
        }
        long extra = delta - 1L;
        offsetHours += extra;
        return extra;
    }

    /** 现在的分钟序号 (UTC 小时序号 × 60 + 分钟, 再减去累计偏移)。 */
    public long nowMinute(long wallHour, long wallMinute) {
        return wallHour * 60L + wallMinute - offsetHours * 60L;
    }

    /** 用当前挂钟算「现在」的分钟序号。 */
    public long nowMinute() {
        return nowMinute(currentWallHour(), currentWallMinute());
    }

    /**
     * 测试专用: 直接落一个偏移 (GameTest 必须在 finally 里复位, 见规格「时钟」)。
     * 仍然只增不减口径 —— 负值当 0。
     */
    public void setOffsetHoursForTest(long offsetHours) {
        this.offsetHours = Math.max(0L, offsetHours);
    }
}

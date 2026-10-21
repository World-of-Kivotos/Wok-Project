package com.miningdim.freshness;

import java.time.Instant;

/**
 * 腐烂时钟的纯逻辑: 挂钟只前进时推进逻辑分钟, 运行中大跳仍按「差值 − 1」补偿。
 */
public final class FreshnessClock {

    /** 尚未记录过任何挂钟时刻。 */
    public static final long UNSET_HOUR = Long.MIN_VALUE;
    public static final long UNSET_MINUTE = Long.MIN_VALUE;

    private long lastSeenHour = UNSET_HOUR;
    private long lastSeenMinute = UNSET_MINUTE;
    private long lastNowMinute = UNSET_MINUTE;
    private long offsetHours;
    private long pendingRuntimeCompensationHours;

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

    public long lastSeenMinute() {
        return lastSeenMinute;
    }

    public long lastNowMinute() {
        return lastNowMinute;
    }

    /** 累计补回的小时数 (停服 + 挂钟跳变)。 */
    public long offsetHours() {
        return offsetHours;
    }

    /** 从旧小时精度存档恢复。 */
    public void restore(long lastSeenHour, long offsetHours) {
        long minute = lastSeenHour == UNSET_HOUR ? UNSET_MINUTE : lastSeenHour * 60L;
        long now = minute == UNSET_MINUTE ? UNSET_MINUTE : minute - Math.max(0L, offsetHours) * 60L;
        restore(lastSeenHour, minute, now, offsetHours);
    }

    /** 从分钟精度存档恢复。 */
    public void restore(long lastSeenHour, long lastSeenMinute, long lastNowMinute, long offsetHours) {
        this.lastSeenHour = lastSeenHour;
        this.lastSeenMinute = lastSeenMinute;
        this.lastNowMinute = lastNowMinute;
        this.offsetHours = Math.max(0L, offsetHours);
    }

    /** 启动时按心跳与当前挂钟分钟的整小时差补偿停服。 */
    public long compensateStartupFromMinutes(long heartbeatMinute, long wallMinute) {
        long downtimeMinutes = heartbeatMinute == UNSET_MINUTE ? 0L : wallMinute - heartbeatMinute;
        long compensated = downtimeMinutes <= 0L ? 0L : Math.floorDiv(downtimeMinutes, 60L);
        offsetHours += compensated;
        lastSeenHour = Math.floorDiv(wallMinute, 60L);
        lastSeenMinute = wallMinute;
        if (lastNowMinute == UNSET_MINUTE) {
            lastNowMinute = wallMinute - offsetHours * 60L;
        } else if (downtimeMinutes > 0L) {
            lastNowMinute += downtimeMinutes - compensated * 60L;
        }
        return compensated;
    }

    /** 旧小时精度调用口, 用于兼容现有测试与调用者。 */
    public long compensateStartupFrom(long heartbeatHour, long wallHour) {
        long heartbeatMinute = heartbeatHour == UNSET_HOUR ? UNSET_MINUTE : heartbeatHour * 60L;
        return compensateStartupFromMinutes(heartbeatMinute, wallHour * 60L);
    }

    /** 只有时钟自己的观测可用时的简化入口。 */
    public long compensateStartup(long wallHour) {
        long basis = lastSeenMinute == UNSET_MINUTE ? UNSET_MINUTE : lastSeenMinute;
        return compensateStartupFromMinutes(basis, wallHour * 60L);
    }

    /** 兼容小时调用口; 生产心跳应使用 {@link #observeMinute(long, long)}。 */
    public long observeHour(long wallHour) {
        return observeMinute(wallHour, 0L);
    }

    /**
     * 运行中观察挂钟。回拨时重置挂钟基线但保留逻辑分钟；正常前进按经过分钟推进, 大跳按「小时差 − 1」补偿。
     *
     * @return 本次补进偏移的小时数
     */
    public long observeMinute(long wallHour, long wallMinute) {
        long absoluteMinute = wallHour * 60L + wallMinute;
        if (lastSeenMinute == UNSET_MINUTE) {
            lastSeenHour = wallHour;
            lastSeenMinute = absoluteMinute;
            lastNowMinute = absoluteMinute - offsetHours * 60L;
            return 0L;
        }
        long elapsedMinutes = absoluteMinute - lastSeenMinute;
        if (elapsedMinutes < 0L) {
            lastSeenHour = wallHour;
            lastSeenMinute = absoluteMinute;
            return 0L;
        }

        long hourDelta = wallHour - lastSeenHour;
        long extra = hourDelta >= 2L ? hourDelta - 1L : 0L;
        offsetHours += extra;
        pendingRuntimeCompensationHours += extra;
        lastSeenHour = wallHour;
        lastSeenMinute = absoluteMinute;
        lastNowMinute += elapsedMinutes - extra * 60L;
        return extra;
    }

    public long drainRuntimeCompensationHours() {
        long compensated = pendingRuntimeCompensationHours;
        pendingRuntimeCompensationHours = 0L;
        return compensated;
    }

    /** 现在的分钟序号; 同时观察注入的挂钟时刻。 */
    public long nowMinute(long wallHour, long wallMinute) {
        observeMinute(wallHour, wallMinute);
        return lastNowMinute;
    }

    /** 用当前挂钟算「现在」的分钟序号。 */
    public long nowMinute() {
        return nowMinute(currentWallHour(), currentWallMinute());
    }

    /** 测试专用: 直接落一个偏移并重建注入时刻的逻辑基线; 生产时钟路径不调用此入口。 */
    public void setOffsetHoursForTest(long offsetHours) {
        this.offsetHours = Math.max(0L, offsetHours);
        this.lastNowMinute = lastSeenMinute == UNSET_MINUTE
                ? UNSET_MINUTE : lastSeenMinute - this.offsetHours * 60L;
        this.pendingRuntimeCompensationHours = 0L;
    }
}

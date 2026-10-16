package com.miningdim.freshness;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 挂在主世界的腐烂时钟存档: 心跳 + 累计偏移 (Spoilage_Granary_DesignSpec「时钟」)。
 *
 * 心跳每 5 分钟写一次, 所以「服务器没跑多久」可以从心跳反推: 开服时按整小时补, 运行中挂钟跳变按
 * 「差值 − 1」补, 两种都进 {@link FreshnessClock#offsetHours()}。心跳同时也让**回档**能被识别 ——
 * 恢复出来的心跳是旧的, 于是同样会补一段偏移。
 */
public final class FreshnessClockData extends SavedData {

    private static final int VERSION = 1;
    private static final String KEY_VERSION = "Version";
    private static final String KEY_LAST_SEEN = "LastSeenHour";
    private static final String KEY_OFFSET = "OffsetHours";
    private static final String KEY_HEARTBEAT = "HeartbeatHour";

    /** 心跳间隔 (分钟): 规格「每 5 分钟写一次心跳」。 */
    public static final long HEARTBEAT_INTERVAL_MINUTES = 5L;

    private final FreshnessClock clock = new FreshnessClock();
    private long heartbeatHour = FreshnessClock.UNSET_HOUR;
    private long lastHeartbeatMinute = Long.MIN_VALUE;

    public static FreshnessClockData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                FreshnessClockData::load, FreshnessClockData::new, FreshnessConstants.CLOCK_DATA_NAME);
    }

    public FreshnessClock clock() {
        return clock;
    }

    /** 最后一次写下的挂钟小时序号。 */
    public long heartbeatHour() {
        return heartbeatHour;
    }

    /** 现在的分钟序号 (现算, 不落盘)。 */
    public long nowMinute() {
        return clock.nowMinute();
    }

    /**
     * 开服: 按心跳做停服补偿。
     *
     * @return 本次补进偏移的小时数
     */
    public long onServerStarted(long wallHour) {
        long basis = heartbeatHour == FreshnessClock.UNSET_HOUR ? clock.lastSeenHour() : heartbeatHour;
        long compensated = clock.compensateStartupFrom(basis, wallHour);
        heartbeatHour = wallHour;
        lastHeartbeatMinute = wallHour * 60L;
        setDirty();
        return compensated;
    }

    /**
     * 运行中跨小时的推进。
     *
     * @return 本次补进偏移的小时数 (0 表示正常前进)
     */
    public long observeHour(long wallHour) {
        long compensated = clock.observeHour(wallHour);
        setDirty();
        return compensated;
    }

    /**
     * 心跳: 每 5 分钟把「服务器还活着」的时刻写下来, 供下次开服算停服时长。
     * 只有跨过心跳间隔才落盘, 免得每 tick 都标脏。
     *
     * @return 本次是否真的写了心跳
     */
    public boolean touchHeartbeat(long wallHour, long wallMinute) {
        long absoluteMinute = wallHour * 60L + wallMinute;
        if (lastHeartbeatMinute != Long.MIN_VALUE
                && absoluteMinute - lastHeartbeatMinute < HEARTBEAT_INTERVAL_MINUTES) {
            return false;
        }
        lastHeartbeatMinute = absoluteMinute;
        heartbeatHour = wallHour;
        setDirty();
        return true;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt(KEY_VERSION, VERSION);
        tag.putLong(KEY_LAST_SEEN, clock.lastSeenHour());
        tag.putLong(KEY_OFFSET, clock.offsetHours());
        tag.putLong(KEY_HEARTBEAT, heartbeatHour);
        return tag;
    }

    public static FreshnessClockData load(CompoundTag tag) {
        if (!tag.contains(KEY_VERSION, Tag.TAG_INT) || tag.getInt(KEY_VERSION) != VERSION) {
            throw new IllegalArgumentException("Invalid freshness clock save header");
        }
        FreshnessClockData data = new FreshnessClockData();
        long lastSeen = tag.contains(KEY_LAST_SEEN, Tag.TAG_LONG)
                ? tag.getLong(KEY_LAST_SEEN) : FreshnessClock.UNSET_HOUR;
        long offset = tag.contains(KEY_OFFSET, Tag.TAG_LONG) ? tag.getLong(KEY_OFFSET) : 0L;
        data.clock.restore(lastSeen, offset);
        data.heartbeatHour = tag.contains(KEY_HEARTBEAT, Tag.TAG_LONG)
                ? tag.getLong(KEY_HEARTBEAT) : FreshnessClock.UNSET_HOUR;
        data.lastHeartbeatMinute = data.heartbeatHour == FreshnessClock.UNSET_HOUR
                ? Long.MIN_VALUE : data.heartbeatHour * 60L;
        return data;
    }
}

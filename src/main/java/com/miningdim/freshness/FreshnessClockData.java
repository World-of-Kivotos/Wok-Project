package com.miningdim.freshness;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** 挂在主世界的腐烂时钟存档: 分钟精度心跳、累计偏移与统计环。 */
public final class FreshnessClockData extends SavedData {

    private static final int VERSION = 2;
    private static final int LEGACY_VERSION = 1;
    private static final String KEY_VERSION = "Version";
    private static final String KEY_LAST_SEEN = "LastSeenHour";
    private static final String KEY_LAST_SEEN_MINUTE = "LastSeenMinute";
    private static final String KEY_LAST_NOW_MINUTE = "LastNowMinute";
    private static final String KEY_OFFSET = "OffsetHours";
    private static final String KEY_HEARTBEAT = "HeartbeatHour";
    private static final String KEY_HEARTBEAT_MINUTE = "HeartbeatMinute";
    private static final String KEY_STATS = "Stats";

    public static final long HEARTBEAT_INTERVAL_MINUTES = 5L;
    private static final long HEARTBEAT_INTERVAL_NANOS = HEARTBEAT_INTERVAL_MINUTES * 60L * 1_000_000_000L;

    private final FreshnessClock clock = new FreshnessClock();
    private FreshnessStatsRing stats = new FreshnessStatsRing();
    private long heartbeatHour = FreshnessClock.UNSET_HOUR;
    private long heartbeatMinute = FreshnessClock.UNSET_MINUTE;
    private long lastHeartbeatNanos = System.nanoTime();
    private boolean started;

    public static FreshnessClockData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                FreshnessClockData::load, FreshnessClockData::new, FreshnessConstants.CLOCK_DATA_NAME);
    }

    public FreshnessClock clock() {
        return clock;
    }

    public FreshnessStatsRing stats() {
        return stats;
    }

    /** 把这一小时攒下的计数写进环, 空计数不写。 */
    public void flushHourStats(long wallHour) {
        FreshnessStats.Snapshot snapshot = FreshnessStats.peek();
        if (snapshot.isEmpty()) {
            return;
        }
        stats.record(wallHour, snapshot.stamped(), snapshot.wouldRot(), snapshot.rotted());
        FreshnessStats.drainCounters();
        setDirty();
    }

    public long heartbeatHour() {
        return heartbeatHour;
    }

    public long heartbeatMinute() {
        return heartbeatMinute;
    }

    public boolean isStarted() {
        return started;
    }

    public long nowMinute() {
        return clock.nowMinute();
    }

    public static long nowMinute(ServerLevel level) {
        return get(level).nowMinute();
    }

    /** 开服时按 lastSeen 与逻辑 now 相同的分钟锚点补偿停服, 只补满整小时。 */
    public long onServerStarted(long wallHour, long wallMinute) {
        if (started) {
            return 0L;
        }
        long absoluteWallMinute = wallHour * 60L + wallMinute;
        long basis = clock.lastSeenMinute();
        if (basis == FreshnessClock.UNSET_MINUTE) {
            basis = heartbeatMinute;
        }
        long compensated = clock.compensateStartupFromMinutes(basis, absoluteWallMinute);
        if (heartbeatMinute == FreshnessClock.UNSET_MINUTE) {
            heartbeatMinute = basis == FreshnessClock.UNSET_MINUTE || absoluteWallMinute > basis
                    ? absoluteWallMinute : basis;
        } else if (absoluteWallMinute > heartbeatMinute) {
            heartbeatMinute = absoluteWallMinute;
        }
        heartbeatHour = heartbeatMinute == FreshnessClock.UNSET_MINUTE
                ? FreshnessClock.UNSET_HOUR : Math.floorDiv(heartbeatMinute, 60L);
        lastHeartbeatNanos = System.nanoTime();
        started = true;
        setDirty();
        return compensated;
    }

    /** 旧小时调用口。 */
    public long onServerStarted(long wallHour) {
        return onServerStarted(wallHour, 0L);
    }

    /** 兼容旧调用口; 分钟精度由每 tick 心跳入口负责。 */
    public long observeHour(long wallHour) {
        long compensated = clock.observeHour(wallHour);
        if (compensated > 0L) {
            setDirty();
        }
        return compensated;
    }

    /** 每 tick 观测挂钟并按五分钟间隔写入心跳。 */
    public boolean touchHeartbeat(long wallHour, long wallMinute) {
        clock.observeMinute(wallHour, wallMinute);
        long absoluteWallMinute = wallHour * 60L + wallMinute;
        boolean wallIntervalElapsed = heartbeatMinute == FreshnessClock.UNSET_MINUTE
                || absoluteWallMinute - heartbeatMinute >= HEARTBEAT_INTERVAL_MINUTES;
        boolean runtimeIntervalElapsed = System.nanoTime() - lastHeartbeatNanos >= HEARTBEAT_INTERVAL_NANOS;
        if (!wallIntervalElapsed && !runtimeIntervalElapsed) {
            return false;
        }
        if (heartbeatMinute == FreshnessClock.UNSET_MINUTE || absoluteWallMinute > heartbeatMinute) {
            heartbeatMinute = absoluteWallMinute;
            heartbeatHour = wallHour;
        }
        lastHeartbeatNanos = System.nanoTime();
        setDirty();
        return true;
    }

    /** 最近一次被时钟观察到的运行中补偿, 供 Module 在每 tick 发一次告警后排空。 */
    public long drainRuntimeCompensationHours() {
        return clock.drainRuntimeCompensationHours();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt(KEY_VERSION, VERSION);
        tag.putLong(KEY_LAST_SEEN, clock.lastSeenHour());
        tag.putLong(KEY_LAST_SEEN_MINUTE, clock.lastSeenMinute());
        tag.putLong(KEY_LAST_NOW_MINUTE, clock.lastNowMinute());
        tag.putLong(KEY_OFFSET, clock.offsetHours());
        tag.putLong(KEY_HEARTBEAT, heartbeatHour);
        tag.putLong(KEY_HEARTBEAT_MINUTE, heartbeatMinute);
        tag.put(KEY_STATS, stats.save());
        return tag;
    }

    public static FreshnessClockData load(CompoundTag tag) {
        if (!tag.contains(KEY_VERSION, Tag.TAG_INT)) {
            throw new IllegalArgumentException("Invalid freshness clock save header");
        }
        int version = tag.getInt(KEY_VERSION);
        if (version != LEGACY_VERSION && version != VERSION) {
            throw new IllegalArgumentException("Unsupported freshness clock save version: " + version);
        }
        long lastSeen = requiredLong(tag, KEY_LAST_SEEN);
        long offset = requiredLong(tag, KEY_OFFSET);
        long heartbeat = requiredLong(tag, KEY_HEARTBEAT);
        FreshnessClockData data = new FreshnessClockData();
        data.heartbeatHour = heartbeat;
        if (version == LEGACY_VERSION) {
            long lastSeenMinute = lastSeen == FreshnessClock.UNSET_HOUR
                    ? FreshnessClock.UNSET_MINUTE : lastSeen * 60L;
            long lastNowMinute = lastSeenMinute == FreshnessClock.UNSET_MINUTE
                    ? FreshnessClock.UNSET_MINUTE : lastSeenMinute - Math.max(0L, offset) * 60L;
            data.clock.restore(lastSeen, lastSeenMinute, lastNowMinute, offset);
            data.heartbeatMinute = heartbeat == FreshnessClock.UNSET_HOUR
                    ? FreshnessClock.UNSET_MINUTE : heartbeat * 60L;
        } else {
            long lastSeenMinute = requiredLong(tag, KEY_LAST_SEEN_MINUTE);
            long lastNowMinute = requiredLong(tag, KEY_LAST_NOW_MINUTE);
            data.heartbeatMinute = requiredLong(tag, KEY_HEARTBEAT_MINUTE);
            data.clock.restore(lastSeen, lastSeenMinute, lastNowMinute, offset);
        }
        if (tag.contains(KEY_STATS, Tag.TAG_COMPOUND)) {
            data.stats = FreshnessStatsRing.load(tag.getCompound(KEY_STATS));
        }
        data.lastHeartbeatNanos = System.nanoTime();
        return data;
    }

    private static long requiredLong(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_LONG)) {
            throw new IllegalArgumentException("Missing freshness clock field: " + key);
        }
        return tag.getLong(key);
    }
}

package com.miningdim.freshness;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * 按小时的统计环 (Spoilage_Granary_DesignSpec「物品数据与存档」: 按小时的统计环, 保留 30 天)。
 *
 * 每小时一格, 只留最近 30 天 × 24 小时的量: 转一圈就把最老的那格覆盖掉。三列计数分别对应
 * 「盖了多少章」「本该腐烂多少件 (试运行)」「真换掉多少件 (开启档)」—— 这三列就是上线后判断
 * 「有没有误烂、玩家负担大不大」的依据。
 *
 * 纯数据 + 纯算术, 不依赖世界对象, 存档读写也放在这里, 由 {@link FreshnessClockData} 挂进 SavedData。
 */
public final class FreshnessStatsRing {

    /** 保留 30 天。 */
    public static final int HOURS_RETAINED = 24 * 30;

    private static final String KEY_HOURS = "Hours";
    private static final String KEY_STAMPED = "Stamped";
    private static final String KEY_WOULD_ROT = "WouldRot";
    private static final String KEY_ROTTED = "Rotted";

    /** 0 当作「这一格还没用过」: 真实的挂钟小时序号在十万量级, 不会撞上。 */
    private final long[] hours = new long[HOURS_RETAINED];
    private final long[] stamped = new long[HOURS_RETAINED];
    private final long[] wouldRot = new long[HOURS_RETAINED];
    private final long[] rotted = new long[HOURS_RETAINED];

    /** 记下这一小时的计数 (同一小时重复调用会覆盖该格, 便于跨整点的多次排空)。 */
    public void record(long wallHour, long stampedCount, long wouldRotCount, long rottedCount) {
        int slot = slotOf(wallHour);
        hours[slot] = wallHour;
        stamped[slot] = stampedCount;
        wouldRot[slot] = wouldRotCount;
        rotted[slot] = rottedCount;
    }

    /** 汇总 wallHour 及之后的小时 (含未用满的当前小时)。 */
    public FreshnessStats.Snapshot totals(long fromWallHour) {
        long sumStamped = 0L;
        long sumWouldRot = 0L;
        long sumRotted = 0L;
        long countedHours = 0L;
        for (int i = 0; i < HOURS_RETAINED; i++) {
            if (hours[i] == 0L || hours[i] < fromWallHour) {
                continue;
            }
            sumStamped += stamped[i];
            sumWouldRot += wouldRot[i];
            sumRotted += rotted[i];
            countedHours++;
        }
        return new FreshnessStats.Snapshot(sumStamped, sumWouldRot, sumRotted);
    }

    /** 环里最新的一格的小时序号 (空环返回 0)。 */
    public long newestHour() {
        long newest = 0L;
        for (long hour : hours) {
            newest = Math.max(newest, hour);
        }
        return newest;
    }

    /** 已经用了多少格 (诊断与展示用)。 */
    public int filledHours() {
        int filled = 0;
        for (long hour : hours) {
            if (hour != 0L) {
                filled++;
            }
        }
        return filled;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLongArray(KEY_HOURS, hours.clone());
        tag.putLongArray(KEY_STAMPED, stamped.clone());
        tag.putLongArray(KEY_WOULD_ROT, wouldRot.clone());
        tag.putLongArray(KEY_ROTTED, rotted.clone());
        return tag;
    }

    public static FreshnessStatsRing load(CompoundTag tag) {
        FreshnessStatsRing ring = new FreshnessStatsRing();
        if (!tag.contains(KEY_HOURS, Tag.TAG_LONG_ARRAY)) {
            return ring; // 旧存档没有统计环: 从空环开始, 不报错
        }
        long[] loadedHours = tag.getLongArray(KEY_HOURS);
        if (loadedHours.length != HOURS_RETAINED) {
            throw new IllegalArgumentException("Invalid freshness stats ring size: " + loadedHours.length);
        }
        copyInto(loadedHours, ring.hours);
        copyInto(tag.getLongArray(KEY_STAMPED), ring.stamped);
        copyInto(tag.getLongArray(KEY_WOULD_ROT), ring.wouldRot);
        copyInto(tag.getLongArray(KEY_ROTTED), ring.rotted);
        return ring;
    }

    private static void copyInto(long[] source, long[] target) {
        if (source.length != HOURS_RETAINED) {
            throw new IllegalArgumentException("Invalid freshness stats column size: " + source.length);
        }
        System.arraycopy(source, 0, target, 0, HOURS_RETAINED);
    }

    /**
     * 小时序号 → 环内位置。用取模映射而不是「找最后一次同小时」: 同一小时重复记账要落在同一格,
     * 而 30 天之后同一位置会被新小时覆盖, 这正是环的语义。
     */
    private static int slotOf(long wallHour) {
        return (int) Math.floorMod(wallHour, (long) HOURS_RETAINED);
    }
}

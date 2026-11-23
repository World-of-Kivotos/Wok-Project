package com.miningdim.freshness.client;

import com.miningdim.freshness.FreshnessCategories;
import com.miningdim.freshness.FreshnessData;
import com.miningdim.freshness.FreshnessConfig;
import com.miningdim.freshness.FreshnessReminder;
import com.miningdim.freshness.FreshnessShelfLives;
import com.miningdim.freshness.FreshnessSyncS2C;
import net.minecraft.world.item.ItemStack;


/**
 * 客户端的同步缓存 (规格「客户端显示」的数据一节): 只保存**同步下来的那几个数**, 不保存任何物品数据。
 *
 * 时间在两次同步之间自己走: 收到包时记下服务端的分钟序号与**本机**当时的毫秒, 之后的"现在"就是
 * "同步那一刻 + 本机流逝的分钟数"。于是 {@link com.miningdim.freshness.FreshnessSync#INTERVAL_TICKS} 两次校准之间 (默认 30 秒) 显示照样在动,
 * 而长时间在线也不会漂 —— 漂了下一个包就拉回来。
 *
 * 本类被 {@code FreshnessSyncS2C.handle} 在客户端线程调用; 所有字段都是 volatile, 渲染线程只读。
 */
public final class FreshnessClientState {

    private FreshnessClientState() {
    }

    private static volatile boolean synced;
    private static volatile long syncedNowMinute;
    private static volatile long syncedAtNanos;
    private static volatile long offsetHours;
    private static volatile FreshnessConfig.Mode mode = FreshnessConfig.Mode.DRY_RUN;

    /** 收到同步包: 写缓存 (保质期交给 {@link FreshnessShelfLives}, 供客户端显示读取)。 */
    public static void apply(FreshnessSyncS2C msg) {
        if (msg == null) {
            return;
        }
        syncedNowMinute = msg.nowMinute();
        syncedAtNanos = System.nanoTime();
        offsetHours = msg.offsetHours();
        mode = msg.modeOrDefault();
        FreshnessShelfLives.applySynced(msg.shelfLives());
        synced = true;
    }

    /** 断开连接时清掉 (免得把上一个服务器的钟带进下一个)。 */
    public static void clear() {
        synced = false;
        offsetHours = 0L;
        mode = FreshnessConfig.Mode.DRY_RUN;
        FreshnessShelfLives.clearSynced();
    }

    public static boolean synced() {
        return synced;
    }

    public static long offsetHours() {
        return offsetHours;
    }

    public static FreshnessConfig.Mode mode() {
        return mode;
    }

    /** 客户端认为的"现在" (分钟序号); 还没同步过时返回 {@link Long#MIN_VALUE} (调用方据此不画)。 */
    public static long nowMinute() {
        if (!synced) {
            return Long.MIN_VALUE;
        }
        return syncedNowMinute + (System.nanoTime() - syncedAtNanos) / 60_000_000_000L;
    }

    /** 一件东西现在还剩多少分钟; {@link Long#MAX_VALUE} = 不会腐烂。 */
    public static long remainingMinutes(ItemStack stack) {
        long now = nowMinute();
        if (now == Long.MIN_VALUE) {
            return Long.MAX_VALUE;
        }
        return remainingMinutes(stack, now);
    }

    private static long remainingMinutes(ItemStack stack, long now) {
        int hours = FreshnessCategories.categoryOf(stack).map(FreshnessShelfLives::clientHours).orElse(0);
        return hours == 0 ? Long.MAX_VALUE : FreshnessData.remainingMinutes(stack, hours, now);
    }

    public record Reminder(ItemStack earliest, long remainingMinutes, int count) {}

    /** 在同一时刻扫描背包，返回最先到期的一件及临期物品栈数。 */
    public static Reminder reminder(Iterable<ItemStack> slots, long now) {
        ItemStack earliest = ItemStack.EMPTY;
        long earliestRemaining = Long.MAX_VALUE;
        int count = 0;
        if (now != Long.MIN_VALUE && mode != FreshnessConfig.Mode.OFF) {
            for (ItemStack stack : slots) {
                long remaining = remainingMinutes(stack, now);
                if (FreshnessReminder.isExpiringSoon(remaining, remaining == Long.MAX_VALUE)) {
                    count++;
                    if (remaining < earliestRemaining) {
                        earliest = stack;
                        earliestRemaining = remaining;
                    }
                }
            }
        }
        return new Reminder(earliest, earliestRemaining, count);
    }
}

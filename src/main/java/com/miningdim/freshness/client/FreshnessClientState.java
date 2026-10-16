package com.miningdim.freshness.client;

import com.miningdim.freshness.FreshnessCategories;
import com.miningdim.freshness.FreshnessCategory;
import com.miningdim.freshness.FreshnessCheck;
import com.miningdim.freshness.FreshnessConfig;
import com.miningdim.freshness.FreshnessDisplay;
import com.miningdim.freshness.FreshnessReminder;
import com.miningdim.freshness.FreshnessShelfLives;
import com.miningdim.freshness.FreshnessSyncS2C;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端的同步缓存 (规格「客户端显示」的数据一节): 只保存**同步下来的那几个数**, 不保存任何物品数据。
 *
 * 时间在两次同步之间自己走: 收到包时记下服务端的分钟序号与**本机**当时的毫秒, 之后的"现在"就是
 * "同步那一刻 + 本机流逝的分钟数"。于是 {@link #INTERVAL_TICKS} 两次校准之间 (默认 30 秒) 显示照样在动,
 * 而长时间在线也不会漂 —— 漂了下一个包就拉回来。
 *
 * 本类被 {@code FreshnessSyncS2C.handle} 在客户端线程调用; 所有字段都是 volatile, 渲染线程只读。
 */
public final class FreshnessClientState {

    private FreshnessClientState() {
    }

    private static volatile boolean synced;
    private static volatile long syncedNowMinute;
    private static volatile long syncedAtMillis;
    private static volatile long offsetHours;
    private static volatile FreshnessConfig.Mode mode = FreshnessConfig.Mode.DRY_RUN;

    /** 收到同步包: 写缓存 (保质期交给 {@link FreshnessShelfLives}, 它是两端共用的读取口)。 */
    public static void apply(FreshnessSyncS2C msg) {
        if (msg == null) {
            return;
        }
        syncedNowMinute = msg.nowMinute();
        syncedAtMillis = System.currentTimeMillis();
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
        long elapsedMinutes = (System.currentTimeMillis() - syncedAtMillis) / 60_000L;
        return syncedNowMinute + Math.max(0L, elapsedMinutes);
    }

    /** 一件东西现在还剩多少分钟; {@link Long#MAX_VALUE} = 不会腐烂。 */
    public static long remainingMinutes(ItemStack stack) {
        long now = nowMinute();
        if (now == Long.MIN_VALUE) {
            return Long.MAX_VALUE;
        }
        return FreshnessCheck.remainingMinutes(stack, now);
    }

    /** 一类东西的展示档位 (画条 / 数字用)。 */
    public static FreshnessDisplay.Level levelOf(ItemStack stack) {
        long now = nowMinute();
        if (now == Long.MIN_VALUE) {
            return FreshnessDisplay.Level.UNTIMED;
        }
        long remaining = FreshnessCheck.remainingMinutes(stack, now);
        return FreshnessDisplay.level(remaining, remaining == Long.MAX_VALUE);
    }

    /** 这一格的显示数据 (画的时候一次算完, 免得两次调用之间跨过整点算出两个不同的数)。 */
    public record SlotDisplay(long remainingMinutes, int shelfLifeHours, double fraction,
                              String label, FreshnessDisplay.Level level, boolean drawable) {
    }

    /** 算一格的显示数据; 不腐烂 / 还没同步 / 客户端关掉了都返回 {@code drawable=false}。 */
    public static SlotDisplay displayOf(ItemStack stack) {
        long now = nowMinute();
        if (now == Long.MIN_VALUE || !FreshnessClientConfig.slotBarEnabled()) {
            return new SlotDisplay(0L, 0, 0.0D, "", FreshnessDisplay.Level.UNTIMED, false);
        }
        long remaining = FreshnessCheck.remainingMinutes(stack, now);
        boolean untimed = remaining == Long.MAX_VALUE;
        int shelfLife = FreshnessCategories.categoryOf(stack)
                .map(FreshnessShelfLives::hours)
                .orElse(0);
        FreshnessDisplay.Level level = FreshnessDisplay.level(remaining, untimed);
        boolean drawable = FreshnessDisplay.shouldDraw(level)
                && (level == FreshnessDisplay.Level.ROTTEN
                    || FreshnessClientConfig.slotNumbersVisible(remaining));
        return new SlotDisplay(untimed ? 0L : remaining, shelfLife,
                FreshnessDisplay.barFraction(remaining, shelfLife),
                FreshnessDisplay.label(remaining), level,
                FreshnessClientConfig.slotBarEnabled() && level != FreshnessDisplay.Level.UNTIMED);
    }

    /** 身上在 {@link FreshnessDisplay#REMIND_WITHIN_HOURS} 小时内会烂的东西 (快捷栏提醒用)。 */
    public static List<ItemStack> expiringSoon(ItemStack[] slots) {
        List<ItemStack> found = new ArrayList<>();
        long now = nowMinute();
        if (now == Long.MIN_VALUE || slots == null) {
            return found;
        }
        for (ItemStack stack : slots) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            long remaining = FreshnessCheck.remainingMinutes(stack, now);
            boolean untimed = remaining == Long.MAX_VALUE;
            if (FreshnessReminder.isExpiringSoon(remaining, untimed)) {
                found.add(stack);
            }
        }
        return found;
    }

    /** 一类东西的保质期 (客户端只认同步下来的那份)。 */
    public static int shelfLifeHours(FreshnessCategory category) {
        return FreshnessShelfLives.hours(category);
    }
}

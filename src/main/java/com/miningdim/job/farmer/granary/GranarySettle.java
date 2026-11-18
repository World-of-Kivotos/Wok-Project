package com.miningdim.job.farmer.granary;

import com.miningdim.freshness.FreshnessConstants;
import com.miningdim.freshness.FreshnessData;
import net.minecraft.world.item.ItemStack;

/**
 * 粮仓停表的结算算术 (Spoilage_Granary_DesignSpec「停表规则」「结算 GranarySettle」)。
 *
 * 规则只有两条, 但两条都容易写错:
 * <ol>
 *   <li><b>连续存满 24 小时才算停表</b>: 不满 24 小时就取出, 这次停表记 0, 东西和放在外面一样老去;
 *       存满 24 小时则按「存放小时 − 1」记 —— 存 25 小时记 24, 存 168 小时记 167。</li>
 *   <li><b>每件累计最多 21 天 (504 小时)</b>: 额度用完之后即使还在仓里也照常腐烂。</li>
 * </ol>
 *
 * 每个储粮囤的**每一格**记两个数: 原始入仓时刻 {@code E} 与本次已计停表 {@code C}。
 * 结算到截止时刻 T 时只更新 C、不动 E —— 这就是「中途打开账台看一眼不会重置计时」的实现方式,
 * 也是「先按原来的状态结算到现在, 再切换状态」这条顺序要求的落点。
 *
 * 纯算术, 不读世界也不读配置, 所以能直接把设计文档里那四组数字钉成用例。
 */
public final class GranarySettle {

    /** 连续存满多少小时才开始停表。 */
    public static final int MIN_STORE_HOURS = FreshnessConstants.MIN_STORE_HOURS;

    /** 每件累计最多停表多少小时 (21 天)。 */
    public static final int STOP_CAP_HOURS = FreshnessConstants.STOP_CAP_HOURS;

    /** 一次结算的结果。 */
    public record Result(int creditedHours, int grantedHours, boolean capReached) {

        /** 这一趟有没有真的记上停表。 */
        public boolean credited() {
            return grantedHours > 0;
        }
    }

    private GranarySettle() {
    }

    /**
     * 把某一格结算到截止时刻。
     *
     * @param entryHour      原始入仓时刻 (小时序号; 中途结算不改它)
     * @param creditedHours  这一趟已经记过的停表小时 (C)
     * @param deadlineHour   结算截止时刻 (小时序号)
     * @param itemStopHours  这件东西当前的已停表小时 (物品上的 f)
     * @return 新的 C 与本趟增量; 增量同时受「应计 − 已经记过的」与「504 − 物品已有额度」两个上限约束
     */
    public static Result settle(long entryHour, int creditedHours, long deadlineHour, int itemStopHours) {
        if (creditedHours < 0) {
            throw new IllegalArgumentException("creditedHours must be >= 0, got " + creditedHours);
        }
        int itemHours = clampItemHours(itemStopHours);
        int remainingCap = STOP_CAP_HOURS - itemHours;
        if (remainingCap <= 0) {
            return new Result(creditedHours, 0, true);
        }
        long storedHours = deadlineHour - entryHour;
        if (storedHours <= 0) {
            return new Result(creditedHours, 0, false);
        }
        // 存满 24 小时才起算, 起算后按「存放小时 − 1」记 (入仓那一小时不计)。
        int due = storedHours < MIN_STORE_HOURS ? 0 : (int) Math.min(Integer.MAX_VALUE, storedHours - 1L);
        int granted = Math.min(Math.max(0, due - creditedHours), remainingCap);
        int newCredited = creditedHours + granted;
        return new Result(newCredited, granted, itemHours + granted >= STOP_CAP_HOURS);
    }

    /**
     * 直接对物品结算: 把本趟增量写进物品的 {@code f}, 并返回结果。
     * 调用方负责把新的 C 写回囤的格账上。
     */
    public static Result settle(ItemStack stack, long entryHour, int creditedHours, long deadlineHour) {
        Result result = settle(entryHour, creditedHours, deadlineHour, FreshnessData.stoppedHours(stack));
        if (result.grantedHours() > 0) {
            FreshnessData.setStoppedHours(stack, FreshnessData.stoppedHours(stack) + result.grantedHours());
        }
        return result;
    }

    /** 一件东西的停表额度是否已经用完 (用完就不该再收进仓)。 */
    public static boolean capExhausted(ItemStack stack) {
        return FreshnessData.stoppedHours(stack) >= STOP_CAP_HOURS;
    }

    /** 还能再停多少小时。 */
    public static int remainingCap(ItemStack stack) {
        return Math.max(0, STOP_CAP_HOURS - clampItemHours(FreshnessData.stoppedHours(stack)));
    }

    private static int clampItemHours(int itemStopHours) {
        return Math.max(0, Math.min(STOP_CAP_HOURS, itemStopHours));
    }
}

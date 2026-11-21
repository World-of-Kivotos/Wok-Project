package com.miningdim.freshness;

import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * 同种食物合并 (Spoilage_Granary_DesignSpec「同种食物合并」)。
 *
 * 按小时分批之后, 同一种食物会被拆成很多小堆, 所以玩家自己叠放时合并、新鲜度取平均 ——
 * 但只在两堆足够接近时才合并, 免得新货把快烂的货「救活」(反漏洞 #50、#52、#53)。
 *
 * 三条合并条件缺一不可:
 * <ol>
 *   <li>除新鲜度外其它数据完全相同 ({@link FreshnessData#sameExceptFreshness}, 迁移货与村民货标记照常参与比较);</li>
 *   <li>两堆剩余相差不超过 1/8 保质期, 至少 2 小时 —— 生鲜 2 小时、果蔬 4.5 小时、48 小时类 6 小时、腌制 21 小时;</li>
 *   <li>停表时间相同 (从粮仓取出的货不和新货自动叠, 免得新货被悄悄扣掉停表额度)。</li>
 * </ol>
 *
 * <b>为什么按「有效批次」算而不用「现在」</b>: 剩余 = 有效批次 + 保质期 × 60 − 现在, 其中
 * 有效批次 = 批次时刻 + 已停表小时 × 60。两堆同种食物的保质期与「现在」都相同, 所以剩余的差、
 * 加权平均这些量都能只在有效批次上算 —— 于是合并核心完全不依赖时钟, 注入点也不用去摸世界对象
 * (原版背包与合成格那些方法里根本没有 level)。
 *
 * <b>为什么不会凭空多出新鲜度</b>: 加权平均保持「件数 × 剩余小时」的总和不变; 窗口又把
 * 「先合并再加热白拿封顶部分」和「一直往老堆里并新货」都压到每件不超过半个窗口。
 */
public final class FreshMerge {

    private FreshMerge() {
    }

    /** 合并窗口 (分钟): 1/8 保质期, 至少 2 小时。 */
    public static long windowMinutes(int shelfLifeHours) {
        long byFraction = (long) Math.floor(shelfLifeHours * 60.0D
                / FreshnessConfig.MERGE_WINDOW_DIVISOR.get());
        return Math.max(FreshnessConfig.MERGE_WINDOW_MIN_HOURS.get() * 60L, byFraction);
    }

    /** 两堆能不能合并 (两堆都必须是已盖章的易腐品)。 */
    public static boolean canMerge(ItemStack target, ItemStack incoming) {
        if (target.isEmpty() || incoming.isEmpty()) {
            return false;
        }
        if (!FreshnessData.sameExceptFreshness(target, incoming)) {
            return false;
        }
        Optional<FreshnessCategory> category = FreshnessCategories.categoryOf(target);
        if (category.isEmpty() || !FreshnessCategories.spoils(incoming)) {
            return false;
        }
        if (!FreshnessData.isStamped(target) || !FreshnessData.isStamped(incoming)) {
            return false;
        }
        if (FreshnessData.stoppedHours(target) != FreshnessData.stoppedHours(incoming)) {
            return false;
        }
        int shelfLifeHours = FreshnessConfig.shelfLifeHours(category.get());
        long gap = Math.abs(effectiveMinute(target) - effectiveMinute(incoming));
        return gap <= windowMinutes(shelfLifeHours);
    }

    /**
     * 把 incoming 并进 target: 剩余按件数加权平均 (精确到分钟), 停表时间不变, 最后按
     * 「加权平均有效批次 − 停表 × 60」反算批次时刻写回两堆。
     *
     * @return 实际并入的件数 (0 = 没合并)
     */
    public static int mergeInto(ItemStack target, ItemStack incoming) {
        int moved = prepareAppend(target, incoming);
        if (moved <= 0) {
            return 0;
        }
        target.grow(moved);
        incoming.shrink(moved);
        return moved;
    }

    /**
     * 只把两堆的章改成「合并后应有的样子」, 不搬件数 —— 给那些「原版随后自己会搬」的注入点用
     * (背包入包、配方书追加): 先让两边数据一致, 原版的 grow 之后账就是对的。
     *
     * 只有 incoming 能整堆放进 target 时才动手: 装不下时原版只会搬一部分, 那部分跟着 target 的老章走
     * 是保守方向, 不该被平均拉年轻。
     *
     * @return 预计会被并入的件数 (0 = 不处理)
     */
    public static int prepareAppend(ItemStack target, ItemStack incoming) {
        if (!canMerge(target, incoming)) {
            return 0;
        }
        int room = target.getMaxStackSize() - target.getCount();
        int moved = Math.min(room, incoming.getCount());
        if (moved <= 0 || moved != incoming.getCount()) {
            return 0;
        }
        int shelfLifeHours = FreshnessCategories.categoryOf(target)
                .map(FreshnessConfig::shelfLifeHours).orElse(0);
        if (shelfLifeHours <= 0) {
            return 0;
        }
        int stoppedHours = FreshnessData.stoppedHours(target);
        int totalCount = target.getCount() + moved;
        long mergedEffective = Math.floorDiv(
                effectiveMinute(target) * target.getCount() + effectiveMinute(incoming) * moved, totalCount);
        long batchMinute = mergedEffective - (long) stoppedHours * 60L;
        FreshnessData.stampAt(target, batchMinute, stoppedHours);
        FreshnessData.stampAt(incoming, batchMinute, stoppedHours);
        return moved;
    }

    /**
     * 配方书往格子里追加时的口径: 格子取**最老**的那批 (剩余取较少、停表取较大), 不走加权平均 ——
     * 追加时原版只搬一件, 平均会把这件新货的年龄当分母 (反漏洞 #9)。
     */
    public static void preferOlderBatch(ItemStack target, ItemStack incoming) {
        if (target.isEmpty() || incoming.isEmpty()
                || !FreshnessData.sameExceptFreshness(target, incoming)
                || !FreshnessData.isStamped(target) || !FreshnessData.isStamped(incoming)) {
            return;
        }
        if (!FreshnessCategories.spoils(target) || !FreshnessCategories.spoils(incoming)) {
            return;
        }
        int stoppedHours = Math.max(FreshnessData.stoppedHours(target), FreshnessData.stoppedHours(incoming));
        long olderEffective = Math.min(effectiveMinute(target), effectiveMinute(incoming));
        long batchMinute = olderEffective - (long) stoppedHours * 60L;
        FreshnessData.stampAt(target, batchMinute, stoppedHours);
        FreshnessData.stampAt(incoming, batchMinute, stoppedHours);
    }

    /** 有效批次 = 批次时刻 + 已停表小时 × 60 (未盖章返回 {@link FreshnessData#UNSET_BATCH})。 */
    private static long effectiveMinute(ItemStack stack) {
        long batch = FreshnessData.batchMinute(stack);
        if (batch == FreshnessData.UNSET_BATCH) {
            return FreshnessData.UNSET_BATCH;
        }
        return batch + FreshnessData.stoppedHours(stack) * 60L;
    }
}

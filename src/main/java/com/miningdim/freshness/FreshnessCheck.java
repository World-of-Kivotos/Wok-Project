package com.miningdim.freshness;

import net.minecraft.world.item.ItemStack;

/**
 * 给**别的模块**用的两个小查询 (Spoilage_Granary_DesignSpec「消费点改造」: 卖麦、酿酒台、任务上交、
 * 市场都要在消费点拒收已经腐烂的东西)。
 *
 * 为什么要有这一层: 直接拼 {@code FreshnessData.isRotten(stack, shelfLifeHours(stack), now)} 的话,
 * **不腐烂的东西** ({@code shelfLifeHours == 0}) 会被算成「剩余 0 分钟」→ 一律判腐烂 —— 骨头、火药、
 * 下界之星这类任务上交物会整批被拒。这里把「不腐烂永远不算烂」这条口径固定下来, 消费点照着调就行。
 */
public final class FreshnessCheck {

    private FreshnessCheck() {
    }

    /** 现在是不是已经烂了 (不腐烂的东西、豁免品、空栈永远返回 false)。 */
    public static boolean isRottenNow(ItemStack stack, long nowMinute) {
        if (stack.isEmpty()) {
            return false;
        }
        int shelfLifeHours = FreshnessCategories.shelfLifeHours(stack);
        if (shelfLifeHours <= 0) {
            return false;
        }
        return FreshnessData.isRotten(stack, shelfLifeHours, nowMinute);
    }

    /**
     * 还剩多少分钟 (不腐烂的东西返回 {@link Long#MAX_VALUE})。
     *
     * 消费点按「先扣最老的」排序时用它: 不腐烂的东西排在最后, 不会被当成最老的那一批。
     */
    public static long remainingMinutes(ItemStack stack, long nowMinute) {
        if (stack.isEmpty()) {
            return Long.MAX_VALUE;
        }
        int shelfLifeHours = FreshnessCategories.shelfLifeHours(stack);
        if (shelfLifeHours <= 0) {
            return Long.MAX_VALUE;
        }
        return FreshnessData.remainingMinutes(stack, shelfLifeHours, nowMinute);
    }
}

package com.miningdim.job.farmer;

/**
 * NPC 小麦动态收购价裁决 (FarmingXP_Mod_DesignSpec 第八节方案4)。纯函数, 确定性, 无世界引用。
 *
 * 与经验衰减是两条独立曲线 (spec 第八节明示): 经验侧走 {@link com.miningdim.job.JobXpCurve} 的 2000 系
 * 软上限 (削经验不削掉落); 经济侧走本类的收购价递减 (削货币注入不削掉落)。二者各自持久化、互不影响。
 *
 * 曲线形态与矿物收购同构 (economy.AbuseGuard.buyPrice, 全服 faucet 衰减语言一致):
 *   price(n) = basePrice * max(floorRatio, decayBase^max(0, n - softCap))
 * 其中 n = 当日已售出该玩家的小麦累计株数 (含本株)。softCap 内全价, 超出后单价指数衰减至 basePrice×floor 地板。
 */
public final class FarmerWheatBuyback {

    private FarmerWheatBuyback() {
    }

    /**
     * 第 n 株小麦的收购单价 (信用点, 向下取整)。
     *
     * @param countSoFar 当日该玩家已售出小麦累计株数 (含本株, >=1)
     * @param basePrice  基础单价 (信用点/株, > 0)
     * @return 该株的递减后收购单价 (信用点, >= floor; 取整后至少为 basePrice×floor 的下取整)
     */
    public static long wheatBuyPrice(int countSoFar, long basePrice) {
        if (countSoFar < 1) {
            throw new IllegalArgumentException("countSoFar must be >= 1, got " + countSoFar);
        }
        if (basePrice <= 0L) {
            throw new IllegalArgumentException("basePrice must be > 0, got " + basePrice);
        }
        int over = Math.max(0, countSoFar - FarmerConstants.WHEAT_DAILY_SOFTCAP);
        double decayed = Math.pow(FarmerConstants.WHEAT_DECAY_BASE, over);
        double ratio = Math.max(FarmerConstants.WHEAT_PRICE_FLOOR_RATIO, decayed);
        return (long) Math.floor(basePrice * ratio);
    }

    /**
     * 一次卖出 amount 株小麦的总收购价 (从第 from+1 株到第 from+amount 株逐株按 {@link #wheatBuyPrice} 求和)。
     * 逐株求和保证跨越 softCap 边界时收益连续 (与 "一株一株卖" 结果一致, 无边界跳变), 与经验跨段切分同纪律。
     *
     * @param alreadySoldToday 卖出本批前当日已售株数 (>=0)
     * @param amount           本批卖出株数 (>=1)
     * @param basePrice        基础单价 (信用点/株, > 0)
     * @return 本批总收购价 (信用点)
     */
    public static long totalBuyPrice(int alreadySoldToday, int amount, long basePrice) {
        if (alreadySoldToday < 0) {
            throw new IllegalArgumentException("alreadySoldToday must be >= 0, got " + alreadySoldToday);
        }
        if (amount < 1) {
            throw new IllegalArgumentException("amount must be >= 1, got " + amount);
        }
        long total = 0L;
        for (int i = 1; i <= amount; i++) {
            total += wheatBuyPrice(alreadySoldToday + i, basePrice);
        }
        return total;
    }
}

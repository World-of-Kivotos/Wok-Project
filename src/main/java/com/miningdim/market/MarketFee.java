package com.miningdim.market;

import java.util.OptionalLong;

/**
 * 跳蚤市场挂单手续费 (经济文档 0.3-45 / {@link MarketConstants} 第 9 行预留的"偏离校验"通道核心)。纯函数, 确定性,
 * 可单元断言。塔科夫跳蚤市场反洗钱内核的"二次型对数"等价式 (规避原版 4^log10 的取幂-对数数值别扭, 性质一致):
 *
 *   偏离费 fee = max(V0, VR) * count * ( FEE_RATE + DEVIATION_K * ln(VR/V0)^2 )
 *
 * 其中 V0 = 物品基准价值 (单位价, 反洗钱锚, 不可被挂单价操纵), VR = 卖家挂出的单价。性质 (反洗钱所依赖):
 *  - 平价 (VR == V0): ln(1)=0, 费率落到 {@link MarketConstants#FEE_RATE} 地板 (诚实挂单最便宜)。
 *  - 偏离越大费指数涨 (贱卖搬货 VR&lt;&lt;V0 / 天价搬钱 VR&gt;&gt;V0 两端对称同罚): 把"挂垃圾天价/挂好货贱价"的对敲洗钱
 *    在手续费上压成净亏 —— 搬 X 信用点须把基准价 1 的垃圾挂到 X, 偏离费远超 X, 上单即收即付不起 -&gt; 挂单被拒。
 *  - 不封顶 (用户决策): 极端偏离的费可达 long 饱和, 卖家付不起即挂单失败 (自限, 无需硬天花板)。
 *
 * 无锚兜底 (V0 不可解析时, 见 {@link DefaultBaseValues}): 退化为平率费 round(FEE_RATE * VR * count) —— 冷启动/长尾
 * 物品的诚实简单兜底 (无"偏离"可测时不装偏离), 与平价偏离费数值一致。
 *
 * 收费时机 (用户决策"上单即收"): 本费在 {@link MarketEngine#place} 向卖家一次性收取, 蒸发为 sink, 撤单/未售不退。
 */
public final class MarketFee {

    private MarketFee() {
    }

    /**
     * 一笔拟挂单的挂单手续费。{@code v0} 有值走偏离费 (锚定基准价), 无值走平率费 (无锚兜底)。
     *
     * @param v0        物品基准价值 (单位价); {@link OptionalLong#empty()} 表示无可信锚 -&gt; 平率兜底
     * @param unitPrice 卖家挂出单价 (必须 &gt; 0)
     * @param count     挂单数量 (必须 &gt; 0)
     * @return 应向卖家收取的挂单手续费 (信用点, &gt;= 0)
     */
    public static long listingFee(OptionalLong v0, long unitPrice, long count) {
        if (unitPrice <= 0L) {
            throw new IllegalArgumentException("unitPrice must be > 0, got " + unitPrice);
        }
        if (count <= 0L) {
            throw new IllegalArgumentException("count must be > 0, got " + count);
        }
        if (v0.isEmpty()) {
            return flatFee(unitPrice, count);
        }
        return deviationFee(v0.getAsLong(), unitPrice, count);
    }

    /** 平率费 = round(FEE_RATE * unitPrice * count)。无锚物品的诚实兜底 (与平价偏离费等值)。 */
    public static long flatFee(long unitPrice, long count) {
        double total = (double) unitPrice * (double) count;
        return Math.round(total * MarketConstants.FEE_RATE);
    }

    /**
     * 偏离费 = max(V0,VR) * count * (FEE_RATE + DEVIATION_K * ln(VR/V0)^2)。对称 (两端同罚), 不封顶。
     * V0/VR 在取 ln 前下钳到 {@link MarketConstants#MIN_ANCHOR_VALUE} (防 ln(0)/除零)。
     *
     * 不封顶的实现纪律: 极端偏离时 scaleRef*rate 可超 {@link Long#MAX_VALUE}; {@link Math#round(double)} 对超界 double
     * 饱和到 Long.MAX_VALUE (JLS 5.1.3 double-&gt;long 窄化), 即"费大到付不起 -&gt; 挂单被拒", 无需显式天花板。
     */
    public static long deviationFee(long v0, long unitPrice, long count) {
        double anchor = (double) Math.max(MarketConstants.MIN_ANCHOR_VALUE, v0);
        double vr = (double) Math.max(MarketConstants.MIN_ANCHOR_VALUE, unitPrice);
        double logRatio = Math.log(vr / anchor);
        double rate = MarketConstants.FEE_RATE + MarketConstants.DEVIATION_K * logRatio * logRatio;
        double scaleRef = Math.max(anchor, vr) * (double) count;
        return Math.round(scaleRef * rate);
    }
}

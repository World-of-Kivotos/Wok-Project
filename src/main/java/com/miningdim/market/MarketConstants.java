package com.miningdim.market;

import java.util.Set;

/**
 * 跳蚤市场 (P2P 交易通道) 数值与标的常量 (共享契约第 5 节)。
 *
 * 经济定位 (经济文档 0.3-45 / IEconomyService 注释 0.3-46): 货币层刻意无 P2P 转移入口 (零成本洗钱后门);
 * 玩家间信用点转移只能经本"收手续费 + 落流水审计 + 偏离校验"的交易通道。本类承载该通道的两个核心反通胀
 * 旋钮: 成交手续费率 (sink, 凭空蒸发抑制通胀) 与铜/铁每日 P2P 量上限 (对齐定价台账"铜 P2P 单人 cap"遗留)。
 *
 * 数值纪律: 以下数值均为 DRAFT, 待用户依定价台账 (Ore_Pricing_Ledger_DesignSpec) 标定后定稿。常量集中此处
 * 即"单一真值", 标定时只改本文件不动引擎逻辑。
 */
public final class MarketConstants {

    private MarketConstants() {
    }

    /**
     * 挂单手续费的【平价基础费率】(DRAFT, 待用户标定)。偏离费 {@link MarketFee} 在 VR==V0 (诚实按基准价挂单) 时
     * 退化到本费率: fee = round(FEE_RATE * unitPrice * count); 无可信锚的物品也用本平率兜底。0.05 = 5% 是草案锚点。
     *
     * 收费时机 (用户决策"上单即收"): 本费在 {@link MarketEngine#place} 向卖家一次性收取, 蒸发为 sink (不 grant 给任何人,
     * 反通胀), 撤单/未售【不退】(EFT 非退性)。买入 ({@link MarketEngine#buy}) 不再二次收费, 卖家实收全额 total。
     */
    public static final double FEE_RATE = 0.05D;

    /**
     * 偏离费二次系数 K (DRAFT, 待标定): fee = max(V0,VR)*count*(FEE_RATE + DEVIATION_K * ln(VR/V0)^2)。
     * 0.00717 使"基准价 V0 的物挂到极端价 (偏离 10^5 倍)"时费 ≈ 物品自身价值 (校准自用户意图: 基准 10w 物挂 1 块约付 10w 费);
     * 对称 —— 贱卖 (VR&lt;V0) 与天价 (VR&gt;V0) 同等惩罚 (用户决策: 不对称非故意)。调大则偏离更狠。见 {@link MarketFee}。
     */
    public static final double DEVIATION_K = 0.00717D;

    /**
     * 偏离费取 ln 前对 V0/VR 的下钳值 (防 ln(0)/除零): 任何 &lt; 1 的基准价/挂出价按 1 算。挂出价 unitPrice 本已 &gt; 0
     * (place 校验), 本钳主要兜底基准价 V0 的极小值。
     */
    public static final long MIN_ANCHOR_VALUE = 1L;

    /**
     * 铜/铁每卖家每日 P2P 量上限 (DRAFT, 待用户依定价台账标定)。对齐定价台账"铜 P2P 单人 cap"遗留约束:
     * 铜铁是低价大宗矿, 不设上限则可经市场无限对倒刷量。口径 = 今日该卖家这些 item 的 (当前 ACTIVE 挂单 count 之和
     * + 今日已 SOLD 成交 count 之和), 见 {@link com.miningdim.market.store.MarketDao#soldOrListedCountToday}。
     */
    public static final int COPPER_IRON_DAILY_P2P_CAP = 512;

    /**
     * 受每日 P2P 量上限约束的铜/铁标的 item_id 集合 (DRAFT, 按定价台账语义取定; 见 notes 报告对取定的说明)。
     *
     * 取定依据: 定价台账把"铜/铁"作为低价大宗矿管控, 故纳入原矿 (块矿 *_ore)、粗矿 (raw_*) 与锭 (*_ingot) 三态,
     * 覆盖玩家从挖掘到冶炼后的全部可交易形态 —— 任一形态不纳入即留刷量绕过口 (例只管锭则改挂粗矿绕过)。
     * 深层矿 deepslate_copper_ore / deepslate_iron_ore 是否单列待定价台账确认: 其掉落物经熔炼后并入同一 ingot,
     * 当前仅按掉落物 (raw_*) 与产物 (*_ingot) 归并管控, 不单列深层块矿 id (块矿本身极少被直接挂单交易)。
     */
    public static final Set<String> COPPER_IRON_ITEM_IDS = Set.of(
            "minecraft:copper_ore",
            "minecraft:raw_copper",
            "minecraft:copper_ingot",
            "minecraft:iron_ore",
            "minecraft:raw_iron",
            "minecraft:iron_ingot");

    /** 市场只允许信用点计价 (契约第 1 节: AZURE 不可转移, 非 CREDIT 一律拒绝挂单)。 */
    public static final String CURRENCY_CREDIT = "CREDIT";
}

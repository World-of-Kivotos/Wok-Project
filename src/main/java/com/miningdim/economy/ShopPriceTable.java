package com.miningdim.economy;

import com.miningdim.economy.EconomyConstants.HighValueOre;

/**
 * 矿物收购锚价表 (经济文档 8.1 "量化锚 · ×10 物价基准 · 结构 DECIDED / 绝对值 config")。
 *
 * 8.1 把信用点物价定为 ×10 面值: 高价矿基础收购价 钻石 500 / 金锭 120 / 下界残骸 4,500 (信用点/个)。
 * 本表只承载货币层 faucet 结算所需的"高价矿基础价"——它喂给 {@link IEconomyService#settleOreSale}
 * 做 18.3 软上限衰减 ({@link AbuseGuard#buyPrice})。低价矿 (煤/红石/铁等) 与弹药/枪械 sink 价 (8.2/8.3)
 * 属各自子系统的物价职责, 不落本货币层 (YAGNI: 货币层只接最大 faucet 的高价矿龙头)。
 *
 * "结构 DECIDED / 绝对值 config" (8.1): 此处常量是 ×10 锚的起始基准, 上线后经 7.3 观测校准应改为读
 * ForgeConfig; 在 config 接线前它们是 settleOreSale 生效所必需的真实锚价, 非占位。
 */
public final class ShopPriceTable {

    private ShopPriceTable() {
    }

    /** 钻石基础收购价 (8.1 ×10 锚: 500 信用点/个)。 */
    public static final double ORE_BASE_DIAMOND = 500.0D;

    /** 金锭基础收购价 (8.1 ×10 锚: 120 信用点/个)。 */
    public static final double ORE_BASE_GOLD = 120.0D;

    /** 下界残骸基础收购价 (8.1 ×10 锚: 4,500 信用点/个)。 */
    public static final double ORE_BASE_NETHERITE_SCRAP = 4_500.0D;

    /**
     * 某高价矿的基础收购价 (8.1 ×10 锚)。供 {@link IEconomyService#settleOreSale} 取 base 后做衰减。
     *
     * @param ore 高价矿种
     * @return 该矿基础收购价 (信用点/个)
     */
    public static double oreBasePrice(HighValueOre ore) {
        return switch (ore) {
            case DIAMOND -> ORE_BASE_DIAMOND;
            case GOLD -> ORE_BASE_GOLD;
            case NETHERITE_SCRAP -> ORE_BASE_NETHERITE_SCRAP;
        };
    }
}

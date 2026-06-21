package com.miningdim.market;

import com.miningdim.economy.ShopPriceTable;

import java.util.Map;
import java.util.OptionalLong;

/**
 * 物品基准价值 V0 的代码内置预设 (偏离费 {@link MarketFee} 锚的"原版预设 + 高价矿锚"层)。
 *
 * V0 分层解析 (用户设计): admin 后台手写覆盖 (commit 2 接 base_values 表) -&gt; 本代码预设 -&gt; 市场成交中位数 (commit 3,
 * 带钳制) -&gt; 无锚退平率费。本类只承载"开服第一天就该有、且操纵不动"的强锚: 高价矿龙头 (转引 {@link ShopPriceTable}
 * ×10 锚, 单一真值不复制字面量) 与农夫 mod 小麦基准。原版长尾与各 mod 物品暂无预设 -&gt; resolver 退平 20% (诚实兜底),
 * 由 admin 逐个 curate 提升成强锚。
 *
 * 数值纪律: 高价矿 V0 = ShopPriceTable 的 ×10 收购锚 (钻 500 / 金锭 120 / 残骸 4500); 小麦 V0 = 1
 * (与 job.farmer.FarmerConstants.WHEAT_BASE_PRICE 同值, 但本市场包不依赖 job 包实现, 用字面量 1 对齐, 见 notes)。
 */
public final class DefaultBaseValues {

    private DefaultBaseValues() {
    }

    /**
     * item_id -&gt; 基准价值 V0 (信用点/个) 的内置预设。高价矿龙头转引 ShopPriceTable ×10 锚;
     * miningdim:farmer_wheat = 1 与农夫收购基价对齐 (字面量, 不 import job 包)。
     */
    private static final Map<String, Long> PRESET = Map.of(
            "minecraft:diamond", (long) ShopPriceTable.ORE_BASE_DIAMOND,
            "minecraft:gold_ingot", (long) ShopPriceTable.ORE_BASE_GOLD,
            "minecraft:netherite_scrap", (long) ShopPriceTable.ORE_BASE_NETHERITE_SCRAP,
            "miningdim:farmer_wheat", 1L);

    /**
     * 解析某物品的内置预设 V0; 无预设返回 {@link OptionalLong#empty()} (调用方退下一层: 后续 admin 覆盖/中位数, 最终平率)。
     *
     * @param itemId 物品注册 id (如 minecraft:diamond)
     * @return 该物品的预设基准价值, 无预设则空
     */
    public static OptionalLong resolve(String itemId) {
        Long v = PRESET.get(itemId);
        return v == null ? OptionalLong.empty() : OptionalLong.of(v);
    }
}

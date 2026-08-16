package com.miningdim.enchant;

import com.miningdim.economy.ShopPriceTable;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 金钱修补的计价配置 (miningdim-money-mending.toml)。
 *
 * <b>定价模型 (主控 2026-08-17 拍板倍率 2.0)</b>:
 * {@code 每点耐久费用 = 该物品的材料总价 ÷ 最大耐久 × 倍率}。
 *
 * 为什么不是一个全局的"X 信用点修 1 点耐久"常数: 各类装备的"每点耐久内含多少信用点"相差 50 倍以上
 * (钻石镐 0.96 / 下界合金头盔 51.5)。定一个低常数会让高价装备变成套利机器 —— 收破损的下界合金装、修满、
 * 转手卖, 稳赚十几倍; 定一个高常数则钻石镐修满比重做一把还贵几十倍, 附魔直接是废票。挂在物品自身价值上,
 * 倍率 &gt; 1 就从数学上杜绝套利 (修永远比材料贵), 同时它是笔真 sink —— 这正是当前经济最缺的东西。
 *
 * 单位材料价<b>只有铁是本文件新引入的</b>: 金锭与钻石直接读 {@link ShopPriceTable} 的既有锚价 (单一真源,
 * 那边调价这边跟着走), 下界合金锭按其合成配方从下界残骸与金锭推导。铁不在锚价表里 (该表自述只承载"高价矿",
 * 低价矿属各子系统自理), 故此处按<b>维修计价口径</b>单列一个值 —— 它不是铁的市场价, 只用于算维修费。
 */
public final class MoneyMendingConfig {

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.DoubleValue PRICE_MULTIPLIER;
    public static final ForgeConfigSpec.LongValue IRON_UNIT_VALUE;
    public static final ForgeConfigSpec.IntValue REPAIR_POINTS_PER_SECOND;

    private MoneyMendingConfig() {
    }

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("moneyMending");
        ENABLED = builder.comment("Whether the money mending enchantment repairs gear at all")
                .define("enabled", true);
        PRICE_MULTIPLIER = builder.comment(
                        "Repair cost = (item material value / max durability) * this multiplier. Must stay above 1.0: "
                                + "at or below 1.0 repairing costs less than the materials the item is made of, which "
                                + "turns the enchantment into an arbitrage machine (buy broken gear, repair, resell)")
                .defineInRange("priceMultiplier", 2.0D, 1.01D, 100.0D);
        IRON_UNIT_VALUE = builder.comment(
                        "CREDIT value of one iron ingot, for repair costing only. This is NOT a market price: iron is "
                                + "absent from ShopPriceTable by design (that table only carries high-value ores). "
                                + "Gold and diamond are read from ShopPriceTable instead of being duplicated here")
                .defineInRange("ironUnitValue", 60L, 1L, Long.MAX_VALUE);
        REPAIR_POINTS_PER_SECOND = builder.comment(
                        "Durability points restored per equipped item per second. Kept low deliberately: repairing a "
                                + "worn item back to full in one tick would take one large unannounced bite out of the "
                                + "player's balance")
                .defineInRange("repairPointsPerSecond", 10, 1, 1_000);
        builder.pop();

        SPEC = builder.build();
    }

    /** 一个下界合金锭的维修计价值 = 4 下界残骸 + 4 金锭 (原版合成配方)。 */
    public static long netheriteIngotValue() {
        return Math.round(ShopPriceTable.ORE_BASE_NETHERITE_SCRAP * 4.0D + ShopPriceTable.ORE_BASE_GOLD * 4.0D);
    }
}

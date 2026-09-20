package com.miningdim.job.fisher.ore;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.EnumMap;
import java.util.Map;

/** 获取概率与收购价为首测值，由服务器配置统一调整。 */
public final class OreFishingConfig {
    public static final ForgeConfigSpec SPEC;
    static final Map<OreFishType, ForgeConfigSpec.IntValue> CATCH_WEIGHTS = new EnumMap<>(OreFishType.class);
    private static final Map<OreFishType, ForgeConfigSpec.LongValue> SELL_PRICES = new EnumMap<>(OreFishType.class);

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("每次矿洞水域成功钓获的万分权重；五鱼总和不得超过10000，余量沿用原掉落。")
                .push("catch_weights");
        int[] weights = {2000, 800, 200, 100, 20};
        for (OreFishType type : OreFishType.values()) {
            CATCH_WEIGHTS.put(type, builder.defineInRange(type.id(), weights[type.ordinal()], 0, 10000));
        }
        builder.pop();
        builder.comment("原鱼每条基础收购价，实际收入经过统一每日信用点衰减。首测价格可调整。")
                .push("sell_prices");
        long[] prices = {20L, 80L, 400L, 600L, 2000L};
        for (OreFishType type : OreFishType.values()) {
            SELL_PRICES.put(type, builder.defineInRange(type.id(), prices[type.ordinal()], 1L, 1000000L));
        }
        builder.pop();
        SPEC = builder.build();
    }

    private OreFishingConfig() {
    }

    public static int catchWeight(OreFishType type) {
        return CATCH_WEIGHTS.get(type).get();
    }

    public static long sellPrice(OreFishType type) {
        return SELL_PRICES.get(type).get();
    }

    public static void validate() {
        int total = 0;
        for (OreFishType type : OreFishType.values()) {
            total += catchWeight(type);
        }
        if (total > 10000) {
            throw new IllegalArgumentException("矿石鱼钓获权重之和超过10000: " + total);
        }
    }
}

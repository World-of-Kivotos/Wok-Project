package com.miningdim.freshness;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 腐烂底座的服务端配置 ({@code miningdim-freshness.toml}, 由 {@link FreshnessModule} 在 register 内注册)。
 *
 * 保质期**只写在配置里**, 不写进物品: 改完推送在线客户端, 全服存量立刻按新值算 (规格「怎么算」)。
 * 总开关三档是安全阀 (规格「安全阀」): 试运行只盖章、不换腐烂物、不拦截, 只把「本该腐烂多少件」记进统计;
 * 默认停在试运行, 测试服看满一周统计没有误烂再切开启。
 */
public final class FreshnessConfig {

    /** 总开关三档。 */
    public enum Mode {
        /** 关闭: 不盖章、不换腐烂物、不拦截。 */
        OFF,
        /** 试运行: 照常盖章并把「本该腐烂」记进统计, 但不换腐烂物、不拦截任何交互。 */
        DRY_RUN,
        /** 开启: 完整行为。 */
        ON
    }

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.EnumValue<Mode> MODE;
    public static final ForgeConfigSpec.IntValue SHELF_LIFE_FRESH;
    public static final ForgeConfigSpec.IntValue SHELF_LIFE_PRODUCE;
    public static final ForgeConfigSpec.IntValue SHELF_LIFE_COOKED;
    public static final ForgeConfigSpec.IntValue SHELF_LIFE_ROOT;
    public static final ForgeConfigSpec.IntValue SHELF_LIFE_GRAIN;
    public static final ForgeConfigSpec.IntValue SHELF_LIFE_PRESERVED;
    public static final ForgeConfigSpec.IntValue STOP_CAP_HOURS;
    public static final ForgeConfigSpec.IntValue MIN_STORE_HOURS;
    public static final ForgeConfigSpec.IntValue VILLAGER_GOODS_HOURS;
    public static final ForgeConfigSpec.IntValue MARKET_MIN_REMAINING_HOURS;
    public static final ForgeConfigSpec.IntValue MERGE_WINDOW_DIVISOR;
    public static final ForgeConfigSpec.IntValue MERGE_WINDOW_MIN_HOURS;
    public static final ForgeConfigSpec.BooleanValue MIGRATION_ENABLED;
    public static final ForgeConfigSpec.LongValue MIGRATION_ENABLED_AT_MINUTE;
    public static final ForgeConfigSpec.IntValue MIGRATION_GRACE_HOURS;
    public static final ForgeConfigSpec.BooleanValue RECIPE_FILTER_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> RECIPE_FILTER_WHITELIST;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("freshness");
        MODE = builder.comment("Overall switch: OFF / DRY_RUN (stamp and count only) / ON")
                .defineEnum("mode", Mode.DRY_RUN);
        builder.pop();

        builder.push("shelfLife");
        SHELF_LIFE_FRESH = builder.comment("Fresh meat, fish and seafood (hours)")
                .defineInRange("fresh", FreshnessCategory.FRESH.defaultShelfLifeHours(), 1, 8_760);
        SHELF_LIFE_PRODUCE = builder.comment("Fruit, vegetables, eggs and dough (hours)")
                .defineInRange("produce", FreshnessCategory.PRODUCE.defaultShelfLifeHours(), 1, 8_760);
        SHELF_LIFE_COOKED = builder.comment("Cooked dishes; also the fallback for any edible item without a category tag (hours)")
                .defineInRange("cooked", FreshnessCategory.COOKED.defaultShelfLifeHours(), 1, 8_760);
        SHELF_LIFE_ROOT = builder.comment("Root vegetables and whole melons (hours)")
                .defineInRange("root", FreshnessCategory.ROOT.defaultShelfLifeHours(), 1, 8_760);
        SHELF_LIFE_GRAIN = builder.comment("Grains and dry goods (hours)")
                .defineInRange("grain", FreshnessCategory.GRAIN.defaultShelfLifeHours(), 1, 8_760);
        SHELF_LIFE_PRESERVED = builder.comment("Preserved and air-dried food (hours); 168 = 7 days")
                .defineInRange("preserved", FreshnessCategory.PRESERVED.defaultShelfLifeHours(), 1, 8_760);
        builder.pop();

        builder.push("granary");
        STOP_CAP_HOURS = builder.comment("Per-item cap on credited stop hours (504 = 21 days)")
                .defineInRange("stopCapHours", FreshnessConstants.STOP_CAP_HOURS, 1, 24_000);
        MIN_STORE_HOURS = builder.comment("Continuous hours in a granary before the clock stops (24 = one night is not enough)")
                .defineInRange("minStoreHours", FreshnessConstants.MIN_STORE_HOURS, 1, 24_000);
        builder.pop();

        builder.push("consume");
        VILLAGER_GOODS_HOURS = builder.comment("Remaining hours of food bought from villagers")
                .defineInRange("villagerGoodsHours", FreshnessConstants.VILLAGER_GOODS_HOURS, 1, 720);
        MARKET_MIN_REMAINING_HOURS = builder.comment("Minimum remaining hours required to list food on the flea market")
                .defineInRange("marketMinRemainingHours", 6, 1, 720);
        builder.pop();

        builder.push("merge");
        MERGE_WINDOW_DIVISOR = builder.comment("Merge window as a fraction of shelf life (8 = one eighth)")
                .defineInRange("windowDivisor", 8, 1, 64);
        MERGE_WINDOW_MIN_HOURS = builder.comment("Lower bound of the merge window (hours)")
                .defineInRange("windowMinHours", 2, 1, 720);
        builder.pop();

        builder.push("migration");
        MIGRATION_ENABLED = builder.comment("Migrate pre-existing food on the first start after enabling (spoilage spec: 开服迁移)")
                .define("enabled", true);
        MIGRATION_ENABLED_AT_MINUTE = builder.comment(
                        "Epoch MINUTE of the enable instant (0 = use the current minute on first start).",
                        "Items found without a stamp get '6 hours left at the enable instant' + a full stop-hour credit + the migrated flag.",
                        "Set this explicitly when the enable instant must match an announced time; leave 0 to let the server decide once (then persisted).")
                .defineInRange("enabledAtEpochMinute", 0L, 0L, Long.MAX_VALUE);
        MIGRATION_GRACE_HOURS = builder.comment("Hours of life migrated goods get at the enable instant")
                .defineInRange("graceHours", FreshnessConstants.MIGRATION_GRACE_HOURS, 1, 720);
        builder.pop();

        builder.push("recipes");
        RECIPE_FILTER_ENABLED = builder.comment(
                        "Remove 'compressed block back into its perishable parts' crafting recipes (spoilage spec rule 1).",
                        "Only crafting recipes with one ingredient kind, >= 4 outputs, a perishable result and a non-perishable input are removed.")
                .define("removeUnsquashRecipes", true);
        RECIPE_FILTER_WHITELIST = builder.comment(
                        "Recipe ids that must never be removed by the filter (pack authors with legitimate 1-to-many recipes).")
                .defineListAllowEmpty("unsquashWhitelist", java.util.List.of(), entry -> entry instanceof String);
        builder.pop();

        SPEC = builder.build();
    }

    private FreshnessConfig() {
    }

    /** 该类别的保质期 (小时)。 */
    public static int shelfLifeHours(FreshnessCategory category) {
        return switch (category) {
            case FRESH -> SHELF_LIFE_FRESH.get();
            case PRODUCE -> SHELF_LIFE_PRODUCE.get();
            case COOKED -> SHELF_LIFE_COOKED.get();
            case ROOT -> SHELF_LIFE_ROOT.get();
            case GRAIN -> SHELF_LIFE_GRAIN.get();
            case PRESERVED -> SHELF_LIFE_PRESERVED.get();
        };
    }

    /** 是否在盖章 (只要不是关闭就盖章 —— 试运行就是「照常盖章、但不换腐烂物」)。 */
    public static boolean stampingEnabled() {
        return MODE.get() != Mode.OFF;
    }

    /** 是否执行终态替换与拦截 (只有开启档才做)。 */
    public static boolean replacementEnabled() {
        return MODE.get() == Mode.ON;
    }
}

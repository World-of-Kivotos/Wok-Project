package com.miningdim.caseopening;

import net.minecraftforge.common.ForgeConfigSpec;

/** Server-only operating knobs. Complex catalogue data remains code/data-pack shaped, not a TOML string list. */
public final class CaseOpeningConfig {

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.LongValue CREDIT_COST;
    public static final ForgeConfigSpec.LongValue AZURE_COST;
    public static final ForgeConfigSpec.IntValue WEIGHT_BLUE;
    public static final ForgeConfigSpec.IntValue WEIGHT_PURPLE;
    public static final ForgeConfigSpec.IntValue WEIGHT_PINK;
    public static final ForgeConfigSpec.IntValue WEIGHT_RED;
    public static final ForgeConfigSpec.IntValue WEIGHT_GOLD;
    public static final ForgeConfigSpec.IntValue OPEN_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.IntValue ENFORCE_INTERVAL_TICKS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("case");
        ENABLED = builder.comment("Whether the server-authoritative case opening system is enabled")
                .define("enabled", true);
        CREDIT_COST = builder.comment("CREDIT destroyed by one founders-case opening")
                .defineInRange("creditCost", 50_000L, 1L, Long.MAX_VALUE);
        AZURE_COST = builder.comment("Bound AZURE destroyed by one founders-case opening")
                .defineInRange("azureCost", 10L, 1L, Long.MAX_VALUE);
        OPEN_COOLDOWN_TICKS = builder.comment("Minimum server-side interval between new openings by one player")
                .defineInRange("openCooldownTicks", 20, 1, 1_200);
        ENFORCE_INTERVAL_TICKS = builder.comment("How often to strip unauthorized case displays from a held TaCZ gun")
                .defineInRange("ownershipEnforceIntervalTicks", 20, 1, 200);
        builder.pop();

        builder.push("weights");
        WEIGHT_BLUE = builder.defineInRange("blue", 79_110, 1, CaseWeights.TOTAL);
        WEIGHT_PURPLE = builder.defineInRange("purple", 15_500, 1, CaseWeights.TOTAL);
        WEIGHT_PINK = builder.defineInRange("pink", 4_000, 1, CaseWeights.TOTAL);
        WEIGHT_RED = builder.defineInRange("red", 990, 1, CaseWeights.TOTAL);
        WEIGHT_GOLD = builder.defineInRange("gold", 400, 1, CaseWeights.TOTAL);
        builder.pop();
        SPEC = builder.build();
    }

    private CaseOpeningConfig() {
    }

    public static CaseWeights weights() {
        return new CaseWeights(WEIGHT_BLUE.get(), WEIGHT_PURPLE.get(), WEIGHT_PINK.get(),
                WEIGHT_RED.get(), WEIGHT_GOLD.get());
    }
}

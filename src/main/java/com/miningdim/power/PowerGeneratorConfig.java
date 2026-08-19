package com.miningdim.power;

import com.miningdim.power.generator.GeneratorSpec;
import com.miningdim.power.generator.PreheatGeneratorSpec;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 发电机服务器配置。档位身份、源电压、缓冲关系和保护阈值不在配置中重复定义。
 */
public final class PowerGeneratorConfig {

    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.IntValue INDUSTRIAL_PEAK_FE_PER_TICK;
    private static final ForgeConfigSpec.IntValue INDUSTRIAL_FUEL_CORE_DURABILITY;
    private static final ForgeConfigSpec.DoubleValue INDUSTRIAL_MELTDOWN_TEMPERATURE_C;
    private static final ForgeConfigSpec.DoubleValue INDUSTRIAL_MAX_REJECTED_TEMPERATURE_RISE_PER_TICK;
    private static final ForgeConfigSpec.DoubleValue INDUSTRIAL_LOW_LOAD_COOLING_PER_TICK;
    private static final ForgeConfigSpec.IntValue INDUSTRIAL_SCATTER_RADIUS;
    private static final ForgeConfigSpec.IntValue INDUSTRIAL_MAX_DESTRUCTIBLE_BLOCKS;
    private static final ForgeConfigSpec.IntValue INDUSTRIAL_MAX_FIRE_POINTS;
    private static final ForgeConfigSpec.DoubleValue INDUSTRIAL_CENTER_DAMAGE_FRACTION;

    private static final ForgeConfigSpec.IntValue MODERN_PEAK_FE_PER_TICK;
    private static final ForgeConfigSpec.IntValue MODERN_FUEL_CORE_DURABILITY;
    private static final ForgeConfigSpec.DoubleValue MODERN_MELTDOWN_TEMPERATURE_C;
    private static final ForgeConfigSpec.DoubleValue MODERN_MAX_REJECTED_TEMPERATURE_RISE_PER_TICK;
    private static final ForgeConfigSpec.DoubleValue MODERN_LOW_LOAD_COOLING_PER_TICK;
    private static final ForgeConfigSpec.IntValue MODERN_SCATTER_RADIUS;
    private static final ForgeConfigSpec.IntValue MODERN_MAX_DESTRUCTIBLE_BLOCKS;
    private static final ForgeConfigSpec.IntValue MODERN_MAX_FIRE_POINTS;
    private static final ForgeConfigSpec.DoubleValue MODERN_CENTER_DAMAGE_FRACTION;

    private static final ForgeConfigSpec.IntValue FUTURE_PEAK_FE_PER_TICK;
    private static final ForgeConfigSpec.IntValue FUTURE_FUEL_CORE_DURABILITY;
    private static final ForgeConfigSpec.DoubleValue FUTURE_MELTDOWN_TEMPERATURE_C;
    private static final ForgeConfigSpec.DoubleValue FUTURE_MAX_REJECTED_TEMPERATURE_RISE_PER_TICK;
    private static final ForgeConfigSpec.DoubleValue FUTURE_LOW_LOAD_COOLING_PER_TICK;
    private static final ForgeConfigSpec.IntValue FUTURE_SCATTER_RADIUS;
    private static final ForgeConfigSpec.IntValue FUTURE_MAX_DESTRUCTIBLE_BLOCKS;
    private static final ForgeConfigSpec.IntValue FUTURE_MAX_FIRE_POINTS;
    private static final ForgeConfigSpec.DoubleValue FUTURE_CENTER_DAMAGE_FRACTION;

    private static final ForgeConfigSpec.IntValue COAL_PEAK_FE_PER_TICK;
    private static final ForgeConfigSpec.DoubleValue COAL_WORKING_TEMPERATURE_C;
    private static final ForgeConfigSpec.IntValue COAL_PREHEAT_TICKS;
    private static final ForgeConfigSpec.DoubleValue COAL_COOLING_PER_TICK;
    private static final ForgeConfigSpec.IntValue COAL_FUEL_BURN_MULTIPLIER;

    private static final ForgeConfigSpec.IntValue GEOTHERMAL_PEAK_FE_PER_TICK;
    private static final ForgeConfigSpec.DoubleValue GEOTHERMAL_WORKING_TEMPERATURE_C;
    private static final ForgeConfigSpec.IntValue GEOTHERMAL_PREHEAT_TICKS;
    private static final ForgeConfigSpec.DoubleValue GEOTHERMAL_COOLING_PER_TICK;
    private static final ForgeConfigSpec.IntValue GEOTHERMAL_FUEL_BURN_MULTIPLIER;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("industrial");
        INDUSTRIAL_PEAK_FE_PER_TICK = builder.defineInRange("peakFePerTick", 192, 1, 1_000_000);
        INDUSTRIAL_FUEL_CORE_DURABILITY = builder.defineInRange(
                "fuelCoreDurability", 3_600, 1, 1_000_000);
        INDUSTRIAL_MELTDOWN_TEMPERATURE_C = builder.defineInRange(
                "meltdownTemperatureC", 200.0D, 21.0D, 10_000.0D);
        INDUSTRIAL_MAX_REJECTED_TEMPERATURE_RISE_PER_TICK = builder.defineInRange(
                "maxRejectedTemperatureRisePerTick", 0.25D, 0.0D, 1_000.0D);
        INDUSTRIAL_LOW_LOAD_COOLING_PER_TICK = builder.defineInRange(
                "lowLoadCoolingPerTick", 0.10D, 0.0D, 1_000.0D);
        INDUSTRIAL_SCATTER_RADIUS = builder.defineInRange("scatterRadius", 4, 1, 32);
        INDUSTRIAL_MAX_DESTRUCTIBLE_BLOCKS = builder.defineInRange("maxDestructibleBlocks", 64, 1, 4_096);
        INDUSTRIAL_MAX_FIRE_POINTS = builder.defineInRange("maxFirePoints", 8, 1, 1_024);
        INDUSTRIAL_CENTER_DAMAGE_FRACTION = builder.defineInRange(
                "centerDamageFraction", 0.25D, 0.0001D, 1.0D);
        builder.pop();

        builder.push("modern");
        MODERN_PEAK_FE_PER_TICK = builder.defineInRange("peakFePerTick", 1_152, 1, 1_000_000);
        MODERN_FUEL_CORE_DURABILITY = builder.defineInRange(
                "fuelCoreDurability", 7_200, 1, 1_000_000);
        MODERN_MELTDOWN_TEMPERATURE_C = builder.defineInRange(
                "meltdownTemperatureC", 260.0D, 21.0D, 10_000.0D);
        MODERN_MAX_REJECTED_TEMPERATURE_RISE_PER_TICK = builder.defineInRange(
                "maxRejectedTemperatureRisePerTick", 0.50D, 0.0D, 1_000.0D);
        MODERN_LOW_LOAD_COOLING_PER_TICK = builder.defineInRange(
                "lowLoadCoolingPerTick", 0.15D, 0.0D, 1_000.0D);
        MODERN_SCATTER_RADIUS = builder.defineInRange("scatterRadius", 8, 1, 32);
        MODERN_MAX_DESTRUCTIBLE_BLOCKS = builder.defineInRange("maxDestructibleBlocks", 192, 1, 4_096);
        MODERN_MAX_FIRE_POINTS = builder.defineInRange("maxFirePoints", 24, 1, 1_024);
        MODERN_CENTER_DAMAGE_FRACTION = builder.defineInRange(
                "centerDamageFraction", 0.40D, 0.0001D, 1.0D);
        builder.pop();

        builder.push("future");
        FUTURE_PEAK_FE_PER_TICK = builder.defineInRange("peakFePerTick", 3_072, 1, 1_000_000);
        FUTURE_FUEL_CORE_DURABILITY = builder.defineInRange(
                "fuelCoreDurability", 14_400, 1, 1_000_000);
        FUTURE_MELTDOWN_TEMPERATURE_C = builder.defineInRange(
                "meltdownTemperatureC", 320.0D, 21.0D, 10_000.0D);
        FUTURE_MAX_REJECTED_TEMPERATURE_RISE_PER_TICK = builder.defineInRange(
                "maxRejectedTemperatureRisePerTick", 1.00D, 0.0D, 1_000.0D);
        FUTURE_LOW_LOAD_COOLING_PER_TICK = builder.defineInRange(
                "lowLoadCoolingPerTick", 0.20D, 0.0D, 1_000.0D);
        FUTURE_SCATTER_RADIUS = builder.defineInRange("scatterRadius", 24, 1, 32);
        FUTURE_MAX_DESTRUCTIBLE_BLOCKS = builder.defineInRange("maxDestructibleBlocks", 512, 1, 4_096);
        FUTURE_MAX_FIRE_POINTS = builder.defineInRange("maxFirePoints", 64, 1, 1_024);
        FUTURE_CENTER_DAMAGE_FRACTION = builder.defineInRange(
                "centerDamageFraction", 0.60D, 0.0001D, 1.0D);
        builder.pop();

        builder.push("coal");
        COAL_PEAK_FE_PER_TICK = builder.defineInRange("peakFePerTick", 48, 1, 1_000_000);
        COAL_WORKING_TEMPERATURE_C = builder.defineInRange(
                "workingTemperatureC", 300.0D, 21.0D, 10_000.0D);
        COAL_PREHEAT_TICKS = builder.defineInRange("preheatTicks", 1_200, 1, 1_000_000);
        COAL_COOLING_PER_TICK = builder.defineInRange("coolingPerTick", 0.15D, 0.0D, 1_000.0D);
        COAL_FUEL_BURN_MULTIPLIER = builder.comment(
                        "Multiplier applied to the vanilla burn time of the inserted fuel.",
                        "Raw coal only burns 1600 ticks, which would force a refuel every 80 seconds.")
                .defineInRange("fuelBurnMultiplier", 4, 1, 1_000);
        builder.pop();

        builder.push("geothermal");
        GEOTHERMAL_PEAK_FE_PER_TICK = builder.defineInRange("peakFePerTick", 144, 1, 1_000_000);
        GEOTHERMAL_WORKING_TEMPERATURE_C = builder.defineInRange(
                "workingTemperatureC", 600.0D, 21.0D, 10_000.0D);
        GEOTHERMAL_PREHEAT_TICKS = builder.defineInRange("preheatTicks", 2_400, 1, 1_000_000);
        GEOTHERMAL_COOLING_PER_TICK = builder.defineInRange("coolingPerTick", 0.20D, 0.0D, 1_000.0D);
        GEOTHERMAL_FUEL_BURN_MULTIPLIER = builder.defineInRange("fuelBurnMultiplier", 1, 1, 1_000);
        builder.pop();

        PowerStorageConfig.define(builder);
        PowerMachineConfig.define(builder);
        SPEC = builder.build();
    }

    private PowerGeneratorConfig() {
    }

    public static GeneratorSpec.Runtime profile(GeneratorSpec spec) {
        return switch (spec) {
            case LOW -> runtime(INDUSTRIAL_PEAK_FE_PER_TICK.get(), INDUSTRIAL_FUEL_CORE_DURABILITY.get(),
                    INDUSTRIAL_MELTDOWN_TEMPERATURE_C.get(),
                    INDUSTRIAL_MAX_REJECTED_TEMPERATURE_RISE_PER_TICK.get(),
                    INDUSTRIAL_LOW_LOAD_COOLING_PER_TICK.get(), INDUSTRIAL_SCATTER_RADIUS.get(),
                    INDUSTRIAL_MAX_DESTRUCTIBLE_BLOCKS.get(), INDUSTRIAL_MAX_FIRE_POINTS.get(),
                    INDUSTRIAL_CENTER_DAMAGE_FRACTION.get());
            case MEDIUM -> runtime(MODERN_PEAK_FE_PER_TICK.get(), MODERN_FUEL_CORE_DURABILITY.get(),
                    MODERN_MELTDOWN_TEMPERATURE_C.get(),
                    MODERN_MAX_REJECTED_TEMPERATURE_RISE_PER_TICK.get(),
                    MODERN_LOW_LOAD_COOLING_PER_TICK.get(), MODERN_SCATTER_RADIUS.get(),
                    MODERN_MAX_DESTRUCTIBLE_BLOCKS.get(), MODERN_MAX_FIRE_POINTS.get(),
                    MODERN_CENTER_DAMAGE_FRACTION.get());
            case HIGH -> runtime(FUTURE_PEAK_FE_PER_TICK.get(), FUTURE_FUEL_CORE_DURABILITY.get(),
                    FUTURE_MELTDOWN_TEMPERATURE_C.get(),
                    FUTURE_MAX_REJECTED_TEMPERATURE_RISE_PER_TICK.get(),
                    FUTURE_LOW_LOAD_COOLING_PER_TICK.get(), FUTURE_SCATTER_RADIUS.get(),
                    FUTURE_MAX_DESTRUCTIBLE_BLOCKS.get(), FUTURE_MAX_FIRE_POINTS.get(),
                    FUTURE_CENTER_DAMAGE_FRACTION.get());
        };
    }

    public static PreheatGeneratorSpec.Runtime preheatProfile(PreheatGeneratorSpec spec) {
        return switch (spec) {
            case COAL -> new PreheatGeneratorSpec.Runtime(COAL_PEAK_FE_PER_TICK.get(),
                    COAL_WORKING_TEMPERATURE_C.get(), COAL_PREHEAT_TICKS.get(),
                    COAL_COOLING_PER_TICK.get(), COAL_FUEL_BURN_MULTIPLIER.get());
            case GEOTHERMAL -> new PreheatGeneratorSpec.Runtime(GEOTHERMAL_PEAK_FE_PER_TICK.get(),
                    GEOTHERMAL_WORKING_TEMPERATURE_C.get(), GEOTHERMAL_PREHEAT_TICKS.get(),
                    GEOTHERMAL_COOLING_PER_TICK.get(), GEOTHERMAL_FUEL_BURN_MULTIPLIER.get());
        };
    }

    private static GeneratorSpec.Runtime runtime(int peakFePerTick, int coreDurability,
                                                   double meltdownTemperatureC,
                                                   double maxRejectedTemperatureRiseCPerTick,
                                                   double lowLoadCoolingCPerTick, int scatterRadius,
                                                   int maxDestructibleBlocks, int maxFirePoints,
                                                   double centerDamageFraction) {
        return new GeneratorSpec.Runtime(peakFePerTick, coreDurability, meltdownTemperatureC,
                maxRejectedTemperatureRiseCPerTick, lowLoadCoolingCPerTick, scatterRadius,
                maxDestructibleBlocks, maxFirePoints, centerDamageFraction);
    }
}

package com.miningdim.job.engineer.shield;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.EnumMap;
import java.util.Map;

/** Server-side balance values for the plasma-shield state machine. */
public final class PlasmaShieldConfig {

    private final ForgeConfigSpec.DoubleValue maxHeat;
    private final ForgeConfigSpec.DoubleValue restartHeat;
    private final ForgeConfigSpec.IntValue stateTickInterval;
    private final ForgeConfigSpec.IntValue heatCoolDelayTicks;
    private final Map<PlasmaShieldVariant, Values> values = new EnumMap<>(PlasmaShieldVariant.class);

    private PlasmaShieldConfig(ForgeConfigSpec.Builder builder) {
        builder.push("plasmaShield");
        builder.comment("Energy shield values are server-authoritative. Rates are per second unless named in ticks.");
        maxHeat = builder.comment("Heat at which the shield immediately shuts down.")
                .defineInRange("maxHeat", 100.0D, 1.0D, 100000.0D);
        restartHeat = builder.comment("An overheated shield restarts only after cooling to this heat value.")
                .defineInRange("restartHeat", 30.0D, 0.0D, 100000.0D);
        stateTickInterval = builder.comment("Server ticks between cooling/recharge settlements. 5 = four HUD updates per second.")
                .defineInRange("stateTickInterval", 5, 1, 20);
        heatCoolDelayTicks = builder.comment("Cooling delay after a non-overheated shield is hit. Overheated shields emergency-cool immediately.")
                .defineInRange("heatCoolDelayTicks", 20, 0, 1200);

        builder.push("balanceV4");
        builder.comment("Version 4 values enlarge the active shield layer without increasing total battery storage.",
                "These values retain finite batteries and distinct overheat/recovery roles.",
                "They are intentionally isolated from earlier shield balance paths so existing V3 capacity values do not override the new defaults.",
                "One absorbed damage always consumes exactly one shield energy; no reduction factor is applied.");
        for (PlasmaShieldVariant variant : PlasmaShieldVariant.values()) {
            defineVariant(builder, variant, defaults(variant));
        }
        builder.pop();
        builder.pop();
    }

    public static PlasmaShieldConfig define(ForgeConfigSpec.Builder builder) {
        return new PlasmaShieldConfig(builder);
    }

    private void defineVariant(ForgeConfigSpec.Builder builder,
                               PlasmaShieldVariant variant,
                               Defaults defaults) {
        builder.push(variant.id());
        builder.comment("Balance for " + variant.itemId() + ".");
        Values configured = new Values(
                builder.comment("Maximum raw damage absorbed at one energy per damage before heat limits apply.")
                        .defineInRange("capacity", defaults.capacity(), 1.0D, 100000.0D),
                builder.comment("Total remaining battery, including energy currently allocated to the shield layer.")
                        .defineInRange("maxTotalEnergy", defaults.maxTotalEnergy(),
                                defaults.capacity(), 1000000.0D),
                builder.comment("Heat generated per absorbed damage point.")
                        .defineInRange("heatPerDamage", defaults.heatPerDamage(), 0.001D, 1000.0D),
                builder.comment("Heat removed per second after the cooling delay.")
                        .defineInRange("coolingPerSecond", defaults.coolingPerSecond(), 0.0D, 100000.0D),
                builder.comment("Shield energy restored per second after the recharge delay.")
                        .defineInRange("rechargePerSecond", defaults.rechargePerSecond(), 0.0D, 100000.0D),
                builder.comment("Ticks without eligible damage before shield recharge starts.")
                        .defineInRange("rechargeDelayTicks", defaults.rechargeDelayTicks(), 0, 72000),
                builder.comment("Movement-speed MULTIPLY_TOTAL modifier; -0.12 = -12%.")
                        .defineInRange("movementModifier", defaults.movementModifier(), -0.95D, 10.0D));
        values.put(variant, configured);
        builder.pop();
    }

    public Stats stats(PlasmaShieldVariant variant) {
        Values configured = values.get(variant);
        if (configured == null) {
            throw new IllegalArgumentException("unregistered plasma shield variant: " + variant);
        }
        double heatMaximum = maxHeat.get();
        return new Stats(
                configured.capacity().get(),
                Math.max(configured.capacity().get(), configured.maxTotalEnergy().get()),
                heatMaximum,
                Math.min(heatMaximum, restartHeat.get()),
                configured.heatPerDamage().get(),
                configured.coolingPerSecond().get(),
                configured.rechargePerSecond().get(),
                configured.rechargeDelayTicks().get(),
                heatCoolDelayTicks.get(),
                configured.movementModifier().get());
    }

    /** Existing callers and hidden legacy items resolve to their corresponding tier-I variant. */
    public Stats stats(PlasmaShieldType legacyType) {
        return stats(legacyType.variant());
    }

    public int stateTickInterval() {
        return stateTickInterval.get();
    }

    public record Stats(double capacity,
                        double maxTotalEnergy,
                        double maxHeat,
                        double restartHeat,
                        double heatPerDamage,
                        double coolingPerSecond,
                        double rechargePerSecond,
                        int rechargeDelayTicks,
                        int heatCoolDelayTicks,
                        double movementModifier) {
    }

    private static Defaults defaults(PlasmaShieldVariant variant) {
        return switch (variant) {
            case NANO_I -> new Defaults(30.0D, 60.0D, 0.50D, 30.0D, 18.0D, 90, 0.0D);
            case NANO_II -> new Defaults(45.0D, 84.0D, 0.44D, 34.0D, 22.0D, 88, 0.0D);
            case NANO_III -> new Defaults(65.0D, 114.0D, 0.39D, 38.0D, 26.0D, 86, 0.0D);
            case NANO_IV -> new Defaults(90.0D, 150.0D, 0.34D, 42.0D, 30.0D, 84, 0.0D);
            case NANO_V -> new Defaults(120.0D, 192.0D, 0.30D, 46.0D, 34.0D, 82, 0.0D);
            case NANO_VI -> new Defaults(155.0D, 240.0D, 0.26D, 50.0D, 38.0D, 80, 0.0D);

            case STANDARD_I -> new Defaults(45.0D, 112.0D, 2.20D, 10.0D, 7.0D, 110, 0.0D);
            case STANDARD_II -> new Defaults(70.0D, 160.0D, 2.00D, 11.0D, 8.0D, 108, 0.0D);
            case STANDARD_III -> new Defaults(100.0D, 216.0D, 1.80D, 12.0D, 9.0D, 106, 0.0D);
            case STANDARD_IV -> new Defaults(140.0D, 280.0D, 1.60D, 13.0D, 10.0D, 104, 0.0D);
            case STANDARD_V -> new Defaults(190.0D, 360.0D, 1.40D, 14.0D, 11.0D, 102, 0.0D);
            case STANDARD_VI -> new Defaults(250.0D, 448.0D, 1.20D, 15.0D, 12.0D, 100, 0.0D);

            case QUANTUM_I -> new Defaults(65.0D, 240.0D, 0.65D, 5.0D, 3.0D, 130, -0.12D);
            case QUANTUM_II -> new Defaults(100.0D, 336.0D, 0.58D, 5.6D, 3.5D, 128, -0.114D);
            case QUANTUM_III -> new Defaults(150.0D, 444.0D, 0.52D, 6.2D, 4.0D, 126, -0.108D);
            case QUANTUM_IV -> new Defaults(215.0D, 576.0D, 0.46D, 6.8D, 4.5D, 124, -0.102D);
            case QUANTUM_V -> new Defaults(300.0D, 732.0D, 0.41D, 7.4D, 5.0D, 122, -0.096D);
            case QUANTUM_VI -> new Defaults(400.0D, 912.0D, 0.36D, 8.0D, 5.5D, 120, -0.09D);
        };
    }

    private record Defaults(double capacity,
                            double maxTotalEnergy,
                            double heatPerDamage,
                            double coolingPerSecond,
                            double rechargePerSecond,
                            int rechargeDelayTicks,
                            double movementModifier) {
    }

    private record Values(ForgeConfigSpec.DoubleValue capacity,
                          ForgeConfigSpec.DoubleValue maxTotalEnergy,
                          ForgeConfigSpec.DoubleValue heatPerDamage,
                          ForgeConfigSpec.DoubleValue coolingPerSecond,
                          ForgeConfigSpec.DoubleValue rechargePerSecond,
                          ForgeConfigSpec.IntValue rechargeDelayTicks,
                          ForgeConfigSpec.DoubleValue movementModifier) {
    }
}

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

        for (PlasmaShieldVariant variant : PlasmaShieldVariant.values()) {
            defineVariant(builder, variant, defaults(variant));
        }
        builder.pop();
    }

    public static PlasmaShieldConfig define(ForgeConfigSpec.Builder builder) {
        return new PlasmaShieldConfig(builder);
    }

    private void defineVariant(ForgeConfigSpec.Builder builder,
                               PlasmaShieldVariant variant,
                               Defaults defaults) {
        builder.push(variant.configId());
        builder.comment("Balance for " + variant.itemId()
                + ". Tier-I paths keep the former nano/light/heavy_ion keys for save compatibility.");
        Values configured = new Values(
                builder.comment("Maximum stored shield energy (also the extra-health capacity).")
                        .defineInRange("capacity", defaults.capacity(), 1.0D, 100000.0D),
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
                heatMaximum,
                Math.min(heatMaximum, restartHeat.get()),
                configured.heatPerDamage().get(),
                configured.coolingPerSecond().get(),
                configured.rechargePerSecond().get(),
                configured.rechargeDelayTicks().get(),
                heatCoolDelayTicks.get(),
                configured.movementModifier().get());
    }

    /** Existing callers and hidden legacy items resolve to the unchanged tier-I defaults. */
    public Stats stats(PlasmaShieldType legacyType) {
        return stats(legacyType.variant());
    }

    public int stateTickInterval() {
        return stateTickInterval.get();
    }

    public record Stats(double capacity,
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
            case NANO_I -> new Defaults(36.0D, 0.65D, 20.0D, 8.0D, 60, 0.0D);
            case NANO_II -> new Defaults(42.0D, 0.62D, 22.0D, 9.0D, 57, 0.0D);
            case NANO_III -> new Defaults(48.0D, 0.59D, 24.0D, 10.0D, 54, 0.0D);
            case NANO_IV -> new Defaults(55.0D, 0.56D, 26.0D, 11.0D, 51, 0.0D);
            case NANO_V -> new Defaults(63.0D, 0.53D, 28.0D, 12.0D, 48, 0.0D);
            case NANO_VI -> new Defaults(72.0D, 0.50D, 30.0D, 13.0D, 45, 0.0D);

            case STANDARD_I -> new Defaults(60.0D, 1.70D, 14.0D, 7.0D, 80, 0.0D);
            case STANDARD_II -> new Defaults(69.0D, 1.64D, 15.2D, 7.8D, 77, 0.0D);
            case STANDARD_III -> new Defaults(79.0D, 1.58D, 16.4D, 8.6D, 74, 0.0D);
            case STANDARD_IV -> new Defaults(91.0D, 1.52D, 17.6D, 9.4D, 71, 0.0D);
            case STANDARD_V -> new Defaults(105.0D, 1.46D, 18.8D, 10.2D, 68, 0.0D);
            case STANDARD_VI -> new Defaults(120.0D, 1.40D, 20.0D, 11.0D, 65, 0.0D);

            case QUANTUM_I -> new Defaults(140.0D, 1.00D, 5.0D, 5.0D, 120, -0.12D);
            case QUANTUM_II -> new Defaults(161.0D, 0.96D, 5.6D, 5.7D, 116, -0.114D);
            case QUANTUM_III -> new Defaults(185.0D, 0.92D, 6.2D, 6.4D, 112, -0.108D);
            case QUANTUM_IV -> new Defaults(213.0D, 0.88D, 6.8D, 7.1D, 108, -0.102D);
            case QUANTUM_V -> new Defaults(245.0D, 0.84D, 7.4D, 7.8D, 104, -0.096D);
            case QUANTUM_VI -> new Defaults(280.0D, 0.80D, 8.0D, 8.5D, 100, -0.09D);
        };
    }

    private record Defaults(double capacity,
                            double heatPerDamage,
                            double coolingPerSecond,
                            double rechargePerSecond,
                            int rechargeDelayTicks,
                            double movementModifier) {
    }

    private record Values(ForgeConfigSpec.DoubleValue capacity,
                          ForgeConfigSpec.DoubleValue heatPerDamage,
                          ForgeConfigSpec.DoubleValue coolingPerSecond,
                          ForgeConfigSpec.DoubleValue rechargePerSecond,
                          ForgeConfigSpec.IntValue rechargeDelayTicks,
                          ForgeConfigSpec.DoubleValue movementModifier) {
    }
}

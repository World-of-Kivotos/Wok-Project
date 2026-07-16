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
    private final Map<PlasmaShieldType, Values> values = new EnumMap<>(PlasmaShieldType.class);

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

        defineType(builder, PlasmaShieldType.NANO,
                36.0D, 0.65D, 20.0D, 8.0D, 60, 0.0D);
        defineType(builder, PlasmaShieldType.LIGHT,
                60.0D, 1.70D, 14.0D, 7.0D, 80, 0.0D);
        defineType(builder, PlasmaShieldType.HEAVY_ION,
                140.0D, 1.00D, 5.0D, 5.0D, 120, -0.12D);
        builder.pop();
    }

    public static PlasmaShieldConfig define(ForgeConfigSpec.Builder builder) {
        return new PlasmaShieldConfig(builder);
    }

    private void defineType(ForgeConfigSpec.Builder builder, PlasmaShieldType type,
                            double capacityDefault, double heatPerDamageDefault,
                            double coolingPerSecondDefault, double rechargePerSecondDefault,
                            int rechargeDelayDefault, double movementDefault) {
        builder.push(type.id());
        Values configured = new Values(
                builder.comment("Maximum stored shield energy (also the extra-health capacity).")
                        .defineInRange("capacity", capacityDefault, 1.0D, 100000.0D),
                builder.comment("Heat generated per absorbed damage point.")
                        .defineInRange("heatPerDamage", heatPerDamageDefault, 0.001D, 1000.0D),
                builder.comment("Heat removed per second after the cooling delay.")
                        .defineInRange("coolingPerSecond", coolingPerSecondDefault, 0.0D, 100000.0D),
                builder.comment("Shield energy restored per second after the recharge delay.")
                        .defineInRange("rechargePerSecond", rechargePerSecondDefault, 0.0D, 100000.0D),
                builder.comment("Ticks without eligible damage before shield recharge starts.")
                        .defineInRange("rechargeDelayTicks", rechargeDelayDefault, 0, 72000),
                builder.comment("Movement-speed MULTIPLY_TOTAL modifier; -0.12 = -12%.")
                        .defineInRange("movementModifier", movementDefault, -0.95D, 10.0D));
        values.put(type, configured);
        builder.pop();
    }

    public Stats stats(PlasmaShieldType type) {
        Values configured = values.get(type);
        if (configured == null) {
            throw new IllegalArgumentException("unregistered plasma shield type: " + type);
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

    private record Values(ForgeConfigSpec.DoubleValue capacity,
                          ForgeConfigSpec.DoubleValue heatPerDamage,
                          ForgeConfigSpec.DoubleValue coolingPerSecond,
                          ForgeConfigSpec.DoubleValue rechargePerSecond,
                          ForgeConfigSpec.IntValue rechargeDelayTicks,
                          ForgeConfigSpec.DoubleValue movementModifier) {
    }
}

package com.miningdim.power;

import com.miningdim.power.machine.AirSeparatingRuntime;
import com.miningdim.power.machine.AirSeparationMode;
import com.miningdim.power.machine.PurifyingProfile;
import com.miningdim.power.machine.PurifyingRuntime;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.EnumMap;
import java.util.Map;

/** 能源加工机器的服务端运行参数，挂入既有 miningdim-power.toml。 */
public final class PowerMachineConfig {

    private static final Map<PurifyingProfile, ForgeConfigSpec.IntValue> PURIFYING_DURATION_TICKS =
            new EnumMap<>(PurifyingProfile.class);
    private static final Map<PurifyingProfile, ForgeConfigSpec.IntValue> PURIFYING_FE_PER_TICK =
            new EnumMap<>(PurifyingProfile.class);
    private static final Map<PurifyingProfile, ForgeConfigSpec.IntValue> PURIFYING_INFUSION_UNITS =
            new EnumMap<>(PurifyingProfile.class);
    private static final Map<PurifyingProfile, ForgeConfigSpec.IntValue> PURIFYING_INFUSION_UNITS_PER_ITEM =
            new EnumMap<>(PurifyingProfile.class);
    private static final Map<AirSeparationMode, ForgeConfigSpec.IntValue> AIR_DURATION_TICKS =
            new EnumMap<>(AirSeparationMode.class);
    private static final Map<AirSeparationMode, ForgeConfigSpec.IntValue> AIR_FE_PER_TICK =
            new EnumMap<>(AirSeparationMode.class);

    private static ForgeConfigSpec.IntValue purifierEnergyCapacity;
    private static ForgeConfigSpec.IntValue infusionCapacity;
    private static ForgeConfigSpec.IntValue airSeparatorEnergyCapacity;

    private PowerMachineConfig() {
    }

    static void define(ForgeConfigSpec.Builder builder) {
        builder.push("purifier");
        purifierEnergyCapacity = builder.defineInRange("energyCapacity", 102_400, 1, 10_000_000);
        infusionCapacity = builder.defineInRange("infusionCapacity", 100, 1, 100_000);
        builder.push("profiles");
        for (PurifyingProfile profile : PurifyingProfile.values()) {
            builder.push(profile.id());
            PURIFYING_DURATION_TICKS.put(profile, builder.defineInRange(
                    "durationTicks", profile.defaultDurationTicks(), 1, 1_000_000));
            PURIFYING_FE_PER_TICK.put(profile, builder.defineInRange(
                    "fePerTick", profile.defaultFePerTick(), 1, 1_000_000));
            PURIFYING_INFUSION_UNITS.put(profile, builder.defineInRange(
                    "infusionUnits", profile.defaultInfusionUnits(), 1, 100_000));
            PURIFYING_INFUSION_UNITS_PER_ITEM.put(profile, builder.defineInRange(
                    "infusionUnitsPerItem", profile.defaultInfusionUnitsPerItem(), 1, 100_000));
            builder.pop();
        }
        builder.pop();
        builder.pop();

        builder.push("airSeparation");
        airSeparatorEnergyCapacity = builder.defineInRange("energyCapacity", 614_400, 1, 10_000_000);
        for (AirSeparationMode mode : AirSeparationMode.values()) {
            builder.push(mode.id());
            AIR_DURATION_TICKS.put(mode, builder.defineInRange(
                    "durationTicks", mode.defaultDurationTicks(), 1, 1_000_000));
            AIR_FE_PER_TICK.put(mode, builder.defineInRange(
                    "fePerTick", mode.defaultFePerTick(), 1, 1_000_000));
            builder.pop();
        }
        builder.pop();
    }

    public static int purifierEnergyCapacity() {
        return requireMinimum("purifier.energyCapacity", purifierEnergyCapacity.get(), maxPurifyingFePerTick());
    }

    public static int infusionCapacity() {
        return requireMinimum("purifier.infusionCapacity", infusionCapacity.get(), maxCompleteInfusionLoad());
    }

    public static int airSeparatorEnergyCapacity() {
        return requireMinimum("airSeparation.energyCapacity", airSeparatorEnergyCapacity.get(),
                maxAirSeparatingFePerTick());
    }

    public static PurifyingRuntime purifying(PurifyingProfile profile) {
        return new PurifyingRuntime(PURIFYING_DURATION_TICKS.get(profile).get(),
                PURIFYING_FE_PER_TICK.get(profile).get(),
                PURIFYING_INFUSION_UNITS.get(profile).get(),
                PURIFYING_INFUSION_UNITS_PER_ITEM.get(profile).get());
    }

    public static AirSeparatingRuntime airSeparating(AirSeparationMode mode) {
        return new AirSeparatingRuntime(AIR_DURATION_TICKS.get(mode).get(), AIR_FE_PER_TICK.get(mode).get());
    }

    private static int maxPurifyingFePerTick() {
        int maximum = 0;
        for (PurifyingProfile profile : PurifyingProfile.values()) {
            int configured = PURIFYING_FE_PER_TICK.get(profile).get();
            if (configured > maximum) {
                maximum = configured;
            }
        }
        return maximum;
    }

    private static int maxCompleteInfusionLoad() {
        long maximum = 0L;
        for (PurifyingProfile profile : PurifyingProfile.values()) {
            long units = PURIFYING_INFUSION_UNITS.get(profile).get();
            long unitsPerItem = PURIFYING_INFUSION_UNITS_PER_ITEM.get(profile).get();
            long numerator = Math.addExact(units, unitsPerItem - 1L);
            long completeLoad = Math.multiplyExact(numerator / unitsPerItem, unitsPerItem);
            if (completeLoad > maximum) {
                maximum = completeLoad;
            }
        }
        return Math.toIntExact(maximum);
    }

    private static int maxAirSeparatingFePerTick() {
        int maximum = 0;
        for (AirSeparationMode mode : AirSeparationMode.values()) {
            int configured = AIR_FE_PER_TICK.get(mode).get();
            if (configured > maximum) {
                maximum = configured;
            }
        }
        return maximum;
    }

    private static int requireMinimum(String field, int actual, int required) {
        if (actual < required) {
            throw new IllegalStateException("incompatible power machine config " + field
                    + ": actual=" + actual + ", required=" + required);
        }
        return actual;
    }
}

package com.miningdim.power;

import com.miningdim.power.storage.PowerCellSpec;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 三级储电的服务器配置。分节由 {@link PowerGeneratorConfig} 的 static 块统一并入同一个 SPEC，
 * 与 {@link PowerMachineConfig} 同范式：本类不持有 SPEC，也不自行 registerConfig。
 */
public final class PowerStorageConfig {

    private static ForgeConfigSpec.IntValue industrialCapacity;
    private static ForgeConfigSpec.IntValue industrialTransfer;
    private static ForgeConfigSpec.IntValue modernCapacity;
    private static ForgeConfigSpec.IntValue modernTransfer;
    private static ForgeConfigSpec.IntValue futureCapacity;
    private static ForgeConfigSpec.IntValue futureTransfer;

    private PowerStorageConfig() {
    }

    static void define(ForgeConfigSpec.Builder builder) {
        builder.comment("Energy storage tiers. Capacity is capped below Integer.MAX_VALUE on purpose:",
                        "Forge's IEnergyStorage is int-based, so keeping the configurable ceiling inside the",
                        "int safe range means the exposed capability never needs a lossy truncation.")
                .push("storage");
        builder.push("industrial");
        industrialCapacity = capacity(builder, PowerCellSpec.INDUSTRIAL);
        industrialTransfer = transfer(builder, PowerCellSpec.INDUSTRIAL);
        builder.pop();
        builder.push("modern");
        modernCapacity = capacity(builder, PowerCellSpec.MODERN);
        modernTransfer = transfer(builder, PowerCellSpec.MODERN);
        builder.pop();
        builder.push("future");
        futureCapacity = capacity(builder, PowerCellSpec.FUTURE);
        futureTransfer = transfer(builder, PowerCellSpec.FUTURE);
        builder.pop();
        builder.pop();
    }

    private static ForgeConfigSpec.IntValue capacity(ForgeConfigSpec.Builder builder, PowerCellSpec spec) {
        return builder.defineInRange("capacityFe", spec.defaults().capacityFe(), 1,
                PowerCellSpec.MAX_CONFIGURABLE_CAPACITY);
    }

    private static ForgeConfigSpec.IntValue transfer(ForgeConfigSpec.Builder builder, PowerCellSpec spec) {
        return builder.defineInRange("transferFePerTick", spec.defaults().transferFePerTick(), 1, 10_000_000);
    }

    public static PowerCellSpec.Runtime profile(PowerCellSpec spec) {
        return switch (spec) {
            case INDUSTRIAL -> new PowerCellSpec.Runtime(industrialCapacity.get(), industrialTransfer.get());
            case MODERN -> new PowerCellSpec.Runtime(modernCapacity.get(), modernTransfer.get());
            case FUTURE -> new PowerCellSpec.Runtime(futureCapacity.get(), futureTransfer.get());
        };
    }
}

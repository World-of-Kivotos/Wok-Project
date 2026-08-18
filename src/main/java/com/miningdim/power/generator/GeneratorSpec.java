package com.miningdim.power.generator;

import com.miningdim.power.PowerGeneratorConfig;
import com.miningdim.power.GeneratorMultiblockBlock;
import com.miningdim.power.grid.VoltageClass;
import net.minecraft.world.level.block.Block;

/** 三档发电机不可配置的身份与可配置运行数据的唯一入口。 */
public enum GeneratorSpec {
    LOW("low", VoltageClass.LOW, 192, 600, 200.0D, 0.25D, 0.10D, 4, 64, 8, 0.25D),
    MEDIUM("medium", VoltageClass.MEDIUM, 1_152, 900, 260.0D, 0.50D, 0.15D, 8, 192, 24, 0.40D),
    HIGH("high", VoltageClass.HIGH, 3_072, 1_200, 320.0D, 1.00D, 0.20D, 24, 512, 64, 0.60D);

    private final String id;
    private final VoltageClass sourceVoltage;
    private final Runtime defaults;

    GeneratorSpec(String id, VoltageClass sourceVoltage, int peakFePerTick, int coreDurability,
                  double meltdownTemperatureC, double maxRejectedTemperatureRiseCPerTick,
                  double lowLoadCoolingCPerTick, int scatterRadius, int maxDestructibleBlocks,
                  int maxFirePoints, double centerDamageFraction) {
        this.id = id;
        this.sourceVoltage = sourceVoltage;
        this.defaults = new Runtime(peakFePerTick, coreDurability, meltdownTemperatureC,
                maxRejectedTemperatureRiseCPerTick, lowLoadCoolingCPerTick, scatterRadius,
                maxDestructibleBlocks, maxFirePoints, centerDamageFraction);
    }

    public String id() {
        return id;
    }

    public VoltageClass sourceVoltage() {
        return sourceVoltage;
    }

    public Runtime defaults() {
        return defaults;
    }

    public Runtime runtime() {
        return PowerGeneratorConfig.profile(this);
    }

    public static GeneratorSpec forBlock(Block block) {
        if (block instanceof GeneratorMultiblockBlock generator) {
            return generator.spec();
        }
        throw new IllegalArgumentException("block is not a registered generator: " + block);
    }

    public static GeneratorSpec byId(String id) {
        for (GeneratorSpec spec : values()) {
            if (spec.id.equals(id)) {
                return spec;
            }
        }
        throw new IllegalArgumentException("unknown generator spec id: " + id);
    }

    /** 运行档位；容量永远由峰值乘固定 200 tick 派生，杜绝单独扩容。 */
    public record Runtime(
            int peakFePerTick,
            int coreDurability,
            double meltdownTemperatureC,
            double maxRejectedTemperatureRiseCPerTick,
            double lowLoadCoolingCPerTick,
            int scatterRadius,
            int maxDestructibleBlocks,
            int maxFirePoints,
            double centerDamageFraction
    ) {
        public Runtime {
            if (peakFePerTick <= 0 || coreDurability <= 0 || meltdownTemperatureC <= 0.0D
                    || maxRejectedTemperatureRiseCPerTick < 0.0D || lowLoadCoolingCPerTick < 0.0D
                    || scatterRadius <= 0 || maxDestructibleBlocks <= 0 || maxFirePoints <= 0
                    || centerDamageFraction <= 0.0D || centerDamageFraction > 1.0D) {
                throw new IllegalArgumentException("invalid generator runtime profile");
            }
        }

        public int bufferCapacityFe() {
            return Math.multiplyExact(peakFePerTick, 200);
        }

        public int coreDurationTicks() {
            return Math.multiplyExact(coreDurability, 20);
        }
    }
}

package com.miningdim.power.generator;

import com.miningdim.power.PowerGeneratorConfig;
import com.miningdim.power.GeneratorMultiblockBlock;
import com.miningdim.power.grid.VoltageClass;
import net.minecraft.world.level.block.Block;

/** 三档发电机不可配置的身份与可配置运行数据的唯一入口。 */
public enum GeneratorSpec {
    LOW("low", VoltageClass.LOW, 192, 3_600, 200.0D, 0.25D, 0.10D, 4, 64, 8, 0.25D),
    MEDIUM("medium", VoltageClass.MEDIUM, 1_152, 7_200, 260.0D, 0.50D, 0.15D, 8, 192, 24, 0.40D),
    HIGH("high", VoltageClass.HIGH, 3_072, 14_400, 320.0D, 1.00D, 0.20D, 24, 512, 64, 0.60D);

    /**
     * 输出裕度默认 1.25 倍。输出上限一旦等于峰值产能, 满载运行时缓冲净流出恒为零: 玩家改一次线导致电网
     * 短暂断流后积压的 FE 永远排不掉, 只能持续满额拒收升温直到熔毁。1.25 倍让装满的缓冲(200 tick 产量)
     * 在约 800 tick 内自行排空, 又不至于大到让缓冲形同虚设。
     */
    public static final double DEFAULT_OUTPUT_MARGIN_MULTIPLIER = 1.25D;

    private final String id;
    private final VoltageClass sourceVoltage;
    private final Runtime defaults;

    GeneratorSpec(String id, VoltageClass sourceVoltage, int peakFePerTick, int coreDurability,
                  double meltdownTemperatureC, double maxRejectedTemperatureRiseCPerTick,
                  double lowLoadCoolingCPerTick, int scatterRadius, int maxDestructibleBlocks,
                  int maxFirePoints, double centerDamageFraction) {
        this.id = id;
        this.sourceVoltage = sourceVoltage;
        this.defaults = new Runtime(peakFePerTick, DEFAULT_OUTPUT_MARGIN_MULTIPLIER, coreDurability,
                meltdownTemperatureC, maxRejectedTemperatureRiseCPerTick, lowLoadCoolingCPerTick,
                scatterRadius, maxDestructibleBlocks, maxFirePoints, centerDamageFraction);
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
            double outputMarginMultiplier,
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
            if (peakFePerTick <= 0 || outputMarginMultiplier < 1.0D
                    || coreDurability <= 0 || meltdownTemperatureC <= 0.0D
                    || maxRejectedTemperatureRiseCPerTick < 0.0D || lowLoadCoolingCPerTick < 0.0D
                    || scatterRadius <= 0 || maxDestructibleBlocks <= 0 || maxFirePoints <= 0
                    || centerDamageFraction <= 0.0D || centerDamageFraction > 1.0D) {
                throw new IllegalArgumentException("invalid generator runtime profile");
            }
        }

        public int bufferCapacityFe() {
            return Math.multiplyExact(peakFePerTick, 200);
        }

        /** 对外输出上限。高出峰值的那部分是缓冲的唯一排空手段, 产电侧绝不共用该上限, 否则等于上调整机功率。 */
        public int outputCapFePerTick() {
            return Math.toIntExact((long) Math.floor(peakFePerTick * outputMarginMultiplier));
        }

        public int coreDurationTicks() {
            return Math.multiplyExact(coreDurability, 20);
        }
    }
}

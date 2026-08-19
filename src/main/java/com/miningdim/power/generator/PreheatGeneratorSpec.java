package com.miningdim.power.generator;

import com.miningdim.power.PowerGeneratorConfig;
import com.miningdim.power.grid.CableThermics;
import com.miningdim.power.grid.VoltageClass;

/**
 * 两台前期预热式发电机的身份与可配置运行数据入口，与三档燃料芯发电机的 {@link GeneratorSpec} 平行。
 *
 * 与燃料芯发电机的根本差别是温度语义：燃料芯机插芯即满功率，温度只在拒收时上升并通向熔毁；预热式机器
 * 的温度反过来决定功率，冷机产出为零，烧到工作温度才满载。两者共用 {@link CableThermics#AMBIENT_C}
 * 作为环境温度基准，不另立常量。
 *
 * 前期机不设熔毁：镍铬保险丝要镍锭加铬锭，玩家造得出煤炭发电机时通常还拿不到保护件，让入门机在无保护
 * 件可用的阶段炸掉基地是纯劝退。过热行为只到"停止升温"为止。
 */
public enum PreheatGeneratorSpec {
    COAL("coal", FuelSource.BURNABLE_ITEM, 48, 300.0D, 1_200, 0.15D, 4),
    GEOTHERMAL("geothermal", FuelSource.LAVA_SOURCE_BELOW, 144, 600.0D, 2_400, 0.20D, 1);

    private final String id;
    private final FuelSource fuelSource;
    private final Runtime defaults;

    PreheatGeneratorSpec(String id, FuelSource fuelSource, int peakFePerTick, double workingTemperatureC,
                         int preheatTicks, double coolingCPerTick, int fuelBurnMultiplier) {
        this.id = id;
        this.fuelSource = fuelSource;
        this.defaults = new Runtime(peakFePerTick, workingTemperatureC, preheatTicks, coolingCPerTick,
                fuelBurnMultiplier);
    }

    public String id() {
        return id;
    }

    public FuelSource fuelSource() {
        return fuelSource;
    }

    /** 前期两台一律 LOW 段电源，铁线缆即可承载；这是身份而非配置。 */
    public VoltageClass sourceVoltage() {
        return VoltageClass.LOW;
    }

    public Runtime defaults() {
        return defaults;
    }

    public Runtime runtime() {
        return PowerGeneratorConfig.preheatProfile(this);
    }

    public static PreheatGeneratorSpec byId(String id) {
        for (PreheatGeneratorSpec spec : values()) {
            if (spec.id.equals(id)) {
                return spec;
            }
        }
        throw new IllegalArgumentException("unknown preheat generator spec id: " + id);
    }

    /** 供能来源。煤炭机吃任何可燃物品，地热机认脚下的岩浆源方块。 */
    public enum FuelSource {
        BURNABLE_ITEM,
        LAVA_SOURCE_BELOW
    }

    /**
     * 运行档位。缓冲沿用燃料芯发电机的"峰值乘 200 tick"公式；升温速率由工作温度与预热时长派生，
     * 不单列配置项——两者都可配会立刻产生"预热时长与升温速率互相打架"的二义状态。
     */
    public record Runtime(
            int peakFePerTick,
            double workingTemperatureC,
            int preheatTicks,
            double coolingCPerTick,
            int fuelBurnMultiplier
    ) {
        public Runtime {
            if (peakFePerTick <= 0 || workingTemperatureC <= CableThermics.AMBIENT_C || preheatTicks <= 0
                    || coolingCPerTick < 0.0D || fuelBurnMultiplier <= 0) {
                throw new IllegalArgumentException("invalid preheat generator runtime profile");
            }
        }

        public int bufferCapacityFe() {
            return Math.multiplyExact(peakFePerTick, 200);
        }

        /** 冷机烧到工作温度所需的每 tick 升温量。 */
        public double heatupCPerTick() {
            return (workingTemperatureC - CableThermics.AMBIENT_C) / preheatTicks;
        }

        /**
         * 当前温度对应的实际输出。冷机为 0，到达工作温度即满载，中间线性。
         * 这就是"岩浆温度更高所以发电更快"的落地：地热工作温度是煤炭的两倍，峰值也更高。
         */
        public int outputAt(double temperatureC) {
            double span = workingTemperatureC - CableThermics.AMBIENT_C;
            double ratio = (temperatureC - CableThermics.AMBIENT_C) / span;
            if (ratio <= 0.0D) {
                return 0;
            }
            if (ratio >= 1.0D) {
                return peakFePerTick;
            }
            // 取整用 round 而非 floor: 温度是逐 tick 累加出来的, 1200 次累加后 160.0 实际会落在
            // 159.999999 上, floor 会让玩家看到"温度到一半、输出却少 1"的抖动读数。
            return (int) Math.round(peakFePerTick * ratio);
        }
    }
}

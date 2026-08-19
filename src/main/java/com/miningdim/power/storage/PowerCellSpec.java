package com.miningdim.power.storage;

import com.miningdim.power.PowerStorageConfig;
import com.miningdim.power.grid.VoltageClass;

/**
 * 三级储电的身份与可配置运行数据入口。
 *
 * 容量锚点：每级储电 = 对应档发电机一个燃料芯的总产量，物理意义是"插一芯正好把配套储电充满"。
 * 三级 884,736,000 FE 可供 L10 满配弹药产线连续运转约七小时，玩家离线一晚回来不亏产量——这才是
 * 储电存在的理由，而不是再加一个方块。
 *
 * 容量上限刻意压在 2,000,000,000 以内：Forge 的 {@code IEnergyStorage} 全套接口是 int
 * （上限 2,147,483,647），把可配置上限卡在 int 安全区内，对外暴露 capability 时就永远不需要
 * 有损截断。跨储电求和仍必须走 long，见 {@link PowerCellBlockEntity}。
 */
public enum PowerCellSpec {
    INDUSTRIAL("industrial", 13_824_000, 768),
    MODERN("modern", 165_888_000, 4_608),
    FUTURE("future", 884_736_000, 12_288);

    /** 可配置容量的硬上限，留出 int 余量以杜绝 capability 截断。 */
    public static final int MAX_CONFIGURABLE_CAPACITY = 2_000_000_000;

    private final String id;
    private final Runtime defaults;

    PowerCellSpec(String id, int capacityFe, int transferFePerTick) {
        this.id = id;
        this.defaults = new Runtime(capacityFe, transferFePerTick);
    }

    public String id() {
        return id;
    }

    /** 储电不改变电压等级，只做缓冲；耐压由接入的线缆决定。 */
    public VoltageClass voltageClass() {
        return VoltageClass.LOW;
    }

    public Runtime defaults() {
        return defaults;
    }

    public Runtime runtime() {
        return PowerStorageConfig.profile(this);
    }

    public static PowerCellSpec byId(String id) {
        for (PowerCellSpec spec : values()) {
            if (spec.id.equals(id)) {
                return spec;
            }
        }
        throw new IllegalArgumentException("unknown power cell spec id: " + id);
    }

    /**
     * 运行档位。传输速率取对应档发电机峰值的四倍，即一台储电可以同时吃下四台同档发电机的满载输出，
     * 也就不会出现"储电成为电网瓶颈"这种反直觉状况。
     */
    public record Runtime(int capacityFe, int transferFePerTick) {
        public Runtime {
            if (capacityFe <= 0 || capacityFe > MAX_CONFIGURABLE_CAPACITY || transferFePerTick <= 0) {
                throw new IllegalArgumentException("invalid power cell runtime profile");
            }
        }
    }
}

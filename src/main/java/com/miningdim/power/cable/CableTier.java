package com.miningdim.power.cable;

/**
 * 有线 FE 线缆的五级吞吐阶梯 (前中期主力传输层, 毕业到 Flux Networks 无线终局)。
 *
 * 每级是一个独立注册的方块 (范式同 {@code PowerRegistry.registerGenerator} 的三发电机: 五块五配方五掉落),
 * 本枚举只作参数化: 决定该级线缆的每 settlement 吞吐帽与瞬态缓冲, 不作为 blockstate 属性。
 *
 * 数值为占位 (x4 等比: 256..65536 FE/t), 最终须与自研发电机实际出力 + 经济总表对齐后定稿, 不是终值。
 * 抗掉刻不靠"少 tick", 靠架构: 线缆本身零 ticker, 由 EnergyNetworkManager 每 settlement 对每张网做一次
 * O(端点数) 的批量结算; 吞吐帽随传输量走, 与铺了多少根线缆无关。
 */
public enum CableTier {

    BASIC("basic", 256),
    IMPROVED("improved", 1_024),
    ADVANCED("advanced", 4_096),
    ELITE("elite", 16_384),
    ULTIMATE("ultimate", 65_536);

    private final String id;
    private final int transferCapFePerTick;

    CableTier(String id, int transferCapFePerTick) {
        this.id = id;
        this.transferCapFePerTick = transferCapFePerTick;
    }

    public String id() {
        return id;
    }

    /** 注册 id / 方块名, 与 loot/blockstate/lang 资源键一致 (如 basic_energy_cable)。 */
    public String blockId() {
        return id + "_energy_cable";
    }

    /** 单张网单次 settlement 可流过的 FE 上限。混级网取网内最低级此值 (木桶效应, 仅重建时算一次)。 */
    public int transferCapFePerTick() {
        return transferCapFePerTick;
    }

    /**
     * 瞬态导体缓冲容量 = 一次 settlement 吞吐。线缆不是电池, 该缓冲只为"推入(发电机 receiveEnergy)"与
     * "拉出(用电端 extractEnergy)"双向汇聚提供一次结算的过渡空间, 每 settlement 由 manager 抽空再分配。
     */
    public int transientBufferCap() {
        return transferCapFePerTick;
    }
}

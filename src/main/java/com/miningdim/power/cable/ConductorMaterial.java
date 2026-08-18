package com.miningdim.power.cable;

import com.miningdim.power.grid.VoltageClass;

/**
 * 12 级导体阶梯的数据表 —— 设计文档第四/十章的单一真源。加一级 = 加一行; 网络引擎与热学层只读本表取
 * 额定容量 / 降效 floor / 绝缘档, 不自持任何材料常量。
 *
 * 真实背书 (CRC/Wikipedia, 见设计文档第四章):
 *  - 额定容量按真实电导率排序: 银 > 铜各牌号 > 金 > 铝 > 铁 (电阻率 ×10^-8 Ω·m: 银1.587<铜1.678<金2.214<铝2.65<铁9.61);
 *    石墨烯/超导为合成料, 容量在顶。金的反差: 导电不如铜, 但温度系数最低 (α=0.0034) => 最耐热稳定 => floor 最高。
 *  - 降效 floor 按真实温度系数 (低系数=耐热=floor 高): 铁 α=0.0065 最先崩 (floor 最低), 金 α=0.0034 最稳 (floor 最高);
 *    石墨烯负温度系数 (越热电阻越低) => floor 接近 1; 超导临界温下近零损耗 => floor = 1。
 *
 * 数值定标: ratedCapacityFe 与 degradeFloor 已按能源总表定标；吞吐是隐性 faucet，过载损耗是隐性 sink，修改时
 * 须同步复核 docs/Economy_BalanceSheet_DesignSpec.md。
 *
 * P1-P3 已注册完整十二档导体；石墨烯及两种超导导体的特殊热学由 P3 网络缓存结算。
 */
public enum ConductorMaterial implements CableProfile {

    //                    id                      色值       额定FE  floor 绝缘                     耐压                    热学模式       线阻  可raw搓
    IRON(                "iron",                  0xA7A7A7,    256, 0.35, InsulationGrade.PVC,      VoltageClass.LOW,     ThermalMode.STANDARD, 961, true),
    ALUMINUM(            "aluminum",              0xD6DEE5,    768, 0.42, InsulationGrade.PVC,      VoltageClass.LOW,     ThermalMode.STANDARD, 265, true),
    COPPER(              "copper",                0xC46B3C,   1280, 0.45, InsulationGrade.PE,       VoltageClass.LOW,     ThermalMode.STANDARD, 168, true),
    TINNED_COPPER(       "tinned_copper",         0xC1C9CF,   1536, 0.45, InsulationGrade.PE,       VoltageClass.MEDIUM,  ThermalMode.STANDARD, 168, false),
    OFC_COPPER(          "ofc_copper",            0xD47B45,   2048, 0.46, InsulationGrade.EPR,      VoltageClass.MEDIUM,  ThermalMode.STANDARD, 166, false),
    OFE_COPPER(          "ofe_copper",            0xE08A50,   3072, 0.46, InsulationGrade.XLPE,     VoltageClass.MEDIUM,  ThermalMode.STANDARD, 165, false),
    SILVER_PLATED_COPPER("silver_plated_copper",  0xD8E0E8,   4096, 0.47, InsulationGrade.XLPE,     VoltageClass.HIGH,    ThermalMode.STANDARD, 160, false),
    GOLD(                "gold",                  0xE6B84A,   2560, 0.50, InsulationGrade.XLPE,     VoltageClass.HIGH,    ThermalMode.STANDARD, 221, false),
    SILVER(              "silver",                0xC7D1D8,   5120, 0.46, InsulationGrade.SILICONE, VoltageClass.HIGH,    ThermalMode.STANDARD, 159, false),
    GRAPHENE(            "graphene",              0x4A4A50,   8192, 1.00, InsulationGrade.SILICONE, VoltageClass.EXTREME, ThermalMode.GRAPHENE, 80, false),
    NBTI_SUPERCONDUCTOR( "nbti_superconductor",   0x8A9CAF,  16384, 1.00, InsulationGrade.SILICONE, VoltageClass.EXTREME, ThermalMode.NBTI, 168, false),
    YBCO_SUPERCONDUCTOR( "ybco_superconductor",   0x6E7E9C,  32768, 1.00, InsulationGrade.SILICONE, VoltageClass.EXTREME, ThermalMode.YBCO, 1, false);

    private final String id;
    private final int tintColor;
    private final int ratedCapacityFe;
    private final double degradeFloor;
    private final InsulationGrade insulation;
    private final VoltageClass voltageClass;
    private final ThermalMode thermalMode;
    private final int baseLineResistanceUnits;
    private final boolean craftableRaw;

    ConductorMaterial(String id, int tintColor, int ratedCapacityFe, double degradeFloor,
                      InsulationGrade insulation, VoltageClass voltageClass, ThermalMode thermalMode,
                      int baseLineResistanceUnits, boolean craftableRaw) {
        this.id = id;
        this.tintColor = tintColor;
        this.ratedCapacityFe = ratedCapacityFe;
        this.degradeFloor = degradeFloor;
        this.insulation = insulation;
        this.voltageClass = voltageClass;
        this.thermalMode = thermalMode;
        this.baseLineResistanceUnits = baseLineResistanceUnits;
        this.craftableRaw = craftableRaw;
    }

    public String id() {
        return id;
    }

    /** 灰度导线基底的客户端 tint；与物理网络剖面无关，故不进入 {@link CableProfile}。 */
    public int tintColor() {
        return tintColor;
    }

    /** 注册 id / 资源键, 与 blockstate/model/loot/lang 一致 (如 iron_energy_cable)。 */
    public String blockId() {
        return id + "_energy_cable";
    }

    /** 额定每 settlement 吞吐帽 R (FE)。混级网取网内最低此值（木桶效应）。 */
    public int ratedCapacityFe() {
        return ratedCapacityFe;
    }

    /** 瞬态导体缓冲容量 = 一次额定吞吐 (线缆不是电池, 仅双向汇聚的一次结算过渡空间)。 */
    public int transientBufferCap() {
        return ratedCapacityFe;
    }

    /** 过热降效地板 eff∈[floor,1] 的下界 (由真实温度系数: 低系数=耐热=floor 高)。混级网取最低 (最弱一段主导)。 */
    public double degradeFloor() {
        return degradeFloor;
    }

    /** 绝缘等级 = 耐温档。混级网取最低 (最弱绝缘主导整条网的降效起始点)。 */
    public InsulationGrade insulation() {
        return insulation;
    }

    @Override
    public ThermalMode thermalMode() {
        return thermalMode;
    }

    @Override
    public int baseLineResistanceUnits() {
        return baseLineResistanceUnits;
    }

    /** 线缆可承受的最高输出电压，混级网取最低等级。 */
    public VoltageClass voltageClass() {
        return voltageClass;
    }

    /** 是否可 raw 合成 (无需提纯即可搓, bootstrap 用): 铁/铝/铜。false 者须经提纯/镀层/合成。 */
    public boolean craftableRaw() {
        return craftableRaw;
    }
}

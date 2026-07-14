package com.miningdim.power.cable;

/**
 * 绝缘等级 = 耐温档 (接设计文档第三章热学)。耐温由低到高 (已核实真实材料): PVC < PE < EPR < XLPE < 硅橡胶。
 *
 * 绝缘与导体纯度双轴各司其职: 导体纯度定容量, 绝缘定耐热。绝缘决定"网温升到多高才开始/加剧降效" ——
 * 低档 PVC 一过载即软化降效, 高档硅橡胶扛更高温才降效 (见 {@link CableThermics#efficiency})。
 *
 * maxContinuousTempC 为占位 (PENDING, 落码前过经济总表), 结构真实即可。
 */
public enum InsulationGrade {

    PVC("pvc", 70),
    PE("pe", 80),
    EPR("epr", 105),
    XLPE("xlpe", 120),
    SILICONE("silicone", 180);

    private final String id;
    private final int maxContinuousTempC;

    InsulationGrade(String id, int maxContinuousTempC) {
        this.id = id;
        this.maxContinuousTempC = maxContinuousTempC;
    }

    public String id() {
        return id;
    }

    /** 允许的最高持续温度 (°C); 网温接近此值降效加剧, 达到则钳在导体的降效 floor。占位数值, 待经济总表标定。 */
    public int maxContinuousTempC() {
        return maxContinuousTempC;
    }
}

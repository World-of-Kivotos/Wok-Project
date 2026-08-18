package com.miningdim.power.cable;

import com.miningdim.power.grid.VoltageClass;

/**
 * 线缆网络所需的稳定物理契约。十二级导体和后续独立特殊线缆均通过本接口进入同一网络引擎。
 */
public interface CableProfile {

    String id();

    String blockId();

    int ratedCapacityFe();

    int transientBufferCap();

    double degradeFloor();

    InsulationGrade insulation();

    /**
     * 允许特殊线缆在不伪造绝缘档位的情况下给出自身连续耐温上限。
     */
    default int maxContinuousTemperatureC() {
        return insulation().maxContinuousTempC();
    }

    ThermalMode thermalMode();

    /**
     * 用于 P3 拓扑缓存的每段常态线路电阻单位；单位与距离损耗比例尺配套，非现实欧姆值。
     */
    int baseLineResistanceUnits();

    VoltageClass voltageClass();

    enum ThermalMode {
        STANDARD,
        GRAPHENE,
        NBTI,
        YBCO
    }
}

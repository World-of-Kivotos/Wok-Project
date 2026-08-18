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

    VoltageClass voltageClass();
}

package com.miningdim.power.grid;

import java.util.Set;

/**
 * 能源网的只读观测值。快照复制故障集合，调用方不能修改运行时网络状态。
 */
public record EnergyNetworkSnapshot(
        int ratedCapacityFe,
        int effectiveCapacityFe,
        int bufferCapacityFe,
        int storedFe,
        double temperatureC,
        int lastLoadFe,
        double loadRatio,
        int lastBufferOverflowLossFe,
        long totalBufferOverflowLossFe,
        int lastDistanceLossFe,
        long totalDistanceLossFe,
        VoltageClass voltageLimit,
        Set<EnergyNetworkFault> faults,
        CoolingState coolingState
) {
    public EnergyNetworkSnapshot {
        faults = Set.copyOf(faults);
    }

    public enum CoolingState {
        NOT_REQUIRED,
        ACTIVE,
        INSUFFICIENT
    }
}

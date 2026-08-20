package com.miningdim.power.grid;

/**
 * 网状态中可被 Jade 和诊断日志读取的故障标记。
 */
public enum EnergyNetworkFault {
    NONE,
    OVER_VOLTAGE,
    /** 缓冲临时超额 (合网后 stored 高于木桶容量)。它表示"还没送完", 不表示"已经丢电", 消化完会自行摘除。 */
    BUFFER_OVERFLOW,
    SUPERCONDUCTOR_QUENCH
}

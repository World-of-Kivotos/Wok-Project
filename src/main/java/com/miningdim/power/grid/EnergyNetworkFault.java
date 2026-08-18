package com.miningdim.power.grid;

/**
 * 网状态中可被 Jade 和诊断日志读取的故障标记。
 */
public enum EnergyNetworkFault {
    NONE,
    OVER_VOLTAGE,
    BUFFER_OVERFLOW
}

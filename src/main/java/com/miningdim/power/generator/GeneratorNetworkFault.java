package com.miningdim.power.generator;

/** 控制器记录的最近一次电网故障，不与本机缓冲拒收混用。 */
public enum GeneratorNetworkFault {
    NONE,
    OVER_VOLTAGE
}

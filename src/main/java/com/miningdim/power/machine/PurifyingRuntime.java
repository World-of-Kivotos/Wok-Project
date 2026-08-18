package com.miningdim.power.machine;

/** 提纯工序的服务器权威运行参数快照。 */
public record PurifyingRuntime(int durationTicks, int fePerTick, int infusionUnits, int infusionUnitsPerItem) {
}

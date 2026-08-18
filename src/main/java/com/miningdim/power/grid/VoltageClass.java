package com.miningdim.power.grid;

/**
 * 线缆和自研发电端的耐压等级。枚举顺序即由低到高的比较顺序。
 */
public enum VoltageClass {
    LOW,
    MEDIUM,
    HIGH,
    EXTREME;

    public boolean isHigherThan(VoltageClass other) {
        return ordinal() > other.ordinal();
    }
}

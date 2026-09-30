package com.miningdim.district.core;

/** 地块状态, 由户主与冻结两个字段推出 (冻结优先)。wire 值与契约一致。 */
public enum PlotStatus {
    OWNED("owned"),
    VACANT("vacant"),
    FROZEN("frozen");

    private final String wire;

    PlotStatus(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}

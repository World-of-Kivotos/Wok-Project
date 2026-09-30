package com.miningdim.district.core;

import org.jetbrains.annotations.Nullable;

/** 地块开关的三列, 顺序即"恢复默认"时同一项内逐列写记录的顺序。三列从不继承自管区。 */
public enum PlotAudience {
    FRIEND("friend"),
    RESIDENT("resident"),
    OUTSIDER("outsider");

    private final String wire;

    PlotAudience(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    @Nullable
    public static PlotAudience fromWire(@Nullable String raw) {
        for (PlotAudience value : values()) {
            if (value.wire.equals(raw)) {
                return value;
            }
        }
        return null;
    }
}

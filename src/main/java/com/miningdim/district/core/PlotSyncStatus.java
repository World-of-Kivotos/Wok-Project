package com.miningdim.district.core;

import org.jetbrains.annotations.Nullable;

/** 地块的领地权限生效状态: 最近一次整块重写的成败。 */
public enum PlotSyncStatus {
    SYNCED("synced"),
    FAILED("failed");

    private final String wire;

    PlotSyncStatus(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    @Nullable
    public static PlotSyncStatus fromWire(@Nullable String raw) {
        for (PlotSyncStatus value : values()) {
            if (value.wire.equals(raw)) {
                return value;
            }
        }
        return null;
    }
}

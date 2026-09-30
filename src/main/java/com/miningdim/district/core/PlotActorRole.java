package com.miningdim.district.core;

import org.jetbrains.annotations.Nullable;

/** 地块记录里操作人的身份, 与契约枚举逐值相同。 */
public enum PlotActorRole {
    OWNER("owner"),
    ADMIN("admin"),
    WARDEN("warden"),
    SYSTEM("system");

    private final String wire;

    PlotActorRole(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    @Nullable
    public static PlotActorRole fromWire(@Nullable String raw) {
        for (PlotActorRole value : values()) {
            if (value.wire.equals(raw)) {
                return value;
            }
        }
        return null;
    }
}

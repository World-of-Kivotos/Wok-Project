package com.miningdim.district.core;

import org.jetbrains.annotations.Nullable;

/**
 * 公共区域开关表的列: member 项分住户、外人两列; region 项 (区域规则) 只有全区一列。
 */
public enum DistrictAudience {
    RESIDENT("resident"),
    OUTSIDER("outsider"),
    DISTRICT("district");

    private final String wire;

    DistrictAudience(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** 这一列是否属于某种 scope 的项。 */
    public boolean appliesTo(PermissionScope scope) {
        return scope == PermissionScope.REGION ? this == DISTRICT : this != DISTRICT;
    }

    @Nullable
    public static DistrictAudience fromWire(@Nullable String raw) {
        for (DistrictAudience value : values()) {
            if (value.wire.equals(raw)) {
                return value;
            }
        }
        return null;
    }
}

package com.miningdim.district.core;

import org.jetbrains.annotations.Nullable;

/** 本区记录里操作人的身份, 与契约枚举逐值相同。住户身份只出现在买地; system 只出现在冻结期满自动收回。 */
public enum DistrictActorRole {
    ADMIN("admin"),
    WARDEN("warden"),
    RESIDENT("resident"),
    SYSTEM("system");

    private final String wire;

    DistrictActorRole(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    @Nullable
    public static DistrictActorRole fromWire(@Nullable String raw) {
        for (DistrictActorRole value : values()) {
            if (value.wire.equals(raw)) {
                return value;
            }
        }
        return null;
    }
}

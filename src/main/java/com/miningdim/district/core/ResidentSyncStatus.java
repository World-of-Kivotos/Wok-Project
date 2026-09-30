package com.miningdim.district.core;

import org.jetbrains.annotations.Nullable;

/**
 * 住户 (名单行) 的领地权限生效状态 (设计文档第九章): 最近一次成员资格写入的成败; 从没进过服的人是 pending, 不写 Flan。
 * wire 值即库里存的值与契约值。
 */
public enum ResidentSyncStatus {
    SYNCED("synced"),
    PENDING("pending"),
    FAILED("failed");

    private final String wire;

    ResidentSyncStatus(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    @Nullable
    public static ResidentSyncStatus fromWire(@Nullable String raw) {
        for (ResidentSyncStatus value : values()) {
            if (value.wire.equals(raw)) {
                return value;
            }
        }
        return null;
    }
}

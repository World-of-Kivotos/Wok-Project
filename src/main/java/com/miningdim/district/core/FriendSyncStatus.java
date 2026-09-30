package com.miningdim.district.core;

import org.jetbrains.annotations.Nullable;

/**
 * 地块朋友的生效状态: 从没进过服的人是 pending (首次登录后才进朋友组)。写入成败记在地块上, 不记在朋友上, 所以
 * 契约类型里的 failed 服务端不产生 (设计文档 A9)。
 */
public enum FriendSyncStatus {
    SYNCED("synced"),
    PENDING("pending");

    private final String wire;

    FriendSyncStatus(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    @Nullable
    public static FriendSyncStatus fromWire(@Nullable String raw) {
        for (FriendSyncStatus value : values()) {
            if (value.wire.equals(raw)) {
                return value;
            }
        }
        return null;
    }
}

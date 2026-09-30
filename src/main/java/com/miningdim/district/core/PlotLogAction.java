package com.miningdim.district.core;

import org.jetbrains.annotations.Nullable;

/** 地块记录的动作, 与契约枚举逐值相同, 库上另有 CHECK。 */
public enum PlotLogAction {
    CREATE("create"),
    RESIZE("resize"),
    PURCHASE("purchase"),
    ADD_FRIEND("addFriend"),
    REMOVE_FRIEND("removeFriend"),
    SUSPEND_FRIEND("suspendFriend"),
    RESTORE_FRIEND("restoreFriend"),
    PERMISSION("permission"),
    FREEZE("freeze"),
    UNFREEZE("unfreeze"),
    VACATE("vacate");

    private final String wire;

    PlotLogAction(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    @Nullable
    public static PlotLogAction fromWire(@Nullable String raw) {
        for (PlotLogAction value : values()) {
            if (value.wire.equals(raw)) {
                return value;
            }
        }
        return null;
    }
}

package com.miningdim.district.core;

import org.jetbrains.annotations.Nullable;

/**
 * 本区记录的动作, 与契约枚举逐值相同 (前端的标签表按这些值索引, 出现枚举外的值会显示成空白), 库上另有 CHECK。
 * 刻意没有"解绑""首次登录补写"这类动作: 契约没有, 前端也没有标签。
 */
public enum DistrictLogAction {
    ADD("add"),
    REMOVE("remove"),
    APPOINT("appoint"),
    REVOKE("revoke"),
    RESYNC("resync"),
    PERMISSION("permission"),
    CREATE_PLOT("createPlot"),
    RESIZE_PLOT("resizePlot"),
    DELETE_PLOT("deletePlot"),
    BUY_PLOT("buyPlot"),
    FREEZE_PLOT("freezePlot"),
    UNFREEZE_PLOT("unfreezePlot"),
    VACATE_PLOT("vacatePlot"),
    SUSPEND_FRIENDS("suspendFriends"),
    SET_PLOT_PRICING("setPlotPricing"),
    SET_PURCHASE_OPEN("setPurchaseOpen");

    private final String wire;

    DistrictLogAction(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    @Nullable
    public static DistrictLogAction fromWire(@Nullable String raw) {
        for (DistrictLogAction value : values()) {
            if (value.wire.equals(raw)) {
                return value;
            }
        }
        return null;
    }
}

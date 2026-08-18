package com.miningdim.power.machine;

import java.util.Arrays;

/** 提纯工序的稳定运行配置键；物料输入输出仍由数据包配方定义。 */
public enum PurifyingProfile {

    COPPER_DEOXIDIZING("copper_to_deoxidized", 200, 20, 20, 1),
    OFC_COPPER("deoxidized_to_ofc", 400, 40, 40, 1),
    PHOSPHORUS_DEOXIDIZING("deoxidized_to_phosphorus", 200, 20, 20, 1),
    OFE_COPPER("ofc_to_ofe", 800, 128, 100, 100),
    GOLD_4N("gold_to_4n", 600, 64, 100, 100);

    private final String id;
    private final int defaultDurationTicks;
    private final int defaultFePerTick;
    private final int defaultInfusionUnits;
    private final int defaultInfusionUnitsPerItem;

    PurifyingProfile(String id, int defaultDurationTicks, int defaultFePerTick, int defaultInfusionUnits,
                      int defaultInfusionUnitsPerItem) {
        this.id = id;
        this.defaultDurationTicks = defaultDurationTicks;
        this.defaultFePerTick = defaultFePerTick;
        this.defaultInfusionUnits = defaultInfusionUnits;
        this.defaultInfusionUnitsPerItem = defaultInfusionUnitsPerItem;
    }

    public String id() {
        return id;
    }

    public int defaultDurationTicks() {
        return defaultDurationTicks;
    }

    public int defaultFePerTick() {
        return defaultFePerTick;
    }

    public int defaultInfusionUnits() {
        return defaultInfusionUnits;
    }

    public int defaultInfusionUnitsPerItem() {
        return defaultInfusionUnitsPerItem;
    }

    public static PurifyingProfile byId(String id) {
        return Arrays.stream(values()).filter(profile -> profile.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown purifying profile: " + id));
    }
}

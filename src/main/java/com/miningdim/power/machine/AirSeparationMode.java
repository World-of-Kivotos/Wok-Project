package com.miningdim.power.machine;

import java.util.Arrays;

/** 空分装置的唯一输出模式。 */
public enum AirSeparationMode {

    ARGON("argon", 1_200, 512),
    LIQUID_NITROGEN("liquid_nitrogen", 400, 256);

    private final String id;
    private final int defaultDurationTicks;
    private final int defaultFePerTick;

    AirSeparationMode(String id, int defaultDurationTicks, int defaultFePerTick) {
        this.id = id;
        this.defaultDurationTicks = defaultDurationTicks;
        this.defaultFePerTick = defaultFePerTick;
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

    public static AirSeparationMode byId(String id) {
        return Arrays.stream(values()).filter(mode -> mode.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown air separation mode: " + id));
    }
}

package com.miningdim.job.munitions.gunsmith;

/** 势力只负责组件来源标识，不参与平台、槽位或属性计算。 */
public enum GunsmithFaction {
    RED_WINTER("red_winter"),
    GEHENNA("gehenna"),
    BLUE_HEAVY_INDUSTRIES("blue_heavy_industries"),
    TRINITY("trinity");

    private final String id;

    GunsmithFaction(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}

package com.miningdim.job.engineer.armor;

/** 插板构型。顺序固定为轻、中、重，与配置矩阵每级的三个连续值一致。 */
public enum PlateArmorWeight {
    LIGHT("light"),
    MEDIUM("medium"),
    HEAVY("heavy");

    private final String id;

    PlateArmorWeight(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public String translationKey() {
        return "weight.miningdim.plate_armor." + id;
    }
}

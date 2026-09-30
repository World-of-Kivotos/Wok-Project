package com.miningdim.district.core;

import java.util.Objects;

/**
 * 一所学院: id (ASCII, 也是它第一次绑定的自管区的 districtId)、简称 (地块编号前缀, 如"千年-03")、全称 (界面上写
 * "某某学院"的地方)、在自管区列表里的顺序。
 */
public record Academy(String academyId, String shortName, String fullName, int sortOrder) {

    public Academy {
        Objects.requireNonNull(academyId, "academyId");
        Objects.requireNonNull(shortName, "shortName");
        Objects.requireNonNull(fullName, "fullName");
    }

    /** 这所学院的自管区显示名, 如"千年自管区"。 */
    public String districtDisplayName() {
        return shortName + "自管区";
    }
}

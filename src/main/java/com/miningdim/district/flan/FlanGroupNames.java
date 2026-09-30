package com.miningdim.district.flan;

/**
 * 本模块在 Flan 里用的组名 (设计文档 8.4)。每块地的组名都是这块地独有的: Flan 建子领地时对父领地的组做的是浅拷贝,
 * 同名组会共用同一份设置, 改一块地会连带改掉全区。自管区父领地上只放本区居民组。
 */
public final class FlanGroupNames {

    private FlanGroupNames() {
    }

    /** 自管区父领地上唯一的组的前缀。 */
    public static final String DISTRICT_PREFIX = "d_";
    /** 地块组的前缀。 */
    public static final String PLOT_PREFIX = "p_";

    public static String districtResident(String districtId) {
        return DISTRICT_PREFIX + districtId + "_resident";
    }

    public static String plotOwner(String plotId) {
        return PLOT_PREFIX + plotId + "_owner";
    }

    public static String plotFriend(String plotId) {
        return PLOT_PREFIX + plotId + "_friend";
    }

    public static String plotResident(String plotId) {
        return PLOT_PREFIX + plotId + "_resident";
    }
}

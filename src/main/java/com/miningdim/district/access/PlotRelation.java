package com.miningdim.district.access;

/**
 * 对某块地的关系 (设计文档 7.4), 按顺序判定: 现任户主为 OWNER (最先判, OP 管自己的地时按户主算, 不记"代改");
 * 否则 OP 为 ADMIN; 其余为 NONE (含区务长和其他住户)。冻结地块与空置地块没有现任户主, 只有 ADMIN 能打开。
 */
public enum PlotRelation {
    OWNER("owner"),
    ADMIN("admin"),
    NONE("none");

    private final String wire;

    PlotRelation(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}

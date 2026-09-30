package com.miningdim.district.access;

/**
 * 全局身份 (district.state viewer.role, 设计文档 7.2), 按顺序判定: OP 恒为 admin; 否则是某个未解绑自管区的区务长为 warden;
 * 在某个未解绑自管区所属学院的名单上为 resident; 其余 (含已解绑学院的成员) 为 outsider。
 */
public enum GlobalRole {
    ADMIN("admin"),
    WARDEN("warden"),
    RESIDENT("resident"),
    OUTSIDER("outsider");

    private final String wire;

    GlobalRole(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}

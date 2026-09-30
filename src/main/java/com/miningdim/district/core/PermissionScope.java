package com.miningdim.district.core;

/** 权限目录里一组开关的种类: member = 分住户 / 外人 (地块另分朋友) 的开关; region = 全区统一的区域规则。 */
public enum PermissionScope {
    MEMBER("member"),
    REGION("region");

    private final String wire;

    PermissionScope(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}

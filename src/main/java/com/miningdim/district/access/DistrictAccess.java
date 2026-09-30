package com.miningdim.district.access;

/**
 * 对某个自管区的身份 (设计文档 7.3), 几乎所有动作按这一层判定: OP 为 ADMIN; 本区区务长为 WARDEN; 在本区学院的名单上为
 * RESIDENT; 其余为 NONE —— 别区的区务长、别区的住户、已解绑学院的成员都在这一档。
 */
public enum DistrictAccess {
    ADMIN,
    WARDEN,
    RESIDENT,
    NONE;

    /** 管理者: 管理员或本区区务长。 */
    public boolean manager() {
        return this == ADMIN || this == WARDEN;
    }
}

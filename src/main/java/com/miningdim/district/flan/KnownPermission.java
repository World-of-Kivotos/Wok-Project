package com.miningdim.district.flan;

import java.util.Objects;

/**
 * Flan 权限表里的一项 (设计文档 20.1、20.3): id、是否全局、Flan 新建领地时的出厂值、是否"要求显式设置"。
 * 真网关从 {@code PermissionManager.INSTANCE.getAll()} 读出, 记录型与 Disabled 网关用 {@link FlanPermissions#BUILTIN}。
 *
 * @param id              形如 {@code flan:break}
 * @param global          全局权限: 不能按组设, 只能对整块领地设
 * @param defaultValue    Flan 的 defaultVal: 新领地构造时预填为真的那些
 * @param requireExplicit Flan 的 requireExplicitSet (may_flight、no_hunger): OP 也不绕过, 写真就等于给出去
 */
public record KnownPermission(String id, boolean global, boolean defaultValue, boolean requireExplicit) {

    public KnownPermission {
        Objects.requireNonNull(id, "id");
    }
}

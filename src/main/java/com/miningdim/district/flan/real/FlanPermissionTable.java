package com.miningdim.district.flan.real;

import com.miningdim.district.flan.KnownPermission;
import io.github.flemmli97.flan.api.permission.ClaimPermission;
import io.github.flemmli97.flan.api.permission.PermissionManager;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Flan 当前的权限表, 按 {@code PermissionManager.INSTANCE.getAll()} 返回对象的身份缓存 (设计文档 20.3 的 /reload 一节)。
 *
 * <p>PermissionManager.INSTANCE 本身从不替换; 它的 apply() 换掉的是内部的 permissions 与 sorted 两个字段, 而
 * getAll() 直接返回 sorted。所以 getAll() 返回的对象换了身份, 就说明发生过一次重载: 重读、版本号加一。比较只是一次
 * 引用比较, 每轮对账开始时与每批写入的预检都做。
 */
final class FlanPermissionTable {

    @Nullable
    private Collection<ClaimPermission> identity;
    private List<KnownPermission> table = List.of();
    private long version;

    /** 当前的表 (身份变了就重读)。 */
    synchronized List<KnownPermission> table() {
        Collection<ClaimPermission> all = PermissionManager.INSTANCE.getAll();
        if (all != identity) {
            List<KnownPermission> read = new ArrayList<>(all.size());
            for (ClaimPermission permission : all) {
                read.add(new KnownPermission(permission.getId().toString(), permission.global, permission.defaultVal,
                        permission.requireExplicitSet));
            }
            table = List.copyOf(read);
            identity = all;
            version++;
        }
        return table;
    }

    synchronized long version() {
        table();
        return version;
    }

    /** 整服数据包重载 (OnDatapackSyncEvent 玩家为 null): 主动作废缓存, 下一次读取必定重读。 */
    synchronized void invalidate() {
        identity = null;
    }
}

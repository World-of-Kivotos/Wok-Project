package com.miningdim.district.flan;

import java.util.Map;

/**
 * 一块领地此刻的权限状态: 组 -> (权限 -> 真 / 假) 与默认 (全局) 权限。只列已设置的键, 没设置 (跟随上一层) 的不出现。
 * 阶段 2 的对账用它比较期望与实际。
 */
public record ClaimPermissionSnapshot(Map<String, Map<String, Boolean>> groups, Map<String, Boolean> defaults) {

    public ClaimPermissionSnapshot {
        groups = Map.copyOf(groups);
        defaults = Map.copyOf(defaults);
    }
}

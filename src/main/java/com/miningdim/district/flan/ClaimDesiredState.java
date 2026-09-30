package com.miningdim.district.flan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 一块领地 (父领地或地块) 整块的期望状态, 对比与写入的共同输入 (设计文档 20.4 "推送也改用按差异写")。
 *
 * @param name     期望的名字 (不含 %)
 * @param groups   组 -&gt; (权限 -&gt; 值); 不在这里的组一律删掉。组里 UNSET 表示"不许有这个键"
 * @param defaults 默认 (全局) 权限: 权限 -&gt; 值; UNSET 表示"不许有这个键" (地块上跟随父领地)
 * @param members  成员 -&gt; 组; 不在这里的成员一律移出
 * @param root     顶层领地 (父领地): Flan 存盘时顶层领地的 GlobalPerms 只存值为真的 id, 重启后"假"就成了"缺省",
 *                 而顶层领地上"缺省"的判定结果也是假, 所以对比时把两者视为相同; 地块真假都存, 不适用
 */
public record ClaimDesiredState(String name, Map<String, Map<String, PermValue>> groups,
                                Map<String, PermValue> defaults, Map<UUID, String> members, boolean root) {

    public ClaimDesiredState {
        Objects.requireNonNull(name, "name");
        Map<String, Map<String, PermValue>> groupCopy = new LinkedHashMap<>();
        groups.forEach((group, perms) -> groupCopy.put(group, Collections.unmodifiableMap(new LinkedHashMap<>(perms))));
        groups = Collections.unmodifiableMap(groupCopy);
        defaults = Collections.unmodifiableMap(new LinkedHashMap<>(defaults));
        members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
    }
}

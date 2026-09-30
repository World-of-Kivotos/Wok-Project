package com.miningdim.district.flan;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 期望状态与 Flan 现状的差异 (设计文档 20.4): 纯函数, 输出一串按"先收后放"排好序的 {@link FlanOp}。值相同的格子
 * 不出现, 所以同一个状态推两遍, 第二遍一次写入都没有 (领地不被标脏)。
 *
 * <p>比较一律按值: 地块与父领地共用内层 Map 的状况只在建地块的那一刻出现, 且清理会立刻删掉; 重启后更是各自独立的副本。
 */
public final class ClaimDiff {

    private ClaimDiff() {
    }

    /**
     * @param desired  期望状态
     * @param current  现状: 组与默认 (只列已设置的键)
     * @param members  现状: 成员 -&gt; 组
     * @param info     现状: 名字、假玩家、药水; null 时不比这三样
     */
    public static List<FlanOp> plan(ClaimDesiredState desired, ClaimPermissionSnapshot current,
                                    Map<UUID, String> members, @Nullable ClaimInfo info) {
        List<FlanOp> ops = new ArrayList<>();
        if (info != null) {
            if (!desired.name().equals(info.name())) {
                ops.add(new FlanOp.SetName(info.name(), desired.name()));
            }
            if (info.hasExtras()) {
                ops.add(new FlanOp.ClearExtras(info.fakePlayers(), info.potions(), info.allowListEntries()));
            }
        }

        // 多余的组: 连同成员一起删 (deleteGroup 会移出这个组的成员, 下面的成员比较因此跳过他们)。
        Set<String> deletedGroups = new HashSet<>();
        for (Map.Entry<String, Map<String, Boolean>> group : current.groups().entrySet()) {
            if (!desired.groups().containsKey(group.getKey())) {
                long count = members.values().stream().filter(group.getKey()::equals).count();
                ops.add(new FlanOp.DeleteGroup(group.getKey(), (int) count));
                deletedGroups.add(group.getKey());
            }
        }
        for (Map.Entry<UUID, String> member : members.entrySet()) {
            if (!desired.members().containsKey(member.getKey()) && !deletedGroups.contains(member.getValue())) {
                ops.add(new FlanOp.RemoveMember(member.getKey(), member.getValue()));
            }
        }

        for (Map.Entry<String, Map<String, PermValue>> group : desired.groups().entrySet()) {
            Map<String, Boolean> actual = current.groups().getOrDefault(group.getKey(), Map.of());
            for (Map.Entry<String, PermValue> perm : group.getValue().entrySet()) {
                Boolean now = actual.get(perm.getKey());
                if (!same(perm.getValue(), now, false)) {
                    ops.add(new FlanOp.SetGroupPerm(group.getKey(), perm.getKey(), now, perm.getValue()));
                }
            }
        }
        for (Map.Entry<String, PermValue> perm : desired.defaults().entrySet()) {
            Boolean now = current.defaults().get(perm.getKey());
            if (!same(perm.getValue(), now, desired.root())) {
                ops.add(new FlanOp.SetDefaultPerm(perm.getKey(), now, perm.getValue()));
            }
        }

        for (Map.Entry<UUID, String> member : desired.members().entrySet()) {
            String now = members.get(member.getKey());
            if (deletedGroups.contains(now)) {
                now = null;
            }
            if (!member.getValue().equals(now)) {
                ops.add(new FlanOp.SetMember(member.getKey(), now, member.getValue()));
            }
        }
        ops.sort(Comparator.comparingInt(FlanOp::phase));
        return List.copyOf(ops);
    }

    /**
     * 一格是否已经是期望值。
     *
     * @param falseEqualsAbsent 顶层领地的默认权限: 假与缺省判定结果相同 (见 {@link ClaimDesiredState#root()})
     */
    static boolean same(PermValue desired, @Nullable Boolean now, boolean falseEqualsAbsent) {
        return switch (desired) {
            case TRUE -> Boolean.TRUE.equals(now);
            case FALSE -> Boolean.FALSE.equals(now) || (falseEqualsAbsent && now == null);
            case UNSET -> now == null;
        };
    }
}

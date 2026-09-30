package com.miningdim.district.flan;

import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.FriendRecord;
import com.miningdim.district.core.MemberRecord;
import com.miningdim.district.core.PermissionCells;
import com.miningdim.district.core.PlotAudience;
import com.miningdim.district.core.PlotRecord;
import com.miningdim.district.core.PlotStatus;
import com.miningdim.district.core.ResidentSyncStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 一块地整块的期望状态 (设计文档 8.3, 阶段 2 按 20.3 的权限全表)。推送与对账都按它"整块"比较, 只写不同的格子。
 *
 * <table>
 *   <tr><th>状态</th><th>户主组</th><th>朋友组</th><th>居民组</th><th>默认 (外人)</th></tr>
 *   <tr><td>owned</td><td>户主一人, Policy 的户主组</td><td>有效朋友 (未暂停且非待生效), 朋友列</td>
 *       <td>本区 synced 名单去掉户主与有效朋友, 住户列</td><td>外人列</td></tr>
 *   <tr><td>vacant</td><td>空</td><td>空</td><td>本区 synced 名单全员, 住户列默认值</td><td>外人列默认值</td></tr>
 *   <tr><td>frozen</td><td>空 (组权限全假)</td><td>空 (全假)</td><td>空 (全假)</td><td>全假; 库里存着的户主、朋友与三列不动</td></tr>
 * </table>
 * 共同规则: 一个人只进一个组, 按"户主 &gt; 朋友 &gt; 居民"取最高; 被暂停的朋友不进朋友组 (是本区住户时进居民组);
 * 每个非全局权限都显式写; 全局权限 (区域规则与其余 8 个) 一律 UNSET, 跟随父领地。
 */
public record PlotDesiredState(
        PlotStatus mode,
        String name,
        Map<UUID, String> members,
        Map<String, Map<String, PermValue>> groupPerms,
        Map<String, PermValue> defaults) {

    public PlotDesiredState {
        members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
        groupPerms = Collections.unmodifiableMap(new LinkedHashMap<>(groupPerms));
        defaults = Collections.unmodifiableMap(new LinkedHashMap<>(defaults));
    }

    /** 转成领地的期望状态 (对比与写入用)。 */
    public ClaimDesiredState asClaimState() {
        return new ClaimDesiredState(name, groupPerms, defaults, members, false);
    }

    public static PlotDesiredState compute(FlanPermissionPolicy policy, DistrictRecord district, PlotRecord plot,
                                           PermissionCells cells, List<FriendRecord> friends,
                                           List<MemberRecord> academyMembers) {
        return compute(policy, district, plot, cells, friends, academyMembers, null);
    }

    /**
     * @param cells          这块地的三列 (owned 时使用; vacant 一律用目录默认值, frozen 一律全假)
     * @param friends        这块地的朋友行
     * @param academyMembers 本区学院的名单
     * @param assumeSynced   当作已生效的住户: 成员资格推送时本区居民组已写成功、名单行还挂着"临时失败"的那一位
     */
    public static PlotDesiredState compute(FlanPermissionPolicy policy, DistrictRecord district, PlotRecord plot,
                                           PermissionCells cells, List<FriendRecord> friends,
                                           List<MemberRecord> academyMembers, @Nullable UUID assumeSynced) {
        String ownerGroup = FlanGroupNames.plotOwner(plot.plotId());
        String friendGroup = FlanGroupNames.plotFriend(plot.plotId());
        String residentGroup = FlanGroupNames.plotResident(plot.plotId());
        PlotStatus mode = plot.status();
        String name = DistrictTexts.claimName(plot.code());

        Map<String, Map<String, PermValue>> groupPerms = new LinkedHashMap<>();
        Map<UUID, String> members = new LinkedHashMap<>();

        if (mode == PlotStatus.FROZEN) {
            for (String group : List.of(ownerGroup, friendGroup, residentGroup)) {
                groupPerms.put(group, policy.frozenGroup());
            }
            return new PlotDesiredState(mode, name, members, groupPerms, policy.frozenDefaults());
        }

        boolean owned = mode == PlotStatus.OWNED;
        PermissionCells column = owned ? cells : null;
        groupPerms.put(ownerGroup, policy.plotOwnerGroup());
        groupPerms.put(friendGroup, policy.plotColumn(column, PlotAudience.FRIEND));
        groupPerms.put(residentGroup, policy.plotColumn(column, PlotAudience.RESIDENT));
        Map<String, PermValue> defaults = policy.plotDefaults(column);

        Set<UUID> placed = new HashSet<>();
        if (owned && plot.ownerUuid() != null) {
            members.put(plot.ownerUuid(), ownerGroup);
            placed.add(plot.ownerUuid());
            for (FriendRecord friend : friends) {
                if (friend.active() && placed.add(friend.uuid())) {
                    members.put(friend.uuid(), friendGroup);
                }
            }
        }
        for (MemberRecord member : academyMembers) {
            if (countsAsResident(district, member, assumeSynced) && placed.add(member.uuid())) {
                members.put(member.uuid(), residentGroup);
            }
        }
        return new PlotDesiredState(mode, name, members, groupPerms, defaults);
    }

    /**
     * 某一个人在这块地里该在哪个组 (没有组为 null), 与 {@link #compute} 给出的 members 里这个人的那一项相同, 但不用读
     * 这块地的三列与全区名单: 加一名住户要触达每一块地, 逐块整张算期望状态在大区里是平方级 (20.4)。
     *
     * @param friends 这块地的朋友行
     * @param member  这个人在名单里的那一行 (不是名单里的人为 null)
     */
    @Nullable
    public static String memberGroup(DistrictRecord district, PlotRecord plot, List<FriendRecord> friends, UUID uuid,
                                     @Nullable MemberRecord member, @Nullable UUID assumeSynced) {
        if (plot.status() == PlotStatus.FROZEN) {
            return null;
        }
        if (plot.status() == PlotStatus.OWNED && plot.ownerUuid() != null) {
            if (plot.ownerUuid().equals(uuid)) {
                return FlanGroupNames.plotOwner(plot.plotId());
            }
            for (FriendRecord friend : friends) {
                if (friend.active() && friend.uuid().equals(uuid)) {
                    return FlanGroupNames.plotFriend(plot.plotId());
                }
            }
        }
        return member != null && member.uuid().equals(uuid) && countsAsResident(district, member, assumeSynced)
                ? FlanGroupNames.plotResident(plot.plotId())
                : null;
    }

    /** 进居民组的条件: 名单行已生效 (或是正被推送、当作已生效的那一位), 且是本区学院的人。 */
    private static boolean countsAsResident(DistrictRecord district, MemberRecord member, @Nullable UUID assumeSynced) {
        boolean synced = member.syncStatus() == ResidentSyncStatus.SYNCED || member.uuid().equals(assumeSynced);
        return synced && member.academyId().equals(district.academyId());
    }
}

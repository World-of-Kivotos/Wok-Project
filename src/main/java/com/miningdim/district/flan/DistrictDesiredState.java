package com.miningdim.district.flan;

import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.MemberRecord;
import com.miningdim.district.core.PermissionCells;
import com.miningdim.district.core.ResidentSyncStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 自管区父领地的期望状态 (设计文档 20.3):
 * <ul>
 *   <li>只有一个组: 本区居民组 d_&lt;districtId&gt;_resident, 组权限 = 公共区域住户列 + 全表里其余非全局权限;</li>
 *   <li>成员: 本区名单里 synced 与 failed 的人 (failed 表示"本该在、上次没写成"; pending 的人不写, 他们的 UUID
 *       还可能在首次登录时换键);</li>
 *   <li>默认 = 外人列 + 其余非全局; 全局 = 7 项区域规则按全区列 (mob_spawn 取反), 其余写 Flan 出厂值;</li>
 *   <li>名字 = 自管区显示名去掉 %; 假玩家白名单与药水为空 (对比时另查)。</li>
 * </ul>
 */
public final class DistrictDesiredState {

    private DistrictDesiredState() {
    }

    public static ClaimDesiredState compute(FlanPermissionPolicy policy, DistrictRecord district, PermissionCells cells,
                                            List<MemberRecord> academyMembers) {
        String group = FlanGroupNames.districtResident(district.districtId());
        Map<String, Map<String, PermValue>> groups = new LinkedHashMap<>();
        groups.put(group, policy.districtResidentGroup(cells));
        Map<UUID, String> members = new LinkedHashMap<>();
        for (MemberRecord member : academyMembers) {
            if (member.academyId().equals(district.academyId())
                    && member.syncStatus() != ResidentSyncStatus.PENDING) {
                members.put(member.uuid(), group);
            }
        }
        return new ClaimDesiredState(DistrictTexts.claimName(district.displayName()), groups,
                policy.districtDefaults(cells), members, true);
    }
}

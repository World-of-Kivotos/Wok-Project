package com.miningdim.district.access;

import com.miningdim.district.core.MemberRecord;
import com.miningdim.district.core.ResidentSyncStatus;
import org.jetbrains.annotations.Nullable;

/**
 * district.detail abilities (设计文档 7.5), 服务端算好下发, 前端不再推一遍。
 *
 * 区务长和住户的 build / interact 只在本人名单行 synced 时为真: 其他情况下本人不在 Flan 居民组里, 游戏里按外人那一列算。
 * 管理员不经居民组, 恒为真; 管人的能力由服务端代办, 与本人的 Flan 状态无关。
 */
public record Abilities(
        boolean build,
        boolean interact,
        boolean manageResidents,
        boolean viewRoster,
        boolean editClaim,
        boolean deleteDistrict,
        boolean appointWarden,
        boolean retrySync,
        boolean managePermissions,
        boolean manageRegionRules,
        boolean viewPlotList,
        boolean viewPlotStatus,
        boolean inspectPlots,
        boolean overridePlots,
        boolean managePlots,
        boolean managePlotMarket,
        boolean manageFrozenPlots) {

    /**
     * @param access 对这个区的身份, 不能是 NONE (district.detail 对 NONE 直接拒绝)
     * @param own    查看者自己的名单行 (管理员可以为 null)
     */
    public static Abilities of(DistrictAccess access, @Nullable MemberRecord own) {
        return switch (access) {
            case ADMIN -> new Abilities(true, true, true, true, true, true, true, true, true, true, true, true, true,
                    true, true, true, true);
            case WARDEN -> {
                boolean effective = effective(own);
                yield new Abilities(effective, effective, true, true, false, false, false, false, true, false, true,
                        true, false, false, true, false, false);
            }
            case RESIDENT -> {
                boolean effective = effective(own);
                yield new Abilities(effective, effective, false, false, false, false, false, false, false, false, true,
                        false, false, false, false, false, false);
            }
            case NONE -> throw new IllegalArgumentException("abilities are not defined for access NONE");
        };
    }

    private static boolean effective(@Nullable MemberRecord own) {
        return own != null && own.syncStatus() == ResidentSyncStatus.SYNCED;
    }
}

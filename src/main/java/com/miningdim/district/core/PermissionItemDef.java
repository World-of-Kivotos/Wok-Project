package com.miningdim.district.core;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 权限目录里的一项。permissionId 是本模块自己的稳定 id, 不是 Flan 的 id; 一项可对多条 Flan 权限 (坐船和矿车 =
 * boat + minecart), 写的时候一起写。
 *
 * member 项: residentDefault / outsiderDefault 非 null、districtDefault 为 null, 另有地块三列默认值 plotDefaults;
 * region 项 (区域规则): 只有 districtDefault, plotDefaults 为 null (地块里不能改区域规则)。
 * outsiderRisk 与 plotRisk 成对出现 (高风险清单只有一份: 公共区域和地块永远拦同一批项)。
 */
public record PermissionItemDef(
        String permissionId,
        String groupId,
        PermissionScope scope,
        String label,
        @Nullable String detail,
        List<String> flanIds,
        boolean flanInverted,
        @Nullable Boolean residentDefault,
        @Nullable Boolean outsiderDefault,
        @Nullable Boolean districtDefault,
        @Nullable String outsiderRisk,
        @Nullable String districtRisk,
        @Nullable PlotDefaults plotDefaults,
        @Nullable String plotRisk) {

    public PermissionItemDef {
        Objects.requireNonNull(permissionId, "permissionId");
        Objects.requireNonNull(label, "label");
        flanIds = List.copyOf(flanIds);
    }

    /** 地块三列的默认值。 */
    public record PlotDefaults(boolean friend, boolean resident, boolean outsider) {

        public boolean of(PlotAudience audience) {
            return switch (audience) {
                case FRIEND -> friend;
                case RESIDENT -> resident;
                case OUTSIDER -> outsider;
            };
        }
    }

    public boolean region() {
        return scope == PermissionScope.REGION;
    }

    /** 公共区域某一列的默认值; 该列不属于这一项时抛 (调用方应先用 {@link DistrictAudience#appliesTo} 判定)。 */
    public boolean districtDefault(DistrictAudience audience) {
        Boolean value = switch (audience) {
            case RESIDENT -> residentDefault;
            case OUTSIDER -> outsiderDefault;
            case DISTRICT -> districtDefault;
        };
        if (value == null) {
            throw new IllegalArgumentException(permissionId + " has no " + audience.wire() + " column");
        }
        return value;
    }

    /** 地块某一列的默认值; 区域规则没有地块列, 调用时抛。 */
    public boolean plotDefault(PlotAudience audience) {
        if (plotDefaults == null) {
            throw new IllegalArgumentException(permissionId + " is a region rule and has no plot columns");
        }
        return plotDefaults.of(audience);
    }
}

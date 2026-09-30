package com.miningdim.district.core;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * district 表的一行: 学院与领地的一次绑定。unboundAt 非 null 即已解绑 (归档行, 学院名单保留、Flan 领地不动)。
 */
public record DistrictRecord(
        String districtId,
        String academyId,
        String displayName,
        DistrictBounds bounds,
        List<String> rules,
        @Nullable UUID wardenUuid,
        @Nullable String wardenName,
        int unitPrice,
        int minSide,
        int maxSide,
        boolean purchaseOpen,
        int nextPlotNo,
        @Nullable UUID flanClaimId,
        boolean needsReconcile,
        long createdAt,
        String createdByName,
        @Nullable Long unboundAt,
        @Nullable String unboundByName,
        @Nullable Integer unboundMemberCount,
        @Nullable Integer unboundPlotCount) {

    public DistrictRecord {
        Objects.requireNonNull(districtId, "districtId");
        Objects.requireNonNull(academyId, "academyId");
        Objects.requireNonNull(bounds, "bounds");
        rules = List.copyOf(rules);
    }

    /** 未解绑。 */
    public boolean live() {
        return unboundAt == null;
    }

    /** 某人是否本区区务长 (按 UUID)。 */
    public boolean isWarden(@Nullable UUID player) {
        return player != null && player.equals(wardenUuid);
    }

    /** 一块地的价格 = 面积 × 本区单价, 按 long 计算并检出溢出。 */
    public long priceOf(PlotArea area) {
        return Math.multiplyExact(area.area(), (long) unitPrice);
    }
}

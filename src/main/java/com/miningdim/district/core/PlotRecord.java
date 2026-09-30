package com.miningdim.district.core;

import com.miningdim.district.DistrictLimits;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * 一块现存地块 (自管区 Flan 领地下的一块子领地)。户主与冻结中的原户主互斥 (库上有 CHECK); 冻结时朋友与三列设置
 * 原样留着, 解冻时照原样还回去。tenure 是当前任期, 地块记录按任期区分本任与历任。
 */
public record PlotRecord(
        String plotId,
        String districtId,
        int plotNo,
        String code,
        PlotArea area,
        @Nullable UUID ownerUuid,
        @Nullable String ownerName,
        @Nullable UUID frozenOwnerUuid,
        @Nullable String frozenOwnerName,
        @Nullable Long frozenAt,
        int tenure,
        PlotSyncStatus syncStatus,
        @Nullable String syncError,
        @Nullable UUID flanClaimId,
        long createdAt) {

    public PlotRecord {
        Objects.requireNonNull(plotId, "plotId");
        Objects.requireNonNull(districtId, "districtId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(area, "area");
        Objects.requireNonNull(syncStatus, "syncStatus");
    }

    /** 冻结优先: 冻结中户主字段为空, 原户主记在 frozen* 里。 */
    public PlotStatus status() {
        if (frozenOwnerUuid != null) {
            return PlotStatus.FROZEN;
        }
        return ownerUuid == null ? PlotStatus.VACANT : PlotStatus.OWNED;
    }

    public boolean owned() {
        return status() == PlotStatus.OWNED;
    }

    public boolean frozen() {
        return status() == PlotStatus.FROZEN;
    }

    public boolean vacant() {
        return status() == PlotStatus.VACANT;
    }

    /** 某人是否这块地的现任户主。 */
    public boolean isOwner(@Nullable UUID player) {
        return player != null && player.equals(ownerUuid);
    }

    /** 冻结到期、应被收回的时刻; 未冻结为 null。 */
    @Nullable
    public Long reclaimAt() {
        return frozenAt == null ? null : frozenAt + DistrictLimits.FREEZE_MS;
    }
}

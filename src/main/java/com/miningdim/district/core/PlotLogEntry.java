package com.miningdim.district.core;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * 地块记录一条 (只追加, 删地块后仍保留)。tenure 是它属于哪一任户主: 本任期 = tenure == plot.tenure, 户主只看得到这些;
 * 历任只有管理员看得到。移出原因绝不写进地块记录: 下一任户主能看到地块记录。
 */
public record PlotLogEntry(
        long id,
        String plotId,
        String districtId,
        int tenure,
        long at,
        @Nullable UUID actorUuid,
        String actorName,
        PlotActorRole actorRole,
        PlotLogAction action,
        @Nullable String targetName,
        @Nullable String reason,
        @Nullable PermissionChange permission,
        @Nullable AreaChange area,
        boolean onBehalfOfOwner) {

    public PlotLogEntry {
        Objects.requireNonNull(plotId, "plotId");
        Objects.requireNonNull(districtId, "districtId");
        Objects.requireNonNull(actorName, "actorName");
        Objects.requireNonNull(actorRole, "actorRole");
        Objects.requireNonNull(action, "action");
        if ((action == PlotLogAction.PERMISSION) != (permission != null)) {
            throw new IllegalArgumentException("permission payload must be present exactly for permission rows");
        }
    }

    public PlotLogEntry withId(long newId) {
        return new PlotLogEntry(newId, plotId, districtId, tenure, at, actorUuid, actorName, actorRole, action,
                targetName, reason, permission, area, onBehalfOfOwner);
    }

    public String entryId() {
        return "p" + id;
    }
}

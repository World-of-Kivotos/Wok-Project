package com.miningdim.district.core;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * 本区记录一条 (只追加)。id 为 0 表示尚未写入; 写入后 entryId = "d" + id, 在本任期、历任与墓碑里都唯一。
 * 同一次动作写出的几行共用同一个 at, 读取按 (at DESC, id DESC) 排, 后插入的排在前面。
 */
public record DistrictLogEntry(
        long id,
        String districtId,
        long at,
        @Nullable UUID actorUuid,
        String actorName,
        DistrictActorRole actorRole,
        DistrictLogAction action,
        @Nullable String targetName,
        @Nullable String reason,
        @Nullable PermissionChange permission,
        @Nullable AreaChange area) {

    public DistrictLogEntry {
        Objects.requireNonNull(districtId, "districtId");
        Objects.requireNonNull(actorName, "actorName");
        Objects.requireNonNull(actorRole, "actorRole");
        Objects.requireNonNull(action, "action");
        if ((action == DistrictLogAction.PERMISSION) != (permission != null)) {
            throw new IllegalArgumentException("permission payload must be present exactly for permission rows");
        }
    }

    public DistrictLogEntry withId(long newId) {
        return new DistrictLogEntry(newId, districtId, at, actorUuid, actorName, actorRole, action, targetName, reason,
                permission, area);
    }

    public String entryId() {
        return "d" + id;
    }
}

package com.miningdim.district.core;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * 学院名单的一行 (= 住户一名)。id 自增即入学顺序。从没进过服的人按离线 UUID 记入、状态 pending, 首次登录时
 * 按小写名匹配并换键到登录者的 UUID (设计文档 10.4 / 10.5)。
 */
public record MemberRecord(
        long id,
        UUID uuid,
        String name,
        String academyId,
        long joinedAt,
        @Nullable UUID addedByUuid,
        String addedByName,
        ResidentSyncStatus syncStatus,
        @Nullable String syncError) {

    public MemberRecord {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(academyId, "academyId");
        Objects.requireNonNull(syncStatus, "syncStatus");
    }

    public String nameLower() {
        return DistrictTexts.lower(name);
    }
}

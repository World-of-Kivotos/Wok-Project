package com.miningdim.district.core;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * 地块的一位朋友。suspendedAt 非 null = 因违反区规被移出本区而暂停: 暂停期间不进朋友组 (TA 是本区住户时按其他住户算,
 * 否则按外人算), 仍占朋友名额, 户主可以恢复。
 */
public record FriendRecord(
        long id,
        String plotId,
        UUID uuid,
        String name,
        long addedAt,
        String addedByName,
        FriendSyncStatus syncStatus,
        @Nullable Long suspendedAt) {

    public FriendRecord {
        Objects.requireNonNull(plotId, "plotId");
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(syncStatus, "syncStatus");
    }

    public boolean suspended() {
        return suspendedAt != null;
    }

    /** 在游戏里按朋友算: 没暂停且不是待生效。 */
    public boolean active() {
        return suspendedAt == null && syncStatus == FriendSyncStatus.SYNCED;
    }

    /**
     * 同一个人: UUID 相同; 名字不分大小写相同只在这一行还是待生效时才算 (待生效的朋友只有名字, UUID 是按输入原样算的
     * 离线 UUID)。已生效的行带着真 UUID, 名字相同而 UUID 不同的是另一个账号。
     */
    public boolean matches(@Nullable UUID player, String nameLower) {
        return uuid.equals(player)
                || (syncStatus == FriendSyncStatus.PENDING && DistrictTexts.lower(name).equals(nameLower));
    }

    /**
     * 与这个 UUID 或这个小写名撞车: 同一块地里两者各自唯一 (V9 的两个 UNIQUE), 所以加朋友前的查重用它, 不用
     * {@link #matches} —— 那里名字相同而 UUID 不同的已生效行不算同一个人, 但照样占着这个小写名。
     */
    public boolean collidesWith(@Nullable UUID player, String nameLower) {
        return uuid.equals(player) || DistrictTexts.lower(name).equals(nameLower);
    }
}

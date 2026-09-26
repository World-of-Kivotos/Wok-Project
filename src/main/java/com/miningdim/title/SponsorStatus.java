package com.miningdim.title;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * 一名玩家的赞助资格 (title_sponsor 一行, Title_System_DesignSpec 13.5 / 13.6), 由管理员手动发放。
 *
 * @param player    玩家
 * @param grantedBy 最近一次发放或续期的执行者
 * @param grantedAt 最近一次发放或续期的时间 (毫秒)
 * @param expiresAt 到期时间 (毫秒); null 表示永久
 */
public record SponsorStatus(UUID player, String grantedBy, long grantedAt, @Nullable Long expiresAt) {

    public SponsorStatus {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(grantedBy, "grantedBy");
    }

    public boolean permanent() {
        return expiresAt == null;
    }

    /** now 时刻资格是否有效: 永久, 或尚未到期 (到期那一毫秒起即失效)。 */
    public boolean activeAt(long now) {
        return expiresAt == null || now < expiresAt;
    }
}

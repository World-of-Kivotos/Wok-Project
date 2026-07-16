package com.miningdim.caseopening.store;

import com.miningdim.caseopening.CaseRarity;

import java.util.Objects;
import java.util.UUID;

/** One independently transferable ownership unit, even when two openings roll the same skin. */
public record SkinAssetRow(UUID assetId, UUID ownerId, String skinId, CaseRarity rarity,
                           String gunId, String displayId, UUID sourceOpeningId,
                           long acquiredAt, long tradeLockedUntil) {

    public SkinAssetRow {
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(skinId, "skinId");
        Objects.requireNonNull(rarity, "rarity");
        Objects.requireNonNull(gunId, "gunId");
        Objects.requireNonNull(displayId, "displayId");
        Objects.requireNonNull(sourceOpeningId, "sourceOpeningId");
    }
}

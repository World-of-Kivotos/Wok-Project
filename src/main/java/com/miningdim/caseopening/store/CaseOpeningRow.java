package com.miningdim.caseopening.store;

import com.miningdim.caseopening.CaseRarity;

import java.util.Objects;
import java.util.UUID;

/** Durable opening intent and its pre-rolled immutable result. */
public record CaseOpeningRow(UUID openingId, UUID ownerId, String caseId,
                             long creditCost, long azureCost, CaseOpeningStatus status,
                             UUID assetId, String skinId, CaseRarity rarity,
                             String gunId, String displayId, String reelJson, int stopIndex,
                             long createdAt, long updatedAt) {

    public CaseOpeningRow {
        Objects.requireNonNull(openingId, "openingId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(skinId, "skinId");
        Objects.requireNonNull(rarity, "rarity");
        Objects.requireNonNull(gunId, "gunId");
        Objects.requireNonNull(displayId, "displayId");
        Objects.requireNonNull(reelJson, "reelJson");
        if (creditCost <= 0L || azureCost <= 0L) {
            throw new IllegalArgumentException("case opening costs must be positive");
        }
    }

    public CaseOpeningRow withStatus(CaseOpeningStatus next, long now) {
        return new CaseOpeningRow(openingId, ownerId, caseId, creditCost, azureCost, next,
                assetId, skinId, rarity, gunId, displayId, reelJson, stopIndex, createdAt, now);
    }
}

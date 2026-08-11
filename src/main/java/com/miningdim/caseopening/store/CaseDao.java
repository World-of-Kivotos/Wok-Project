package com.miningdim.caseopening.store;

import java.util.List;
import java.util.UUID;

/** SQLite contract for durable openings and non-item skin ownership. */
public interface CaseDao {

    CaseOpeningRow reserve(CaseOpeningRow proposed);

    CaseOpeningRow findOpening(UUID openingId);

    boolean markDebited(UUID openingId, long updatedAt);

    SkinAssetRow commitOpening(UUID openingId, SkinAssetRow asset, long updatedAt);

    boolean markRefunded(UUID openingId, long updatedAt);

    List<CaseOpeningRow> recoverableOpenings(UUID ownerId);

    SkinAssetRow findAsset(UUID assetId);

    SkinAssetRow findOwnedAsset(UUID ownerId, UUID assetId);

    List<SkinAssetRow> ownedAssets(UUID ownerId);
}

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

    /** 全服待对账的开箱行 (跨玩家, 供启动期对账捞出从不再上线的玩家)。 */
    List<CaseOpeningRow> allRecoverableOpenings();

    /** 把一行落成隔离终态; 返回是否真的改动了行。 */
    boolean markQuarantined(UUID openingId, long updatedAt);

    SkinAssetRow findAsset(UUID assetId);

    SkinAssetRow findOwnedAsset(UUID ownerId, UUID assetId);

    List<SkinAssetRow> ownedAssets(UUID ownerId);
}

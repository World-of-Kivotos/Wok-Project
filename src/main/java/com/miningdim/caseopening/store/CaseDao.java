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

    /**
     * 把该开箱行标记为已结算：双币扣款已持久化到账本 COMPLETED，此后与账本行是否被
     * {@code EconomySystem} 定期回收无关。只有 COMMITTED 行可以带这个标记。
     *
     * @return 是否真的改动了行；非 COMMITTED 行（不可能状态）返回 false
     */
    boolean markEconomySettled(UUID openingId, long updatedAt);

    /**
     * 该开箱行的结算锚是否已落定。行不存在时视为数据损坏并直接抛出，不返回 false 掩盖。
     */
    boolean isOpeningSettled(UUID openingId);

    /** 只返回结算锚已落定 (economy_settled=1) 的资产；过滤在 SQL 侧完成，不把点查留给 Java 流。 */
    List<SkinAssetRow> settledOwnedAssets(UUID ownerId);
}

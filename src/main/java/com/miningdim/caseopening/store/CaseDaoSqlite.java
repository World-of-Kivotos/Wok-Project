package com.miningdim.caseopening.store;

import com.miningdim.caseopening.CaseRarity;
import com.miningdim.store.StoreTx;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Single-connection SQLite implementation. Each opening commit and ownership insert share one SQL transaction. */
public final class CaseDaoSqlite implements CaseDao {

    private final Connection connection;

    CaseDaoSqlite(Connection connection) {
        this.connection = connection;
    }

    /**
     * 暴露底层连接给同包 {@link CaseDb} 做 GameTest 内存库的关闭。
     * 包级可见, 不进 {@link CaseDao} 接口 —— 业务层只经接口操作, 不直接碰连接。
     */
    Connection connection() {
        return connection;
    }

    @Override
    public CaseOpeningRow reserve(CaseOpeningRow proposed) {
        String sql = "INSERT OR IGNORE INTO case_openings "
                + "(opening_id,owner_uuid,case_id,credit_cost,azure_cost,status,asset_id,skin_id,rarity,gun_id,"
                + "display_id,reel_json,stop_index,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindOpening(statement, proposed);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new CaseStoreException("failed to reserve case opening " + proposed.openingId(), exception);
        }
        CaseOpeningRow actual = findOpening(proposed.openingId());
        if (actual == null) {
            throw new CaseStoreException("reserved case opening disappeared: " + proposed.openingId());
        }
        return actual;
    }

    @Override
    public CaseOpeningRow findOpening(UUID openingId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM case_openings WHERE opening_id=?")) {
            statement.setString(1, openingId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? opening(result) : null;
            }
        } catch (SQLException exception) {
            throw new CaseStoreException("failed to find case opening " + openingId, exception);
        }
    }

    @Override
    public boolean markDebited(UUID openingId, long updatedAt) {
        int changed = updateStatus(openingId, CaseOpeningStatus.RESERVED, CaseOpeningStatus.DEBITED, updatedAt);
        if (changed > 0) {
            return true;
        }
        CaseOpeningRow row = findOpening(openingId);
        return row != null && (row.status() == CaseOpeningStatus.DEBITED
                || row.status() == CaseOpeningStatus.COMMITTED);
    }

    @Override
    public SkinAssetRow commitOpening(UUID openingId, SkinAssetRow asset, long updatedAt) {
        // 事务经 StoreTx: 连接现在是全服共享的, 若调用方已开着事务 (扣钱与发资产同事务), 本方法必须并入
        // 而不是提前 commit 掉外层 —— 那会把"钱已扣、资产未发"这个中间态直接落盘。
        return StoreTx.call(connection, () -> {
            CaseOpeningRow opening = findOpening(openingId);
            if (opening == null) {
                throw new CaseStoreException("cannot commit missing case opening " + openingId);
            }
            if (opening.status() == CaseOpeningStatus.COMMITTED) {
                SkinAssetRow existing = findAsset(asset.assetId());
                if (existing == null) {
                    throw new CaseStoreException("committed opening has no asset " + openingId);
                }
                return existing;
            }
            if (opening.status() != CaseOpeningStatus.DEBITED) {
                throw new CaseStoreException("cannot commit opening " + openingId + " from " + opening.status());
            }
            insertAssetChecked(asset);
            markCommitted(openingId, updatedAt);
            return asset;
        });
    }

    private void markCommitted(UUID openingId, long updatedAt) {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE case_openings SET status='COMMITTED',updated_at=? "
                        + "WHERE opening_id=? AND status='DEBITED'")) {
            statement.setLong(1, updatedAt);
            statement.setString(2, openingId.toString());
            if (statement.executeUpdate() != 1) {
                throw new CaseStoreException("case opening changed while committing " + openingId);
            }
        } catch (SQLException exception) {
            throw new CaseStoreException("failed to commit case opening " + openingId, exception);
        }
    }

    private void insertAssetChecked(SkinAssetRow asset) {
        try {
            insertAsset(asset);
        } catch (SQLException exception) {
            throw new CaseStoreException("failed to insert skin asset " + asset.assetId(), exception);
        }
    }

    @Override
    public boolean markRefunded(UUID openingId, long updatedAt) {
        String sql = "UPDATE case_openings SET status='REFUNDED',updated_at=? "
                + "WHERE opening_id=? AND status IN ('RESERVED','DEBITED')";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, updatedAt);
            statement.setString(2, openingId.toString());
            int changed = statement.executeUpdate();
            if (changed > 0) {
                return true;
            }
            CaseOpeningRow row = findOpening(openingId);
            return row != null && row.status() == CaseOpeningStatus.REFUNDED;
        } catch (SQLException exception) {
            throw new CaseStoreException("failed to mark case opening refunded " + openingId, exception);
        }
    }

    @Override
    public List<CaseOpeningRow> recoverableOpenings(UUID ownerId) {
        // RESERVED/DEBITED/REFUNDED 仍需要恢复: SQLite 可能已把这些状态持久化, 而对应的 SavedData
        // complete/refund 迁移在硬崩溃时仍只脏在内存里。已结算 (economy_settled=1) 的 COMMITTED 行
        // 被排除在外, 因为它是永久终态: 结算锚落在开箱库自身, 与账本行是否被 EconomySystem 定期回收
        // 无关。把已结算的 COMMITTED 也留在这里, 曾经既是登录恢复集合无上界增长的成因,
        // 也是账本行被回收后重复扣款的载体。
        String sql = "SELECT * FROM case_openings WHERE owner_uuid=? "
                + "AND (status IN ('RESERVED','DEBITED','REFUNDED') "
                + "OR (status='COMMITTED' AND economy_settled=0)) "
                + "ORDER BY created_at ASC";
        List<CaseOpeningRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(opening(result));
                }
            }
            return rows;
        } catch (SQLException exception) {
            throw new CaseStoreException("failed to list recoverable openings for " + ownerId, exception);
        }
    }

    @Override
    public List<CaseOpeningRow> allRecoverableOpenings() {
        // RESERVED/DEBITED/REFUNDED 仍需要恢复: SQLite 可能已把这些状态持久化, 而对应的 SavedData
        // complete/refund 迁移在硬崩溃时仍只脏在内存里。已结算 (economy_settled=1) 的 COMMITTED 行
        // 被排除在外, 因为它是永久终态: 结算锚落在开箱库自身, 与账本行是否被 EconomySystem 定期回收
        // 无关。把已结算的 COMMITTED 也留在这里, 曾经既是登录恢复集合无上界增长的成因,
        // 也是账本行被回收后重复扣款的载体。
        String sql = "SELECT * FROM case_openings "
                + "WHERE status IN ('RESERVED','DEBITED','REFUNDED') "
                + "OR (status='COMMITTED' AND economy_settled=0) "
                + "ORDER BY created_at ASC";
        List<CaseOpeningRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                rows.add(opening(result));
            }
            return rows;
        } catch (SQLException exception) {
            throw new CaseStoreException("failed to list recoverable case openings", exception);
        }
    }

    @Override
    public boolean markQuarantined(UUID openingId, long updatedAt) {
        String sql = "UPDATE case_openings SET status='QUARANTINED',updated_at=? WHERE opening_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, updatedAt);
            statement.setString(2, openingId.toString());
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new CaseStoreException("failed to quarantine case opening " + openingId, exception);
        }
    }

    @Override
    public SkinAssetRow findAsset(UUID assetId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM skin_assets WHERE asset_id=?")) {
            statement.setString(1, assetId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? asset(result) : null;
            }
        } catch (SQLException exception) {
            throw new CaseStoreException("failed to find skin asset " + assetId, exception);
        }
    }

    @Override
    public SkinAssetRow findOwnedAsset(UUID ownerId, UUID assetId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM skin_assets WHERE asset_id=? AND owner_uuid=?")) {
            statement.setString(1, assetId.toString());
            statement.setString(2, ownerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? asset(result) : null;
            }
        } catch (SQLException exception) {
            throw new CaseStoreException("failed to find owned skin asset " + assetId, exception);
        }
    }

    @Override
    public List<SkinAssetRow> ownedAssets(UUID ownerId) {
        List<SkinAssetRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM skin_assets WHERE owner_uuid=? ORDER BY acquired_at DESC,asset_id ASC")) {
            statement.setString(1, ownerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(asset(result));
                }
            }
            return rows;
        } catch (SQLException exception) {
            throw new CaseStoreException("failed to list owned skin assets for " + ownerId, exception);
        }
    }

    @Override
    public boolean markEconomySettled(UUID openingId, long updatedAt) {
        // status='COMMITTED' 是刻意加的闸: 非 COMMITTED 行被标结算属于不可能状态, 必须让调用方看见
        // false 而不是静默写成功。
        String sql = "UPDATE case_openings SET economy_settled=1,updated_at=? "
                + "WHERE opening_id=? AND status='COMMITTED'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, updatedAt);
            statement.setString(2, openingId.toString());
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new CaseStoreException("failed to mark case opening economy settled " + openingId, exception);
        }
    }

    @Override
    public boolean isOpeningSettled(UUID openingId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT economy_settled FROM case_openings WHERE opening_id=?")) {
            statement.setString(1, openingId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    // skin_assets.source_opening_id 有指向 case_openings 的外键, 查不到即数据损坏,
                    // 必须响, 严禁返回 false 掩盖。
                    throw new CaseStoreException("case opening referenced by settled check is missing: " + openingId);
                }
                return result.getInt("economy_settled") != 0;
            }
        } catch (SQLException exception) {
            throw new CaseStoreException("failed to read economy settled flag for case opening " + openingId, exception);
        }
    }

    @Override
    public List<SkinAssetRow> settledOwnedAssets(UUID ownerId) {
        String sql = "SELECT a.* FROM skin_assets a JOIN case_openings o ON a.source_opening_id=o.opening_id "
                + "WHERE a.owner_uuid=? AND o.economy_settled=1 "
                + "ORDER BY a.acquired_at DESC,a.asset_id ASC";
        List<SkinAssetRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(asset(result));
                }
            }
            return rows;
        } catch (SQLException exception) {
            throw new CaseStoreException("failed to list settled owned skin assets for " + ownerId, exception);
        }
    }

    private void insertAsset(SkinAssetRow asset) throws SQLException {
        String sql = "INSERT OR IGNORE INTO skin_assets "
                + "(asset_id,owner_uuid,skin_id,rarity,gun_id,display_id,source_opening_id,acquired_at,trade_locked_until) "
                + "VALUES (?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, asset.assetId().toString());
            statement.setString(2, asset.ownerId().toString());
            statement.setString(3, asset.skinId());
            statement.setString(4, asset.rarity().name());
            statement.setString(5, asset.gunId());
            statement.setString(6, asset.displayId());
            statement.setString(7, asset.sourceOpeningId().toString());
            statement.setLong(8, asset.acquiredAt());
            statement.setLong(9, asset.tradeLockedUntil());
            statement.executeUpdate();
        }
        SkinAssetRow existing = findAsset(asset.assetId());
        if (existing == null || !existing.sourceOpeningId().equals(asset.sourceOpeningId())
                || !existing.ownerId().equals(asset.ownerId())) {
            throw new CaseStoreException("skin asset id collision for " + asset.assetId());
        }
    }

    private int updateStatus(UUID openingId, CaseOpeningStatus from, CaseOpeningStatus to, long updatedAt) {
        String sql = "UPDATE case_openings SET status=?,updated_at=? WHERE opening_id=? AND status=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, to.name());
            statement.setLong(2, updatedAt);
            statement.setString(3, openingId.toString());
            statement.setString(4, from.name());
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw new CaseStoreException("failed to move case opening " + openingId + " to " + to, exception);
        }
    }

    private static void bindOpening(PreparedStatement statement, CaseOpeningRow row) throws SQLException {
        statement.setString(1, row.openingId().toString());
        statement.setString(2, row.ownerId().toString());
        statement.setString(3, row.caseId());
        statement.setLong(4, row.creditCost());
        statement.setLong(5, row.azureCost());
        statement.setString(6, row.status().name());
        statement.setString(7, row.assetId().toString());
        statement.setString(8, row.skinId());
        statement.setString(9, row.rarity().name());
        statement.setString(10, row.gunId());
        statement.setString(11, row.displayId());
        statement.setString(12, row.reelJson());
        statement.setInt(13, row.stopIndex());
        statement.setLong(14, row.createdAt());
        statement.setLong(15, row.updatedAt());
    }

    private static CaseOpeningRow opening(ResultSet result) throws SQLException {
        return new CaseOpeningRow(
                UUID.fromString(result.getString("opening_id")),
                UUID.fromString(result.getString("owner_uuid")),
                result.getString("case_id"),
                result.getLong("credit_cost"),
                result.getLong("azure_cost"),
                CaseOpeningStatus.valueOf(result.getString("status")),
                UUID.fromString(result.getString("asset_id")),
                result.getString("skin_id"),
                CaseRarity.valueOf(result.getString("rarity")),
                result.getString("gun_id"),
                result.getString("display_id"),
                result.getString("reel_json"),
                result.getInt("stop_index"),
                result.getLong("created_at"),
                result.getLong("updated_at"));
    }

    private static SkinAssetRow asset(ResultSet result) throws SQLException {
        return new SkinAssetRow(
                UUID.fromString(result.getString("asset_id")),
                UUID.fromString(result.getString("owner_uuid")),
                result.getString("skin_id"),
                CaseRarity.valueOf(result.getString("rarity")),
                result.getString("gun_id"),
                result.getString("display_id"),
                UUID.fromString(result.getString("source_opening_id")),
                result.getLong("acquired_at"),
                result.getLong("trade_locked_until"));
    }

}

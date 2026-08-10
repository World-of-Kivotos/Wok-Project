package com.miningdim.caseopening.store;

import com.miningdim.caseopening.CaseRarity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Single-connection SQLite implementation. Each opening commit and ownership insert share one SQL transaction. */
public final class CaseDaoSqlite implements CaseDao {

    private final Connection connection;

    CaseDaoSqlite(Connection connection) {
        this.connection = connection;
    }

    @Override
    public void initSchema() {
        String openings = "CREATE TABLE IF NOT EXISTS case_openings ("
                + "opening_id TEXT PRIMARY KEY, "
                + "owner_uuid TEXT NOT NULL, "
                + "case_id TEXT NOT NULL, "
                + "credit_cost INTEGER NOT NULL, "
                + "azure_cost INTEGER NOT NULL, "
                + "status TEXT NOT NULL, "
                + "asset_id TEXT NOT NULL UNIQUE, "
                + "skin_id TEXT NOT NULL, "
                + "rarity TEXT NOT NULL, "
                + "gun_id TEXT NOT NULL, "
                + "display_id TEXT NOT NULL, "
                + "reel_json TEXT NOT NULL, "
                + "stop_index INTEGER NOT NULL, "
                + "created_at INTEGER NOT NULL, "
                + "updated_at INTEGER NOT NULL)";
        String assets = "CREATE TABLE IF NOT EXISTS skin_assets ("
                + "asset_id TEXT PRIMARY KEY, "
                + "owner_uuid TEXT NOT NULL, "
                + "skin_id TEXT NOT NULL, "
                + "rarity TEXT NOT NULL, "
                + "gun_id TEXT NOT NULL, "
                + "display_id TEXT NOT NULL, "
                + "source_opening_id TEXT NOT NULL UNIQUE, "
                + "acquired_at INTEGER NOT NULL, "
                + "trade_locked_until INTEGER NOT NULL, "
                + "FOREIGN KEY(source_opening_id) REFERENCES case_openings(opening_id))";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(openings);
            statement.executeUpdate(assets);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_case_openings_owner_status "
                    + "ON case_openings(owner_uuid,status)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_skin_assets_owner "
                    + "ON skin_assets(owner_uuid)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_skin_assets_owner_skin "
                    + "ON skin_assets(owner_uuid,skin_id)");
        } catch (SQLException exception) {
            throw new CaseStoreException("failed to initialize case ledger schema", exception);
        }
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
        boolean previousAutoCommit = autoCommit();
        try {
            connection.setAutoCommit(false);
            CaseOpeningRow opening = findOpening(openingId);
            if (opening == null) {
                throw new CaseStoreException("cannot commit missing case opening " + openingId);
            }
            if (opening.status() == CaseOpeningStatus.COMMITTED) {
                SkinAssetRow existing = findAsset(asset.assetId());
                if (existing == null) {
                    throw new CaseStoreException("committed opening has no asset " + openingId);
                }
                connection.commit();
                return existing;
            }
            if (opening.status() != CaseOpeningStatus.DEBITED) {
                throw new CaseStoreException("cannot commit opening " + openingId + " from " + opening.status());
            }
            insertAsset(asset);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE case_openings SET status='COMMITTED',updated_at=? "
                            + "WHERE opening_id=? AND status='DEBITED'")) {
                statement.setLong(1, updatedAt);
                statement.setString(2, openingId.toString());
                if (statement.executeUpdate() != 1) {
                    throw new CaseStoreException("case opening changed while committing " + openingId);
                }
            }
            connection.commit();
            return asset;
        } catch (SQLException | RuntimeException exception) {
            rollbackQuietly();
            if (exception instanceof CaseStoreException storeException) {
                throw storeException;
            }
            throw new CaseStoreException("failed to commit case opening " + openingId, exception);
        } finally {
            restoreAutoCommit(previousAutoCommit);
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
        // COMMITTED/REFUNDED are included deliberately: SQLite may have persisted its terminal state while the
        // corresponding SavedData complete/refund transition was still only dirty in memory at a hard crash.
        String sql = "SELECT * FROM case_openings WHERE owner_uuid=? "
                + "AND status IN ('RESERVED','DEBITED','COMMITTED','REFUNDED') "
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
    public void close() {
        try {
            connection.close();
        } catch (SQLException exception) {
            throw new CaseStoreException("failed to close case ledger", exception);
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

    private boolean autoCommit() {
        try {
            return connection.getAutoCommit();
        } catch (SQLException exception) {
            throw new CaseStoreException("failed to read case ledger transaction state", exception);
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original failure remains the useful exception at this boundary.
        }
    }

    private void restoreAutoCommit(boolean autoCommit) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException exception) {
            throw new CaseStoreException("failed to restore case ledger transaction state", exception);
        }
    }
}

package com.miningdim.economy;

import com.miningdim.store.MiningStoreException;
import com.miningdim.store.StoreMeta;
import com.miningdim.store.StoreTx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

/**
 * 把旧存档 SavedData 里的余额、双币操作与每日计数一次性搬进统一 SQLite。
 *
 * 搬迁在单个事务内完成, 完成后逐项核对: 钱包条数、每个玩家的两种余额、两种货币的总量、操作条数、
 * 计数条数。任一项对不上就抛异常回滚并拒绝启动 —— 钱少了却把服开起来, 之后再也说不清少在哪。
 *
 * 只搬一次: 完成后在 meta 表打标记。旧的 .dat 文件保留不动作回滚保险 ({@link EconomyWalletData} 迁移后
 * 只读且永不标脏, Minecraft 不会再覆盖它)。
 */
public final class EconomyLedgerBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/economy");

    /** 迁移完成标记的 meta 键。 */
    static final String META_WALLETS_IMPORTED = "imported_wallets_at";

    private static final String KIND_CHARGE = "CHARGE";
    private static final String KIND_FAUCET = "FAUCET";

    private EconomyLedgerBootstrap() {
    }

    /**
     * 若尚未迁移过则执行迁移。
     *
     * @param conn      已完成 schema 迁移的统一库连接
     * @param legacy    旧存档账本 (可以是空账本, 表示这个世界从未有过旧数据)
     * @param nowMillis 迁移时刻, 写进标记供事后追溯
     */
    public static void migrateIfNeeded(Connection conn, EconomyWalletData legacy, long nowMillis) {
        if (StoreMeta.get(conn, META_WALLETS_IMPORTED) != null) {
            return;
        }
        if (legacy.isEmpty()) {
            // 全新世界: 无旧数据可搬, 直接打标记, 免得每次启动都重读一遍存档。
            StoreTx.run(conn, () -> StoreMeta.put(conn, META_WALLETS_IMPORTED, Long.toString(nowMillis)));
            return;
        }
        assertTargetEmpty(conn);

        StoreTx.run(conn, () -> {
            for (Map.Entry<UUID, PlayerWallet> entry : legacy.wallets().entrySet()) {
                insertWallet(conn, entry.getKey(), entry.getValue());
            }
            for (EconomyWalletData.LegacyOperation operation : legacy.operations()) {
                insertOperation(conn, operation, nowMillis);
            }
            for (EconomyWalletData.LegacyDailyCounter counter : legacy.dailyCharges()) {
                insertCounter(conn, counter, KIND_CHARGE);
            }
            for (EconomyWalletData.LegacyDailyCounter counter : legacy.dailyFaucets()) {
                insertCounter(conn, counter, KIND_FAUCET);
            }
            reconcile(conn, legacy);
            StoreMeta.put(conn, META_WALLETS_IMPORTED, Long.toString(nowMillis));
        });

        LOGGER.info("[miningdim] 经济账本已迁入统一 SQLite: {} 个钱包 / {} 笔双币操作 / {} 条每日计数",
                legacy.wallets().size(), legacy.operations().size(),
                legacy.dailyCharges().size() + legacy.dailyFaucets().size());
    }

    /**
     * 目标表必须全空。非空说明 SQLite 侧已经在记账, 而旧存档里还有数据且没有迁移标记 —— 两份账本各自
     * 都可能是权威, 自动合并只会让余额凭空变多或变少, 只能拒绝启动交人核对。
     */
    private static void assertTargetEmpty(Connection conn) {
        for (String table : new String[]{"wallets", "bundle_operations", "daily_counters"}) {
            long rows = countRows(conn, table);
            if (rows != 0L) {
                throw new MiningStoreException("旧存档仍有经济数据未迁移, 但统一库的 " + table
                        + " 已有 " + rows + " 行且无迁移标记; 无法判定哪份账本权威, 拒绝启动");
            }
        }
    }

    /** 逐项核对搬迁结果; 任一项不符即抛, 由外层事务整体回滚。 */
    private static void reconcile(Connection conn, EconomyWalletData legacy) {
        long expectedWallets = legacy.wallets().size();
        long actualWallets = countRows(conn, "wallets");
        if (expectedWallets != actualWallets) {
            throw new MiningStoreException("钱包条数不一致: 旧存档 " + expectedWallets
                    + " 个, 新库 " + actualWallets + " 个");
        }
        long expectedCreditTotal = 0L;
        long expectedAzureTotal = 0L;
        for (Map.Entry<UUID, PlayerWallet> entry : legacy.wallets().entrySet()) {
            long credit = entry.getValue().balance(Currency.CREDIT);
            long azure = entry.getValue().balance(Currency.AZURE);
            expectedCreditTotal += credit;
            expectedAzureTotal += azure;
            long[] stored = readWallet(conn, entry.getKey());
            if (stored == null || stored[0] != credit || stored[1] != azure) {
                throw new MiningStoreException("玩家 " + entry.getKey() + " 余额不一致: 旧存档 "
                        + credit + "/" + azure + ", 新库 "
                        + (stored == null ? "无记录" : stored[0] + "/" + stored[1]));
            }
        }
        long creditTotal = sum(conn, "SELECT COALESCE(SUM(credit), 0) FROM wallets");
        long azureTotal = sum(conn, "SELECT COALESCE(SUM(azure), 0) FROM wallets");
        if (creditTotal != expectedCreditTotal || azureTotal != expectedAzureTotal) {
            throw new MiningStoreException("货币总量不一致: 旧存档 " + expectedCreditTotal + " CREDIT / "
                    + expectedAzureTotal + " AZURE, 新库 " + creditTotal + " / " + azureTotal);
        }
        long expectedOperations = legacy.operations().size();
        long actualOperations = countRows(conn, "bundle_operations");
        if (expectedOperations != actualOperations) {
            throw new MiningStoreException("双币操作条数不一致: 旧存档 " + expectedOperations
                    + " 笔, 新库 " + actualOperations + " 笔");
        }
        long expectedCounters = legacy.dailyCharges().size() + legacy.dailyFaucets().size();
        long actualCounters = countRows(conn, "daily_counters");
        if (expectedCounters != actualCounters) {
            throw new MiningStoreException("每日计数条数不一致: 旧存档 " + expectedCounters
                    + " 条, 新库 " + actualCounters + " 条");
        }
    }

    private static void insertWallet(Connection conn, UUID playerId, PlayerWallet wallet) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO wallets (player_id, credit, azure) VALUES (?, ?, ?)")) {
            ps.setString(1, playerId.toString());
            ps.setLong(2, wallet.balance(Currency.CREDIT));
            ps.setLong(3, wallet.balance(Currency.AZURE));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MiningStoreException("迁移钱包失败: " + playerId, e);
        }
    }

    private static void insertOperation(Connection conn, EconomyWalletData.LegacyOperation operation,
                                        long createdAt) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO bundle_operations "
                        + "(operation_id, domain, player_id, credit_amount, azure_amount, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, operation.operationId().toString());
            ps.setString(2, operation.domain().id());
            ps.setString(3, operation.playerId().toString());
            ps.setLong(4, operation.creditAmount());
            ps.setLong(5, operation.azureAmount());
            ps.setString(6, operation.status().name());
            // 旧结构没有时间戳, 只能以迁移时刻充当下限; 它的用途是终态回收与审计定位, 不参与业务判定。
            ps.setLong(7, createdAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MiningStoreException("迁移双币操作失败: " + operation.operationId(), e);
        }
    }

    private static void insertCounter(Connection conn, EconomyWalletData.LegacyDailyCounter counter,
                                      String kind) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO daily_counters "
                        + "(player_id, counter_key, kind, amount, day_stamp, credit_carry) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, counter.playerId().toString());
            ps.setString(2, counter.counterKey());
            ps.setString(3, kind);
            ps.setLong(4, counter.amount());
            ps.setLong(5, counter.dayStamp());
            ps.setDouble(6, counter.creditCarry());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MiningStoreException("迁移每日计数失败: " + counter.playerId()
                    + "|" + counter.counterKey(), e);
        }
    }

    /** 返回 [credit, azure]; 无记录返 null。 */
    private static long[] readWallet(Connection conn, UUID playerId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT credit, azure FROM wallets WHERE player_id=?")) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new long[]{rs.getLong(1), rs.getLong(2)} : null;
            }
        } catch (SQLException e) {
            throw new MiningStoreException("核对钱包失败: " + playerId, e);
        }
    }

    private static long countRows(Connection conn, String table) {
        return sum(conn, "SELECT COUNT(*) FROM " + table);
    }

    private static long sum(Connection conn, String sql) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) {
                throw new MiningStoreException("聚合查询无结果: " + sql);
            }
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new MiningStoreException("聚合查询失败: " + sql, e);
        }
    }
}

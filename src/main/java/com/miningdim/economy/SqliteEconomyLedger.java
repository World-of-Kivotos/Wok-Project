package com.miningdim.economy;

import com.miningdim.store.MiningDb;
import com.miningdim.store.MiningSchema;
import com.miningdim.store.MiningStoreException;
import com.miningdim.store.StoreTx;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link EconomyLedger} 的 SQLite 实现, 建在全服唯一连接上。
 *
 * 金额规则不在 SQL 里重写: 正数校验、余额充足判定、{@link Math#addExact} 溢出检出全部仍由
 * {@link PlayerWallet} 承担, 本类只负责把钱包读出来、交给它算、再写回去。把 "credit >= ?" 之类的条件
 * 搬进 SQL 文本会让同一套货币不变量在 Java 与 SQL 两处各有一份, 改一处漏一处的代价是真钱。
 * 读-算-写在此处是安全的: Minecraft 服务端逻辑单线程, 全服单连接单写者, 且读与写在同一个事务内,
 * 中间不可能有别的写者插进来。这个前提一旦变化 (多线程写账本), 本类必须重做。
 *
 * 事务: 凡是"改余额 + 改账本条目"的复合操作都裹在 {@link StoreTx} 里; 若调用方已开事务则并入其中,
 * 提交权归最外层。
 */
public final class SqliteEconomyLedger implements EconomyLedger {

    /** 每日计数的种类: 扣费侧。 */
    private static final String KIND_CHARGE = "CHARGE";
    /** 每日计数的种类: faucet 侧 (带小数余量 carry)。 */
    private static final String KIND_FAUCET = "FAUCET";

    private final Connection conn;

    public SqliteEconomyLedger(Connection conn) {
        if (conn == null) {
            throw new IllegalArgumentException("SqliteEconomyLedger requires a non-null Connection");
        }
        this.conn = conn;
    }

    /**
     * GameTest 专用: 在一条独立的内存统一库上建账本。
     * 内存库随连接存活, 用例之间互不污染; 连接随进程结束释放。需要与市场/开箱共用同一事务的用例
     * 应改为自己开一条 {@link MiningDb#openInMemory()} 连接、{@link MiningSchema#apply} 后注入本类。
     */
    public static SqliteEconomyLedger openInMemory() {
        Connection conn = MiningDb.openInMemory();
        MiningSchema.apply(conn);
        return new SqliteEconomyLedger(conn);
    }

    // ---- 余额 ----

    @Override
    public long balance(UUID playerId, Currency currency) {
        return loadWallet(playerId).balance(currency);
    }

    @Override
    public boolean tryDebit(UUID playerId, Currency currency, long amount) {
        return StoreTx.call(conn, () -> {
            PlayerWallet wallet = loadWallet(playerId);
            if (!wallet.tryDebit(currency, amount)) {
                return false;
            }
            saveWallet(playerId, wallet);
            return true;
        });
    }

    @Override
    public void credit(UUID playerId, Currency currency, long amount) {
        StoreTx.run(conn, () -> {
            PlayerWallet wallet = loadWallet(playerId);
            wallet.credit(currency, amount);
            saveWallet(playerId, wallet);
        });
    }

    @Override
    public void creditBundle(UUID playerId, long creditAmount, long azureAmount) {
        StoreTx.run(conn, () -> {
            PlayerWallet wallet = loadWallet(playerId);
            wallet.creditBundle(creditAmount, azureAmount);
            saveWallet(playerId, wallet);
        });
    }

    // ---- 双币幂等操作 ----

    @Override
    public EconomyOperationStatus tryChargeBundle(EconomyOperationDomain domain, UUID playerId, UUID operationId,
                                                  long creditAmount, long azureAmount) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(operationId, "operationId");
        requireBundleAmounts(creditAmount, azureAmount);

        return StoreTx.call(conn, () -> {
            BundleOperation existing = findOperation(operationId);
            if (existing != null) {
                if (!existing.matches(domain, playerId, creditAmount, azureAmount)) {
                    throw operationConflict(operationId);
                }
                return existing.status();
            }
            PlayerWallet wallet = loadWallet(playerId);
            if (!wallet.tryDebitBundle(creditAmount, azureAmount)) {
                return EconomyOperationStatus.NONE;
            }
            saveWallet(playerId, wallet);
            insertOperation(new BundleOperation(operationId, domain, playerId, creditAmount, azureAmount,
                    EconomyOperationStatus.CHARGED), System.currentTimeMillis());
            return EconomyOperationStatus.CHARGED;
        });
    }

    @Override
    public EconomyOperationStatus operationStatus(EconomyOperationDomain domain, UUID playerId, UUID operationId) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(operationId, "operationId");
        BundleOperation operation = findOperation(operationId);
        return operation != null && operation.belongsTo(domain, playerId)
                ? operation.status()
                : EconomyOperationStatus.NONE;
    }

    @Override
    public EconomyOperationStatus completeBundle(EconomyOperationDomain domain, UUID playerId, UUID operationId) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(operationId, "operationId");
        return StoreTx.call(conn, () -> {
            BundleOperation operation = findOperation(operationId);
            if (operation == null || !operation.belongsTo(domain, playerId)) {
                return EconomyOperationStatus.NONE;
            }
            if (operation.status() == EconomyOperationStatus.CHARGED) {
                updateOperationStatus(operationId, EconomyOperationStatus.COMPLETED);
                return EconomyOperationStatus.COMPLETED;
            }
            return operation.status();
        });
    }

    @Override
    public EconomyOperationStatus refundBundle(EconomyOperationDomain domain, UUID playerId, UUID operationId) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(operationId, "operationId");
        return StoreTx.call(conn, () -> {
            BundleOperation operation = findOperation(operationId);
            if (operation == null || !operation.belongsTo(domain, playerId)) {
                return EconomyOperationStatus.NONE;
            }
            if (operation.status() != EconomyOperationStatus.CHARGED) {
                return operation.status();
            }
            PlayerWallet wallet = loadWallet(playerId);
            // 溢出时 creditBundle 抛出, 整个事务回滚, 状态与两币一并保持不变。
            wallet.creditBundle(operation.creditAmount(), operation.azureAmount());
            saveWallet(playerId, wallet);
            updateOperationStatus(operationId, EconomyOperationStatus.REFUNDED);
            return EconomyOperationStatus.REFUNDED;
        });
    }

    // ---- 每日计数 ----

    @Override
    public boolean tryChargeDaily(UUID playerId, Currency currency, long amount,
                                  String dailyKey, long dailyCap, long todayStamp) {
        if (amount <= 0L) {
            throw new EconomyException(EconomyException.Reason.ILLEGAL_AMOUNT,
                    "daily charge amount must be > 0, got " + amount);
        }
        return StoreTx.call(conn, () -> {
            DailyCounter counter = findCounter(playerId, dailyKey, KIND_CHARGE);
            long spentToday = (counter == null || counter.dayStamp() != todayStamp) ? 0L : counter.amount();
            if (spentToday + amount > dailyCap) {
                return false;
            }
            // 先扣余额 (先校验后扣); 余额不足则不计每日计数。
            PlayerWallet wallet = loadWallet(playerId);
            if (!wallet.tryDebit(currency, amount)) {
                return false;
            }
            saveWallet(playerId, wallet);
            putCounter(playerId, dailyKey, KIND_CHARGE, spentToday + amount, todayStamp, 0.0D);
            return true;
        });
    }

    @Override
    public long recordFaucetGrant(UUID playerId, String faucetKey, long rawAmount, long todayStamp) {
        if (rawAmount <= 0L) {
            throw new EconomyException(EconomyException.Reason.ILLEGAL_AMOUNT,
                    "faucet grant amount must be > 0, got " + rawAmount);
        }
        return StoreTx.call(conn, () -> {
            DailyCounter counter = findCounter(playerId, faucetKey, KIND_FAUCET);
            boolean newDay = counter == null || counter.dayStamp() != todayStamp;
            long before = newDay ? 0L : counter.amount();
            // 翻日: 重置累计与 carry; 同日: 累加 raw, carry 原样保留 (carry 由 creditFaucetWithCarry 推进)。
            double carry = newDay ? 0.0D : counter.creditCarry();
            putCounter(playerId, faucetKey, KIND_FAUCET, before + rawAmount, todayStamp, carry);
            return before;
        });
    }

    @Override
    public long creditFaucetWithCarry(UUID playerId, String faucetKey, double exactEffective, long todayStamp) {
        if (exactEffective < 0.0D) {
            throw new EconomyException(EconomyException.Reason.ILLEGAL_AMOUNT,
                    "faucet exactEffective must be >= 0, got " + exactEffective);
        }
        return StoreTx.call(conn, () -> {
            DailyCounter counter = findCounter(playerId, faucetKey, KIND_FAUCET);
            boolean sameDay = counter != null && counter.dayStamp() == todayStamp;
            double carryBefore = sameDay ? counter.creditCarry() : 0.0D;
            double pooled = carryBefore + exactEffective;
            long payout = (long) Math.floor(pooled);
            double carryAfter = pooled - payout;
            // 同日条目只推进 carry, 原始累计 amount 归 recordFaucetGrant 专管; 极端时序下无前置条目则以 0 建条目。
            long amount = sameDay ? counter.amount() : 0L;
            putCounter(playerId, faucetKey, KIND_FAUCET, amount, todayStamp, carryAfter);
            return payout;
        });
    }

    @Override
    public long creditAzureDaily(UUID playerId, String faucetKey, long amount, long dailyCap, long todayStamp) {
        if (amount <= 0L) {
            throw new EconomyException(EconomyException.Reason.ILLEGAL_AMOUNT,
                    "azure daily faucet amount must be > 0, got " + amount);
        }
        if (dailyCap <= 0L) {
            throw new EconomyException(EconomyException.Reason.ILLEGAL_AMOUNT,
                    "azure daily faucet cap must be > 0, got " + dailyCap);
        }
        return StoreTx.call(conn, () -> {
            DailyCounter counter = findCounter(playerId, faucetKey, KIND_FAUCET);
            boolean newDay = counter == null || counter.dayStamp() != todayStamp;
            long grantedToday = newDay ? 0L : counter.amount();
            long room = dailyCap - grantedToday;
            if (room <= 0L) {
                return 0L; // 当日已撞上限: 本批全截断, 无状态变更。
            }
            long credited = Math.min(amount, room);
            putCounter(playerId, faucetKey, KIND_FAUCET, grantedToday + credited, todayStamp, 0.0D);
            credit(playerId, Currency.AZURE, credited);
            return credited;
        });
    }

    // ---- 钱包表读写 ----

    private PlayerWallet loadWallet(UUID playerId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT credit, azure FROM wallets WHERE player_id=?")) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                // 无记录的玩家等价于余额 0 (新玩家), 不建行。
                return rs.next() ? PlayerWallet.of(rs.getLong(1), rs.getLong(2)) : PlayerWallet.of(0L, 0L);
            }
        } catch (SQLException e) {
            throw new MiningStoreException("读取钱包失败: " + playerId, e);
        }
    }

    private void saveWallet(UUID playerId, PlayerWallet wallet) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO wallets (player_id, credit, azure) VALUES (?, ?, ?) "
                        + "ON CONFLICT(player_id) DO UPDATE SET credit=excluded.credit, azure=excluded.azure")) {
            ps.setString(1, playerId.toString());
            ps.setLong(2, wallet.balance(Currency.CREDIT));
            ps.setLong(3, wallet.balance(Currency.AZURE));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MiningStoreException("写入钱包失败: " + playerId, e);
        }
    }

    // ---- 双币操作表读写 ----

    /** 一笔可恢复的双币扣款; operationId 在全服账本内唯一。 */
    private record BundleOperation(UUID operationId, EconomyOperationDomain domain, UUID playerId,
                                   long creditAmount, long azureAmount, EconomyOperationStatus status) {

        /**
         * 幂等判定必须比对完整元组。只比 playerId 会让不同业务共用同一 operationId 时互相串号 ——
         * 一笔业务的"已付款事实"被另一笔业务当成自己的付款凭据, 从而跳过扣款。
         */
        boolean matches(EconomyOperationDomain expectedDomain, UUID expectedPlayerId,
                        long expectedCredit, long expectedAzure) {
            return domain == expectedDomain
                    && playerId.equals(expectedPlayerId)
                    && creditAmount == expectedCredit
                    && azureAmount == expectedAzure;
        }

        /** 状态查询与推进只认域与归属; 金额比对留给 charge, 以便金额不符时抛冲突而非静默放行。 */
        boolean belongsTo(EconomyOperationDomain expectedDomain, UUID expectedPlayerId) {
            return domain == expectedDomain && playerId.equals(expectedPlayerId);
        }
    }

    private BundleOperation findOperation(UUID operationId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT domain, player_id, credit_amount, azure_amount, status "
                        + "FROM bundle_operations WHERE operation_id=?")) {
            ps.setString(1, operationId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new BundleOperation(operationId,
                        EconomyOperationDomain.valueOf(rs.getString(1)),
                        UUID.fromString(rs.getString(2)),
                        rs.getLong(3),
                        rs.getLong(4),
                        EconomyOperationStatus.valueOf(rs.getString(5)));
            }
        } catch (SQLException e) {
            throw new MiningStoreException("读取双币操作失败: " + operationId, e);
        }
    }

    private void insertOperation(BundleOperation operation, long createdAt) {
        if (operation.status() == EconomyOperationStatus.NONE) {
            throw new IllegalArgumentException("NONE must not be persisted as a bundle operation");
        }
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
            ps.setLong(7, createdAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MiningStoreException("写入双币操作失败: " + operation.operationId(), e);
        }
    }

    private void updateOperationStatus(UUID operationId, EconomyOperationStatus status) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE bundle_operations SET status=? WHERE operation_id=?")) {
            ps.setString(1, status.name());
            ps.setString(2, operationId.toString());
            if (ps.executeUpdate() != 1) {
                // 调用方刚在同一事务内读到过这一行, 更新影响 0 行意味着表被并发改写, 属不可能状态。
                throw new MiningStoreException("推进双币操作状态时行已消失: " + operationId);
            }
        } catch (SQLException e) {
            throw new MiningStoreException("更新双币操作状态失败: " + operationId, e);
        }
    }

    // ---- 每日计数表读写 ----

    /** 单条每日计数: 当日累计 + 所属 UTC 日戳 + faucet 侧的小数余量。 */
    private record DailyCounter(long amount, long dayStamp, double creditCarry) {
    }

    private DailyCounter findCounter(UUID playerId, String counterKey, String kind) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT amount, day_stamp, credit_carry FROM daily_counters "
                        + "WHERE player_id=? AND counter_key=? AND kind=?")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, counterKey);
            ps.setString(3, kind);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new DailyCounter(rs.getLong(1), rs.getLong(2), rs.getDouble(3)) : null;
            }
        } catch (SQLException e) {
            throw new MiningStoreException("读取每日计数失败: " + playerId + "|" + counterKey, e);
        }
    }

    private void putCounter(UUID playerId, String counterKey, String kind,
                            long amount, long dayStamp, double creditCarry) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO daily_counters (player_id, counter_key, kind, amount, day_stamp, credit_carry) "
                        + "VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(player_id, counter_key, kind) DO UPDATE SET "
                        + "amount=excluded.amount, day_stamp=excluded.day_stamp, credit_carry=excluded.credit_carry")) {
            ps.setString(1, playerId.toString());
            ps.setString(2, counterKey);
            ps.setString(3, kind);
            ps.setLong(4, amount);
            ps.setLong(5, dayStamp);
            ps.setDouble(6, creditCarry);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MiningStoreException("写入每日计数失败: " + playerId + "|" + counterKey, e);
        }
    }

    private static void requireBundleAmounts(long creditAmount, long azureAmount) {
        if (creditAmount <= 0L || azureAmount <= 0L) {
            throw new EconomyException(EconomyException.Reason.ILLEGAL_AMOUNT,
                    "bundle amounts must both be > 0, got " + creditAmount + " CREDIT / " + azureAmount + " AZURE");
        }
    }

    private static EconomyException operationConflict(UUID operationId) {
        return new EconomyException(EconomyException.Reason.OPERATION_CONFLICT,
                "operationId " + operationId + " was reused with a different player or amount");
    }
}

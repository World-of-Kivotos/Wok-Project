package com.miningdim.market.store;

import com.miningdim.store.StoreTx;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@link MarketDao} 的 SQLite 实现 (契约第 4 节)。持单条 {@link Connection} —— MC 服务端逻辑单线程,
 * 单写者契合 SQLite, 故全程复用同一连接, 不引连接池 (YAGNI)。连接生命周期由 {@link MarketDb} 管 (open/close)。
 *
 * 异常纪律 (契约第 0/4 节): 每个方法在 SQL 操作外层 catch {@link SQLException}, 包装成
 * {@link MarketStoreException} 重抛 (保留 cause), 这是契约允许的"资源边界包装重抛", 不是吞异常。
 * try-with-resources 关 PreparedStatement/ResultSet (资源回收, 与吞异常无关)。
 *
 * 只用 {@code java.sql.*} (JDK 自带, 编译期不依赖 sqlite-jdbc jar); 运行期驱动经 DriverManager 反射加载
 * (jarJar 打包 org.xerial:sqlite-jdbc, 由 C 负责)。UUID 以 toString 文本存 (TEXT 列), 读回 fromString。
 */
public final class MarketDaoSqlite implements MarketDao {

    private final Connection conn;

    public MarketDaoSqlite(Connection conn) {
        if (conn == null) {
            throw new IllegalArgumentException("MarketDaoSqlite requires a non-null Connection");
        }
        this.conn = conn;
    }

    /**
     * 暴露底层连接给同包 {@link MarketDb} 做生命周期关闭 (契约第 2 节: 连接由 MarketDb open/close 编排)。
     * 包级可见, 不进 {@link MarketDao} 接口 —— 业务层 (B) 只经接口操作, 不直接碰连接。
     */
    Connection connection() {
        return conn;
    }

    // ---- listings ----

    @Override
    public long insertListing(UUID seller, String sellerName, String itemId, byte[] nbt,
                              int count, long unitPrice, String currency, long createdAt) {
        final String sql =
                "INSERT INTO listings "
                        + "(seller_uuid, seller_name, item_id, item_nbt, count, unit_price, currency, created_at, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, seller.toString());
            ps.setString(2, sellerName);
            ps.setString(3, itemId);
            ps.setBytes(4, nbt);
            ps.setInt(5, count);
            ps.setLong(6, unitPrice);
            ps.setString(7, currency);
            ps.setLong(8, createdAt);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    // INSERT 成功必有自增键; 取不到 = 驱动/表结构异常, 自然冒泡不静默返 0。
                    throw new MarketStoreException("insertListing did not return a generated id", null);
                }
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new MarketStoreException("insertListing failed", e);
        }
    }

    @Override
    public ListingRow findListing(long id) {
        final String sql =
                "SELECT id, seller_uuid, seller_name, item_id, item_nbt, count, unit_price, currency, created_at, status "
                        + "FROM listings WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return mapListing(rs);
            }
        } catch (SQLException e) {
            throw new MarketStoreException("findListing failed for id " + id, e);
        }
    }

    @Override
    public List<ListingRow> queryActive(String itemFilterOrNull, String sortKey, int offset, int limit) {
        // ORDER BY 不能参数化, 故白名单映射 sortKey 防注入; 非法/缺省落 newest。
        final String orderBy = orderByClause(sortKey);
        boolean hasFilter = itemFilterOrNull != null && !itemFilterOrNull.isEmpty();
        StringBuilder sql = new StringBuilder(
                "SELECT id, seller_uuid, seller_name, item_id, item_nbt, count, unit_price, currency, created_at, status "
                        + "FROM listings WHERE status = 'ACTIVE'");
        if (hasFilter) {
            sql.append(" AND item_id LIKE ?");
        }
        sql.append(" ORDER BY ").append(orderBy).append(" LIMIT ? OFFSET ?");
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int p = 1;
            if (hasFilter) {
                ps.setString(p++, "%" + itemFilterOrNull + "%");
            }
            ps.setInt(p++, limit);
            ps.setInt(p, offset);
            try (ResultSet rs = ps.executeQuery()) {
                List<ListingRow> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapListing(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new MarketStoreException("queryActive failed", e);
        }
    }

    @Override
    public List<ListingRow> listingsBySeller(UUID seller, String statusOrNull) {
        boolean hasStatus = statusOrNull != null && !statusOrNull.isEmpty();
        StringBuilder sql = new StringBuilder(
                "SELECT id, seller_uuid, seller_name, item_id, item_nbt, count, unit_price, currency, created_at, status "
                        + "FROM listings WHERE seller_uuid = ?");
        if (hasStatus) {
            sql.append(" AND status = ?");
        }
        sql.append(" ORDER BY created_at DESC");
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setString(1, seller.toString());
            if (hasStatus) {
                ps.setString(2, statusOrNull);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<ListingRow> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapListing(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new MarketStoreException("listingsBySeller failed", e);
        }
    }

    @Override
    public boolean markSold(long id) {
        // 条件 UPDATE: 仅 ACTIVE 行可转 SOLD; 受影响行 = 0 表示已被并发抢/非 ACTIVE (供 B rollback+退款)。
        final String sql = "UPDATE listings SET status = 'SOLD' WHERE id = ? AND status = 'ACTIVE'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new MarketStoreException("markSold failed for id " + id, e);
        }
    }

    @Override
    public boolean markCancelled(long id, UUID seller) {
        // 条件 UPDATE: 同时校验本人 (seller_uuid) 与 ACTIVE 状态, 越权/非 ACTIVE 受影响行为 0。
        final String sql =
                "UPDATE listings SET status = 'CANCELLED' WHERE id = ? AND seller_uuid = ? AND status = 'ACTIVE'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setString(2, seller.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new MarketStoreException("markCancelled failed for id " + id, e);
        }
    }

    @Override
    public boolean reduceListing(long id, int newCount, byte[] newNbt) {
        // 部分买入拆分: 改剩余 count + 同步余量托管 NBT, 条件 status=ACTIVE (并发防御)。
        final String sql = "UPDATE listings SET count = ?, item_nbt = ? WHERE id = ? AND status = 'ACTIVE'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, newCount);
            ps.setBytes(2, newNbt);
            ps.setLong(3, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new MarketStoreException("reduceListing failed for id " + id, e);
        }
    }

    // ---- transactions ----

    @Override
    public void insertTxn(long listingId, UUID buyer, UUID seller, String itemId, int count,
                          long unitPrice, long total, long fee, long createdAt) {
        final String sql =
                "INSERT INTO transactions "
                        + "(listing_id, buyer_uuid, seller_uuid, item_id, count, unit_price, total, fee, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, listingId);
            ps.setString(2, buyer.toString());
            ps.setString(3, seller.toString());
            ps.setString(4, itemId);
            ps.setInt(5, count);
            ps.setLong(6, unitPrice);
            ps.setLong(7, total);
            ps.setLong(8, fee);
            ps.setLong(9, createdAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MarketStoreException("insertTxn failed", e);
        }
    }

    @Override
    public int soldOrListedCountToday(UUID seller, Set<String> itemIds, long dayStartEpoch) {
        // 铜铁日 cap (契约第 5 节): 今日该卖家这些 item 的 (ACTIVE listing.count 之和 + 今日 SOLD txn.count 之和)。
        // 空集合无可统计的 item, 直接返 0 (也避免拼出空 IN () 的非法 SQL)。
        if (itemIds.isEmpty()) {
            return 0;
        }
        String placeholders = placeholders(itemIds.size());
        // listing 侧: 当前 ACTIVE 且属铜铁集的挂单量 (托管中, 计入今日 P2P 投放). 不限 created_at —— ACTIVE 即占用额度。
        String listedSql =
                "SELECT COALESCE(SUM(count), 0) FROM listings "
                        + "WHERE seller_uuid = ? AND status = 'ACTIVE' AND item_id IN (" + placeholders + ")";
        // 成交侧: 今日 (created_at >= dayStartEpoch) 卖家已售出的铜铁量 (已落 SOLD, listing 行已不再计入 listed 侧)。
        String soldSql =
                "SELECT COALESCE(SUM(count), 0) FROM transactions "
                        + "WHERE seller_uuid = ? AND created_at >= ? AND item_id IN (" + placeholders + ")";
        try {
            int listed = sumCount(listedSql, seller, itemIds, null);
            int sold = sumCount(soldSql, seller, itemIds, dayStartEpoch);
            return listed + sold;
        } catch (SQLException e) {
            throw new MarketStoreException("soldOrListedCountToday failed", e);
        }
    }

    // ---- pending_payout ----

    @Override
    public void insertPendingPayout(UUID seller, long amount, String currency, long createdAt) {
        final String sql =
                "INSERT INTO pending_payout (seller_uuid, amount, currency, created_at) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, seller.toString());
            ps.setLong(2, amount);
            ps.setString(3, currency);
            ps.setLong(4, createdAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MarketStoreException("insertPendingPayout failed", e);
        }
    }

    @Override
    public List<long[]> drainPendingPayout(UUID seller) {
        // 取+删原子 (契约第 4 节): 先 SELECT 出全部待结金额, 再 DELETE 该卖家全部行, 同一事务提交。
        // 事务经 StoreTx: 连接现在是全服共享的, 若调用方已开着事务, 本方法必须并入而不是提前 commit 掉外层。
        final String selectSql = "SELECT amount FROM pending_payout WHERE seller_uuid = ?";
        final String deleteSql = "DELETE FROM pending_payout WHERE seller_uuid = ?";
        return StoreTx.call(conn, () -> {
            List<long[]> out = new ArrayList<>();
            try (PreparedStatement sel = conn.prepareStatement(selectSql)) {
                sel.setString(1, seller.toString());
                try (ResultSet rs = sel.executeQuery()) {
                    while (rs.next()) {
                        out.add(new long[]{rs.getLong(1)});
                    }
                }
                try (PreparedStatement del = conn.prepareStatement(deleteSql)) {
                    del.setString(1, seller.toString());
                    del.executeUpdate();
                }
            } catch (SQLException e) {
                throw new MarketStoreException("drainPendingPayout failed", e);
            }
            return out;
        });
    }

    // ---- base_values (V0 admin 覆盖) ----

    @Override
    public void upsertBaseValue(String itemId, long v0, String updatedBy, long updatedAt) {
        final String sql =
                "INSERT OR REPLACE INTO base_values (item_id, v0, updated_by, updated_at) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, itemId);
            ps.setLong(2, v0);
            ps.setString(3, updatedBy);
            ps.setLong(4, updatedAt);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MarketStoreException("upsertBaseValue failed for " + itemId, e);
        }
    }

    @Override
    public Long getBaseValue(String itemId) {
        final String sql = "SELECT v0 FROM base_values WHERE item_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        } catch (SQLException e) {
            throw new MarketStoreException("getBaseValue failed for " + itemId, e);
        }
    }

    @Override
    public java.util.Map<String, Long> allBaseValues() {
        final String sql = "SELECT item_id, v0 FROM base_values";
        java.util.Map<String, Long> out = new java.util.HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getLong(2));
            }
            return out;
        } catch (SQLException e) {
            throw new MarketStoreException("allBaseValues failed", e);
        }
    }

    // ---- 内部 helper ----

    /** 把 ResultSet 当前行映射为 ListingRow (列顺序与各 SELECT 投影一致)。 */
    private static ListingRow mapListing(ResultSet rs) throws SQLException {
        return new ListingRow(
                rs.getLong("id"),
                UUID.fromString(rs.getString("seller_uuid")),
                rs.getString("seller_name"),
                rs.getString("item_id"),
                rs.getBytes("item_nbt"),
                rs.getInt("count"),
                rs.getLong("unit_price"),
                rs.getString("currency"),
                rs.getLong("created_at"),
                rs.getString("status"));
    }

    /**
     * soldOrListedCountToday 两条 SUM 查询共用: 绑 seller(参1) + 可选 dayStart(参2, 仅成交侧传) + IN 子句 itemIds。
     * dayStartOrNull 非 null 时第二个占位为 created_at 下界, 其后才是 IN 列表; null 时 IN 列表紧接 seller。
     */
    private int sumCount(String sql, UUID seller, Set<String> itemIds, Long dayStartOrNull) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int p = 1;
            ps.setString(p++, seller.toString());
            if (dayStartOrNull != null) {
                ps.setLong(p++, dayStartOrNull);
            }
            for (String itemId : itemIds) {
                ps.setString(p++, itemId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                // SUM 必返一行 (COALESCE 兜 0); 防御性判 next。
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** 生成 n 个逗号分隔的 '?' 占位 (IN 子句用; n>=1 由调用方保证)。 */
    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('?');
        }
        return sb.toString();
    }

    /** sortKey 白名单 -> ORDER BY 片段 (ORDER BY 不可参数化, 白名单防注入)。非法/缺省落 newest。 */
    private static String orderByClause(String sortKey) {
        if (sortKey == null) {
            return "created_at DESC";
        }
        switch (sortKey) {
            case "price_asc":
                return "unit_price ASC";
            case "price_desc":
                return "unit_price DESC";
            case "newest":
            default:
                return "created_at DESC";
        }
    }

}

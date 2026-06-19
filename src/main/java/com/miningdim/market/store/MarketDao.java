package com.miningdim.market.store;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 跳蚤市场 SQLite 存储层 DAO 契约 (契约第 4 节, 签名固定不得改名/改签名)。
 *
 * 由 {@link MarketDaoSqlite} 实现 (持单条 {@link java.sql.Connection}); 交易引擎 (作者 B) 消费。
 * 异常纪律 (契约第 0/4 节): 所有方法把底层 {@link java.sql.SQLException} 包装成非受检
 * {@link MarketStoreException} 自然冒泡, 不吞。findListing 不存在返 null 由调用方判定并抛业务异常
 * (DAO 只如实反映表行)。markSold/markCancelled 的条件 UPDATE 返回受影响行 >0 作并发/越权防御信号。
 *
 * 事务边界 (契约第 4 节): markSold + insertTxn 的跨方法显式事务由 B 在交易引擎里用同一 Connection
 * 控制 (setAutoCommit(false) -> commit/rollback); DAO 单方法内部各自原子, drainPendingPayout 取+删
 * 用内部事务保证原子。
 */
public interface MarketDao {

    /** 建表 + 索引 (契约第 3 节 DDL, IF NOT EXISTS, 幂等)。 */
    void initSchema();

    /**
     * 插入一条 ACTIVE 挂单, 返回自增主键 listing id。
     * nbt = 托管 ItemStack 的 NBT 字节; currency 由 B 校验为 CREDIT 后传入; createdAt = epoch millis。
     */
    long insertListing(UUID seller, String sellerName, String itemId, byte[] nbt,
                       int count, long unitPrice, String currency, long createdAt);

    /** 按主键查挂单; 不存在返 null (调用方判定并抛业务异常, DAO 不代为抛)。 */
    ListingRow findListing(long id);

    /**
     * 查 ACTIVE 挂单 (市场浏览/分页)。
     *
     * @param itemFilterOrNull 物品 id 子串过滤; null/空表示不过滤
     * @param sortKey          排序键 (price_asc/price_desc/newest, 非法值落默认 newest)
     * @param offset           跳过行数 (分页)
     * @param limit            返回上限
     */
    List<ListingRow> queryActive(String itemFilterOrNull, String sortKey, int offset, int limit);

    /** 查某卖家的挂单; statusOrNull 为 null 则不限状态 (市场"我的挂单"/历史)。 */
    List<ListingRow> listingsBySeller(UUID seller, String statusOrNull);

    /**
     * 条件标记售出: UPDATE ... SET status='SOLD' WHERE id=? AND status='ACTIVE'。
     * 返回受影响行 >0 (单写者下成交必为 true; 返回 false = 已被并发抢/非 ACTIVE, 供 B rollback+退款)。
     */
    boolean markSold(long id);

    /**
     * 条件标记撤单: WHERE id=? AND seller_uuid=? AND status='ACTIVE'。
     * 返回受影响行 >0 (false = 非本人/非 ACTIVE, 越权防御)。
     */
    boolean markCancelled(long id, UUID seller);

    /** 插入一条成交流水 (审计, 30 天留存; 清理 deferred)。全字段对应 transactions 模式。 */
    void insertTxn(long listingId, UUID buyer, UUID seller, String itemId, int count,
                   long unitPrice, long total, long fee, long createdAt);

    /**
     * 铜铁日 cap 计数 (契约第 5 节): 今日该卖家这些 item 的
     * (ACTIVE listing.count 之和 + 今日 SOLD transactions.count 之和)。
     *
     * @param seller        卖家 UUID
     * @param itemIds       铜/铁 item_id 集合 (空集合返 0)
     * @param dayStartEpoch 今日起点 epoch millis (B 按本服翻日口径算; created_at >= 此值即"今日")
     */
    int soldOrListedCountToday(UUID seller, Set<String> itemIds, long dayStartEpoch);

    /** 插入一条离线卖家待结信用点 (买入时卖家离线, 卖家登录时结清)。 */
    void insertPendingPayout(UUID seller, long amount, String currency, long createdAt);

    /**
     * 取该卖家全部待结款并删除 (登录结算, 取+删用事务保证原子, 防取后未删的重复结算)。
     * 返回 [amount] 数组的列表 (每条一个 long[]{amount}; currency 当前恒 CREDIT, 由 B 累加 grant)。
     */
    List<long[]> drainPendingPayout(UUID seller);
}

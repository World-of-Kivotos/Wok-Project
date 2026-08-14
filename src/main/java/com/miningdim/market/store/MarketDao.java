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

    /**
     * 部分买入拆分: 把 ACTIVE 挂单的剩余 count 改为 newCount 并同步 item_nbt (余量托管 stack), 保持 ACTIVE。
     * 条件 UPDATE WHERE id=? AND status='ACTIVE'; 返回受影响行 >0 (false = 非 ACTIVE, 并发防御, 供退款)。
     */
    boolean reduceListing(long id, int newCount, byte[] newNbt);

    /** 插入一条成交流水 (审计, 30 天留存; 清理 deferred)。全字段对应 transactions 模式。 */
    void insertTxn(long listingId, UUID buyer, UUID seller, String itemId, int count,
                   long unitPrice, long total, long fee, long createdAt);

    /**
     * 铜铁日 cap 计数 (契约第 5 节): 今日该卖家这些 item 的
     * (ACTIVE listing.count 之和 + 今日 SOLD transactions.count 之和)。
     *
     * 恒等于 {@link #soldOrListedSplitToday} 的 {@link SoldOrListedSplit#total()} (实现里就是调它再求和),
     * cap 判定继续用本方法的单值; 要区分"在挂中/今日已成交"两段时用拆分版, 两者不会给出互相矛盾的数字。
     *
     * @param seller        卖家 UUID
     * @param itemIds       铜/铁 item_id 集合 (空集合返 0)
     * @param dayStartEpoch 今日起点 epoch millis (B 按本服翻日口径算; created_at >= 此值即"今日")
     */
    int soldOrListedCountToday(UUID seller, Set<String> itemIds, long dayStartEpoch);

    /**
     * 同 {@link #soldOrListedCountToday} 的计数, 但把两段来源分开返回 (在挂中的 ACTIVE 量 / 今日已成交量)。
     *
     * 面板要靠这个拆分才能说出诚实的话: 只有成交那一段会随日窗口翻篇归零, ACTIVE 那一段撤单前一直占着额度。
     * 参数与口径与单值版逐字相同 (空集合返两段皆 0), 本方法不放宽也不收紧任何统计范围。
     */
    SoldOrListedSplit soldOrListedSplitToday(UUID seller, Set<String> itemIds, long dayStartEpoch);

    /**
     * 该玩家参与的成交流水 (买家或卖家任一侧命中), 按 created_at DESC, id DESC 排序后分页。
     *
     * 第二排序键 id 不是装饰: created_at 是毫秒时间戳, 同一 tick 内的多笔成交时间戳相同, 只按它排序时
     * SQLite 的行序不保证稳定, 翻页会出现同一条重复出现在两页 / 另一条永远看不到。
     *
     * @param player 视角玩家 (买或卖任一侧)
     * @param offset 跳过行数 (分页)
     * @param limit  返回上限
     */
    List<TxnRow> transactionsByPlayer(UUID player, int offset, int limit);

    /** 该玩家参与的成交流水总条数 (与 {@link #transactionsByPlayer} 同一 WHERE), 供前端算总页数。 */
    int transactionsCountByPlayer(UUID player);

    /** 插入一条离线卖家待结信用点 (买入时卖家离线, 卖家登录时结清)。 */
    void insertPendingPayout(UUID seller, long amount, String currency, long createdAt);

    /**
     * 取该卖家全部待结款并删除 (登录结算, 取+删用事务保证原子, 防取后未删的重复结算)。
     * 返回 [amount] 数组的列表 (每条一个 long[]{amount}; currency 当前恒 CREDIT, 由 B 累加 grant)。
     */
    List<long[]> drainPendingPayout(UUID seller);

    /**
     * 只读查看该卖家的待结款 (面板展示), 返回与 {@link #drainPendingPayout} 同形的 [amount] 列表但**不删行**。
     *
     * 必须是独立方法而不是给 drain 加开关: drain 的"取即删"是登录结算的正确语义, 让它顺手承担只读查询,
     * 迟早会有人在展示路径上调到删除分支 —— 玩家点开一次收件箱就把货款冲掉, 且行已物理删除无从追溯。
     */
    List<long[]> peekPendingPayout(UUID seller);

    // ---- 基准价值 V0 admin 覆盖 (偏离费锚的最高优先层; DefaultBaseValues 代码预设之上) ----

    /**
     * 写入/更新某物品的 admin 手写基准价值 V0 覆盖 (INSERT OR REPLACE, 幂等)。覆盖优先于代码预设 (见
     * {@link com.miningdim.market.BaseValueResolver})。v0 由调用方校验 &gt;= 1。
     *
     * @param itemId    物品注册 id
     * @param v0        基准价值 (信用点/个, &gt;= 1)
     * @param updatedBy 操作者 (OP 玩家 UUID 文本, 审计用)
     * @param updatedAt epoch millis
     */
    void upsertBaseValue(String itemId, long v0, String updatedBy, long updatedAt);

    /** 取某物品的 admin 覆盖 V0; 无覆盖返 null (调用方退代码预设)。 */
    Long getBaseValue(String itemId);

    /** 全部 admin 覆盖 (item_id -&gt; v0); 供 admin 面板列出当前覆盖与 resolver 批量取用。 */
    java.util.Map<String, Long> allBaseValues();
}

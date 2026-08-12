package com.miningdim.economy;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 全服双货币账本的持久化契约。
 *
 * 方法签名与语义原样继承自此前的 SavedData 实现, 换的只是落盘介质与事务边界: 每个方法自身原子, 且当
 * 调用方已经开着事务时并入该事务 (由 {@link com.miningdim.store.StoreTx} 的嵌套规则保证), 这样"扣钱 +
 * 发资产"才可能真正落在同一个提交里。
 *
 * 余额语义: 无记录的玩家等价于余额 0 (新玩家), 查询不建记录。入账溢出抛
 * {@link EconomyException.Reason#BALANCE_OVERFLOW} 不静默回绕; 余额不足由 try* 系列返 false 表达, 不抛。
 *
 * 线程: 仅服务端主线程调用。
 */
public interface EconomyLedger {

    /**
     * 把 body 内的全部账本写入合并进单个事务并返回其结果; 已在事务中则并入外层, 提交权仍归最外层。
     *
     * 两个用途, 缺一不可:
     * 1) 正确性 —— 一次 faucet 入账要改三样东西 (当日原始累计、小数余量、余额), 三者分别提交时崩在中间
     *    会出现"衰减档位推进了钱却没发"; 合并后它们同生共死。
     * 2) 写放大 —— 连锁挖矿一次结算数十个产出物, 每个都改同一行钱包。逐笔提交会把同一页反复追加进 WAL,
     *    合并后只追加一次。
     */
    <T> T inTransaction(Supplier<T> body);

    /**
     * 回收创建时间早于 createdBefore 的【终态】双币操作记录, 返回删除条数。
     *
     * 终态记录只用于幂等重放, 而重放窗口是有限的; 不回收就会随开箱次数无限累积。
     * CHARGED 永不回收 —— 那是在途的付款事实, 删掉等于让玩家的钱凭空消失且无从追溯。
     */
    int pruneTerminalOperations(long createdBefore);

    /** 某玩家某货币余额; 无记录返 0。 */
    long balance(UUID playerId, Currency currency);

    /** 先校验后扣; 余额不足返 false 且不改余额。 */
    boolean tryDebit(UUID playerId, Currency currency, long amount);

    /**
     * 以 operationId 幂等地原子扣除信用点与青辉石。首次余额足则同时扣两币并记 CHARGED; 任一余额不足返 NONE,
     * 既不改余额也不记账。重放同一 operationId 返回已持久化状态; 若域/玩家/金额不符抛 OPERATION_CONFLICT。
     */
    EconomyOperationStatus tryChargeBundle(EconomyOperationDomain domain, UUID playerId, UUID operationId,
                                           long creditAmount, long azureAmount);

    /** 查询该域该玩家的 operationId 状态; 不存在或不属于该玩家返 NONE。 */
    EconomyOperationStatus operationStatus(EconomyOperationDomain domain, UUID playerId, UUID operationId);

    /** 把 CHARGED 幂等推进为 COMPLETED; REFUNDED 不可反向完成。 */
    EconomyOperationStatus completeBundle(EconomyOperationDomain domain, UUID playerId, UUID operationId);

    /** 把 CHARGED 幂等退款并推进为 REFUNDED; COMPLETED 不可退款。 */
    EconomyOperationStatus refundBundle(EconomyOperationDomain domain, UUID playerId, UUID operationId);

    /** 入账 (faucet); 溢出抛 BALANCE_OVERFLOW。 */
    void credit(UUID playerId, Currency currency, long amount);

    /** 原子双币入账; 任一溢出则两币均不变。供系统补偿与 OP 管理命令使用, 不是玩家间转账。 */
    void creditBundle(UUID playerId, long creditAmount, long azureAmount);

    /**
     * 含每日上限的事务扣费: 当日经同一 (playerId, dailyKey) 累计 + 本次 &lt;= dailyCap 且余额足才扣。
     * UTC 翻日先清零该计数。超上限或余额不足返 false, 不扣不计。
     */
    boolean tryChargeDaily(UUID playerId, Currency currency, long amount,
                           String dailyKey, long dailyCap, long todayStamp);

    /**
     * 累计一次 faucet 当日"原始信用点"入账并返回本次入账前的累计值 n0。只维护计数器, 不做衰减也不动余额。
     * 累计的是原始额而非实发额, 使衰减档随当日总产出单调推进。
     */
    long recordFaucetGrant(UUID playerId, String faucetKey, long rawAmount, long todayStamp);

    /**
     * 把衰减主闸算出的精确实发额 (小数) 累进 carry, 返回本次落账的整数部分, 余下小数留待跨笔累进。
     * 不直接动余额, 整数部分由调用方经 {@link #credit} 落账。
     */
    long creditFaucetWithCarry(UUID playerId, String faucetKey, double exactEffective, long todayStamp);

    /**
     * 青辉石 faucet 每人每日硬上限入账: 当日累计已达 dailyCap 则一律不发, 未达则只发到刚好填满上限的部分。
     * 直接落 AZURE 余额, 返回实际入账量。
     */
    long creditAzureDaily(UUID playerId, String faucetKey, long amount, long dailyCap, long todayStamp);
}

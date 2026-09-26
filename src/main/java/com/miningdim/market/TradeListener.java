package com.miningdim.market;

/**
 * 市场成交监听 (Achievement_System_DesignSpec 9.10, 成就市场类条件的来源)。
 *
 * 在 {@link MarketEngine#buy} 的扣款、改挂单、写流水、卖家结算这一事务<b>提交之后</b>调用, 每笔成交恰好一次;
 * 事务回滚 (余额不足、挂单被抢、写库失败, 或调用方开着的外层事务回滚) 时不调用。经
 * {@link MarketServices#registerTradeListener} 在 mod 构造期注册, 在服务端主线程上调用。监听器抛出的异常由广播方
 * 记录后吞掉, 不影响已经完成的成交; 监听器仍应自行捕获并记录自己的失败。
 */
@FunctionalInterface
public interface TradeListener {

    /** 一笔成交已提交。 */
    void onTrade(MarketTrade trade);
}

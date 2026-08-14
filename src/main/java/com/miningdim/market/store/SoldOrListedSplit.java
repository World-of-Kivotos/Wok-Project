package com.miningdim.market.store;

/**
 * 日 cap 计数的两段拆分 (契约第 5 节铜铁日 cap 的两个来源, 不可变 record)。
 *
 * 为什么必须拆开而不是只给一个总数: 两段的释放条件根本不同 —— {@code activeHeld} 是仍在挂的量, 它不看
 * created_at, 撤单或卖掉之前永远占着额度, 翻日不会掉; {@code soldToday} 是今日已成交的量, 次日零点随窗口
 * 自然归零。只给总数的话, 面板只能笼统承诺"额度明天 00:00 重置", 而挂着 500 铜锭不撤单的玩家到点一个不掉,
 * 在他眼里就是系统在骗人。
 *
 * 两段之和就是 {@link MarketDao#soldOrListedCountToday} 的口径 (见 {@link #total()}), 拆分不改变任何计数规则。
 */
public record SoldOrListedSplit(int activeHeld, int soldToday) {

    /** 已占用的总额度 (cap 判定吃的就是这个值)。 */
    public int total() {
        return activeHeld + soldToday;
    }
}

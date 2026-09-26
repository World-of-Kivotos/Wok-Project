package com.miningdim.achievement.trigger;

import java.util.UUID;

/**
 * 市场成就的按买家记账 (Achievement_System_DesignSpec 6.6, 表 {@code achievement_market_partner}): 每一对
 * (卖家, 买家) 一行, 记合格交易笔数与这位买家贡献给这位卖家的计数成交额。业务代码只经本接口访问, 当前实现为
 * {@link SqliteMarketPartnerRepository}, 为多子服预留。全部方法在服务端主线程同步调用。
 */
public interface MarketPartnerRepository {

    /** 一位买家贡献给一位卖家的计数成交额上限 (6.6 第 2 条)。 */
    long PAIR_VOLUME_CAP = 1_000_000L;

    /**
     * 记一笔合格交易 (调用方已排除低于 1,000、同 IP 与夫妻之间的交易): 这一对的笔数加一, 计数成交额变为
     * {@code max(原值, min(原值 + total, 1,000,000, buyerCreditsEarned))}。上限随买家当时的系统收入走, 所以小号之间
     * 对刷基本记为 0; 取 max 保证计数成交额只增不减。判断与写入在一条语句里完成。
     *
     * @param total              成交额 (信用点)
     * @param buyerCreditsEarned 买家交易那一刻的 credits_earned
     */
    void recordQualifyingTrade(UUID seller, UUID buyer, long total, long buyerCreditsEarned);

    /** 该玩家作为买家与卖家两边的累计; 没有记录时是全零。 */
    MarketStanding standing(UUID player);
}

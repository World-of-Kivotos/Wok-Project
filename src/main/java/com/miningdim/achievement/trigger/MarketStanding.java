package com.miningdim.achievement.trigger;

import java.util.List;

/**
 * 一名玩家在市场成就口径下的累计 (6.6), 由 {@code achievement_market_partner} 汇总得出, 交给 market_trade 判定。
 *
 * @param buyerTrades    作为买家的合格交易笔数
 * @param sellerTrades   作为卖家的合格交易笔数
 * @param sellerVolume   作为卖家的计数成交额 (各买家贡献的计数成交额之和)
 * @param partnerVolumes 作为卖家时每位买家各自贡献的计数成交额 (每位买家一项, 顺序无意义)
 */
public record MarketStanding(int buyerTrades, int sellerTrades, long sellerVolume, List<Long> partnerVolumes) {

    public MarketStanding {
        partnerVolumes = List.copyOf(partnerVolumes);
    }

    /** 某个角色下的合格交易笔数; ANY 是买卖两边之和。 */
    int trades(MarketTradeTrigger.Role role) {
        return switch (role) {
            case ANY -> buyerTrades + sellerTrades;
            case BUYER -> buyerTrades;
            case SELLER -> sellerTrades;
        };
    }

    /** 贡献的计数成交额不少于 minVolume 的买家人数。 */
    int partnersAtLeast(long minVolume) {
        int partners = 0;
        for (long volume : partnerVolumes) {
            if (volume >= minVolume) {
                partners++;
            }
        }
        return partners;
    }
}

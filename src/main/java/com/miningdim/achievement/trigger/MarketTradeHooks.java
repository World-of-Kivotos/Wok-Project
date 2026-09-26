package com.miningdim.achievement.trigger;

import com.miningdim.achievement.AchievementServices;
import com.miningdim.entry.IMiningPlayerData;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.market.MarketTrade;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraftforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;

/**
 * 市场成就的防刷计数 (Achievement_System_DesignSpec 6.6), 由 {@link SocialEconomyHooks} 注册进市场模块的成交监听
 * (9.10), 在 {@code MarketEngine#buy} 的事务提交后收到每一笔成交。
 *
 * <p>一笔成交 (买家不是 FakePlayer) 过三道闸, 全部通过才是"合格交易", 否则整笔不计入成就:
 * <ol>
 *   <li>成交额不低于 {@value #QUALIFYING_TOTAL} 信用点;</li>
 *   <li>买卖双方此刻的连接 IP 不同: 只在这一刻比较两边当前的连接 (卖家不在线就没有可比的连接, 视为不同),
 *       IP 不落库也不进日志;</li>
 *   <li>双方不是夫妻 (读核心模块的婚姻指针, 任一方的配偶指向另一方即算)。</li>
 * </ol>
 * 合格交易写进 {@link MarketPartnerRepository} (这一对的笔数加一, 计数成交额按买家此刻的 credits_earned 封顶), 随后
 * 对买家与在线的卖家各触发一次 market_trade。离线卖家在下次登录时由 {@link #recheck} 补查 (不静默: 那是他离线期间
 * 新成交的, 不是上线前的历史)。写库失败只记错误日志, 这一笔不计, 不影响已经完成的成交。
 */
final class MarketTradeHooks {

    /** 合格交易的最低成交额 (6.6 第 1 条)。 */
    static final long QUALIFYING_TOTAL = 1_000L;

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/achievement/hooks");

    private MarketTradeHooks() {
    }

    /** 一笔成交已提交 ({@link com.miningdim.market.TradeListener})。 */
    static void onTrade(MarketTrade trade) {
        ServerPlayer buyer = trade.buyer();
        if (buyer instanceof FakePlayer || trade.total() < QUALIFYING_TOTAL) {
            return;
        }
        try {
            ServerPlayer seller = buyer.server.getPlayerList().getPlayer(trade.seller());
            if (sameConnectionAddress(buyer, seller) || spouses(buyer, trade.seller(), seller)) {
                return;
            }
            long buyerEarned = buyer.getStats().getValue(Stats.CUSTOM.get(AchievementStats.CREDITS_EARNED.get()));
            MarketPartnerRepository partners = AchievementServices.marketPartners();
            partners.recordQualifyingTrade(trade.seller(), buyer.getUUID(), trade.total(), buyerEarned);
            AchievementTriggers.MARKET_TRADE.trigger(buyer, partners.standing(buyer.getUUID()));
            if (seller != null) {
                AchievementTriggers.MARKET_TRADE.trigger(seller, partners.standing(seller.getUUID()));
            }
        } catch (RuntimeException failure) {
            LOGGER.error("[miningdim] market trade of {} ({} x{}, {} credit) from {} not counted for achievements",
                    buyer.getGameProfile().getName(), trade.itemId(), trade.count(), trade.total(), trade.seller(),
                    failure);
        }
    }

    /** 按记账表重新核对该玩家的市场成就 (登录时: 离线期间卖出的货在这里补上)。 */
    static void recheck(ServerPlayer player) {
        try {
            AchievementTriggers.MARKET_TRADE.trigger(player,
                    AchievementServices.marketPartners().standing(player.getUUID()));
        } catch (RuntimeException failure) {
            LOGGER.error("[miningdim] market achievements of {} not rechecked at login",
                    player.getGameProfile().getName(), failure);
        }
    }

    /** 双方此刻的连接 IP 是否相同。卖家不在线、任一方的连接不是网络连接 (本地或测试连接) 时答 false。 */
    private static boolean sameConnectionAddress(ServerPlayer buyer, @Nullable ServerPlayer seller) {
        if (seller == null) {
            return false;
        }
        InetAddress buyerAddress = connectionAddress(buyer);
        return buyerAddress != null && buyerAddress.equals(connectionAddress(seller));
    }

    /** 双方是否是夫妻: 买家的配偶是卖家, 或在线卖家的配偶是买家。 */
    private static boolean spouses(ServerPlayer buyer, UUID sellerId, @Nullable ServerPlayer seller) {
        if (sellerId.equals(spouseOf(buyer))) {
            return true;
        }
        return seller != null && buyer.getUUID().equals(spouseOf(seller));
    }

    @Nullable
    private static InetAddress connectionAddress(ServerPlayer player) {
        if (player.connection == null) {
            return null;
        }
        SocketAddress remote = player.connection.getRemoteAddress();
        return remote instanceof InetSocketAddress inet ? inet.getAddress() : null;
    }

    @Nullable
    private static UUID spouseOf(ServerPlayer player) {
        IMiningPlayerData data = MiningCapabilities.get(player).orElse(null);
        return data == null ? null : data.spouseUUID();
    }
}

package com.miningdim.achievement.trigger;

import com.miningdim.achievement.AchievementStoreException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 基于统一库 {@code miningdim.db} 的市场按买家记账 (表结构见 {@code MiningSchema} V8 的
 * achievement_market_partner, 主键 (seller_uuid, buyer_uuid), 另有 buyer_uuid 索引)。连接是全服共享的那一条,
 * 本类只持引用、从不关闭它。
 */
public final class SqliteMarketPartnerRepository implements MarketPartnerRepository {

    /**
     * 没有这一对就插入 (1 笔, 计数额 min(total, 上限)); 有则笔数加一、计数额取 max(原值, min(原值 + total, 上限))。
     * 参数依次: 卖家、买家、插入时的计数额、total、上限。
     */
    private static final String RECORD_TRADE = "INSERT INTO achievement_market_partner "
            + "(seller_uuid, buyer_uuid, trades, counted_volume) VALUES (?, ?, 1, ?) "
            + "ON CONFLICT(seller_uuid, buyer_uuid) DO UPDATE SET trades = trades + 1, "
            + "counted_volume = MAX(counted_volume, MIN(counted_volume + ?, ?))";

    private final Connection connection;

    public SqliteMarketPartnerRepository(Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("market partner repository needs a connection");
        }
        this.connection = connection;
    }

    @Override
    public void recordQualifyingTrade(UUID seller, UUID buyer, long total, long buyerCreditsEarned) {
        if (total <= 0L) {
            throw new IllegalArgumentException("a qualifying trade has a positive total, got " + total);
        }
        long cap = Math.max(0L, Math.min(PAIR_VOLUME_CAP, buyerCreditsEarned));
        try (PreparedStatement statement = connection.prepareStatement(RECORD_TRADE)) {
            statement.setString(1, seller.toString());
            statement.setString(2, buyer.toString());
            statement.setLong(3, Math.min(total, cap));
            statement.setLong(4, total);
            statement.setLong(5, cap);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new AchievementStoreException("failed to record market trade " + buyer + " -> " + seller,
                    exception);
        }
    }

    @Override
    public MarketStanding standing(UUID player) {
        int buyerTrades;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(SUM(trades), 0) FROM achievement_market_partner WHERE buyer_uuid=?")) {
            statement.setString(1, player.toString());
            try (ResultSet result = statement.executeQuery()) {
                buyerTrades = result.next() ? result.getInt(1) : 0;
            }
        } catch (SQLException exception) {
            throw new AchievementStoreException("failed to read market purchases of " + player, exception);
        }
        int sellerTrades = 0;
        long sellerVolume = 0L;
        List<Long> partnerVolumes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT trades, counted_volume FROM achievement_market_partner WHERE seller_uuid=?")) {
            statement.setString(1, player.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    sellerTrades += result.getInt(1);
                    long counted = result.getLong(2);
                    sellerVolume += counted;
                    partnerVolumes.add(counted);
                }
            }
        } catch (SQLException exception) {
            throw new AchievementStoreException("failed to read market sales of " + player, exception);
        }
        return new MarketStanding(buyerTrades, sellerTrades, sellerVolume, partnerVolumes);
    }
}

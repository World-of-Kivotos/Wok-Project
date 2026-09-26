package com.miningdim.achievement.shop;

import com.miningdim.achievement.AchievementStoreException;
import com.miningdim.achievement.reward.LedgerReason;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 基于统一库 {@code miningdim.db} 的成就点商店查询: 兑换次数取自成就点流水 {@code achievement_point_ledger}
 * (MiningSchema V7) 里 reason=shop_buy 的行。连接是全服共享的那一条, 本类只持引用、从不关闭它。
 *
 * 流水表没有按玩家的索引: 按当前规模 (历史玩家不足一千, 每人几十条流水) 全表扫描也在毫秒以内, 而补索引要追加一版
 * schema 迁移, 不值得为此单开。
 */
public final class SqlitePointShopRepository implements PointShopRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/achievement");

    private final Connection connection;

    public SqlitePointShopRepository(Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("achievement point shop repository needs a connection");
        }
        this.connection = connection;
    }

    @Override
    public int purchaseCount(UUID player, ResourceLocation goodsId) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM achievement_point_ledger "
                + "WHERE player_uuid=? AND reason=? AND ref=?")) {
            statement.setString(1, player.toString());
            statement.setString(2, LedgerReason.SHOP_BUY.id());
            statement.setString(3, goodsId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        } catch (SQLException exception) {
            throw new AchievementStoreException("failed to count purchases of " + goodsId + " by " + player, exception);
        }
    }

    @Override
    public Map<ResourceLocation, Integer> purchaseCounts(UUID player) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT ref, COUNT(*) FROM "
                + "achievement_point_ledger WHERE player_uuid=? AND reason=? GROUP BY ref ORDER BY ref")) {
            statement.setString(1, player.toString());
            statement.setString(2, LedgerReason.SHOP_BUY.id());
            Map<ResourceLocation, Integer> counts = new LinkedHashMap<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String ref = result.getString(1);
                    ResourceLocation goodsId = ref == null ? null : ResourceLocation.tryParse(ref);
                    if (goodsId == null) {
                        // 手工改坏的流水行只跳过这一行, 不让整个成就点商店页打不开。
                        LOGGER.warn("[miningdim] ignoring shop_buy ledger rows of {} with malformed ref '{}'", player,
                                ref);
                        continue;
                    }
                    counts.put(goodsId, result.getInt(2));
                }
            }
            return Collections.unmodifiableMap(counts);
        } catch (SQLException exception) {
            throw new AchievementStoreException("failed to count purchases of " + player, exception);
        }
    }
}

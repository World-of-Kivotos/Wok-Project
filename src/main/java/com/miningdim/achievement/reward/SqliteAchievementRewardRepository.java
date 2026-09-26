package com.miningdim.achievement.reward;

import com.miningdim.achievement.AchievementStoreException;
import com.miningdim.achievement.tier.AchievementTier;
import com.miningdim.store.StoreTx;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 基于统一库 {@code miningdim.db} 的奖励与成就点存储 (表结构见 {@code MiningSchema} V7)。
 *
 * 连接是全服共享的那一条 (由存储子系统在 ServerAboutToStart 打开、ServerStopped 关闭), 本类只持引用、从不关闭它。
 * 事务一律经 {@link StoreTx}: 调用方已开着事务时并入外层, 不会提前把外层的中间态提交掉。
 */
public final class SqliteAchievementRewardRepository implements AchievementRewardRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/achievement");

    private static final String REWARD_COLUMNS =
            "player_uuid, advancement_id, tier, points, title_id, earned_at, claimed_at";

    private final Connection connection;

    public SqliteAchievementRewardRepository(Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("achievement reward repository needs a connection");
        }
        this.connection = connection;
    }

    @Override
    public boolean insertReward(UUID player, ResourceLocation advancementId, AchievementTier tier, int points,
                                @Nullable ResourceLocation titleId, long earnedAt) {
        try (PreparedStatement statement = connection.prepareStatement("INSERT OR IGNORE INTO achievement_reward ("
                + REWARD_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, NULL)")) {
            statement.setString(1, player.toString());
            statement.setString(2, advancementId.toString());
            statement.setString(3, tier.id());
            statement.setInt(4, points);
            statement.setString(5, titleId == null ? null : titleId.toString());
            statement.setLong(6, earnedAt);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new AchievementStoreException("failed to record reward " + advancementId + " for " + player,
                    exception);
        }
    }

    @Override
    public Optional<AchievementReward> reward(UUID player, ResourceLocation advancementId) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + REWARD_COLUMNS
                + " FROM achievement_reward WHERE player_uuid=? AND advancement_id=?")) {
            statement.setString(1, player.toString());
            statement.setString(2, advancementId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.ofNullable(readReward(result, player)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new AchievementStoreException("failed to read reward " + advancementId + " of " + player, exception);
        }
    }

    @Override
    public List<AchievementReward> pending(UUID player) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + REWARD_COLUMNS
                + " FROM achievement_reward WHERE player_uuid=? AND claimed_at IS NULL"
                + " ORDER BY earned_at, advancement_id")) {
            statement.setString(1, player.toString());
            List<AchievementReward> rewards = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    AchievementReward reward = readReward(result, player);
                    if (reward != null) {
                        rewards.add(reward);
                    }
                }
            }
            return Collections.unmodifiableList(rewards);
        } catch (SQLException exception) {
            throw new AchievementStoreException("failed to list pending rewards of " + player, exception);
        }
    }

    @Override
    public boolean markClaimed(UUID player, ResourceLocation advancementId, long claimedAt) {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE achievement_reward SET claimed_at=? "
                + "WHERE player_uuid=? AND advancement_id=? AND claimed_at IS NULL")) {
            statement.setLong(1, claimedAt);
            statement.setString(2, player.toString());
            statement.setString(3, advancementId.toString());
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new AchievementStoreException("failed to claim reward " + advancementId + " for " + player,
                    exception);
        }
    }

    @Override
    public PointBalance points(UUID player) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance, lifetime FROM achievement_points WHERE player_uuid=?")) {
            statement.setString(1, player.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new PointBalance(result.getLong(1), result.getLong(2)) : PointBalance.ZERO;
            }
        } catch (SQLException exception) {
            throw new AchievementStoreException("failed to read achievement points of " + player, exception);
        }
    }

    @Override
    public void credit(UUID player, long amount) {
        requirePositive(amount);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO achievement_points (player_uuid, balance, lifetime) VALUES (?, ?, ?) "
                        + "ON CONFLICT(player_uuid) DO UPDATE SET balance = balance + excluded.balance, "
                        + "lifetime = lifetime + excluded.lifetime")) {
            statement.setString(1, player.toString());
            statement.setLong(2, amount);
            statement.setLong(3, amount);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new AchievementStoreException("failed to credit " + amount + " points to " + player, exception);
        }
    }

    @Override
    public boolean debit(UUID player, long amount) {
        requirePositive(amount);
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE achievement_points SET balance = balance - ? WHERE player_uuid=? AND balance >= ?")) {
            statement.setLong(1, amount);
            statement.setString(2, player.toString());
            statement.setLong(3, amount);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new AchievementStoreException("failed to debit " + amount + " points from " + player, exception);
        }
    }

    @Override
    public void appendLedger(UUID player, long delta, LedgerReason reason, @Nullable String ref, long at) {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO achievement_point_ledger (player_uuid, delta, reason, ref, at) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, player.toString());
            statement.setLong(2, delta);
            statement.setString(3, reason.id());
            if (ref == null) {
                statement.setNull(4, Types.VARCHAR);
            } else {
                statement.setString(4, ref);
            }
            statement.setLong(5, at);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new AchievementStoreException("failed to append point ledger for " + player, exception);
        }
    }

    @Override
    public <T> T inTransaction(Function<Connection, T> body) {
        return StoreTx.call(connection, () -> body.apply(connection));
    }

    @Override
    public boolean inOpenTransaction() {
        try {
            return !connection.getAutoCommit();
        } catch (SQLException exception) {
            throw new AchievementStoreException("failed to read auto-commit state", exception);
        }
    }

    private static void requirePositive(long amount) {
        if (amount <= 0L) {
            throw new IllegalArgumentException("point amount must be positive, got " + amount);
        }
    }

    /**
     * 读一行 achievement_reward (列序见 {@link #REWARD_COLUMNS})。进度 id、档位或称号 id 被手工改坏时告警并跳过该行,
     * 不让整个玩家的奖励列表读不出来。
     */
    @Nullable
    private static AchievementReward readReward(ResultSet result, UUID player) throws SQLException {
        String rawAdvancement = result.getString(2);
        ResourceLocation advancementId = ResourceLocation.tryParse(rawAdvancement);
        AchievementTier tier = AchievementTier.byId(result.getString(3)).orElse(null);
        String rawTitle = result.getString(5);
        ResourceLocation titleId = rawTitle == null ? null : ResourceLocation.tryParse(rawTitle);
        if (advancementId == null || tier == null || (rawTitle != null && titleId == null)) {
            LOGGER.warn("[miningdim] ignoring malformed achievement reward row of {}: advancement '{}', tier '{}', "
                    + "title '{}'", player, rawAdvancement, result.getString(3), rawTitle);
            return null;
        }
        long claimedAt = result.getLong(7);
        Long claimed = result.wasNull() ? null : claimedAt;
        return new AchievementReward(player, advancementId, tier, result.getInt(4), titleId, result.getLong(6),
                claimed);
    }
}

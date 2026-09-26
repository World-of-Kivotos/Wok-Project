package com.miningdim.achievement.trigger;

import com.miningdim.achievement.AchievementStoreException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * 基于统一库 {@code miningdim.db} 的每日计数 (表结构见 {@code MiningSchema} V7 的 achievement_daily_counter,
 * 主键 (player_uuid, counter_key, day))。连接是全服共享的那一条, 本类只持引用、从不关闭它。
 */
public final class SqliteDailyCounterRepository implements DailyCounterRepository {

    /**
     * 没有当天的行就插入 1; 有则只在 count 低于上限时加一。ON CONFLICT ... DO UPDATE ... WHERE 被 WHERE 挡下时
     * 影响行数为 0, 调用方据此得知已到上限。
     */
    private static final String INCREMENT_IF_BELOW = "INSERT INTO achievement_daily_counter "
            + "(player_uuid, counter_key, day, count) VALUES (?, ?, ?, 1) "
            + "ON CONFLICT(player_uuid, counter_key, day) DO UPDATE SET count = count + 1 WHERE count < ?";

    private final Connection connection;

    public SqliteDailyCounterRepository(Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("daily counter repository needs a connection");
        }
        this.connection = connection;
    }

    @Override
    public boolean incrementIfBelow(UUID player, String counterKey, long day, int cap) {
        if (cap <= 0) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement(INCREMENT_IF_BELOW)) {
            statement.setString(1, player.toString());
            statement.setString(2, counterKey);
            statement.setLong(3, day);
            statement.setInt(4, cap);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new AchievementStoreException("failed to count " + counterKey + " for " + player, exception);
        }
    }

    @Override
    public int count(UUID player, String counterKey, long day) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT count FROM achievement_daily_counter WHERE player_uuid=? AND counter_key=? AND day=?")) {
            statement.setString(1, player.toString());
            statement.setString(2, counterKey);
            statement.setLong(3, day);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        } catch (SQLException exception) {
            throw new AchievementStoreException("failed to read " + counterKey + " of " + player, exception);
        }
    }

    @Override
    public int deleteBefore(long day) {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM achievement_daily_counter WHERE day < ?")) {
            statement.setLong(1, day);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw new AchievementStoreException("failed to prune daily counters before day " + day, exception);
        }
    }
}

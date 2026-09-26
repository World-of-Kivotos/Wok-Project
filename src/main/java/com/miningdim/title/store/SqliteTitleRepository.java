package com.miningdim.title.store;

import com.miningdim.store.StoreTx;
import com.miningdim.title.TitleSource;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 基于统一库 {@code miningdim.db} 的称号存储 (表结构见 {@code MiningSchema} V5)。
 *
 * 连接是全服共享的那一条 (由存储子系统在 ServerAboutToStart 打开、ServerStopped 关闭), 本类只持引用、
 * 从不关闭它。事务一律经 {@link StoreTx}: 若调用方 (经济、成就点商店) 已开着事务, 本类的写入并入外层,
 * 不会提前把外层的中间态 commit 掉。
 */
public final class SqliteTitleRepository implements TitleRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/title");

    private static final String INSERT_OWNED = "INSERT OR IGNORE INTO title_owned "
            + "(player_uuid, title_id, source, source_ref, granted_at) VALUES (?, ?, ?, ?, ?)";

    private final Connection connection;

    public SqliteTitleRepository(Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("title repository needs a connection");
        }
        this.connection = connection;
    }

    @Override
    public boolean insertOwned(UUID player, ResourceLocation titleId, TitleSource source,
                               @Nullable String sourceRef, long grantedAt) {
        return insert(connection, player, titleId, source, sourceRef, grantedAt);
    }

    @Override
    public boolean insertOwnedInTransaction(Connection tx, UUID player, ResourceLocation titleId,
                                            TitleSource source, @Nullable String sourceRef, long grantedAt) {
        requireOpenTransaction(tx);
        return insert(tx, player, titleId, source, sourceRef, grantedAt);
    }

    @Override
    public void requireOpenTransaction(Connection tx) {
        if (tx == null) {
            throw new IllegalArgumentException("transaction connection must not be null");
        }
        try {
            if (tx.getAutoCommit()) {
                throw new IllegalStateException("grantInTransaction requires the caller to open a transaction first"
                        + " (connection is in auto-commit mode)");
            }
        } catch (SQLException exception) {
            throw new TitleStoreException("failed to read auto-commit state", exception);
        }
    }

    private static boolean insert(Connection target, UUID player, ResourceLocation titleId, TitleSource source,
                                  @Nullable String sourceRef, long grantedAt) {
        try (PreparedStatement statement = target.prepareStatement(INSERT_OWNED)) {
            statement.setString(1, player.toString());
            statement.setString(2, titleId.toString());
            statement.setString(3, source.id());
            statement.setString(4, sourceRef);
            statement.setLong(5, grantedAt);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new TitleStoreException("failed to grant title " + titleId + " to " + player, exception);
        }
    }

    @Override
    public boolean deleteOwned(UUID player, ResourceLocation titleId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM title_owned WHERE player_uuid=? AND title_id=?")) {
            statement.setString(1, player.toString());
            statement.setString(2, titleId.toString());
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new TitleStoreException("failed to revoke title " + titleId + " from " + player, exception);
        }
    }

    @Override
    public Set<ResourceLocation> owned(UUID player) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT title_id FROM title_owned WHERE player_uuid=? ORDER BY granted_at, title_id")) {
            statement.setString(1, player.toString());
            Set<ResourceLocation> owned = new LinkedHashSet<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ResourceLocation id = parseId(result.getString(1), player);
                    if (id != null) {
                        owned.add(id);
                    }
                }
            }
            return Collections.unmodifiableSet(owned);
        } catch (SQLException exception) {
            throw new TitleStoreException("failed to read titles of " + player, exception);
        }
    }

    @Override
    public Optional<ResourceLocation> equipped(UUID player) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT title_id FROM title_equipped WHERE player_uuid=?")) {
            statement.setString(1, player.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.ofNullable(parseId(result.getString(1), player)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new TitleStoreException("failed to read equipped title of " + player, exception);
        }
    }

    @Override
    public void setEquipped(UUID player, ResourceLocation titleId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO title_equipped (player_uuid, title_id) VALUES (?, ?) "
                        + "ON CONFLICT(player_uuid) DO UPDATE SET title_id=excluded.title_id")) {
            statement.setString(1, player.toString());
            statement.setString(2, titleId.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new TitleStoreException("failed to equip title " + titleId + " for " + player, exception);
        }
    }

    @Override
    public boolean clearEquipped(UUID player) {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM title_equipped WHERE player_uuid=?")) {
            statement.setString(1, player.toString());
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new TitleStoreException("failed to unequip title for " + player, exception);
        }
    }

    @Override
    public <T> T inTransaction(Supplier<T> body) {
        return StoreTx.call(connection, body);
    }

    @Override
    public boolean inOpenTransaction() {
        try {
            return !connection.getAutoCommit();
        } catch (SQLException exception) {
            throw new TitleStoreException("failed to read auto-commit state", exception);
        }
    }

    /** 库里的 id 若已不是合法 ResourceLocation (手工改库), 告警后跳过该行而不是让整个玩家读不出来。 */
    @Nullable
    private static ResourceLocation parseId(String raw, UUID player) {
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null) {
            LOGGER.warn("[miningdim] ignoring malformed title id '{}' stored for {}", raw, player);
        }
        return id;
    }
}

package com.miningdim.title.store;

import com.miningdim.store.StoreTx;
import com.miningdim.title.CustomTitle;
import com.miningdim.title.CustomTitleStyle;
import com.miningdim.title.CustomTitleValidator;
import com.miningdim.title.SponsorStatus;
import com.miningdim.title.TierPalette;
import com.miningdim.title.TitleSource;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 基于统一库 {@code miningdim.db} 的称号存储 (表结构见 {@code MiningSchema} V5 的持有、佩戴与 V6 的赞助资格、
 * 专属称号)。
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

    // ---- 赞助资格 ----

    @Override
    public Optional<SponsorStatus> sponsor(UUID player) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid, granted_by, granted_at, expires_at FROM title_sponsor WHERE player_uuid=?")) {
            statement.setString(1, player.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.ofNullable(readSponsor(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new TitleStoreException("failed to read sponsorship of " + player, exception);
        }
    }

    @Override
    public List<SponsorStatus> sponsors() {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid, granted_by, granted_at, expires_at FROM title_sponsor "
                        + "ORDER BY granted_at, player_uuid");
             ResultSet result = statement.executeQuery()) {
            List<SponsorStatus> sponsors = new ArrayList<>();
            while (result.next()) {
                SponsorStatus sponsor = readSponsor(result);
                if (sponsor != null) {
                    sponsors.add(sponsor);
                }
            }
            return Collections.unmodifiableList(sponsors);
        } catch (SQLException exception) {
            throw new TitleStoreException("failed to list sponsors", exception);
        }
    }

    @Override
    public void upsertSponsor(UUID player, String grantedBy, long grantedAt, @Nullable Long expiresAt) {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO title_sponsor (player_uuid, granted_by, granted_at, expires_at) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT(player_uuid) DO UPDATE SET granted_by=excluded.granted_by, "
                        + "granted_at=excluded.granted_at, expires_at=excluded.expires_at")) {
            statement.setString(1, player.toString());
            statement.setString(2, grantedBy);
            statement.setLong(3, grantedAt);
            if (expiresAt == null) {
                statement.setNull(4, Types.INTEGER);
            } else {
                statement.setLong(4, expiresAt);
            }
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new TitleStoreException("failed to grant sponsorship to " + player, exception);
        }
    }

    @Override
    public boolean deleteSponsor(UUID player) {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM title_sponsor WHERE player_uuid=?")) {
            statement.setString(1, player.toString());
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new TitleStoreException("failed to revoke sponsorship of " + player, exception);
        }
    }

    // ---- 专属称号 ----

    @Override
    public Optional<CustomTitle> customTitle(UUID player) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT text, colors, bold, updated_at, locked, locked_by FROM title_custom WHERE player_uuid=?")) {
            statement.setString(1, player.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                List<Integer> colors = parseColors(result.getString(2));
                if (colors == null) {
                    // 手工改坏的颜色列: 告警后当作没有记录, 不让整个玩家的称号数据读不出来。
                    LOGGER.warn("[miningdim] ignoring custom title of {} with malformed colors '{}'",
                            player, result.getString(2));
                    return Optional.empty();
                }
                CustomTitleStyle style = new CustomTitleStyle(result.getString(1), colors, result.getInt(3) != 0);
                return Optional.of(new CustomTitle(player, style, result.getLong(4), result.getInt(5) != 0,
                        result.getString(6)));
            }
        } catch (SQLException exception) {
            throw new TitleStoreException("failed to read custom title of " + player, exception);
        }
    }

    @Override
    public void saveCustomTitle(UUID player, CustomTitleStyle style, long updatedAt) {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO title_custom (player_uuid, text, colors, bold, updated_at) VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT(player_uuid) DO UPDATE SET text=excluded.text, colors=excluded.colors, "
                        + "bold=excluded.bold, updated_at=excluded.updated_at")) {
            statement.setString(1, player.toString());
            statement.setString(2, style.text());
            statement.setString(3, style.colorsText());
            statement.setInt(4, style.bold() ? 1 : 0);
            statement.setLong(5, updatedAt);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new TitleStoreException("failed to save custom title of " + player, exception);
        }
    }

    @Override
    public boolean deleteCustomTitle(UUID player) {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM title_custom WHERE player_uuid=?")) {
            statement.setString(1, player.toString());
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new TitleStoreException("failed to reset custom title of " + player, exception);
        }
    }

    @Override
    public boolean setCustomTitleLocked(UUID player, boolean locked, @Nullable String lockedBy) {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE title_custom SET locked=?, locked_by=? WHERE player_uuid=?")) {
            statement.setInt(1, locked ? 1 : 0);
            statement.setString(2, lockedBy);
            statement.setString(3, player.toString());
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new TitleStoreException("failed to change custom title lock of " + player, exception);
        }
    }

    @Override
    public boolean setCustomTitleUpdatedAt(UUID player, long updatedAt) {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE title_custom SET updated_at=? WHERE player_uuid=?")) {
            statement.setLong(1, updatedAt);
            statement.setString(2, player.toString());
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new TitleStoreException("failed to change custom title cooldown of " + player, exception);
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

    /** 读一行 title_sponsor (列序 player_uuid, granted_by, granted_at, expires_at); UUID 被改坏时告警跳过。 */
    @Nullable
    private static SponsorStatus readSponsor(ResultSet result) throws SQLException {
        String rawPlayer = result.getString(1);
        UUID player;
        try {
            player = UUID.fromString(rawPlayer);
        } catch (IllegalArgumentException malformed) {
            LOGGER.warn("[miningdim] ignoring sponsorship row with malformed player uuid '{}'", rawPlayer);
            return null;
        }
        long expiresAt = result.getLong(4);
        Long expiry = result.wasNull() ? null : expiresAt;
        return new SponsorStatus(player, result.getString(2), result.getLong(3), expiry);
    }

    /** 解析 title_custom.colors (逗号分隔的 1 ~ 3 个 #RRGGBB); 任何一项不合法返回 null。 */
    @Nullable
    private static List<Integer> parseColors(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String[] stops = raw.split(",", -1);
        if (stops.length > TierPalette.MAX_GRADIENT_STOPS) {
            return null;
        }
        List<Integer> colors = new ArrayList<>(stops.length);
        for (String stop : stops) {
            OptionalInt color = CustomTitleValidator.parseColor(stop);
            if (color.isEmpty()) {
                return null;
            }
            colors.add(color.getAsInt());
        }
        return colors;
    }
}

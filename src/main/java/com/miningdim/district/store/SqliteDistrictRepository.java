package com.miningdim.district.store;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.miningdim.district.core.Academy;
import com.miningdim.district.core.AreaChange;
import com.miningdim.district.core.DistrictActorRole;
import com.miningdim.district.core.DistrictAudience;
import com.miningdim.district.core.DistrictBounds;
import com.miningdim.district.core.DistrictLogAction;
import com.miningdim.district.core.DistrictLogEntry;
import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.FriendRecord;
import com.miningdim.district.core.FriendSyncStatus;
import com.miningdim.district.core.MemberRecord;
import com.miningdim.district.core.NoticeRecord;
import com.miningdim.district.core.PermissionCatalog;
import com.miningdim.district.core.PermissionCells;
import com.miningdim.district.core.PermissionChange;
import com.miningdim.district.core.PermissionItemDef;
import com.miningdim.district.core.PlotActorRole;
import com.miningdim.district.core.PlotArea;
import com.miningdim.district.core.PlotAudience;
import com.miningdim.district.core.PlotLogAction;
import com.miningdim.district.core.PlotLogEntry;
import com.miningdim.district.core.PlotRecord;
import com.miningdim.district.core.PlotSyncStatus;
import com.miningdim.district.core.ResidentSyncStatus;
import com.miningdim.district.core.SeenPlayer;
import com.miningdim.district.core.TombstoneRecord;
import com.miningdim.store.StoreTx;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 基于统一库 {@code miningdim.db} 的自管区仓储 (表结构见 MiningSchema V9)。
 *
 * 连接是全服共享的那一条, 本类只持引用、从不关闭它; 事务一律经 {@link StoreTx}: 买地时经济账本的扣款与本类的写入落在
 * 同一条连接的同一个事务里, 外层回滚时扣款一起撤销。每个方法一个 PreparedStatement, SQLException 一律包成
 * {@link DistrictStoreException} 并写明是哪个操作失败。
 */
public final class SqliteDistrictRepository implements DistrictRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/district");
    private static final Gson GSON = new Gson();

    private static final String DISTRICT_COLUMNS = "d.district_id, d.academy_id, d.display_name, d.dimension, "
            + "d.min_x, d.min_z, d.max_x, d.max_z, d.rules_json, d.warden_uuid, d.warden_name, d.unit_price, "
            + "d.min_side, d.max_side, d.purchase_open, d.next_plot_no, d.flan_claim_id, d.needs_reconcile, "
            + "d.created_at, d.created_by_name, d.unbound_at, d.unbound_by_name, d.unbound_member_count, "
            + "d.unbound_plot_count";
    private static final String PLOT_COLUMNS = "p.plot_id, p.district_id, p.plot_no, p.code, p.min_x, p.min_z, "
            + "p.max_x, p.max_z, p.owner_uuid, p.owner_name, p.frozen_owner_uuid, p.frozen_owner_name, p.frozen_at, "
            + "p.tenure, p.sync_status, p.sync_error, p.flan_claim_id, p.created_at";
    private static final String MEMBER_COLUMNS = "id, player_uuid, player_name, academy_id, joined_at, "
            + "added_by_uuid, added_by_name, sync_status, sync_error";
    private static final String FRIEND_COLUMNS = "f.id, f.plot_id, f.player_uuid, f.player_name, f.added_at, "
            + "f.added_by_name, f.sync_status, f.suspended_at";
    private static final String LOG_TAIL_COLUMNS = "at, actor_uuid, actor_name, actor_role, action, target_name, "
            + "reason, perm_id, perm_label, perm_audience, perm_from, perm_to, from_min_x, from_min_z, from_max_x, "
            + "from_max_z, to_min_x, to_min_z, to_max_x, to_max_z";
    /** LOG_TAIL_COLUMNS 的列数, 与 {@link #bindLogTail} 绑定的参数个数一致。 */
    private static final int LOG_TAIL_COUNT = LOG_TAIL_COLUMNS.split(",").length;

    private final Connection connection;

    /** 几何变化的监听者 (空间索引的重建)。 */
    private volatile Runnable layoutListener = () -> {
    };
    /**
     * 有几何写入还没通知监听者。提交后的动作先看它: 同一事务里登记了多次也只通知一次。回滚时队列被丢弃, 标记留着为真,
     * 只会让下一次提交后多通知一次 (重建是幂等的), 不会漏。
     */
    private volatile boolean layoutDirty;

    public SqliteDistrictRepository(Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("district repository needs a connection");
        }
        this.connection = connection;
    }

    // ================================================================
    // 事务
    // ================================================================

    @Override
    public <T> T inTransaction(Supplier<T> body) {
        return StoreTx.call(connection, body);
    }

    @Override
    public void afterCommit(Runnable action) {
        StoreTx.afterCommit(connection, action);
    }

    @Override
    public void onLayoutChanged(Runnable listener) {
        this.layoutListener = listener == null ? () -> {
        } : listener;
    }

    /** 改几何的写方法执行成功之后调: 每次都登记 (不用"只登记一次"的标记, 回滚会清空提交后队列)。 */
    private void layoutChanged() {
        layoutDirty = true;
        afterCommit(this::flushLayout);
    }

    private void flushLayout() {
        if (!layoutDirty) {
            return;
        }
        layoutDirty = false;
        layoutListener.run();
    }

    @Override
    public boolean inOpenTransaction() {
        try {
            return !connection.getAutoCommit();
        } catch (SQLException exception) {
            throw new DistrictStoreException("failed to read auto-commit state", exception);
        }
    }

    @Override
    public Connection connection() {
        return connection;
    }

    // ================================================================
    // 学院
    // ================================================================

    @Override
    public int ensureAcademies(List<Academy> academies, long at) {
        int inserted = 0;
        for (Academy academy : academies) {
            inserted += update("ensure academy " + academy.academyId(),
                    "INSERT OR IGNORE INTO district_academy (academy_id, short_name, full_name, sort_order, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    ps -> {
                        ps.setString(1, academy.academyId());
                        ps.setString(2, academy.shortName());
                        ps.setString(3, academy.fullName());
                        ps.setInt(4, academy.sortOrder());
                        ps.setLong(5, at);
                    });
        }
        return inserted;
    }

    @Override
    public List<Academy> academies() {
        return query("list academies",
                "SELECT academy_id, short_name, full_name, sort_order FROM district_academy ORDER BY sort_order",
                ps -> {
                }, SqliteDistrictRepository::readAcademy);
    }

    @Override
    public Optional<Academy> academy(String academyId) {
        return queryOne("read academy " + academyId,
                "SELECT academy_id, short_name, full_name, sort_order FROM district_academy WHERE academy_id=?",
                ps -> ps.setString(1, academyId), SqliteDistrictRepository::readAcademy);
    }

    private static Academy readAcademy(ResultSet rs) throws SQLException {
        return new Academy(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4));
    }

    // ================================================================
    // 自管区
    // ================================================================

    /**
     * 新建的自管区一律没有区务长: V9 的"区务长必须是本学院成员"触发器只在 UPDATE 前触发, 插入时带上区务长就绕过了它。
     * 任命只走 {@link #setWarden} (触发器把关), 所以带区务长的行在这里就拒掉, 不写库。
     */
    @Override
    public void insertDistrict(DistrictRecord d) {
        if (d.wardenUuid() != null || d.wardenName() != null) {
            throw new IllegalArgumentException("district " + d.districtId() + " must be inserted without a warden; "
                    + "appoint one through setWarden (the warden-is-member trigger only guards UPDATE)");
        }
        update("insert district " + d.districtId(),
                "INSERT INTO district (district_id, academy_id, display_name, dimension, min_x, min_z, max_x, max_z, "
                        + "rules_json, warden_uuid, warden_name, unit_price, min_side, max_side, purchase_open, "
                        + "next_plot_no, flan_claim_id, needs_reconcile, created_at, created_by_name) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setString(1, d.districtId());
                    ps.setString(2, d.academyId());
                    ps.setString(3, d.displayName());
                    ps.setString(4, d.bounds().dimension());
                    ps.setInt(5, d.bounds().minX());
                    ps.setInt(6, d.bounds().minZ());
                    ps.setInt(7, d.bounds().maxX());
                    ps.setInt(8, d.bounds().maxZ());
                    ps.setString(9, GSON.toJson(d.rules()));
                    ps.setNull(10, Types.VARCHAR);
                    ps.setNull(11, Types.VARCHAR);
                    ps.setInt(12, d.unitPrice());
                    ps.setInt(13, d.minSide());
                    ps.setInt(14, d.maxSide());
                    ps.setInt(15, d.purchaseOpen() ? 1 : 0);
                    ps.setInt(16, d.nextPlotNo());
                    ps.setString(17, uuidText(d.flanClaimId()));
                    ps.setInt(18, d.needsReconcile() ? 1 : 0);
                    ps.setLong(19, d.createdAt());
                    ps.setString(20, d.createdByName());
                });
        layoutChanged();
    }

    @Override
    public List<DistrictRecord> liveDistricts() {
        return query("list live districts",
                "SELECT " + DISTRICT_COLUMNS + " FROM district d JOIN district_academy a ON a.academy_id = d.academy_id "
                        + "WHERE d.unbound_at IS NULL ORDER BY a.sort_order, d.district_id",
                ps -> {
                }, SqliteDistrictRepository::readDistrict);
    }

    @Override
    public Optional<DistrictRecord> liveDistrict(String districtId) {
        return queryOne("read live district " + districtId,
                "SELECT " + DISTRICT_COLUMNS + " FROM district d WHERE d.district_id=? AND d.unbound_at IS NULL",
                ps -> ps.setString(1, districtId), SqliteDistrictRepository::readDistrict);
    }

    @Override
    public Optional<DistrictRecord> anyDistrict(String districtId) {
        return queryOne("read district " + districtId,
                "SELECT " + DISTRICT_COLUMNS + " FROM district d WHERE d.district_id=?",
                ps -> ps.setString(1, districtId), SqliteDistrictRepository::readDistrict);
    }

    @Override
    public Optional<DistrictRecord> liveDistrictOfAcademy(String academyId) {
        return queryOne("read live district of academy " + academyId,
                "SELECT " + DISTRICT_COLUMNS + " FROM district d WHERE d.academy_id=? AND d.unbound_at IS NULL",
                ps -> ps.setString(1, academyId), SqliteDistrictRepository::readDistrict);
    }

    @Override
    public List<DistrictRecord> districtsOfAcademy(String academyId) {
        return query("list districts of academy " + academyId,
                "SELECT " + DISTRICT_COLUMNS + " FROM district d WHERE d.academy_id=? ORDER BY d.created_at, d.district_id",
                ps -> ps.setString(1, academyId), SqliteDistrictRepository::readDistrict);
    }

    @Override
    public List<DistrictRecord> archivedDistricts(int limit) {
        return query("list archived districts",
                "SELECT " + DISTRICT_COLUMNS + " FROM district d WHERE d.unbound_at IS NOT NULL "
                        + "ORDER BY d.unbound_at DESC, d.district_id DESC LIMIT ?",
                ps -> ps.setInt(1, limit), SqliteDistrictRepository::readDistrict);
    }

    @Override
    public void setWarden(String districtId, @Nullable UUID uuid, @Nullable String name) {
        requireOne("set warden of " + districtId, update("set warden of " + districtId,
                "UPDATE district SET warden_uuid=?, warden_name=? WHERE district_id=?",
                ps -> {
                    ps.setString(1, uuidText(uuid));
                    ps.setString(2, name);
                    ps.setString(3, districtId);
                }));
    }

    @Override
    public void setPricing(String districtId, int unitPrice, int minSide, int maxSide) {
        requireOne("set pricing of " + districtId, update("set pricing of " + districtId,
                "UPDATE district SET unit_price=?, min_side=?, max_side=? WHERE district_id=?",
                ps -> {
                    ps.setInt(1, unitPrice);
                    ps.setInt(2, minSide);
                    ps.setInt(3, maxSide);
                    ps.setString(4, districtId);
                }));
    }

    @Override
    public void setPurchaseOpen(String districtId, boolean open) {
        requireOne("set purchase open of " + districtId, update("set purchase open of " + districtId,
                "UPDATE district SET purchase_open=? WHERE district_id=?",
                ps -> {
                    ps.setInt(1, open ? 1 : 0);
                    ps.setString(2, districtId);
                }));
    }

    @Override
    public void setBounds(String districtId, DistrictBounds bounds) {
        requireOne("set bounds of " + districtId, update("set bounds of " + districtId,
                "UPDATE district SET dimension=?, min_x=?, min_z=?, max_x=?, max_z=? WHERE district_id=?",
                ps -> {
                    ps.setString(1, bounds.dimension());
                    ps.setInt(2, bounds.minX());
                    ps.setInt(3, bounds.minZ());
                    ps.setInt(4, bounds.maxX());
                    ps.setInt(5, bounds.maxZ());
                    ps.setString(6, districtId);
                }));
        layoutChanged();
    }

    @Override
    public void setRules(String districtId, List<String> rules) {
        requireOne("set rules of " + districtId, update("set rules of " + districtId,
                "UPDATE district SET rules_json=? WHERE district_id=?",
                ps -> {
                    ps.setString(1, GSON.toJson(List.copyOf(rules)));
                    ps.setString(2, districtId);
                }));
    }

    @Override
    public void setDistrictClaimId(String districtId, @Nullable UUID claimId) {
        requireOne("set claim id of " + districtId, update("set claim id of " + districtId,
                "UPDATE district SET flan_claim_id=? WHERE district_id=?",
                ps -> {
                    ps.setString(1, uuidText(claimId));
                    ps.setString(2, districtId);
                }));
    }

    @Override
    public int takeNextPlotNo(String districtId) {
        int next = queryOne("read next plot number of " + districtId,
                "SELECT next_plot_no FROM district WHERE district_id=?",
                ps -> ps.setString(1, districtId), rs -> rs.getInt(1))
                .orElseThrow(() -> new DistrictStoreException("district " + districtId + " vanished"));
        requireOne("advance next plot number of " + districtId, update("advance next plot number of " + districtId,
                "UPDATE district SET next_plot_no=? WHERE district_id=?",
                ps -> {
                    ps.setInt(1, next + 1);
                    ps.setString(2, districtId);
                }));
        return next;
    }

    @Override
    public void unbind(String districtId, long at, String byName, int memberCount, int plotCount) {
        requireOne("unbind " + districtId, update("unbind " + districtId,
                "UPDATE district SET unbound_at=?, unbound_by_name=?, unbound_member_count=?, unbound_plot_count=?, "
                        + "warden_uuid=NULL, warden_name=NULL WHERE district_id=? AND unbound_at IS NULL",
                ps -> {
                    ps.setLong(1, at);
                    ps.setString(2, byName);
                    ps.setInt(3, memberCount);
                    ps.setInt(4, plotCount);
                    ps.setString(5, districtId);
                }));
        layoutChanged();
    }

    @Override
    public void markNeedsReconcile(String districtId) {
        update("mark reconcile of " + districtId,
                "UPDATE district SET needs_reconcile=1 WHERE district_id=?", ps -> ps.setString(1, districtId));
    }

    @Override
    public void clearNeedsReconcile(String districtId) {
        update("clear reconcile of " + districtId,
                "UPDATE district SET needs_reconcile=0 WHERE district_id=?", ps -> ps.setString(1, districtId));
    }

    @Nullable
    private static DistrictRecord readDistrict(ResultSet rs) throws SQLException {
        String districtId = rs.getString("district_id");
        UUID warden;
        UUID claim;
        try {
            warden = parseNullableUuid(rs.getString("warden_uuid"));
            claim = parseNullableUuid(rs.getString("flan_claim_id"));
        } catch (IllegalArgumentException malformed) {
            LOGGER.warn("[miningdim] ignoring district {} with a malformed uuid column", districtId);
            return null;
        }
        return new DistrictRecord(
                districtId,
                rs.getString("academy_id"),
                rs.getString("display_name"),
                new DistrictBounds(rs.getString("dimension"), rs.getInt("min_x"), rs.getInt("min_z"),
                        rs.getInt("max_x"), rs.getInt("max_z")),
                parseRules(districtId, rs.getString("rules_json")),
                warden,
                rs.getString("warden_name"),
                rs.getInt("unit_price"),
                rs.getInt("min_side"),
                rs.getInt("max_side"),
                rs.getInt("purchase_open") != 0,
                rs.getInt("next_plot_no"),
                claim,
                rs.getInt("needs_reconcile") != 0,
                rs.getLong("created_at"),
                rs.getString("created_by_name"),
                nullableLong(rs, "unbound_at"),
                rs.getString("unbound_by_name"),
                nullableInt(rs, "unbound_member_count"),
                nullableInt(rs, "unbound_plot_count"));
    }

    private static List<String> parseRules(String districtId, @Nullable String raw) {
        if (raw == null) {
            return List.of();
        }
        try {
            String[] rules = GSON.fromJson(raw, String[].class);
            return rules == null ? List.of() : List.copyOf(Arrays.asList(rules));
        } catch (JsonParseException | NullPointerException malformed) {
            LOGGER.warn("[miningdim] district {} has malformed rules_json; showing no rules", districtId);
            return List.of();
        }
    }

    // ================================================================
    // 学院名单
    // ================================================================

    @Override
    public long insertMember(MemberRecord m) {
        return insertReturningId("insert member " + m.name(),
                "INSERT INTO district_member (player_uuid, player_name, name_lower, academy_id, joined_at, "
                        + "added_by_uuid, added_by_name, sync_status, sync_error) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setString(1, m.uuid().toString());
                    ps.setString(2, m.name());
                    ps.setString(3, m.nameLower());
                    ps.setString(4, m.academyId());
                    ps.setLong(5, m.joinedAt());
                    ps.setString(6, uuidText(m.addedByUuid()));
                    ps.setString(7, m.addedByName());
                    ps.setString(8, m.syncStatus().wire());
                    ps.setString(9, m.syncError());
                });
    }

    @Override
    public Optional<MemberRecord> memberByUuid(UUID uuid) {
        return queryOne("read member " + uuid,
                "SELECT " + MEMBER_COLUMNS + " FROM district_member WHERE player_uuid=?",
                ps -> ps.setString(1, uuid.toString()), SqliteDistrictRepository::readMember);
    }

    @Override
    public Optional<MemberRecord> memberByNameLower(String nameLower) {
        return queryOne("read member by name " + nameLower,
                "SELECT " + MEMBER_COLUMNS + " FROM district_member WHERE name_lower=?",
                ps -> ps.setString(1, nameLower), SqliteDistrictRepository::readMember);
    }

    @Override
    public List<MemberRecord> membersOf(String academyId) {
        return query("list members of " + academyId,
                "SELECT " + MEMBER_COLUMNS + " FROM district_member WHERE academy_id=? ORDER BY id",
                ps -> ps.setString(1, academyId), SqliteDistrictRepository::readMember);
    }

    @Override
    public boolean deleteMember(UUID uuid) {
        return update("delete member " + uuid, "DELETE FROM district_member WHERE player_uuid=?",
                ps -> ps.setString(1, uuid.toString())) > 0;
    }

    @Override
    public void setMemberSync(UUID uuid, ResidentSyncStatus status, @Nullable String error) {
        update("set sync status of member " + uuid,
                "UPDATE district_member SET sync_status=?, sync_error=? WHERE player_uuid=?",
                ps -> {
                    ps.setString(1, status.wire());
                    ps.setString(2, error);
                    ps.setString(3, uuid.toString());
                });
    }

    @Override
    public void rekeyMember(long memberId, UUID newUuid, String newName) {
        requireOne("rekey member " + memberId, update("rekey member " + memberId,
                "UPDATE district_member SET player_uuid=?, player_name=?, name_lower=? WHERE id=?",
                ps -> {
                    ps.setString(1, newUuid.toString());
                    ps.setString(2, newName);
                    ps.setString(3, DistrictTexts.lower(newName));
                    ps.setLong(4, memberId);
                }));
    }

    @Override
    public int countMembers(String academyId) {
        return queryOne("count members of " + academyId,
                "SELECT COUNT(*) FROM district_member WHERE academy_id=?",
                ps -> ps.setString(1, academyId), rs -> rs.getInt(1)).orElse(0);
    }

    @Override
    public SyncCounts countMembersBySync(String academyId) {
        int[] counts = new int[2];
        query("count member sync of " + academyId,
                "SELECT sync_status, COUNT(*) FROM district_member WHERE academy_id=? GROUP BY sync_status",
                ps -> ps.setString(1, academyId), rs -> {
                    String status = rs.getString(1);
                    if (ResidentSyncStatus.PENDING.wire().equals(status)) {
                        counts[0] = rs.getInt(2);
                    } else if (ResidentSyncStatus.FAILED.wire().equals(status)) {
                        counts[1] = rs.getInt(2);
                    }
                    return Boolean.TRUE;
                });
        return new SyncCounts(counts[0], counts[1]);
    }

    @Nullable
    private static MemberRecord readMember(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        try {
            ResidentSyncStatus status = ResidentSyncStatus.fromWire(rs.getString("sync_status"));
            if (status == null) {
                throw new IllegalArgumentException("sync_status");
            }
            return new MemberRecord(id, UUID.fromString(rs.getString("player_uuid")), rs.getString("player_name"),
                    rs.getString("academy_id"), rs.getLong("joined_at"),
                    parseNullableUuid(rs.getString("added_by_uuid")), rs.getString("added_by_name"), status,
                    rs.getString("sync_error"));
        } catch (IllegalArgumentException malformed) {
            LOGGER.warn("[miningdim] ignoring malformed district member row {}", id);
            return null;
        }
    }

    // ================================================================
    // 公共区域开关
    // ================================================================

    @Override
    public PermissionCells districtCells(String districtId) {
        Map<String, Map<String, Boolean>> cells = new LinkedHashMap<>();
        query("read permission cells of " + districtId,
                "SELECT permission_id, audience, enabled FROM district_permission WHERE district_id=?",
                ps -> ps.setString(1, districtId), rs -> {
                    cells.computeIfAbsent(rs.getString(1), ignored -> new LinkedHashMap<>())
                            .put(rs.getString(2), rs.getInt(3) != 0);
                    return Boolean.TRUE;
                });
        return new PermissionCells(cells);
    }

    @Override
    public void setDistrictCell(String districtId, String permissionId, String audience, boolean enabled) {
        update("set permission cell " + permissionId + "/" + audience + " of " + districtId,
                "INSERT INTO district_permission (district_id, permission_id, audience, enabled) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT(district_id, permission_id, audience) DO UPDATE SET enabled=excluded.enabled",
                ps -> {
                    ps.setString(1, districtId);
                    ps.setString(2, permissionId);
                    ps.setString(3, audience);
                    ps.setInt(4, enabled ? 1 : 0);
                });
    }

    @Override
    public int insertDistrictDefaults(String districtId) {
        int inserted = 0;
        for (PermissionItemDef item : PermissionCatalog.items()) {
            for (DistrictAudience audience : DistrictAudience.values()) {
                if (!audience.appliesTo(item.scope())) {
                    continue;
                }
                boolean value = item.districtDefault(audience);
                inserted += update("insert default cell of " + districtId,
                        "INSERT OR IGNORE INTO district_permission (district_id, permission_id, audience, enabled) "
                                + "VALUES (?, ?, ?, ?)",
                        ps -> {
                            ps.setString(1, districtId);
                            ps.setString(2, item.permissionId());
                            ps.setString(3, audience.wire());
                            ps.setInt(4, value ? 1 : 0);
                        });
            }
        }
        return inserted;
    }

    // ================================================================
    // 地块
    // ================================================================

    @Override
    public void insertPlot(PlotRecord p) {
        update("insert plot " + p.plotId(),
                "INSERT INTO district_plot (plot_id, district_id, plot_no, code, min_x, min_z, max_x, max_z, "
                        + "owner_uuid, owner_name, frozen_owner_uuid, frozen_owner_name, frozen_at, tenure, "
                        + "sync_status, sync_error, flan_claim_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setString(1, p.plotId());
                    ps.setString(2, p.districtId());
                    ps.setInt(3, p.plotNo());
                    ps.setString(4, p.code());
                    ps.setInt(5, p.area().minX());
                    ps.setInt(6, p.area().minZ());
                    ps.setInt(7, p.area().maxX());
                    ps.setInt(8, p.area().maxZ());
                    ps.setString(9, uuidText(p.ownerUuid()));
                    ps.setString(10, p.ownerName());
                    ps.setString(11, uuidText(p.frozenOwnerUuid()));
                    ps.setString(12, p.frozenOwnerName());
                    setNullableLong(ps, 13, p.frozenAt());
                    ps.setInt(14, p.tenure());
                    ps.setString(15, p.syncStatus().wire());
                    ps.setString(16, p.syncError());
                    ps.setString(17, uuidText(p.flanClaimId()));
                    ps.setLong(18, p.createdAt());
                });
        layoutChanged();
    }

    @Override
    public Optional<PlotRecord> plot(String plotId) {
        return queryOne("read plot " + plotId,
                "SELECT " + PLOT_COLUMNS + " FROM district_plot p WHERE p.plot_id=?",
                ps -> ps.setString(1, plotId), SqliteDistrictRepository::readPlot);
    }

    @Override
    public List<PlotRecord> plotsOf(String districtId) {
        return query("list plots of " + districtId,
                "SELECT " + PLOT_COLUMNS + " FROM district_plot p WHERE p.district_id=? ORDER BY p.plot_no",
                ps -> ps.setString(1, districtId), SqliteDistrictRepository::readPlot);
    }

    @Override
    public Optional<PlotRecord> plotOwnedBy(UUID owner) {
        return queryOne("read plot owned by " + owner,
                "SELECT " + PLOT_COLUMNS + " FROM district_plot p WHERE p.owner_uuid=?",
                ps -> ps.setString(1, owner.toString()), SqliteDistrictRepository::readPlot);
    }

    @Override
    public List<PlotRecord> frozenPlotsOf(UUID formerOwner) {
        return query("list frozen plots of " + formerOwner,
                "SELECT " + PLOT_COLUMNS + " FROM district_plot p WHERE p.frozen_owner_uuid=? ORDER BY p.frozen_at",
                ps -> ps.setString(1, formerOwner.toString()), SqliteDistrictRepository::readPlot);
    }

    @Override
    public boolean assignOwner(String plotId, UUID owner, String ownerName) {
        return update("assign owner of " + plotId,
                "UPDATE district_plot SET owner_uuid=?, owner_name=? "
                        + "WHERE plot_id=? AND owner_uuid IS NULL AND frozen_owner_uuid IS NULL",
                ps -> {
                    ps.setString(1, owner.toString());
                    ps.setString(2, ownerName);
                    ps.setString(3, plotId);
                }) == 1;
    }

    @Override
    public void freeze(String plotId, UUID formerOwner, String formerName, long at) {
        requireOne("freeze " + plotId, update("freeze " + plotId,
                "UPDATE district_plot SET owner_uuid=NULL, owner_name=NULL, frozen_owner_uuid=?, frozen_owner_name=?, "
                        + "frozen_at=? WHERE plot_id=?",
                ps -> {
                    ps.setString(1, formerOwner.toString());
                    ps.setString(2, formerName);
                    ps.setLong(3, at);
                    ps.setString(4, plotId);
                }));
    }

    @Override
    public void unfreeze(String plotId, UUID owner, String ownerName) {
        requireOne("unfreeze " + plotId, update("unfreeze " + plotId,
                "UPDATE district_plot SET owner_uuid=?, owner_name=?, frozen_owner_uuid=NULL, frozen_owner_name=NULL, "
                        + "frozen_at=NULL WHERE plot_id=?",
                ps -> {
                    ps.setString(1, owner.toString());
                    ps.setString(2, ownerName);
                    ps.setString(3, plotId);
                }));
    }

    @Override
    public void vacate(String plotId) {
        requireOne("vacate " + plotId, update("vacate " + plotId,
                "UPDATE district_plot SET owner_uuid=NULL, owner_name=NULL, frozen_owner_uuid=NULL, "
                        + "frozen_owner_name=NULL, frozen_at=NULL, tenure=tenure+1 WHERE plot_id=?",
                ps -> ps.setString(1, plotId)));
    }

    @Override
    public void releaseOwner(String plotId) {
        requireOne("release owner of " + plotId, update("release owner of " + plotId,
                "UPDATE district_plot SET owner_uuid=NULL, owner_name=NULL WHERE plot_id=?",
                ps -> ps.setString(1, plotId)));
    }

    @Override
    public void rekeyPlotOwner(UUID oldUuid, UUID newUuid, String newName) {
        update("rekey plot owner " + oldUuid,
                "UPDATE district_plot SET owner_uuid=?, owner_name=? WHERE owner_uuid=?",
                ps -> {
                    ps.setString(1, newUuid.toString());
                    ps.setString(2, newName);
                    ps.setString(3, oldUuid.toString());
                });
    }

    @Override
    public void setPlotBounds(String plotId, PlotArea area) {
        requireOne("resize " + plotId, update("resize " + plotId,
                "UPDATE district_plot SET min_x=?, min_z=?, max_x=?, max_z=? WHERE plot_id=?",
                ps -> {
                    ps.setInt(1, area.minX());
                    ps.setInt(2, area.minZ());
                    ps.setInt(3, area.maxX());
                    ps.setInt(4, area.maxZ());
                    ps.setString(5, plotId);
                }));
        layoutChanged();
    }

    @Override
    public void setPlotSync(String plotId, PlotSyncStatus status, @Nullable String error) {
        update("set sync status of plot " + plotId,
                "UPDATE district_plot SET sync_status=?, sync_error=? WHERE plot_id=?",
                ps -> {
                    ps.setString(1, status.wire());
                    ps.setString(2, error);
                    ps.setString(3, plotId);
                });
    }

    @Override
    public void setPlotClaimId(String plotId, @Nullable UUID claimId) {
        update("set claim id of plot " + plotId,
                "UPDATE district_plot SET flan_claim_id=? WHERE plot_id=?",
                ps -> {
                    ps.setString(1, uuidText(claimId));
                    ps.setString(2, plotId);
                });
    }

    @Override
    public void deletePlot(String plotId) {
        requireOne("delete plot " + plotId, update("delete plot " + plotId,
                "DELETE FROM district_plot WHERE plot_id=?", ps -> ps.setString(1, plotId)));
        layoutChanged();
    }

    @Override
    public List<PlotRecord> expiredFrozenPlots(long frozenAtOrBefore, @Nullable String districtId) {
        String sql = "SELECT " + PLOT_COLUMNS + " FROM district_plot p JOIN district d ON d.district_id = p.district_id "
                + "WHERE d.unbound_at IS NULL AND p.frozen_at IS NOT NULL AND p.frozen_at <= ?"
                + (districtId == null ? "" : " AND p.district_id = ?")
                + " ORDER BY p.frozen_at, p.plot_id";
        return query("list expired frozen plots", sql, ps -> {
            ps.setLong(1, frozenAtOrBefore);
            if (districtId != null) {
                ps.setString(2, districtId);
            }
        }, SqliteDistrictRepository::readPlot);
    }

    @Nullable
    private static PlotRecord readPlot(ResultSet rs) throws SQLException {
        String plotId = rs.getString("plot_id");
        try {
            PlotSyncStatus status = PlotSyncStatus.fromWire(rs.getString("sync_status"));
            if (status == null) {
                throw new IllegalArgumentException("sync_status");
            }
            return new PlotRecord(
                    plotId,
                    rs.getString("district_id"),
                    rs.getInt("plot_no"),
                    rs.getString("code"),
                    new PlotArea(rs.getInt("min_x"), rs.getInt("min_z"), rs.getInt("max_x"), rs.getInt("max_z")),
                    parseNullableUuid(rs.getString("owner_uuid")),
                    rs.getString("owner_name"),
                    parseNullableUuid(rs.getString("frozen_owner_uuid")),
                    rs.getString("frozen_owner_name"),
                    nullableLong(rs, "frozen_at"),
                    rs.getInt("tenure"),
                    status,
                    rs.getString("sync_error"),
                    parseNullableUuid(rs.getString("flan_claim_id")),
                    rs.getLong("created_at"));
        } catch (IllegalArgumentException malformed) {
            LOGGER.warn("[miningdim] ignoring malformed district plot row {}", plotId);
            return null;
        }
    }

    // ================================================================
    // 地块开关
    // ================================================================

    @Override
    public PermissionCells plotCells(String plotId) {
        Map<String, Map<String, Boolean>> cells = new LinkedHashMap<>();
        query("read plot cells of " + plotId,
                "SELECT permission_id, audience, enabled FROM district_plot_permission WHERE plot_id=?",
                ps -> ps.setString(1, plotId), rs -> {
                    cells.computeIfAbsent(rs.getString(1), ignored -> new LinkedHashMap<>())
                            .put(rs.getString(2), rs.getInt(3) != 0);
                    return Boolean.TRUE;
                });
        return new PermissionCells(cells);
    }

    @Override
    public void setPlotCell(String plotId, String permissionId, String audience, boolean enabled) {
        update("set plot cell " + permissionId + "/" + audience + " of " + plotId,
                "INSERT INTO district_plot_permission (plot_id, permission_id, audience, enabled) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT(plot_id, permission_id, audience) DO UPDATE SET enabled=excluded.enabled",
                ps -> {
                    ps.setString(1, plotId);
                    ps.setString(2, permissionId);
                    ps.setString(3, audience);
                    ps.setInt(4, enabled ? 1 : 0);
                });
    }

    @Override
    public void resetPlotCells(String plotId) {
        update("clear plot cells of " + plotId, "DELETE FROM district_plot_permission WHERE plot_id=?",
                ps -> ps.setString(1, plotId));
        insertPlotDefaults(plotId);
    }

    @Override
    public int insertPlotDefaults(String plotId) {
        int inserted = 0;
        for (PermissionItemDef item : PermissionCatalog.memberItems()) {
            for (PlotAudience audience : PlotAudience.values()) {
                boolean value = item.plotDefault(audience);
                inserted += update("insert default plot cell of " + plotId,
                        "INSERT OR IGNORE INTO district_plot_permission (plot_id, permission_id, audience, enabled) "
                                + "VALUES (?, ?, ?, ?)",
                        ps -> {
                            ps.setString(1, plotId);
                            ps.setString(2, item.permissionId());
                            ps.setString(3, audience.wire());
                            ps.setInt(4, value ? 1 : 0);
                        });
            }
        }
        return inserted;
    }

    @Override
    public int backfillCells() {
        int inserted = 0;
        for (DistrictRecord district : liveDistricts()) {
            inserted += insertDistrictDefaults(district.districtId());
        }
        List<String> plotIds = query("list all plot ids", "SELECT plot_id FROM district_plot", ps -> {
        }, rs -> rs.getString(1));
        for (String plotId : plotIds) {
            inserted += insertPlotDefaults(plotId);
        }
        return inserted;
    }

    // ================================================================
    // 朋友
    // ================================================================

    @Override
    public List<FriendRecord> friendsOf(String plotId) {
        return query("list friends of " + plotId,
                "SELECT " + FRIEND_COLUMNS + " FROM district_plot_friend f WHERE f.plot_id=? ORDER BY f.id",
                ps -> ps.setString(1, plotId), SqliteDistrictRepository::readFriend);
    }

    @Override
    public List<FriendRecord> friendsInDistrict(String districtId) {
        return query("list friends in " + districtId,
                "SELECT " + FRIEND_COLUMNS + " FROM district_plot_friend f JOIN district_plot p ON p.plot_id = f.plot_id "
                        + "WHERE p.district_id=? ORDER BY p.plot_no, f.id",
                ps -> ps.setString(1, districtId), SqliteDistrictRepository::readFriend);
    }

    @Override
    public List<FriendRecord> friendshipsOf(UUID player, String nameLower) {
        return query("list friendships of " + player,
                "SELECT " + FRIEND_COLUMNS + " FROM district_plot_friend f WHERE f.player_uuid=? "
                        + "OR (f.sync_status='pending' AND f.name_lower=?) ORDER BY f.id",
                ps -> {
                    ps.setString(1, player.toString());
                    ps.setString(2, nameLower);
                }, SqliteDistrictRepository::readFriend);
    }

    @Override
    public List<FriendRecord> pendingFriendsFor(UUID player, String nameLower) {
        return query("list pending friendships of " + player,
                "SELECT " + FRIEND_COLUMNS + " FROM district_plot_friend f WHERE f.sync_status='pending' "
                        + "AND (f.player_uuid=? OR f.name_lower=?) ORDER BY f.id",
                ps -> {
                    ps.setString(1, player.toString());
                    ps.setString(2, nameLower);
                }, SqliteDistrictRepository::readFriend);
    }

    @Override
    public long insertFriend(FriendRecord f) {
        return insertReturningId("insert friend " + f.name() + " of " + f.plotId(),
                "INSERT INTO district_plot_friend (plot_id, player_uuid, player_name, name_lower, added_at, "
                        + "added_by_name, sync_status, suspended_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setString(1, f.plotId());
                    ps.setString(2, f.uuid().toString());
                    ps.setString(3, f.name());
                    ps.setString(4, DistrictTexts.lower(f.name()));
                    ps.setLong(5, f.addedAt());
                    ps.setString(6, f.addedByName());
                    ps.setString(7, f.syncStatus().wire());
                    setNullableLong(ps, 8, f.suspendedAt());
                });
    }

    @Override
    public void deleteFriend(long friendId) {
        requireOne("delete friend " + friendId, update("delete friend " + friendId,
                "DELETE FROM district_plot_friend WHERE id=?", ps -> ps.setLong(1, friendId)));
    }

    @Override
    public void deleteFriends(String plotId) {
        update("delete friends of " + plotId, "DELETE FROM district_plot_friend WHERE plot_id=?",
                ps -> ps.setString(1, plotId));
    }

    @Override
    public void setFriendSuspended(long friendId, @Nullable Long at) {
        requireOne("suspend friend " + friendId, update("suspend friend " + friendId,
                "UPDATE district_plot_friend SET suspended_at=? WHERE id=?",
                ps -> {
                    setNullableLong(ps, 1, at);
                    ps.setLong(2, friendId);
                }));
    }

    @Override
    public void activateFriend(long friendId, UUID uuid, String name) {
        requireOne("activate friend " + friendId, update("activate friend " + friendId,
                "UPDATE district_plot_friend SET player_uuid=?, player_name=?, name_lower=?, sync_status='synced' "
                        + "WHERE id=?",
                ps -> {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, name);
                    ps.setString(3, DistrictTexts.lower(name));
                    ps.setLong(4, friendId);
                }));
    }

    @Nullable
    private static FriendRecord readFriend(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        try {
            FriendSyncStatus status = FriendSyncStatus.fromWire(rs.getString("sync_status"));
            if (status == null) {
                throw new IllegalArgumentException("sync_status");
            }
            return new FriendRecord(id, rs.getString("plot_id"), UUID.fromString(rs.getString("player_uuid")),
                    rs.getString("player_name"), rs.getLong("added_at"), rs.getString("added_by_name"), status,
                    nullableLong(rs, "suspended_at"));
        } catch (IllegalArgumentException malformed) {
            LOGGER.warn("[miningdim] ignoring malformed district friend row {}", id);
            return null;
        }
    }

    // ================================================================
    // 墓碑
    // ================================================================

    @Override
    public void insertTombstone(TombstoneRecord t) {
        update("insert tombstone " + t.plotId(),
                "INSERT INTO district_plot_tombstone (plot_id, district_id, code, min_x, min_z, max_x, max_z, "
                        + "deleted_at, deleted_by_uuid, deleted_by_name) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setString(1, t.plotId());
                    ps.setString(2, t.districtId());
                    ps.setString(3, t.code());
                    ps.setInt(4, t.area().minX());
                    ps.setInt(5, t.area().minZ());
                    ps.setInt(6, t.area().maxX());
                    ps.setInt(7, t.area().maxZ());
                    ps.setLong(8, t.deletedAt());
                    ps.setString(9, uuidText(t.deletedByUuid()));
                    ps.setString(10, t.deletedByName());
                });
    }

    @Override
    public List<TombstoneRecord> tombstones(String districtId, int limit) {
        return query("list tombstones of " + districtId,
                "SELECT plot_id, district_id, code, min_x, min_z, max_x, max_z, deleted_at, deleted_by_uuid, "
                        + "deleted_by_name FROM district_plot_tombstone WHERE district_id=? "
                        + "ORDER BY deleted_at DESC, plot_id DESC LIMIT ?",
                ps -> {
                    ps.setString(1, districtId);
                    ps.setInt(2, limit);
                }, rs -> {
                    UUID deletedBy;
                    try {
                        deletedBy = parseNullableUuid(rs.getString("deleted_by_uuid"));
                    } catch (IllegalArgumentException malformed) {
                        deletedBy = null;
                    }
                    return new TombstoneRecord(rs.getString("plot_id"), rs.getString("district_id"),
                            rs.getString("code"), new PlotArea(rs.getInt("min_x"), rs.getInt("min_z"),
                            rs.getInt("max_x"), rs.getInt("max_z")), rs.getLong("deleted_at"), deletedBy,
                            rs.getString("deleted_by_name"));
                });
    }

    // ================================================================
    // 记录
    // ================================================================

    @Override
    public long insertDistrictLog(DistrictLogEntry e) {
        return insertReturningId("insert district log " + e.action().wire() + " of " + e.districtId(),
                "INSERT INTO district_log (district_id, " + LOG_TAIL_COLUMNS + ") VALUES ("
                        + placeholders(1 + LOG_TAIL_COUNT) + ")",
                ps -> {
                    ps.setString(1, e.districtId());
                    bindLogTail(ps, 2, e.at(), e.actorUuid(), e.actorName(), e.actorRole().wire(), e.action().wire(),
                            e.targetName(), e.reason(), e.permission(), e.area());
                });
    }

    @Override
    public List<DistrictLogEntry> districtLog(String districtId, int limit) {
        return query("read district log of " + districtId,
                "SELECT id, district_id, " + LOG_TAIL_COLUMNS + " FROM district_log WHERE district_id=? "
                        + "ORDER BY at DESC, id DESC LIMIT ?",
                ps -> {
                    ps.setString(1, districtId);
                    ps.setInt(2, limit);
                }, SqliteDistrictRepository::readDistrictLog);
    }

    @Override
    public long insertPlotLog(PlotLogEntry e) {
        return insertReturningId("insert plot log " + e.action().wire() + " of " + e.plotId(),
                "INSERT INTO district_plot_log (plot_id, district_id, tenure, " + LOG_TAIL_COLUMNS
                        + ", on_behalf_of_owner) VALUES (" + placeholders(3 + LOG_TAIL_COUNT + 1) + ")",
                ps -> {
                    ps.setString(1, e.plotId());
                    ps.setString(2, e.districtId());
                    ps.setInt(3, e.tenure());
                    int next = bindLogTail(ps, 4, e.at(), e.actorUuid(), e.actorName(), e.actorRole().wire(),
                            e.action().wire(), e.targetName(), e.reason(), e.permission(), e.area());
                    ps.setInt(next, e.onBehalfOfOwner() ? 1 : 0);
                });
    }

    @Override
    public List<PlotLogEntry> plotLog(String plotId, int minTenure, int maxTenure, int limit) {
        return query("read plot log of " + plotId,
                "SELECT id, plot_id, district_id, tenure, on_behalf_of_owner, " + LOG_TAIL_COLUMNS
                        + " FROM district_plot_log WHERE plot_id=? AND tenure BETWEEN ? AND ? "
                        + "ORDER BY tenure DESC, at DESC, id DESC LIMIT ?",
                ps -> {
                    ps.setString(1, plotId);
                    ps.setInt(2, minTenure);
                    ps.setInt(3, maxTenure);
                    ps.setInt(4, limit);
                }, SqliteDistrictRepository::readPlotLog);
    }

    /** 绑定记录表共有的尾部列 (LOG_TAIL_COLUMNS 的顺序); 返回下一个参数下标。 */
    private static int bindLogTail(PreparedStatement ps, int start, long at, @Nullable UUID actorUuid, String actorName,
                                   String actorRole, String action, @Nullable String targetName,
                                   @Nullable String reason, @Nullable PermissionChange permission,
                                   @Nullable AreaChange area) throws SQLException {
        int i = start;
        ps.setLong(i++, at);
        ps.setString(i++, uuidText(actorUuid));
        ps.setString(i++, actorName);
        ps.setString(i++, actorRole);
        ps.setString(i++, action);
        ps.setString(i++, targetName);
        ps.setString(i++, reason);
        ps.setString(i++, permission == null ? null : permission.permissionId());
        ps.setString(i++, permission == null ? null : permission.label());
        ps.setString(i++, permission == null ? null : permission.audience());
        setNullableLong(ps, i++, permission == null ? null : (permission.from() ? 1L : 0L));
        setNullableLong(ps, i++, permission == null ? null : (permission.to() ? 1L : 0L));
        PlotArea from = area == null ? null : area.from();
        PlotArea to = area == null ? null : area.to();
        i = bindArea(ps, i, from);
        i = bindArea(ps, i, to);
        return i;
    }

    private static int bindArea(PreparedStatement ps, int start, @Nullable PlotArea area) throws SQLException {
        int i = start;
        setNullableLong(ps, i++, area == null ? null : (long) area.minX());
        setNullableLong(ps, i++, area == null ? null : (long) area.minZ());
        setNullableLong(ps, i++, area == null ? null : (long) area.maxX());
        setNullableLong(ps, i++, area == null ? null : (long) area.maxZ());
        return i;
    }

    @Nullable
    private static DistrictLogEntry readDistrictLog(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        DistrictActorRole role = DistrictActorRole.fromWire(rs.getString("actor_role"));
        DistrictLogAction action = DistrictLogAction.fromWire(rs.getString("action"));
        if (role == null || action == null) {
            LOGGER.warn("[miningdim] ignoring district log row {} with an unknown role or action", id);
            return null;
        }
        UUID actor;
        try {
            actor = parseNullableUuid(rs.getString("actor_uuid"));
        } catch (IllegalArgumentException malformed) {
            actor = null;
        }
        return new DistrictLogEntry(id, rs.getString("district_id"), rs.getLong("at"), actor,
                rs.getString("actor_name"), role, action, rs.getString("target_name"), rs.getString("reason"),
                readPermission(rs), readAreaChange(rs));
    }

    @Nullable
    private static PlotLogEntry readPlotLog(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        PlotActorRole role = PlotActorRole.fromWire(rs.getString("actor_role"));
        PlotLogAction action = PlotLogAction.fromWire(rs.getString("action"));
        if (role == null || action == null) {
            LOGGER.warn("[miningdim] ignoring plot log row {} with an unknown role or action", id);
            return null;
        }
        UUID actor;
        try {
            actor = parseNullableUuid(rs.getString("actor_uuid"));
        } catch (IllegalArgumentException malformed) {
            actor = null;
        }
        return new PlotLogEntry(id, rs.getString("plot_id"), rs.getString("district_id"), rs.getInt("tenure"),
                rs.getLong("at"), actor, rs.getString("actor_name"), role, action, rs.getString("target_name"),
                rs.getString("reason"), readPermission(rs), readAreaChange(rs), rs.getInt("on_behalf_of_owner") != 0);
    }

    @Nullable
    private static PermissionChange readPermission(ResultSet rs) throws SQLException {
        String permissionId = rs.getString("perm_id");
        if (permissionId == null) {
            return null;
        }
        return new PermissionChange(permissionId, rs.getString("perm_label"), rs.getString("perm_audience"),
                rs.getInt("perm_from") != 0, rs.getInt("perm_to") != 0);
    }

    @Nullable
    private static AreaChange readAreaChange(ResultSet rs) throws SQLException {
        PlotArea from = readArea(rs, "from_");
        PlotArea to = readArea(rs, "to_");
        return from == null && to == null ? null : new AreaChange(from, to);
    }

    @Nullable
    private static PlotArea readArea(ResultSet rs, String prefix) throws SQLException {
        Long minX = nullableLong(rs, prefix + "min_x");
        if (minX == null) {
            return null;
        }
        return new PlotArea(minX.intValue(), rs.getInt(prefix + "min_z"), rs.getInt(prefix + "max_x"),
                rs.getInt(prefix + "max_z"));
    }

    // ================================================================
    // 见过的玩家
    // ================================================================

    @Override
    public void upsertSeenOnLogin(UUID uuid, String name, long at) {
        update("record login of " + uuid,
                "INSERT INTO district_seen_player (player_uuid, player_name, name_lower, first_seen_at, last_seen_at, "
                        + "source) VALUES (?, ?, ?, ?, ?, 'login') ON CONFLICT(player_uuid) DO UPDATE SET "
                        + "player_name=excluded.player_name, name_lower=excluded.name_lower, "
                        + "last_seen_at=excluded.last_seen_at",
                ps -> {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, name);
                    ps.setString(3, DistrictTexts.lower(name));
                    ps.setLong(4, at);
                    ps.setLong(5, at);
                });
    }

    @Override
    public void touchLastSeen(UUID uuid, long at) {
        update("record logout of " + uuid,
                "UPDATE district_seen_player SET last_seen_at=? WHERE player_uuid=?",
                ps -> {
                    ps.setLong(1, at);
                    ps.setString(2, uuid.toString());
                });
    }

    @Override
    public Optional<SeenPlayer> seenByUuid(UUID uuid) {
        return queryOne("read seen player " + uuid,
                "SELECT player_uuid, player_name, first_seen_at, last_seen_at, source FROM district_seen_player "
                        + "WHERE player_uuid=?",
                ps -> ps.setString(1, uuid.toString()), SqliteDistrictRepository::readSeen);
    }

    @Override
    public Optional<SeenPlayer> seenByNameLower(String nameLower) {
        return queryOne("read seen player by name " + nameLower,
                "SELECT player_uuid, player_name, first_seen_at, last_seen_at, source FROM district_seen_player "
                        + "WHERE name_lower=? ORDER BY last_seen_at DESC LIMIT 1",
                ps -> ps.setString(1, nameLower), SqliteDistrictRepository::readSeen);
    }

    @Override
    public boolean insertSeenBackfill(UUID uuid, String name, long at) {
        return update("backfill seen player " + uuid,
                "INSERT OR IGNORE INTO district_seen_player (player_uuid, player_name, name_lower, first_seen_at, "
                        + "last_seen_at, source) VALUES (?, ?, ?, ?, ?, 'backfill')",
                ps -> {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, name);
                    ps.setString(3, DistrictTexts.lower(name));
                    ps.setLong(4, at);
                    ps.setLong(5, at);
                }) == 1;
    }

    @Nullable
    private static SeenPlayer readSeen(ResultSet rs) throws SQLException {
        String raw = rs.getString("player_uuid");
        try {
            return new SeenPlayer(UUID.fromString(raw), rs.getString("player_name"), rs.getLong("first_seen_at"),
                    rs.getLong("last_seen_at"), rs.getString("source"));
        } catch (IllegalArgumentException malformed) {
            LOGGER.warn("[miningdim] ignoring seen-player row with malformed uuid '{}'", raw);
            return null;
        }
    }

    // ================================================================
    // 通知队列 (22.11)
    // ================================================================

    /** 删除时一条语句最多带几个 id (SQLite 的参数上限默认 999, 留足余量)。 */
    private static final int DELETE_CHUNK = 200;

    @Override
    public long insertNotice(NoticeRecord n) {
        return insertReturningId("insert notice " + n.kind() + " for " + n.recipient(),
                "INSERT INTO district_notice (recipient_uuid, kind, args_json, district_id, plot_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                ps -> {
                    ps.setString(1, n.recipient().toString());
                    ps.setString(2, n.kind());
                    ps.setString(3, GSON.toJson(n.args() == null ? List.of() : n.args()));
                    ps.setString(4, n.districtId());
                    ps.setString(5, n.plotId());
                    ps.setLong(6, n.createdAt());
                });
    }

    @Override
    public List<NoticeRecord> noticesFor(UUID recipient) {
        return query("list notices of " + recipient,
                "SELECT id, recipient_uuid, kind, args_json, district_id, plot_id, created_at FROM district_notice "
                        + "WHERE recipient_uuid=? ORDER BY id",
                ps -> ps.setString(1, recipient.toString()), SqliteDistrictRepository::readNotice);
    }

    @Override
    public int deleteNotices(Collection<Long> ids) {
        List<Long> all = new ArrayList<>(ids);
        int deleted = 0;
        for (int start = 0; start < all.size(); start += DELETE_CHUNK) {
            List<Long> chunk = all.subList(start, Math.min(all.size(), start + DELETE_CHUNK));
            deleted += update("delete " + chunk.size() + " notice(s)",
                    "DELETE FROM district_notice WHERE id IN (" + placeholders(chunk.size()) + ")",
                    ps -> {
                        for (int i = 0; i < chunk.size(); i++) {
                            ps.setLong(i + 1, chunk.get(i));
                        }
                    });
        }
        return deleted;
    }

    @Override
    public int countNotices(UUID recipient) {
        return queryOne("count notices of " + recipient,
                "SELECT COUNT(*) FROM district_notice WHERE recipient_uuid=?",
                ps -> ps.setString(1, recipient.toString()), rs -> rs.getInt(1)).orElse(0);
    }

    @Override
    public int deleteOldestNotices(UUID recipient, int keep, Collection<String> expendableKinds) {
        List<String> expendable = List.copyOf(expendableKinds);
        // 留下的: 先按"不是可先删的"排在前, 再按新的在前; 取前 keep 条, 其余删掉。
        String priority = expendable.isEmpty() ? "0"
                : "CASE WHEN kind IN (" + placeholders(expendable.size()) + ") THEN 0 ELSE 1 END";
        return update("trim notices of " + recipient + " to " + keep,
                "DELETE FROM district_notice WHERE recipient_uuid=? AND id NOT IN (SELECT id FROM district_notice "
                        + "WHERE recipient_uuid=? ORDER BY " + priority + " DESC, id DESC LIMIT ?)",
                ps -> {
                    int i = 1;
                    ps.setString(i++, recipient.toString());
                    ps.setString(i++, recipient.toString());
                    for (String kind : expendable) {
                        ps.setString(i++, kind);
                    }
                    ps.setInt(i, Math.max(0, keep));
                });
    }

    @Override
    public int pruneNoticesBefore(long at) {
        return update("prune notices before " + at, "DELETE FROM district_notice WHERE created_at < ?",
                ps -> ps.setLong(1, at));
    }

    @Override
    public int rekeyNotices(UUID oldUuid, UUID newUuid) {
        return update("rekey notices of " + oldUuid,
                "UPDATE district_notice SET recipient_uuid=? WHERE recipient_uuid=?",
                ps -> {
                    ps.setString(1, newUuid.toString());
                    ps.setString(2, oldUuid.toString());
                });
    }

    /** 收件人读不出来的行跳过 (按收件人查不会遇到); args_json 读不出来的行照样返回, args 为 null, 由投递丢掉。 */
    @Nullable
    private static NoticeRecord readNotice(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        UUID recipient;
        try {
            recipient = UUID.fromString(rs.getString("recipient_uuid"));
        } catch (IllegalArgumentException malformed) {
            LOGGER.warn("[miningdim] ignoring district notice row {} with a malformed recipient", id);
            return null;
        }
        List<String> args = null;
        String raw = rs.getString("args_json");
        try {
            String[] parsed = GSON.fromJson(raw, String[].class);
            if (parsed != null && Arrays.stream(parsed).noneMatch(java.util.Objects::isNull)) {
                args = List.of(parsed);
            }
        } catch (JsonParseException malformed) {
            args = null;
        }
        if (args == null) {
            LOGGER.warn("[miningdim] district notice row {} has malformed args '{}'", id, raw);
        }
        return new NoticeRecord(id, recipient, String.valueOf(rs.getString("kind")), args,
                rs.getString("district_id"), rs.getString("plot_id"), rs.getLong("created_at"));
    }

    // ================================================================
    // JDBC 小工具
    // ================================================================

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        @Nullable
        T map(ResultSet rs) throws SQLException;
    }

    private int update(String operation, String sql, Binder binder) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            return ps.executeUpdate();
        } catch (SQLException exception) {
            throw new DistrictStoreException("failed to " + operation, exception);
        }
    }

    private long insertReturningId(String operation, String sql, Binder binder) {
        update(operation, sql, binder);
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT last_insert_rowid()")) {
            if (!rs.next()) {
                throw new DistrictStoreException("failed to " + operation + ": no generated id");
            }
            return rs.getLong(1);
        } catch (SQLException exception) {
            throw new DistrictStoreException("failed to read the generated id after " + operation, exception);
        }
    }

    private <T> List<T> query(String operation, String sql, Binder binder, RowMapper<T> mapper) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            List<T> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    T row = mapper.map(rs);
                    if (row != null) {
                        rows.add(row);
                    }
                }
            }
            return Collections.unmodifiableList(rows);
        } catch (SQLException exception) {
            throw new DistrictStoreException("failed to " + operation, exception);
        }
    }

    private <T> Optional<T> queryOne(String operation, String sql, Binder binder, RowMapper<T> mapper) {
        List<T> rows = query(operation, sql, binder, mapper);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 按主键更新的写入必须恰好命中一行; 命中 0 行说明调用方刚读到的行已经不在, 属于代码 bug。 */
    private static void requireOne(String operation, int updated) {
        if (updated != 1) {
            throw new DistrictStoreException("failed to " + operation + ": expected 1 row, updated " + updated);
        }
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    @Nullable
    private static String uuidText(@Nullable UUID uuid) {
        return uuid == null ? null : uuid.toString();
    }

    @Nullable
    private static UUID parseNullableUuid(@Nullable String raw) {
        return raw == null ? null : UUID.fromString(raw);
    }

    private static void setNullableLong(PreparedStatement ps, int index, @Nullable Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setLong(index, value);
        }
    }

    @Nullable
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    @Nullable
    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}

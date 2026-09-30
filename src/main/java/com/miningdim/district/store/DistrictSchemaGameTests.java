package com.miningdim.district.store;

import com.miningdim.core.MiningConstants;
import com.miningdim.district.DistrictTestEnv;
import com.miningdim.district.access.Actor;
import com.miningdim.district.core.AcademyCatalog;
import com.miningdim.district.core.AreaChange;
import com.miningdim.district.core.DistrictActorRole;
import com.miningdim.district.core.DistrictBounds;
import com.miningdim.district.core.DistrictLogAction;
import com.miningdim.district.core.DistrictLogEntry;
import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.MemberRecord;
import com.miningdim.district.core.PermissionChange;
import com.miningdim.district.core.PlotActorRole;
import com.miningdim.district.core.PlotArea;
import com.miningdim.district.core.PlotLogAction;
import com.miningdim.district.core.PlotLogEntry;
import com.miningdim.district.core.PlotRecord;
import com.miningdim.district.core.PlotSyncStatus;
import com.miningdim.district.core.ResidentSyncStatus;
import com.miningdim.district.flan.RecordingFlanGateway;
import com.miningdim.district.service.DistrictAdminService;
import com.miningdim.district.service.DistrictContext;
import com.miningdim.district.service.PlotLayoutService;
import com.miningdim.district.service.ServerPlayerDirectory;
import com.miningdim.store.MiningDb;
import com.miningdim.store.MiningSchema;
import com.miningdim.store.SchemaMigrator;
import com.miningdim.testutil.TempStoreDb;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static com.miningdim.district.DistrictTestEnv.T0;
import static com.miningdim.district.DistrictTestEnv.uuidOf;

/**
 * 自管区存储层: V9 的表、约束与触发器, 仓储的读写往返, 以及落盘后重开 (设计文档第四、五章)。
 * 约束类的测试直接打仓储或原始 SQL: 它们是服务层之外的最后一道闸, 服务层的业务码另有测试。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class DistrictSchemaGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "district_store";

    private static final List<String> TABLES = List.of("district_academy", "district_member", "district",
            "district_permission", "district_plot", "district_plot_permission", "district_plot_friend",
            "district_plot_tombstone", "district_log", "district_plot_log", "district_seen_player", "district_notice");

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void v9CreatesEveryDistrictTableAtUserVersion9(GameTestHelper helper) {
        Connection conn = MiningDb.openInMemory();
        try {
            MiningSchema.apply(conn);
            helper.assertTrue(SchemaMigrator.userVersion(conn) == 9,
                    "统一 schema 应用后 user_version 必须是 9, 实为 " + SchemaMigrator.userVersion(conn));
            for (String table : TABLES) {
                helper.assertTrue(SchemaMigrator.tableExists(conn, table), "缺表 " + table);
            }
            long triggers = count(conn, "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' "
                    + "AND name LIKE 'trg_district_%'");
            helper.assertTrue(triggers == 4, "V9 应建 4 个触发器, 实为 " + triggers);
            long partial = count(conn, "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name IN "
                    + "('ux_district_live_academy', 'ux_district_plot_owner')");
            helper.assertTrue(partial == 2, "两个部分唯一索引都必须存在, 实为 " + partial);
            long noticeIndex = count(conn, "SELECT COUNT(*) FROM sqlite_master WHERE type='index' "
                    + "AND name='idx_district_notice_recipient'");
            helper.assertTrue(noticeIndex == 1, "通知队列按 (recipient_uuid, id) 的索引必须存在 (22.11)");
        } finally {
            MiningDb.close(conn);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void oneAcademyPerPlayerByUuid(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            UUID uuid = uuidOf("Solo_Sam");
            env.repo.insertMember(member(uuid, "Solo_Sam", "abydos"));
            helper.assertTrue(rejects(() -> env.repo.insertMember(member(uuid, "Solo_Sam_Two", "millennium"))),
                    "同一 UUID 进第二个学院必须被 player_uuid 唯一约束拒绝");
            helper.assertTrue(env.repo.membersOf("millennium").isEmpty(), "被拒后千年名单必须仍为空");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void caseInsensitiveNameIsUniqueAcrossAcademies(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.repo.insertMember(member(uuidOf("Pebble_Fox"), "Pebble_Fox", "abydos"));
            helper.assertTrue(rejects(() -> env.repo.insertMember(member(uuidOf("pebble_fox"), "pebble_fox",
                            "millennium"))),
                    "大小写不同的同一个名字 (不同的离线 UUID) 必须被 name_lower 唯一约束拒绝");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void onePlotPerOwnerAcrossDistricts(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_One");
            String first = env.plot("abydos", 10, 10, 25, 25);
            env.own(first, "Owner_One");
            env.ctx.admin().unbind(env.admin, "abydos");
            // 解绑不删 Flan 领地: 同一个学院重新绑回旧地走 bind 收编旧的父领地 (20.6)。
            DistrictRecord rebound = env.rebind("abydos", 5, 5);
            helper.assertTrue("abydos-2".equals(rebound.districtId()),
                    "重新绑定的自管区 id 应为 abydos-2, 实为 " + rebound.districtId());
            String second = env.plot("abydos-2", 10, 10, 25, 25);
            helper.assertTrue(rejects(() -> env.repo.inTransaction(
                            () -> env.repo.assignOwner(second, uuidOf("Owner_One"), "Owner_One"))),
                    "一人一块地的部分唯一索引对全部地块生效 (含已解绑自管区的地块)");
            helper.assertTrue(env.plotRecord(second).vacant(), "被拒后新地块必须仍然空置");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ownerAndFrozenOwnerAreExclusive(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_One");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.own(plot, "Owner_One");
            helper.assertTrue(rejects(() -> env.exec("UPDATE district_plot SET frozen_owner_uuid='x', "
                            + "frozen_owner_name='x', frozen_at=1 WHERE plot_id='" + plot + "'")),
                    "户主与冻结中的原户主必须互斥 (CHECK)");
            helper.assertTrue(rejects(() -> env.exec("UPDATE district_plot SET owner_uuid=NULL WHERE plot_id='"
                            + plot + "'")),
                    "户主名与 UUID 必须同时有或同时无 (CHECK)");
            helper.assertTrue(env.plotRecord(plot).owned(), "被拒后地块必须仍归原户主");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void newPlotMustBeVacant(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_One");
            PlotRecord owned = new PlotRecord("abydos-99", "abydos", 99, "阿拜多斯-99", new PlotArea(10, 10, 25, 25),
                    uuidOf("Owner_One"), "Owner_One", null, null, null, 1, PlotSyncStatus.SYNCED, null, null, T0);
            helper.assertTrue(rejects(() -> env.repo.insertPlot(owned)), "带户主插入新地块必须被触发器拒绝");
            helper.assertTrue(env.repo.plot("abydos-99").isEmpty(), "被拒后不得留下地块行");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ownerMustBeMemberOfTheAcademy(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.millennium();
            env.resident("millennium", "Mill_Mo");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            helper.assertTrue(rejects(() -> env.repo.inTransaction(
                            () -> env.repo.assignOwner(plot, uuidOf("Stranger_Stu"), "Stranger_Stu"))),
                    "不在任何名单上的人不能成为户主 (触发器)");
            helper.assertTrue(rejects(() -> env.repo.inTransaction(
                            () -> env.repo.assignOwner(plot, uuidOf("Mill_Mo"), "Mill_Mo"))),
                    "别的学院的成员不能成为本区地块的户主 (触发器)");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wardenMustBeMemberOfTheAcademy(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.millennium();
            env.resident("millennium", "Mill_Mo");
            helper.assertTrue(rejects(() -> env.repo.setWarden("abydos", uuidOf("Stranger_Stu"), "Stranger_Stu")),
                    "不在名单上的人不能当区务长 (触发器)");
            helper.assertTrue(rejects(() -> env.repo.setWarden("abydos", uuidOf("Mill_Mo"), "Mill_Mo")),
                    "别的学院的成员不能当本区区务长 (触发器)");
            helper.assertTrue(env.districtRecord("abydos").wardenUuid() == null, "被拒后本区仍没有区务长");

            // 触发器只管 UPDATE: 插入时带区务长会绕过它, 所以仓储在插入这一步就拒 (连本学院的成员也不许, 任命只走 setWarden)。
            env.repo.insertMember(member(uuidOf("Geh_Gil"), "Geh_Gil", "gehenna"));
            for (UUID warden : List.of(uuidOf("Stranger_Stu"), uuidOf("Mill_Mo"), uuidOf("Geh_Gil"))) {
                DistrictRecord withWarden = new DistrictRecord("gehenna", "gehenna", "格赫娜自管区",
                        new DistrictBounds(DistrictTestEnv.DIMENSION, 2000, 0, 2199, 199), List.of(), warden, "x", 5,
                        8, 48, false, 1, null, false, T0, "op", null, null, null, null);
                helper.assertTrue(rejects(() -> env.repo.insertDistrict(withWarden)),
                        "带区务长插入新自管区必须被拒: " + warden);
            }
            DistrictRecord nameOnly = new DistrictRecord("gehenna", "gehenna", "格赫娜自管区",
                    new DistrictBounds(DistrictTestEnv.DIMENSION, 2000, 0, 2199, 199), List.of(), null, "Geh_Gil", 5,
                    8, 48, false, 1, null, false, T0, "op", null, null, null, null);
            helper.assertTrue(rejects(() -> env.repo.insertDistrict(nameOnly)), "只带区务长名字也拒");
            helper.assertTrue(env.repo.anyDistrict("gehenna").isEmpty(), "被拒的插入一行都不写");
            DistrictRecord created = env.district("gehenna", 2000, 0, 2199, 199);
            helper.assertTrue(created.wardenUuid() == null && created.wardenName() == null,
                    "建区不带区务长, 实为 " + created);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void memberWithPlotOrWardenshipCannotBeDeleted(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_One");
            env.resident("abydos", "Warden_Wu");
            env.warden("abydos", "Warden_Wu");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.own(plot, "Owner_One");
            helper.assertTrue(rejects(() -> env.repo.deleteMember(uuidOf("Owner_One"))),
                    "还是户主的成员不能被删除 (触发器)");
            helper.assertTrue(rejects(() -> env.repo.deleteMember(uuidOf("Warden_Wu"))),
                    "还是区务长的成员不能被删除 (触发器)");
            helper.assertTrue(env.repo.membersOf("abydos").size() == 2, "被拒后名单必须仍是 2 人");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void onlyOneLiveBindingPerAcademy(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            DistrictAdminService.CommandResult again = env.ctx.admin().createDistrict(Actor.console(), "abydos",
                    new DistrictBounds(DistrictTestEnv.DIMENSION, 500, 500, 600, 600), null);
            helper.assertTrue(again.outcome() == DistrictAdminService.CommandOutcome.ACADEMY_ALREADY_BOUND,
                    "学院已有在用的自管区时建区必须被拒, 实为 " + again.outcome());
            DistrictRecord sneaky = new DistrictRecord("abydos-x", "abydos", "阿拜多斯自管区",
                    new DistrictBounds(DistrictTestEnv.DIMENSION, 500, 500, 600, 600), List.of(), null, null, 5, 8,
                    48, false, 1, null, false, T0, "op", null, null, null, null);
            helper.assertTrue(rejects(() -> env.repo.insertDistrict(sneaky)),
                    "绕过服务直接插第二个未解绑绑定必须被部分唯一索引拒绝");
            env.ctx.admin().unbind(env.admin, "abydos");
            DistrictRecord rebound = env.district("abydos", 500, 500, 700, 700);
            helper.assertTrue("abydos-2".equals(rebound.districtId()), "解绑后可以重新绑定, 新 id 为 abydos-2, 实为 "
                    + rebound.districtId());
            helper.assertTrue(env.repo.anyDistrict("abydos").map(d -> !d.live()).orElse(false),
                    "旧绑定作为归档行保留");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unboundDistrictHasNoWarden(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Warden_Wu");
            env.warden("abydos", "Warden_Wu");
            helper.assertTrue(rejects(() -> env.exec("UPDATE district SET unbound_at=1, unbound_by_name='x' "
                            + "WHERE district_id='abydos'")),
                    "已解绑的自管区不能留着区务长 (CHECK)");
            env.ctx.admin().unbind(env.admin, "abydos");
            DistrictRecord archived = env.districtRecord("abydos");
            helper.assertTrue(archived.wardenUuid() == null && archived.wardenName() == null,
                    "解绑必须一并清空区务长");
            helper.assertTrue(archived.unboundAt() != null && archived.unboundMemberCount() == 1,
                    "解绑写入时间与当时的人数 1, 实为 " + archived.unboundMemberCount());
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void logPayloadsRoundTripNewestFirst(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            PermissionChange change = new PermissionChange("place", "放置方块", "outsider", false, true);
            AreaChange area = new AreaChange(new PlotArea(1, 2, 3, 4), null);
            long first = env.repo.insertDistrictLog(new DistrictLogEntry(0, "abydos", 500, null, "控制台",
                    DistrictActorRole.ADMIN, DistrictLogAction.PERMISSION, null, null, change, null));
            long second = env.repo.insertDistrictLog(new DistrictLogEntry(0, "abydos", 500, uuidOf("Op_Admin"),
                    "Op_Admin", DistrictActorRole.ADMIN, DistrictLogAction.DELETE_PLOT, "阿拜多斯-01", "删", null, area));
            List<DistrictLogEntry> log = env.repo.districtLog("abydos", 10).stream()
                    .filter(entry -> entry.at() == 500).toList();
            helper.assertTrue(log.size() == 2 && log.get(0).id() == second && log.get(1).id() == first,
                    "同一 at 的记录后插入的排在前面, 实为 " + log);
            helper.assertTrue(change.equals(log.get(1).permission()), "改权限记录的内容必须原样读回");
            helper.assertTrue(area.equals(log.get(0).area()), "范围变化 (to 为 null) 必须原样读回");
            helper.assertTrue(log.get(1).actorUuid() == null && "控制台".equals(log.get(1).actorName()),
                    "控制台操作人的 UUID 为 null");

            env.repo.insertPlotLog(new PlotLogEntry(0, "abydos-07", "abydos", 1, 600, null, "Op_Admin",
                    PlotActorRole.ADMIN, PlotLogAction.ADD_FRIEND, "Fay", null, null, null, true));
            env.repo.insertPlotLog(new PlotLogEntry(0, "abydos-07", "abydos", 2, 700, null, "服务器",
                    PlotActorRole.SYSTEM, PlotLogAction.VACATE, "Fay", "x", null, null, false));
            List<PlotLogEntry> current = env.repo.plotLog("abydos-07", 2, 2, 10);
            List<PlotLogEntry> all = env.repo.plotLog("abydos-07", 1, Integer.MAX_VALUE, 10);
            helper.assertTrue(current.size() == 1 && current.get(0).action() == PlotLogAction.VACATE,
                    "按任期过滤只读到本任期的行, 实为 " + current);
            helper.assertTrue(all.size() == 2 && all.get(1).onBehalfOfOwner() && all.get(1).tenure() == 1,
                    "全部任期按任期新的在前, 代改标记原样读回, 实为 " + all);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void fullStateSurvivesReopen(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        Path file = dir.resolve("district.db");
        String plotId;
        try {
            Connection first = TempStoreDb.openUnified(file);
            try {
                SqliteDistrictRepository repo = new SqliteDistrictRepository(first);
                repo.ensureAcademies(AcademyCatalog.LAUNCH, T0);
                AtomicLong clock = new AtomicLong(T0);
                DistrictContext ctx = DistrictContext.of(repo, new RecordingFlanGateway(), clock::get, () -> null,
                        ServerPlayerDirectory.seenTableOnly(repo, clock::get));
                Actor admin = DistrictTestEnv.op("Op_Admin");
                ctx.admin().createDistrict(Actor.console(), "abydos",
                        new DistrictBounds(DistrictTestEnv.DIMENSION, 0, 0, 199, 199), 7);
                ctx.admin().createDistrict(Actor.console(), "millennium",
                        new DistrictBounds(DistrictTestEnv.DIMENSION, 1000, 0, 1199, 199), null);
                repo.upsertSeenOnLogin(uuidOf("Keeper_Kay"), "Keeper_Kay", T0);
                repo.upsertSeenOnLogin(uuidOf("Friend_Fay"), "Friend_Fay", T0);
                ctx.residents().addResident(admin, "abydos", "Keeper_Kay", false);
                ctx.residents().addResident(admin, "abydos", "Late_Lou", true);
                plotId = ctx.layout().create(admin, "abydos",
                        PlotLayoutService.AreaInput.of(new PlotArea(10, 10, 25, 25))).plot().plotId();
                String doomed = ctx.layout().create(admin, "abydos",
                        PlotLayoutService.AreaInput.of(new PlotArea(40, 10, 55, 25))).plot().plotId();
                repo.inTransaction(() -> repo.assignOwner(plotId, uuidOf("Keeper_Kay"), "Keeper_Kay"));
                ctx.plotOwners().addFriend(admin, "abydos", plotId, "Friend_Fay", false);
                ctx.layout().delete(admin, "abydos", doomed);
                ctx.admin().unbind(admin, "millennium");
            } finally {
                MiningDb.close(first);
            }

            Connection second = MiningDb.openAt(file);
            try {
                helper.assertTrue(SchemaMigrator.userVersion(second) == 9, "重开后 user_version 仍是 9");
                SqliteDistrictRepository repo = new SqliteDistrictRepository(second);
                DistrictRecord abydos = repo.liveDistrict("abydos").orElse(null);
                helper.assertTrue(abydos != null && abydos.unitPrice() == 7 && abydos.bounds().maxX() == 199
                                && abydos.nextPlotNo() == 3,
                        "自管区行必须原样落盘 (单价 7、下一个编号 3), 实为 " + abydos);
                List<MemberRecord> members = repo.membersOf("abydos");
                helper.assertTrue(members.size() == 2 && members.get(1).syncStatus() == ResidentSyncStatus.PENDING,
                        "名单与待生效状态必须落盘, 实为 " + members);
                PlotRecord plot = repo.plot(plotId).orElse(null);
                helper.assertTrue(plot != null && "Keeper_Kay".equals(plot.ownerName()), "户主必须落盘");
                helper.assertTrue(repo.friendsOf(plotId).size() == 1, "朋友必须落盘");
                helper.assertTrue(repo.plotCells(plotId).asMap().size() == 29, "三列开关每一项都必须落盘");
                helper.assertTrue(repo.districtCells("abydos").asMap().size() == 36, "公共区域开关每一项都必须落盘");
                helper.assertTrue(repo.tombstones("abydos", 10).size() == 1, "墓碑必须落盘");
                helper.assertTrue(repo.plotLog(plotId, 1, Integer.MAX_VALUE, 10).size() == 2,
                        "地块记录 (create + addFriend) 必须落盘");
                helper.assertTrue(repo.districtLog("abydos", 10).size() == 5,
                        "本区记录 (add ×2、createPlot ×2、deletePlot) 必须落盘, 实为 "
                                + repo.districtLog("abydos", 10).size());
                helper.assertTrue(repo.archivedDistricts(10).stream().anyMatch(d -> d.districtId().equals("millennium")),
                        "归档行必须落盘");
                helper.assertTrue(repo.seenByUuid(uuidOf("Keeper_Kay")).isPresent(), "见过的玩家表必须落盘");
            } finally {
                MiningDb.close(second);
            }
        } finally {
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    private static MemberRecord member(UUID uuid, String name, String academyId) {
        return new MemberRecord(0, uuid, name, academyId, T0, null, "op", ResidentSyncStatus.SYNCED, null);
    }

    private static boolean rejects(Runnable body) {
        try {
            body.run();
            return false;
        } catch (DistrictStoreException | IllegalStateException | IllegalArgumentException expected) {
            return true;
        }
    }

    private static long count(Connection conn, String sql) {
        try (Statement statement = conn.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1;
        } catch (SQLException exception) {
            throw new IllegalStateException(sql, exception);
        }
    }
}

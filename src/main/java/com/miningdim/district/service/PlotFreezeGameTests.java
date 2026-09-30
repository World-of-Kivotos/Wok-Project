package com.miningdim.district.service;

import com.miningdim.core.MiningConstants;
import com.miningdim.district.DistrictLimits;
import com.miningdim.district.DistrictSystem;
import com.miningdim.district.DistrictTestEnv;
import com.miningdim.district.core.DistrictActorRole;
import com.miningdim.district.core.DistrictError;
import com.miningdim.district.core.DistrictLogAction;
import com.miningdim.district.core.DistrictLogEntry;
import com.miningdim.district.core.DistrictRuleException;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.FriendRecord;
import com.miningdim.district.core.PermissionCatalog;
import com.miningdim.district.core.PermissionItemDef;
import com.miningdim.district.core.PlotActorRole;
import com.miningdim.district.core.PlotLogAction;
import com.miningdim.district.core.PlotLogEntry;
import com.miningdim.district.core.PlotRecord;
import com.miningdim.district.core.PlotStatus;
import com.miningdim.district.core.PlotSyncStatus;
import com.miningdim.district.core.ResidentSyncStatus;
import com.miningdim.district.flan.FlanGroupNames;
import com.miningdim.district.flan.FlanPermissions;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

import static com.miningdim.district.DistrictTestEnv.DAY;
import static com.miningdim.district.DistrictTestEnv.T0;
import static com.miningdim.district.DistrictTestEnv.expect;
import static com.miningdim.district.DistrictTestEnv.player;

/**
 * 冻结与收回 (admin.plot.unfreeze / admin.plot.reclaimNow 与到期收回, 设计文档第十一章): 解冻照原样还回朋友与三列, 收回把记录归档并开新任期, 到期收回用
 * 可拨时钟驱动、操作人为服务器、缘由固定, 写动作先收本区到期的地块, 读动作从不收回。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class PlotFreezeGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "district_plot_freeze";

    /** 阿拜多斯 abydos-01 归 Owner_One, 有一位朋友 Resident_Rae; 三列改过一格; 然后 Owner_One 被移出, 地块冻结于 T0。 */
    private static String frozenPlot(DistrictTestEnv env) {
        env.abydos();
        env.resident("abydos", "Owner_One");
        env.resident("abydos", "Resident_Rae");
        String plot = env.plot("abydos", 10, 10, 25, 25);
        env.own(plot, "Owner_One");
        env.ctx.plotOwners().addFriend(player("Owner_One"), "abydos", plot, "Resident_Rae", false);
        env.ctx.plotOwners().setPermission(player("Owner_One"), "abydos", plot, "place", "resident", true);
        env.ctx.residents().removeResident(env.admin, "abydos", "Owner_One", "inactive", "不上线");
        return plot;
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unfreezeRestoresFriendsAndPermissionsExactly(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            String plot = frozenPlot(env);
            List<FriendRecord> friendsBefore = env.repo.friendsOf(plot);
            var cellsBefore = env.repo.plotCells(plot).asMap();
            env.ctx.residents().addResident(env.admin, "abydos", "Owner_One", false);
            env.advance(DAY);
            PlotFreezeService.UnfreezeResult result = env.ctx.freezes().unfreeze(env.admin, "abydos", plot);
            PlotRecord back = env.plotRecord(plot);
            helper.assertTrue(back.owned() && "Owner_One".equals(back.ownerName()) && back.frozenAt() == null
                            && back.tenure() == 1 && back.syncStatus() == PlotSyncStatus.SYNCED,
                    "解冻还给原户主, 任期不变, 实为 " + back);
            helper.assertTrue(env.repo.friendsOf(plot).equals(friendsBefore)
                            && env.repo.plotCells(plot).asMap().equals(cellsBefore),
                    "朋友和三列照原样还回去");
            helper.assertTrue("Owner_One".equals(result.ownerName())
                            && result.logEntry().action() == DistrictLogAction.UNFREEZE_PLOT
                            && "还给原户主 Owner_One".equals(result.logEntry().reason()),
                    "本区记录 unfreezePlot, 实为 " + result.logEntry());
            PlotLogEntry note = env.repo.plotLog(plot, 1, 1, 1).get(0);
            helper.assertTrue(note.action() == PlotLogAction.UNFREEZE && DistrictTexts.PLOT_NOTE_UNFREEZE.equals(note.reason())
                            && "Owner_One".equals(note.targetName()) && note.actorRole() == PlotActorRole.ADMIN,
                    "地块记录 unfreeze, 实为 " + note);
            UUID claim = back.flanClaimId();
            helper.assertTrue(FlanGroupNames.plotOwner(plot).equals(env.recording().membersOf(claim)
                            .get(DistrictTestEnv.uuidOf("Owner_One")))
                            && FlanGroupNames.plotFriend(plot).equals(env.recording().membersOf(claim)
                            .get(DistrictTestEnv.uuidOf("Resident_Rae"))),
                    "照库里存的设置整块重写");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unfreezeChecksInOrder(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            String plot = frozenPlot(env);
            String other = env.plot("abydos", 40, 10, 55, 25);
            PlotFreezeService freezes = env.ctx.freezes();
            expect(helper, DistrictError.PERMISSION_DENIED, "非 OP 解冻",
                    () -> freezes.unfreeze(player("Resident_Rae"), "nowhere", plot));
            DistrictRuleException notFrozen = expect(helper, DistrictError.PLOT_NOT_FROZEN, "空置地块解冻",
                    () -> freezes.unfreeze(env.admin, "abydos", other));
            helper.assertTrue(notFrozen.getMessage().equals("阿拜多斯-02 没有冻结"), "文案");
            DistrictRuleException gone = expect(helper, DistrictError.FORMER_OWNER_NOT_RESIDENT, "原户主不在名单上",
                    () -> freezes.unfreeze(env.admin, "abydos", plot));
            helper.assertTrue(gone.getMessage().equals("原户主 Owner_One 现在不是本区住户，先把 TA 加回本区才能解除冻结"),
                    "文案, 实为 " + gone.getMessage());
            env.ctx.residents().addResident(env.admin, "abydos", "Owner_One", false);
            env.own(other, "Owner_One");
            DistrictRuleException elsewhere = expect(helper, DistrictError.ALREADY_OWNS_PLOT, "原户主回来后已有别的地块",
                    () -> freezes.unfreeze(env.admin, "abydos", plot));
            helper.assertTrue(elsewhere.getMessage().equals("Owner_One 回来后已经有了 阿拜多斯-02，一人最多一块"),
                    "文案, 实为 " + elsewhere.getMessage());
            helper.assertTrue(env.plotRecord(plot).frozen(), "被拒时仍冻结");
        }
        helper.succeed();
    }

    /**
     * 原户主按 UUID 认; 名字 (不分大小写) 只认还只有名字的待生效行。离线 UUID 随名字的大小写变: owner_one 与 Owner_One
     * 是两个账号, 已知玩家 owner_one 加进本区后不能按名字把 Owner_One 冻结着的地块领走 (加住户回执不提它、canRestore
     * 为假、解冻报 FORMER_OWNER_NOT_RESIDENT); 朋友列表同理不按名字串到别的账号上。以待生效记入的 ghost_gus 则照旧按
     * 名字认回 Ghost_Gus 的地块。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void formerOwnerIsMatchedByUuidAndNameOnlyForPendingRows(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            String plot = frozenPlot(env);
            env.advance(DAY);
            env.seen("owner_one");
            ResidentService.AddResult other = env.ctx.residents().addResident(env.admin, "abydos", "owner_one", false);
            helper.assertTrue(other.resident().uuid().equals(DistrictTestEnv.uuidOf("owner_one"))
                            && other.resident().syncStatus() != ResidentSyncStatus.PENDING && other.frozenPlot() == null,
                    "同名的另一个已知账号: 加住户回执不提别人冻结着的地块, 实为 " + other);
            DistrictQueryService.PlotSummaryView row = env.ctx.queries().plots(env.admin, null, "abydos").plots().get(0);
            helper.assertTrue(row.freeze() != null && !row.freeze().canRestore(), "canRestore 按 UUID 判, 不按名字");
            expect(helper, DistrictError.FORMER_OWNER_NOT_RESIDENT, "名字相同而 UUID 不同的已知玩家不能领走冻结的地块",
                    () -> env.ctx.freezes().unfreeze(env.admin, "abydos", plot));
            helper.assertTrue(env.plotRecord(plot).frozen(), "被拒时仍冻结");

            env.ctx.residents().addResident(env.admin, "abydos", "Ghost_Gus", true);
            String ghostPlot = env.plot("abydos", 40, 10, 55, 25);
            env.own(ghostPlot, "Ghost_Gus");
            env.ctx.residents().removeResident(env.admin, "abydos", "Ghost_Gus", "inactive", "不上线");
            ResidentService.AddResult back = env.ctx.residents().addResident(env.admin, "abydos", "ghost_gus", true);
            helper.assertTrue(back.resident().syncStatus() == ResidentSyncStatus.PENDING
                            && !back.resident().uuid().equals(DistrictTestEnv.uuidOf("Ghost_Gus"))
                            && back.frozenPlot() != null && ghostPlot.equals(back.frozenPlot().plotId()),
                    "只有名字的待生效行照旧按名字认回原来的地块, 实为 " + back);
            PlotFreezeService.UnfreezeResult restored = env.ctx.freezes().unfreeze(env.admin, "abydos", ghostPlot);
            helper.assertTrue("ghost_gus".equals(restored.ownerName())
                            && env.plotRecord(ghostPlot).isOwner(DistrictTestEnv.uuidOf("ghost_gus")),
                    "解冻给待生效的那一行, 实为 " + restored.ownerName());

            env.seen("Friend_Fay");
            env.ctx.plotOwners().addFriend(player("ghost_gus"), "abydos", ghostPlot, "Friend_Fay", false);
            helper.assertTrue(env.ctx.queries().state(player("Friend_Fay")).friendOf().size() == 1
                            && env.ctx.queries().state(player("friend_fay")).friendOf().isEmpty(),
                    "已生效的朋友行只认 UUID: 同名的另一个账号看不到这份朋友身份");
        }
        helper.succeed();
    }

    /**
     * 到期收回每块地一个事务: 中间那块地的收回事务注入故障, 只有它整块回滚 (仍冻结、任期不变、不写记录), 前后两块照常
     * 收回; 故障排除后下一轮补上。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void expiryFailureOfOnePlotDoesNotBlockOthers(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            List<String> plots = List.of(env.plot("abydos", 10, 10, 25, 25), env.plot("abydos", 40, 10, 55, 25),
                    env.plot("abydos", 70, 10, 85, 25));
            List<String> owners = List.of("Owner_One", "Owner_Two", "Owner_Three");
            for (int i = 0; i < plots.size(); i++) {
                env.resident("abydos", owners.get(i));
                env.own(plots.get(i), owners.get(i));
            }
            for (String owner : owners) {
                env.ctx.residents().removeResident(env.admin, "abydos", owner, "inactive", "不上线");
            }
            String stuck = plots.get(1);
            env.advance(DistrictLimits.FREEZE_MS);
            int logRows = env.repo.districtLog("abydos", 100).size();
            env.exec("CREATE TEMP TRIGGER fail_one_vacate BEFORE INSERT ON district_plot_log WHEN NEW.plot_id = '"
                    + stuck + "' BEGIN SELECT RAISE(ABORT, 'injected failure'); END");
            int reclaimed = env.ctx.freezes().reclaimExpired(null);
            env.exec("DROP TRIGGER fail_one_vacate");
            helper.assertTrue(reclaimed == 2 && env.plotRecord(plots.get(0)).vacant()
                            && env.plotRecord(plots.get(2)).vacant(),
                    "出错的那块不挡后面的: 前后两块照常收回, 实为 " + reclaimed);
            PlotRecord frozen = env.plotRecord(stuck);
            helper.assertTrue(frozen.frozen() && frozen.tenure() == 1 && "Owner_Two".equals(frozen.frozenOwnerName()),
                    "出错的那块整块回滚: 仍冻结, 任期不变, 实为 " + frozen);
            helper.assertTrue(env.repo.districtLog("abydos", 100).size() == logRows + 2
                            && env.repo.plotLog(stuck, 2, 2, 10).isEmpty(),
                    "只写了成功那两块的记录");
            helper.assertTrue(env.ctx.freezes().reclaimExpired(null) == 1 && env.plotRecord(stuck).vacant(),
                    "故障排除后下一轮补上");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void reclaimNowArchivesLogAndStartsNewTenureWithVacate(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            String plot = frozenPlot(env);
            String vacant = env.plot("abydos", 40, 10, 55, 25);
            DistrictRuleException notFrozen = expect(helper, DistrictError.PLOT_NOT_FROZEN, "空置地块立即收回",
                    () -> env.ctx.freezes().reclaimNow(env.admin, "abydos", vacant));
            helper.assertTrue(notFrozen.getMessage().equals("阿拜多斯-02 没有冻结；只有冻结中的地块能立即收回"), "文案");
            env.advance(DAY);
            DistrictLogEntry log = env.ctx.freezes().reclaimNow(env.admin, "abydos", plot);
            helper.assertTrue(log.action() == DistrictLogAction.VACATE_PLOT
                            && "管理员立即收回（原户主 Owner_One）".equals(log.reason())
                            && log.actorRole() == DistrictActorRole.ADMIN,
                    "本区记录 vacatePlot, 实为 " + log);
            PlotRecord vacated = env.plotRecord(plot);
            helper.assertTrue(vacated.vacant() && vacated.tenure() == 2 && vacated.frozenOwnerUuid() == null,
                    "收回后空置, 任期 +1, 实为 " + vacated);
            helper.assertTrue(env.repo.friendsOf(plot).isEmpty(), "朋友清空");
            for (PermissionItemDef item : PermissionCatalog.memberItems()) {
                helper.assertTrue(env.repo.plotCells(plot).require(item.permissionId(), "resident")
                        == item.plotDefault(com.miningdim.district.core.PlotAudience.RESIDENT), "三列回到默认值");
            }
            List<PlotLogEntry> current = env.repo.plotLog(plot, 2, 2, 10);
            helper.assertTrue(current.size() == 1 && current.get(0).action() == PlotLogAction.VACATE
                            && DistrictTexts.PLOT_NOTE_RECLAIM_NOW.equals(current.get(0).reason())
                            && "Owner_One".equals(current.get(0).targetName()),
                    "新任期的第一行是 vacate, 实为 " + current);
            helper.assertTrue(env.repo.plotLog(plot, 1, 1, 50).size() == 4,
                    "旧任期的记录 (create / addFriend / permission / freeze) 一行不动, 只是归档");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void expiryByInjectedClockUsesSystemActorAndFixedReasons(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            String plot = frozenPlot(env);
            env.advance(DistrictLimits.FREEZE_MS - 1);
            helper.assertTrue(env.ctx.freezes().reclaimExpired(null) == 0, "差 1 毫秒不收回");
            env.advance(1);
            long at = env.clock.get();
            helper.assertTrue(env.ctx.freezes().reclaimExpired(null) == 1, "到期即收回");
            helper.assertTrue(env.ctx.freezes().reclaimExpired(null) == 0, "收回一次之后不再重复");
            DistrictLogEntry log = env.repo.districtLog("abydos", 1).get(0);
            helper.assertTrue(log.action() == DistrictLogAction.VACATE_PLOT && log.actorRole() == DistrictActorRole.SYSTEM
                            && "服务器".equals(log.actorName()) && log.actorUuid() == null
                            && "冻结期满，自动收回".equals(log.reason()) && log.at() == at,
                    "本区记录: 操作人服务器 / system, 缘由固定, at 取处理时刻, 实为 " + log);
            PlotLogEntry vacate = env.repo.plotLog(plot, 2, 2, 1).get(0);
            helper.assertTrue(vacate.action() == PlotLogAction.VACATE && vacate.actorRole() == PlotActorRole.SYSTEM
                            && "冻结期满，地块收回".equals(vacate.reason()),
                    "地块记录: vacate / system / 固定缘由, 实为 " + vacate);
            helper.assertTrue(env.plotRecord(plot).vacant(), "地块已收回");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void writeActionSweepsBeforeValidation(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            String plot = frozenPlot(env);
            env.ctx.residents().addResident(env.admin, "abydos", "Owner_One", false);
            env.advance(8 * DAY);
            expect(helper, DistrictError.PLOT_NOT_FROZEN, "到期后解冻: 写动作先收回, 再校验",
                    () -> env.ctx.freezes().unfreeze(env.admin, "abydos", plot));
            helper.assertTrue(env.plotRecord(plot).vacant(), "地块已被写动作前的 sweep 收回");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void readsNeverSweep(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            String plot = frozenPlot(env);
            env.advance(8 * DAY);
            int logRows = env.repo.districtLog("abydos", 100).size();
            DistrictQueryService queries = env.ctx.queries();
            queries.state(env.admin);
            queries.detail(env.admin, "abydos");
            queries.permissions(env.admin, "abydos");
            DistrictQueryService.PlotsView plots = queries.plots(env.admin, null, "abydos");
            DistrictQueryService.PlotDetailView detail = queries.plotDetail(env.admin, "abydos", plot);
            helper.assertTrue(plots.plots().get(0).plot().status() == PlotStatus.FROZEN
                            && plots.plots().get(0).freeze() != null
                            && plots.plots().get(0).freeze().reclaimAt() < env.clock.get(),
                    "读动作照实给出冻结状态, reclaimAt 已经过去");
            helper.assertTrue(detail.plot().frozen(), "plot.detail 同样不收回");
            helper.assertTrue(env.repo.districtLog("abydos", 100).size() == logRows && env.plotRecord(plot).frozen(),
                    "读动作不写库");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tickSweepRunsEvery1200Ticks(GameTestHelper helper) {
        helper.assertTrue(!DistrictSystem.isSweepTick(0) && !DistrictSystem.isSweepTick(1199)
                        && DistrictSystem.isSweepTick(1200) && !DistrictSystem.isSweepTick(1201)
                        && DistrictSystem.isSweepTick(2400),
                "定时收回每 1200 tick (60 秒) 一次");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void frozenPlotIsWrittenAllOff(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            String plot = frozenPlot(env);
            PlotRecord frozen = env.plotRecord(plot);
            UUID claim = frozen.flanClaimId();
            helper.assertTrue(frozen.syncStatus() == PlotSyncStatus.SYNCED, "冻结的整块重写已生效");
            helper.assertTrue(env.recording().membersOf(claim).isEmpty(), "冻结期间所有组都没有成员");
            for (PermissionItemDef item : PermissionCatalog.memberItems()) {
                for (String flanId : item.flanIds()) {
                    helper.assertTrue(Boolean.FALSE.equals(env.recording().defaultPerm(claim, flanId))
                                    && Boolean.FALSE.equals(env.recording().groupPerm(claim,
                                    FlanGroupNames.plotFriend(plot), flanId)),
                            flanId + " 在默认与朋友组里全假");
                }
            }
            for (String outside : FlanPermissions.FIXED_TRUE) {
                helper.assertTrue(Boolean.FALSE.equals(env.recording().defaultPerm(claim, outside)),
                        outside + " 冻结时也写假");
            }
            helper.assertTrue(env.repo.friendsOf(plot).size() == 1
                            && env.repo.plotCells(plot).require("place", "resident"),
                    "库里存着的朋友与三列不动");
        }
        helper.succeed();
    }
}

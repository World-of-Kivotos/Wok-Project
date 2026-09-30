package com.miningdim.district.service;

import com.miningdim.core.MiningConstants;
import com.miningdim.district.DistrictTestEnv;
import com.miningdim.district.core.DistrictBounds;
import com.miningdim.district.core.DistrictError;
import com.miningdim.district.core.DistrictLogAction;
import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.DistrictRuleException;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.PlotActorRole;
import com.miningdim.district.core.PlotArea;
import com.miningdim.district.core.PlotLogAction;
import com.miningdim.district.core.PlotLogEntry;
import com.miningdim.district.core.PlotRecord;
import com.miningdim.district.core.PlotStatus;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

import static com.miningdim.district.DistrictTestEnv.DAY;
import static com.miningdim.district.DistrictTestEnv.expect;
import static com.miningdim.district.DistrictTestEnv.player;
import static com.miningdim.district.DistrictTestEnv.uuidOf;

/**
 * 解绑与归档 (admin.district.delete / admin.district.archive, 设计文档第十二章): 只解绑, 一行都不删; 解绑后这个区从一切动作里消失, Flan 一律不碰;
 * 学院名单保留、仍约束一人一学院; 重新绑定用新的 districtId。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class DistrictUnbindGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "district_unbind";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unbindKeepsRosterPlotsAndArchivesLog(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_One");
            env.resident("abydos", "Warden_Wu");
            env.warden("abydos", "Warden_Wu");
            String owned = env.plot("abydos", 10, 10, 25, 25);
            env.plot("abydos", 40, 10, 55, 25);
            env.own(owned, "Owner_One");
            env.ctx.plotOwners().addFriend(player("Owner_One"), "abydos", owned, "Warden_Wu", false);
            int logRows = env.repo.districtLog("abydos", 100).size();
            env.advance(DAY);

            DistrictAdminService.UnbindResult result = env.ctx.admin().unbind(env.admin, "abydos");
            helper.assertTrue(result.keptMembers() == 2 && result.keptPlots() == 2,
                    "回执带解绑那一刻的人数与地块数, 实为 " + result);
            DistrictRecord archived = env.districtRecord("abydos");
            helper.assertTrue(!archived.live() && "Op_Admin".equals(archived.unboundByName())
                            && archived.unboundAt() == DistrictTestEnv.T0 + DAY && archived.wardenUuid() == null,
                    "归档行带解绑时间与操作人, 区务长清空, 实为 " + archived);
            helper.assertTrue(env.repo.membersOf("abydos").size() == 2, "学院名单保留");
            helper.assertTrue(env.repo.plotsOf("abydos").size() == 2 && env.repo.friendsOf(owned).size() == 1
                            && env.repo.plotCells(owned).asMap().size() == 29,
                    "地块、朋友、三列一行都不删");
            helper.assertTrue(env.repo.districtLog("abydos", 100).size() == logRows, "解绑不写本区记录");

            DistrictQueryService.ArchiveView archive = env.ctx.queries().archive(env.admin);
            helper.assertTrue(archive.districts().size() == 1 && !archive.truncated(), "归档里有这一个区");
            DistrictQueryService.ArchivedDistrictView view = archive.districts().get(0);
            helper.assertTrue(view.log().size() == logRows && "阿拜多斯".equals(view.academy().shortName())
                            && view.district().unboundMemberCount() == 2,
                    "归档带本区记录与当时的人数, 实为 " + view);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unboundDistrictIsNotFoundEverywhere(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_One");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.own(plot, "Owner_One");
            env.ctx.admin().unbind(env.admin, "abydos");
            expect(helper, DistrictError.DISTRICT_NOT_FOUND, "district.detail",
                    () -> env.ctx.queries().detail(env.admin, "abydos"));
            expect(helper, DistrictError.DISTRICT_NOT_FOUND, "district.permissions",
                    () -> env.ctx.queries().permissions(env.admin, "abydos"));
            expect(helper, DistrictError.DISTRICT_NOT_FOUND, "district.plots",
                    () -> env.ctx.queries().plots(env.admin, null, "abydos"));
            expect(helper, DistrictError.DISTRICT_NOT_FOUND, "plot.detail",
                    () -> env.ctx.queries().plotDetail(player("Owner_One"), "abydos", plot));
            expect(helper, DistrictError.DISTRICT_NOT_FOUND, "district.addResident",
                    () -> env.ctx.residents().addResident(env.admin, "abydos", "Anyone_Ann", true));
            expect(helper, DistrictError.DISTRICT_NOT_FOUND, "district.setPermission",
                    () -> env.ctx.permissions().setPermission(env.admin, "abydos", "place", "outsider", true));
            expect(helper, DistrictError.DISTRICT_NOT_FOUND, "plot.setPermission",
                    () -> env.ctx.plotOwners().setPermission(player("Owner_One"), "abydos", plot, "place", "outsider",
                            true));
            expect(helper, DistrictError.DISTRICT_NOT_FOUND, "plot.create",
                    () -> env.ctx.layout().create(env.admin, "abydos",
                            PlotLayoutService.AreaInput.of(new PlotArea(40, 40, 55, 55))));
            expect(helper, DistrictError.DISTRICT_NOT_FOUND, "admin.district.delete 再解绑一次",
                    () -> env.ctx.admin().unbind(env.admin, "abydos"));
            helper.assertTrue(env.ctx.queries().state(env.admin).districts().isEmpty(),
                    "district.state 不再列出已解绑的区");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unboundRosterStillBlocksOtherAcademies(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.millennium();
            env.resident("abydos", "Kept_Kim");
            env.ctx.admin().unbind(env.admin, "abydos");
            DistrictRuleException blocked = expect(helper, DistrictError.RESIDENT_ELSEWHERE, "已解绑学院的成员去别的学院",
                    () -> env.ctx.residents().addResident(env.admin, "millennium", "Kept_Kim", false));
            helper.assertTrue("true".equals(blocked.params().get("unbound")), "用已解绑那句文案");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unbindMakesNoGatewayCalls(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_One");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.own(plot, "Owner_One");
            env.recording().clear();
            env.ctx.admin().unbind(env.admin, "abydos");
            helper.assertTrue(env.recording().calls().isEmpty(), "解绑不调用任何 Flan 方法, 实为 "
                    + env.recording().calls());
            helper.assertTrue(env.recording().exists(env.plotRecord(plot).flanClaimId()), "子领地原样保留");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void expirySkipsUnboundDistricts(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_One");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.own(plot, "Owner_One");
            env.ctx.residents().removeResident(env.admin, "abydos", "Owner_One", "inactive", "不上线");
            env.ctx.admin().unbind(env.admin, "abydos");
            env.advance(8 * DAY);
            int reclaimed = env.ctx.freezes().reclaimExpired(null);
            helper.assertTrue(reclaimed == 0, "已解绑自管区的冻结地块不做到期收回, 实为 " + reclaimed);
            helper.assertTrue(env.plotRecord(plot).status() == PlotStatus.FROZEN, "地块仍是冻结");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void archiveNewestFirstAndAdminOnly(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.millennium();
            env.ctx.admin().unbind(env.admin, "millennium");
            env.advance(DAY);
            env.ctx.admin().unbind(env.admin, "abydos");
            DistrictQueryService.ArchiveView archive = env.ctx.queries().archive(env.admin);
            helper.assertTrue(archive.districts().size() == 2
                            && "abydos".equals(archive.districts().get(0).district().districtId())
                            && "millennium".equals(archive.districts().get(1).district().districtId()),
                    "归档按解绑时间新的在前, 实为 " + archive.districts());
            expect(helper, DistrictError.PERMISSION_DENIED, "非 OP 看归档",
                    () -> env.ctx.queries().archive(player("Anyone_Ann")));
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void rebindCreatesNewDistrictIdAndRestartsNumbering(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.plot("abydos", 10, 10, 25, 25);
            env.ctx.admin().unbind(env.admin, "abydos");
            DistrictRecord rebound = env.district("abydos", 500, 500, 699, 699);
            helper.assertTrue("abydos-2".equals(rebound.districtId()) && "阿拜多斯自管区".equals(rebound.displayName()),
                    "第二次绑定的 id 为 abydos-2, 实为 " + rebound.districtId());
            String plot = env.plot("abydos-2", 510, 510, 525, 525);
            PlotRecord record = env.plotRecord(plot);
            helper.assertTrue("abydos-2-01".equals(record.plotId()) && "阿拜多斯-01".equals(record.code()),
                    "新区的地块编号从 1 开始, 实为 " + record.plotId() + " / " + record.code());
            helper.assertTrue(env.repo.plot("abydos-01").isPresent(), "旧区的地块仍挂在旧 districtId 下");
            env.ctx.admin().unbind(env.admin, "abydos-2");
            helper.assertTrue("abydos-3".equals(env.district("abydos", 900, 900, 999, 999).districtId()),
                    "第三次绑定为 abydos-3");
        }
        helper.succeed();
    }

    /**
     * 解绑刻意不删 Flan 领地, 所以已解绑区的范围仍算"有人": 别的学院建区或 (金锄头改领地后) bounds sync 压上去一律
     * OVERLAPS_DISTRICT (detail 是那个归档区)。同一个学院把自己原来那片地重新绑回来: 旧的父领地还在, /district create
     * 被 FLAN_CLAIM_EXISTS 拦下, 改用 /district bind 收编旧的父领地 (20.6)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void archivedLandBlocksOtherAcademiesButNotItsOwnRebind(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.millennium();
            env.plot("abydos", 10, 10, 25, 25);
            UUID oldClaim = env.districtRecord("abydos").flanClaimId();
            env.ctx.admin().unbind(env.admin, "abydos");
            DistrictAdminService.CommandResult gehenna = env.ctx.admin().createDistrict(env.admin, "gehenna",
                    new DistrictBounds(DistrictTestEnv.DIMENSION, 150, 150, 300, 300), null);
            helper.assertTrue(gehenna.outcome() == DistrictAdminService.CommandOutcome.OVERLAPS_DISTRICT
                            && gehenna.detail().equals(List.of("abydos")),
                    "别的学院压到已解绑区的范围: 拒绝并指出归档区, 实为 " + gehenna.outcome() + " " + gehenna.detail());
            UUID millenniumClaim = env.districtRecord("millennium").flanClaimId();
            env.recording().driftResize(millenniumClaim, new PlotArea(100, 0, 1199, 199));
            DistrictAdminService.CommandResult widened = env.ctx.admin().syncBoundsFromClaim(env.admin, "millennium");
            helper.assertTrue(widened.outcome() == DistrictAdminService.CommandOutcome.OVERLAPS_DISTRICT
                            && widened.detail().equals(List.of("abydos"))
                            && env.districtRecord("millennium").bounds().minX() == 1000,
                    "bounds sync 同一套规则, 被拒时范围不变, 实为 " + widened.outcome());
            // 被拒之后 OP 用金锄头把领地改回原样。
            env.recording().driftResize(millenniumClaim, new PlotArea(1000, 0, 1199, 199));
            helper.assertTrue(env.ctx.admin().createDistrict(env.admin, "gehenna",
                            new DistrictBounds(DistrictTestEnv.DIMENSION, 300, 300, 450, 450), null).ok(),
                    "不压到任何区的范围照常建");
            DistrictAdminService.CommandResult recreate = env.ctx.admin().createDistrict(env.admin, "abydos",
                    new DistrictBounds(DistrictTestEnv.DIMENSION, 0, 0, 249, 199), null);
            helper.assertTrue(recreate.outcome() == DistrictAdminService.CommandOutcome.FLAN_CLAIM_EXISTS
                            && recreate.detail().equals(List.of(oldClaim + " (admin)")),
                    "旧的父领地还在: 建区被拦下, 提示改用 bind, 实为 " + recreate.outcome() + " " + recreate.detail());
            DistrictAdminService.CommandResult rebound = env.ctx.admin().bindDistrict(env.admin, "abydos",
                    DistrictTestEnv.DIMENSION, 5, 5, true);
            helper.assertTrue(rebound.ok() && rebound.district() != null
                            && "abydos-2".equals(rebound.district().districtId())
                            && oldClaim.equals(rebound.district().flanClaimId())
                            && env.recording().childrenOf(oldClaim).isEmpty(),
                    "同一个学院重新绑回自己的旧地: 收编旧的父领地, 旧地块的子领地删掉, 实为 " + rebound.outcome());
        }
        helper.succeed();
    }

    /**
     * 重新绑定之后移出住户 (ResidentService.releaseArchivedOwnership): TA 还登记为旧区一块地的户主时, 一人一块地按全库判
     * (新区里买不了), 移出时先清空旧区那块地的户主 (不冻结、任期不变、不写 Flan), 在那块地的当前任期写一行 vacate,
     * 再删名单行; 回来之后就能在新区买地。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void removalAfterRebindReleasesArchivedOwnership(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_One");
            String old = env.plot("abydos", 10, 10, 25, 25);
            env.own(old, "Owner_One");
            env.ctx.admin().unbind(env.admin, "abydos");
            env.district("abydos", 500, 500, 699, 699);
            env.plot("abydos-2", 510, 510, 525, 525);
            env.ctx.admin().setPurchaseOpen(env.admin, "abydos-2", true);
            DistrictQueryService queries = env.ctx.queries();
            helper.assertTrue(queries.plots(player("Owner_One"), null, "abydos-2").market().viewerBlock()
                    == DistrictError.ALREADY_OWNS_PLOT, "旧区的地还登记在 TA 名下: 新区里 ALREADY_OWNS_PLOT");

            UUID oldClaim = env.plotRecord(old).flanClaimId();
            env.recording().clear();
            ResidentService.RemoveResult removed = env.ctx.residents().removeResident(env.admin, "abydos-2",
                    "Owner_One", "inactive", "不上线");
            helper.assertTrue(removed.frozenPlot() == null && removed.reclaimAt() == null, "新区里没有地, 不冻结");
            PlotRecord released = env.plotRecord(old);
            helper.assertTrue(released.ownerUuid() == null && !released.frozen() && released.tenure() == 1,
                    "旧区那块地清空户主, 不冻结, 任期不变, 实为 " + released);
            PlotLogEntry vacate = env.repo.plotLog(old, 1, 1, 1).get(0);
            helper.assertTrue(vacate.action() == PlotLogAction.VACATE
                            && DistrictTexts.PLOT_NOTE_RELEASE_ARCHIVED.equals(vacate.reason())
                            && vacate.actorRole() == PlotActorRole.ADMIN && "Owner_One".equals(vacate.targetName()),
                    "在旧地块的当前任期写一行 vacate, 实为 " + vacate);
            helper.assertTrue(env.repo.memberByUuid(uuidOf("Owner_One")).isEmpty(), "名单行删除");
            helper.assertTrue(env.recording().calls().stream().noneMatch(call -> oldClaim.equals(call.claimId())),
                    "已解绑区的那块地不写 Flan, 实为 " + env.recording().calls());

            env.ctx.residents().addResident(env.admin, "abydos-2", "Owner_One", false);
            helper.assertTrue(queries.plots(player("Owner_One"), null, "abydos-2").market().viewerBlock() == null,
                    "回来之后能在新区买地");
        }
        helper.succeed();
    }

    /**
     * 重新绑定之后的 /district academy kick: 学院又有了在用的自管区就拒 (ACADEMY_STILL_BOUND, detail 是新区); 新区也解绑
     * 之后才放行, 并清掉此人在更早那个归档区里的户主登记。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void kickAfterRebindWaitsUntilTheNewBindingIsGone(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_One");
            String old = env.plot("abydos", 10, 10, 25, 25);
            env.own(old, "Owner_One");
            env.ctx.admin().unbind(env.admin, "abydos");
            env.district("abydos", 500, 500, 699, 699);
            DistrictAdminService.CommandResult blocked = env.ctx.admin().kickFromUnboundAcademy(env.admin, "abydos",
                    "Owner_One");
            helper.assertTrue(blocked.outcome() == DistrictAdminService.CommandOutcome.ACADEMY_STILL_BOUND
                            && blocked.detail().equals(List.of("abydos-2")),
                    "重新绑定后学院又有在用的自管区, 实为 " + blocked.outcome() + " " + blocked.detail());
            helper.assertTrue(env.plotRecord(old).isOwner(uuidOf("Owner_One")), "被拒时旧地块不动");

            env.ctx.admin().unbind(env.admin, "abydos-2");
            DistrictAdminService.CommandResult kicked = env.ctx.admin().kickFromUnboundAcademy(env.admin, "abydos",
                    "OWNER_ONE");
            helper.assertTrue(kicked.ok() && kicked.detail().equals(List.of("Owner_One")),
                    "两次绑定都解除后放行, 回报规范名, 实为 " + kicked.outcome() + " " + kicked.detail());
            PlotRecord released = env.plotRecord(old);
            PlotLogEntry vacate = env.repo.plotLog(old, 1, 1, 1).get(0);
            helper.assertTrue(released.ownerUuid() == null && released.tenure() == 1
                            && vacate.action() == PlotLogAction.VACATE
                            && DistrictTexts.PLOT_NOTE_KICK_ARCHIVED.equals(vacate.reason()),
                    "最早那个归档区里的户主登记一并清掉, 实为 " + released + " / " + vacate);
            helper.assertTrue(env.repo.memberByUuid(uuidOf("Owner_One")).isEmpty(), "名单行删除");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void kickFromUnboundAcademyClearsArchivedOwnership(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.millennium();
            env.resident("abydos", "Owner_One");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.own(plot, "Owner_One");
            DistrictAdminService.CommandResult live = env.ctx.admin().kickFromUnboundAcademy(env.admin, "abydos",
                    "Owner_One");
            helper.assertTrue(live.outcome() == DistrictAdminService.CommandOutcome.ACADEMY_STILL_BOUND,
                    "学院还有在用的自管区时不能用这条命令, 实为 " + live.outcome());

            env.ctx.admin().unbind(env.admin, "abydos");
            DistrictAdminService.CommandResult kicked = env.ctx.admin().kickFromUnboundAcademy(env.admin, "abydos",
                    "owner_one");
            helper.assertTrue(kicked.ok(), "已解绑学院可以移出成员, 实为 " + kicked.outcome());
            PlotRecord released = env.plotRecord(plot);
            helper.assertTrue(released.ownerUuid() == null && released.tenure() == 1, "先清空户主, 任期不变");
            PlotLogEntry vacate = env.repo.plotLog(plot, 1, 1, 1).get(0);
            helper.assertTrue(vacate.action() == PlotLogAction.VACATE
                            && DistrictTexts.PLOT_NOTE_KICK_ARCHIVED.equals(vacate.reason()),
                    "在当前任期写一行 vacate, 实为 " + vacate);
            helper.assertTrue(env.repo.memberByUuid(uuidOf("Owner_One")).isEmpty(), "名单行删除");
            env.ctx.residents().addResident(env.admin, "millennium", "Owner_One", false);
            helper.assertTrue(env.member("Owner_One").academyId().equals("millennium"), "之后可以加入别的学院");
            helper.assertTrue(env.repo.districtLog("abydos", 100).stream()
                            .noneMatch(e -> e.action() == DistrictLogAction.REMOVE),
                    "这条命令不写本区记录");
        }
        helper.succeed();
    }
}

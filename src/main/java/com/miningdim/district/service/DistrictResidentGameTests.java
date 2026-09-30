package com.miningdim.district.service;

import com.miningdim.core.MiningConstants;
import com.miningdim.district.DistrictLimits;
import com.miningdim.district.DistrictTestEnv;
import com.miningdim.district.core.DistrictActorRole;
import com.miningdim.district.core.DistrictError;
import com.miningdim.district.core.DistrictLogAction;
import com.miningdim.district.core.DistrictLogEntry;
import com.miningdim.district.core.DistrictRuleException;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.FriendRecord;
import com.miningdim.district.core.MemberRecord;
import com.miningdim.district.core.PlotLogAction;
import com.miningdim.district.core.PlotLogEntry;
import com.miningdim.district.core.PlotRecord;
import com.miningdim.district.core.PlotStatus;
import com.miningdim.district.core.ResidentSyncStatus;
import com.miningdim.district.flan.FlanGroupNames;
import com.miningdim.district.store.DistrictStoreException;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

import static com.miningdim.district.DistrictTestEnv.T0;
import static com.miningdim.district.DistrictTestEnv.expect;
import static com.miningdim.district.DistrictTestEnv.player;
import static com.miningdim.district.DistrictTestEnv.uuidOf;

/**
 * 住户名单: 加住户 (district.addResident)、移出住户 (district.removeResident)、重试同步 (admin.district.retrySync)、
 * 任命与撤销区务长 (admin.district.setWarden)。检查顺序、拒绝文案与副作用见设计文档第十四章; 规则以 Java 为准,
 * 开发构建的假后端 webui/src/mock/district-handlers.ts 与这里不一致时改它。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class DistrictResidentGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "district_residents";

    // ================================================================
    // district.addResident
    // ================================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void addResidentChecksInOrder(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.millennium();
            env.resident("abydos", "Already_Al");
            env.resident("millennium", "Mill_Mo");
            ResidentService residents = env.ctx.residents();

            DistrictRuleException missing = expect(helper, DistrictError.DISTRICT_NOT_FOUND, "不存在的区",
                    () -> residents.addResident(env.admin, "nowhere", "a!", true));
            helper.assertTrue("nowhere".equals(missing.params().get("districtId")), "params.districtId 回显输入");
            DistrictRuleException denied = expect(helper, DistrictError.PERMISSION_DENIED, "住户加人 (名字也非法)",
                    () -> residents.addResident(player("Already_Al"), "abydos", "a!", true));
            helper.assertTrue("manager".equals(denied.params().get("requires"))
                            && "district.addResident".equals(denied.params().get("action"))
                            && denied.getMessage().equals("只有本区区务长或管理员可以管理住户"),
                    "区务长门的 params 与文案, 实为 " + denied.params() + " / " + denied.getMessage());
            expect(helper, DistrictError.INVALID_PLAYER_NAME, "非法名字排在已是住户之前",
                    () -> residents.addResident(env.admin, "abydos", "no spaces allowed", true));
            DistrictRuleException already = expect(helper, DistrictError.ALREADY_RESIDENT, "已是本区住户",
                    () -> residents.addResident(env.admin, "abydos", "  already_al  ", true));
            helper.assertTrue(already.getMessage().equals("Already_Al 已经是本区住户了"),
                    "文案回显库里的规范名, 实为 " + already.getMessage());
            expect(helper, DistrictError.RESIDENT_ELSEWHERE, "别的学院的成员",
                    () -> residents.addResident(env.admin, "abydos", "Mill_Mo", true));
            DistrictRuleException never = expect(helper, DistrictError.PLAYER_NEVER_JOINED, "从没进过服",
                    () -> residents.addResident(env.admin, "abydos", "Brand_New", false));
            helper.assertTrue(never.getMessage().equals("没有找到 Brand_New 的登录记录")
                            && "Brand_New".equals(never.params().get("playerName")),
                    "PLAYER_NEVER_JOINED 的文案与 params, 实为 " + never.getMessage() + " / " + never.params());
            helper.assertTrue(env.repo.membersOf("abydos").size() == 1, "被拒的请求一行都不写");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void neverJoinedNeedsConfirmationThenPending(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.recording().clear();
            ResidentService.AddResult added = env.ctx.residents().addResident(env.admin, "abydos", " brand_new_kid ",
                    true);
            MemberRecord member = added.resident();
            helper.assertTrue(member.syncStatus() == ResidentSyncStatus.PENDING && member.syncError() == null,
                    "从没进过服的人以待生效记入, 实为 " + member);
            helper.assertTrue(member.uuid().equals(uuidOf("brand_new_kid")) && "brand_new_kid".equals(member.name()),
                    "按输入原样的离线 UUID 与名字记入, 实为 " + member);
            helper.assertTrue(env.recording().callsOf("setMember").isEmpty(), "待生效的人不写 Flan");
            DistrictQueryService.ResidentView view = env.ctx.queries().residentView(env.districtRecord("abydos"),
                    member);
            helper.assertTrue(view.lastSeenAt() == null, "从没进过服的人 lastSeenAt 为 null");
            helper.assertTrue(added.logEntry().action() == DistrictLogAction.ADD
                            && "brand_new_kid".equals(added.logEntry().targetName())
                            && added.logEntry().actorRole() == DistrictActorRole.ADMIN,
                    "本区记录 add, 实为 " + added.logEntry());
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void caseInsensitiveDuplicateIsAlreadyResident(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.ctx.residents().addResident(env.admin, "abydos", "pebble_fox", true);
            env.seen("Pebble_Fox");
            DistrictRuleException duplicate = expect(helper, DistrictError.ALREADY_RESIDENT, "大小写不同的同一个名字",
                    () -> env.ctx.residents().addResident(env.admin, "abydos", "Pebble_Fox", false));
            helper.assertTrue(duplicate.getMessage().equals("pebble_fox 已经是本区住户了"),
                    "按小写名匹配到已有的名单行, 实为 " + duplicate.getMessage());
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void residentElsewhereLiveAndUnboundMessages(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.millennium();
            env.district("gehenna", 3000, 0, 3199, 199);
            env.resident("millennium", "Mill_Mo");
            env.resident("gehenna", "Gehenna_Gi");
            env.ctx.admin().unbind(env.admin, "gehenna");

            DistrictRuleException live = expect(helper, DistrictError.RESIDENT_ELSEWHERE, "未解绑学院",
                    () -> env.ctx.residents().addResident(env.admin, "abydos", "Mill_Mo", false));
            helper.assertTrue(live.getMessage().equals(
                            "Mill_Mo 已经是千年学院的成员。一个人只能属于一个学院，要转过来得先让原学院的区务长把 TA 移出"),
                    "未解绑学院的文案, 实为 " + live.getMessage());
            helper.assertTrue("false".equals(live.params().get("unbound"))
                    && "millennium".equals(live.params().get("academyId")), "params, 实为 " + live.params());
            DistrictRuleException unbound = expect(helper, DistrictError.RESIDENT_ELSEWHERE, "已解绑学院",
                    () -> env.ctx.residents().addResident(env.admin, "abydos", "Gehenna_Gi", false));
            helper.assertTrue(unbound.getMessage().equals(
                            "Gehenna_Gi 已经是格赫娜学院的成员（这个学院的自管区已解除绑定，成员名单还在）。一个人只能属于一个学院"),
                    "已解绑学院的文案, 实为 " + unbound.getMessage());
            helper.assertTrue("true".equals(unbound.params().get("unbound")), "params.unbound = true");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void canonicalNameAndUuidAreStored(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            UUID real = UUID.fromString("0f0f0f0f-1111-4222-8333-444444444444");
            env.repo.upsertSeenOnLogin(real, "Pebble_Fox", T0 - 5000);
            ResidentService.AddResult added = env.ctx.residents().addResident(env.admin, "abydos", "  pebble_FOX ",
                    false);
            helper.assertTrue(added.resident().uuid().equals(real) && "Pebble_Fox".equals(added.resident().name()),
                    "进过服的人以见过的玩家表里的规范名与 UUID 记入, 实为 " + added.resident());
            helper.assertTrue("Pebble_Fox".equals(added.logEntry().targetName()), "记录里也写规范名");
            DistrictQueryService.ResidentView view = env.ctx.queries().residentView(env.districtRecord("abydos"),
                    added.resident());
            helper.assertTrue(view.lastSeenAt() != null && view.lastSeenAt() == T0 - 5000,
                    "lastSeenAt 取见过的玩家表, 实为 " + view.lastSeenAt());
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void knownPlayerSyncedOnlyAfterGatewaySuccess(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            MemberRecord member = env.resident("abydos", "Known_Kai");
            helper.assertTrue(member.syncStatus() == ResidentSyncStatus.SYNCED && member.syncError() == null,
                    "网关写入成功后名单行为已生效 (回执读到的是推送后的真值), 实为 " + member);
            UUID claim = env.districtRecord("abydos").flanClaimId();
            helper.assertTrue(claim != null && FlanGroupNames.districtResident("abydos")
                            .equals(env.recording().membersOf(claim).get(member.uuid())),
                    "玩家在本区居民组里");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void gatewayFailureLeavesFailedWithError(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.recording().failNext("setMember", "区块未加载");
            MemberRecord member = env.resident("abydos", "Unlucky_Uma");
            helper.assertTrue(member.syncStatus() == ResidentSyncStatus.FAILED && "区块未加载".equals(member.syncError()),
                    "网关写入失败时名单行为同步失败并带原因, 实为 " + member);
            DistrictRepositorySnapshot snapshot = DistrictRepositorySnapshot.of(env);
            helper.assertTrue(snapshot.syncFailed() == 1 && snapshot.syncPending() == 0,
                    "syncIssues 计数 {pending 0, failed 1}, 实为 " + snapshot);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void reAddReturnsFrozenPlotWithoutUnfreezing(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_One");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.own(plot, "Owner_One");
            env.ctx.residents().removeResident(env.admin, "abydos", "Owner_One", "inactive", "长期不上线");
            ResidentService.AddResult back = env.ctx.residents().addResident(env.admin, "abydos", "Owner_One", false);
            helper.assertTrue(back.frozenPlot() != null && plot.equals(back.frozenPlot().plotId()),
                    "加回来时回执带上 TA 冻结中的地块");
            helper.assertTrue(env.plotRecord(plot).status() == PlotStatus.FROZEN, "加住户不会顺带解冻");
        }
        helper.succeed();
    }

    // ================================================================
    // district.removeResident
    // ================================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void removeResidentChecksInOrder(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Warden_Wu");
            env.resident("abydos", "Target_Tim");
            env.warden("abydos", "Warden_Wu");
            ResidentService residents = env.ctx.residents();
            expect(helper, DistrictError.DISTRICT_NOT_FOUND, "不存在的区",
                    () -> residents.removeResident(env.admin, "nowhere", "Target_Tim", "bogus", ""));
            expect(helper, DistrictError.PERMISSION_DENIED, "住户移人",
                    () -> residents.removeResident(player("Target_Tim"), "abydos", "Warden_Wu", "bogus", ""));
            DistrictRuleException kind = expect(helper, DistrictError.INVALID_REQUEST, "原因种类不在四种之内",
                    () -> residents.removeResident(player("Warden_Wu"), "abydos", "Target_Tim", "bogus", ""));
            helper.assertTrue("reasonKind".equals(kind.params().get("field")) && "bogus".equals(kind.params().get("value")),
                    "INVALID_REQUEST 的 params 指出 reasonKind, 实为 " + kind.params());
            expect(helper, DistrictError.REASON_REQUIRED, "原因为空白",
                    () -> residents.removeResident(player("Warden_Wu"), "abydos", "Target_Tim", "other", "   "));
            DistrictRuleException notResident = expect(helper, DistrictError.NOT_RESIDENT, "不在名单上",
                    () -> residents.removeResident(player("Warden_Wu"), "abydos", "Ghost_Gus", "other", "x"));
            helper.assertTrue(notResident.getMessage().equals("Ghost_Gus 不是本区住户"), "文案, 实为 " + notResident.getMessage());
            expect(helper, DistrictError.RESIDENT_IS_WARDEN, "区务长不能被移出",
                    () -> residents.removeResident(env.admin, "abydos", "warden_wu", "other", "x"));
            helper.assertTrue(env.repo.membersOf("abydos").size() == 2, "被拒的请求一行都不删");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wardenCannotBeRemovedEvenByAdminNorBySelf(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Warden_Wu");
            env.warden("abydos", "Warden_Wu");
            DistrictRuleException byAdmin = expect(helper, DistrictError.RESIDENT_IS_WARDEN, "管理员移区务长",
                    () -> env.ctx.residents().removeResident(env.admin, "abydos", "Warden_Wu", "other", "x"));
            helper.assertTrue(byAdmin.getMessage().equals("Warden_Wu 是本区区务长，要先由管理员撤销区务长才能移出"),
                    "文案, 实为 " + byAdmin.getMessage());
            expect(helper, DistrictError.RESIDENT_IS_WARDEN, "区务长移自己",
                    () -> env.ctx.residents().removeResident(player("Warden_Wu"), "abydos", "Warden_Wu", "selfRequest",
                            "本人申请退出"));
            env.ctx.admin().setWarden(env.admin, "abydos", null);
            env.ctx.residents().removeResident(env.admin, "abydos", "Warden_Wu", "selfRequest", "本人申请退出");
            helper.assertTrue(env.repo.membersOf("abydos").isEmpty(), "撤销区务长之后可以移出");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void removingOwnerFreezesPlotAndLogsInOrder(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Warden_Wu");
            env.warden("abydos", "Warden_Wu");
            env.resident("abydos", "Owner_One");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.own(plot, "Owner_One");
            env.advance(1000);
            long at = env.clock.get();
            ResidentService.RemoveResult result = env.ctx.residents().removeResident(player("Warden_Wu"), "abydos",
                    "Owner_One", "inactive", "长期不上线：半年没来");

            PlotRecord frozen = env.plotRecord(plot);
            helper.assertTrue(frozen.status() == PlotStatus.FROZEN && frozen.frozenAt() == at
                            && "Owner_One".equals(frozen.frozenOwnerName()) && frozen.ownerUuid() == null,
                    "户主被移出后地块原地冻结, 实为 " + frozen);
            helper.assertTrue(result.frozenPlot() != null && result.reclaimAt() != null
                            && result.reclaimAt() == at + DistrictLimits.FREEZE_MS,
                    "回执带冻结地块与 at + 7 天的收回时刻");
            helper.assertTrue(result.logEntry().action() == DistrictLogAction.REMOVE
                            && "长期不上线：半年没来".equals(result.logEntry().reason())
                            && result.logEntry().actorRole() == DistrictActorRole.WARDEN,
                    "回执的 logEntry 是 remove 那一条, 原因原文保存, 实为 " + result.logEntry());
            List<DistrictLogEntry> log = env.repo.districtLog("abydos", 2);
            helper.assertTrue(log.get(0).action() == DistrictLogAction.FREEZE_PLOT
                            && log.get(1).action() == DistrictLogAction.REMOVE,
                    "本区记录新的在前: freezePlot 在 remove 之上, 实为 " + log);
            helper.assertTrue(log.get(0).reason().equals("原户主 Owner_One 被移出本区")
                            && frozen.code().equals(log.get(0).targetName()),
                    "freezePlot 的缘由与目标, 实为 " + log.get(0));
            PlotLogEntry freeze = env.repo.plotLog(plot, 1, 1, 1).get(0);
            helper.assertTrue(freeze.action() == PlotLogAction.FREEZE && DistrictTexts.PLOT_NOTE_FREEZE.equals(freeze.reason())
                            && "Owner_One".equals(freeze.targetName()) && !freeze.onBehalfOfOwner(),
                    "地块记录 freeze 用固定缘由, 实为 " + freeze);
            helper.assertTrue(env.repo.memberByUuid(uuidOf("Owner_One")).isEmpty(), "名单行已删除 (退出学院)");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void plotLogNeverContainsRemovalReason(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_One");
            env.resident("abydos", "Suspect_Sue");
            String own = env.plot("abydos", 10, 10, 25, 25);
            String neighbour = env.plot("abydos", 40, 10, 55, 25);
            env.own(own, "Suspect_Sue");
            env.own(neighbour, "Owner_One");
            env.ctx.plotOwners().addFriend(player("Owner_One"), "abydos", neighbour, "Suspect_Sue", false);
            String secret = "偷了邻居箱子里的钻石";
            env.ctx.residents().removeResident(env.admin, "abydos", "Suspect_Sue", "violation", secret);
            for (String plot : List.of(own, neighbour)) {
                for (PlotLogEntry entry : env.repo.plotLog(plot, 1, Integer.MAX_VALUE, 50)) {
                    helper.assertTrue(entry.reason() == null || !entry.reason().contains(secret),
                            "移出原因绝不写进地块记录, 实为 " + entry);
                }
            }
            helper.assertTrue(env.repo.districtLog("abydos", 50).stream().anyMatch(e -> secret.equals(e.reason())),
                    "移出原因只进本区记录");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void violationSuspendsOnlyThisDistrictIncludingFrozenNotOwn(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.millennium();
            env.resident("abydos", "Owner_A");
            env.resident("abydos", "Owner_B");
            env.resident("abydos", "Suspect_Sue");
            env.resident("millennium", "Mill_Owner");
            String plotA = env.plot("abydos", 10, 10, 25, 25);
            String plotB = env.plot("abydos", 40, 10, 55, 25);
            String plotOwn = env.plot("abydos", 70, 10, 85, 25);
            String plotMill = env.plot("millennium", 1010, 10, 1025, 25);
            env.own(plotA, "Owner_A");
            env.own(plotB, "Owner_B");
            env.own(plotOwn, "Suspect_Sue");
            env.own(plotMill, "Mill_Owner");
            env.ctx.plotOwners().addFriend(player("Owner_A"), "abydos", plotA, "Suspect_Sue", false);
            env.ctx.plotOwners().addFriend(player("Owner_B"), "abydos", plotB, "Suspect_Sue", false);
            env.ctx.plotOwners().addFriend(player("Mill_Owner"), "millennium", plotMill, "Suspect_Sue", false);
            env.ctx.residents().removeResident(env.admin, "abydos", "Owner_B", "inactive", "不上线");
            env.advance(1000);
            long at = env.clock.get();

            ResidentService.RemoveResult result = env.ctx.residents().removeResident(env.admin, "abydos",
                    "Suspect_Sue", "violation", "违反区规：拆房子");
            helper.assertTrue(result.suspendedFriendOfPlots() == 2 && result.stillFriendOfPlots() == 0,
                    "违反区规: 本区 2 块地 (含冻结中的) 的朋友身份暂停, 实为 " + result);
            helper.assertTrue(suspendedAt(env, plotA, "Suspect_Sue") == at && suspendedAt(env, plotB, "Suspect_Sue") == at,
                    "有户主的与冻结中的地块都暂停, 时刻即本次动作的 at");
            helper.assertTrue(suspendedAt(env, plotMill, "Suspect_Sue") == null, "别区的朋友身份不受影响");
            List<DistrictLogEntry> log = env.repo.districtLog("abydos", 3);
            helper.assertTrue(log.get(0).action() == DistrictLogAction.SUSPEND_FRIENDS
                            && "本区 2 块地的朋友身份已暂停".equals(log.get(0).reason())
                            && log.get(1).action() == DistrictLogAction.FREEZE_PLOT
                            && log.get(2).action() == DistrictLogAction.REMOVE,
                    "本区记录顺序 (新的在前) suspendFriends / freezePlot / remove, 实为 " + log);
            for (String plot : List.of(plotA, plotB)) {
                PlotLogEntry notice = env.repo.plotLog(plot, 1, Integer.MAX_VALUE, 1).get(0);
                helper.assertTrue(notice.action() == PlotLogAction.SUSPEND_FRIEND
                                && DistrictTexts.PLOT_NOTE_SUSPEND.equals(notice.reason())
                                && "Suspect_Sue".equals(notice.targetName()),
                        plot + " 的地块记录第一行是 suspendFriend 通知, 实为 " + notice);
            }
            helper.assertTrue(env.plotRecord(plotOwn).status() == PlotStatus.FROZEN, "TA 自己的地块被冻结");
        }
        helper.succeed();
    }

    /**
     * 移出是必须原子的三条流程之一: 冻结、暂停朋友身份、删名单行、本区与地块记录在同一个事务里。在这个事务的最后一笔
     * 写入 (受影响地块先写的"临时失败", 排在登记提交后推送之后) 注入失败: 前面的写入必须全部回滚, 登记在提交后队列里
     * 的 Flan 推送一条都不能发。把删名单行或冻结挪进自己的事务、或在事务里直接推 Flan, 这里都会失败。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void removalIsAtomicAndPushesNothingOnRollback(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_A");
            env.resident("abydos", "Suspect_Sue");
            String plotA = env.plot("abydos", 10, 10, 25, 25);
            String plotS = env.plot("abydos", 40, 10, 55, 25);
            env.own(plotA, "Owner_A");
            env.own(plotS, "Suspect_Sue");
            env.ctx.plotOwners().addFriend(player("Owner_A"), "abydos", plotA, "Suspect_Sue", false);
            env.recording().clear();
            int districtLogBefore = env.repo.districtLog("abydos", 100).size();
            int plotALogBefore = env.repo.plotLog(plotA, 1, Integer.MAX_VALUE, 100).size();
            int plotSLogBefore = env.repo.plotLog(plotS, 1, Integer.MAX_VALUE, 100).size();

            env.exec("CREATE TEMP TRIGGER fail_last_write BEFORE UPDATE OF sync_status ON district_plot "
                    + "WHEN NEW.plot_id = '" + plotA + "' AND NEW.sync_status = 'failed' "
                    + "BEGIN SELECT RAISE(ABORT, 'injected failure'); END");
            boolean failed = false;
            try {
                env.ctx.residents().removeResident(env.admin, "abydos", "Suspect_Sue", "violation", "违反区规");
            } catch (DistrictStoreException expected) {
                failed = true;
            } finally {
                env.exec("DROP TRIGGER fail_last_write");
            }
            helper.assertTrue(failed, "注入的写入失败必须冒出来");

            helper.assertTrue(env.repo.memberByUuid(uuidOf("Suspect_Sue")).isPresent(), "名单行随事务回滚");
            PlotRecord own = env.plotRecord(plotS);
            helper.assertTrue(uuidOf("Suspect_Sue").equals(own.ownerUuid()) && own.frozenAt() == null
                    && own.status() != PlotStatus.FROZEN, "TA 的地块没有冻结, 实为 " + own);
            helper.assertTrue(suspendedAt(env, plotA, "Suspect_Sue") == null, "朋友身份的暂停随事务回滚");
            helper.assertTrue(env.repo.districtLog("abydos", 100).size() == districtLogBefore
                            && env.repo.plotLog(plotA, 1, Integer.MAX_VALUE, 100).size() == plotALogBefore
                            && env.repo.plotLog(plotS, 1, Integer.MAX_VALUE, 100).size() == plotSLogBefore,
                    "本区记录与两块地的地块记录都回滚");
            helper.assertTrue(env.recording().calls().isEmpty(),
                    "回滚的事务登记的提交后推送整批作废, 实为 " + env.recording().calls());

            ResidentService.RemoveResult retry = env.ctx.residents().removeResident(env.admin, "abydos",
                    "Suspect_Sue", "violation", "违反区规");
            helper.assertTrue(retry.suspendedFriendOfPlots() == 1 && retry.frozenPlot() != null
                            && plotS.equals(retry.frozenPlot().plotId()),
                    "去掉故障后照常移出, 实为 " + retry);
            helper.assertTrue(env.repo.memberByUuid(uuidOf("Suspect_Sue")).isEmpty()
                    && suspendedAt(env, plotA, "Suspect_Sue") != null, "重试后名单行已删、朋友身份已暂停");
            helper.assertTrue(!env.recording().calls().isEmpty(), "提交之后才推送 Flan");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void friendCountsForViolationAndOtherReasons(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_A");
            env.resident("abydos", "Leaver_Lee");
            String plotA = env.plot("abydos", 10, 10, 25, 25);
            env.own(plotA, "Owner_A");
            env.ctx.plotOwners().addFriend(player("Owner_A"), "abydos", plotA, "Leaver_Lee", false);
            ResidentService.RemoveResult result = env.ctx.residents().removeResident(env.admin, "abydos",
                    "Leaver_Lee", "selfRequest", "本人申请退出");
            helper.assertTrue(result.suspendedFriendOfPlots() == 0 && result.stillFriendOfPlots() == 1,
                    "其余原因保留朋友身份, 只回块数, 实为 " + result);
            helper.assertTrue(suspendedAt(env, plotA, "Leaver_Lee") == null, "朋友行不动");
            helper.assertTrue(env.repo.districtLog("abydos", 5).stream()
                            .noneMatch(e -> e.action() == DistrictLogAction.SUSPEND_FRIENDS),
                    "不写 suspendFriends");
            helper.assertTrue(result.frozenPlot() == null && result.reclaimAt() == null, "没有地块时不冻结");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void removalDropsPlayerFromAllPlotResidentGroups(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_A");
            String owned = env.plot("abydos", 10, 10, 25, 25);
            String vacant = env.plot("abydos", 40, 10, 55, 25);
            env.own(owned, "Owner_A");
            UUID leaver = env.resident("abydos", "Leaver_Lee").uuid();
            UUID ownedClaim = env.plotRecord(owned).flanClaimId();
            UUID vacantClaim = env.plotRecord(vacant).flanClaimId();
            UUID districtClaim = env.districtRecord("abydos").flanClaimId();
            helper.assertTrue(FlanGroupNames.plotResident(owned).equals(env.recording().membersOf(ownedClaim).get(leaver))
                            && FlanGroupNames.plotResident(vacant).equals(env.recording().membersOf(vacantClaim).get(leaver)),
                    "加住户时写进每块地的居民组");
            env.ctx.residents().removeResident(env.admin, "abydos", "Leaver_Lee", "inactive", "不上线");
            helper.assertTrue(!env.recording().membersOf(districtClaim).containsKey(leaver)
                            && !env.recording().membersOf(ownedClaim).containsKey(leaver)
                            && !env.recording().membersOf(vacantClaim).containsKey(leaver),
                    "移出后从本区组与每块地的居民组里都移出");
        }
        helper.succeed();
    }

    // ================================================================
    // admin.district.retrySync
    // ================================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void retrySyncAdminCheckPrecedesDistrictLookup(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            DistrictRuleException denied = expect(helper, DistrictError.PERMISSION_DENIED, "非 OP 查不存在的区",
                    () -> env.ctx.residents().retrySync(player("Anyone_Ann"), "nowhere", "x"));
            helper.assertTrue("admin.district.retrySync".equals(denied.params().get("action"))
                            && !denied.params().containsKey("requires"),
                    "管理员门与 requireOp 同形 (只有 action), 实为 " + denied.params());
            expect(helper, DistrictError.DISTRICT_NOT_FOUND, "OP 查不存在的区",
                    () -> env.ctx.residents().retrySync(env.admin, "nowhere", "x"));
            expect(helper, DistrictError.NOT_RESIDENT, "不在名单上",
                    () -> env.ctx.residents().retrySync(env.admin, "abydos", "Ghost_Gus"));
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void retrySyncRejectsPendingAndSynced(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.ctx.residents().addResident(env.admin, "abydos", "Pending_Pe", true);
            env.resident("abydos", "Synced_Sy");
            DistrictRuleException pending = expect(helper, DistrictError.SYNC_NOTHING_TO_RETRY, "待生效",
                    () -> env.ctx.residents().retrySync(env.admin, "abydos", "Pending_Pe"));
            helper.assertTrue("pending".equals(pending.params().get("syncStatus")), "params.syncStatus = pending");
            expect(helper, DistrictError.SYNC_NOTHING_TO_RETRY, "已生效",
                    () -> env.ctx.residents().retrySync(env.admin, "abydos", "Synced_Sy"));
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void retrySyncFailureKeepsFailedAndThrows(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            UUID uma = uuidOf("Unlucky_Uma");
            env.recording().failWhen(call -> call.op().equals("setMember") && call.hasArg(uma.toString()),
                    "区块未加载");
            env.resident("abydos", "Unlucky_Uma");
            int logBefore = env.repo.districtLog("abydos", 50).size();
            DistrictRuleException failed = expect(helper, DistrictError.SYNC_RETRY_FAILED, "重试仍失败",
                    () -> env.ctx.residents().retrySync(env.admin, "abydos", "Unlucky_Uma"));
            helper.assertTrue(failed.getMessage().equals("重试没有成功：区块未加载。Unlucky_Uma 仍是“同步失败”"),
                    "文案带上 Flan 的失败原因, 实为 " + failed.getMessage());
            helper.assertTrue(env.member("Unlucky_Uma").syncStatus() == ResidentSyncStatus.FAILED,
                    "失败后仍是同步失败");
            helper.assertTrue(env.repo.districtLog("abydos", 50).size() == logBefore, "失败时不写 resync 记录");

            env.recording().clearFailures();
            MemberRecord retried = env.ctx.residents().retrySync(env.admin, "abydos", "Unlucky_Uma");
            helper.assertTrue(retried.syncStatus() == ResidentSyncStatus.SYNCED && retried.syncError() == null,
                    "重试成功后改为已生效并清空原因, 实为 " + retried);
            DistrictLogEntry resync = env.repo.districtLog("abydos", 1).get(0);
            helper.assertTrue(resync.action() == DistrictLogAction.RESYNC && "Unlucky_Uma".equals(resync.targetName()),
                    "成功时写 resync 记录, 实为 " + resync);
        }
        helper.succeed();
    }

    // ================================================================
    // admin.district.setWarden
    // ================================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void setWardenRulesAndReplaceWritesRevokeThenAppoint(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "First_Fi");
            env.ctx.residents().addResident(env.admin, "abydos", "Second_Se", true);
            DistrictAdminService admin = env.ctx.admin();
            expect(helper, DistrictError.PERMISSION_DENIED, "非 OP 任命",
                    () -> admin.setWarden(player("First_Fi"), "abydos", "First_Fi"));
            expect(helper, DistrictError.WARDEN_NOT_APPOINTED, "没有区务长时撤销",
                    () -> admin.setWarden(env.admin, "abydos", null));
            DistrictRuleException notResident = expect(helper, DistrictError.NOT_RESIDENT, "任命名单外的人",
                    () -> admin.setWarden(env.admin, "abydos", "Ghost_Gus"));
            helper.assertTrue(notResident.getMessage().equals("Ghost_Gus 还不是本区住户，请先把 TA 加进来再任命"),
                    "文案, 实为 " + notResident.getMessage());

            DistrictAdminService.WardenResult first = admin.setWarden(env.admin, "abydos", "first_fi");
            helper.assertTrue("First_Fi".equals(first.wardenName()) && first.logEntry().action() == DistrictLogAction.APPOINT,
                    "任命用名单上的规范名, 只写 appoint, 实为 " + first);
            expect(helper, DistrictError.ALREADY_WARDEN, "重复任命", () -> admin.setWarden(env.admin, "abydos", "First_Fi"));

            DistrictAdminService.WardenResult second = admin.setWarden(env.admin, "abydos", "Second_Se");
            helper.assertTrue("Second_Se".equals(second.wardenName()), "待生效的住户也可以任命");
            List<DistrictLogEntry> log = env.repo.districtLog("abydos", 3);
            helper.assertTrue(log.get(0).action() == DistrictLogAction.APPOINT && "Second_Se".equals(log.get(0).targetName())
                            && log.get(1).action() == DistrictLogAction.REVOKE && "First_Fi".equals(log.get(1).targetName())
                            && log.get(2).action() == DistrictLogAction.APPOINT,
                    "换人先写 revoke 再写 appoint (新的在前时 appoint 在上), 实为 " + log);
            helper.assertTrue(second.logEntry().id() == log.get(0).id(), "回执的 logEntry 是 appoint 那一条");

            DistrictAdminService.WardenResult revoked = admin.setWarden(env.admin, "abydos", null);
            helper.assertTrue(revoked.wardenName() == null && revoked.logEntry().action() == DistrictLogAction.REVOKE
                            && "Second_Se".equals(revoked.logEntry().targetName()),
                    "显式 null 撤销, 记 revoke 前任, 实为 " + revoked);
            helper.assertTrue(env.districtRecord("abydos").wardenUuid() == null, "区务长已清空");
        }
        helper.succeed();
    }

    private static Long suspendedAt(DistrictTestEnv env, String plotId, String name) {
        for (FriendRecord friend : env.repo.friendsOf(plotId)) {
            if (friend.name().equalsIgnoreCase(name)) {
                return friend.suspendedAt();
            }
        }
        throw new IllegalStateException("no friend " + name + " on " + plotId);
    }

    /** syncIssues 计数的小快照。 */
    private record DistrictRepositorySnapshot(int syncPending, int syncFailed) {
        static DistrictRepositorySnapshot of(DistrictTestEnv env) {
            var counts = env.repo.countMembersBySync("abydos");
            return new DistrictRepositorySnapshot(counts.pending(), counts.failed());
        }
    }
}

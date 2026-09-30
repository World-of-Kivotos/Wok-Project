package com.miningdim.district.flan;

import com.miningdim.core.MiningConstants;
import com.miningdim.district.DistrictFeature;
import com.miningdim.district.DistrictLimits;
import com.miningdim.district.DistrictSystem;
import com.miningdim.district.DistrictTestEnv;
import com.miningdim.district.core.DistrictBounds;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.MemberRecord;
import com.miningdim.district.core.PermissionCatalog;
import com.miningdim.district.core.PermissionItemDef;
import com.miningdim.district.core.PlotArea;
import com.miningdim.district.core.PlotRecord;
import com.miningdim.district.core.PlotSyncStatus;
import com.miningdim.district.core.ResidentSyncStatus;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.miningdim.district.DistrictTestEnv.player;
import static com.miningdim.district.DistrictTestEnv.uuidOf;

/**
 * Flan 推送 (设计文档第八章): 地块整块重写的期望状态 (一人一组, 户主 &gt; 朋友 &gt; 居民)、管理类权限恒假、区域规则不写在
 * 地块上、成员资格触达每块地、记录型网关的不变式断言、生产默认网关如实报"未启用"、失败的地块下一次写入自然补齐。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class DistrictFlanSyncGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "district_flan_sync";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ownedPlotGroupsFollowPriority(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_One");
            env.resident("abydos", "Friend_Fay");
            env.resident("abydos", "Resident_Rae");
            env.resident("abydos", "Suspended_Su");
            env.seen("Outsider_Oz");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.own(plot, "Owner_One");
            env.ctx.plotOwners().addFriend(player("Owner_One"), "abydos", plot, "Friend_Fay", false);
            env.ctx.plotOwners().addFriend(player("Owner_One"), "abydos", plot, "Suspended_Su", false);
            env.ctx.plotOwners().addFriend(player("Owner_One"), "abydos", plot, "Outsider_Oz", false);
            env.ctx.plotOwners().addFriend(player("Owner_One"), "abydos", plot, "Pending_Pat", true);
            env.ctx.residents().removeResident(env.admin, "abydos", "Suspended_Su", "violation", "违反区规");
            env.ctx.residents().addResident(env.admin, "abydos", "Suspended_Su", false);

            Map<UUID, String> members = env.recording().membersOf(env.plotRecord(plot).flanClaimId());
            helper.assertTrue(FlanGroupNames.plotOwner(plot).equals(members.get(uuidOf("Owner_One"))), "户主在户主组");
            helper.assertTrue(FlanGroupNames.plotFriend(plot).equals(members.get(uuidOf("Friend_Fay"))),
                    "既是住户又是朋友的人按朋友算");
            helper.assertTrue(FlanGroupNames.plotFriend(plot).equals(members.get(uuidOf("Outsider_Oz"))),
                    "外人朋友进朋友组");
            helper.assertTrue(FlanGroupNames.plotResident(plot).equals(members.get(uuidOf("Resident_Rae"))),
                    "普通住户进居民组");
            helper.assertTrue(FlanGroupNames.plotResident(plot).equals(members.get(uuidOf("Suspended_Su"))),
                    "被暂停的朋友是住户时进居民组");
            helper.assertTrue(!members.containsKey(uuidOf("Pending_Pat")), "待生效的朋友不进任何组");
            helper.assertTrue(members.size() == 5, "一人一组, 共 5 人, 实为 " + members);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void vacantPlotHasOnlyResidentGroup(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Resident_Rae");
            env.ctx.residents().addResident(env.admin, "abydos", "Pending_Pe", true);
            env.recording().failWhen(call -> call.op().equals("setMember")
                    && call.hasArg(uuidOf("Failed_Fa").toString()) && call.hasArg("d_abydos_resident"), "区块未加载");
            env.resident("abydos", "Failed_Fa");
            env.recording().clearFailures();
            String plot = env.plot("abydos", 10, 10, 25, 25);
            Map<UUID, String> members = env.recording().membersOf(env.plotRecord(plot).flanClaimId());
            helper.assertTrue(members.equals(Map.of(uuidOf("Resident_Rae"), FlanGroupNames.plotResident(plot))),
                    "空置地块只有居民组: 本区 synced 名单全员 (待生效与同步失败的不在), 实为 " + members);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void regionAndAdminPermissionsOnPlots(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_One");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.own(plot, "Owner_One");
            RecordingFlanGateway flan = env.recording();
            UUID claim = env.plotRecord(plot).flanClaimId();
            for (RecordingFlanGateway.FlanCall call : flan.callsOf("setDefaultPermission")) {
                if (!claim.equals(call.claimId())) {
                    continue;
                }
                boolean region = PermissionCatalog.regionItems().stream()
                        .anyMatch(item -> item.flanIds().contains(call.args().get(0)));
                helper.assertTrue(!region || "UNSET".equals(call.args().get(1)),
                        "区域规则在地块上只写 UNSET (跟随自管区), 实为 " + call);
            }
            for (String group : new String[]{FlanGroupNames.plotOwner(plot), FlanGroupNames.plotFriend(plot),
                    FlanGroupNames.plotResident(plot)}) {
                for (String admin : FlanPermissions.ADMIN) {
                    helper.assertTrue(Boolean.FALSE.equals(flan.groupPerm(claim, group, admin)),
                            group + " 的 " + admin + " 显式写假");
                }
            }
            for (PermissionItemDef item : PermissionCatalog.memberItems()) {
                helper.assertTrue(Boolean.TRUE.equals(flan.groupPerm(claim, FlanGroupNames.plotOwner(plot),
                        item.flanIds().get(0))), "户主组的非管理类权限全真: " + item.permissionId());
            }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void residentMembershipTouchesEveryUnfrozenPlot(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_One");
            env.resident("abydos", "Owner_Two");
            String owned = env.plot("abydos", 10, 10, 25, 25);
            String vacant = env.plot("abydos", 40, 10, 55, 25);
            String frozen = env.plot("abydos", 70, 10, 85, 25);
            env.own(owned, "Owner_One");
            env.own(frozen, "Owner_Two");
            env.ctx.residents().removeResident(env.admin, "abydos", "Owner_Two", "inactive", "不上线");
            UUID newcomer = env.resident("abydos", "Newcomer_Nia").uuid();
            helper.assertTrue(FlanGroupNames.plotResident(owned).equals(
                            env.recording().membersOf(env.plotRecord(owned).flanClaimId()).get(newcomer))
                            && FlanGroupNames.plotResident(vacant).equals(
                            env.recording().membersOf(env.plotRecord(vacant).flanClaimId()).get(newcomer)),
                    "新住户进每块未冻结地块的居民组 (子领地只在创建时复制一次快照, 所以要逐块写)");
            helper.assertTrue(env.recording().membersOf(env.plotRecord(frozen).flanClaimId()).isEmpty(),
                    "冻结中的地块跳过, 居民组保持为空");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void recordingGatewayRejectsInvariantBreaks(GameTestHelper helper) {
        RecordingFlanGateway flan = new RecordingFlanGateway();
        ClaimHandle district = flan.createDistrictClaim("minecraft:overworld",
                new DistrictBounds("minecraft:overworld", 0, 0, 99, 99), "test").value();
        helper.assertTrue(district != null, "建得出父领地");
        helper.assertTrue(throwsAssertion(() -> flan.setGroupPermission(district, "d_x_resident", "flan:edit_perms",
                PermValue.TRUE)), "edit_* 写真必须当场报错");
        helper.assertTrue(throwsAssertion(() -> flan.setDefaultPermission(district, "flan:edit_claim", PermValue.TRUE)),
                "默认权限里 edit_* 写真也报错");
        helper.assertTrue(throwsAssertion(() -> flan.setGroupPermission(district, "p_x_owner", "flan:place",
                PermValue.TRUE)), "父领地上写非 d_ 组必须报错");
        flan.setGroupPermission(district, "d_x_resident", "flan:place", PermValue.TRUE);
        flan.setMember(district, uuidOf("Anyone_Ann"), "d_x_resident");

        flan.disableScrubForTest();
        ClaimHandle leaky = flan.createPlotClaim(district, new PlotArea(10, 10, 20, 20), "leaky", "x-01").value();
        helper.assertTrue(leaky != null && flan.groupsOf(leaky.claimId()).contains("d_x_resident")
                        && flan.membersOf(leaky.claimId()).containsKey(uuidOf("Anyone_Ann")),
                "不清理时子领地带着继承来的组与成员快照 (Flan 的浅拷贝语义)");
        helper.assertTrue(throwsAssertion(() -> flan.setGroupPermission(leaky, "d_x_resident", "flan:place",
                PermValue.FALSE)), "往地块写继承来的组必须报错 (共用内层 Map, 会连带改掉全区)");
        helper.succeed();
    }

    /**
     * 开服选网关 (20.2) 的每一个分支, 用注入的"Flan 在不在位 / 版本 / 自检 / 工厂"核对:
     * <ul>
     *   <li>记录型只在 GameTest 服务端上选得上 (null 代表"不是 GameTest 服务端": 忽略系统属性, 照常往下选);</li>
     *   <li>没装 Flan、版本不符、自检不过 → Disabled 带原因, 自检与工厂按顺序短路, 真网关根本没被造出来;</li>
     *   <li>都通过 → 工厂造出来的网关, LIVE。</li>
     * </ul>
     * 另核对开发运行时 (加载着服主批准的 Flan, 设计文档 20.1 出路 ③) 里真实的 DistrictSystem.selectGateway 走完自检、
     * 选出真网关。"没装 Flan"一支由注入的 flanLoaded = false 覆盖。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void recordingGatewayIsGameTestServerOnly(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        helper.assertTrue(server instanceof GameTestServer, "本用例跑在 GameTest 服务端上");
        String previous = System.getProperty(DistrictLimits.GATEWAY_PROPERTY);
        int[] checks = {0};
        int[] builds = {0};
        java.util.function.Supplier<com.miningdim.district.flan.real.FlanCompat.Result> passing = () -> {
            checks[0]++;
            return new com.miningdim.district.flan.real.FlanCompat.Result(true, List.of(),
                    com.miningdim.district.flan.real.FlanCompat.VERIFIED_VERSION, List.of());
        };
        java.util.function.Supplier<com.miningdim.district.flan.real.FlanCompat.Result> failing = () -> {
            checks[0]++;
            return new com.miningdim.district.flan.real.FlanCompat.Result(false, List.of("missing X#y()"),
                    com.miningdim.district.flan.real.FlanCompat.VERIFIED_VERSION, List.of());
        };
        GatewaySelector.RealFactory factory = () -> {
            builds[0]++;
            return new RecordingFlanGateway();
        };
        String verified = com.miningdim.district.flan.real.FlanCompat.VERIFIED_VERSION;
        try {
            System.clearProperty(DistrictLimits.GATEWAY_PROPERTY);
            GatewaySelector.Selection real = DistrictSystem.selectGateway(server);
            if (DistrictTestEnv.withoutFlanRun()) {
                // -PwithoutFlan 的专门一轮: 生产环境没装 Flan 的那条路, 真实的开服选择选出 Disabled, 一个 Flan 类都不碰。
                helper.assertTrue(real.state() == DistrictFeature.State.DEGRADED
                                && real.gateway() instanceof DisabledFlanGateway disabled
                                && DistrictTexts.FLAN_REASON_MISSING.equals(disabled.reason()) && real.flanVersion() == null,
                        "没有 Flan 的运行时: 真实的开服选择选出 Disabled (原因: Flan 没有安装), 实为 " + real);
            } else {
                helper.assertTrue(real.state() == DistrictFeature.State.LIVE
                                && "FlanClaimGateway".equals(real.gateway().getClass().getSimpleName())
                                && com.miningdim.district.flan.real.FlanCompat.VERIFIED_VERSION.equals(real.flanVersion())
                                && real.problems().isEmpty(),
                        "开发运行时加载着服主批准的 Flan (20.1 出路 ③): 真实的开服选择走完自检, 选出真网关, LIVE, 实为 "
                                + real);
            }

            GatewaySelector.Selection missing = GatewaySelector.select(server, false, null, passing, factory);
            helper.assertTrue(missing.gateway() instanceof DisabledFlanGateway && checks[0] == 0 && builds[0] == 0,
                    "没装 Flan: 不自检、不造真网关");
            GatewaySelector.Selection version = GatewaySelector.select(server, true, "1.20.1-1.11.15", passing,
                    factory);
            helper.assertTrue(version.gateway() instanceof DisabledFlanGateway disabled
                            && DistrictTexts.flanReasonVersion("1.20.1-1.11.15").equals(disabled.reason())
                            && checks[0] == 0 && builds[0] == 0,
                    "版本不是核对过的那一个: 即使签名全对也不认, 实为 " + version);
            GatewaySelector.Selection mismatch = GatewaySelector.select(server, true, verified, failing, factory);
            helper.assertTrue(mismatch.gateway() instanceof DisabledFlanGateway disabled
                            && DistrictTexts.FLAN_REASON_SELF_CHECK.equals(disabled.reason())
                            && mismatch.problems().equals(List.of("missing X#y()")) && checks[0] == 1
                            && builds[0] == 0,
                    "自检不过: Disabled, 问题清单原样带出, 真网关没被造出来 (一次写入都不可能发生), 实为 " + mismatch);
            GatewaySelector.Selection live = GatewaySelector.select(server, true, verified, passing, factory);
            helper.assertTrue(live.gateway() instanceof RecordingFlanGateway
                            && live.state() == DistrictFeature.State.LIVE && builds[0] == 1,
                    "都通过: 工厂造出网关, LIVE");

            System.setProperty(DistrictLimits.GATEWAY_PROPERTY, "recording");
            helper.assertTrue(GatewaySelector.select(server, false, null, passing, factory).gateway()
                    instanceof RecordingFlanGateway, "GameTest 服务端上照系统属性换成记录型网关");
            helper.assertTrue(GatewaySelector.select(null, false, null, passing, factory).gateway()
                    instanceof DisabledFlanGateway, "不是 GameTest 服务端: 忽略系统属性, 照常往下选");
        } finally {
            if (previous == null) {
                System.clearProperty(DistrictLimits.GATEWAY_PROPERTY);
            } else {
                System.setProperty(DistrictLimits.GATEWAY_PROPERTY, previous);
            }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void disabledGatewayReportsNotEnabled(GameTestHelper helper) {
        String expected = DistrictTexts.flanDisabled(DistrictTexts.FLAN_REASON_MISSING);
        helper.assertTrue("领地对接未启用：Flan 没有安装".equals(expected), "带原因的文案, 实为 " + expected);
        try (DistrictTestEnv env = DistrictTestEnv.openWith(new DisabledFlanGateway(DistrictTexts.FLAN_REASON_MISSING))) {
            env.abydos();
            MemberRecord member = env.resident("abydos", "Known_Kai");
            helper.assertTrue(member.syncStatus() == ResidentSyncStatus.FAILED
                            && expected.equals(member.syncError()),
                    "降级网关: 住户如实显示同步失败, 原因带上降级原因, 实为 " + member);
            String plot = env.plot("abydos", 10, 10, 25, 25);
            PlotRecord record = env.plotRecord(plot);
            helper.assertTrue(record.syncStatus() == PlotSyncStatus.FAILED
                            && expected.equals(record.syncError()) && record.flanClaimId() == null,
                    "地块同样如实显示失败, 实为 " + record);
            helper.assertTrue(env.districtRecord("abydos").needsReconcile() && env.districtRecord("abydos").flanClaimId() == null,
                    "建区时父领地没建成: 置对账标记");
            helper.assertTrue(!env.ctx.queries().detail(player("Known_Kai"), "abydos").abilities().build(),
                    "没生效时不许说住户能建造");
        }
        helper.succeed();
    }

    /**
     * 降级时查询一律为空 (20.2), 不能当成"父领地被删了": 库里记着父领地 id 的区 (建区时 Flan 还在, 之后被卸掉), 推送与
     * 对账 (预演与 resync) 都报出真正的原因"领地对接未启用：…", 不说"在 Flan 里找不到了", 也不置对账标记。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void degradedGatewayIsNotMistakenForAMissingClaim(GameTestHelper helper) {
        String expected = DistrictTexts.flanDisabled(DistrictTexts.FLAN_REASON_MISSING);
        try (DistrictTestEnv env = DistrictTestEnv.openWith(new DisabledFlanGateway(DistrictTexts.FLAN_REASON_MISSING))) {
            env.abydos();
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.repo.setDistrictClaimId("abydos", UUID.randomUUID());
            env.repo.clearNeedsReconcile("abydos");
            env.ctx.flanSync().writePlotState(plot);
            helper.assertTrue(expected.equals(env.plotRecord(plot).syncError())
                            && !env.districtRecord("abydos").needsReconcile(),
                    "推送: 地块的原因是降级原因, 不置对账标记, 实为 " + env.plotRecord(plot).syncError());
            DistrictReconciler.DistrictProgress dry = env.ctx.reconciler().reconcileDistrict("abydos",
                    DistrictReconciler.Mode.DRY_RUN);
            helper.assertTrue(dry.skipped() && dry.unfixable().equals(List.of(expected)),
                    "/district inspect: 报降级原因, 不报找不到, 实为 " + dry.unfixable());
            DistrictReconciler.DistrictProgress resync = env.ctx.reconciler().reconcileDistrict("abydos",
                    DistrictReconciler.Mode.EXPLICIT);
            helper.assertTrue(resync.unfixable().equals(List.of(expected)) && resync.failed() == 1
                            && !env.districtRecord("abydos").needsReconcile(),
                    "/district resync: 同样报降级原因、不置标记, 实为 " + resync.unfixable());
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void plotFailureHealsOnNextWrite(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_One");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.recording().failNext("setMember", "区块未加载");
            env.own(plot, "Owner_One");
            helper.assertTrue(env.plotRecord(plot).syncStatus() == PlotSyncStatus.FAILED
                            && "区块未加载".equals(env.plotRecord(plot).syncError()),
                    "写失败的地块标同步失败");
            env.ctx.plotOwners().setPermission(player("Owner_One"), "abydos", plot, "door", "outsider", false);
            helper.assertTrue(env.plotRecord(plot).syncStatus() == PlotSyncStatus.SYNCED
                            && env.plotRecord(plot).syncError() == null,
                    "下一次任何改动都整块重写, 自然补齐");
            helper.assertTrue(FlanGroupNames.plotOwner(plot).equals(env.recording().membersOf(
                    env.plotRecord(plot).flanClaimId()).get(uuidOf("Owner_One"))), "户主补进户主组");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void resyncRewritesEverythingAndCounts(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.recording().failNext("setMember", "区块未加载");
            env.resident("abydos", "Unlucky_Uma");
            env.resident("abydos", "Lucky_Lu");
            env.ctx.residents().addResident(env.admin, "abydos", "Pending_Pe", true);
            env.plot("abydos", 10, 10, 25, 25);
            DistrictReconciler.DistrictProgress report = env.ctx.reconciler().reconcileDistrict("abydos",
                    DistrictReconciler.Mode.EXPLICIT);
            helper.assertTrue(report.failed() == 0 && report.succeeded() == 4,
                    "父领地 1 + 住户 2 (待生效的跳过) + 地块 1, 实为 " + report.succeeded() + " / " + report.failed()
                            + " " + report.unfixable());
            helper.assertTrue(env.recording().callsOf("backupNow").stream().anyMatch(call -> call.hasArg("resync")),
                    "resync 先强制备份");
            helper.assertTrue(env.member("Unlucky_Uma").syncStatus() == ResidentSyncStatus.SYNCED,
                    "同步失败的住户重推后生效");
            helper.assertTrue(env.member("Pending_Pe").syncStatus() == ResidentSyncStatus.PENDING, "待生效的不动");
        }
        helper.succeed();
    }

    private static boolean throwsAssertion(Runnable body) {
        try {
            body.run();
            return false;
        } catch (AssertionError expected) {
            return true;
        }
    }
}

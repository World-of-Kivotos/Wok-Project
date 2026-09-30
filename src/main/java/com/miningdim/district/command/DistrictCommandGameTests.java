package com.miningdim.district.command;

import com.miningdim.core.MiningConstants;
import com.miningdim.district.DistrictLimits;
import com.miningdim.district.DistrictTestEnv;
import com.miningdim.district.core.DistrictLogAction;
import com.miningdim.district.core.DistrictLogEntry;
import com.miningdim.district.core.DistrictBounds;
import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.MemberRecord;
import com.miningdim.district.core.PermissionCatalog;
import com.miningdim.district.core.PlotArea;
import com.miningdim.district.core.PlotStatus;
import com.miningdim.district.core.ResidentSyncStatus;
import com.miningdim.district.flan.DisabledFlanGateway;
import com.miningdim.district.flan.DistrictReconciler;
import com.miningdim.district.flan.FlanGroupNames;
import com.miningdim.district.flan.RecordingFlanGateway;
import com.miningdim.district.guard.GuardSettings;
import com.miningdim.district.guard.WorldGuardGameTests;
import com.miningdim.district.service.DistrictAdminService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * /district OP 引导命令 (设计文档第十六章): 权限门、建区的默认值与拒绝、批量加住户逐人回报、任免区务长写记录、
 * 改范围拒绝落到范围外的地块、到期收回与重推的计数, 以及区规、查询类子命令。命令与平板调同一批服务方法, 这里只核
 * 命令层自己的事: 参数解析、反馈的语言键、返回值与写进库里的东西。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class DistrictCommandGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "district_commands";
    private static final String KEY = "district.miningdim.command.";

    // ================================================================
    // 权限门
    // ================================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void commandsRequirePermissionLevel2(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = null;
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            player = DistrictTestEnv.onlinePlayer(helper, "Cmd_Player");
            CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
            Capture out = new Capture();
            CommandSourceStack asPlayer = player.createCommandSourceStack().withSource(out).withPermission(0);
            CommandSourceStack asOp = player.createCommandSourceStack().withSource(out).withPermission(2);
            CommandSourceStack asConsole = server.createCommandSourceStack().withSource(out);
            for (String command : List.of("district academy list", "district academy members abydos",
                    "district academy kick abydos Someone", "district create abydos 0 0 199 199",
                    "district create abydos 0 0 199 199 minecraft:overworld 5", "district bounds abydos 0 0 10 10",
                    "district rules abydos add 不许乱挖", "district rules abydos remove 1", "district rules abydos clear",
                    "district warden abydos set Someone", "district warden abydos clear",
                    "district residents abydos add A_one B_two", "district residents abydos addUnseen A_one",
                    "district list", "district info abydos", "district plots abydos", "district sweep",
                    "district resync abydos", "district status", "district inspect abydos",
                    "district bounds abydos sync", "district bind abydos 0 0", "district bind abydos ~ ~ confirm",
                    "district claim abydos recreate", "district machines abydos",
                    "district personalclaims abydos")) {
                helper.assertTrue(parses(dispatcher, asConsole, command), "控制台应能解析: " + command);
                helper.assertTrue(parses(dispatcher, asOp, command), "2 级权限的玩家应能解析: " + command);
                helper.assertTrue(!parses(dispatcher, asPlayer, command), "普通玩家不得执行: " + command);
            }
            helper.assertTrue(env.repo.liveDistricts().isEmpty(), "只解析不执行");
        } finally {
            DistrictTestEnv.removePlayer(helper, player);
        }
        helper.succeed();
    }

    /**
     * 功能关着 (OFF, 服务不绑定) 时 (20.8): 除 status 外一律回 disabled 并返回 0; status 照常可用, 报出"关闭"。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void commandsReplyDisabledExceptStatus(GameTestHelper helper) {
        helper.assertTrue(!com.miningdim.district.DistrictServices.isRegistered()
                        && com.miningdim.district.DistrictFeature.state() == com.miningdim.district.DistrictFeature.State.OFF,
                "前提: GameTest 服务端上 enabled 恒为 false, 功能 OFF, 服务不绑定");
        Console console = new Console(helper);
        for (String command : List.of("district list", "district info abydos", "district create abydos 0 0 199 199",
                "district resync abydos", "district inspect abydos", "district bind abydos 0 0",
                "district bounds abydos sync", "district claim abydos recreate", "district machines abydos",
                "district personalclaims abydos")) {
            console.expect(command, 0, KEY + "disabled");
        }
        List<String> status = console.expect("district status", 1, KEY + "status.state");
        helper.assertTrue(status.contains(KEY + "status.state.off"), "status 报出关闭, 实为 " + status);
        helper.assertTrue(status.contains(KEY + "status.guard.off") && status.contains(KEY + "status.guard.world"),
                "功能关着时守卫没装, mixin 照样核对并列出, 实为 " + status);
        helper.succeed();
    }

    // ================================================================
    // 建区
    // ================================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void createSeedsPermissionsAndDefaults(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            Console console = new Console(helper);
            console.expect("district create abydos 199 199 0 0", 1, KEY + "create.done");
            DistrictRecord abydos = env.districtRecord("abydos");
            helper.assertTrue(abydos.bounds().minX() == 0 && abydos.bounds().maxZ() == 199
                            && DistrictTestEnv.DIMENSION.equals(abydos.bounds().dimension())
                            && abydos.unitPrice() == DistrictLimits.DEFAULT_UNIT_PRICE
                            && abydos.minSide() == DistrictLimits.DEFAULT_MIN_SIDE
                            && abydos.maxSide() == DistrictLimits.DEFAULT_MAX_SIDE && !abydos.purchaseOpen()
                            && "阿拜多斯自管区".equals(abydos.displayName())
                            && DistrictLimits.CONSOLE_ACTOR_NAME.equals(abydos.createdByName()),
                    "建区的默认值: 两角规整、维度取执行者所在世界、单价 5、边长 8~48、购买关闭, 实为 " + abydos);
            helper.assertTrue(env.repo.districtCells("abydos").asMap().size() == PermissionCatalog.items().size(),
                    "开关表按目录全部写好");

            console.expect("district create millennium 1000 0 1199 199 minecraft:overworld 7", 1, KEY + "create.done");
            helper.assertTrue(env.districtRecord("millennium").unitPrice() == 7, "显式单价");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void createRejectsOverlapAndSecondLiveBinding(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            Console console = new Console(helper);
            console.expect("district create abydos 0 0 199 199", 1, KEY + "create.done");
            console.expect("district create abydos 500 500 600 600", 0, KEY + "create.already_bound");
            console.expect("district create gehenna 150 150 300 300", 0, KEY + "overlaps_district");
            console.expect("district create nowhere 700 700 800 800", 0, KEY + "academy.unknown");
            helper.assertTrue(env.repo.liveDistricts().size() == 1, "被拒的建区一个都没写");
        }
        helper.succeed();
    }

    // ================================================================
    // 住户与区务长
    // ================================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bulkAddReportsPerName(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.seen("Alpha_A", "Beta_B");
            Console console = new Console(helper);
            List<String> keys = console.expect("district residents abydos add Alpha_A,beta_b  Unseen_U bad!name", 2,
                    KEY + "residents.summary");
            helper.assertTrue(keys.stream().filter((KEY + "residents.added")::equals).count() == 2
                            && keys.stream().filter((KEY + "residents.rejected")::equals).count() == 2,
                    "逐人回报: 2 人加入, 2 人被拒, 实为 " + keys);
            List<String> names = env.repo.membersOf("abydos").stream().map(MemberRecord::name).toList();
            helper.assertTrue(names.equals(List.of("Alpha_A", "Beta_B")), "存规范名, 从没进过服的被拒, 实为 " + names);
            helper.assertTrue(console.texts().stream().anyMatch(text -> text.contains("没有找到 Unseen_U 的登录记录")),
                    "被拒的原因原文照转, 实为 " + console.texts());
            console.expect("district residents abydos add ,,", 0, KEY + "residents.empty");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void addUnseenRecordsPending(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            Console console = new Console(helper);
            console.expect("district residents abydos addUnseen Pebble_Fox", 1, KEY + "residents.added");
            MemberRecord member = env.member("Pebble_Fox");
            helper.assertTrue(member.syncStatus() == ResidentSyncStatus.PENDING
                            && member.uuid().equals(DistrictTestEnv.uuidOf("Pebble_Fox"))
                            && DistrictLimits.CONSOLE_ACTOR_NAME.equals(member.addedByName()),
                    "从没进过服的人以离线 UUID、待生效记入, 实为 " + member);
            console.expect("district residents abydos addUnseen Pebble_Fox", 0, KEY + "residents.summary");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wardenSetAndClearWriteLogs(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Ward_W");
            Console console = new Console(helper);
            console.expect("district warden abydos set ward_w", 1, KEY + "warden.set");
            helper.assertTrue("Ward_W".equals(env.districtRecord("abydos").wardenName()), "任命用名单上的规范名");
            console.expect("district warden abydos clear", 1, KEY + "warden.cleared");
            List<DistrictLogEntry> log = env.repo.districtLog("abydos", 5);
            helper.assertTrue(log.get(0).action() == DistrictLogAction.REVOKE && log.get(1).action() == DistrictLogAction.APPOINT
                            && DistrictLimits.CONSOLE_ACTOR_NAME.equals(log.get(0).actorName())
                            && "admin".equals(log.get(0).actorRole().wire()),
                    "与平板同一条记录: 先 appoint 后 revoke, 操作人是控制台, 实为 " + log);
            console.expect("district warden abydos clear", 0, KEY + "rejected");
            helper.assertTrue(console.texts().contains("本区现在没有区务长"), "业务拒绝原文照转");
            console.expect("district warden abydos set Nobody_N", 0, KEY + "rejected");
            console.expect("district warden nowhere clear", 0, KEY + "rejected");
        }
        helper.succeed();
    }

    // ================================================================
    // 范围、区规、查询与排障
    // ================================================================

    /**
     * 带坐标的 /district bounds 只改库, 只在本区还没有父领地时可用 (功能降级时建的区, 20.6): 这时照旧校验地块落在范围外
     * 与边距; 一旦有了父领地, 范围以 Flan 为准, 一律回 bounds.follow_claim, 库不动。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void boundsWithCoordinatesFollowTheClaimOnceBound(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.openWith(
                new DisabledFlanGateway(DistrictTexts.FLAN_REASON_MISSING))) {
            env.abydos();
            env.plot("abydos", 150, 150, 170, 170);
            helper.assertTrue(env.districtRecord("abydos").flanClaimId() == null, "降级时建的区还没有父领地");
            Console console = new Console(helper);
            console.expect("district bounds abydos 0 0 100 100", 0, KEY + "bounds.plots_outside");
            helper.assertTrue(env.districtRecord("abydos").bounds().maxX() == 199, "被拒时范围不变");
            console.expect("district bounds abydos 0 0 171 171", 0, KEY + "bounds.plots_outside");
            console.expect("district bounds abydos 250 250 0 0", 1, KEY + "bounds.done");
            helper.assertTrue(env.districtRecord("abydos").bounds().maxX() == 250, "改成新范围");
            console.expect("district bounds nowhere 0 0 10 10", 0, KEY + "district_unknown");
            console.expect("district bounds abydos sync", 0, KEY + "flan_unavailable");
        }
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            Console console = new Console(helper);
            console.expect("district bounds abydos 0 0 250 250", 0, KEY + "bounds.follow_claim");
            helper.assertTrue(env.districtRecord("abydos").bounds().maxX() == 199, "已有父领地: 库不动");
        }
        helper.succeed();
    }

    /**
     * /district bounds &lt;id&gt; sync (20.6): 读父领地 (OP 用金锄头改过) 的 X/Z, 按改范围的规则校验后写库; 一致时回
     * unchanged; 缩到压住地块时拒绝并列出编号, 库不动, 对账预演报告范围漂移。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void boundsSyncReadsTheClaimAndRejectsPlotsOutside(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            String plot = env.plot("abydos", 150, 150, 170, 170);
            UUID claim = env.districtRecord("abydos").flanClaimId();
            Console console = new Console(helper);
            console.expect("district bounds abydos sync", 1, KEY + "bounds.sync.unchanged");
            env.recording().driftResize(claim, new PlotArea(0, 0, 250, 250));
            console.expect("district bounds abydos sync", 1, KEY + "bounds.sync.done");
            helper.assertTrue(env.districtRecord("abydos").bounds().equals(
                    new DistrictBounds(DistrictTestEnv.DIMENSION, 0, 0, 250, 250)), "库跟着领地走");
            env.recording().driftResize(claim, new PlotArea(0, 0, 160, 160));
            List<String> keys = console.expect("district bounds abydos sync", 0, KEY + "bounds.sync.plots_outside");
            helper.assertTrue(console.texts().stream().anyMatch(text -> text.contains(env.plotRecord(plot).code())),
                    "列出落在范围外的地块编号, 实为 " + keys + " / " + console.texts());
            helper.assertTrue(env.districtRecord("abydos").bounds().maxX() == 250, "被拒时库不动");
            DistrictReconciler.DistrictProgress dry = env.ctx.reconciler().reconcileDistrict("abydos",
                    DistrictReconciler.Mode.DRY_RUN);
            helper.assertTrue(dry.unfixable().stream().anyMatch(line -> line.contains("district claim covers")),
                    "对账预演报告范围漂移, 实为 " + dry.unfixable());
            helper.assertTrue(dry.unfixable().stream().anyMatch(line -> line.contains("lies outside the district claim")),
                    "超出父领地的地块只报告, 不自动缩小, 实为 " + dry.unfixable());
        }
        helper.succeed();
    }

    /** 建区时这片范围上已有 Flan 领地: FLAN_CLAIM_EXISTS, 管理员领地带 (admin) 并提示改用 bind; 库里一行都不写。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void createRejectsWhereAFlanClaimExists(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            UUID existing = env.recording().driftAdminClaim(DistrictTestEnv.DIMENSION, new PlotArea(100, 100, 300, 300),
                    "old");
            Console console = new Console(helper);
            console.expect("district create abydos 0 0 199 199", 0, KEY + "flan_claim_exists");
            helper.assertTrue(console.texts().stream().anyMatch(text -> text.contains(existing + " (admin)")),
                    "列出相交的领地并标出管理员领地, 实为 " + console.texts());
            helper.assertTrue(env.repo.liveDistricts().isEmpty(), "被拒的建区一个都没写");
        }
        helper.succeed();
    }

    /**
     * /district bind (20.6): 预览一个字都不写; 确认后收编 —— 默认组、成员、子领地、假玩家与药水全清, 只剩本区居民组,
     * 库里记着这块领地的 id, 范围取领地的 X/Z; 已被在用区绑着的领地、玩家领地、没有领地的位置分别被拒。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bindPreviewWritesNothingAndConfirmAdopts(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            RecordingFlanGateway flan = env.recording();
            UUID claim = flan.driftAdminClaim(DistrictTestEnv.DIMENSION, new PlotArea(0, 0, 199, 199), "旧领地");
            flan.driftMember(claim, DistrictTestEnv.uuidOf("Stranger_S"), "Visitor");
            flan.driftSubclaim(claim, new PlotArea(10, 10, 20, 20));
            flan.driftSubclaim(claim, new PlotArea(30, 30, 40, 40));
            flan.driftFakePlayer(claim, UUID.randomUUID());
            flan.driftPotion(claim, "minecraft:speed", 1);
            env.seen("Resident_R");
            Console console = new Console(helper);
            List<String> preview = console.expect("district bind abydos 50 50", 1, KEY + "bind.preview.confirm");
            helper.assertTrue(preview.contains(KEY + "bind.preview.header"), "先列预览, 实为 " + preview);
            helper.assertTrue(env.repo.liveDistricts().isEmpty() && flan.groupsOf(claim).size() == 2
                            && flan.childrenOf(claim).size() == 2 && flan.callsOf("deleteGroup").isEmpty(),
                    "预览一个字都不写");
            console.expect("district bind abydos 500 500 confirm", 0, KEY + "claim_not_found");
            UUID playerClaim = flan.driftPlayerClaim(DistrictTestEnv.DIMENSION, new PlotArea(600, 600, 700, 700));
            console.expect("district bind abydos 650 650 confirm", 0, KEY + "claim_not_admin");
            helper.assertTrue(flan.exists(playerClaim), "玩家领地原样留着");

            console.expect("district bind abydos 50 50 confirm", 1, KEY + "bind.done");
            DistrictRecord abydos = env.districtRecord("abydos");
            helper.assertTrue(claim.equals(abydos.flanClaimId())
                            && abydos.bounds().equals(new DistrictBounds(DistrictTestEnv.DIMENSION, 0, 0, 199, 199)),
                    "库里记着这块领地的 id, 范围取领地的 X/Z, 实为 " + abydos);
            helper.assertTrue(flan.groupsOf(claim).equals(java.util.Set.of(FlanGroupNames.districtResident("abydos")))
                            && flan.membersOf(claim).isEmpty() && flan.childrenOf(claim).isEmpty()
                            && flan.fakePlayerCount(claim) == 0 && flan.potionCount(claim) == 0,
                    "收编: 只剩本区居民组, 成员、子领地、假玩家与药水全清, 实为 " + flan.groupsOf(claim));
            helper.assertTrue(flan.callsOf("backupNow").stream().anyMatch(call -> call.hasArg("bind"))
                            && flan.callsOf("flush").size() >= 1, "收编前强制备份, 收编后显式存盘");
            env.resident("abydos", "Resident_R");
            helper.assertTrue(FlanGroupNames.districtResident("abydos").equals(
                    flan.membersOf(claim).get(DistrictTestEnv.uuidOf("Resident_R"))), "之后照常加住户");

            env.ctx.admin().unbind(env.admin, "abydos");
            DistrictAdminService.CommandResult other = env.ctx.admin().bindDistrict(env.admin, "gehenna",
                    DistrictTestEnv.DIMENSION, 50, 50, true);
            helper.assertTrue(other.outcome() == DistrictAdminService.CommandOutcome.OVERLAPS_DISTRICT,
                    "别的学院不能绑已解绑区的领地, 实为 " + other.outcome());
            DistrictAdminService.CommandResult again = env.ctx.admin().bindDistrict(env.admin, "abydos",
                    DistrictTestEnv.DIMENSION, 50, 50, true);
            helper.assertTrue(again.ok() && again.district() != null
                            && "abydos-2".equals(again.district().districtId())
                            && claim.equals(again.district().flanClaimId()),
                    "同一个学院重新绑回自己的旧地走 bind, 实为 " + again.outcome());
            DistrictAdminService.CommandResult twice = env.ctx.admin().bindDistrict(env.admin, "millennium",
                    DistrictTestEnv.DIMENSION, 50, 50, true);
            helper.assertTrue(twice.outcome() == DistrictAdminService.CommandOutcome.CLAIM_ALREADY_BOUND
                            && twice.detail().equals(List.of("abydos-2")),
                    "已被在用区绑着的领地被拒, 实为 " + twice.outcome() + " " + twice.detail());
        }
        helper.succeed();
    }

    /** 父领地丢了: 对账只报告不重建; /district claim recreate 按库重建并 resync, 地块跟着重建; 领地还在时拒绝。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void recreateRebuildsAMissingDistrictClaim(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_O");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.own(plot, "Owner_O");
            Console console = new Console(helper);
            console.expect("district claim abydos recreate", 0, KEY + "claim_present");
            UUID old = env.districtRecord("abydos").flanClaimId();
            env.recording().driftDelete(old);
            DistrictReconciler.DistrictProgress auto = env.ctx.reconciler().reconcileDistrict("abydos",
                    DistrictReconciler.Mode.AUTO);
            helper.assertTrue(auto.skipped() && env.districtRecord("abydos").needsReconcile()
                            && old.equals(env.districtRecord("abydos").flanClaimId())
                            && env.recording().callsOf("createDistrictClaim").size() == 1,
                    "对账不擅自重建, 只置标记");
            console.expect("district claim abydos recreate", 1, KEY + "claim.recreate.done");
            UUID recreated = env.districtRecord("abydos").flanClaimId();
            helper.assertTrue(recreated != null && !recreated.equals(old) && env.recording().exists(recreated),
                    "新领地的 id 写回库");
            UUID plotClaim = env.plotRecord(plot).flanClaimId();
            helper.assertTrue(env.recording().childrenOf(recreated).contains(plotClaim)
                            && FlanGroupNames.plotOwner(plot).equals(env.recording().membersOf(plotClaim)
                            .get(DistrictTestEnv.uuidOf("Owner_O"))),
                    "resync 把地块按库重建, 户主回到户主组");
            helper.assertTrue(!env.districtRecord("abydos").needsReconcile(), "整轮对完, 标记清掉");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void rulesListInfoAndPlots(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            // 一个区都没有也是执行成功: 查询类返回列出的条数, 至少 1; 0 只留给拒绝。
            new Console(helper).expect("district list", 1, KEY + "list.none");
            env.abydos();
            env.resident("abydos", "Owner_O");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.own(plot, "Owner_O");
            Console console = new Console(helper);
            console.expect("district rules abydos add 不许在公共区域乱挖", 1, KEY + "rules.added");
            console.expect("district rules abydos add 夜里十点后不放烟花", 1, KEY + "rules.added");
            console.expect("district rules abydos remove 3", 0, KEY + "rules.bad_index");
            console.expect("district rules abydos remove 1", 1, KEY + "rules.removed");
            helper.assertTrue(env.districtRecord("abydos").rules().equals(List.of("夜里十点后不放烟花")), "删掉第一条");
            console.expect("district rules abydos add " + "长".repeat(DistrictLimits.MAX_RULE_CHARS + 1), 0,
                    KEY + "rules.too_long");

            console.expect("district list", 1, KEY + "list.entry");
            List<String> info = console.expect("district info abydos", 1, KEY + "info.header");
            helper.assertTrue(info.contains(KEY + "info.rule") && info.contains(KEY + "info.log_entry"),
                    "info 列出区规与最近的记录, 实为 " + info);
            console.expect("district plots abydos", 1, KEY + "plots.entry");
            helper.assertTrue(console.texts().stream().anyMatch(text -> text.contains("Owner_O")), "地块列表带户主");
            console.expect("district rules abydos clear", 1, KEY + "rules.cleared");
            helper.assertTrue(env.districtRecord("abydos").rules().isEmpty(), "清空区规");
            console.expect("district academy list", 6, KEY + "academy.list.entry");
            console.expect("district academy members abydos", 1, KEY + "academy.members.entry");
            console.expect("district academy members nowhere", 0, KEY + "academy.unknown");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void kickOnlyWorksOnUnboundAcademies(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Kick_Me");
            Console console = new Console(helper);
            console.expect("district academy kick abydos Kick_Me", 0, KEY + "academy.kick.still_bound");
            env.ctx.admin().unbind(env.admin, "abydos");
            console.expect("district academy members abydos", 1, KEY + "academy.members.unbound");
            console.expect("district academy kick abydos Nobody_N", 0, KEY + "academy.kick.not_member");
            console.expect("district academy kick abydos kick_me", 1, KEY + "academy.kick.done");
            helper.assertTrue(env.repo.membersOf("abydos").isEmpty(), "移出已解绑学院的名单");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sweepAndResyncReportCounts(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Gone_G");
            env.resident("abydos", "Stay_S");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.own(plot, "Gone_G");
            env.ctx.residents().removeResident(env.admin, "abydos", "Gone_G", "inactive", "不上线");
            Console console = new Console(helper);
            console.expect("district sweep", 1, KEY + "sweep.done");
            helper.assertTrue(console.lastArgs(KEY + "sweep.done").equals(List.of("0"))
                    && env.plotRecord(plot).frozen(), "没到期不收回");
            env.advance(DistrictLimits.FREEZE_MS + 1);
            console.expect("district sweep", 1, KEY + "sweep.done");
            helper.assertTrue(console.lastArgs(KEY + "sweep.done").equals(List.of("1"))
                    && env.plotRecord(plot).status() == PlotStatus.VACANT, "到期收回 1 块");

            // 返回值是成功的项数 (至少 1: 命令本身执行成功)。
            console.expect("district resync abydos", 3, KEY + "resync.done");
            List<String> clean = console.lastArgs(KEY + "resync.done");
            helper.assertTrue(clean.equals(List.of("abydos", "3", "0")),
                    "全部重推成功: 开关 1 + 住户 1 + 地块 1, 实为 " + clean);
            env.recording().failWhen(call -> true, "区块未加载");
            console.expect("district resync abydos", 1, KEY + "resync.done");
            List<String> failed = console.lastArgs(KEY + "resync.done");
            helper.assertTrue(failed.equals(List.of("abydos", "0", "3")), "网关全失败时如实计失败, 实为 " + failed);
            env.recording().clearFailures();
            console.expect("district resync nowhere", 0, KEY + "district_unknown");
        }
        helper.succeed();
    }

    // ================================================================
    // 守卫 (22.7、22.8)
    // ================================================================

    /**
     * 只有熔炉算"机器"的测试分类: minecraft 当成机械动力的命名空间, 其余带方块实体的原版方块全部放进放行名单。
     * /district machines 按列扫全高, 结构旁边一格宽的空隙和头顶上可能留着别的用例 (别的 batch) 的方块实体 (刷怪笼、信标、
     * 结构方块、命令方块……), 按一般的测试分类会被数进去, 计数就随世界而变。
     */
    private static GuardSettings furnaceOnlySettings() {
        List<String> allow = new ArrayList<>();
        for (ResourceLocation id : ForgeRegistries.BLOCKS.getKeys()) {
            Block block = ForgeRegistries.BLOCKS.getValue(id);
            if ("minecraft".equals(id.getNamespace()) && block instanceof EntityBlock && block != Blocks.FURNACE) {
                allow.add(id.toString());
            }
        }
        return GuardSettings.parse(true, true, List.of("minecraft"), List.of(), allow, true, namespace -> true,
                GuardSettings::blockRegistered);
    }

    /**
     * /district machines (22.8): 测试分类里只有熔炉是机器。区内放两个熔炉、外围 8 格放一个、更远放一个: 计数 3, 列出这三个的
     * 坐标, 不列更远的那个; bind confirm 的回显带摘要。用 16 × 5 × 16 的结构 (district_guard), 区在正中。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = WorldGuardGameTests.TEMPLATE, batch = BATCH)
    public static void machinesListsCreateBlockEntitiesInLoadedChunks(GameTestHelper helper) {
        GuardSettings furnaceOnly = furnaceOnlySettings();
        helper.assertTrue(furnaceOnly.problems().isEmpty(), "前提: 测试分类没有名单问题, 实为 " + furnaceOnly.problems());
        try (DistrictTestEnv env = DistrictTestEnv.openWithGuards(furnaceOnly)) {
            BlockPos min = helper.absolutePos(new BlockPos(7, 0, 7));
            BlockPos max = helper.absolutePos(new BlockPos(8, 0, 8));
            helper.setBlock(new BlockPos(7, 1, 7), Blocks.FURNACE);
            helper.setBlock(new BlockPos(8, 1, 8), Blocks.FURNACE);
            helper.setBlock(new BlockPos(8, 1, 15), Blocks.FURNACE);
            helper.setBlock(new BlockPos(8, 1, 13), Blocks.CHEST);
            helper.setBlock(new BlockPos(-2, 1, 8), Blocks.FURNACE);
            RecordingFlanGateway flan = env.recording();
            flan.driftAdminClaim(DistrictTestEnv.DIMENSION, new PlotArea(Math.min(min.getX(), max.getX()),
                    Math.min(min.getZ(), max.getZ()), Math.max(min.getX(), max.getX()),
                    Math.max(min.getZ(), max.getZ())), "旧领地");
            Console console = new Console(helper);
            List<String> bound = console.expect("district bind abydos " + min.getX() + " " + min.getZ() + " confirm",
                    1, KEY + "bind.done");
            List<String> summary = console.lastArgs(KEY + "machines.summary");
            helper.assertTrue(bound.contains(KEY + "machines.summary") && summary.size() == 3,
                    "bind confirm 的回显带摘要, 实为 " + bound);
            List<String> listed = console.expect("district machines abydos", Math.max(1,
                    Integer.parseInt(summary.get(1))), KEY + "machines.header");
            helper.assertTrue(summary.get(1).equals("3"),
                    "摘要与清单都是 3 个, 实为 " + summary + "; 清单 " + console.texts());
            List<String> header = console.lastArgs(KEY + "machines.header");
            helper.assertTrue(header.get(2).equals("3") && header.get(3).equals("1") && header.get(4).equals("0"),
                    "3 个机器、1 种、没有没加载的区块, 实为 " + header);
            List<String> entry = console.lastArgs(KEY + "machines.entry");
            boolean listsOurs = true;
            for (BlockPos furnace : List.of(new BlockPos(7, 1, 7), new BlockPos(8, 1, 8), new BlockPos(8, 1, 15))) {
                listsOurs &= entry.size() == 3 && entry.get(2).contains(coords(helper.absolutePos(furnace)));
            }
            helper.assertTrue(entry.get(0).equals("minecraft:furnace") && entry.get(1).equals("3") && listsOurs
                            && !entry.get(2).contains(coords(helper.absolutePos(new BlockPos(-2, 1, 8)))),
                    "按方块 id 计数并列出坐标 (外围 8 格以外的不列), 实为 " + entry + " (" + listed + ")");
            console.expect("district machines nowhere", 0, KEY + "district_unknown");
        }
        helper.succeed();
    }

    /** status 的守卫一节: 索引、三个开关、mixin 情况, 以及名单问题。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void statusShowsGuardState(GameTestHelper helper) {
        GuardSettings withProblem = GuardSettings.parse(true, false, List.of("minecraft"), List.of("nocolon"),
                List.of(), true, namespace -> true, GuardSettings::blockRegistered);
        try (DistrictTestEnv env = DistrictTestEnv.openWithGuards(withProblem)) {
            env.abydos();
            env.plot("abydos", 10, 10, 20, 20);
            Console console = new Console(helper);
            List<String> keys = console.expect("district status", 1, KEY + "status.guard.index");
            helper.assertTrue(keys.contains(KEY + "status.guard.not_installed")
                            && keys.contains(KEY + "status.guard.list_problem")
                            && keys.contains(KEY + "status.guard.world")
                            && keys.contains(KEY + (net.minecraftforge.fml.ModList.get().isLoaded("create")
                            ? "status.guard.create_ok" : "status.guard.create_absent")),
                    "守卫一节: 索引 (测试 context 不装进门面)、名单问题、mixin 情况, 实为 " + keys);
            List<String> index = console.lastArgs(KEY + "status.guard.index");
            helper.assertTrue(index.get(1).equals("1") && index.get(2).equals("1"),
                    "索引里 1 个区、1 块地, 实为 " + index);
            helper.assertTrue(console.lastArgs(KEY + "status.guard.namespaces").equals(List.of("minecraft")),
                    "列出算作机械动力的命名空间 (测试设置只有 minecraft), 实为 "
                            + console.lastArgs(KEY + "status.guard.namespaces"));
            List<String> world = console.lastArgs(KEY + "status.guard.world");
            helper.assertTrue(world.get(0).equals("6") && world.get(1).equals("6"), "原版守卫 6/6, 实为 " + world);
        }
        helper.succeed();
    }

    /**
     * status 的个人圈地限制 (22.20): 守卫一行带急停开关 (第 7 项)、运行时出错放行的次数、F1/F2 的情况 (开发运行时有 Flan
     * 时 2/2, -PwithoutFlan 那一轮"未安装 Flan"); 外围个人领地的计数: 记录型网关里外围一块、外面一块时为"abydos 1",
     * /district personalclaims 列出那一块; Disabled 网关时显示数不了、personalclaims 回领地对接没有生效。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void statusShowsPersonalClaimGuard(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.seen("Buffer_Z");
            env.recording().driftPlayerClaim(DistrictTestEnv.DIMENSION, new PlotArea(-8, 50, -1, 60),
                    DistrictTestEnv.uuidOf("Buffer_Z"));
            env.recording().driftPlayerClaim(DistrictTestEnv.DIMENSION, new PlotArea(-30, 50, -9, 60),
                    DistrictTestEnv.uuidOf("Far_Z"));
            Console console = new Console(helper);
            List<String> keys = console.expect("district status", 1, KEY + "status.guard.buffer_claims");
            helper.assertTrue(console.lastArgs(KEY + "status.guard.buffer_claims").equals(List.of("abydos 1")),
                    "外围个人领地: abydos 1 (外面那块不算), 实为 " + console.lastArgs(KEY + "status.guard.buffer_claims"));
            List<String> index = console.lastArgs(KEY + "status.guard.index");
            helper.assertTrue(index.size() == 7 && keys.contains(KEY + "status.guard.claims_fail_open")
                            && keys.contains(KEY + (net.minecraftforge.fml.ModList.get().isLoaded("flan")
                            ? "status.guard.claims_ok" : "status.guard.claims_absent")),
                    "守卫一行带个人圈地限制的急停开关, 另有放行次数与 F1/F2 的情况, 实为 " + index + " / " + keys);
            helper.assertTrue(console.lastArgs(KEY + "status.guard.namespaces").equals(List.of("create, ignored_void")),
                    "默认设置的命名空间按字母排: create, ignored_void, 实为 "
                            + console.lastArgs(KEY + "status.guard.namespaces"));

            List<String> listed = console.expect("district personalclaims abydos", 1,
                    KEY + "personalclaims.header");
            helper.assertTrue(console.lastArgs(KEY + "personalclaims.header").equals(List.of("abydos",
                            String.valueOf(DistrictLimits.BUFFER_BLOCKS), "1"))
                            && console.texts().contains("Buffer_Z") && console.texts().contains("(-8, 50) ~ (-1, 60)")
                            && listed.contains(KEY + "personalclaims.buffer") && listed.contains(KEY + "personalclaims.flat"),
                    "清单: 主人 (见过的玩家表)、范围、外围离区边 1 格, 实为 " + console.texts());
            console.expect("district personalclaims nowhere", 0, KEY + "district_unknown");
        }
        try (DistrictTestEnv env = DistrictTestEnv.openWith(new DisabledFlanGateway("测试"))) {
            env.abydos();
            Console console = new Console(helper);
            console.expect("district status", 1, KEY + "status.guard.buffer_claims_unavailable");
            console.expect("district personalclaims abydos", 0, KEY + "flan_unavailable");
        }
        helper.succeed();
    }

    // ================================================================
    // 工具
    // ================================================================

    private static boolean parses(CommandDispatcher<CommandSourceStack> dispatcher, CommandSourceStack source,
                                  String command) {
        ParseResults<CommandSourceStack> parse = dispatcher.parse(command, source);
        return !parse.getReader().canRead() && parse.getContext().getCommand() != null;
    }

    /** /district machines 清单里的坐标写法。 */
    private static String coords(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    /** 以控制台身份执行命令并收集反馈。 */
    private static final class Console {

        private final GameTestHelper helper;
        private final CommandDispatcher<CommandSourceStack> dispatcher;
        private final Capture out = new Capture();
        private final CommandSourceStack source;

        Console(GameTestHelper helper) {
            this.helper = helper;
            MinecraftServer server = helper.getLevel().getServer();
            this.dispatcher = server.getCommands().getDispatcher();
            this.source = server.createCommandSourceStack().withSource(out);
        }

        /** 执行并断言返回值与反馈里出现某个语言键 (key 为 null 时不查键); 返回这条命令产生的全部语言键。 */
        List<String> expect(String command, int expected, @Nullable String key) {
            out.messages.clear();
            Integer result;
            try {
                result = dispatcher.execute(command, source);
            } catch (CommandSyntaxException syntax) {
                result = null;
            }
            List<String> keys = new ArrayList<>();
            out.messages.forEach(message -> collectKeys(message, keys));
            helper.assertTrue(result != null && result == expected,
                    "/" + command + " 应返回 " + expected + ", 实为 " + result + " (反馈 " + keys + ")");
            helper.assertTrue(key == null || keys.contains(key), "/" + command + " 的反馈应含 " + key + ", 实为 " + keys);
            return keys;
        }

        /** 最近一条命令反馈里的全部文字 (字面量与翻译参数)。 */
        List<String> texts() {
            List<String> texts = new ArrayList<>();
            out.messages.forEach(message -> collectTexts(message, texts));
            return texts;
        }

        /** 最近一条命令反馈里某个语言键的参数 (最后出现的那一条)。 */
        List<String> lastArgs(String key) {
            List<String> args = List.of();
            for (Component message : out.messages) {
                if (message.getContents() instanceof TranslatableContents translatable && key.equals(translatable.getKey())) {
                    List<String> found = new ArrayList<>();
                    for (Object arg : translatable.getArgs()) {
                        found.add(arg instanceof Component component ? component.getString() : String.valueOf(arg));
                    }
                    args = found;
                }
            }
            return args;
        }
    }

    private static void collectKeys(Component component, List<String> keys) {
        if (component.getContents() instanceof TranslatableContents translatable) {
            keys.add(translatable.getKey());
            for (Object argument : translatable.getArgs()) {
                if (argument instanceof Component nested) {
                    collectKeys(nested, keys);
                }
            }
        }
        for (Component sibling : component.getSiblings()) {
            collectKeys(sibling, keys);
        }
    }

    private static void collectTexts(Component component, List<String> texts) {
        if (component.getContents() instanceof TranslatableContents translatable) {
            for (Object argument : translatable.getArgs()) {
                if (argument instanceof Component nested) {
                    collectTexts(nested, texts);
                } else {
                    texts.add(String.valueOf(argument));
                }
            }
        } else {
            texts.add(component.getString());
        }
        for (Component sibling : component.getSiblings()) {
            collectTexts(sibling, texts);
        }
    }

    private static final class Capture implements CommandSource {

        private final List<Component> messages = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component message) {
            messages.add(message);
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }
}

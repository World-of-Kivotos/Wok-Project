package com.miningdim.district.flan;

import com.miningdim.core.MiningConstants;
import com.miningdim.district.DistrictTestEnv;
import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.PlotArea;
import com.miningdim.district.core.PlotRecord;
import com.miningdim.district.core.PlotSyncStatus;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static com.miningdim.district.DistrictTestEnv.player;
import static com.miningdim.district.DistrictTestEnv.uuidOf;

/**
 * 对账与按差异写 (设计文档 20.4) 的纯逻辑部分, 用记录型网关: 改回漂移且第二轮零改动、外来与孤儿子领地、错位的认领、
 * 从不碰没有绑定的领地、时间切片、权限表重读、先收后放、推送只写不同的格子、新地块写满全表、建地块的清理。
 * 记录型网关的 drift* 方法绕过守卫直接改模型, 模拟有人用了 /flan 或金锄头。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class DistrictReconcileGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "district_reconcile";

    /** 记录型网关里只有写入会进调用记录 (读不记), 所以"零写入"就是调用记录为空。 */
    private static List<RecordingFlanGateway.FlanCall> writes(RecordingFlanGateway flan) {
        return flan.calls();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void reconcileRevertsDriftAndIsIdempotent(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_O");
            env.resident("abydos", "Friend_F");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.own(plot, "Owner_O");
            env.ctx.plotOwners().addFriend(player("Owner_O"), "abydos", plot, "Friend_F", false);
            RecordingFlanGateway flan = env.recording();
            UUID district = env.districtRecord("abydos").flanClaimId();
            UUID plotClaim = env.plotRecord(plot).flanClaimId();
            UUID stranger = uuidOf("Stranger_S");

            flan.driftGroupPerm(plotClaim, FlanGroupNames.plotFriend(plot), "flan:trample", true);
            flan.driftMember(plotClaim, stranger, FlanGroupNames.plotOwner(plot));
            flan.driftDefault(district, "flan:place", true);
            flan.driftGroupPerm(district, "Visitor", "flan:door", true);
            flan.driftMember(district, stranger, "Visitor");
            flan.driftFakePlayer(district, UUID.randomUUID());
            flan.driftPotion(district, "minecraft:speed", 1);
            env.repo.markNeedsReconcile("abydos");
            flan.clear();

            DistrictReconciler.DistrictProgress first = env.ctx.reconciler().reconcileDistrict("abydos",
                    DistrictReconciler.Mode.AUTO);
            helper.assertTrue(first.reverted() == 5,
                    "父领地 3 项 (清假玩家与药水、删 Visitor 组连同成员、默认 place) + 地块 2 项 (移出陌生人、朋友组 trample),"
                            + " 实为 " + first.reverted() + " " + first.changes());
            helper.assertTrue(!Boolean.TRUE.equals(flan.groupPerm(plotClaim, FlanGroupNames.plotFriend(plot),
                    "flan:trample")) && !flan.membersOf(plotClaim).containsKey(stranger), "地块改回");
            helper.assertTrue(!Boolean.TRUE.equals(flan.defaultPerm(district, "flan:place"))
                            && !flan.groupsOf(district).contains("Visitor") && !flan.membersOf(district).containsKey(stranger)
                            && flan.fakePlayerCount(district) == 0 && flan.potionCount(district) == 0,
                    "父领地改回: 多余的组连同成员删掉, 假玩家与药水清空");
            helper.assertTrue(env.plotRecord(plot).syncStatus() == PlotSyncStatus.SYNCED
                            && !env.districtRecord("abydos").needsReconcile() && first.unfixable().isEmpty(),
                    "地块 synced, 整轮对完且没有修不了的差异时清掉对账标记, 实为 " + first.unfixable());
            helper.assertTrue(flan.callsOf("backupNow").isEmpty(), "自动对账没删子领地, 不强制备份");

            flan.clear();
            DistrictReconciler.DistrictProgress second = env.ctx.reconciler().reconcileDistrict("abydos",
                    DistrictReconciler.Mode.AUTO);
            helper.assertTrue(second.reverted() == 0 && writes(flan).isEmpty(),
                    "再跑一轮: 零改动, 一次写入都没有 (领地不被标脏), 实为 " + writes(flan));

            flan.driftGroupPerm(plotClaim, FlanGroupNames.plotResident(plot), "flan:break", true);
            DistrictReconciler.DistrictProgress dry = env.ctx.reconciler().reconcileDistrict("abydos",
                    DistrictReconciler.Mode.DRY_RUN);
            helper.assertTrue(dry.changes().size() == 1 && dry.changes().get(0).contains("flan:break")
                            && Boolean.TRUE.equals(flan.groupPerm(plotClaim, FlanGroupNames.plotResident(plot),
                            "flan:break")),
                    "预演只列出差异, 一个字都不写, 实为 " + dry.changes());
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void reconcileHandlesForeignOrphanAndMisplacedSubclaims(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            String kept = env.plot("abydos", 10, 10, 25, 25);
            String deleted = env.plot("abydos", 40, 10, 55, 25);
            RecordingFlanGateway flan = env.recording();
            UUID district = env.districtRecord("abydos").flanClaimId();
            UUID foreign = flan.driftSubclaim(district, new PlotArea(100, 100, 110, 110));
            UUID orphan = env.plotRecord(deleted).flanClaimId();
            flan.failNext("deletePlotClaim", "区块未加载");
            env.ctx.layout().delete(env.admin, "abydos", deleted);
            helper.assertTrue(flan.exists(orphan), "前提: 删地块时推送失败, 子领地成了孤儿 (墓碑不存领地 id)");
            UUID keptClaim = env.plotRecord(kept).flanClaimId();
            env.repo.setPlotClaimId(kept, UUID.randomUUID());
            int createsBefore = flan.callsOf("createPlotClaim").size();

            DistrictReconciler.DistrictProgress auto = env.ctx.reconciler().reconcileDistrict("abydos",
                    DistrictReconciler.Mode.AUTO);
            helper.assertTrue(!flan.exists(orphan), "有墓碑的本模块子领地 (孤儿): 自动对账删掉");
            helper.assertTrue(flan.callsOf("backupNow").stream().anyMatch(call -> call.hasArg("orphan")),
                    "删孤儿之前强制备份");
            helper.assertTrue(flan.exists(foreign) && auto.reportedSubclaims().size() == 1,
                    "外来子领地: 自动对账只报告不删, 实为 " + auto.reportedSubclaims());
            helper.assertTrue(keptClaim.equals(env.plotRecord(kept).flanClaimId())
                            && flan.callsOf("createPlotClaim").size() == createsBefore,
                    "错位的本区地块 (库里的领地 id 在 Flan 里找不到): 认领带着它组键的子领地, 不新建");

            DistrictReconciler.DistrictProgress explicit = env.ctx.reconciler().reconcileDistrict("abydos",
                    DistrictReconciler.Mode.EXPLICIT);
            helper.assertTrue(!flan.exists(foreign) && explicit.removedSubclaims().size() == 1
                            && flan.exists(keptClaim),
                    "resync 连外来子领地也删, 本区地块不动, 实为 " + explicit.removedSubclaims());
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void reconcileNeverTouchesUnboundClaims(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            RecordingFlanGateway flan = env.recording();
            UUID otherAdmin = flan.driftAdminClaim(DistrictTestEnv.DIMENSION, new PlotArea(5000, 5000, 5100, 5100),
                    "别的管理员领地");
            UUID playerClaim = flan.driftPlayerClaim(DistrictTestEnv.DIMENSION, new PlotArea(6000, 6000, 6100, 6100));
            env.abydos();
            env.millennium();
            env.plot("millennium", 1010, 10, 1025, 25);
            UUID archived = env.districtRecord("millennium").flanClaimId();
            env.ctx.admin().unbind(env.admin, "millennium");
            flan.driftGroupPerm(archived, FlanGroupNames.districtResident("millennium"), "flan:break", true);
            List<Object> before = snapshot(flan, List.of(otherAdmin, playerClaim, archived));

            env.plot("abydos", 10, 10, 25, 25);
            env.ctx.reconciler().reconcileDistrict("abydos", DistrictReconciler.Mode.AUTO);
            env.ctx.reconciler().reconcileDistrict("abydos", DistrictReconciler.Mode.EXPLICIT);
            new ReconcileScheduler(env.clock::get, System::nanoTime).runFullRoundNow(env.ctx.reconciler(), env.repo,
                    flan);
            helper.assertTrue(before.equals(snapshot(flan, List.of(otherAdmin, playerClaim, archived))),
                    "无关的管理员领地、玩家领地、已解绑区的父领地 (连同它的子领地) 前后一模一样");
        }
        helper.succeed();
    }

    private static List<Object> snapshot(RecordingFlanGateway flan, List<UUID> claims) {
        List<Object> state = new ArrayList<>();
        for (UUID claim : claims) {
            ClaimHandle handle = new ClaimHandle(DistrictTestEnv.DIMENSION, claim, null);
            state.add(flan.readPermissions(handle));
            state.add(flan.readMembers(handle));
            state.add(flan.inspectClaim(handle));
            state.add(flan.childrenOf(claim));
        }
        return state;
    }

    /** 时间切片: 每 tick 限时 (这里的假时钟每读一次走 3 毫秒, 一 tick 只够一个条目), 游标记住做到了哪里。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void schedulerSlicesARoundAcrossTicks(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.plot("abydos", 10, 10, 25, 25);
            env.plot("abydos", 40, 10, 55, 25);
            AtomicLong nanos = new AtomicLong();
            ReconcileScheduler scheduler = new ReconcileScheduler(env.clock::get, () -> nanos.addAndGet(3_000_000L));
            DistrictReconciler reconciler = env.ctx.reconciler();
            scheduler.tick(1, reconciler, env.repo, env.gateway);
            helper.assertTrue(!scheduler.running() && scheduler.lastRound() == null, "没到点、没被请求: 不起一轮");
            scheduler.request();
            scheduler.tick(2, reconciler, env.repo, env.gateway);
            helper.assertTrue(scheduler.running(), "第一 tick 只做了父领地条目");
            scheduler.tick(3, reconciler, env.repo, env.gateway);
            helper.assertTrue(scheduler.running(), "第二 tick 做了第一块地");
            scheduler.tick(4, reconciler, env.repo, env.gateway);
            ReconcileScheduler.RoundStats round = scheduler.lastRound();
            helper.assertTrue(!scheduler.running() && round != null && round.slices() == 3 && round.districts() == 1
                            && !round.aborted(),
                    "三个条目切成三片, 实为 " + round);
            scheduler.tick(5, reconciler, env.repo, env.gateway);
            helper.assertTrue(!scheduler.running(), "一轮结束后下一轮等 6000 tick");
            scheduler.tick(4 + ReconcileScheduler.INTERVAL_TICKS, reconciler, env.repo, env.gateway);
            helper.assertTrue(scheduler.running(), "到点起下一轮");
        }
        helper.succeed();
    }

    /** 一轮之中写入连续失败 20 次就中止本轮 (Flan 整体坏掉时不刷屏)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void schedulerAbortsAfterConsecutiveFailures(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            for (int i = 0; i < 24; i++) {
                int x = 5 + (i % 10) * 18;
                int z = 5 + (i / 10) * 18;
                env.plot("abydos", x, z, x + 10, z + 10);
            }
            for (PlotRecord plot : env.repo.plotsOf("abydos")) {
                env.recording().driftGroupPerm(plot.flanClaimId(), FlanGroupNames.plotOwner(plot.plotId()),
                        "flan:break", false);
            }
            env.recording().failWhen(call -> call.op().equals("setGroupPermission"), "区块未加载");
            ReconcileScheduler.RoundStats round = new ReconcileScheduler(env.clock::get, System::nanoTime)
                    .runFullRoundNow(env.ctx.reconciler(), env.repo, env.gateway);
            helper.assertTrue(round.aborted() && round.failures() == ReconcileScheduler.MAX_CONSECUTIVE_FAILURES,
                    "连续 20 次写失败就中止, 实为 " + round);
        }
        helper.succeed();
    }

    /**
     * 一个条目抛异常 (Flan 或本模块的 bug) 不能让整轮每 tick 抛一次 (20.4): 记成失败, 接着做后面的条目, 这一轮照常结束;
     * 数据库出错 (库锁着或坏了) 则整轮中止, 注明原因, 等下一次定时。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void schedulerSurvivesThrowingEntries(GameTestHelper helper) {
        RecordingFlanGateway flan = new RecordingFlanGateway();
        boolean[] armed = {false};
        FlanGateway throwing = (FlanGateway) Proxy.newProxyInstance(FlanGateway.class.getClassLoader(),
                new Class<?>[]{FlanGateway.class}, (proxy, method, args) -> {
                    if (armed[0] && method.getName().equals("inspectClaim") && args[0] instanceof ClaimHandle handle
                            && handle.parentId() != null) {
                        armed[0] = false;
                        throw new IllegalStateException("injected: a Flan bug while reading one plot");
                    }
                    try {
                        return method.invoke(flan, args);
                    } catch (InvocationTargetException wrapped) {
                        throw wrapped.getCause();
                    }
                });
        try (DistrictTestEnv env = DistrictTestEnv.openWith(throwing)) {
            env.abydos();
            String first = env.plot("abydos", 10, 10, 25, 25);
            String second = env.plot("abydos", 40, 10, 55, 25);
            for (String plot : List.of(first, second)) {
                flan.driftGroupPerm(env.plotRecord(plot).flanClaimId(), FlanGroupNames.plotResident(plot), "flan:break",
                        true);
            }
            armed[0] = true;
            ReconcileScheduler scheduler = new ReconcileScheduler(env.clock::get, System::nanoTime);
            ReconcileScheduler.RoundStats round = scheduler.runFullRoundNow(env.ctx.reconciler(), env.repo, throwing);
            long reverted = List.of(first, second).stream().filter(plot -> !Boolean.TRUE.equals(flan.groupPerm(
                    env.plotRecord(plot).flanClaimId(), FlanGroupNames.plotResident(plot), "flan:break"))).count();
            helper.assertTrue(!armed[0] && round != null && !round.aborted() && round.failures() == 1 && reverted == 1,
                    "抛异常的那块记成失败, 另一块照常改回, 这一轮照常结束, 实为 " + round + " / 改回 " + reverted);

            env.repo.setPlotSync(first, PlotSyncStatus.FAILED, "旧的失败");
            env.exec("CREATE TEMP TRIGGER fail_plot_sync BEFORE UPDATE OF sync_status ON district_plot "
                    + "BEGIN SELECT RAISE(ABORT, 'injected'); END");
            ReconcileScheduler.RoundStats locked = scheduler.runFullRoundNow(env.ctx.reconciler(), env.repo, throwing);
            env.exec("DROP TRIGGER fail_plot_sync");
            helper.assertTrue(locked != null && locked.aborted()
                            && ReconcileScheduler.ABORT_DATABASE.equals(locked.abortReason()) && !scheduler.running(),
                    "数据库出错: 整轮中止并注明原因, 实为 " + locked);
        }
        helper.succeed();
    }

    /**
     * 父领地的高度与放行清单 (20.3): 2D 但底偏高的父领地, 自动对账用 extendDistrictClaimToBottom 补到世界底; 父领地上的
     * 放行清单条目 (Flan 在顶层领地上先查它们, 命中即放行) 随假玩家与药水一起清空。3D 的父领地补不了, 只报告。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void reconcileDeepensShallowParentsAndClearsAllowLists(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.plot("abydos", 10, 10, 25, 25);
            RecordingFlanGateway flan = env.recording();
            UUID district = env.districtRecord("abydos").flanClaimId();
            flan.driftShape(district, 54, true);
            flan.driftAllowList(district, 3);
            flan.clear();
            DistrictReconciler.DistrictProgress dry = env.ctx.reconciler().reconcileDistrict("abydos",
                    DistrictReconciler.Mode.DRY_RUN);
            helper.assertTrue(writes(flan).isEmpty() && dry.changes().stream().anyMatch(c -> c.contains("bottom y 54"))
                            && dry.changes().stream().anyMatch(c -> c.contains("3 allow-list entries")),
                    "预演列出两项, 一个字都不写, 实为 " + dry.changes());
            DistrictReconciler.DistrictProgress round = env.ctx.reconciler().reconcileDistrict("abydos",
                    DistrictReconciler.Mode.AUTO);
            helper.assertTrue(flan.minYOf(district) == RecordingFlanGateway.WORLD_MIN_Y
                            && flan.allowListCount(district) == 0 && round.reverted() == 2 && round.unfixable().isEmpty(),
                    "自动对账把底补到世界底、清空放行清单, 实为 " + round.changes() + " / " + round.unfixable());

            flan.driftShape(district, 54, false);
            DistrictReconciler.DistrictProgress cube = env.ctx.reconciler().reconcileDistrict("abydos",
                    DistrictReconciler.Mode.AUTO);
            helper.assertTrue(flan.minYOf(district) == 54 && cube.unfixable().stream().anyMatch(u -> u.contains("3D")),
                    "3D 的父领地补不了, 只报告, 实为 " + cube.unfixable());
        }
        helper.succeed();
    }

    /** 权限表重读 (/reload): 版本加一、全表重建; id 集合变了就安排一轮对账, 新的非全局权限写进每一块地。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void permissionTableReloadRebuildsPolicy(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            String plot = env.plot("abydos", 10, 10, 25, 25);
            RecordingFlanGateway flan = env.recording();
            ReconcileScheduler scheduler = new ReconcileScheduler(env.clock::get, System::nanoTime);
            scheduler.checkPermissionTable(flan);
            helper.assertTrue(!scheduler.running(), "第一次看到权限表只记未知 id, 不算变了");
            long version = flan.permissionTableVersion();
            List<KnownPermission> table = new ArrayList<>(FlanPermissions.BUILTIN);
            table.add(new KnownPermission("othermod:new_local", false, false, false));
            flan.replacePermissionTableForTest(table);
            helper.assertTrue(flan.permissionTableVersion() == version + 1
                            && env.ctx.flanSync().policy().table().size() == 67,
                    "版本加一, 全表按新表重建");
            scheduler.checkPermissionTable(flan);
            scheduler.tick(1, env.ctx.reconciler(), env.repo, flan);
            helper.assertTrue(scheduler.lastRound() != null || scheduler.running(), "id 集合变了: 安排一轮完整对账");
            scheduler.runFullRoundNow(env.ctx.reconciler(), env.repo, flan);
            UUID plotClaim = env.plotRecord(plot).flanClaimId();
            helper.assertTrue(Boolean.TRUE.equals(flan.groupPerm(plotClaim, FlanGroupNames.plotOwner(plot),
                            "othermod:new_local"))
                            && Boolean.FALSE.equals(flan.groupPerm(plotClaim, FlanGroupNames.plotResident(plot),
                            "othermod:new_local")),
                    "新的非全局权限写进每一块地: 户主组为真, 其余为假");

            List<KnownPermission> broken = new ArrayList<>();
            FlanPermissions.BUILTIN.stream().filter(permission -> !permission.id().equals("flan:break"))
                    .forEach(broken::add);
            flan.replacePermissionTableForTest(broken);
            env.resident("abydos", "Owner_O");
            env.own(plot, "Owner_O");
            helper.assertTrue(env.plotRecord(plot).syncStatus() == PlotSyncStatus.FAILED
                            && String.valueOf(env.plotRecord(plot).syncError()).contains("flan:break"),
                    "权限表缺了必需的 id: 整批不写, 报出缺的 id, 实为 " + env.plotRecord(plot).syncError());
        }
        helper.succeed();
    }

    /**
     * 先收后放 (20.2 第 6 条): 在"写第一个真值"处注入失败, 停下来的地块没有任何一格比期望宽 —— 该写假的都已写假,
     * 该写真的一个都还没写。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void writesAreOrderedRestrictiveFirst(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_O");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.own(plot, "Owner_O");
            RecordingFlanGateway flan = env.recording();
            flan.failWhen(call -> call.op().equals("setGroupPermission") || call.op().equals("setDefaultPermission"),
                    "区块未加载");
            env.ctx.plotOwners().setPermission(player("Owner_O"), "abydos", plot, "door", "outsider", false);
            env.ctx.plotOwners().setPermission(player("Owner_O"), "abydos", plot, "container", "resident", true);
            flan.clearFailures();
            flan.failWhen(call -> (call.op().equals("setGroupPermission") || call.op().equals("setDefaultPermission"))
                    && call.hasArg("TRUE"), "区块未加载");
            env.ctx.flanSync().writePlotState(plot);
            PlotRecord record = env.plotRecord(plot);
            helper.assertTrue(record.syncStatus() == PlotSyncStatus.FAILED, "写真值失败: 地块标 failed");
            UUID claim = record.flanClaimId();
            helper.assertTrue(Boolean.FALSE.equals(flan.defaultPerm(claim, "flan:door")),
                    "该收的已经收了: 外人开门写成了假");
            helper.assertTrue(!Boolean.TRUE.equals(flan.groupPerm(claim, FlanGroupNames.plotResident(plot),
                    "flan:open_container")), "该放的还没放: 其他住户开箱子仍不是真");
            DistrictRecord district = env.districtRecord("abydos");
            PlotDesiredState desired = env.ctx.flanSync().desiredState(district, record, null);
            for (Map.Entry<String, Map<String, PermValue>> group : desired.groupPerms().entrySet()) {
                for (Map.Entry<String, PermValue> perm : group.getValue().entrySet()) {
                    helper.assertTrue(perm.getValue() != PermValue.FALSE
                                    || !Boolean.TRUE.equals(flan.groupPerm(claim, group.getKey(), perm.getKey())),
                            "没有任何一格比期望宽: " + group.getKey() + " " + perm.getKey());
                }
            }
            flan.clearFailures();
            env.ctx.flanSync().writePlotState(plot);
            helper.assertTrue(env.plotRecord(plot).syncStatus() == PlotSyncStatus.SYNCED
                            && Boolean.TRUE.equals(flan.groupPerm(claim, FlanGroupNames.plotResident(plot),
                            "flan:open_container")), "下一次写入补齐");
        }
        helper.succeed();
    }

    /** 推送也按差异写 (20.4): 同一个状态推两遍, 第二遍 Flan 一次写入都没有。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pushesWriteOnlyDifferences(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Owner_O");
            String plot = env.plot("abydos", 10, 10, 25, 25);
            env.own(plot, "Owner_O");
            RecordingFlanGateway flan = env.recording();
            flan.clear();
            env.ctx.flanSync().writePlotState(plot);
            env.ctx.flanSync().writeDistrictState("abydos");
            env.ctx.flanSync().syncResidentMembership("abydos", uuidOf("Owner_O"));
            helper.assertTrue(writes(flan).isEmpty() && env.plotRecord(plot).syncStatus() == PlotSyncStatus.SYNCED,
                    "库不变时再推一遍: 零写入, 实为 " + writes(flan));
            env.ctx.plotOwners().setPermission(player("Owner_O"), "abydos", plot, "door", "outsider", false);
            List<RecordingFlanGateway.FlanCall> changed = writes(flan);
            helper.assertTrue(changed.size() == 1 && changed.get(0).op().equals("setDefaultPermission")
                            && changed.get(0).hasArg("flan:door"),
                    "改一格只写那一格, 实为 " + changed);
        }
        helper.succeed();
    }

    /**
     * 新地块写满全表 (20.3): 三个组与默认对全部非全局权限都有显式值; 全局权限在地块上一律没有键 (UNSET, 跟随父领地),
     * 包括新领地构造时预填为真的 lock_items、snow_golem。建地块时的清理: 继承来的组、成员与药水都删掉。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void newPlotIsScrubbedAndWritesTheWholeTable(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.resident("abydos", "Resident_R");
            RecordingFlanGateway flan = env.recording();
            UUID district = env.districtRecord("abydos").flanClaimId();
            flan.driftPotion(district, "minecraft:speed", 1);
            String plot = env.plot("abydos", 10, 10, 25, 25);
            UUID claim = env.plotRecord(plot).flanClaimId();
            helper.assertTrue(flan.groupsOf(claim).equals(Set.of(FlanGroupNames.plotOwner(plot),
                            FlanGroupNames.plotFriend(plot), FlanGroupNames.plotResident(plot))),
                    "地块上只有自己的三个 p_ 组, 没有继承来的 d_ 组, 实为 " + flan.groupsOf(claim));
            helper.assertTrue(flan.membersOf(claim).equals(Map.of(uuidOf("Resident_R"), FlanGroupNames.plotResident(plot)))
                            && flan.potionCount(claim) == 0,
                    "成员只有期望的人, 药水为空");
            for (KnownPermission permission : FlanPermissions.BUILTIN) {
                if (permission.global()) {
                    helper.assertTrue(flan.defaultPerm(claim, permission.id()) == null,
                            "全局权限在地块上没有键: " + permission.id());
                    continue;
                }
                helper.assertTrue(flan.defaultPerm(claim, permission.id()) != null,
                        "地块默认显式写了 " + permission.id());
                for (String group : flan.groupsOf(claim)) {
                    helper.assertTrue(flan.groupPerm(claim, group, permission.id()) != null,
                            group + " 显式写了 " + permission.id());
                }
            }
        }
        helper.succeed();
    }
}

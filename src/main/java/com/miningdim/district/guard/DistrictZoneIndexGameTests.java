package com.miningdim.district.guard;

import com.miningdim.core.MiningConstants;
import com.miningdim.district.DistrictSystem;
import com.miningdim.district.DistrictTestEnv;
import com.miningdim.district.core.DistrictBounds;
import com.miningdim.district.core.PlotArea;
import com.miningdim.district.flan.LogThrottle;
import com.miningdim.district.service.PlotLayoutService;
import com.miningdim.district.store.DistrictRepository;
import com.miningdim.district.store.DistrictStoreException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按坐标查区与地块的空间索引 (设计文档 22.3、22.14): 快照与库一致、已解绑的区为 0、外围 8 格的口径、提交之后才重建、
 * 读库失败保留旧快照、定时节拍兜底、框判定与逐格判定一致。全部同步, 不碰方块 (DistrictTestEnv 的 context 不装进门面)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class DistrictZoneIndexGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "district_zone_index";

    private DistrictZoneIndexGameTests() {
    }

    /**
     * 生产的接线 (22.3、22.19): {@link DistrictWorldGuards#install} 装上 context 的索引之后, 门面跟着索引走 —— 开服之后才
     * 建的区、划的地块、解绑, 提交之后守卫都看得到。其余守卫用例都走 GuardTestZones 的合成快照, 看不住这一段: 丢了 install、
     * 丢了 onRebuilt 的挂接, 或者 installedIndex 的比较写错, 生产上守卫就一直停在开服那一刻的 (空) 快照上。
     *
     * <p>同步用例, 单独一个 batch: 装上到复位都在这一次调用里, 别的用例看不到这个全局的门面。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = "district_guard_install")
    public static void productionInstallFollowsTheIndex(GameTestHelper helper) {
        Level level = helper.getLevel();
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            DistrictWorldGuards.install(env.ctx);
            helper.assertTrue(DistrictWorldGuards.installedIndex() == env.ctx.zones()
                            && DistrictWorldGuards.view().installed()
                            && DistrictWorldGuards.view().zones().zoneAt(level, 100, 100) == 0
                            && !DistrictWorldGuards.view().banActive(),
                    "装上时还没有区: 门面装着这个 context 的索引, 什么都不拦");

            env.abydos();
            helper.assertTrue(DistrictWorldGuards.view().zones().zoneAt(level, 100, 100) != 0
                            && DistrictWorldGuards.view().banActive() && DistrictWorldGuards.view().crossPlotActive()
                            && DistrictWorldGuards.view().machineryActive(),
                    "开服之后建的区: 提交之后门面就看得到");

            env.plot("abydos", 10, 10, 20, 20);
            helper.assertTrue(!DistrictWorldGuards.mayDispense(level, new BlockPos(20, 64, 15), Direction.EAST)
                            && DistrictWorldGuards.mayDispense(level, new BlockPos(15, 64, 15), Direction.EAST),
                    "开服之后划的地块: 发射器不能朝公共区域发射, 地块里照常");
            helper.assertTrue(!DistrictWorldGuards.pistonMayMove(level, new BlockPos(19, 64, 15), Direction.EAST,
                            true, Direction.EAST, List.of(new BlockPos(20, 64, 15)), List.of())
                            && DistrictWorldGuards.pistonMayMove(level, new BlockPos(12, 64, 15), Direction.EAST, true,
                            Direction.EAST, List.of(new BlockPos(13, 64, 15)), List.of()),
                    "活塞推不过 x = 20|21 的地块边界, 地块里照常");

            env.ctx.admin().unbind(env.admin, "abydos");
            helper.assertTrue(DistrictWorldGuards.view().zones().zoneAt(level, 100, 100) == 0
                            && DistrictWorldGuards.mayDispense(level, new BlockPos(20, 64, 15), Direction.EAST),
                    "解绑之后门面跟着放行");
        } finally {
            DistrictWorldGuards.reset();
        }
        helper.assertTrue(DistrictWorldGuards.installedIndex() == null && !DistrictWorldGuards.view().installed(),
                "复位之后回到 OFF");
        helper.succeed();
    }

    /** 两个区各三块地 (有户主、空置、冻结各一), 另有一个已解绑区: 区域号两两不同, 两端都含; 已解绑区的格子是 0。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void snapshotMatchesRepositoryAndSkipsUnbound(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            env.millennium();
            env.district("gehenna", 2000, 0, 2199, 199);
            env.plot("gehenna", 2010, 10, 2020, 20);
            env.ctx.admin().unbind(env.admin, "gehenna");
            String[] abydos = threePlots(env, "abydos", 0, "Owner_Aa", "Frozen_Aa");
            String[] millennium = threePlots(env, "millennium", 1000, "Owner_Mm", "Frozen_Mm");
            for (String[] plots : List.of(abydos, millennium)) {
                helper.assertTrue(env.plotRecord(plots[0]).owned() && env.plotRecord(plots[1]).vacant()
                        && env.plotRecord(plots[2]).frozen(), "前提: 有户主、空置、冻结各一块");
            }
            Level level = helper.getLevel();
            DistrictZoneSnapshot zones = env.ctx.zones().current();
            List<Integer> distinct = List.of(zones.zoneAt(level, 100, 100), zones.zoneAt(level, 15, 15),
                    zones.zoneAt(level, 35, 15), zones.zoneAt(level, 55, 15), zones.zoneAt(level, 1100, 100),
                    zones.zoneAt(level, 1015, 15), zones.zoneAt(level, 1035, 15), zones.zoneAt(level, 1055, 15));
            helper.assertTrue(!distinct.contains(0) && new HashSet<>(distinct).size() == 8,
                    "两个区的公共区域与六块地的区域号两两不同且都不为 0, 实为 " + distinct);
            int plot = zones.zoneAt(level, 15, 15);
            int publicArea = zones.zoneAt(level, 100, 100);
            helper.assertTrue(zones.zoneAt(level, 10, 10) == plot && zones.zoneAt(level, 20, 20) == plot
                            && zones.zoneAt(level, 9, 15) == publicArea && zones.zoneAt(level, 21, 15) == publicArea
                            && zones.zoneAt(level, 15, 9) == publicArea && zones.zoneAt(level, 15, 21) == publicArea,
                    "地块两端都含, 紧挨着的一格是公共区域");
            helper.assertTrue(zones.zoneAt(level, 0, 0) == publicArea && zones.zoneAt(level, 199, 199) == publicArea
                            && zones.zoneAt(level, -1, 0) == 0 && zones.zoneAt(level, 0, -1) == 0
                            && zones.zoneAt(level, 200, 199) == 0 && zones.zoneAt(level, 199, 200) == 0,
                    "区的两端都含, 外面一格是 0");
            helper.assertTrue(zones.zoneAt(level, 2015, 15) == 0 && zones.zoneAt(level, 2100, 100) == 0
                            && zones.hit(level, 2100, 100) == null, "已解绑的区 (Flan 领地还在) 是 0");
            helper.assertTrue(zones.districtCount() == 2 && zones.plotCount() == 6,
                    "快照只有两个在用区与六块地, 实为 " + zones.districtCount() + " / " + zones.plotCount());
        }
        helper.succeed();
    }

    private static String[] threePlots(DistrictTestEnv env, String districtId, int offset, String owner,
                                       String frozen) {
        String owned = env.plot(districtId, offset + 10, 10, offset + 20, 20);
        String vacant = env.plot(districtId, offset + 30, 10, offset + 40, 20);
        String frozenPlot = env.plot(districtId, offset + 50, 10, offset + 60, 20);
        env.resident(districtId, owner);
        env.own(owned, owner);
        env.resident(districtId, frozen);
        env.own(frozenPlot, frozen);
        env.ctx.residents().removeResident(env.admin, districtId, frozen, "inactive", "不上线");
        return new String[]{owned, vacant, frozenPlot};
    }

    /** 外围 8 格: 方形外扩、两端都含 (minX − 8 在, minX − 9 不在), 四角不削圆, 只在自管区所在的维度。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bufferIsEightBlocksSquareSameDimension(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            Level level = helper.getLevel();
            DistrictZoneSnapshot zones = env.ctx.zones().current();
            helper.assertTrue(zones.inBanArea(level, -8, 50) && !zones.inBanArea(level, -9, 50)
                            && zones.inBanArea(level, 207, 50) && !zones.inBanArea(level, 208, 50)
                            && zones.inBanArea(level, 50, -8) && !zones.inBanArea(level, 50, -9),
                    "四边各外扩 8 格, 两端都含");
            helper.assertTrue(zones.inBanArea(level, -8, -8) && zones.inBanArea(level, 207, 207)
                            && !zones.inBanArea(level, -9, -8) && !zones.inBanArea(level, -8, -9),
                    "方形外扩, 四角不削圆");
            helper.assertTrue(zones.inBanArea(level, 100, 100) && zones.zoneAt(level, -8, 50) == 0,
                    "禁放区包含自管区本身; 外围不是自管区");
            helper.assertTrue(!zones.inBanArea(Level.NETHER, -8, 50) && !zones.inBanArea(Level.NETHER, 100, 100)
                            && zones.zoneAt(Level.NETHER, 100, 100) == 0,
                    "别的维度的同一坐标不受影响");
            helper.assertTrue(!zones.touchesBanArea(level, -20, -20, -9, -9)
                            && zones.touchesBanArea(level, -20, -20, -8, -8)
                            && !zones.touchesDistrict(level, -20, -20, -1, -1)
                            && zones.touchesDistrict(level, -20, -20, 0, 0),
                    "框判定的边界与逐格一致");
        }
        helper.succeed();
    }

    /**
     * 经服务划、改、删地块, 改区范围、解绑: 提交后快照跟着变; TEMP 触发器让划地块回滚、事务里抛异常回滚: 快照不变;
     * 事务进行中查到的仍是旧快照。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void layoutWritesRebuildOnlyAfterCommit(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            Level level = helper.getLevel();
            helper.assertTrue(env.ctx.zones().current().zoneAt(level, 100, 100) == 0, "前提: 还没有区");
            env.abydos();
            int publicArea = zone(env, level, 100, 100);
            helper.assertTrue(publicArea != 0, "建区提交后快照里有这个区");

            String plot = env.plot("abydos", 10, 10, 20, 20);
            int plotZone = zone(env, level, 15, 15);
            helper.assertTrue(plotZone != 0 && plotZone != publicArea, "划地块提交后快照里有这块地");

            env.ctx.layout().resize(env.admin, "abydos", plot,
                    PlotLayoutService.AreaInput.of(new PlotArea(10, 10, 30, 30)));
            helper.assertTrue(zone(env, level, 25, 25) == plotZone, "改范围提交后快照跟着变");

            env.repo.inTransaction(() -> {
                env.repo.setPlotBounds(plot, new PlotArea(10, 10, 40, 40));
                helper.assertTrue(zone(env, level, 35, 35) == publicArea, "事务进行中查到的仍是旧快照");
                return null;
            });
            helper.assertTrue(zone(env, level, 35, 35) == plotZone, "提交之后才换成新快照");

            env.exec("CREATE TEMP TRIGGER gt_zone_fail_plot BEFORE INSERT ON district_plot "
                    + "BEGIN SELECT RAISE(ABORT, 'gt zone index'); END");
            try {
                env.ctx.layout().create(env.admin, "abydos",
                        PlotLayoutService.AreaInput.of(new PlotArea(100, 100, 110, 110)));
                helper.fail("触发器应让划地块失败");
            } catch (RuntimeException expected) {
                // 事务回滚
            } finally {
                env.exec("DROP TRIGGER IF EXISTS gt_zone_fail_plot");
            }
            helper.assertTrue(zone(env, level, 105, 105) == publicArea, "回滚了的划地块不进快照");

            try {
                env.repo.inTransaction(() -> {
                    env.repo.deletePlot(plot);
                    throw new IllegalStateException("gt rollback");
                });
            } catch (IllegalStateException expected) {
                // 事务回滚
            }
            helper.assertTrue(zone(env, level, 15, 15) == plotZone, "回滚了的删地块不改快照");

            env.ctx.layout().delete(env.admin, "abydos", plot);
            helper.assertTrue(zone(env, level, 15, 15) == publicArea, "删地块提交后回到公共区域");

            // 有父领地时范围以 Flan 为准: 在记录型网关里改父领地的范围, 再经 /district bounds sync 的服务方法写库 (20.6)。
            java.util.UUID claim = env.districtRecord("abydos").flanClaimId();
            helper.assertTrue(claim != null, "前提: 建区时建了父领地");
            env.recording().driftResize(claim, new PlotArea(0, 0, 299, 199));
            helper.assertTrue(env.ctx.admin().syncBoundsFromClaim(env.admin, "abydos").ok(), "前提: 改区范围成功");
            helper.assertTrue(zone(env, level, 250, 100) != 0 && env.ctx.zones().current().inBanArea(level, 307, 100),
                    "改区范围提交后快照跟着变 (外围 8 格也跟着走)");

            env.ctx.admin().unbind(env.admin, "abydos");
            helper.assertTrue(zone(env, level, 100, 100) == 0 && !env.ctx.zones().current().inBanArea(level, -8, 50),
                    "解绑提交后区与外围都没了");
        }
        helper.succeed();
    }

    private static int zone(DistrictTestEnv env, Level level, int x, int z) {
        return env.ctx.zones().current().zoneAt(level, x, z);
    }

    /** 重建时仓储抛 DistrictStoreException: 旧快照照用, 状态报"索引过期"; 下一次成功后标记清掉。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void storeFailureKeepsThePreviousSnapshot(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            AtomicBoolean fail = new AtomicBoolean();
            DistrictRepository flaky = (DistrictRepository) Proxy.newProxyInstance(
                    DistrictRepository.class.getClassLoader(), new Class<?>[]{DistrictRepository.class},
                    (proxy, method, args) -> {
                        if (fail.get() && "liveDistricts".equals(method.getName())) {
                            throw new DistrictStoreException("gt: simulated read failure");
                        }
                        try {
                            return method.invoke(env.repo, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            DistrictZoneIndex index = new DistrictZoneIndex(flaky, new LogThrottle(env.clock::get));
            Level level = helper.getLevel();
            helper.assertTrue(index.rebuild() && !index.stale() && index.current().zoneAt(level, 100, 100) != 0,
                    "前提: 第一次重建成功");
            DistrictZoneSnapshot before = index.current();
            fail.set(true);
            helper.assertTrue(!index.rebuild() && index.stale() && index.current() == before,
                    "读库失败: 旧快照照用, 置过期标记");
            fail.set(false);
            helper.assertTrue(index.rebuild() && !index.stale(), "下一次成功后标记清掉");
        }
        helper.succeed();
    }

    /** 绕过仓储写方法直接改库: 快照不变; 下一次定时节拍之后对上 (兜底)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sweepRebuildsAsASafetyNet(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            Level level = helper.getLevel();
            env.exec("UPDATE district SET max_x = 299 WHERE district_id = 'abydos'");
            helper.assertTrue(zone(env, level, 250, 100) == 0, "直接改库不经仓储, 快照还没变");
            DistrictSystem.sweep("gametest");
            helper.assertTrue(zone(env, level, 250, 100) != 0, "定时节拍重建之后快照对上");
        }
        helper.succeed();
    }

    /** 框判定与逐格判定一致; hit 给出正确的区 id、地块 id 与标签。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void boxAndHitAgreeWithZoneAt(GameTestHelper helper) {
        try (DistrictTestEnv env = DistrictTestEnv.open()) {
            env.abydos();
            String plot = env.plot("abydos", 10, 10, 20, 20);
            Level level = helper.getLevel();
            DistrictZoneSnapshot zones = env.ctx.zones().current();
            DistrictZoneSnapshot.Hit hit = zones.hit(level, 15, 15);
            helper.assertTrue(hit != null && "abydos".equals(hit.districtId()) && plot.equals(hit.plotId())
                            && env.plotRecord(plot).code().equals(hit.plotLabel())
                            && env.districtRecord("abydos").displayName().equals(hit.districtName()),
                    "hit 给出区 id、地块 id 与标签, 实为 " + hit);
            DistrictZoneSnapshot.Hit open = zones.hit(level, 100, 100);
            helper.assertTrue(open != null && open.plotId() == null && open.plotLabel() == null,
                    "公共区域没有地块");
            helper.assertTrue(zones.hit(level, -1, 0) == null, "区外为 null");
            List<int[]> boxes = List.of(new int[]{-5, -5, -1, -1}, new int[]{-5, -5, 0, 0},
                    new int[]{199, 199, 205, 205}, new int[]{200, 200, 205, 205}, new int[]{-3, 50, 2, 60},
                    new int[]{-12, 50, -9, 60}, new int[]{-12, 50, -8, 60}, new int[]{210, 210, 205, 205});
            Set<String> mismatches = new java.util.TreeSet<>();
            for (int[] box : boxes) {
                boolean anyDistrict = false;
                boolean anyBan = false;
                for (int x = Math.min(box[0], box[2]); x <= Math.max(box[0], box[2]); x++) {
                    for (int z = Math.min(box[1], box[3]); z <= Math.max(box[1], box[3]); z++) {
                        anyDistrict |= zones.zoneAt(level, x, z) != 0;
                        anyBan |= zones.inBanArea(level, x, z);
                    }
                }
                if (anyDistrict != zones.touchesDistrict(level, box[0], box[1], box[2], box[3])
                        || anyBan != zones.touchesBanArea(level, box[0], box[1], box[2], box[3])) {
                    mismatches.add(java.util.Arrays.toString(box));
                }
            }
            helper.assertTrue(mismatches.isEmpty(), "框判定与逐格判定不一致: " + mismatches);
        }
        helper.succeed();
    }

    /**
     * 个人圈地限制的两个新查询 (22.20) 与逐格判定一致: banHit 对框的每个边界值 (两个区的外围重叠时报第一个命中的);
     * newBanColumns 对一批固定种子的随机 (旧框, 新框), "新框里有一列落在某个区的禁圈区里、却不在旧框里" 报第一个这样的区。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void banHitAndNewBanColumns(GameTestHelper helper) {
        Level level = helper.getLevel();
        String dimension = level.dimension().location().toString();
        // A 与 B 的禁圈区在 X 22..27 重叠。
        int[][] districts = {{0, 0, 19, 19}, {30, 0, 49, 19}};
        String[] ids = {"gt-a", "gt-b"};
        List<DistrictZoneSnapshot.DistrictInput> inputs = new java.util.ArrayList<>();
        for (int i = 0; i < districts.length; i++) {
            int[] d = districts[i];
            inputs.add(new DistrictZoneSnapshot.DistrictInput(ids[i], ids[i], new DistrictBounds(dimension, d[0], d[1],
                    d[2], d[3]), List.of()));
        }
        DistrictZoneSnapshot zones = DistrictZoneSnapshot.build(inputs);
        int buffer = com.miningdim.district.DistrictLimits.BUFFER_BLOCKS;
        int[][] banZones = new int[districts.length][];
        for (int i = 0; i < districts.length; i++) {
            int[] d = districts[i];
            banZones[i] = new int[]{d[0] - buffer, d[1] - buffer, d[2] + buffer, d[3] + buffer};
        }

        Set<String> mismatches = new java.util.TreeSet<>();
        int[] xs = {-10, -9, -8, 0, 20, 21, 22, 27, 28, 30, 57, 58};
        int[] zs = {-9, -8, 0, 27, 28};
        for (int ax = 0; ax < xs.length; ax++) {
            for (int bx = ax; bx < xs.length; bx++) {
                for (int az = 0; az < zs.length; az++) {
                    for (int bz = az; bz < zs.length; bz++) {
                        int x0 = xs[ax];
                        int x1 = xs[bx];
                        int z0 = zs[az];
                        int z1 = zs[bz];
                        String expected = null;
                        for (int i = 0; i < banZones.length && expected == null; i++) {
                            if (anyColumn(x0, z0, x1, z1, banZones[i], null)) {
                                expected = ids[i];
                            }
                        }
                        DistrictZoneSnapshot.BanHit hit = zones.banHit(level, x1, z1, x0, z0);
                        String actual = hit == null ? null : hit.districtId();
                        if (!java.util.Objects.equals(expected, actual)) {
                            mismatches.add("banHit [" + x0 + "," + z0 + " ~ " + x1 + "," + z1 + "] 应为 " + expected
                                    + ", 实为 " + actual);
                        }
                    }
                }
            }
        }
        DistrictZoneSnapshot.BanHit first = zones.banHit(level, 23, 5, 25, 6);
        helper.assertTrue(first != null && "gt-a".equals(first.districtId())
                        && first.zone().equals(new PlotArea(-8, -8, 27, 27)),
                "两个区的外围重叠: 报第一个, 禁圈区是区 ± 8, 实为 " + first);
        helper.assertTrue(zones.banHit(Level.NETHER, 5, 5, 6, 6) == null, "别的维度: 不碰");

        java.util.Random random = new java.util.Random(20260930L);
        for (int n = 0; n < 400; n++) {
            PlotArea old = randomBox(random);
            PlotArea next = random.nextInt(4) == 0 ? old : randomBox(random);
            String expected = null;
            for (int i = 0; i < banZones.length && expected == null; i++) {
                if (anyColumn(next.minX(), next.minZ(), next.maxX(), next.maxZ(), banZones[i], old)) {
                    expected = ids[i];
                }
            }
            DistrictZoneSnapshot.BanHit hit = zones.newBanColumns(level, old, next);
            String actual = hit == null ? null : hit.districtId();
            if (!java.util.Objects.equals(expected, actual)) {
                mismatches.add("newBanColumns " + old + " -> " + next + " 应为 " + expected + ", 实为 " + actual);
            }
        }
        helper.assertTrue(mismatches.isEmpty(), "新查询与逐格判定不一致 (" + mismatches.size() + "): "
                + mismatches.stream().limit(10).toList());
        helper.succeed();
    }

    /** 框 [x0..x1] × [z0..z1] 里有没有一列落在 zone 里 (且不在 except 里)。 */
    private static boolean anyColumn(int x0, int z0, int x1, int z1, int[] zone, PlotArea except) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean inZone = x >= zone[0] && x <= zone[2] && z >= zone[1] && z <= zone[3];
                boolean inExcept = except != null && x >= except.minX() && x <= except.maxX() && z >= except.minZ()
                        && z <= except.maxZ();
                if (inZone && !inExcept) {
                    return true;
                }
            }
        }
        return false;
    }

    private static PlotArea randomBox(java.util.Random random) {
        int x0 = -15 + random.nextInt(81);
        int x1 = -15 + random.nextInt(81);
        int z0 = -12 + random.nextInt(45);
        int z1 = -12 + random.nextInt(45);
        return new PlotArea(Math.min(x0, x1), Math.min(z0, z1), Math.max(x0, x1), Math.max(z0, z1));
    }
}

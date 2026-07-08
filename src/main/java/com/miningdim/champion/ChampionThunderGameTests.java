package com.miningdim.champion;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 精英怪【技能词条·天雷 THUNDER】(批4 波2; ChampionStarAffix spec 7.4 可躲多点 AOE) 纯逻辑 GameTest (TDD)。
 *
 * 严禁触 Champions 加载路径 (compileOnly 铁律): 只断言 {@link ChampionThunderPlan} 的周期表 / 周期推进到点 / 门控
 * 24 边界 / 落点数 2..6 / 每点 %maxHP 表 + 每点伤害折算 / 半径 2.5 判定 / 预兆 30t / 散布距离 [3,8] 边界 / 两两间距
 * &gt;=5 校验 (合法组通过 + 贴脸组拒) / 拒绝采样放宽 (不死循环) + 参数校验, 全部逐位精确断言 (删被测折算/几何/边界必挂)。
 * 落雷/AOE 结算/免疫缓冲/粒子由 {@code ChampionThunderHandler} 真服验; 免疫缓冲拦截真值表已由
 * {@code AoeImmunityBufferGameTests} 覆盖, 此处不重复。
 *
 * template = "empty", batch = "champion_thunder"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionThunderGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_thunder";

    /** 数值/几何断言容差: 折算/旋转/开方浮点误差远小于此。 */
    private static final double EPS = 1.0e-6D;

    // ============================================================
    // 施放周期表 (16/15/14/13/12 s = 320/300/280/260/240 tick; 5 档精确)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cyclePeriodPerQuality(GameTestHelper helper) {
        helper.assertTrue(ChampionThunderPlan.cycleTicks(AffixQuality.COMMON) == 320L,
                "天雷 普通 周期 = 16s = 320 tick");
        helper.assertTrue(ChampionThunderPlan.cycleTicks(AffixQuality.UNCOMMON) == 300L,
                "天雷 中级 周期 = 15s = 300 tick");
        helper.assertTrue(ChampionThunderPlan.cycleTicks(AffixQuality.RARE) == 280L,
                "天雷 高级 周期 = 14s = 280 tick");
        helper.assertTrue(ChampionThunderPlan.cycleTicks(AffixQuality.EPIC) == 260L,
                "天雷 超凡 周期 = 13s = 260 tick");
        helper.assertTrue(ChampionThunderPlan.cycleTicks(AffixQuality.LEGENDARY) == 240L,
                "天雷 闪耀 周期 = 12s = 240 tick");
        helper.succeed();
    }

    // ============================================================
    // 周期推进 (扫描步进累加 -> 到点判定; 到点清零重计)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void advanceCycleStepsByScanInterval(GameTestHelper helper) {
        // 每次推进恰加一个扫描粒度 20 tick (删 +SCAN 步进则此处必挂)。
        helper.assertTrue(ChampionThunderPlan.advanceCycle(0L) == 20L, "推进一次 = +20 tick");
        helper.assertTrue(ChampionThunderPlan.advanceCycle(300L) == 320L, "推进累加 300 -> 320 tick");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cycleReadyAtPeriodBoundary(GameTestHelper helper) {
        // 闪耀周期 240: 239 未到点, 240 到点 (删 >= 判定退回 > 则下界必挂)。
        helper.assertTrue(!ChampionThunderPlan.cycleReady(239L, AffixQuality.LEGENDARY),
                "累加 239 < 240 (闪耀) 未到周期");
        helper.assertTrue(ChampionThunderPlan.cycleReady(240L, AffixQuality.LEGENDARY),
                "累加 240 = 240 (闪耀) 到点");
        // 普通周期 320: 319 未到, 320 到点 (跨品质联动周期表)。
        helper.assertTrue(!ChampionThunderPlan.cycleReady(319L, AffixQuality.COMMON),
                "累加 319 < 320 (普通) 未到周期");
        helper.assertTrue(ChampionThunderPlan.cycleReady(320L, AffixQuality.COMMON),
                "累加 320 = 320 (普通) 到点");
        helper.succeed();
    }

    // ============================================================
    // 门控 24 格边界 (超出冻结不耗周期; 同电磁)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void targetRangeBoundaryAt24Blocks(GameTestHelper helper) {
        // 24² = 576 恰在内; 577 超出 (删 <= 退回 < 则等距边界必挂)。
        helper.assertTrue(ChampionThunderPlan.withinTargetRange(576.0D),
                "距离² 576 (= 24 格) 在门控范围内");
        helper.assertTrue(!ChampionThunderPlan.withinTargetRange(577.0D),
                "距离² 577 (> 24 格) 超门控范围");
        helper.assertTrue(ChampionThunderPlan.withinTargetRange(0.0D), "距离² 0 (贴脸) 在内");
        helper.assertTrue(!ChampionThunderPlan.withinTargetRange(1000.0D), "距离² 1000 超范围");
        helper.succeed();
    }

    // ============================================================
    // 落点数 (2/3/4/5/6; 读 AffixDef.THUNDER 副数值转发)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pointCountPerQuality(GameTestHelper helper) {
        helper.assertTrue(ChampionThunderPlan.pointCount(AffixQuality.COMMON) == 2, "天雷 普通 2 落点");
        helper.assertTrue(ChampionThunderPlan.pointCount(AffixQuality.UNCOMMON) == 3, "天雷 中级 3 落点");
        helper.assertTrue(ChampionThunderPlan.pointCount(AffixQuality.RARE) == 4, "天雷 高级 4 落点");
        helper.assertTrue(ChampionThunderPlan.pointCount(AffixQuality.EPIC) == 5, "天雷 超凡 5 落点");
        helper.assertTrue(ChampionThunderPlan.pointCount(AffixQuality.LEGENDARY) == 6, "天雷 闪耀 6 落点");
        // 边界常量与档位联动 (普通=下限, 闪耀=上限)。
        helper.assertTrue(ChampionThunderPlan.pointCount(AffixQuality.COMMON) == ChampionThunderPlan.MIN_POINTS,
                "普通落点数 = MIN_POINTS");
        helper.assertTrue(ChampionThunderPlan.pointCount(AffixQuality.LEGENDARY) == ChampionThunderPlan.MAX_POINTS,
                "闪耀落点数 = MAX_POINTS");
        helper.succeed();
    }

    // ============================================================
    // 每点 %maxHP 表 + 每点伤害折算 (fraction × maxHP)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void perPointDamageFractionPerQuality(GameTestHelper helper) {
        helper.assertTrue(Math.abs(ChampionThunderPlan.perPointDamageFraction(AffixQuality.COMMON) - 0.12D) < EPS,
                "天雷 普通 每点 12% maxHP");
        helper.assertTrue(Math.abs(ChampionThunderPlan.perPointDamageFraction(AffixQuality.UNCOMMON) - 0.17D) < EPS,
                "天雷 中级 每点 17% maxHP");
        helper.assertTrue(Math.abs(ChampionThunderPlan.perPointDamageFraction(AffixQuality.RARE) - 0.22D) < EPS,
                "天雷 高级 每点 22% maxHP");
        helper.assertTrue(Math.abs(ChampionThunderPlan.perPointDamageFraction(AffixQuality.EPIC) - 0.27D) < EPS,
                "天雷 超凡 每点 27% maxHP");
        helper.assertTrue(Math.abs(ChampionThunderPlan.perPointDamageFraction(AffixQuality.LEGENDARY) - 0.32D) < EPS,
                "天雷 闪耀 每点 32% maxHP");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void perPointDamageScalesWithMaxHealth(GameTestHelper helper) {
        // 普通 12% × 80 血 = 9.6 (公服初始血 80 场景)。
        helper.assertTrue(Math.abs(ChampionThunderPlan.perPointDamage(80.0D, AffixQuality.COMMON) - 9.6D) < EPS,
                "普通 每点对 80 血玩家 = 9.6 名义伤");
        // 高级 22% × 100 血 = 22.0。
        helper.assertTrue(Math.abs(ChampionThunderPlan.perPointDamage(100.0D, AffixQuality.RARE) - 22.0D) < EPS,
                "高级 每点对 100 血玩家 = 22.0 名义伤");
        // 闪耀 32% × 200 血 = 64.0 (精装高血玩家吃更多, %maxHP 语义)。
        helper.assertTrue(Math.abs(ChampionThunderPlan.perPointDamage(200.0D, AffixQuality.LEGENDARY) - 64.0D) < EPS,
                "闪耀 每点对 200 血玩家 = 64.0 名义伤");
        // 0 血退化: 名义伤 0 (不抛)。
        helper.assertTrue(ChampionThunderPlan.perPointDamage(0.0D, AffixQuality.EPIC) == 0.0D,
                "0 maxHP 每点伤 = 0");
        helper.succeed();
    }

    // ============================================================
    // 预兆 30t + 半径 2.5 判定
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void warningWindowIs30Ticks(GameTestHelper helper) {
        helper.assertTrue(ChampionThunderPlan.WARNING_TICKS == 30, "预兆 1.5s = 30 tick");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void blastRadiusBoundaryAt2Point5(GameTestHelper helper) {
        // 半径 2.5 -> 半径² 6.25 恰命中; 6.26 逃过 (删 <= 退回 < 则贴边界必挂)。
        helper.assertTrue(ChampionThunderPlan.withinBlast(6.25D), "水平距² 6.25 (= 2.5 格) 命中");
        helper.assertTrue(!ChampionThunderPlan.withinBlast(6.26D), "水平距² 6.26 (> 2.5 格) 逃过");
        helper.assertTrue(ChampionThunderPlan.withinBlast(0.0D), "水平距² 0 (正中) 命中");
        helper.assertTrue(!ChampionThunderPlan.withinBlast(100.0D), "水平距² 100 逃过");
        // 半径/半径² 常量锚定 (用户裁定每点 2.5 格; 改 Plan 常量必挂)。
        helper.assertTrue(Math.abs(ChampionThunderPlan.PER_POINT_RADIUS - 2.5D) < EPS, "每点半径 = 2.5 格");
        helper.assertTrue(Math.abs(ChampionThunderPlan.PER_POINT_RADIUS_SQ - 6.25D) < EPS, "每点半径² = 6.25");
        helper.succeed();
    }

    // ============================================================
    // 落点极坐标几何 (角度+距离 -> 坐标; 距圆心恒 = 距离)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pointAtPolarGeometry(GameTestHelper helper) {
        // 圆心 (10,20): 0 弧度 = +X 方向 5 格 -> (15,20)。
        ChampionThunderPlan.BlastPoint east = ChampionThunderPlan.pointAt(10.0D, 20.0D, 0.0D, 5.0D);
        assertPoint(helper, east, 15.0D, 20.0D, "0 弧度 5 格 = 圆心 +X 5");
        // PI/2 弧度 = +Z 方向 5 格 -> (10,25)。
        ChampionThunderPlan.BlastPoint north = ChampionThunderPlan.pointAt(10.0D, 20.0D, Math.PI / 2.0D, 5.0D);
        assertPoint(helper, north, 10.0D, 25.0D, "PI/2 弧度 5 格 = 圆心 +Z 5");
        // PI 弧度 = -X 方向 8 格 (圆心 0,0) -> (-8,0)。
        ChampionThunderPlan.BlastPoint west = ChampionThunderPlan.pointAt(0.0D, 0.0D, Math.PI, 8.0D);
        assertPoint(helper, west, -8.0D, 0.0D, "PI 弧度 8 格 = 圆心 -X 8");
        // 任意角度: 到圆心距离恒 = 距离参数。
        for (double a = 0.0D; a < Math.PI * 2.0D; a += 0.7D) {
            ChampionThunderPlan.BlastPoint p = ChampionThunderPlan.pointAt(3.0D, -4.0D, a, 6.0D);
            double d = Math.sqrt((p.x() - 3.0D) * (p.x() - 3.0D) + (p.z() + 4.0D) * (p.z() + 4.0D));
            helper.assertTrue(Math.abs(d - 6.0D) < EPS, "任意角度落点到圆心恒 = 距离 6, 实测 " + d);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void scatterDistanceBoundaries(GameTestHelper helper) {
        // 散布距离下界 3 / 上界 8 合法, 到圆心恰 = 边界值。
        ChampionThunderPlan.BlastPoint near = ChampionThunderPlan.pointAt(0.0D, 0.0D, 0.0D,
                ChampionThunderPlan.MIN_SCATTER_DISTANCE);
        helper.assertTrue(Math.abs(dist(near, 0.0D, 0.0D) - 3.0D) < EPS, "散布下界 = 3 格");
        ChampionThunderPlan.BlastPoint far = ChampionThunderPlan.pointAt(0.0D, 0.0D, 0.0D,
                ChampionThunderPlan.MAX_SCATTER_DISTANCE);
        helper.assertTrue(Math.abs(dist(far, 0.0D, 0.0D) - 8.0D) < EPS, "散布上界 = 8 格");
        // 越界距离一律拒 (异常必须痛)。
        helper.assertTrue(throwsIae(() -> ChampionThunderPlan.pointAt(0.0D, 0.0D, 0.0D, 2.9D)),
                "pointAt 距离 2.9 (< 3) 须抛");
        helper.assertTrue(throwsIae(() -> ChampionThunderPlan.pointAt(0.0D, 0.0D, 0.0D, 8.1D)),
                "pointAt 距离 8.1 (> 8) 须抛");
        helper.succeed();
    }

    // ============================================================
    // 两两间距 >=5 (= 2×半径) 校验
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void separationThresholdAtTwiceRadius(GameTestHelper helper) {
        // 硬约束 = 2×半径 = 5 格 (不可同点叠杀: 半径 2.5 圆两两至多相切; 改 Plan 常量必挂)。
        helper.assertTrue(Math.abs(ChampionThunderPlan.MIN_POINT_SEPARATION - 5.0D) < EPS,
                "最小间距 = 5 格 (= 2×半径 2.5)");
        // 间距 5.0 恰达标 (相切); 4.99 贴脸拒; 0 拒。
        helper.assertTrue(ChampionThunderPlan.separated(0.0D, 0.0D, 5.0D, 0.0D), "间距 5.0 达标 (相切)");
        helper.assertTrue(!ChampionThunderPlan.separated(0.0D, 0.0D, 4.99D, 0.0D), "间距 4.99 贴脸拒");
        helper.assertTrue(!ChampionThunderPlan.separated(0.0D, 0.0D, 0.0D, 0.0D), "间距 0 (同点) 拒");
        // 斜向 3-4-5: 间距恰 5 达标; 略近 (3, 3.9) = ~4.92 拒。
        helper.assertTrue(ChampionThunderPlan.separated(0.0D, 0.0D, 3.0D, 4.0D), "斜向间距 5 (3-4-5) 达标");
        helper.assertTrue(!ChampionThunderPlan.separated(0.0D, 0.0D, 3.0D, 3.9D), "斜向间距 ~4.92 拒");
        helper.succeed();
    }

    // ============================================================
    // 拒绝采样 (合法组通过 / 贴脸组拒 / 混合 / 放宽不死循环)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void selectScatterLegalGroupAllAccepted(GameTestHelper helper) {
        // 3 提案 120 度均分 @8 格 (圆心 0,0): 两两间距 ~13.86 >=5, 全纳。
        double[] angles = {0.0D, Math.PI * 2.0D / 3.0D, Math.PI * 4.0D / 3.0D};
        double[] distances = {8.0D, 8.0D, 8.0D};
        List<ChampionThunderPlan.BlastPoint> points =
                ChampionThunderPlan.selectScatterPoints(0.0D, 0.0D, angles, distances, 3);
        helper.assertTrue(points.size() == 3, "合法组 3 提案全纳, 实测 " + points.size());
        helper.assertTrue(ChampionThunderPlan.allPairsSeparated(points), "合法组结果两两达标");
        // 每点落在散布环 [3,8] 内。
        for (ChampionThunderPlan.BlastPoint p : points) {
            double d = dist(p, 0.0D, 0.0D);
            helper.assertTrue(d >= ChampionThunderPlan.MIN_SCATTER_DISTANCE - EPS
                            && d <= ChampionThunderPlan.MAX_SCATTER_DISTANCE + EPS,
                    "结果落点在散布环 [3,8], 实测 " + d);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void selectScatterFaceToFaceClusterRelaxesToOne(GameTestHelper helper) {
        // 6 提案全同点 (角度 0 距离 3 -> (3,0)): 首点纳, 其余贴脸全拒 -> 放宽到 1 点 (不死循环/不叠点)。
        double[] angles = new double[6];
        double[] distances = new double[6];
        for (int i = 0; i < 6; i++) {
            angles[i] = 0.0D;
            distances[i] = 3.0D;
        }
        List<ChampionThunderPlan.BlastPoint> points =
                ChampionThunderPlan.selectScatterPoints(0.0D, 0.0D, angles, distances, 6);
        helper.assertTrue(points.size() == 1, "贴脸组放宽到 1 点 (期望 6 却只凑 1), 实测 " + points.size());
        assertPoint(helper, points.get(0), 3.0D, 0.0D, "唯一纳入点 = (3,0)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void selectScatterMixSkipsDuplicatesKeepsSeparated(GameTestHelper helper) {
        // 混合: [ (0,8), (0,8)重复贴脸, (120°,8), (240°,8) ]; 期望 3 -> 纳 3 (跳重复), 两两达标。
        double a1 = Math.PI * 2.0D / 3.0D;
        double a2 = Math.PI * 4.0D / 3.0D;
        double[] angles = {0.0D, 0.0D, a1, a2};
        double[] distances = {8.0D, 8.0D, 8.0D, 8.0D};
        List<ChampionThunderPlan.BlastPoint> points =
                ChampionThunderPlan.selectScatterPoints(0.0D, 0.0D, angles, distances, 3);
        helper.assertTrue(points.size() == 3, "混合组纳 3 (跳过重复贴脸), 实测 " + points.size());
        helper.assertTrue(ChampionThunderPlan.allPairsSeparated(points), "混合组结果两两达标");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void scatterAttemptBudgetScalesWithPoints(GameTestHelper helper) {
        // 提案预算 = 落点数 × 8 (每点 8 次尝试)。
        helper.assertTrue(ChampionThunderPlan.scatterAttemptBudget(2) == 16, "2 点预算 = 16 提案");
        helper.assertTrue(ChampionThunderPlan.scatterAttemptBudget(6) == 48, "6 点预算 = 48 提案");
        helper.succeed();
    }

    // ============================================================
    // 参数校验 (异常必须痛)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void invalidArgsRejected(GameTestHelper helper) {
        helper.assertTrue(throwsIae(() -> ChampionThunderPlan.cycleTicks(null)),
                "cycleTicks null 品质须抛");
        helper.assertTrue(throwsIae(() -> ChampionThunderPlan.cycleReady(0L, null)),
                "cycleReady null 品质须抛");
        helper.assertTrue(throwsIae(() -> ChampionThunderPlan.cycleReady(-1L, AffixQuality.COMMON)),
                "cycleReady 负累加须抛");
        helper.assertTrue(throwsIae(() -> ChampionThunderPlan.advanceCycle(-1L)),
                "advanceCycle 负累加须抛");
        helper.assertTrue(throwsIae(() -> ChampionThunderPlan.withinTargetRange(-1.0D)),
                "withinTargetRange 负距离²须抛");
        helper.assertTrue(throwsIae(() -> ChampionThunderPlan.withinBlast(-1.0D)),
                "withinBlast 负距离²须抛");
        helper.assertTrue(throwsIae(() -> ChampionThunderPlan.pointCount(null)),
                "pointCount null 品质须抛");
        helper.assertTrue(throwsIae(() -> ChampionThunderPlan.perPointDamageFraction(null)),
                "perPointDamageFraction null 品质须抛");
        helper.assertTrue(throwsIae(() -> ChampionThunderPlan.perPointDamage(-1.0D, AffixQuality.COMMON)),
                "perPointDamage 负 maxHP 须抛");
        helper.assertTrue(throwsIae(() -> ChampionThunderPlan.perPointDamage(100.0D, null)),
                "perPointDamage null 品质须抛");
        helper.assertTrue(throwsIae(() -> ChampionThunderPlan.scatterAttemptBudget(1)),
                "scatterAttemptBudget 落点数 1 (< 2) 须抛");
        helper.assertTrue(throwsIae(() -> ChampionThunderPlan.scatterAttemptBudget(7)),
                "scatterAttemptBudget 落点数 7 (> 6) 须抛");
        helper.assertTrue(throwsIae(() -> ChampionThunderPlan.allPairsSeparated(null)),
                "allPairsSeparated null 须抛");
        // selectScatterPoints 参数校验。
        helper.assertTrue(throwsIae(() -> ChampionThunderPlan.selectScatterPoints(
                        0.0D, 0.0D, new double[]{0.0D}, new double[]{3.0D, 3.0D}, 2)),
                "selectScatterPoints 角度/距离长度不一致须抛");
        helper.assertTrue(throwsIae(() -> ChampionThunderPlan.selectScatterPoints(
                        0.0D, 0.0D, new double[]{0.0D, 0.0D}, new double[]{3.0D, 3.0D}, 1)),
                "selectScatterPoints 落点数 1 (< 2) 须抛");
        helper.assertTrue(throwsIae(() -> ChampionThunderPlan.selectScatterPoints(
                        0.0D, 0.0D, new double[]{0.0D, 0.0D}, new double[]{3.0D, 3.0D}, 7)),
                "selectScatterPoints 落点数 7 (> 6) 须抛");
        helper.assertTrue(throwsIae(() -> ChampionThunderPlan.selectScatterPoints(
                        0.0D, 0.0D, new double[]{0.0D, 0.0D}, new double[]{3.0D, 9.0D}, 2)),
                "selectScatterPoints 越界距离 9 须抛");
        helper.assertTrue(throwsIae(() -> ChampionThunderPlan.selectScatterPoints(
                        0.0D, 0.0D, null, new double[]{3.0D}, 2)),
                "selectScatterPoints null 角度须抛");
        helper.succeed();
    }

    // ---- 私有断言辅助 ----

    private static double dist(ChampionThunderPlan.BlastPoint p, double cx, double cz) {
        return Math.sqrt((p.x() - cx) * (p.x() - cx) + (p.z() - cz) * (p.z() - cz));
    }

    private static void assertPoint(GameTestHelper helper, ChampionThunderPlan.BlastPoint p,
                                    double ex, double ez, String msg) {
        helper.assertTrue(Math.abs(p.x() - ex) < EPS && Math.abs(p.z() - ez) < EPS,
                msg + " (期望 " + ex + "," + ez + " 实测 " + p.x() + "," + p.z() + ")");
    }

    private static boolean throwsIae(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }
}

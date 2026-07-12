package com.miningdim.champion;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 精英怪【机动词条·灵体移动 PHASE_WALK】(Stage2 批4 波3 压轴; ChampionStarAffix spec 7.3 穿墙型) 纯逻辑 GameTest (TDD)。
 *
 * 严禁触 Champions/世界加载路径: 只断言 {@link ChampionPhaseWalkPlan} 的施放周期表 / 穿墙时长表 / 周期推进 / 缰绳与
 * 到达边界 / 驱动步进向量数学 (含零距/垂直) / 环搜几何 / 回退链 8 组合真值表 + 参数校验, 全部具体数值逐位断言
 * (删被测折算/几何/回退序位/校验必挂)。落点安全裁决数学由 {@link SafeLandingRules}/{@code KnockbackSafetyGuard}
 * 单一权威 (已由各自 GameTest 覆盖), 此处不重复。真服 (逐 tick 无碰撞驱动 + 回退链实体化) 由
 * {@code ChampionPhaseWalkHandler} 施加。
 *
 * template = "empty", batch = "champion_phase_walk"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionPhaseWalkGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_phase_walk";

    /** 浮点比较容差 (步进/环几何的 double 舍入; 断言精确坐标用)。 */
    private static final double EPS = 1.0E-9D;

    // ============================================================
    // 施放周期表 (15/13/11.5/9.5/8 s = 300/260/230/190/160 tick; 主线拍板, 5 档精确)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cyclePeriodTicksPerQuality(GameTestHelper helper) {
        helper.assertTrue(ChampionPhaseWalkPlan.cyclePeriodTicks(AffixQuality.COMMON) == 300L,
                "灵体 普通 周期 = 15s = 300 tick");
        helper.assertTrue(ChampionPhaseWalkPlan.cyclePeriodTicks(AffixQuality.UNCOMMON) == 260L,
                "灵体 中级 周期 = 13s = 260 tick");
        helper.assertTrue(ChampionPhaseWalkPlan.cyclePeriodTicks(AffixQuality.RARE) == 230L,
                "灵体 高级 周期 = 11.5s = 230 tick");
        helper.assertTrue(ChampionPhaseWalkPlan.cyclePeriodTicks(AffixQuality.EPIC) == 190L,
                "灵体 超凡 周期 = 9.5s = 190 tick");
        helper.assertTrue(ChampionPhaseWalkPlan.cyclePeriodTicks(AffixQuality.LEGENDARY) == 160L,
                "灵体 闪耀 周期 = 8s = 160 tick");
        helper.succeed();
    }

    // ============================================================
    // 穿墙时长表 (2/2.5/3/3.5/4 s = 40/50/60/70/80 tick; 取 AffixDef.PHASE_WALK 数值表)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void phaseDurationTicksPerQuality(GameTestHelper helper) {
        helper.assertTrue(ChampionPhaseWalkPlan.phaseDurationTicks(AffixQuality.COMMON) == 40L,
                "灵体 普通 穿墙 = 2s = 40 tick");
        helper.assertTrue(ChampionPhaseWalkPlan.phaseDurationTicks(AffixQuality.UNCOMMON) == 50L,
                "灵体 中级 穿墙 = 2.5s = 50 tick");
        helper.assertTrue(ChampionPhaseWalkPlan.phaseDurationTicks(AffixQuality.RARE) == 60L,
                "灵体 高级 穿墙 = 3s = 60 tick");
        helper.assertTrue(ChampionPhaseWalkPlan.phaseDurationTicks(AffixQuality.EPIC) == 70L,
                "灵体 超凡 穿墙 = 3.5s = 70 tick");
        helper.assertTrue(ChampionPhaseWalkPlan.phaseDurationTicks(AffixQuality.LEGENDARY) == 80L,
                "灵体 闪耀 穿墙 = 4s = 80 tick");
        helper.succeed();
    }

    // ============================================================
    // 周期推进 (扫描步进累加 -> 到点判定)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void advanceCycleStepsByScanInterval(GameTestHelper helper) {
        helper.assertTrue(ChampionPhaseWalkPlan.advanceCycle(0L) == 20L, "推进一次 = +20 tick");
        helper.assertTrue(ChampionPhaseWalkPlan.advanceCycle(300L) == 320L, "推进累加 300 -> 320 tick");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cycleReadyAtPeriodBoundary(GameTestHelper helper) {
        // 高级周期 230 tick: 220 未到, 恰 230 到点 (删 >= 判定退回 > 则下界必挂; 230 非... 是 20 整数倍)。
        helper.assertTrue(!ChampionPhaseWalkPlan.cycleReady(220L, AffixQuality.RARE),
                "累加 220 < 230 (高级) 未到周期");
        helper.assertTrue(ChampionPhaseWalkPlan.cycleReady(230L, AffixQuality.RARE),
                "累加 230 = 230 (高级) 到点");
        // 超凡周期 190 tick: 180 未到, 恰 190 到点。
        helper.assertTrue(!ChampionPhaseWalkPlan.cycleReady(180L, AffixQuality.EPIC),
                "累加 180 < 190 (超凡) 未到周期");
        helper.assertTrue(ChampionPhaseWalkPlan.cycleReady(190L, AffixQuality.EPIC),
                "累加 190 = 190 (超凡) 到点");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cycleFiresEveryPeriodViaScanStepping(GameTestHelper helper) {
        // 模拟 handler 逐扫描推进 (每步 +20, 到点清零重计): 闪耀周期 160 -> 每 8 步施放一次, 首次在第 8 步。
        AffixQuality quality = AffixQuality.LEGENDARY;
        long elapsed = 0L;
        int fires = 0;
        int firstFireStep = -1;
        for (int step = 1; step <= 24; step++) { // 24 步 = 3 个满周期 (每周期 8 步)
            elapsed = ChampionPhaseWalkPlan.advanceCycle(elapsed);
            if (ChampionPhaseWalkPlan.cycleReady(elapsed, quality)) {
                elapsed = 0L; // 到点清零 (周期照走不补偿)。
                fires++;
                if (firstFireStep < 0) {
                    firstFireStep = step;
                }
            }
        }
        helper.assertTrue(firstFireStep == 8, "闪耀 160tick 周期: 首次施放在第 8 扫描步 (8x20=160)");
        helper.assertTrue(fires == 3, "24 扫描步恰施放 3 次 (每 8 步一次)");
        helper.succeed();
    }

    // ============================================================
    // 缰绳 24 格边界 (24 通 24.0001 拒; 主线拍板)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void leashBoundary(GameTestHelper helper) {
        helper.assertTrue(ChampionPhaseWalkPlan.withinLeash(0.0D), "距离 0 在缰绳内");
        helper.assertTrue(ChampionPhaseWalkPlan.withinLeash(23.999D), "距离 23.999 在缰绳内");
        helper.assertTrue(ChampionPhaseWalkPlan.withinLeash(24.0D), "距离 24 (边界含) 在缰绳内");
        helper.assertTrue(!ChampionPhaseWalkPlan.withinLeash(24.0001D), "距离 24.0001 超缰绳");
        helper.succeed();
    }

    // ============================================================
    // 门控真值 (有存活目标 且 在缰绳内 才推进周期)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void gateTruthTable(GameTestHelper helper) {
        helper.assertTrue(ChampionPhaseWalkPlan.shouldAdvanceCycle(true, 10.0D),
                "有目标 + 缰绳内 (10 格): 推进");
        helper.assertTrue(ChampionPhaseWalkPlan.shouldAdvanceCycle(true, 24.0D),
                "有目标 + 缰绳边界 (24 格): 推进");
        helper.assertTrue(!ChampionPhaseWalkPlan.shouldAdvanceCycle(true, 24.5D),
                "有目标但超缰绳 (24.5 格): 冻结");
        helper.assertTrue(!ChampionPhaseWalkPlan.shouldAdvanceCycle(false, 10.0D),
                "无目标 (纵在缰绳内): 冻结");
        helper.assertTrue(!ChampionPhaseWalkPlan.shouldAdvanceCycle(false, 0.0D),
                "无目标: 距离参数不参与, 恒冻结");
        helper.succeed();
    }

    // ============================================================
    // 到达 / 最小落点距离边界 (均 1.5 格; 到达含边界, 最小距离含边界)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void arrivalAndMinLandingBoundary(GameTestHelper helper) {
        helper.assertTrue(ChampionPhaseWalkPlan.reachedTarget(0.0D), "距目标 0: 已抵达");
        helper.assertTrue(ChampionPhaseWalkPlan.reachedTarget(1.5D), "距目标 1.5 (边界含): 已抵达");
        helper.assertTrue(!ChampionPhaseWalkPlan.reachedTarget(1.5001D), "距目标 1.5001: 未抵达");
        helper.assertTrue(!ChampionPhaseWalkPlan.reachedTarget(3.0D), "距目标 3: 未抵达");

        helper.assertTrue(!ChampionPhaseWalkPlan.meetsMinLandingDistance(1.4999D),
                "落点距目标 1.4999 (<1.5): 不满足身位下限");
        helper.assertTrue(ChampionPhaseWalkPlan.meetsMinLandingDistance(1.5D),
                "落点距目标 1.5 (边界含): 满足身位下限");
        helper.assertTrue(ChampionPhaseWalkPlan.meetsMinLandingDistance(6.0D),
                "落点距目标 6.0: 满足身位下限");
        helper.succeed();
    }

    // ============================================================
    // 驱动步进向量 (单位向量 x 0.25; 含水平/垂直/对角/零距/反向)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void driveStepHorizontal(GameTestHelper helper) {
        // 起 (0,0,0) 终 (10,0,0): 正 X 方向, 步进 +0.25。
        double[] s = ChampionPhaseWalkPlan.driveStep(0.0D, 0.0D, 0.0D, 10.0D, 0.0D, 0.0D);
        helper.assertTrue(Math.abs(s[0] - 0.25D) < EPS, "水平 +X 步进 X = 0.25");
        helper.assertTrue(Math.abs(s[1]) < EPS && Math.abs(s[2]) < EPS, "水平 +X 步进 Y/Z = 0");
        // 起 (2,2,2) 终 (2,2,12): 正 Z, 保留起点基 + 0.25。
        double[] z = ChampionPhaseWalkPlan.driveStep(2.0D, 2.0D, 2.0D, 2.0D, 2.0D, 12.0D);
        helper.assertTrue(Math.abs(z[0] - 2.0D) < EPS && Math.abs(z[1] - 2.0D) < EPS
                && Math.abs(z[2] - 2.25D) < EPS, "起点基 (2,2,2) + 0.25Z -> (2,2,2.25)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void driveStepVertical(GameTestHelper helper) {
        // 纯垂直 (0,0,0) -> (0,5,0): 步进 +0.25 Y (三维向量含 Y 分量, 非仅水平)。
        double[] s = ChampionPhaseWalkPlan.driveStep(0.0D, 0.0D, 0.0D, 0.0D, 5.0D, 0.0D);
        helper.assertTrue(Math.abs(s[0]) < EPS && Math.abs(s[2]) < EPS, "垂直步进 X/Z = 0");
        helper.assertTrue(Math.abs(s[1] - 0.25D) < EPS, "垂直步进 Y = 0.25");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void driveStepDiagonalMagnitude(GameTestHelper helper) {
        // 对角 (0,0,0) -> (1,1,1): 步进模长恒 = 0.25, 每分量 = 0.25/sqrt(3)。
        double[] s = ChampionPhaseWalkPlan.driveStep(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
        double mag = Math.sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2]);
        helper.assertTrue(Math.abs(mag - 0.25D) < EPS, "对角步进模长 = 0.25 (实测 " + mag + ")");
        double comp = 0.25D / Math.sqrt(3.0D);
        helper.assertTrue(Math.abs(s[0] - comp) < EPS && Math.abs(s[1] - comp) < EPS
                && Math.abs(s[2] - comp) < EPS, "对角各分量 = 0.25/sqrt(3)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void driveStepReverseDirection(GameTestHelper helper) {
        // 反向 (5,5,5) -> (1,5,5): 沿 -X 步进 0.25 -> (4.75,5,5)。
        double[] s = ChampionPhaseWalkPlan.driveStep(5.0D, 5.0D, 5.0D, 1.0D, 5.0D, 5.0D);
        helper.assertTrue(Math.abs(s[0] - 4.75D) < EPS && Math.abs(s[1] - 5.0D) < EPS
                && Math.abs(s[2] - 5.0D) < EPS, "反向 -X 步进 -> (4.75,5,5)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void driveStepZeroDistanceDegenerate(GameTestHelper helper) {
        // 零距退化: 起终点重合 -> 原样返回起点 (不 NaN)。
        double[] s = ChampionPhaseWalkPlan.driveStep(3.0D, -2.0D, 7.0D, 3.0D, -2.0D, 7.0D);
        helper.assertTrue(s[0] == 3.0D && s[1] == -2.0D && s[2] == 7.0D, "零距退化: 原地不动");
        helper.assertTrue(!Double.isNaN(s[0]) && !Double.isNaN(s[1]) && !Double.isNaN(s[2]),
                "零距退化: 无 NaN");
        helper.succeed();
    }

    // ============================================================
    // 环搜几何 (由近及远; 每环 8 角度; 半径 1.5..6; 距心 = 环半径)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ringCandidatesGeometry(GameTestHelper helper) {
        double[] expectedRadii = {1.5D, 2.5D, 3.5D, 4.5D, 5.5D, 6.0D};
        int anglesPerRing = 8;
        List<double[]> candidates = ChampionPhaseWalkPlan.ringCandidates(0.0D, 0.0D);
        helper.assertTrue(candidates.size() == expectedRadii.length * anglesPerRing,
                "候选数 = 环数 6 × 每环 8 = 48 (实测 " + candidates.size() + ")");

        double prevRadius = -1.0D;
        for (int i = 0; i < candidates.size(); i++) {
            double[] c = candidates.get(i);
            double dist = Math.sqrt(c[0] * c[0] + c[1] * c[1]);
            double expected = expectedRadii[i / anglesPerRing];
            helper.assertTrue(Math.abs(dist - expected) < EPS,
                    "候选 " + i + " 距心 = 环半径 " + expected + " (实测 " + dist + ")");
            helper.assertTrue(dist >= 1.5D - EPS && dist <= 6.0D + EPS,
                    "候选 " + i + " 半径落在 [1.5,6] (实测 " + dist + ")");
            // 由近及远: 后一环半径 >= 前一环 (同环内相等)。
            helper.assertTrue(expected >= prevRadius - EPS, "环半径由近及远非降");
            prevRadius = expected;
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ringCandidatesCenteredOnTarget(GameTestHelper helper) {
        // 环心偏移到 (100,-50): 首候选 (最近环 1.5, 角度 0 = +X) = (101.5, -50)。
        List<double[]> candidates = ChampionPhaseWalkPlan.ringCandidates(100.0D, -50.0D);
        double[] first = candidates.get(0);
        helper.assertTrue(Math.abs(first[0] - 101.5D) < EPS, "首候选 X = 心X + 1.5");
        helper.assertTrue(Math.abs(first[1] - (-50.0D)) < EPS, "首候选 Z = 心Z (角度 0)");
        // 所有候选距环心恒 >= 最小落点距离 (由构造保证)。
        for (double[] c : candidates) {
            double dx = c[0] - 100.0D;
            double dz = c[1] - (-50.0D);
            double dist = Math.sqrt(dx * dx + dz * dz);
            helper.assertTrue(ChampionPhaseWalkPlan.meetsMinLandingDistance(dist),
                    "环候选恒满足最小落点距离 (实测 " + dist + ")");
        }
        helper.succeed();
    }

    // ============================================================
    // 回退链 8 组合真值表 (严格按序: current > ring > lastValid > forced; 删任一序位必有行翻转)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void fallbackChainTruthTable(GameTestHelper helper) {
        // current=T: 无论 ring/lastValid 恒 IN_PLACE (4 行)。
        assertFallback(helper, true, true, true, ChampionPhaseWalkPlan.FallbackOutcome.IN_PLACE);
        assertFallback(helper, true, true, false, ChampionPhaseWalkPlan.FallbackOutcome.IN_PLACE);
        assertFallback(helper, true, false, true, ChampionPhaseWalkPlan.FallbackOutcome.IN_PLACE);
        assertFallback(helper, true, false, false, ChampionPhaseWalkPlan.FallbackOutcome.IN_PLACE);
        // current=F, ring=T: 恒 RING (2 行; 证明 ring 优先于 lastValid)。
        assertFallback(helper, false, true, true, ChampionPhaseWalkPlan.FallbackOutcome.RING);
        assertFallback(helper, false, true, false, ChampionPhaseWalkPlan.FallbackOutcome.RING);
        // current=F, ring=F, lastValid=T: LAST_VALID。
        assertFallback(helper, false, false, true, ChampionPhaseWalkPlan.FallbackOutcome.LAST_VALID);
        // 全 F: FORCED。
        assertFallback(helper, false, false, false, ChampionPhaseWalkPlan.FallbackOutcome.FORCED);
        helper.succeed();
    }

    private static void assertFallback(GameTestHelper helper, boolean current, boolean ring, boolean lastValid,
                                       ChampionPhaseWalkPlan.FallbackOutcome expected) {
        ChampionPhaseWalkPlan.FallbackOutcome actual =
                ChampionPhaseWalkPlan.resolveFallback(current, ring, lastValid);
        helper.assertTrue(actual == expected,
                "回退链 (current=" + current + " ring=" + ring + " lastValid=" + lastValid + ") -> "
                        + expected + " (实测 " + actual + ")");
    }

    // ============================================================
    // 参数校验 (异常必须痛: 空品质 / 负累加)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void invalidArgsRejected(GameTestHelper helper) {
        boolean rejectedNullCycle = false;
        try {
            ChampionPhaseWalkPlan.cyclePeriodTicks(null);
        } catch (IllegalArgumentException expected) {
            rejectedNullCycle = true;
        }
        helper.assertTrue(rejectedNullCycle, "周期 null 品质须抛 IllegalArgumentException");

        boolean rejectedNullDuration = false;
        try {
            ChampionPhaseWalkPlan.phaseDurationTicks(null);
        } catch (IllegalArgumentException expected) {
            rejectedNullDuration = true;
        }
        helper.assertTrue(rejectedNullDuration, "穿墙时长 null 品质须抛 IllegalArgumentException");

        boolean rejectedNullReady = false;
        try {
            ChampionPhaseWalkPlan.cycleReady(0L, null);
        } catch (IllegalArgumentException expected) {
            rejectedNullReady = true;
        }
        helper.assertTrue(rejectedNullReady, "到点判定 null 品质须抛 IllegalArgumentException");

        boolean rejectedNegativeAdvance = false;
        try {
            ChampionPhaseWalkPlan.advanceCycle(-1L);
        } catch (IllegalArgumentException expected) {
            rejectedNegativeAdvance = true;
        }
        helper.assertTrue(rejectedNegativeAdvance, "推进负累加 tick 须抛 IllegalArgumentException");

        boolean rejectedNegativeReady = false;
        try {
            ChampionPhaseWalkPlan.cycleReady(-1L, AffixQuality.COMMON);
        } catch (IllegalArgumentException expected) {
            rejectedNegativeReady = true;
        }
        helper.assertTrue(rejectedNegativeReady, "到点判定负累加 tick 须抛 IllegalArgumentException");
        helper.succeed();
    }
}

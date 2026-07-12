package com.miningdim.champion;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 精英怪【机动词条·闪光 BLINK】(Stage2 批4 波1; ChampionStarAffix spec 7.2 抵近型反风筝瞬移) 纯逻辑 GameTest (TDD)。
 *
 * 严禁触 Champions/世界加载路径: 只断言 {@link ChampionBlinkPlan} 的施放周期表 / 缰绳边界 / 门控真值 / 落点候选环
 * 几何 / 禁近判定 + 参数校验, 全部具体数值逐位断言 (删被测折算/门控/几何/校验必挂)。落点安全裁决数学由
 * {@link SafeLandingRules} / {@code KnockbackSafetyGuard} 单一权威 (已由各自 GameTest 覆盖), 此处不重复。真服 (效果
 * 施加) 由 {@code ChampionBlinkHandler} 每秒扫近玩家冠军门控推进周期 + 到点选安全落点起预兆瞬移。
 *
 * template = "empty", batch = "champion_blink"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionBlinkGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_blink";

    /** 浮点比较容差 (环几何/角度折算的 double 舍入; 断言精确坐标用)。 */
    private static final double EPS = 1.0E-9D;

    // ============================================================
    // 施放周期表 (9/8/7/5.5/4 s = 180/160/140/110/80 tick; 5 档精确)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cycleTicksPerQuality(GameTestHelper helper) {
        helper.assertTrue(ChampionBlinkPlan.cycleTicks(AffixQuality.COMMON) == 180L,
                "闪光 普通 周期 = 9s = 180 tick");
        helper.assertTrue(ChampionBlinkPlan.cycleTicks(AffixQuality.UNCOMMON) == 160L,
                "闪光 中级 周期 = 8s = 160 tick");
        helper.assertTrue(ChampionBlinkPlan.cycleTicks(AffixQuality.RARE) == 140L,
                "闪光 高级 周期 = 7s = 140 tick");
        helper.assertTrue(ChampionBlinkPlan.cycleTicks(AffixQuality.EPIC) == 110L,
                "闪光 超凡 周期 = 5.5s = 110 tick");
        helper.assertTrue(ChampionBlinkPlan.cycleTicks(AffixQuality.LEGENDARY) == 80L,
                "闪光 闪耀 周期 = 4s = 80 tick");
        helper.succeed();
    }

    // ============================================================
    // 周期推进 (扫描步进累加 -> 到点判定)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void advanceCycleStepsByScanInterval(GameTestHelper helper) {
        // 每次推进恰加一个扫描粒度 20 tick (删 +SCAN 步进则此处必挂)。
        helper.assertTrue(ChampionBlinkPlan.advanceCycle(0L) == 20L, "推进一次 = +20 tick");
        helper.assertTrue(ChampionBlinkPlan.advanceCycle(160L) == 180L, "推进累加 160 -> 180 tick");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cycleReadyAtPeriodBoundary(GameTestHelper helper) {
        // 高级周期 140 tick: 120 未到点, 140 到点 (删 >= 判定退回 > 则下界必挂)。
        helper.assertTrue(!ChampionBlinkPlan.cycleReady(120L, AffixQuality.RARE),
                "累加 120 < 140 (高级) 未到周期");
        helper.assertTrue(ChampionBlinkPlan.cycleReady(140L, AffixQuality.RARE),
                "累加 140 = 140 (高级) 到点");
        // 普通周期 180 tick: 160 未到, 180 到点。
        helper.assertTrue(!ChampionBlinkPlan.cycleReady(160L, AffixQuality.COMMON),
                "累加 160 < 180 (普通) 未到周期");
        helper.assertTrue(ChampionBlinkPlan.cycleReady(180L, AffixQuality.COMMON),
                "累加 180 = 180 (普通) 到点");
        // 超凡 110 tick (非 20 整数倍): 100 未到, 恰 110 到点 —— 钉死非整步周期的精确下界 (步进舍入是 handler 侧行为)。
        helper.assertTrue(!ChampionBlinkPlan.cycleReady(100L, AffixQuality.EPIC),
                "累加 100 < 110 (超凡) 未到周期");
        helper.assertTrue(ChampionBlinkPlan.cycleReady(110L, AffixQuality.EPIC),
                "累加 110 = 110 (超凡) 到点");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cycleFiresEveryPeriodViaScanStepping(GameTestHelper helper) {
        // 模拟 handler 逐扫描推进 (每步 +20, 到点清零重计): 闪耀周期 80 -> 每 4 步施放一次, 首次在第 4 步。
        AffixQuality quality = AffixQuality.LEGENDARY;
        long elapsed = 0L;
        int fires = 0;
        int firstFireStep = -1;
        for (int step = 1; step <= 12; step++) { // 12 步 = 3 个满周期 (每周期 4 步)
            elapsed = ChampionBlinkPlan.advanceCycle(elapsed);
            if (ChampionBlinkPlan.cycleReady(elapsed, quality)) {
                elapsed = 0L; // 到点清零 (周期照走不补偿)。
                fires++;
                if (firstFireStep < 0) {
                    firstFireStep = step;
                }
            }
        }
        helper.assertTrue(firstFireStep == 4, "闪耀 80tick 周期: 首次施放在第 4 扫描步 (4x20=80)");
        helper.assertTrue(fires == 3, "12 扫描步恰施放 3 次 (每 4 步一次)");
        helper.succeed();
    }

    // ============================================================
    // 缰绳 24 格边界 (24 通 24.5 拒; 用户裁定 波1)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void leashBoundary(GameTestHelper helper) {
        helper.assertTrue(ChampionBlinkPlan.withinLeash(0.0D), "距离 0 在缰绳内");
        helper.assertTrue(ChampionBlinkPlan.withinLeash(23.999D), "距离 23.999 在缰绳内");
        helper.assertTrue(ChampionBlinkPlan.withinLeash(24.0D), "距离 24 (边界含) 在缰绳内");
        helper.assertTrue(!ChampionBlinkPlan.withinLeash(24.0001D), "距离 24.0001 超缰绳");
        helper.assertTrue(!ChampionBlinkPlan.withinLeash(24.5D), "距离 24.5 超缰绳");
        helper.succeed();
    }

    // ============================================================
    // 门控真值 (有存活目标 且 在缰绳内 才推进周期)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void gateTruthTable(GameTestHelper helper) {
        helper.assertTrue(ChampionBlinkPlan.shouldAdvanceCycle(true, 10.0D),
                "有目标 + 缰绳内 (10 格): 推进");
        helper.assertTrue(ChampionBlinkPlan.shouldAdvanceCycle(true, 24.0D),
                "有目标 + 缰绳边界 (24 格): 推进");
        helper.assertTrue(!ChampionBlinkPlan.shouldAdvanceCycle(true, 24.5D),
                "有目标但超缰绳 (24.5 格): 冻结");
        helper.assertTrue(!ChampionBlinkPlan.shouldAdvanceCycle(false, 10.0D),
                "无目标 (纵在缰绳内): 冻结");
        helper.assertTrue(!ChampionBlinkPlan.shouldAdvanceCycle(false, 0.0D),
                "无目标: 距离参数不参与, 恒冻结");
        helper.succeed();
    }

    // ============================================================
    // 落点候选环几何 (2-3 格环上; 背后优先; 距目标恒 2.5)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ringCandidatesOnBand(GameTestHelper helper) {
        List<double[]> candidates = ChampionBlinkPlan.ringCandidates(0.0D, 0.0D, 0.0D);
        helper.assertTrue(candidates.size() == 8, "候选环共 8 个角度");
        for (double[] c : candidates) {
            double dist = Math.sqrt(c[0] * c[0] + c[1] * c[1]);
            helper.assertTrue(dist >= 2.0D - EPS && dist <= 3.0D + EPS,
                    "候选距目标落在 [2,3] 格环上 (实测 " + dist + ")");
            helper.assertTrue(Math.abs(dist - ChampionBlinkPlan.RING_RADIUS) < EPS,
                    "候选距目标恒 = 环半径 2.5 (实测 " + dist + ")");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ringFirstCandidateAtBaseAngle(GameTestHelper helper) {
        // baseAngle=0 (指向 +X): 首候选 (背后优先, offset 0) = 目标 + (2.5, 0)。
        List<double[]> east = ChampionBlinkPlan.ringCandidates(0.0D, 0.0D, 0.0D);
        helper.assertTrue(Math.abs(east.get(0)[0] - 2.5D) < EPS, "baseAngle=0 首候选 X = +2.5");
        helper.assertTrue(Math.abs(east.get(0)[1] - 0.0D) < EPS, "baseAngle=0 首候选 Z = 0");
        // baseAngle=pi/2 (指向 +Z), 目标偏移到 (100,200): 首候选 = (100, 202.5)。
        List<double[]> north = ChampionBlinkPlan.ringCandidates(100.0D, 200.0D, Math.PI / 2.0D);
        helper.assertTrue(Math.abs(north.get(0)[0] - 100.0D) < EPS, "baseAngle=pi/2 首候选 X = 目标 X");
        helper.assertTrue(Math.abs(north.get(0)[1] - 202.5D) < EPS, "baseAngle=pi/2 首候选 Z = 目标 Z + 2.5");
        helper.succeed();
    }

    // ============================================================
    // 禁近判定 (距玩家 <1 格拒; 环候选恒不触发)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tooCloseRejection(GameTestHelper helper) {
        helper.assertTrue(ChampionBlinkPlan.tooClose(0.0D, 0.0D, 0.5D, 0.0D),
                "距玩家 0.5 格 (<1) 禁近拒");
        helper.assertTrue(!ChampionBlinkPlan.tooClose(0.0D, 0.0D, 1.0D, 0.0D),
                "距玩家 1.0 格 (=1, 非 <1) 不禁近");
        helper.assertTrue(!ChampionBlinkPlan.tooClose(0.0D, 0.0D, 1.5D, 0.0D),
                "距玩家 1.5 格 (>1) 不禁近");
        // 环候选 (2.5 格) 恒不触发禁近 —— 硬闸对合法环候选是防御性无操作。
        for (double[] c : ChampionBlinkPlan.ringCandidates(7.0D, -3.0D, 1.234D)) {
            helper.assertTrue(!ChampionBlinkPlan.tooClose(7.0D, -3.0D, c[0], c[1]),
                    "2.5 格环候选恒不禁近");
        }
        helper.succeed();
    }

    // ============================================================
    // 参数校验 (异常必须痛: 空品质 / 负累加)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void invalidArgsRejected(GameTestHelper helper) {
        boolean rejectedNullCycle = false;
        try {
            ChampionBlinkPlan.cycleTicks(null);
        } catch (IllegalArgumentException expected) {
            rejectedNullCycle = true;
        }
        helper.assertTrue(rejectedNullCycle, "周期 null 品质须抛 IllegalArgumentException");

        boolean rejectedNullReady = false;
        try {
            ChampionBlinkPlan.cycleReady(0L, null);
        } catch (IllegalArgumentException expected) {
            rejectedNullReady = true;
        }
        helper.assertTrue(rejectedNullReady, "到点判定 null 品质须抛 IllegalArgumentException");

        boolean rejectedNegativeAdvance = false;
        try {
            ChampionBlinkPlan.advanceCycle(-1L);
        } catch (IllegalArgumentException expected) {
            rejectedNegativeAdvance = true;
        }
        helper.assertTrue(rejectedNegativeAdvance, "推进负累加 tick 须抛 IllegalArgumentException");

        boolean rejectedNegativeReady = false;
        try {
            ChampionBlinkPlan.cycleReady(-1L, AffixQuality.COMMON);
        } catch (IllegalArgumentException expected) {
            rejectedNegativeReady = true;
        }
        helper.assertTrue(rejectedNegativeReady, "到点判定负累加 tick 须抛 IllegalArgumentException");
        helper.succeed();
    }
}

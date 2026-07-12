package com.miningdim.champion;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 精英怪【技能词条·视觉干扰】(Stage2; ChampionStarAffix spec 7.4 ★4 c12) 纯逻辑 GameTest (TDD)。
 *
 * 严禁触 Champions 加载路径 (compileOnly 铁律): 只断言 {@link ChampionVisualDisruptionValues} 的失明时长表 /
 * 施放周期表 / 周期推进到点判定 + 参数校验, 全部具体数值逐位断言 (删被测折算/周期判定/校验必挂)。红线 5 控制夹断
 * 数学由 {@link com.miningdim.champion.aggregate.PlayerControlAggregator} 单一权威, 其 admit/hasMinFreeWindow/
 * clampSlow 已由 ChampionGameTests/ChampionEdgeGameTests 覆盖, 此处不重复。真服 (Champions 已加载) 由
 * {@code ChampionVisualDisruptionHandler} 每秒扫近玩家冠军对攻击目标施加失明 (经控制聚合裁剪)。
 *
 * template = "empty", batch = "champion_visual_disruption"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionVisualDisruptionGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_visual_disruption";

    // ============================================================
    // 失明名义时长表 (2026-07-07 真服验收用户二调: 全档 3s = 60 tick, 品质差异保留在周期上)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void blindnessDurationPerQuality(GameTestHelper helper) {
        for (AffixQuality q : AffixQuality.values()) {
            helper.assertTrue(ChampionVisualDisruptionValues.blindnessDurationTicks(q) == 60L,
                    "视觉干扰 " + q + " = 3.0s = 60 tick (用户二调全档统一)");
        }
        // 60 tick 仍在红线 5 额度内 (7s 窗受控帽 70 tick): 单次失明不可能独自超帽。
        helper.assertTrue(60L < com.miningdim.champion.aggregate.PlayerControlAggregator.BUSY_TICK_CAP,
                "3s 失明 < 7s 窗 50% 受控帽 70 tick");
        helper.succeed();
    }

    // ============================================================
    // 施放周期表 (12/10.5/9/8/7 s = 240/210/180/160/140 tick; 5 档精确)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cyclePeriodPerQuality(GameTestHelper helper) {
        helper.assertTrue(ChampionVisualDisruptionValues.cyclePeriodTicks(AffixQuality.COMMON) == 240L,
                "视觉干扰 普通 周期 = 12s = 240 tick");
        helper.assertTrue(ChampionVisualDisruptionValues.cyclePeriodTicks(AffixQuality.UNCOMMON) == 210L,
                "视觉干扰 中级 周期 = 10.5s = 210 tick");
        helper.assertTrue(ChampionVisualDisruptionValues.cyclePeriodTicks(AffixQuality.RARE) == 180L,
                "视觉干扰 高级 周期 = 9s = 180 tick");
        helper.assertTrue(ChampionVisualDisruptionValues.cyclePeriodTicks(AffixQuality.EPIC) == 160L,
                "视觉干扰 超凡 周期 = 8s = 160 tick");
        helper.assertTrue(ChampionVisualDisruptionValues.cyclePeriodTicks(AffixQuality.LEGENDARY) == 140L,
                "视觉干扰 闪耀 周期 = 7s = 140 tick");
        helper.succeed();
    }

    // ============================================================
    // 周期推进 (扫描步进累加 -> 到点判定; 到点清零重计)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void advanceCycleStepsByScanInterval(GameTestHelper helper) {
        // 每次推进恰加一个扫描粒度 20 tick (删 +SCAN 步进则此处必挂)。
        helper.assertTrue(ChampionVisualDisruptionValues.advanceCycle(0L) == 20L, "推进一次 = +20 tick");
        helper.assertTrue(ChampionVisualDisruptionValues.advanceCycle(200L) == 220L, "推进累加 200 -> 220 tick");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cycleReadyAtPeriodBoundary(GameTestHelper helper) {
        // 闪耀周期 140 tick: 累加 139 未到点, 140 到点 (删 >= 判定退回 > 则下界必挂)。
        helper.assertTrue(!ChampionVisualDisruptionValues.cycleReady(139L, AffixQuality.LEGENDARY),
                "累加 139 < 140 未到周期");
        helper.assertTrue(ChampionVisualDisruptionValues.cycleReady(140L, AffixQuality.LEGENDARY),
                "累加 140 = 周期 到点");
        // 普通周期 240 tick: 220 未到, 240 到点 (跨品质联动周期表)。
        helper.assertTrue(!ChampionVisualDisruptionValues.cycleReady(220L, AffixQuality.COMMON),
                "累加 220 < 240 (普通) 未到周期");
        helper.assertTrue(ChampionVisualDisruptionValues.cycleReady(240L, AffixQuality.COMMON),
                "累加 240 = 240 (普通) 到点");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cycleFiresEveryPeriodViaScanStepping(GameTestHelper helper) {
        // 模拟 handler 逐扫描推进 (每步 +20, 到点清零重计): 闪耀周期 140 -> 每 7 步施放一次, 首次在第 7 步。
        AffixQuality quality = AffixQuality.LEGENDARY;
        long elapsed = 0L;
        int fires = 0;
        int firstFireStep = -1;
        for (int step = 1; step <= 21; step++) { // 21 步 = 3 个满周期
            elapsed = ChampionVisualDisruptionValues.advanceCycle(elapsed);
            if (ChampionVisualDisruptionValues.cycleReady(elapsed, quality)) {
                elapsed = 0L; // 到点清零 (周期照走不补偿)。
                fires++;
                if (firstFireStep < 0) {
                    firstFireStep = step;
                }
            }
        }
        helper.assertTrue(firstFireStep == 7, "闪耀 140tick 周期: 首次施放在第 7 扫描步 (7x20=140)");
        helper.assertTrue(fires == 3, "21 扫描步恰施放 3 次 (每 7 步一次)");
        helper.succeed();
    }

    // ============================================================
    // 参数校验 (异常必须痛: 空品质 / 负累加)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void invalidArgsRejected(GameTestHelper helper) {
        boolean rejectedNullDuration = false;
        try {
            ChampionVisualDisruptionValues.blindnessDurationTicks(null);
        } catch (IllegalArgumentException expected) {
            rejectedNullDuration = true;
        }
        helper.assertTrue(rejectedNullDuration, "失明时长 null 品质须抛 IllegalArgumentException");

        boolean rejectedNullPeriod = false;
        try {
            ChampionVisualDisruptionValues.cyclePeriodTicks(null);
        } catch (IllegalArgumentException expected) {
            rejectedNullPeriod = true;
        }
        helper.assertTrue(rejectedNullPeriod, "周期 null 品质须抛 IllegalArgumentException");

        boolean rejectedNegative = false;
        try {
            ChampionVisualDisruptionValues.advanceCycle(-1L);
        } catch (IllegalArgumentException expected) {
            rejectedNegative = true;
        }
        helper.assertTrue(rejectedNegative, "推进负累加 tick 须抛 IllegalArgumentException");
        helper.succeed();
    }
}

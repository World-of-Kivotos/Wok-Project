package com.miningdim.champion;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 精英怪批4 波1【双倍/四倍分跳 + 混沌真击飞】纯逻辑 GameTest (ChampionStarAffix spec 7.2 战斗 + 9.3 红线 6 TDD)。
 *
 * 只断言 {@link ChampionStrikeGate} 新增的纯数值/几何 (每跳系数 0.6/0.35 + 净倍率 1.2/1.4 + 跳间隔 3 + 回声跳距离
 * 资格 + 混沌击飞常量 + CLAMPED 水平缩减比等比数学)。所有断言为具体业务结果, 删被测常量/公式必挂 (禁弱校验)。真
 * 击飞的 setDeltaMovement/hurtMarked/落点守卫/控制聚合等【世界侧】结算由 {@code ChampionAttackHandler} 在受击事件 +
 * 服务端 tick 单点施加, 真服 (Champions 已加载) 验; 本类不触任何世界/实体 (纯逻辑闸), dev 触达安全。
 *
 * template = "empty", batch = "champion_multi_strike"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionMultiStrikeGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_multi_strike";
    private static final double EPS = 1.0E-9D;

    // ============================================================
    // 分跳每跳系数 + 净倍率 (双倍 0.6×2=1.2 / 四倍 0.35×4=1.4); 首跳与合计以名义 F 折算
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void strikeJumpFactorAndNetMultiplier(GameTestHelper helper) {
        // 每跳系数 (用户裁定): 双倍 0.6 / 四倍 0.35。
        helper.assertTrue(Math.abs(ChampionStrikeGate.strikeJumpFactor(AffixDef.DOUBLE_STRIKE) - 0.6D) < EPS,
                "double strike per-jump factor = 0.6");
        helper.assertTrue(Math.abs(ChampionStrikeGate.strikeJumpFactor(AffixDef.QUADRUPLE_STRIKE) - 0.35D) < EPS,
                "quadruple strike per-jump factor = 0.35");

        // 净倍率 = 每跳系数 × 跳数: 双倍 0.6×2=1.2 / 四倍 0.35×4=1.4 (跳数 5 档恒定, 任品质同值)。
        helper.assertTrue(Math.abs(ChampionStrikeGate.strikeNetMultiplier(AffixDef.DOUBLE_STRIKE, AffixQuality.COMMON) - 1.2D) < EPS,
                "double strike net multiplier = 0.6*2 = 1.2");
        helper.assertTrue(Math.abs(ChampionStrikeGate.strikeNetMultiplier(AffixDef.QUADRUPLE_STRIKE, AffixQuality.LEGENDARY) - 1.4D) < EPS,
                "quadruple strike net multiplier = 0.35*4 = 1.4");

        // 名义完整单击 F=20: 首跳 = F×系数 (双倍 12 / 四倍 7); N 跳合计 = F×净倍率 (双倍 24 / 四倍 28)。
        double f = 20.0D;
        helper.assertTrue(Math.abs(f * ChampionStrikeGate.strikeJumpFactor(AffixDef.DOUBLE_STRIKE) - 12.0D) < EPS,
                "double first jump = 20 x 0.6 = 12");
        helper.assertTrue(Math.abs(f * ChampionStrikeGate.strikeJumpFactor(AffixDef.QUADRUPLE_STRIKE) - 7.0D) < EPS,
                "quadruple first jump = 20 x 0.35 = 7");
        helper.assertTrue(Math.abs(f * ChampionStrikeGate.strikeNetMultiplier(AffixDef.DOUBLE_STRIKE, AffixQuality.RARE) - 24.0D) < EPS,
                "double 2-jump total = 20 x 1.2 = 24");
        helper.assertTrue(Math.abs(f * ChampionStrikeGate.strikeNetMultiplier(AffixDef.QUADRUPLE_STRIKE, AffixQuality.RARE) - 28.0D) < EPS,
                "quadruple 4-jump total = 20 x 1.4 = 28");

        // 契约: 非多击 def / null 抛 IAE (不静默返 0/1 掩盖误用)。
        assertThrowsIae(helper, () -> ChampionStrikeGate.strikeJumpFactor(AffixDef.BURNING),
                "strikeJumpFactor non-multi-strike def must throw IAE");
        assertThrowsIae(helper, () -> ChampionStrikeGate.strikeJumpFactor(null),
                "strikeJumpFactor null def must throw IAE");
        helper.succeed();
    }

    // ============================================================
    // 跳间隔常量 (用户裁定 3 tick)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void strikeJumpIntervalIsThreeTicks(GameTestHelper helper) {
        long interval = ChampionStrikeGate.STRIKE_JUMP_INTERVAL_TICKS;
        helper.assertTrue(interval == 3L, "strike jump interval = 3 ticks, got " + interval);
        // 四倍 3 跳余跳的调度点 = now + {3,6,9}: 用间隔常量推导, 删/改常量则位移必挂。
        long now = 100L;
        long[] expected = {103L, 106L, 109L};
        for (int i = 1; i <= 3; i++) {
            long due = now + i * ChampionStrikeGate.STRIKE_JUMP_INTERVAL_TICKS;
            helper.assertTrue(due == expected[i - 1],
                    "quad echo #" + i + " due tick = " + expected[i - 1] + ", got " + due);
        }
        helper.succeed();
    }

    // ============================================================
    // 回声跳距离资格 (欧氏距离平方 <= 6² = 36; 脱战/跑远余跳作废)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void echoJumpDistanceEligibility(GameTestHelper helper) {
        helper.assertTrue(Math.abs(ChampionStrikeGate.ECHO_JUMP_MAX_RANGE - 6.0D) < EPS,
                "echo jump max range = 6 blocks");
        // 边界含等于 (<=): 恰 6 格 (距离平方 36) 仍资格。
        helper.assertTrue(ChampionStrikeGate.echoJumpInRange(36.0D),
                "distance^2 = 36 (exactly 6 blocks) still eligible (<= boundary)");
        // 略超 6 格作废。
        helper.assertFalse(ChampionStrikeGate.echoJumpInRange(36.0001D),
                "distance^2 just over 36 is ineligible");
        // 5 格内资格 / 7 格外作废 / 重合资格。
        helper.assertTrue(ChampionStrikeGate.echoJumpInRange(25.0D), "distance^2 = 25 (5 blocks) eligible");
        helper.assertFalse(ChampionStrikeGate.echoJumpInRange(49.0D), "distance^2 = 49 (7 blocks) ineligible");
        helper.assertTrue(ChampionStrikeGate.echoJumpInRange(0.0D), "coincident (0) eligible");
        helper.succeed();
    }

    // ============================================================
    // 混沌击飞常量 + CLAMPED 水平缩减比等比数学 + 落地初速合成
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void chaosPushConstantsAndClampScale(GameTestHelper helper) {
        // 用户裁定常量: 竖直 0.5 / 水平 0.4 / 末端 3 格 / 控制 12 tick。
        helper.assertTrue(Math.abs(ChampionStrikeGate.CHAOS_PUSH_Y - 0.5D) < EPS, "chaos vertical impulse = 0.5");
        helper.assertTrue(Math.abs(ChampionStrikeGate.CHAOS_PUSH_HORIZONTAL - 0.4D) < EPS, "chaos horizontal impulse = 0.4");
        helper.assertTrue(Math.abs(ChampionStrikeGate.CHAOS_PUSH_DISTANCE - 3.0D) < EPS, "chaos predicted end distance = 3");
        long controlTicks = ChampionStrikeGate.CHAOS_CONTROL_TICKS;
        helper.assertTrue(controlTicks == 12L, "chaos control admit = 12 ticks, got " + controlTicks);

        // CLAMPED 水平缩减比 = 夹后落点距离 / 满推 3.0, 夹 [0,1]: 满推=1.0 / 半推=0.5 / 零推=0 / 超推夹 1.0。
        helper.assertTrue(Math.abs(ChampionStrikeGate.chaosClampedHorizontalScale(3.0D, 3.0D) - 1.0D) < EPS,
                "clamped dist 3.0 / full 3.0 -> scale 1.0 (full)");
        helper.assertTrue(Math.abs(ChampionStrikeGate.chaosClampedHorizontalScale(1.5D, 3.0D) - 0.5D) < EPS,
                "clamped dist 1.5 / full 3.0 -> scale 0.5 (half)");
        helper.assertTrue(Math.abs(ChampionStrikeGate.chaosClampedHorizontalScale(0.0D, 3.0D)) < EPS,
                "clamped dist 0 -> scale 0 (no horizontal push)");
        helper.assertTrue(Math.abs(ChampionStrikeGate.chaosClampedHorizontalScale(4.5D, 3.0D) - 1.0D) < EPS,
                "clamped dist beyond full clamps scale to 1.0");

        // 落地初速合成 (CLAMPED 半推, 沿纯 +X 方向): vx = dir(1) × 0.4 × 0.5 = 0.2; 竖直恒 0.5 不缩。
        double scale = ChampionStrikeGate.chaosClampedHorizontalScale(1.5D, ChampionStrikeGate.CHAOS_PUSH_DISTANCE);
        double vx = 1.0D * ChampionStrikeGate.CHAOS_PUSH_HORIZONTAL * scale;
        helper.assertTrue(Math.abs(vx - 0.2D) < EPS, "clamped half-push +X velocity = 1 x 0.4 x 0.5 = 0.2");
        helper.assertTrue(Math.abs(ChampionStrikeGate.CHAOS_PUSH_Y - 0.5D) < EPS,
                "vertical impulse unchanged by clamp (always 0.5)");

        // 脏几何量不静默掩盖: fullDistance <=0 / clampedDistance <0 抛 IAE。
        assertThrowsIae(helper, () -> ChampionStrikeGate.chaosClampedHorizontalScale(1.0D, 0.0D),
                "chaosClampedHorizontalScale fullDistance 0 must throw IAE");
        assertThrowsIae(helper, () -> ChampionStrikeGate.chaosClampedHorizontalScale(-1.0D, 3.0D),
                "chaosClampedHorizontalScale negative clampedDistance must throw IAE");
        helper.succeed();
    }

    // ---- helpers ----

    private static void assertThrowsIae(GameTestHelper helper, Runnable action, String msg) {
        boolean thrown = false;
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            thrown = true;
        }
        helper.assertTrue(thrown, msg);
    }
}

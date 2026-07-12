package com.miningdim.champion;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 精英怪【技能词条·凯撒实验型转换器 CAESAR_SWAP】(批4 波2; ChampionStarAffix spec 7.4, ★5 技能) 纯逻辑 GameTest (TDD)。
 *
 * 严禁触 Champions 加载路径 (compileOnly 铁律): 只断言 {@link ChampionCaesarSwapPlan} 的 CD 表 (5 档) / CD 推进到点 /
 * 预兆常量 / 缰绳 24 边界 / 充能门控 / 预兆取消条件真值表 (8 组合) / 落点双向安全真值表 (4 组合) + 参数校验, 全部
 * 逐位精确断言 (删被测折算/门控/取消/落点合取必挂)。逐格落点 SAFE/否裁决 (KnockbackSafetyGuard) 已由
 * {@code KnockbackSafetyGuardGameTests} 覆盖, 此处只断言"两侧都安全"的合取; 真服 (Champions 已加载) 由
 * {@code ChampionCaesarSwapHandler} 每秒扫近玩家冠军按 CD 起预兆、1s 后双向换位。
 *
 * template = "empty", batch = "champion_caesar_swap"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionCaesarSwapGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_caesar_swap";

    // ============================================================
    // 施放 CD 表 (20/17/14/12/10 s = 400/340/280/240/200 tick; 5 档精确)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cdPeriodPerQuality(GameTestHelper helper) {
        helper.assertTrue(ChampionCaesarSwapPlan.cdTicks(AffixQuality.COMMON) == 400L,
                "凯撒转换器 普通 CD = 20s = 400 tick");
        helper.assertTrue(ChampionCaesarSwapPlan.cdTicks(AffixQuality.UNCOMMON) == 340L,
                "凯撒转换器 中级 CD = 17s = 340 tick");
        helper.assertTrue(ChampionCaesarSwapPlan.cdTicks(AffixQuality.RARE) == 280L,
                "凯撒转换器 高级 CD = 14s = 280 tick");
        helper.assertTrue(ChampionCaesarSwapPlan.cdTicks(AffixQuality.EPIC) == 240L,
                "凯撒转换器 超凡 CD = 12s = 240 tick");
        helper.assertTrue(ChampionCaesarSwapPlan.cdTicks(AffixQuality.LEGENDARY) == 200L,
                "凯撒转换器 闪耀 CD = 10s = 200 tick");
        helper.succeed();
    }

    // ============================================================
    // 预兆常量 (用户裁定 1s = 20 tick)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void telegraphIsOneSecond(GameTestHelper helper) {
        // 预兆 = 用户裁定 1s: 以 plan 自身 tick/秒 基折算断言 (非硬编码 20 字面, 免与 TICKS_PER_SECOND 漂移;
        // 取非常量局部避免编译期常量折叠成 20L==20L 的恒真比较)。
        long oneSecondTicks = ChampionCaesarSwapPlan.TICKS_PER_SECOND;
        helper.assertTrue(ChampionCaesarSwapPlan.TELEGRAPH_TICKS == oneSecondTicks,
                "预兆时长 = 1s (= TICKS_PER_SECOND, 用户裁定)");
        helper.succeed();
    }

    // ============================================================
    // CD 推进 (扫描步进累加 -> 到点判定; 到点清零重计)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void advanceCycleStepsByScanInterval(GameTestHelper helper) {
        // 每次推进恰加一个扫描粒度 20 tick (删 +SCAN 步进则此处必挂)。
        helper.assertTrue(ChampionCaesarSwapPlan.advanceCycle(0L) == 20L, "推进一次 = +20 tick");
        helper.assertTrue(ChampionCaesarSwapPlan.advanceCycle(380L) == 400L, "推进累加 380 -> 400 tick");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cycleReadyAtCdBoundary(GameTestHelper helper) {
        // 闪耀 CD 200 tick: 199 未到点, 200 到点 (删 >= 判定退回 > 则下界必挂)。
        helper.assertTrue(!ChampionCaesarSwapPlan.cycleReady(199L, AffixQuality.LEGENDARY),
                "累加 199 < 200 (闪耀) 未到 CD");
        helper.assertTrue(ChampionCaesarSwapPlan.cycleReady(200L, AffixQuality.LEGENDARY),
                "累加 200 = 200 (闪耀) 到点");
        // 普通 CD 400 tick: 399 未到, 400 到点 (跨品质联动 CD 表)。
        helper.assertTrue(!ChampionCaesarSwapPlan.cycleReady(399L, AffixQuality.COMMON),
                "累加 399 < 400 (普通) 未到 CD");
        helper.assertTrue(ChampionCaesarSwapPlan.cycleReady(400L, AffixQuality.COMMON),
                "累加 400 = 400 (普通) 到点");
        helper.succeed();
    }

    // ============================================================
    // 缰绳 24 格边界 (超出冻结不耗周期 / 预兆期跑出即取消)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tetherBoundaryAt24Blocks(GameTestHelper helper) {
        // 24² = 576 恰在内; 24.5² = 600.25 超出 (删 <= 退回 < 则等距边界必挂)。
        helper.assertTrue(ChampionCaesarSwapPlan.withinTether(576.0D),
                "距离² 576 (= 24 格) 在缰绳内");
        helper.assertTrue(!ChampionCaesarSwapPlan.withinTether(600.25D),
                "距离² 600.25 (= 24.5 格) 超缰绳");
        helper.assertTrue(!ChampionCaesarSwapPlan.withinTether(577.0D),
                "距离² 577 (> 24 格) 超缰绳");
        helper.assertTrue(ChampionCaesarSwapPlan.withinTether(0.0D), "距离² 0 (贴脸) 在内");
        helper.succeed();
    }

    // ============================================================
    // 充能门控 (有存活目标 且 在缰绳内 才推进 CD)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shouldAdvanceCycleGate(GameTestHelper helper) {
        // 有目标 + 在缰绳内 (576) -> 推进。
        helper.assertTrue(ChampionCaesarSwapPlan.shouldAdvanceCycle(true, 576.0D),
                "有存活目标且距² 576 在缰绳内: 推进 CD");
        // 有目标 + 超缰绳 (600.25) -> 冻结。
        helper.assertTrue(!ChampionCaesarSwapPlan.shouldAdvanceCycle(true, 600.25D),
                "有目标但距² 600.25 超缰绳: 冻结不推进");
        // 无目标 (距离参数不参与) -> 冻结。
        helper.assertTrue(!ChampionCaesarSwapPlan.shouldAdvanceCycle(false, 0.0D),
                "无存活目标 (纵使距² 0): 冻结不推进");
        helper.succeed();
    }

    // ============================================================
    // 预兆取消条件真值表 (目标死亡/离线/跑出缰绳 任一即取消; 8 组合)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void telegraphCancelTruthTable(GameTestHelper helper) {
        // 唯一不取消: 存活 + 在线 + 在缰绳内。
        helper.assertTrue(!ChampionCaesarSwapPlan.telegraphShouldCancel(true, true, true),
                "存活+在线+在缰绳: 不取消 (预兆继续)");
        // 其余 7 组合皆取消 (任一条件破即取消)。
        helper.assertTrue(ChampionCaesarSwapPlan.telegraphShouldCancel(false, true, true),
                "死亡: 取消");
        helper.assertTrue(ChampionCaesarSwapPlan.telegraphShouldCancel(true, false, true),
                "离线: 取消");
        helper.assertTrue(ChampionCaesarSwapPlan.telegraphShouldCancel(true, true, false),
                "跑出缰绳: 取消");
        helper.assertTrue(ChampionCaesarSwapPlan.telegraphShouldCancel(false, false, true),
                "死亡+离线: 取消");
        helper.assertTrue(ChampionCaesarSwapPlan.telegraphShouldCancel(false, true, false),
                "死亡+跑出缰绳: 取消");
        helper.assertTrue(ChampionCaesarSwapPlan.telegraphShouldCancel(true, false, false),
                "离线+跑出缰绳: 取消");
        helper.assertTrue(ChampionCaesarSwapPlan.telegraphShouldCancel(false, false, false),
                "死亡+离线+跑出缰绳: 取消");
        helper.succeed();
    }

    // ============================================================
    // 落点双向安全真值表 (玩家目的格 + 冠军目的格 都 SAFE 才换; 4 组合)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bothLandingsSafeTruthTable(GameTestHelper helper) {
        helper.assertTrue(ChampionCaesarSwapPlan.bothLandingsSafe(true, true),
                "双侧落点都安全: 换位");
        helper.assertTrue(!ChampionCaesarSwapPlan.bothLandingsSafe(true, false),
                "冠军目的格不安全: 放弃");
        helper.assertTrue(!ChampionCaesarSwapPlan.bothLandingsSafe(false, true),
                "玩家目的格不安全: 放弃");
        helper.assertTrue(!ChampionCaesarSwapPlan.bothLandingsSafe(false, false),
                "双侧都不安全: 放弃");
        helper.succeed();
    }

    // ============================================================
    // 参数校验 (异常必须痛: 空品质 / 负累加 / 负距离)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void invalidArgsRejected(GameTestHelper helper) {
        helper.assertTrue(throwsIae(() -> ChampionCaesarSwapPlan.cdTicks(null)),
                "cdTicks null 品质须抛 IllegalArgumentException");
        helper.assertTrue(throwsIae(() -> ChampionCaesarSwapPlan.cycleReady(0L, null)),
                "cycleReady null 品质须抛");
        helper.assertTrue(throwsIae(() -> ChampionCaesarSwapPlan.cycleReady(-1L, AffixQuality.COMMON)),
                "cycleReady 负累加须抛");
        helper.assertTrue(throwsIae(() -> ChampionCaesarSwapPlan.advanceCycle(-1L)),
                "advanceCycle 负累加须抛");
        helper.assertTrue(throwsIae(() -> ChampionCaesarSwapPlan.withinTether(-1.0D)),
                "withinTether 负距离²须抛");
        helper.succeed();
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

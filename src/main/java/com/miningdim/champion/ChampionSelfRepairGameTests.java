package com.miningdim.champion;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 精英怪【自我修复单元】(SELF_REPAIR, spec 7.4 ★4) 读条自愈状态机纯逻辑 GameTest (TDD)。
 *
 * 严禁触 Champions 加载路径 (compileOnly 铁律): 只断言 {@link ChampionSelfRepairCycle} 三态迁移与计时 (触发阈值恰界
 * 0.50 / 读条 120tick / 跳血 20tick 对齐 / 受伤 30tick 停回窗恰界 / 近战打断进 CD / CD 500tick 恰界可再触发 / 中级档 0 不启动)
 * + {@link AffixDef#SELF_REPAIR} 品质档回血数值。全部具体业务结果逐位断言 (删被测折算/门槛/迁移必挂)。真服 (Champions
 * 已加载) 由 {@code ChampionSelfRepairHandler} 每 tick 对在册冠军定身 + 跳血 + 起读条/打断。
 *
 * template = "empty", batch = "champion_self_repair"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionSelfRepairGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_self_repair";
    private static final double EPS = 1e-6D;

    // ============================================================
    // 品质档回血数值 (FLAT HP/s: 40/0/80/150/300)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void healPerSecondByQuality(GameTestHelper helper) {
        helper.assertTrue(Math.abs(AffixDef.SELF_REPAIR.valueFor(AffixQuality.COMMON) - 40.0D) < EPS,
                "自我修复 普通 = 40 HP/s FLAT");
        helper.assertTrue(AffixDef.SELF_REPAIR.valueFor(AffixQuality.UNCOMMON) == 0.0D,
                "自我修复 中级 = 0 (前导占位, handler 防御性不启动)");
        helper.assertTrue(Math.abs(AffixDef.SELF_REPAIR.valueFor(AffixQuality.RARE) - 80.0D) < EPS,
                "自我修复 高级 = 80 HP/s");
        helper.assertTrue(Math.abs(AffixDef.SELF_REPAIR.valueFor(AffixQuality.EPIC) - 150.0D) < EPS,
                "自我修复 超凡 = 150 HP/s");
        helper.assertTrue(Math.abs(AffixDef.SELF_REPAIR.valueFor(AffixQuality.LEGENDARY) - 300.0D) < EPS,
                "自我修复 闪耀 = 300 HP/s");
        helper.succeed();
    }

    // ============================================================
    // 触发阈值 (有效血占比 ≤0.50 恰界触发)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void triggerAtFiftyPercentBoundary(GameTestHelper helper) {
        // 恰界 0.50 触发 (≤); 0.5000001 与 0.51 不触发 (>50%)。
        helper.assertTrue(new ChampionSelfRepairCycle().tryStart(0L, 0.50D, 80.0D),
                "血占比恰 0.50 -> 起读条");
        helper.assertTrue(new ChampionSelfRepairCycle().tryStart(0L, 0.49D, 80.0D),
                "血占比 0.49 -> 起读条");
        helper.assertTrue(!new ChampionSelfRepairCycle().tryStart(0L, 0.5000001D, 80.0D),
                "血占比 0.5000001 (>50%) -> 不起读条");
        helper.assertTrue(!new ChampionSelfRepairCycle().tryStart(0L, 0.51D, 80.0D),
                "血占比 0.51 -> 不起读条");
        // 起读条后状态迁 CHANNELING。
        ChampionSelfRepairCycle cycle = new ChampionSelfRepairCycle();
        cycle.tryStart(0L, 0.30D, 80.0D);
        helper.assertTrue(cycle.state() == ChampionSelfRepairCycle.State.CHANNELING, "起读条后 state=CHANNELING");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void uncommonTierZeroDoesNotStart(GameTestHelper helper) {
        // 中级档 0 前导占位: healPerSecond=0 即便血量到阈值也不起读条 (handler 防御性)。
        ChampionSelfRepairCycle cycle = new ChampionSelfRepairCycle();
        helper.assertTrue(!cycle.tryStart(0L, 0.10D, 0.0D), "healPerSecond=0 -> 不起读条");
        helper.assertTrue(!cycle.tryStart(0L, 0.10D, AffixDef.SELF_REPAIR.valueFor(AffixQuality.UNCOMMON)),
                "中级档 valueFor=0 -> 不起读条");
        helper.assertTrue(cycle.state() == ChampionSelfRepairCycle.State.IDLE, "未起读条 state 仍 IDLE");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cannotDoubleStartWhileChanneling(GameTestHelper helper) {
        ChampionSelfRepairCycle cycle = new ChampionSelfRepairCycle();
        helper.assertTrue(cycle.tryStart(0L, 0.30D, 80.0D), "首次起读条成功");
        helper.assertTrue(!cycle.tryStart(5L, 0.30D, 80.0D), "读条中不可重复起读条");
        helper.succeed();
    }

    // ============================================================
    // 读条时长 120tick + 跳血 20tick 对齐
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void channelLastsOneHundredTwentyTicks(GameTestHelper helper) {
        ChampionSelfRepairCycle cycle = new ChampionSelfRepairCycle();
        cycle.tryStart(0L, 0.30D, 80.0D);
        // elapsed 119: 仍读条中, 未读满。
        ChampionSelfRepairCycle.ChannelTick at119 = cycle.advance(119L, 80.0D);
        helper.assertTrue(!at119.completed(), "elapsed 119 未读满");
        helper.assertTrue(cycle.isChanneling(), "elapsed 119 仍读条中");
        // elapsed 120: 读满, 迁 COOLDOWN。
        ChampionSelfRepairCycle.ChannelTick at120 = cycle.advance(120L, 80.0D);
        helper.assertTrue(at120.completed(), "elapsed 120 读满");
        helper.assertTrue(cycle.state() == ChampionSelfRepairCycle.State.COOLDOWN, "读满后 state=COOLDOWN");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void healJumpsEveryTwentyTicks(GameTestHelper helper) {
        // 高级 80 HP/s: 读条内跳 tick (20/40/60/80/100/120) 各回 80, 共 6 跳 = 480; 第 6 跳 (120) 同 tick 读满。
        ChampionSelfRepairCycle cycle = new ChampionSelfRepairCycle();
        cycle.tryStart(0L, 0.30D, 80.0D);
        double total = 0.0D;
        long[] jumpTicks = {20L, 40L, 60L, 80L, 100L, 120L};
        for (long t : jumpTicks) {
            ChampionSelfRepairCycle.ChannelTick tick = cycle.advance(t, 80.0D);
            total += tick.heal();
        }
        helper.assertTrue(Math.abs(total - 480.0D) < EPS, "6 跳 x 80 = 480 总回血");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void nonJumpTicksDoNotHeal(GameTestHelper helper) {
        ChampionSelfRepairCycle cycle = new ChampionSelfRepairCycle();
        cycle.tryStart(0L, 0.30D, 80.0D);
        helper.assertTrue(cycle.advance(19L, 80.0D).heal() == 0.0D, "elapsed 19 (非跳 tick) 不回血");
        helper.assertTrue(Math.abs(cycle.advance(20L, 80.0D).heal() - 80.0D) < EPS, "elapsed 20 (跳 tick) 回 80");
        helper.assertTrue(cycle.advance(21L, 80.0D).heal() == 0.0D, "elapsed 21 (非跳 tick) 不回血");
        helper.assertTrue(cycle.advance(25L, 80.0D).heal() == 0.0D, "elapsed 25 (非跳 tick) 不回血");
        helper.succeed();
    }

    // ============================================================
    // v2 (2026-07-07 用户二调): 跳血无条件 + 读条期免伤 90% (停回窗已删)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void healJumpsUnconditionalUnderFire(GameTestHelper helper) {
        // v2 删受伤停跳: 持续火力下跳血照回 (v1 停跳使读条在压制下永远零回血, 真服首验"无回血"根因)。
        // 删 advance 的无条件跳血 (恢复任何受伤门控) -> 本断言必挂。
        ChampionSelfRepairCycle cycle = new ChampionSelfRepairCycle();
        cycle.tryStart(0L, 0.30D, 80.0D);
        helper.assertTrue(Math.abs(cycle.advance(20L, 80.0D).heal() - 80.0D) < EPS, "跳 @20 回 80 (无门控)");
        helper.assertTrue(Math.abs(cycle.advance(40L, 80.0D).heal() - 80.0D) < EPS, "跳 @40 回 80 (无门控)");
        helper.assertTrue(cycle.isChanneling(), "非近战伤害不中断读条 (打断只认近战, 在 interruptByMelee)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void channelDamageReductionExact(GameTestHelper helper) {
        // 读条期免伤 90% (用户拍板): 入伤 ×0.10 逐位精确 (删 channelIncomingDamage 的乘算必挂)。
        helper.assertTrue(Math.abs(ChampionSelfRepairCycle.CHANNEL_DAMAGE_KEEP - 0.10D) < EPS,
                "读条期入伤保留系数 = 0.10 (免伤 90%)");
        helper.assertTrue(Math.abs(ChampionSelfRepairCycle.channelIncomingDamage(100.0D) - 10.0D) < EPS,
                "入伤 100 -> 10");
        helper.assertTrue(Math.abs(ChampionSelfRepairCycle.channelIncomingDamage(7.8D) - 0.78D) < EPS,
                "入伤 7.8 -> 0.78 (地板枪单发)");
        helper.assertTrue(ChampionSelfRepairCycle.channelIncomingDamage(0.0D) == 0.0D, "0 入伤 -> 0");
        boolean threw = false;
        try {
            ChampionSelfRepairCycle.channelIncomingDamage(-1.0D);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        helper.assertTrue(threw, "负入伤抛 IllegalArgumentException");
        helper.succeed();
    }

    // ============================================================
    // 近战打断进 CD + CD 500tick 恰界可再触发
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void meleeInterruptEntersCooldown(GameTestHelper helper) {
        ChampionSelfRepairCycle cycle = new ChampionSelfRepairCycle();
        cycle.tryStart(0L, 0.30D, 80.0D);
        helper.assertTrue(cycle.interruptByMelee(50L), "读条中近战命中 -> 打断成功");
        helper.assertTrue(cycle.state() == ChampionSelfRepairCycle.State.COOLDOWN, "打断后 state=COOLDOWN");
        // CD 从打断 tick50 起算 500tick: tick549 (499) 不可再起, tick550 (500) 恰界可再起。
        helper.assertTrue(!cycle.tryStart(549L, 0.30D, 80.0D), "打断后 499tick (CD 未满) 不可再起读条");
        helper.assertTrue(cycle.tryStart(550L, 0.30D, 80.0D), "打断后恰 500tick (CD 满) 可再起读条");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void meleeInterruptOnIdleIsNoop(GameTestHelper helper) {
        // 非读条期近战命中不触发打断 (无事发生)。
        ChampionSelfRepairCycle cycle = new ChampionSelfRepairCycle();
        helper.assertTrue(!cycle.interruptByMelee(0L), "IDLE 期近战 -> 不打断");
        helper.assertTrue(cycle.state() == ChampionSelfRepairCycle.State.IDLE, "IDLE 期近战后仍 IDLE");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cooldownGatesRetriggerAfterDone(GameTestHelper helper) {
        // 读满进 CD, CD 从读满 tick120 起算 500tick: tick619 (499) 不可再起, tick620 (500) 恰界可再起。
        ChampionSelfRepairCycle cycle = new ChampionSelfRepairCycle();
        cycle.tryStart(0L, 0.30D, 80.0D);
        long[] jumpTicks = {20L, 40L, 60L, 80L, 100L, 120L};
        for (long t : jumpTicks) {
            cycle.advance(t, 80.0D);
        }
        helper.assertTrue(cycle.state() == ChampionSelfRepairCycle.State.COOLDOWN, "读满后 state=COOLDOWN");
        helper.assertTrue(!cycle.tryStart(619L, 0.30D, 80.0D), "读满后 499tick (CD 未满) 不可再起读条");
        helper.assertTrue(cycle.tryStart(620L, 0.30D, 80.0D), "读满后恰 500tick (CD 满) 可再起读条");
        helper.assertTrue(cycle.state() == ChampionSelfRepairCycle.State.CHANNELING, "再起后 state=CHANNELING");
        helper.succeed();
    }

    // ============================================================
    // 参数校验 (异常必须痛) + 误用防护
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void invalidArgsThrow(GameTestHelper helper) {
        assertThrowsIae(helper, () -> new ChampionSelfRepairCycle().tryStart(0L, -0.1D, 80.0D),
                "负血占比 tryStart 抛 IAE");
        assertThrowsIae(helper, () -> new ChampionSelfRepairCycle().tryStart(0L, Double.NaN, 80.0D),
                "NaN 血占比 tryStart 抛 IAE");
        assertThrowsIae(helper, () -> new ChampionSelfRepairCycle().tryStart(0L, 0.30D, -1.0D),
                "负 healPerSecond tryStart 抛 IAE");
        // 非读条期 advance 是误用, 抛 IllegalStateException。
        boolean ise = false;
        try {
            new ChampionSelfRepairCycle().advance(10L, 80.0D);
        } catch (IllegalStateException expected) {
            ise = true;
        }
        helper.assertTrue(ise, "IDLE 期 advance 抛 IllegalStateException (误用不掩盖)");
        helper.succeed();
    }

    private static void assertThrowsIae(GameTestHelper helper, Runnable action, String message) {
        boolean thrown = false;
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            thrown = true;
        }
        helper.assertTrue(thrown, message);
    }
}

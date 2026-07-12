package com.miningdim.champion;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 超速移动力竭窗状态机纯逻辑 GameTest (ChampionStarAffix spec 7.3 超速移动; Stage2 批2)。
 *
 * 断言 {@link OverdriveCycle} 三相边界逐 tick 精确 (加速[0,80)/力竭[80,180)/常态[180,240) 模 240 循环)、
 * 各相移速修饰值 (加速 = 品质档 25~85% / 力竭 = -50% / 常态 = 0)、红线"力竭时长 ≥ 加速段"、失去目标宽限。
 * 删相位推导/删力竭减速/改相位次序必挂。modifier 施加与战斗门控接线由 {@code ChampionSelfEffectHandler}
 * 负责 (真服验, 诊断日志 od= 字段对账)。
 *
 * template = "empty", batch = "champion_overdrive"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionOverdriveGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_overdrive";
    private static final double EPS = 1e-9D;

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void phaseBoundariesAreExact(GameTestHelper helper) {
        // 加速 [0,80): 首尾 tick。
        helper.assertTrue(OverdriveCycle.phaseAt(0L) == OverdriveCycle.Phase.SURGE, "t=0 加速");
        helper.assertTrue(OverdriveCycle.phaseAt(79L) == OverdriveCycle.Phase.SURGE, "t=79 仍加速");
        // 力竭 [80,180): 边界翻转 + 首尾。
        helper.assertTrue(OverdriveCycle.phaseAt(80L) == OverdriveCycle.Phase.EXHAUST, "t=80 入力竭");
        helper.assertTrue(OverdriveCycle.phaseAt(179L) == OverdriveCycle.Phase.EXHAUST, "t=179 仍力竭");
        // 常态 [180,240)。
        helper.assertTrue(OverdriveCycle.phaseAt(180L) == OverdriveCycle.Phase.NORMAL, "t=180 入常态");
        helper.assertTrue(OverdriveCycle.phaseAt(239L) == OverdriveCycle.Phase.NORMAL, "t=239 仍常态");
        // 模 240 循环: 第二圈边界与首圈一致。
        helper.assertTrue(OverdriveCycle.phaseAt(240L) == OverdriveCycle.Phase.SURGE, "t=240 回加速 (循环)");
        helper.assertTrue(OverdriveCycle.phaseAt(240L + 80L) == OverdriveCycle.Phase.EXHAUST, "t=320 第二圈力竭");
        helper.assertTrue(OverdriveCycle.phaseAt(240L * 100L + 200L) == OverdriveCycle.Phase.NORMAL,
                "t=24200 第 101 圈常态");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void speedModifierPerQualityExact(GameTestHelper helper) {
        // 加速相 = 品质档 (2026-07-07 真服手感二调: +100/130/160/200/250%, 冲刺 2.0~3.5 倍速真突进)。
        helper.assertTrue(Math.abs(OverdriveCycle.speedModifier(OverdriveCycle.Phase.SURGE, AffixQuality.COMMON)
                - 1.00D) < EPS, "加速 普通 = +100%");
        helper.assertTrue(Math.abs(OverdriveCycle.speedModifier(OverdriveCycle.Phase.SURGE, AffixQuality.RARE)
                - 1.60D) < EPS, "加速 高级 = +160%");
        helper.assertTrue(Math.abs(OverdriveCycle.speedModifier(OverdriveCycle.Phase.SURGE, AffixQuality.LEGENDARY)
                - 2.50D) < EPS, "加速 闪耀 = +250%");
        // 力竭相 = 硬减速 -50%, 与品质无关 (红线下限)。
        helper.assertTrue(Math.abs(OverdriveCycle.speedModifier(OverdriveCycle.Phase.EXHAUST, AffixQuality.COMMON)
                - (-0.50D)) < EPS, "力竭 普通 = -50%");
        helper.assertTrue(Math.abs(OverdriveCycle.speedModifier(OverdriveCycle.Phase.EXHAUST, AffixQuality.LEGENDARY)
                - (-0.50D)) < EPS, "力竭 闪耀 = -50% (不随品质减轻)");
        // 常态 = 0 (handler 摘 modifier)。
        helper.assertTrue(OverdriveCycle.speedModifier(OverdriveCycle.Phase.NORMAL, AffixQuality.EPIC) == 0.0D,
                "常态 = 0");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void exhaustWindowNotShorterThanSurge(GameTestHelper helper) {
        // 红线 (spec 7.3): 力竭窗时长 ≥ 加速段 —— 风筝反制必须成立, 改短力竭必挂。
        helper.assertTrue(OverdriveCycle.EXHAUST_TICKS >= OverdriveCycle.SURGE_TICKS,
                "力竭 " + OverdriveCycle.EXHAUST_TICKS + " >= 加速 " + OverdriveCycle.SURGE_TICKS);
        // 力竭是硬减速 ≥50% (负号 + 幅度下限; 经 speedModifier 取值防常量被调松)。
        helper.assertTrue(OverdriveCycle.speedModifier(OverdriveCycle.Phase.EXHAUST, AffixQuality.COMMON) <= -0.50D,
                "力竭减速幅度 >= 50%");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void targetGraceGatesCycle(GameTestHelper helper) {
        // 从未索敌 = 宽限恒过期 (无战斗不循环)。
        helper.assertTrue(OverdriveCycle.targetGraceExpired(1000L, Long.MIN_VALUE), "从未索敌恒过期");
        // 宽限窗内 (<=200 tick) 不过期: 短暂丢目标不重置循环。
        helper.assertTrue(!OverdriveCycle.targetGraceExpired(1000L, 900L), "丢目标 100 tick 未过期");
        helper.assertTrue(!OverdriveCycle.targetGraceExpired(1000L, 800L), "丢目标恰 200 tick 未过期 (闭区间)");
        // 超窗过期: 201 tick。
        helper.assertTrue(OverdriveCycle.targetGraceExpired(1000L, 799L), "丢目标 201 tick 过期");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void negativeAnchorDeltaThrows(GameTestHelper helper) {
        // 锚点在未来属调用方 bug: 抛不掩盖 (异常必须痛)。
        boolean thrown = false;
        try {
            OverdriveCycle.phaseAt(-1L);
        } catch (IllegalArgumentException expected) {
            thrown = true;
        }
        helper.assertTrue(thrown, "负 tick 差抛 IllegalArgumentException");
        helper.succeed();
    }
}

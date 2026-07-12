package com.miningdim.champion;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 精英怪【技能词条·利刃华尔兹 BLADE_WALTZ】(批4 波2; ChampionStarAffix spec 7.4 连段突袭) 纯逻辑 GameTest (TDD)。
 *
 * 严禁触 Champions 加载路径 (compileOnly 铁律): 只断言 {@link ChampionBladeWaltzPlan} 的 突袭次数 5 档 / 每击 %maxHP
 * (连段帽均分, 合计恰 60% 容差 1e-9) / 每击伤 = pct×maxHP / 时序常量 (段间隔 10t/CD 600t/预兆 30t) / CD 就绪边界 /
 * 缰绳 24 边界 / 中止 12 边界 / 落点环几何 (1.5-2 格双环 × 8 向 = 16 候选) + 参数校验, 全部逐位精确断言 (删被测折算/
 * 几何/门控/中止必挂)。落点安全裁决 (KnockbackSafetyGuard) 已由 {@code KnockbackSafetyGuardGameTests} 覆盖, 此处不
 * 重复; 真服 (Champions 已加载) 由 {@code ChampionBladeWaltzHandler} 每秒扫近玩家冠军按 CD 起手预兆 + 逐段瞬移突袭。
 *
 * template = "empty", batch = "champion_blade_waltz"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionBladeWaltzGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_blade_waltz";

    /** 数值/几何断言容差: 浮点误差远小于此 (连段帽守恒 N×(0.60/N) 的舍入误差量级 ~1e-14)。 */
    private static final double EPS = 1.0e-9D;

    // ============================================================
    // 突袭次数 5 档 (3/4/5/6/7 = 普通/中级/高级/超凡/闪耀)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void strikeCountPerQuality(GameTestHelper helper) {
        helper.assertTrue(ChampionBladeWaltzPlan.strikeCount(AffixQuality.COMMON) == 3, "利刃 普通 突袭 3 次");
        helper.assertTrue(ChampionBladeWaltzPlan.strikeCount(AffixQuality.UNCOMMON) == 4, "利刃 中级 突袭 4 次");
        helper.assertTrue(ChampionBladeWaltzPlan.strikeCount(AffixQuality.RARE) == 5, "利刃 高级 突袭 5 次");
        helper.assertTrue(ChampionBladeWaltzPlan.strikeCount(AffixQuality.EPIC) == 6, "利刃 超凡 突袭 6 次");
        helper.assertTrue(ChampionBladeWaltzPlan.strikeCount(AffixQuality.LEGENDARY) == 7, "利刃 闪耀 突袭 7 次");
        helper.succeed();
    }

    // ============================================================
    // 每击 %maxHP = 连段帽 / N (整套合计恰 = 连段帽 60%)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void perStrikePctIsCapDividedByStrikeCount(GameTestHelper helper) {
        double cap = ChampionRedlines.COMBO_TOTAL_CAP_PCT; // 0.60 单一权威。
        for (AffixQuality q : AffixQuality.values()) {
            int n = ChampionBladeWaltzPlan.strikeCount(q);
            double expected = cap / n;
            helper.assertTrue(Math.abs(ChampionBladeWaltzPlan.perStrikePct(q) - expected) < EPS,
                    "利刃 " + q + " 每击 pct = 0.60/" + n + " = " + expected
                            + " 实测 " + ChampionBladeWaltzPlan.perStrikePct(q));
        }
        // 具体档位钉死: 普通 3 次 = 0.20/击; 闪耀 7 次 ≈ 0.08571/击 (删均分退回定值必挂)。
        helper.assertTrue(Math.abs(ChampionBladeWaltzPlan.perStrikePct(AffixQuality.COMMON) - 0.20D) < EPS,
                "利刃 普通 每击 0.20 maxHP");
        helper.assertTrue(Math.abs(ChampionBladeWaltzPlan.perStrikePct(AffixQuality.LEGENDARY) - (0.60D / 7.0D)) < EPS,
                "利刃 闪耀 每击 0.60/7 maxHP");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void comboTotalPctConservedAtCap(GameTestHelper helper) {
        double cap = ChampionRedlines.COMBO_TOTAL_CAP_PCT;
        // 整套 N 击合计恒 = 连段帽 60% (每档独立验; 删连段帽均分口径则某档合计偏离必挂)。
        for (AffixQuality q : AffixQuality.values()) {
            int n = ChampionBladeWaltzPlan.strikeCount(q);
            double total = n * ChampionBladeWaltzPlan.perStrikePct(q);
            helper.assertTrue(Math.abs(total - cap) < EPS,
                    "利刃 " + q + " 整套 " + n + " 击合计 = " + cap + " 实测 " + total);
        }
        helper.succeed();
    }

    // ============================================================
    // 每击伤 = perStrikePct × 目标 maxHP (整套合计 = 60% × maxHP)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void perStrikeDamageScalesWithMaxHealth(GameTestHelper helper) {
        // 普通 3 次, maxHp 100: 每击 0.20×100 = 20.0; 整套 3×20 = 60 = 0.60×100。
        helper.assertTrue(Math.abs(ChampionBladeWaltzPlan.perStrikeDamage(AffixQuality.COMMON, 100.0D) - 20.0D) < EPS,
                "普通 每击伤 = 20 (maxHp 100)");
        // 超凡 6 次, maxHp 120: 每击 0.10×120 = 12.0; 整套 6×12 = 72 = 0.60×120。
        helper.assertTrue(Math.abs(ChampionBladeWaltzPlan.perStrikeDamage(AffixQuality.EPIC, 120.0D) - 12.0D) < EPS,
                "超凡 每击伤 = 12 (maxHp 120)");
        // 闪耀 7 次, maxHp 140: 每击 0.60/7×140 = 12.0; 整套 7×12 = 84 = 0.60×140。
        helper.assertTrue(Math.abs(ChampionBladeWaltzPlan.perStrikeDamage(AffixQuality.LEGENDARY, 140.0D) - 12.0D) < EPS,
                "闪耀 每击伤 = 12 (maxHp 140)");
        // 整套合计恒 = 连段帽 × maxHP (跨档 + 随机化 maxHp)。
        double cap = ChampionRedlines.COMBO_TOTAL_CAP_PCT;
        double[] maxHps = {20.0D, 80.0D, 137.0D, 500.0D};
        for (AffixQuality q : AffixQuality.values()) {
            int n = ChampionBladeWaltzPlan.strikeCount(q);
            for (double maxHp : maxHps) {
                double total = n * ChampionBladeWaltzPlan.perStrikeDamage(q, maxHp);
                helper.assertTrue(Math.abs(total - cap * maxHp) < 1.0e-6D,
                        "利刃 " + q + " 整套伤合计 = " + (cap * maxHp) + " (maxHp " + maxHp + ") 实测 " + total);
            }
        }
        helper.succeed();
    }

    // ============================================================
    // 时序常量 (段间隔 10t / CD 600t / 预兆 30t)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void timingConstants(GameTestHelper helper) {
        helper.assertTrue(ChampionBladeWaltzPlan.STRIKE_INTERVAL_TICKS == 10L, "段间隔 = 0.5s = 10 tick");
        helper.assertTrue(ChampionBladeWaltzPlan.COOLDOWN_TICKS == 600L, "CD = 30s = 600 tick (全档)");
        helper.assertTrue(ChampionBladeWaltzPlan.TELEGRAPH_TICKS == 30L, "预兆 = 1.5s = 30 tick");
        helper.succeed();
    }

    // ============================================================
    // CD 就绪边界 (从未施放立即就绪; 满 600t 就绪)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cooldownReadyBoundary(GameTestHelper helper) {
        // 从未施放 (MIN_VALUE 锚): 立即就绪 (删 MIN_VALUE 特判则首次施放被 600t 阻塞必挂)。
        helper.assertTrue(ChampionBladeWaltzPlan.cooldownReady(0L, Long.MIN_VALUE), "从未施放: 立即就绪");
        helper.assertTrue(ChampionBladeWaltzPlan.cooldownReady(999999L, Long.MIN_VALUE), "从未施放 (大 nowTick): 就绪");
        // 上次结束 tick 0: 599 未满 CD, 600 满 CD (删 >= 退回 > 则等距边界必挂)。
        helper.assertTrue(!ChampionBladeWaltzPlan.cooldownReady(599L, 0L), "距上次结束 599 < 600: 冷却中");
        helper.assertTrue(ChampionBladeWaltzPlan.cooldownReady(600L, 0L), "距上次结束 600 = 600: 就绪");
        helper.assertTrue(ChampionBladeWaltzPlan.cooldownReady(1200L, 0L), "距上次结束 1200: 就绪");
        helper.succeed();
    }

    // ============================================================
    // 缰绳 24 格边界 (超出不起手)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tetherBoundaryAt24Blocks(GameTestHelper helper) {
        // 24² = 576 恰在内; 577 超出 (删 <= 退回 < 则等距边界必挂)。
        helper.assertTrue(ChampionBladeWaltzPlan.withinTether(576.0D), "距离² 576 (= 24 格) 在缰绳内");
        helper.assertTrue(!ChampionBladeWaltzPlan.withinTether(577.0D), "距离² 577 (> 24 格) 超缰绳");
        helper.assertTrue(ChampionBladeWaltzPlan.withinTether(0.0D), "距离² 0 (贴脸) 在内");
        helper.assertTrue(!ChampionBladeWaltzPlan.withinTether(1000.0D), "距离² 1000 超缰绳");
        helper.succeed();
    }

    // ============================================================
    // 中止 12 格边界 (突袭中目标 >12 格中止; 恰 12 格不中止)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void abortBoundaryAt12Blocks(GameTestHelper helper) {
        // 12² = 144 恰不中止 (仍在追击范围); 145 中止 (删 > 退回 >= 则等距边界翻转必挂)。
        helper.assertTrue(!ChampionBladeWaltzPlan.shouldAbort(144.0D), "距离² 144 (= 12 格): 不中止");
        helper.assertTrue(ChampionBladeWaltzPlan.shouldAbort(145.0D), "距离² 145 (> 12 格): 中止");
        helper.assertTrue(!ChampionBladeWaltzPlan.shouldAbort(0.0D), "距离² 0 (贴脸): 不中止");
        helper.assertTrue(ChampionBladeWaltzPlan.shouldAbort(576.0D), "距离² 576 (24 格): 中止");
        helper.succeed();
    }

    // ============================================================
    // 落点环几何 (1.5-2 格双环 × 8 向 = 16 候选)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void strikeCandidatesRingGeometry(GameTestHelper helper) {
        double tx = 0.0D;
        double ty = 64.0D;
        double tz = 0.0D;
        List<ChampionBladeWaltzPlan.Landing> candidates = ChampionBladeWaltzPlan.strikeCandidates(tx, ty, tz);

        helper.assertTrue(candidates.size() == 16, "2 环半径 × 8 向 = 16 个候选落点");

        int innerCount = 0; // 半径 1.5
        int outerCount = 0; // 半径 2.0
        for (ChampionBladeWaltzPlan.Landing c : candidates) {
            double dHoriz = Math.sqrt((c.x() - tx) * (c.x() - tx) + (c.z() - tz) * (c.z() - tz));
            helper.assertTrue(Math.abs(c.y() - ty) < EPS, "候选 Y 恒取目标脚下");
            boolean isInner = Math.abs(dHoriz - 1.5D) < EPS;
            boolean isOuter = Math.abs(dHoriz - 2.0D) < EPS;
            helper.assertTrue(isInner || isOuter, "候选水平位移须恰为 1.5 或 2.0 格, 实测 " + dHoriz);
            if (isInner) {
                innerCount++;
            }
            if (isOuter) {
                outerCount++;
            }
        }
        helper.assertTrue(innerCount == 8, "内环 1.5 格恰 8 个候选 (每 45° 一向)");
        helper.assertTrue(outerCount == 8, "外环 2.0 格恰 8 个候选 (每 45° 一向)");

        // 偏好序钉死: 首候选 = 外环 2.0 正 +X (angle 0); 第 9 候选 (index 8) = 内环 1.5 正 +X。
        assertPoint(helper, candidates.get(0), tx + 2.0D, ty, tz, "候选[0] = 外环 2.0 格 +X 向 (外档优先)");
        assertPoint(helper, candidates.get(8), tx + 1.5D, ty, tz, "候选[8] = 内环 1.5 格 +X 向");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void strikeCandidatesRecenterOnTarget(GameTestHelper helper) {
        // 环心随目标移动 (段间目标移动逐段重选): 环心平移则全候选平移同量。
        double ty = 70.0D;
        List<ChampionBladeWaltzPlan.Landing> at0 = ChampionBladeWaltzPlan.strikeCandidates(0.0D, ty, 0.0D);
        List<ChampionBladeWaltzPlan.Landing> at5 = ChampionBladeWaltzPlan.strikeCandidates(5.0D, ty, -3.0D);
        helper.assertTrue(at0.size() == at5.size() && at0.size() == 16, "两环心候选数一致 (16)");
        for (int i = 0; i < at0.size(); i++) {
            helper.assertTrue(Math.abs((at5.get(i).x() - at0.get(i).x()) - 5.0D) < EPS,
                    "候选[" + i + "] X 随环心 +5 平移");
            helper.assertTrue(Math.abs((at5.get(i).z() - at0.get(i).z()) - (-3.0D)) < EPS,
                    "候选[" + i + "] Z 随环心 -3 平移");
        }
        helper.succeed();
    }

    // ============================================================
    // 参数校验 (异常必须痛: 空品质 / 非正 maxHp / 负距离)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void invalidArgsRejected(GameTestHelper helper) {
        helper.assertTrue(throwsIae(() -> ChampionBladeWaltzPlan.strikeCount(null)),
                "strikeCount null 品质须抛 IllegalArgumentException");
        helper.assertTrue(throwsIae(() -> ChampionBladeWaltzPlan.perStrikePct(null)),
                "perStrikePct null 品质须抛");
        helper.assertTrue(throwsIae(() -> ChampionBladeWaltzPlan.perStrikeDamage(null, 100.0D)),
                "perStrikeDamage null 品质须抛");
        helper.assertTrue(throwsIae(() -> ChampionBladeWaltzPlan.perStrikeDamage(AffixQuality.COMMON, 0.0D)),
                "perStrikeDamage maxHp=0 须抛");
        helper.assertTrue(throwsIae(() -> ChampionBladeWaltzPlan.perStrikeDamage(AffixQuality.COMMON, -1.0D)),
                "perStrikeDamage 负 maxHp 须抛");
        helper.assertTrue(throwsIae(() -> ChampionBladeWaltzPlan.withinTether(-1.0D)),
                "withinTether 负距离² 须抛");
        helper.assertTrue(throwsIae(() -> ChampionBladeWaltzPlan.shouldAbort(-1.0D)),
                "shouldAbort 负距离² 须抛");
        helper.succeed();
    }

    // ---- 私有断言辅助 ----

    private static void assertPoint(GameTestHelper helper, ChampionBladeWaltzPlan.Landing c,
                                    double ex, double ey, double ez, String msg) {
        helper.assertTrue(Math.abs(c.x() - ex) < EPS && Math.abs(c.y() - ey) < EPS && Math.abs(c.z() - ez) < EPS,
                msg + " (期望 " + ex + "," + ey + "," + ez + " 实测 " + c.x() + "," + c.y() + "," + c.z() + ")");
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

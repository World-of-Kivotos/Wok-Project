package com.miningdim.champion;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 精英怪【技能词条·电磁蓄力 ELECTRO_CHARGE】(批4 波2; ChampionStarAffix spec 7.4 可躲型单点 AOE) 纯逻辑 GameTest (TDD)。
 *
 * 严禁触 Champions 加载路径 (compileOnly 铁律): 只断言 {@link ChampionElectroChargePlan} 的施放冷却周期表 (5 档 tick) /
 * 周期推进到点 / 单发 AOE 百分比 (5 档) 与折算伤害 / AOE 半径 3.5 边界 (平方距离) / 缰绳 24 边界 / 蓄力 40t 常量 /
 * 落点环 24 点几何 + 参数校验, 全部逐位精确断言 (删被测折算/几何/边界必挂)。伤害下发/AABB 扫玩家/免疫缓冲 grant/
 * 蓄力与爆点表现由 {@code ChampionElectroChargeHandler} 施加, 真服 (Champions 已加载) 验收, 此处不涉世界。
 *
 * template = "empty", batch = "champion_electro_charge"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionElectroChargeGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_electro_charge";

    /** 数值/几何断言容差 (浮点误差远小于此)。 */
    private static final double EPS = 1.0e-9D;

    // ============================================================
    // 施放冷却周期表 (14/13/12/11/10 s = 280/260/240/220/200 tick; 5 档精确)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cyclePeriodPerQuality(GameTestHelper helper) {
        helper.assertTrue(ChampionElectroChargePlan.cycleTicks(AffixQuality.COMMON) == 280L,
                "电磁蓄力 普通 周期 = 14s = 280 tick");
        helper.assertTrue(ChampionElectroChargePlan.cycleTicks(AffixQuality.UNCOMMON) == 260L,
                "电磁蓄力 中级 周期 = 13s = 260 tick");
        helper.assertTrue(ChampionElectroChargePlan.cycleTicks(AffixQuality.RARE) == 240L,
                "电磁蓄力 高级 周期 = 12s = 240 tick");
        helper.assertTrue(ChampionElectroChargePlan.cycleTicks(AffixQuality.EPIC) == 220L,
                "电磁蓄力 超凡 周期 = 11s = 220 tick");
        helper.assertTrue(ChampionElectroChargePlan.cycleTicks(AffixQuality.LEGENDARY) == 200L,
                "电磁蓄力 闪耀 周期 = 10s = 200 tick");
        helper.succeed();
    }

    // ============================================================
    // 冷却推进 (扫描步进累加 -> 到点判定)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void advanceCycleStepsByScanInterval(GameTestHelper helper) {
        // 每次推进恰加一个扫描粒度 20 tick (删 +SCAN 步进则此处必挂)。
        helper.assertTrue(ChampionElectroChargePlan.advanceCycle(0L) == 20L, "推进一次 = +20 tick");
        helper.assertTrue(ChampionElectroChargePlan.advanceCycle(260L) == 280L, "推进累加 260 -> 280 tick");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cycleReadyAtPeriodBoundary(GameTestHelper helper) {
        // 闪耀周期 200 tick: 199 未到点, 200 到点 (删 >= 判定退回 > 则下界必挂)。
        helper.assertTrue(!ChampionElectroChargePlan.cycleReady(199L, AffixQuality.LEGENDARY),
                "累加 199 < 200 (闪耀) 未到周期");
        helper.assertTrue(ChampionElectroChargePlan.cycleReady(200L, AffixQuality.LEGENDARY),
                "累加 200 = 200 (闪耀) 到点");
        // 普通周期 280 tick: 279 未到, 280 到点 (跨品质联动周期表)。
        helper.assertTrue(!ChampionElectroChargePlan.cycleReady(279L, AffixQuality.COMMON),
                "累加 279 < 280 (普通) 未到周期");
        helper.assertTrue(ChampionElectroChargePlan.cycleReady(280L, AffixQuality.COMMON),
                "累加 280 = 280 (普通) 到点");
        helper.succeed();
    }

    // ============================================================
    // 蓄力时长常量 (用户裁定 2s = 40 tick)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void chargeDurationIs40Ticks(GameTestHelper helper) {
        // 经局部变量比较, 避免常量内联成字面量自比 (IDE identical-expressions); 改常量即挂。
        long chargeTicks = ChampionElectroChargePlan.CHARGE_TICKS;
        helper.assertTrue(chargeTicks == 40L, "蓄力时长 = 2s = 40 tick");
        helper.succeed();
    }

    // ============================================================
    // 单发 AOE 百分比 (0.18/0.26/0.36/0.46/0.55 × 各玩家 maxHP; 5 档精确)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void aoeFractionPerQuality(GameTestHelper helper) {
        assertClose(helper, ChampionElectroChargePlan.aoeFraction(AffixQuality.COMMON), 0.18D,
                "普通 单发 AOE = 18% maxHP");
        assertClose(helper, ChampionElectroChargePlan.aoeFraction(AffixQuality.UNCOMMON), 0.26D,
                "中级 单发 AOE = 26% maxHP");
        assertClose(helper, ChampionElectroChargePlan.aoeFraction(AffixQuality.RARE), 0.36D,
                "高级 单发 AOE = 36% maxHP");
        assertClose(helper, ChampionElectroChargePlan.aoeFraction(AffixQuality.EPIC), 0.46D,
                "超凡 单发 AOE = 46% maxHP");
        assertClose(helper, ChampionElectroChargePlan.aoeFraction(AffixQuality.LEGENDARY), 0.55D,
                "闪耀 单发 AOE = 55% maxHP");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void aoeDamageScalesWithMaxHp(GameTestHelper helper) {
        // 各玩家按自身 maxHP 结算: 超凡 46% × 100 血 = 46; 普通 18% × 80 血 = 14.4; 闪耀 55% × 200 血 = 110。
        assertClose(helper, ChampionElectroChargePlan.aoeDamage(AffixQuality.EPIC, 100.0D), 46.0D,
                "超凡对 100 血玩家 = 46 名义伤");
        assertClose(helper, ChampionElectroChargePlan.aoeDamage(AffixQuality.COMMON, 80.0D), 14.4D,
                "普通对 80 血玩家 = 14.4 名义伤");
        assertClose(helper, ChampionElectroChargePlan.aoeDamage(AffixQuality.LEGENDARY, 200.0D), 110.0D,
                "闪耀对 200 血玩家 = 110 名义伤");
        helper.succeed();
    }

    // ============================================================
    // AOE 半径 3.5 边界 (平方距离; 3.5 含, 3.51 拒)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void aoeRadiusBoundaryAt3Point5(GameTestHelper helper) {
        // 3.5² = 12.25 恰在内; 3.51² = 12.3201 拒 (删 <= 退回 < 则等距边界必挂)。
        helper.assertTrue(ChampionElectroChargePlan.withinAoe(3.5D * 3.5D),
                "距² 12.25 (= 3.5 格) 在 AOE 内");
        helper.assertTrue(!ChampionElectroChargePlan.withinAoe(3.51D * 3.51D),
                "距² 12.3201 (> 3.5 格) 出 AOE");
        helper.assertTrue(ChampionElectroChargePlan.withinAoe(0.0D), "距² 0 (落点原点) 在内");
        helper.assertTrue(!ChampionElectroChargePlan.withinAoe(20.0D), "距² 20 (远) 出 AOE");
        helper.succeed();
    }

    // ============================================================
    // 缰绳 24 格边界 (超出冻结不耗周期)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tetherBoundaryAt24Blocks(GameTestHelper helper) {
        // 24² = 576 恰在内; 577 超出 (删 <= 退回 < 则等距边界必挂)。
        helper.assertTrue(ChampionElectroChargePlan.withinTether(576.0D),
                "距² 576 (= 24 格) 在缰绳内");
        helper.assertTrue(!ChampionElectroChargePlan.withinTether(577.0D),
                "距² 577 (> 24 格) 超缰绳");
        helper.assertTrue(ChampionElectroChargePlan.withinTether(0.0D), "距² 0 (贴脸) 在内");
        helper.assertTrue(!ChampionElectroChargePlan.withinTether(1000.0D), "距² 1000 超缰绳");
        helper.succeed();
    }

    // ============================================================
    // 落点环几何 (圆周 24 点, 各点恰在半径 3.5 圆周上)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ringPointsGeometry(GameTestHelper helper) {
        double cx = 5.0D;
        double cz = -3.0D;
        List<double[]> ring = ChampionElectroChargePlan.ringPoints(cx, cz);
        // 经局部变量比较, 避免常量内联成字面量自比 (IDE identical-expressions); 改常量即挂。
        int ringPointCount = ChampionElectroChargePlan.RING_POINT_COUNT;
        helper.assertTrue(ringPointCount == 24, "环描点数常量 = 24");
        helper.assertTrue(ring.size() == ringPointCount, "落点环 = 24 点");
        // 每点恰在半径 3.5 圆周上 (删半径缩放/角度均分必挂)。
        for (double[] p : ring) {
            double dist = Math.sqrt((p[0] - cx) * (p[0] - cx) + (p[1] - cz) * (p[1] - cz));
            helper.assertTrue(Math.abs(dist - ChampionElectroChargePlan.AOE_RADIUS) < EPS,
                    "环点恰在半径 3.5 圆周上, 实测 " + dist);
        }
        // 首点 (角 0) = (cx + 3.5, cz) —— cos0=1, sin0=0。
        double[] first = ring.get(0);
        helper.assertTrue(Math.abs(first[0] - (cx + 3.5D)) < EPS && Math.abs(first[1] - cz) < EPS,
                "首点 = (cx+3.5, cz), 实测 (" + first[0] + "," + first[1] + ")");
        helper.succeed();
    }

    // ============================================================
    // 参数校验 (异常必须痛: 空品质 / 负累加 / 负距离 / 非正 maxHp)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void invalidArgsRejected(GameTestHelper helper) {
        helper.assertTrue(throwsIae(() -> ChampionElectroChargePlan.cycleTicks(null)),
                "cycleTicks null 品质须抛 IllegalArgumentException");
        helper.assertTrue(throwsIae(() -> ChampionElectroChargePlan.cycleReady(0L, null)),
                "cycleReady null 品质须抛");
        helper.assertTrue(throwsIae(() -> ChampionElectroChargePlan.cycleReady(-1L, AffixQuality.COMMON)),
                "cycleReady 负累加须抛");
        helper.assertTrue(throwsIae(() -> ChampionElectroChargePlan.advanceCycle(-1L)),
                "advanceCycle 负累加须抛");
        helper.assertTrue(throwsIae(() -> ChampionElectroChargePlan.aoeFraction(null)),
                "aoeFraction null 品质须抛");
        helper.assertTrue(throwsIae(() -> ChampionElectroChargePlan.aoeDamage(null, 100.0D)),
                "aoeDamage null 品质须抛");
        helper.assertTrue(throwsIae(() -> ChampionElectroChargePlan.aoeDamage(AffixQuality.COMMON, 0.0D)),
                "aoeDamage maxHp=0 须抛");
        helper.assertTrue(throwsIae(() -> ChampionElectroChargePlan.aoeDamage(AffixQuality.COMMON, -5.0D)),
                "aoeDamage 负 maxHp 须抛");
        helper.assertTrue(throwsIae(() -> ChampionElectroChargePlan.withinAoe(-1.0D)),
                "withinAoe 负距离²须抛");
        helper.assertTrue(throwsIae(() -> ChampionElectroChargePlan.withinTether(-1.0D)),
                "withinTether 负距离²须抛");
        helper.succeed();
    }

    // ---- 私有断言辅助 ----

    private static void assertClose(GameTestHelper helper, double actual, double expected, String msg) {
        helper.assertTrue(Math.abs(actual - expected) < EPS,
                msg + " (期望 " + expected + " 实测 " + actual + ")");
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

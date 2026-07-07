package com.miningdim.champion;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 6 个减伤词条数值折算 + ramp + FLAT 削顶 + 子弹分类 + 多源连乘红线 纯逻辑 GameTest
 * (ChampionStarAffix spec 7.1 生存 + 9.2 净减伤单点 + 红线 1)。
 *
 * 全断言具体业务数值且含反例 (删被测折算/ramp/削顶/分类/连乘 clamp 对应 test 立挂): ramp 爬升与 3s 无伤重置、
 * 子弹专属源仅子弹纳入、刚毅 FLAT 封顶削顶、重型 <T 整次免疫 (仅近战/爆炸)、缩小化体型 ×0.5 折算、以及 spec 9.2
 * 头号红线: 8★/9★ 多减伤源连乘后 keep 不破 0.51 (净减伤 ≤49%)。严禁触 Champions 加载路径 (compileOnly 铁律) ——
 * 全测纯函数 {@link ChampionDamageReduction} / {@link CompositeArmorRampTracker} / {@link ChampionRedlines}, 不引用
 * top.theillusivec4.champions.*。template = "empty"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionAffixReductionGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_reduction";
    private static final double EPS = 1e-6D;

    // ============================================================
    // 各减伤源数值折算 (照 AffixDef 5 档表 / spec 7.1 体型表 / FLAT 封顶)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bulletSourceRatesMatchAffixTable(GameTestHelper helper) {
        // 超高分子子弹抗 10/15/22/30/40%。
        helper.assertTrue(Math.abs(ChampionDamageReduction.uhmwpeBulletRate(AffixQuality.COMMON) - 0.10D) < EPS,
                "uhmwpe COMMON bullet resist 10%");
        helper.assertTrue(Math.abs(ChampionDamageReduction.uhmwpeBulletRate(AffixQuality.EPIC) - 0.30D) < EPS,
                "uhmwpe EPIC bullet resist 30%");
        helper.assertTrue(Math.abs(ChampionDamageReduction.uhmwpeBulletRate(AffixQuality.LEGENDARY) - 0.40D) < EPS,
                "uhmwpe LEGENDARY bullet resist 40%");

        // 重型护甲子弹抗 35/42/49% (高/超/闪)。
        helper.assertTrue(Math.abs(ChampionDamageReduction.heavyArmorBulletRate(AffixQuality.RARE) - 0.35D) < EPS,
                "heavy armor RARE bullet resist 35%");
        helper.assertTrue(Math.abs(ChampionDamageReduction.heavyArmorBulletRate(AffixQuality.LEGENDARY) - 0.49D) < EPS,
                "heavy armor LEGENDARY bullet resist 49%");

        // 偏斜 EV = 闪避率 8/12/18/25/35%。
        helper.assertTrue(Math.abs(ChampionDamageReduction.deflectorBulletEvRate(AffixQuality.RARE) - 0.18D) < EPS,
                "deflector RARE EV 18%");
        helper.assertTrue(Math.abs(ChampionDamageReduction.deflectorBulletEvRate(AffixQuality.LEGENDARY) - 0.35D) < EPS,
                "deflector LEGENDARY EV 35%");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void miniaturizationSizeFoldsToHalf(GameTestHelper helper) {
        // 体型表 15/25/35/45/55%; 折算率 = 体型 × 0.5。
        helper.assertTrue(Math.abs(ChampionDamageReduction.miniaturizationSizePct(AffixQuality.COMMON) - 0.15D) < EPS,
                "miniaturization COMMON size 15%");
        helper.assertTrue(Math.abs(ChampionDamageReduction.miniaturizationReductionRate(AffixQuality.COMMON) - 0.075D) < EPS,
                "miniaturization COMMON reduction = 15% x 0.5 = 7.5%");
        helper.assertTrue(Math.abs(ChampionDamageReduction.miniaturizationReductionRate(AffixQuality.LEGENDARY) - 0.275D) < EPS,
                "miniaturization LEGENDARY reduction = 55% x 0.5 = 27.5%");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void fortitudeAndHeavyThresholdTables(GameTestHelper helper) {
        // 刚毅 FLAT 封顶 120/80/50 高/超/闪 (品质越高越低 = 越硬)。
        helper.assertTrue(Math.abs(ChampionDamageReduction.fortitudeSingleHitCap(AffixQuality.RARE) - 120.0D) < EPS,
                "fortitude RARE cap 120");
        helper.assertTrue(Math.abs(ChampionDamageReduction.fortitudeSingleHitCap(AffixQuality.EPIC) - 80.0D) < EPS,
                "fortitude EPIC cap 80");
        helper.assertTrue(Math.abs(ChampionDamageReduction.fortitudeSingleHitCap(AffixQuality.LEGENDARY) - 50.0D) < EPS,
                "fortitude LEGENDARY cap 50 (hardest)");

        // 重型 <T 免疫阈值 8/14/22 高/超/闪。
        helper.assertTrue(Math.abs(ChampionDamageReduction.heavyArmorImmunityThreshold(AffixQuality.RARE) - 8.0D) < EPS,
                "heavy armor RARE immunity threshold T=8");
        helper.assertTrue(Math.abs(ChampionDamageReduction.heavyArmorImmunityThreshold(AffixQuality.LEGENDARY) - 22.0D) < EPS,
                "heavy armor LEGENDARY immunity threshold T=22");

        // 反例: 刚毅/重型最低高级, COMMON 前导 0 占位档须抛 (不静默返 0)。
        assertThrowsIae(helper, () -> ChampionDamageReduction.fortitudeSingleHitCap(AffixQuality.COMMON),
                "fortitude COMMON (leading-zero) must throw");
        assertThrowsIae(helper, () -> ChampionDamageReduction.heavyArmorImmunityThreshold(AffixQuality.UNCOMMON),
                "heavy armor UNCOMMON below min usable must throw");
        helper.succeed();
    }

    // ============================================================
    // 复合装甲 ramp: 爬升 (每受击 +上限/5) + 3s 无伤重置
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void compositeRampClimbsAndCaps(GameTestHelper helper) {
        // EPIC 上限 0.22, step = 0.044。
        AffixQuality q = AffixQuality.EPIC;
        helper.assertTrue(ChampionDamageReduction.compositeRampRate(q, 0) == 0.0D, "ramp 0 hits = 0 rate");
        helper.assertTrue(Math.abs(ChampionDamageReduction.compositeRampRate(q, 1) - 0.044D) < EPS,
                "ramp 1 hit = cap/5 = 0.044");
        helper.assertTrue(Math.abs(ChampionDamageReduction.compositeRampRate(q, 3) - 0.132D) < EPS,
                "ramp 3 hits = 3*cap/5 = 0.132");
        helper.assertTrue(Math.abs(ChampionDamageReduction.compositeRampRate(q, 5) - 0.22D) < EPS,
                "ramp 5 hits = cap 0.22");
        // 超过 5 次仍夹到上限 (不再叠加超过词条上限)。
        helper.assertTrue(Math.abs(ChampionDamageReduction.compositeRampRate(q, 9) - 0.22D) < EPS,
                "ramp >5 hits clamped to cap (no over-stack past affix ceiling)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void compositeRampResetsAfterThreeSeconds(GameTestHelper helper) {
        CompositeArmorRampTracker t = new CompositeArmorRampTracker();
        // tick 0,1,2: 连续受击爬到 3 次。
        helper.assertTrue(t.onHit(0L) == 1, "first hit count 1");
        helper.assertTrue(t.onHit(1L) == 2, "second hit (within window) count 2");
        helper.assertTrue(t.onHit(2L) == 3, "third hit count 3");
        // 夹到 5 上限: 再连击 (tick 内 <60 间隔)。
        t.onHit(3L);
        t.onHit(4L);
        helper.assertTrue(t.onHit(5L) == 5, "ramp count clamps at 5 steps");
        // tick 5+60 = 65: 距上次受击 (5) 恰 60 tick >= 3s 无伤 -> 重置, 本次重新 = 1。
        helper.assertTrue(t.onHit(65L) == 1, "3s no-hit gap (>=60 tick) resets ramp to 1");
        // 紧接受击 (tick 66 <60 间隔): 不重置, 爬到 2。
        helper.assertTrue(t.onHit(66L) == 2, "hit right after reset climbs to 2 (no second reset)");

        // 反例: 删 reset 则 65 tick 应延续到 6+ (实际夹 5), 但首击后 reset 必回 1 —— 删 reset 此处必挂。
        helper.assertTrue(t.hitCount() == 2, "tracker state reflects post-reset climb (=2)");
        helper.succeed();
    }

    // ============================================================
    // FLAT 削顶: 刚毅单次封顶 + 重型 <T 整次免疫 (仅近战/爆炸)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void flatCapsClampNetDamage(GameTestHelper helper) {
        // 刚毅封顶 50: 净伤 200 -> 削到 50; 净伤 30 < 50 原样。
        helper.assertTrue(Math.abs(ChampionDamageReduction.applyFlatCaps(200.0D, 50.0D, 0.0D, false) - 50.0D) < EPS,
                "fortitude caps 200 net damage to 50");
        helper.assertTrue(Math.abs(ChampionDamageReduction.applyFlatCaps(30.0D, 50.0D, 0.0D, false) - 30.0D) < EPS,
                "fortitude leaves 30 (< cap 50) untouched");

        // 重型 <T 免疫 (T=14): 近战净伤 10 < 14 -> 0 (整次免疫); 近战净伤 20 >= 14 不免疫。
        helper.assertTrue(ChampionDamageReduction.applyFlatCaps(10.0D, 0.0D, 14.0D, true) == 0.0D,
                "heavy armor melee net 10 < T14 -> immune (0)");
        helper.assertTrue(Math.abs(ChampionDamageReduction.applyFlatCaps(20.0D, 0.0D, 14.0D, true) - 20.0D) < EPS,
                "heavy armor melee net 20 >= T14 not immune");
        // 子弹 (meleeOrExplosion=false) 不享 <T 免疫: 净伤 10 原样 (子弹走 bullet_resistance 比例减, 非整次免疫)。
        helper.assertTrue(Math.abs(ChampionDamageReduction.applyFlatCaps(10.0D, 0.0D, 14.0D, false) - 10.0D) < EPS,
                "bullet net 10 NOT immune by heavy armor T (bullets use proportional resist)");

        // 反例: 负净伤抛 (不掩盖脏值)。
        assertThrowsIae(helper, () -> ChampionDamageReduction.applyFlatCaps(-1.0D, 0.0D, 0.0D, false),
                "negative netDamage must throw");
        helper.succeed();
    }

    // ============================================================
    // 子弹分类 (tacz:bullet*) 纯字符串判定
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bulletClassificationMatchesTaczTypes(GameTestHelper helper) {
        // tacz:bullet / bullet_ignore_armor / bullet_void / bullet_void_ignore_armor 四型均判子弹。
        helper.assertTrue(ChampionDamageReduction.isBulletDamage("tacz", "bullet"), "tacz:bullet is bullet");
        helper.assertTrue(ChampionDamageReduction.isBulletDamage("tacz", "bullet_ignore_armor"),
                "tacz:bullet_ignore_armor is bullet");
        helper.assertTrue(ChampionDamageReduction.isBulletDamage("tacz", "bullet_void"), "tacz:bullet_void is bullet");
        helper.assertTrue(ChampionDamageReduction.isBulletDamage("tacz", "bullet_void_ignore_armor"),
                "tacz:bullet_void_ignore_armor is bullet");

        // 反例: 非 tacz namespace / 非 bullet path / null 均非子弹。
        helper.assertTrue(!ChampionDamageReduction.isBulletDamage("minecraft", "player_attack"),
                "vanilla melee is not bullet");
        helper.assertTrue(!ChampionDamageReduction.isBulletDamage("tacz", "void"), "tacz:void (non-bullet path) is not bullet");
        helper.assertTrue(!ChampionDamageReduction.isBulletDamage(null, "bullet"), "null namespace is not bullet");
        helper.assertTrue(!ChampionDamageReduction.isBulletDamage("tacz", null), "null path is not bullet");
        helper.succeed();
    }

    // ============================================================
    // spec 9.2 头号红线: 多减伤源连乘后 keep 不破 0.51 (净减伤 ≤49%)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void multiSourceNetKeepNeverBreaksFloor(GameTestHelper helper) {
        // 8★ 子弹: 超高分子 EPIC 0.30 + 复合 ramp 满 5 (EPIC 0.22)。裸连乘 keep = 0.7*0.78 = 0.546 (本就 >0.51,
        // 但加偏斜会破), 验 clamp 兜底: 再叠偏斜 EPIC 0.25 -> 0.7*0.78*0.75 = 0.4095 < 0.51 -> 夹到 0.51。
        double bulletRate8 = ChampionDamageReduction.uhmwpeBulletRate(AffixQuality.EPIC);          // 0.30
        double rampFull8 = ChampionDamageReduction.compositeRampRate(AffixQuality.EPIC, 5);        // 0.22
        double deflector8 = ChampionDamageReduction.deflectorBulletEvRate(AffixQuality.EPIC);      // 0.25
        double keep8 = ChampionRedlines.clampNetKeepFactor(bulletRate8, rampFull8, deflector8);
        helper.assertTrue(keep8 >= ChampionRedlines.MIN_KEEP_FACTOR - EPS,
                "8star bullet (0.30+ramp0.22+deflector0.25) raw connet 0.41 clamped to floor 0.51");
        helper.assertTrue(Math.abs(keep8 - 0.51D) < EPS, "clamped keep is exactly 0.51 (net reduction capped at 49%)");

        // 9★ 子弹极端: 子弹抗 LEGENDARY 0.40 + 复合满 LEGENDARY 0.30 + 偏斜 LEGENDARY 0.35 + 缩小化 LEGENDARY 0.275。
        // 裸连乘 0.6*0.7*0.65*0.725 = 0.198 << 0.51 -> 必夹 0.51 (绝无等效无敌)。
        double keep9 = ChampionRedlines.clampNetKeepFactor(
                ChampionDamageReduction.heavyArmorBulletRate(AffixQuality.LEGENDARY),       // 0.49 (重型子弹抗最高)
                ChampionDamageReduction.compositeRampRate(AffixQuality.LEGENDARY, 5),       // 0.30
                ChampionDamageReduction.deflectorBulletEvRate(AffixQuality.LEGENDARY),      // 0.35
                ChampionDamageReduction.miniaturizationReductionRate(AffixQuality.LEGENDARY)); // 0.275
        helper.assertTrue(Math.abs(keep9 - 0.51D) < EPS,
                "9star four-source extreme connet clamps to floor 0.51 (net reduction never exceeds 49%)");

        // 基准子弹最终伤害 >= 原始 x 0.51 (spec 9.2 TDD: 删 clamp 必挂 —— 删 clamp 则 keep9=0.198, 此断言挂)。
        double incoming = 1000.0D;
        double finalDamage = incoming * keep9;
        helper.assertTrue(finalDamage >= incoming * 0.51D - EPS,
                "baseline bullet final damage >= original x 0.51 (no over-reduction past redline 1)");

        // 单源不破底但接近: 单超高分子 LEGENDARY 0.40 -> keep 0.60 (单源 <0.49 不触底, clamp 不动)。
        double keepSingle = ChampionRedlines.clampNetKeepFactor(0.40D);
        helper.assertTrue(Math.abs(keepSingle - 0.60D) < EPS, "single 0.40 source keep 0.60 (below cap, clamp inert)");
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

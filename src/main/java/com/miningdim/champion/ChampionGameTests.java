package com.miningdim.champion;

import com.miningdim.champion.aggregate.DotAggregator;
import com.miningdim.champion.aggregate.PlayerControlAggregator;
import com.miningdim.champion.aggregate.RetaliationAggregator;
import com.miningdim.champion.bloodpool.BloodPool;
import com.miningdim.champion.bloodpool.BloodPoolRegistry;
import com.miningdim.champion.reward.ChampionReward;
import com.miningdim.champion.reward.ContributionPool;
import com.miningdim.champion.reward.ContributionTracker;
import com.miningdim.champion.reward.DamageContribution;
import com.miningdim.core.Difficulty;
import com.miningdim.core.MiningConstants;
import com.miningdim.effect.VulnerabilityEffect;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 精英怪星级词条系统纯逻辑 GameTest (ChampionStarAffix spec 第十四章实现拆分 9 全链路 TDD)。
 *
 * 严禁触 Champions 加载路径 (compileOnly 铁律): 本测试只断言纯逻辑 (星级点数预算 / 血池 HP 数学 / 聚合封顶 /
 * 贡献池加权瓜分 / 红线夹断), 不引用 top.theillusivec4.champions.* —— 真词条/真盖章/真奖励触发在正式服
 * (Champions 已加载) 验。所有断言为具体业务结果 (删被测核心逻辑必挂, 禁 is-not-null 弱校验): 删表/删 clamp/
 * 删拦死判据后对应 test 立挂。
 *
 * 用 template = "empty" (data/miningdim/structures/empty.nbt), batch = "champion"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion";
    private static final double EPS = 1e-6D;

    // ============================================================
    // 星表点数预算 (StarRank) — 删表必挂
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void starRankBudgetTable(GameTestHelper helper) {
        StarRank s5 = StarRank.ofStar(5);
        helper.assertTrue(s5.survivalBudget() == 80, "5star survival budget must be 80");
        helper.assertTrue(s5.combatBudget() == 55, "5star combat budget must be 55");
        helper.assertTrue(s5.mobilityBudget() == 20, "5star mobility budget must be 20");
        helper.assertTrue(s5.skillBudget() == 45, "5star skill budget must be 45");
        helper.assertTrue(s5.maxAffixes() == 5, "5star affix cap must be 5");
        helper.assertTrue(s5.maxSkills() == 1, "5star skill cap must be 1");
        helper.assertTrue(s5.maxQuality() == AffixQuality.RARE, "5star max quality must be RARE (high)");

        StarRank s10 = StarRank.ofStar(10);
        helper.assertTrue(s10.survivalBudget() == 440, "10star survival budget must be 440");
        helper.assertTrue(s10.combatBudget() == 310, "10star combat budget must be 310");
        helper.assertTrue(Math.abs(s10.baseEffectiveHp() - 73_000.0D) < EPS, "10star base eff HP must be 73000");

        // 技能 3star 才解锁: 1-2star 技能数上限 = 0。
        helper.assertTrue(StarRank.ofStar(1).maxSkills() == 0, "1star has no skills");
        helper.assertTrue(StarRank.ofStar(2).maxSkills() == 0, "2star has no skills");
        helper.assertTrue(StarRank.ofStar(3).maxSkills() == 1, "skills unlock at 3star");

        // 6star+ 走自定义血池 (破 1024)。
        helper.assertTrue(!StarRank.ofStar(5).usesCustomBloodPool(), "5star (765 HP) stays vanilla");
        helper.assertTrue(StarRank.ofStar(6).usesCustomBloodPool(), "6star (2700 HP) needs custom pool");

        // 红线 3 单击上限三档。
        helper.assertTrue(Math.abs(StarRank.ofStar(5).normalHitCapPct() - 0.40D) < EPS, "1-5star hit cap 40%");
        helper.assertTrue(Math.abs(StarRank.ofStar(7).normalHitCapPct() - 0.50D) < EPS, "6-7star hit cap 50%");
        helper.assertTrue(Math.abs(StarRank.ofStar(8).normalHitCapPct() - 0.60D) < EPS, "8-10star hit cap 60%");
        helper.succeed();
    }

    // ============================================================
    // 点数花法 (PointBudget) — 买词条扣点 + 剩余换膨胀 + 超预算拒绝
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pointBudgetSpendAndRemainder(GameTestHelper helper) {
        StarRank s5 = StarRank.ofStar(5);
        // 5star 买偏斜护盾高级 (c10 x2.5 = 25) 从生存池 80 扣点剩 55。
        AffixSelection deflectorRare = new AffixSelection(AffixDef.DEFLECTOR_SHIELD, AffixQuality.RARE);
        helper.assertTrue(deflectorRare.cost() == 25, "deflector RARE cost = ceil(10*2.5) = 25");
        PointBudget.Allocation a = PointBudget.allocate(s5, List.of(deflectorRare));
        helper.assertTrue(a.spent(AffixPool.SURVIVAL) == 25, "survival spent 25");
        helper.assertTrue(a.remaining(AffixPool.SURVIVAL) == 55, "survival remaining 80-25 = 55");
        helper.assertTrue(a.convertibleRemainder(AffixPool.SURVIVAL) == 55, "survival remainder converts to base HP/DR");

        // 6star 买刚毅护盾高级 (c22 x2.5 = 55) 从生存池 120 扣点剩 65。
        StarRank s6 = StarRank.ofStar(6);
        AffixSelection fortitudeRare = new AffixSelection(AffixDef.FORTITUDE_SHIELD, AffixQuality.RARE);
        helper.assertTrue(fortitudeRare.cost() == 55, "fortitude RARE cost = ceil(22*2.5) = 55");
        PointBudget.Allocation a6 = PointBudget.allocate(s6, List.of(fortitudeRare));
        helper.assertTrue(a6.remaining(AffixPool.SURVIVAL) == 65, "6star survival 120-55 = 65 remaining");

        // 技能池剩余不转膨胀 (spec 第四章): 技能池 convertibleRemainder 恒 0。
        helper.assertTrue(a6.convertibleRemainder(AffixPool.SKILL) == 0, "skill pool remainder never converts");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pointBudgetOverBudgetRejected(GameTestHelper helper) {
        // 5star 战斗池 55, 买四倍痛处闪耀 (c16 x6.5 = 104) 远超 55 须拒。但闪耀 9star 才解锁, 先用 5star 可取的高级:
        // 四倍痛处高级 c16 x2.5 = 40 (<=55 合法); 改用买两条高成本超预算: 重炮高级 c10x2.5=25 + 嗜血高级 c10x2.5=25
        // + 燃烧高级 c8x2.5=20 = 70 > 55 战斗池 -> 拒。
        StarRank s5 = StarRank.ofStar(5);
        List<AffixSelection> overCombat = List.of(
                new AffixSelection(AffixDef.HEAVY_CANNON, AffixQuality.RARE),
                new AffixSelection(AffixDef.BLOODLUST, AffixQuality.RARE),
                new AffixSelection(AffixDef.BURNING, AffixQuality.RARE));
        boolean rejected = false;
        try {
            PointBudget.allocate(s5, overCombat);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "70 combat points > 55 budget must be rejected");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pointBudgetQualityUnlockClamp(GameTestHelper helper) {
        // 品质随星解锁: 2star 最高品质 = 普通 (COMMON)。clampTo 把高级降到普通。
        StarRank s2 = StarRank.ofStar(2);
        helper.assertTrue(s2.maxQuality() == AffixQuality.COMMON, "2star max quality COMMON");
        AffixQuality clamped = AffixQuality.RARE.clampTo(s2.maxQuality());
        helper.assertTrue(clamped == AffixQuality.COMMON, "RARE clamps to COMMON at 2star");

        // 刚毅护盾最低高级 + 最低 6star: 2star 不解锁本词条 (isUnlockedAt false)。
        helper.assertTrue(!AffixDef.FORTITUDE_SHIELD.isUnlockedAt(s2), "fortitude not unlocked at 2star");
        helper.assertTrue(AffixDef.FORTITUDE_SHIELD.isUnlockedAt(StarRank.ofStar(6)),
                "fortitude unlocks at 6star (RARE available)");

        // 越档装配直接拒: 5star 装配偏斜护盾超凡 (EPIC > 5star RARE 上限) 须拒。
        boolean rejected = false;
        try {
            PointBudget.allocate(StarRank.ofStar(5),
                    List.of(new AffixSelection(AffixDef.DEFLECTOR_SHIELD, AffixQuality.EPIC)));
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "EPIC affix on 5star (max RARE) must be rejected");
        helper.succeed();
    }

    // ============================================================
    // 互斥校验 (PointBudget)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pointBudgetMutex(GameTestHelper helper) {
        StarRank s5 = StarRank.ofStar(5);

        // 高速 + 超速 同族须拒。
        assertRejected(helper, s5, List.of(
                new AffixSelection(AffixDef.SPRINT, AffixQuality.RARE),
                new AffixSelection(AffixDef.OVERDRIVE, AffixQuality.RARE)),
                "high-speed + overdrive (MOVE_SPEED family) must reject");

        // 巨大化 + 任意机动 须拒 (巨大化互斥全部机动)。
        assertRejected(helper, s5, List.of(
                new AffixSelection(AffixDef.GIGANTISM, AffixQuality.RARE),
                new AffixSelection(AffixDef.SPRINT, AffixQuality.RARE)),
                "gigantism + any mobility must reject");

        // 缩小化 须强制 +1 机动: 无机动词条须拒。
        assertRejected(helper, s5, List.of(
                new AffixSelection(AffixDef.MINIATURIZATION, AffixQuality.RARE)),
                "miniaturization without a mobility affix must reject");
        // 缩小化 + 高速 合法 (满足强制机动)。
        PointBudget.Allocation ok = PointBudget.allocate(s5, List.of(
                new AffixSelection(AffixDef.MINIATURIZATION, AffixQuality.RARE),
                new AffixSelection(AffixDef.SPRINT, AffixQuality.RARE)));
        helper.assertTrue(ok.spent(AffixPool.MOBILITY) > 0, "miniaturization + sprint legal (mobility occupied)");

        // 双倍 + 四倍 同族须拒 (4star 解锁四倍? 四倍最低 5star)。用 5star。
        assertRejected(helper, s5, List.of(
                new AffixSelection(AffixDef.DOUBLE_STRIKE, AffixQuality.RARE),
                new AffixSelection(AffixDef.QUADRUPLE_STRIKE, AffixQuality.RARE)),
                "double + quadruple strike (MULTI_STRIKE family) must reject");

        // 传送家族跨池 >2 须拒: 闪光 + 战术 + 灵体 = 3 传送家族。用 5star (灵体 4star, 此处用闪光+战术+ 凯撒? 凯撒 5star 技能)。
        // 闪光(2star) + 战术(2star) 是 2 个机动传送家族 (合法), 加凯撒(5star 技能传送家族) = 3 须拒。
        assertRejected(helper, s5, List.of(
                new AffixSelection(AffixDef.BLINK, AffixQuality.RARE),
                new AffixSelection(AffixDef.TACTICAL_BLINK, AffixQuality.RARE),
                new AffixSelection(AffixDef.CAESAR_SWAP, AffixQuality.RARE)),
                "3 teleport-family affixes (cap 2) must reject");
        helper.succeed();
    }

    // ============================================================
    // 净减伤红线 1 (ChampionRedlines.clampNetKeepFactor) — 删 clamp 必挂
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void netDamageReductionClamp(GameTestHelper helper) {
        // 帽 75% (keep 底 0.25, 2026-07-07 随复合同源适应抬帽)。8star: 0.30 + 0.49 -> keep = 0.70*0.51 = 0.357
        // (> 0.25 不再触帽, 抬帽后此组合放行原值)。
        double keep8 = ChampionRedlines.clampNetKeepFactor(0.30D, 0.49D);
        helper.assertTrue(Math.abs(keep8 - 0.357D) < EPS, "8star 0.30+0.49 keep 0.357 (below 75% cap, not clamped)");

        // 9star: 0.40 + 0.49 + 偏斜 EV 0.35 -> 0.60*0.51*0.65 = 0.1989 < 0.25 -> clamp 0.25。
        double keep9 = ChampionRedlines.clampNetKeepFactor(0.40D, 0.49D, 0.35D);
        helper.assertTrue(Math.abs(keep9 - 0.25D) < EPS, "9star three-source net reduction clamps to 0.25");

        // 最终伤害 = 原始 x keep >= 原始 x 0.25 (删 clamp 则 keep9=0.199, 此断言挂)。
        double original = 1000.0D;
        helper.assertTrue(original * keep9 >= original * 0.25D - EPS, "final damage >= original*0.25");

        // 未撞顶情形不夹: 单源 0.20 -> keep = 0.80 (未到 0.25 底保持原值)。
        double keepLow = ChampionRedlines.clampNetKeepFactor(0.20D);
        helper.assertTrue(Math.abs(keepLow - 0.80D) < EPS, "single 0.20 reduction keeps 0.80 (not clamped)");
        helper.succeed();
    }

    // ============================================================
    // 血池 (BloodPool) — 拦死精确 / 回血无溢出 / 低血阈值走池
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bloodPoolKillPreciseAtZero(GameTestHelper helper) {
        // maxHp=204400 池, currentHp-本次伤害=1 不死 / =0 必死。
        BloodPool pool = new BloodPool(204_400.0D, 100.0D);
        // currentHp=100, 伤害 99 -> 剩 1 不死。
        helper.assertTrue(!pool.wouldDieFrom(99.0D), "currentHp 100 - 99 = 1 must NOT die");
        // 伤害 100 -> 剩 0 必死。
        helper.assertTrue(pool.wouldDieFrom(100.0D), "currentHp 100 - 100 = 0 must die");
        // 伤害 101 -> 负 必死。
        helper.assertTrue(pool.wouldDieFrom(101.0D), "currentHp 100 - 101 < 0 must die");

        // applyDamage 扣到 0 即死且 currentHp 下钳 0。
        boolean died = pool.applyDamage(100.0D);
        helper.assertTrue(died, "applyDamage 100 kills (currentHp -> 0)");
        helper.assertTrue(pool.isDead(), "pool is dead at currentHp 0");
        helper.assertTrue(pool.currentHp() == 0.0D, "currentHp clamps to 0 (not negative)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bloodPoolFlatHealNoOverflow(GameTestHelper helper) {
        // 73000 满血池连续回血 10s (每 tick heal) 恒 73000 不溢。
        BloodPool pool = new BloodPool(73_000.0D);
        helper.assertTrue(pool.currentHp() == 73_000.0D, "starts full at 73000");
        for (int t = 0; t < 200; t++) { // 10s * 20tick
            pool.heal(300.0D); // 自我修复闪耀 300 HP/s 折算每 tick 15 ... 这里直接灌大量验证不溢。
        }
        helper.assertTrue(pool.currentHp() == 73_000.0D, "10s of healing stays clamped at 73000 (no overflow)");

        // 先扣血再回血: 扣 5000 剩 68000, 回 2000 -> 70000 (clamp 内)。
        pool.applyDamage(5_000.0D);
        helper.assertTrue(pool.currentHp() == 68_000.0D, "after 5000 damage currentHp = 68000");
        pool.heal(2_000.0D);
        helper.assertTrue(pool.currentHp() == 70_000.0D, "heal 2000 -> 70000 (within max)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bloodPoolLowHpThresholdUsesPool(GameTestHelper helper) {
        // mock vanilla getHealth=1024 时, 嗜血/命定低血判定仍按 currentHp/maxHp% 而非 vanilla。
        // 池 maxHp=27000 (8star), currentHp=2700 -> fraction=0.10 (低血), 而 vanilla 渲染镜像也按池算 = 102.4。
        BloodPool pool = new BloodPool(27_000.0D, 2_700.0D);
        helper.assertTrue(Math.abs(pool.fraction() - 0.10D) < EPS, "low-hp judged by pool fraction 0.10, not vanilla");
        // 渲染镜像 = 0.10 * 1024 = 102.4。
        helper.assertTrue(Math.abs(pool.displayHealth() - 102.4F) < 0.01F, "display mirror = fraction*1024 = 102.4");
        // 满血渲染镜像封顶 1024 (即便 maxHp 远超 1024)。
        BloodPool full = new BloodPool(204_000.0D);
        helper.assertTrue(Math.abs(full.displayHealth() - 1024.0F) < 0.01F, "full pool mirrors to 1024 cap");
        helper.succeed();
    }

    // ============================================================
    // DoT 聚合红线 4 (DotAggregator) — 删 clamp 后合计>15% 必挂
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void dotAggregateCap(GameTestHelper helper) {
        // maxHp=1000, 上限 15% = 150 HP/s。
        // 燃烧 5 层 (4%*5=20% = 200) + 寒霜冻伤 (3.5% = 35) + 强酸 (按 dot 40) = 275 名义 > 150 上限。
        double maxHp = 1000.0D;
        DotAggregator.Result r = DotAggregator.aggregate(maxHp, 200.0D, 35.0D, 40.0D);
        helper.assertTrue(Math.abs(r.total() - 150.0D) < EPS, "DoT sum clamps to 15% maxHP = 150");
        helper.assertTrue(r.wasCapped(), "exceeded DoT cap so capped");
        // 按贡献比例衰减: 逐源 = 名义 * (150/275)。燃烧 200*150/275 = 109.09...
        double[] perSrc = r.perSource();
        helper.assertTrue(Math.abs(perSrc[0] - 200.0D * 150.0D / 275.0D) < 1e-3D, "burning scaled by contribution");
        // 逐源合计精确 = 150。
        double sum = perSrc[0] + perSrc[1] + perSrc[2];
        helper.assertTrue(Math.abs(sum - 150.0D) < EPS, "scaled per-source sums exactly to cap 150");

        // 未超顶不衰减: 燃烧 50 + 寒霜 30 = 80 < 150 -> 原样。
        DotAggregator.Result under = DotAggregator.aggregate(maxHp, 50.0D, 30.0D);
        helper.assertTrue(Math.abs(under.total() - 80.0D) < EPS, "under-cap DoT passes through unscaled");
        helper.assertTrue(!under.wasCapped(), "under cap not flagged capped");
        helper.succeed();
    }

    // ============================================================
    // 反伤聚合红线 2 (RetaliationAggregator) — 多源累加 <=30%/s + <=40%/窗
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void retaliationAggregateCaps(GameTestHelper helper) {
        // attacker maxHp=80 (服务器初始血). 每秒上限 30% = 24, 单窗上限 40% = 32。
        RetaliationAggregator agg = new RetaliationAggregator(80.0D);
        helper.assertTrue(Math.abs(agg.perSecondCap() - 24.0D) < EPS, "per-second cap 30% of 80 = 24");
        helper.assertTrue(Math.abs(agg.perWindowCap() - 32.0D) < EPS, "per-window cap 40% of 80 = 32");

        // 同一秒注入 100% 反伤比的巨量反伤 -> 被夹到秒上限 24。
        double granted = agg.admit(1000.0D, 0L);
        helper.assertTrue(Math.abs(granted - 24.0D) < EPS, "100% retaliation in one second clamps to 24 (30%/s)");
        // 同秒再申请 -> 秒额度耗尽返 0。
        double again = agg.admit(1000.0D, 5L);
        helper.assertTrue(again == 0.0D, "second-window exhausted -> 0 more this second");

        // 下一秒 (tick 20): 秒重置但窗 (5s) 未重置, 窗已累计 24, 窗上限 32 剩 8 -> 申请 1000 被夹到 8。
        double nextSec = agg.admit(1000.0D, 20L);
        helper.assertTrue(Math.abs(nextSec - 8.0D) < EPS, "next second bounded by window cap remainder (32-24=8)");
        helper.succeed();
    }

    // ============================================================
    // 控制聚合红线 5 (PlayerControlAggregator) — 7s 窗 <=50% + 减速 <=50%
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void controlAggregateWindowCap(GameTestHelper helper) {
        // 7s 窗 = 140 tick, 受控上限 50% = 70 tick。
        helper.assertTrue(PlayerControlAggregator.WINDOW_TICKS == 140L, "7s window = 140 ticks");
        helper.assertTrue(PlayerControlAggregator.BUSY_TICK_CAP == 70L, "50% busy cap = 70 ticks");

        PlayerControlAggregator agg = new PlayerControlAggregator();
        // 申请 60 tick 控制 (起点 0): 全额 (窗内 0 已受控)。
        long g1 = agg.admit(0L, 60L);
        helper.assertTrue(g1 == 60L, "first 60-tick control fully admitted");
        // 再申请 60 tick (起点 60): 窗内已 60, 上限 70 剩 10 -> 只给 10 (超额作废)。
        long g2 = agg.admit(60L, 60L);
        helper.assertTrue(g2 == 10L, "second request bounded to remaining 70-60 = 10 (excess voided)");

        // 减速总量硬封顶 <=50%。
        helper.assertTrue(Math.abs(PlayerControlAggregator.clampSlow(0.80D) - 0.50D) < EPS, "0.80 slow clamps to 0.50");
        helper.assertTrue(Math.abs(PlayerControlAggregator.clampSlow(0.30D) - 0.30D) < EPS, "0.30 slow passes through");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void controlFreeWindowGuard(GameTestHelper helper) {
        // 连续 >=2s (40 tick) 自由窗须存在。
        PlayerControlAggregator agg = new PlayerControlAggregator();
        // 在 [0,140) 窗内: 控制 [0,30) 与 [50,70), 之间空隙 [30,50)=20tick<40, 但 [70,140)=70tick>=40 自由。
        agg.admit(0L, 30L);
        agg.admit(50L, 20L);
        helper.assertTrue(agg.hasMinFreeWindow(0L), "free window [70,140) of 70 ticks satisfies >=2s requirement");

        // 密集控制塞满: 多段控制无 40tick 空隙 -> 无自由窗。注意 admit 受 70tick 上限, 故构造短间隔多段。
        PlayerControlAggregator busy = new PlayerControlAggregator();
        busy.admit(0L, 20L);   // [0,20)
        busy.admit(30L, 20L);  // [30,50) 空隙 [20,30)=10
        busy.admit(60L, 20L);  // [60,80) 空隙 [50,60)=10  (总受控 60<=70)
        // 末尾空隙 [80,140)=60 >= 40 -> 仍有自由窗 (设计要求总能保留)。验证密集前段无 40 空隙但末段有。
        helper.assertTrue(busy.hasMinFreeWindow(0L), "trailing free window guarantees >=2s freedom");
        helper.succeed();
    }

    // ============================================================
    // 单击上限红线 3 (ChampionAffixValues)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void singleHitCaps(GameTestHelper helper) {
        // 6star 普通单击名义 0.70 夹到 0.50。
        double hit6 = ChampionAffixValues.clampNormalHitPct(StarRank.ofStar(6), 0.70D);
        helper.assertTrue(Math.abs(hit6 - 0.50D) < EPS, "6star normal hit clamps to 50%");

        // 穿甲真伤 + 普通合计夹 40%: 普通 0.30 + 穿甲 0.18 = 0.48 -> 0.40。
        double combined = ChampionAffixValues.clampPiercingPlusNormal(0.30D, 0.18D);
        helper.assertTrue(Math.abs(combined - 0.40D) < EPS, "piercing+normal clamps to 40%");

        // 可躲技能名义夹 90%: 1.10 -> 0.90。
        double tele = ChampionAffixValues.clampTelegraphedHitPct(1.10D);
        helper.assertTrue(Math.abs(tele - 0.90D) < EPS, "telegraphed hit clamps to 90%");

        // 连段总伤夹 60%: 利刃华尔兹 7 次每次 0.12 = 0.84 -> 0.60。
        double combo = ChampionAffixValues.clampComboTotalPct(0.84D);
        helper.assertTrue(Math.abs(combo - 0.60D) < EPS, "combo total clamps to 60%");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void rendVulnerabilityAndSummonCap(GameTestHelper helper) {
        // 撕裂闪耀每层 0.20, 6 层 = 1.20 -> 夹易伤封顶 +100%。
        double rend = ChampionAffixValues.rendVulnerabilityPct(AffixQuality.LEGENDARY, 6);
        helper.assertTrue(Math.abs(rend - VulnerabilityEffect.MAX_VULNERABILITY_PCT) < EPS,
                "rend 6 layers legendary clamps to vulnerability cap +100%");
        // 撕裂闪耀 3 层 = 0.60 (未封顶)。
        double rend3 = ChampionAffixValues.rendVulnerabilityPct(AffixQuality.LEGENDARY, 3);
        helper.assertTrue(Math.abs(rend3 - 0.60D) < EPS, "rend 3 layers legendary = 60% (under cap)");

        // 召唤星级三重封顶: clamp(self-2, 1, 4)。10star -> 8 夹到 4; 5star -> 3; 2star -> 0 夹到 1。
        helper.assertTrue(ChampionAffixValues.summonStar(10) == 4, "10star summon clamps to ceil 4");
        helper.assertTrue(ChampionAffixValues.summonStar(5) == 3, "5star summon = 3 (self-2)");
        helper.assertTrue(ChampionAffixValues.summonStar(2) == 1, "2star summon floors to 1");
        helper.succeed();
    }

    // ============================================================
    // 贡献池盖章双门槛 + 加权瓜分 (ContributionPool)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void contributionStampThresholds(GameTestHelper helper) {
        // 10star 巨大化 204000 血. 0.5% = 1020 伤. AKM 单发 9 (0.0044%) 被排除。
        double bossHp = 204_000.0D;
        UUID leech = UUID.randomUUID();
        UUID main = UUID.randomUUID();
        // 蹭枪玩家单发 9 伤; 主力 25000 伤。
        DamageContribution leechC = new DamageContribution(leech, 9.0D, 100L, true);
        DamageContribution mainC = new DamageContribution(main, 25_000.0D, 50L, true);
        double teamAvg = ContributionPool.teamAverageEffectiveDamage(List.of(leechC, mainC));
        // 蹭枪 9 < 0.5%*204000=1020 且 < 15%*teamAvg。
        helper.assertTrue(!ContributionPool.isQualified(leechC, bossHp, teamAvg), "9-damage leech excluded");
        helper.assertTrue(ContributionPool.isQualified(mainC, bossHp, teamAvg), "25000-damage main qualifies");

        // 边界恰 0.5% (= 1020) 须合格。
        DamageContribution boundary = new DamageContribution(UUID.randomUUID(), 1_020.0D, 10L, true);
        helper.assertTrue(ContributionPool.isQualified(boundary, bossHp, teamAvg), "exactly 0.5% boss HP qualifies");

        // 离线没收: 即便高伤, online=false 不合格。
        DamageContribution offline = new DamageContribution(UUID.randomUUID(), 50_000.0D, 5L, false);
        helper.assertTrue(!ContributionPool.isQualified(offline, bossHp, teamAvg), "offline forfeits even with high damage");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void contributionWeightedDistribution(GameTestHelper helper) {
        // 三玩家有效伤害 [5000,3000,2000] 对固定池 10000 按贡献加权瓜分 (50%/30%/20%)。
        // bossHp 小到三者均合格 (各 >0.5%): bossHp=100000 -> 0.5%=500, 三者 >=2000 均合格。
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        List<DamageContribution> contribs = List.of(
                new DamageContribution(a, 5_000.0D, 1L, true),
                new DamageContribution(b, 3_000.0D, 2L, true),
                new DamageContribution(c, 2_000.0D, 3L, true));
        Map<UUID, Long> payout = ContributionPool.distribute(contribs, 100_000.0D, 10_000L);
        helper.assertTrue(payout.size() == 3, "all three qualify and receive payout");
        helper.assertTrue(payout.get(a) == 5_000L, "5000/10000 weight -> 5000 of pool");
        helper.assertTrue(payout.get(b) == 3_000L, "3000/10000 weight -> 3000 of pool");
        helper.assertTrue(payout.get(c) == 2_000L, "2000/10000 weight (last absorbs remainder) -> 2000 of pool");
        long total = payout.get(a) + payout.get(b) + payout.get(c);
        helper.assertTrue(total == 10_000L, "weighted shares sum exactly to fixed pool (no head-count copy)");

        // 含蹭枪玩家: 蹭枪被排除, 合格者瓜分整池 (非按人头复制给蹭枪)。
        UUID leech = UUID.randomUUID();
        List<DamageContribution> withLeech = List.of(
                new DamageContribution(a, 8_000.0D, 1L, true),
                new DamageContribution(leech, 1.0D, 99L, true));
        // bossHp=204000 -> 0.5%=1020; 蹭枪 1 伤排除, teamAvg=(8000+1)/2=4000.5, 15%=600 -> 蹭枪 1<600 排除。
        Map<UUID, Long> p2 = ContributionPool.distribute(withLeech, 204_000.0D, 5_000L);
        helper.assertTrue(p2.size() == 1, "only main qualifies; leech excluded");
        helper.assertTrue(p2.get(a) == 5_000L, "sole qualifier takes whole fixed pool");
        helper.assertTrue(!p2.containsKey(leech), "leech receives nothing (not head-count copied)");

        // 无合格者: 整池不发。
        UUID l1 = UUID.randomUUID();
        UUID l2 = UUID.randomUUID();
        List<DamageContribution> allLeech = List.of(
                new DamageContribution(l1, 5.0D, 1L, true),
                new DamageContribution(l2, 5.0D, 2L, true));
        // bossHp=204000, 0.5%=1020; teamAvg=5, 15%=0.75; 5<1020 但 5>=0.75 -> 合格?! 门槛二取一. 重设 bossHp 让两门槛都不过:
        // teamAvg=5 时 15%=0.75, 5>=0.75 故两人都合格 (门槛二). 这验证门槛二语义正确 (人均低则低伤也算贡献).
        Map<UUID, Long> p3 = ContributionPool.distribute(allLeech, 204_000.0D, 1_000L);
        helper.assertTrue(p3.size() == 2, "when team avg is low, threshold-two qualifies both (取一 semantics)");
        helper.succeed();
    }

    // ============================================================
    // 生成策略 (ChampionSpawnPolicy) — 难度->星级区间 + 升格概率
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void spawnPolicyStarRanges(GameTestHelper helper) {
        // 难度档星级区间硬值 (删表/改区间必挂)。
        helper.assertTrue(ChampionSpawnPolicy.minStar(Difficulty.EASY) == 1, "EASY min star 1");
        helper.assertTrue(ChampionSpawnPolicy.maxStar(Difficulty.EASY) == 3, "EASY max star 3");
        helper.assertTrue(ChampionSpawnPolicy.minStar(Difficulty.MEDIUM) == 3, "MEDIUM min star 3");
        helper.assertTrue(ChampionSpawnPolicy.maxStar(Difficulty.MEDIUM) == 6, "MEDIUM max star 6");
        helper.assertTrue(ChampionSpawnPolicy.minStar(Difficulty.HARD) == 5, "HARD min star 5");
        helper.assertTrue(ChampionSpawnPolicy.maxStar(Difficulty.HARD) == 10, "HARD max star 10");

        // 升格概率随难度升 (EASY < MEDIUM < HARD)。
        helper.assertTrue(ChampionSpawnPolicy.promoteChance(Difficulty.EASY)
                < ChampionSpawnPolicy.promoteChance(Difficulty.MEDIUM), "EASY promote < MEDIUM");
        helper.assertTrue(ChampionSpawnPolicy.promoteChance(Difficulty.MEDIUM)
                < ChampionSpawnPolicy.promoteChance(Difficulty.HARD), "MEDIUM promote < HARD");

        // rollStar 恒落在区间内 (确定性种子掷 500 次全在 [min,max])。
        RandomSource rng = RandomSource.create(0xC0FFEEL);
        for (Difficulty d : Difficulty.values()) {
            int lo = ChampionSpawnPolicy.minStar(d);
            int hi = ChampionSpawnPolicy.maxStar(d);
            for (int i = 0; i < 500; i++) {
                int star = ChampionSpawnPolicy.rollStar(d, rng);
                helper.assertTrue(star >= lo && star <= hi,
                        "rollStar " + star + " out of [" + lo + "," + hi + "] for " + d);
            }
        }
        helper.succeed();
    }

    // ============================================================
    // 词条掷取 (AffixRoller) — 掷出的集合恒合法且不超四池预算 (1-10star 全覆盖)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void affixRollerProducesLegalSets(GameTestHelper helper) {
        RandomSource rng = RandomSource.create(0xBADC0DEL);
        for (int star = StarRank.MIN_STAR; star <= StarRank.MAX_STAR; star++) {
            StarRank rank = StarRank.ofStar(star);
            for (int trial = 0; trial < 60; trial++) {
                List<AffixSelection> rolled = AffixRoller.roll(rank, rng);
                // allocate 必过 (roller 只产出合法集合); 抛出即 roller bug。
                PointBudget.Allocation alloc = PointBudget.allocate(rank, rolled);
                // 各池花费不超预算 (allocate 已校验, 此处再断言剩余非负冗余把关)。
                for (AffixPool pool : AffixPool.values()) {
                    helper.assertTrue(alloc.remaining(pool) >= 0,
                            pool + " remaining negative at star " + star);
                }
                // 词条数不超总上限; 技能数不超技能上限。
                helper.assertTrue(rolled.size() <= rank.maxAffixes(),
                        "rolled " + rolled.size() + " > affix cap " + rank.maxAffixes() + " at star " + star);
                int skills = 0;
                for (AffixSelection sel : rolled) {
                    if (sel.affix().isSkill()) {
                        skills++;
                    }
                }
                helper.assertTrue(skills <= rank.maxSkills(),
                        "rolled " + skills + " skills > cap " + rank.maxSkills() + " at star " + star);
            }
        }
        // 1-2star 技能上限 0: 掷出集合不含任何技能词条。
        List<AffixSelection> star1 = AffixRoller.roll(StarRank.ofStar(1), RandomSource.create(1L));
        for (AffixSelection sel : star1) {
            helper.assertTrue(!sel.affix().isSkill(), "1star must roll no skills");
        }
        helper.succeed();
    }

    // ============================================================
    // 击杀奖励标定 (ChampionReward) — 信用点池随星 + 青辉石 6star 门槛
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void championRewardScaling(GameTestHelper helper) {
        // 信用点固定池 = star × 600 (删公式必挂)。
        helper.assertTrue(ChampionReward.creditPoolRaw(1) == 600L, "1star credit pool 600");
        helper.assertTrue(ChampionReward.creditPoolRaw(10) == 6_000L, "10star credit pool 6000");
        helper.assertTrue(ChampionReward.creditPoolRaw(5) == 3_000L, "5star credit pool 3000");

        // 青辉石: 1-5star 不掉, 6star+ 掉 (门槛 = CUSTOM_BLOOD_POOL_MIN_STAR = 6)。
        helper.assertTrue(!ChampionReward.dropsAzure(5), "5star drops no azure");
        helper.assertTrue(ChampionReward.dropsAzure(6), "6star drops azure");
        helper.assertTrue(ChampionReward.azureDrop(5) == 0L, "5star azure amount 0");
        helper.assertTrue(ChampionReward.azureDrop(6) == 2L, "6star azure 2");
        helper.assertTrue(ChampionReward.azureDrop(10) == 10L, "10star azure 10 (2 + (10-6)*2)");
        helper.succeed();
    }

    // ============================================================
    // 贡献账本 (ContributionTracker) — 累计 + drain online 现查 + 召唤物排除前置
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void contributionTrackerAccumulateAndDrain(GameTestHelper helper) {
        UUID champ = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        try {
            ContributionTracker.record(champ, p1, 100.0D, 10L);
            ContributionTracker.record(champ, p1, 50.0D, 20L);   // 累加 -> 150
            ContributionTracker.record(champ, p2, 80.0D, 15L);
            ContributionTracker.record(champ, p2, 0.0D, 30L);    // 0 伤不计 (无效贡献)
            helper.assertTrue(ContributionTracker.hasLedger(champ), "ledger exists after record");

            // drain: p1 在线, p2 离线 (online 现查注入)。
            List<DamageContribution> drained = ContributionTracker.drain(champ,
                    id -> id.equals(p1));
            helper.assertTrue(drained.size() == 2, "two contributors drained");
            helper.assertTrue(!ContributionTracker.hasLedger(champ), "ledger cleared after drain");

            DamageContribution c1 = drained.stream().filter(c -> c.playerId().equals(p1)).findFirst().orElseThrow();
            DamageContribution c2 = drained.stream().filter(c -> c.playerId().equals(p2)).findFirst().orElseThrow();
            helper.assertTrue(Math.abs(c1.effectiveDamage() - 150.0D) < EPS, "p1 accumulated 100+50=150");
            helper.assertTrue(c1.firstHitTick() == 10L, "p1 first hit tick is earliest record");
            helper.assertTrue(c1.online(), "p1 online via resolver");
            helper.assertTrue(Math.abs(c2.effectiveDamage() - 80.0D) < EPS, "p2 accumulated 80 (0-damage record dropped)");
            helper.assertTrue(!c2.online(), "p2 offline via resolver (will be forfeited downstream)");
        } finally {
            ContributionTracker.reset(); // 防跨 test 脏账本。
        }
        helper.succeed();
    }

    // ============================================================
    // 血池注册表 (BloodPoolRegistry) — 6star+ 建池/取池/拦死后回收
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bloodPoolRegistryInstallAndRemove(GameTestHelper helper) {
        UUID champ = UUID.randomUUID();
        try {
            helper.assertTrue(!BloodPoolRegistry.has(champ), "no pool before install");
            helper.assertTrue(BloodPoolRegistry.get(champ) == null, "get returns null before install");

            // 8star 27000 有效血建池 (破 1024)。
            BloodPool pool = BloodPoolRegistry.install(champ, 27_000.0D);
            helper.assertTrue(BloodPoolRegistry.has(champ), "pool registered after install");
            helper.assertTrue(Math.abs(pool.maxHp() - 27_000.0D) < EPS, "pool maxHp 27000");
            helper.assertTrue(Math.abs(BloodPoolRegistry.get(champ).currentHp() - 27_000.0D) < EPS, "full HP on install");

            // 扣血走同一池实例 (registry 持引用)。
            BloodPoolRegistry.get(champ).applyDamage(2_000.0D);
            helper.assertTrue(Math.abs(BloodPoolRegistry.get(champ).currentHp() - 25_000.0D) < EPS,
                    "damage persisted in registry pool");

            // 拦死回收: remove 后不在册。
            BloodPool removed = BloodPoolRegistry.remove(champ);
            helper.assertTrue(removed != null, "remove returns the pool");
            helper.assertTrue(!BloodPoolRegistry.has(champ), "pool gone after remove");
            helper.assertTrue(BloodPoolRegistry.remove(champ) == null, "double remove returns null (idempotent)");
        } finally {
            BloodPoolRegistry.reset();
        }
        helper.succeed();
    }

    // ============================================================
    // 点数上限拒绝 (PointBudget.validateCounts) — 词条数/技能数超上限
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pointBudgetCountCapsRejected(GameTestHelper helper) {
        // (a) 词条数超总上限: 1star maxAffixes=1。装 2 条 (生存6/战斗8 各在池预算内, 无互斥, 无技能),
        //     唯一违规是词条数 2 > 1 -> validateCounts 词条数分支拒。删该分支则全合法不抛, 本断言必挂。
        StarRank s1 = StarRank.ofStar(1);
        assertRejected(helper, s1, List.of(
                new AffixSelection(AffixDef.REGEN_TISSUE, AffixQuality.COMMON),
                new AffixSelection(AffixDef.BURNING, AffixQuality.COMMON)),
                "2 affixes > 1star affix cap 1 must reject");

        // (b) 技能数超技能上限: 8star maxSkills=3。装 4 条纯技能 (技能池 14+18+12+14=58 <= 180 预算内,
        //     全 NONE 互斥, 词条数 4 <= 9 上限), 唯一违规是技能数 4 > 3 -> validateCounts 技能数分支拒。
        //     删该分支则全合法不抛, 本断言必挂。
        StarRank s8 = StarRank.ofStar(8);
        assertRejected(helper, s8, List.of(
                new AffixSelection(AffixDef.ELECTRO_CHARGE, AffixQuality.COMMON),
                new AffixSelection(AffixDef.THUNDER, AffixQuality.COMMON),
                new AffixSelection(AffixDef.VISUAL_DISRUPTION, AffixQuality.COMMON),
                new AffixSelection(AffixDef.SELF_REPAIR, AffixQuality.COMMON)),
                "4 skills > 8star skill cap 3 must reject");
        helper.succeed();
    }

    // ============================================================
    // 同族至多一互斥 (PointBudget.validateMutex) — SIZE / DEATH_MARK 族
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pointBudgetSameFamilyMutexSizeAndDeathMark(GameTestHelper helper) {
        // SIZE 族至多一: 巨大化 + 缩小化 (均 SIZE 族, 各在生存池预算内) 须拒。巨大化互斥全部机动、缩小化强制
        // +1 机动, 故此组无机动词条时删掉 requireAtMostOne(SIZE) 仍会因缩小化缺机动另行抛 IAE ——
        // 单看异常类型无法隔离本族规则, 故断言异常消息含 SIZE 族标签 "巨大化/缩小化": 删本族互斥后落到缩小化
        // 缺机动分支, 消息不含该标签 -> 断言必挂 (delete-must-fail 隔离到 SIZE 同族校验)。
        StarRank s5 = StarRank.ofStar(5);
        assertRejectedBecause(helper, s5, List.of(
                new AffixSelection(AffixDef.GIGANTISM, AffixQuality.COMMON),
                new AffixSelection(AffixDef.MINIATURIZATION, AffixQuality.COMMON)),
                "巨大化/缩小化", "gigantism + miniaturization (SIZE family) must reject as same-family");

        // DEATH_MARK 族至多一: 命定之死(8star 超凡+) + 反击单元 (均 DEATH_MARK 族, 均技能, 技能池
        // 120+12=132 <= 180, 技能数 2 <= 3), 唯一违规是同族计数 2 -> requireAtMostOne(DEATH_MARK) 拒。
        // 两者别无其它跨族约束, 删该族互斥即全合法不抛, 类型断言足以 delete-must-fail 隔离。
        StarRank s8 = StarRank.ofStar(8);
        assertRejected(helper, s8, List.of(
                new AffixSelection(AffixDef.DEATH_MARK, AffixQuality.EPIC),
                new AffixSelection(AffixDef.COUNTER_UNIT, AffixQuality.COMMON)),
                "death-mark + counter-unit (DEATH_MARK family) must reject");
        helper.succeed();
    }

    // ============================================================
    // 重型护甲单向跨族禁配 (PointBudget.validateMutex) — 互斥机动/偏斜/刚毅
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pointBudgetHeavyArmorCrossFamilyBans(GameTestHelper helper) {
        // 重型护甲最低高级(RARE)、最低 7star: 用 7star (生存池 165, 重型高级成本 ceil(26*2.5)=65)。
        // 三条单向禁配各自唯一违规 (无同族计数/无预算超支), 删对应 if 即全合法不抛 -> 各断言 delete-must-fail。
        StarRank s7 = StarRank.ofStar(7);

        // 重型护甲 互斥全部机动 (SPRINT)。
        assertRejected(helper, s7, List.of(
                new AffixSelection(AffixDef.HEAVY_ARMOR, AffixQuality.RARE),
                new AffixSelection(AffixDef.SPRINT, AffixQuality.COMMON)),
                "heavy armor + any mobility (sprint) must reject");

        // 重型护甲 互斥偏斜护盾。
        assertRejected(helper, s7, List.of(
                new AffixSelection(AffixDef.HEAVY_ARMOR, AffixQuality.RARE),
                new AffixSelection(AffixDef.DEFLECTOR_SHIELD, AffixQuality.COMMON)),
                "heavy armor + deflector shield must reject");

        // 重型护甲 互斥刚毅护盾 (刚毅最低高级/最低 6star, 用 RARE)。
        assertRejected(helper, s7, List.of(
                new AffixSelection(AffixDef.HEAVY_ARMOR, AffixQuality.RARE),
                new AffixSelection(AffixDef.FORTITUDE_SHIELD, AffixQuality.RARE)),
                "heavy armor + fortitude shield must reject");
        helper.succeed();
    }

    // ============================================================
    // 星表全星级锁值 (StarRank) — spec 第五章逐星逐列, 改任一格必挂
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void starRankFullTableLock(GameTestHelper helper) {
        // spec 第五章《每星主数据表》字面值 (index = star-1)。锁死四池预算 + 词条/技能上限 + 最高品质 +
        // 基础有效HP + 基础单击%; 改 StarRank 任一格对应断言即挂 (防手抄魔数漂移)。
        int[] surv = {10, 20, 35, 55, 80, 120, 165, 240, 330, 440};
        int[] comb = {8, 14, 24, 36, 55, 80, 110, 160, 230, 310};
        int[] mob = {0, 4, 8, 12, 20, 30, 45, 75, 115, 155};
        int[] skill = {0, 0, 15, 25, 45, 70, 110, 180, 260, 360};
        int[] affixCap = {1, 2, 3, 4, 5, 6, 7, 9, 11, 13};
        int[] skillCap = {0, 0, 1, 1, 1, 2, 2, 3, 3, 4};
        AffixQuality[] maxQ = {
                AffixQuality.COMMON, AffixQuality.COMMON, AffixQuality.UNCOMMON, AffixQuality.UNCOMMON,
                AffixQuality.RARE, AffixQuality.RARE, AffixQuality.EPIC, AffixQuality.EPIC,
                AffixQuality.LEGENDARY, AffixQuality.LEGENDARY};
        double[] effHp = {135.0D, 225.0D, 360.0D, 540.0D, 765.0D,
                2_700.0D, 6_000.0D, 27_000.0D, 45_000.0D, 73_000.0D};
        double[] hitPct = {0.04D, 0.05D, 0.06D, 0.08D, 0.10D, 0.12D, 0.14D, 0.16D, 0.18D, 0.20D};

        for (int s = StarRank.MIN_STAR; s <= StarRank.MAX_STAR; s++) {
            int i = s - 1;
            StarRank r = StarRank.ofStar(s);
            helper.assertTrue(r.survivalBudget() == surv[i], "star " + s + " survival budget must be " + surv[i]);
            helper.assertTrue(r.combatBudget() == comb[i], "star " + s + " combat budget must be " + comb[i]);
            helper.assertTrue(r.mobilityBudget() == mob[i], "star " + s + " mobility budget must be " + mob[i]);
            helper.assertTrue(r.skillBudget() == skill[i], "star " + s + " skill budget must be " + skill[i]);
            helper.assertTrue(r.maxAffixes() == affixCap[i], "star " + s + " affix cap must be " + affixCap[i]);
            helper.assertTrue(r.maxSkills() == skillCap[i], "star " + s + " skill cap must be " + skillCap[i]);
            helper.assertTrue(r.maxQuality() == maxQ[i], "star " + s + " max quality must be " + maxQ[i]);
            helper.assertTrue(Math.abs(r.baseEffectiveHp() - effHp[i]) < EPS,
                    "star " + s + " base effective HP must be " + effHp[i]);
            helper.assertTrue(Math.abs(r.baseSingleHitPct() - hitPct[i]) < EPS,
                    "star " + s + " base single-hit pct must be " + hitPct[i]);
        }
        helper.succeed();
    }

    // ---- helpers ----

    private static void assertRejected(GameTestHelper helper, StarRank rank,
                                       List<AffixSelection> selections, String msg) {
        boolean rejected = false;
        try {
            PointBudget.allocate(rank, selections);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, msg);
    }

    /**
     * 断言装配被拒【且】拒绝原因消息含指定标签 needle。用于同族互斥无法靠异常类型隔离的场景 (SIZE 族:
     * 巨大化/缩小化两成员各自另有跨族约束, 删同族校验仍会抛别的 IAE) —— 只有拒绝消息命中本族标签才算命中
     * 被测规则; 落到别的分支 (消息不含 needle) 视为未拒, 断言挂 (delete-must-fail 隔离到目标校验)。
     */
    private static void assertRejectedBecause(GameTestHelper helper, StarRank rank,
                                              List<AffixSelection> selections, String needle, String msg) {
        boolean rejectedForReason = false;
        try {
            PointBudget.allocate(rank, selections);
        } catch (IllegalArgumentException expected) {
            String detail = expected.getMessage();
            rejectedForReason = detail != null && detail.contains(needle);
        }
        helper.assertTrue(rejectedForReason, msg);
    }
}

package com.miningdim.champion;

import com.miningdim.champion.aggregate.DotAggregator;
import com.miningdim.champion.aggregate.PlayerControlAggregator;
import com.miningdim.champion.aggregate.RetaliationAggregator;
import com.miningdim.champion.bloodpool.BloodPool;
import com.miningdim.champion.reward.ContributionPool;
import com.miningdim.champion.reward.DamageContribution;
import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 精英怪星级词条系统纯逻辑 GameTest 补充集 (ChampionStarAffix spec 第十四章实现拆分 9 的边界/异常/反例补强)。
 *
 * 与 {@link ChampionGameTests} 同包但独立 batch ("champion_edge"), 聚焦既有头部用例未覆盖的边界:
 *  - 词条表前导 0 占位档反解最低可用品质 (HEAVY_ARMOR/LITTLE_BOY/DEATH_MARK) + costAt/valueFor/secondaryValueFor;
 *  - AffixSelection 构造期拒绝低于最低可用档的品质 (异常自然冒泡);
 *  - 红线/夹断函数对非法输入 (减伤率越界 / 负 %maxHP / 召唤星越界 / 负反伤 / 负减速) 抛 IllegalArgumentException;
 *  - StarRank budgetFor 与各池 getter 跨全部 10 星一致 + baseSingleHitPct 表 + ofStar 越界拒;
 *  - AffixPool 四池 convertsRemainderToBaseStats 取值 (仅技能池 false);
 *  - DotAggregator 恰撞顶/全零边界 + 逐源保序; RetaliationAggregator 5s 窗满后下一窗额度复位;
 *  - PlayerControlAggregator 碎片化控制下无 2s 连续自由窗 (hasMinFreeWindow == false 反例) + clampSlow 恰封顶;
 *  - ContributionPool.distribute 非整除权重下末名吸收 round 余数保总和恒等;
 *  - AffixDef 四池静态视图分区完备 (10/10/5/10 = 35 不重不漏且不可变)。
 *
 * 严禁触 Champions 加载路径 (compileOnly 铁律): 全部断言纯逻辑层业务结果, 不引用 top.theillusivec4.champions.*。
 * 断言均为具体数值且含反例 (删被测核心逻辑/夹断/反解后对应 test 立挂)。template = "empty"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionEdgeGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_edge";
    private static final double EPS = 1e-6D;

    // ============================================================
    // 前导 0 占位档反解最低可用品质 + costAt/valueFor/secondaryValueFor (AffixDef)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void affixMinUsableQualityFromLeadingZeros(GameTestHelper helper) {
        // 重型护甲前两档填 0 (最低高级): 反解 minUsableQuality = RARE (index 2 首个非 0)。
        helper.assertTrue(AffixDef.HEAVY_ARMOR.minUsableQuality() == AffixQuality.RARE,
                "heavy armor min usable quality reverse-derives to RARE (first non-zero tier)");
        // 小男孩/命定前三档填 0 (最低超凡): 反解 = EPIC (index 3)。
        helper.assertTrue(AffixDef.LITTLE_BOY.minUsableQuality() == AffixQuality.EPIC,
                "little boy min usable quality is EPIC (leading three zeros)");
        helper.assertTrue(AffixDef.DEATH_MARK.minUsableQuality() == AffixQuality.EPIC,
                "death mark min usable quality is EPIC");
        // 普通词条首档即有效: 燃烧 = COMMON。
        helper.assertTrue(AffixDef.BURNING.minUsableQuality() == AffixQuality.COMMON,
                "burning usable from COMMON");

        // costAt = ceil(baseCost * 品质系数)。重型护甲 c26: RARE 2.5 -> 65, EPIC 4.0 -> 104, LEGENDARY 6.5 -> 169。
        helper.assertTrue(AffixDef.HEAVY_ARMOR.costAt(AffixQuality.RARE) == 65, "heavy armor RARE cost ceil(26*2.5)=65");
        helper.assertTrue(AffixDef.HEAVY_ARMOR.costAt(AffixQuality.EPIC) == 104, "heavy armor EPIC cost ceil(26*4.0)=104");
        helper.assertTrue(AffixDef.HEAVY_ARMOR.costAt(AffixQuality.LEGENDARY) == 169,
                "heavy armor LEGENDARY cost ceil(26*6.5)=169");
        // ceil 防小数破整: 燃烧 c8 中级 1.6 = 12.8 -> ceil 13 (非截断 12)。
        helper.assertTrue(AffixDef.BURNING.costAt(AffixQuality.UNCOMMON) == 13, "burning UNCOMMON ceil(8*1.6=12.8)=13");
        helper.assertTrue(AffixDef.THORNS.costAt(AffixQuality.UNCOMMON) == 15, "thorns UNCOMMON ceil(9*1.6=14.4)=15");

        // valueFor 取该档主数值 (HEAVY_ARMOR 高级减伤率 = 0.35)。
        helper.assertTrue(Math.abs(AffixDef.HEAVY_ARMOR.valueFor(AffixQuality.RARE) - 0.35D) < EPS,
                "heavy armor RARE bullet resistance 0.35");
        helper.assertTrue(Math.abs(AffixDef.LITTLE_BOY.valueFor(AffixQuality.EPIC) - 0.70D) < EPS,
                "little boy EPIC AOE 70% maxHP");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void affixSecondaryValuesAndUnlock(GameTestHelper helper) {
        // 副数值: 寒霜有 (减速%), 燃烧无。
        helper.assertTrue(AffixDef.FROST.hasSecondaryValues(), "frost defines secondary (slow) values");
        helper.assertTrue(!AffixDef.BURNING.hasSecondaryValues(), "burning has no secondary values");
        helper.assertTrue(Math.abs(AffixDef.FROST.secondaryValueFor(AffixQuality.COMMON) - 0.04D) < EPS,
                "frost COMMON slow 4%");
        helper.assertTrue(AffixDef.BURNING.secondaryValueFor(AffixQuality.LEGENDARY) == 0.0D,
                "burning secondary always 0 (none defined)");
        // 天雷副数值 = 落点数 (闪耀 6 点); 支援副数值 = 同时存活数 (普通 2)。
        helper.assertTrue(Math.abs(AffixDef.THUNDER.secondaryValueFor(AffixQuality.LEGENDARY) - 6.0D) < EPS,
                "thunder LEGENDARY 6 strike points");
        helper.assertTrue(Math.abs(AffixDef.SUMMON_SUPPORT.secondaryValueFor(AffixQuality.COMMON) - 2.0D) < EPS,
                "summon COMMON 2 alive cap");

        // isUnlockedAt 双门槛 (最低星 + 该星最高品质 >= 词条最低可用档)。
        // 重型护甲 (minStar 7, minUsable RARE): 6star 星不足 -> false; 7star (maxQuality EPIC>=RARE) -> true。
        helper.assertTrue(!AffixDef.HEAVY_ARMOR.isUnlockedAt(StarRank.ofStar(6)), "heavy armor not unlocked at 6star (min 7)");
        helper.assertTrue(AffixDef.HEAVY_ARMOR.isUnlockedAt(StarRank.ofStar(7)), "heavy armor unlocks at 7star");
        // 小男孩 (minStar 7, minUsable EPIC): 7star maxQuality EPIC 恰覆盖 -> true。
        helper.assertTrue(AffixDef.LITTLE_BOY.isUnlockedAt(StarRank.ofStar(7)), "little boy unlocks at 7star (EPIC available)");
        helper.assertTrue(!AffixDef.LITTLE_BOY.isUnlockedAt(StarRank.ofStar(6)), "little boy locked at 6star (min star 7)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void affixSelectionRejectsBelowMinUsable(GameTestHelper helper) {
        // 重型护甲最低可用 RARE: 用 COMMON 构造须拒 (前导 0 占位档不可装配)。
        boolean rejectedCommon = false;
        try {
            new AffixSelection(AffixDef.HEAVY_ARMOR, AffixQuality.COMMON);
        } catch (IllegalArgumentException expected) {
            rejectedCommon = true;
        }
        helper.assertTrue(rejectedCommon, "heavy armor COMMON below min usable RARE must reject at construction");

        // 命定之死最低 EPIC: RARE 构造须拒。
        boolean rejectedRare = false;
        try {
            new AffixSelection(AffixDef.DEATH_MARK, AffixQuality.RARE);
        } catch (IllegalArgumentException expected) {
            rejectedRare = true;
        }
        helper.assertTrue(rejectedRare, "death mark RARE below min usable EPIC must reject");

        // 恰最低可用档合法: 重型护甲 RARE 构造成功且 cost = 65。
        AffixSelection legal = new AffixSelection(AffixDef.HEAVY_ARMOR, AffixQuality.RARE);
        helper.assertTrue(legal.cost() == 65, "heavy armor RARE selection legal, cost 65");
        helper.succeed();
    }

    // ============================================================
    // 红线/夹断函数非法输入抛异常 (异常必须痛, 不静默兜底)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void clampRejectsInvalidInputs(GameTestHelper helper) {
        // 净减伤: 减伤率 < 0 抛。
        assertThrowsIae(helper, () -> ChampionRedlines.clampNetKeepFactor(-0.1D),
                "negative reduction rate must throw");
        // 减伤率 > 1 抛。
        assertThrowsIae(helper, () -> ChampionRedlines.clampNetKeepFactor(1.5D),
                "reduction rate > 1 must throw");

        // 单击名义 %maxHP < 0 抛。
        assertThrowsIae(helper, () -> ChampionAffixValues.clampNormalHitPct(StarRank.ofStar(5), -0.01D),
                "negative normal hit pct must throw");
        // 穿甲合计任一负值抛。
        assertThrowsIae(helper, () -> ChampionAffixValues.clampPiercingPlusNormal(-0.1D, 0.1D),
                "negative piercing component must throw");
        // 召唤星越界 (0 < MIN_STAR) 抛。
        assertThrowsIae(helper, () -> ChampionAffixValues.summonStar(0),
                "summonStar 0 out of [1,10] must throw");
        assertThrowsIae(helper, () -> ChampionAffixValues.summonStar(11),
                "summonStar 11 out of [1,10] must throw");

        // 反伤负申请抛 (构造合法后 admit 负值)。
        assertThrowsIae(helper, () -> new RetaliationAggregator(80.0D).admit(-1.0D, 0L),
                "negative retaliation request must throw");
        // 减速负值抛。
        assertThrowsIae(helper, () -> PlayerControlAggregator.clampSlow(-0.2D),
                "negative slow must throw");
        // 控制负时长抛。
        assertThrowsIae(helper, () -> new PlayerControlAggregator().admit(0L, -5L),
                "negative control ticks must throw");
        // StarRank.ofStar 越界抛。
        assertThrowsIae(helper, () -> StarRank.ofStar(0), "ofStar 0 must throw");
        assertThrowsIae(helper, () -> StarRank.ofStar(11), "ofStar 11 must throw");
        helper.succeed();
    }

    // ============================================================
    // 星表全星一致性: budgetFor 等于各池 getter + baseSingleHitPct 表 (StarRank)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void starRankBudgetForMatchesGetters(GameTestHelper helper) {
        // 全 10 星: budgetFor(pool) 与对应 getter 逐星一致 (switch 漏池/串池立挂)。
        for (int star = StarRank.MIN_STAR; star <= StarRank.MAX_STAR; star++) {
            StarRank r = StarRank.ofStar(star);
            helper.assertTrue(r.budgetFor(AffixPool.SURVIVAL) == r.survivalBudget(),
                    "budgetFor SURVIVAL == survivalBudget at star " + star);
            helper.assertTrue(r.budgetFor(AffixPool.COMBAT) == r.combatBudget(),
                    "budgetFor COMBAT == combatBudget at star " + star);
            helper.assertTrue(r.budgetFor(AffixPool.MOBILITY) == r.mobilityBudget(),
                    "budgetFor MOBILITY == mobilityBudget at star " + star);
            helper.assertTrue(r.budgetFor(AffixPool.SKILL) == r.skillBudget(),
                    "budgetFor SKILL == skillBudget at star " + star);
            helper.assertTrue(r.star() == star, "star() round-trips ofStar at " + star);
        }

        // 基础单击 %maxHP 表两端硬值 (4% / 20%) + 中段 (5star 10%)。
        helper.assertTrue(Math.abs(StarRank.ofStar(1).baseSingleHitPct() - 0.04D) < EPS, "1star base hit 4%");
        helper.assertTrue(Math.abs(StarRank.ofStar(5).baseSingleHitPct() - 0.10D) < EPS, "5star base hit 10%");
        helper.assertTrue(Math.abs(StarRank.ofStar(10).baseSingleHitPct() - 0.20D) < EPS, "10star base hit 20%");

        // 基础有效 HP 6star 阶跃破 1024 (765 -> 2700)。
        helper.assertTrue(StarRank.ofStar(5).baseEffectiveHp() < BloodPool.VANILLA_MAX_HEALTH_CLAMP,
                "5star base HP 765 stays under vanilla 1024 clamp");
        helper.assertTrue(StarRank.ofStar(6).baseEffectiveHp() > BloodPool.VANILLA_MAX_HEALTH_CLAMP,
                "6star base HP 2700 breaks past vanilla 1024 clamp");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void affixPoolRemainderConversionFlags(GameTestHelper helper) {
        // 仅技能池剩余不转膨胀; 生存/战斗/机动均转。
        helper.assertTrue(AffixPool.SURVIVAL.convertsRemainderToBaseStats(), "survival remainder converts");
        helper.assertTrue(AffixPool.COMBAT.convertsRemainderToBaseStats(), "combat remainder converts");
        helper.assertTrue(AffixPool.MOBILITY.convertsRemainderToBaseStats(), "mobility remainder converts");
        helper.assertTrue(!AffixPool.SKILL.convertsRemainderToBaseStats(), "skill remainder never converts");
        helper.succeed();
    }

    // ============================================================
    // DoT 聚合边界: 恰撞顶 (合计==cap 不衰减但 wasCapped) / 全零 / 逐源保序 (DotAggregator)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void dotAggregateBoundaryAndZero(GameTestHelper helper) {
        double maxHp = 1000.0D; // cap = 150。
        // 恰撞顶: 100 + 50 = 150 == cap。<= cap 分支故不衰减, 逐源原样 [100,50], 但 wasCapped (total >= cap) = true。
        DotAggregator.Result exact = DotAggregator.aggregate(maxHp, 100.0D, 50.0D);
        helper.assertTrue(Math.abs(exact.cap() - 150.0D) < EPS, "cap = 15% maxHP = 150");
        helper.assertTrue(Math.abs(exact.total() - 150.0D) < EPS, "exact-at-cap total stays 150");
        double[] exactSrc = exact.perSource();
        helper.assertTrue(Math.abs(exactSrc[0] - 100.0D) < EPS && Math.abs(exactSrc[1] - 50.0D) < EPS,
                "exact-at-cap per-source unscaled (order preserved [100,50])");
        helper.assertTrue(exact.wasCapped(), "exact-at-cap flagged capped (total >= cap)");

        // 全零: total 0, cap 仍 150, 未撞顶。
        DotAggregator.Result zero = DotAggregator.aggregate(maxHp, 0.0D, 0.0D);
        helper.assertTrue(zero.total() == 0.0D, "all-zero DoT total 0");
        helper.assertTrue(!zero.wasCapped(), "all-zero not capped");

        // 逐源保序 (超顶衰减后顺序不乱): 三源 [300,100,50] (sum 450 > 150) -> 衰减比 150/450, 0 号 = 300*150/450 = 100。
        DotAggregator.Result scaled = DotAggregator.aggregate(maxHp, 300.0D, 100.0D, 50.0D);
        double[] s = scaled.perSource();
        helper.assertTrue(Math.abs(s[0] - 100.0D) < 1e-3D, "largest source first stays first (300 scaled to 100)");
        helper.assertTrue(s[0] > s[1] && s[1] > s[2], "descending input order preserved after scaling");
        helper.assertTrue(Math.abs(s[0] + s[1] + s[2] - 150.0D) < EPS, "scaled sources sum exactly to cap 150");
        helper.succeed();
    }

    // ============================================================
    // 反伤 5s 窗满后下一窗额度复位 (RetaliationAggregator) — 删窗滚动必挂
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void retaliationWindowResetsAfterFiveSeconds(GameTestHelper helper) {
        // maxHp=80: 秒上限 24, 窗上限 32, 窗 = 5s = 100 tick。
        RetaliationAggregator agg = new RetaliationAggregator(80.0D);
        // tick 0: 灌满秒上限 24 (窗累计 24)。
        helper.assertTrue(Math.abs(agg.admit(1000.0D, 0L) - 24.0D) < EPS, "tick0 clamps to per-second 24");
        // tick 20 (新秒, 同窗): 窗仅剩 32-24=8。
        helper.assertTrue(Math.abs(agg.admit(1000.0D, 20L) - 8.0D) < EPS, "tick20 bounded by window remainder 8");
        // tick 40 (新秒, 同窗): 窗已满 32 -> 0。
        helper.assertTrue(agg.admit(1000.0D, 40L) == 0.0D, "tick40 window exhausted -> 0");
        helper.assertTrue(Math.abs(agg.windowAccumulated() - 32.0D) < EPS, "window accumulated maxed at 32");

        // tick 100: 跨 5s 窗 (100-0 >= 100) -> 窗复位, 重新可领满秒上限 24。
        double afterWindow = agg.admit(1000.0D, 100L);
        helper.assertTrue(Math.abs(afterWindow - 24.0D) < EPS, "after 5s window reset, full per-second 24 available again");
        helper.assertTrue(Math.abs(agg.windowAccumulated() - 24.0D) < EPS, "new window accumulated reset to 24");
        helper.succeed();
    }

    // ============================================================
    // 控制聚合反例: 碎片化控制下无 2s 连续自由窗 (PlayerControlAggregator) — hasMinFreeWindow == false
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void controlFragmentedHasNoFreeWindow(GameTestHelper helper) {
        // 窗 [0,140), 受控上限 70 tick。把 70 tick 拆成三段塞出全部 < 40tick 的空隙:
        // [10,40)=30 + [60,80)=20 + [100,120)=20 = 70 (恰满 cap); 空隙 [0,10) [40,60) [80,100) [120,140) 均 <=20 < 40。
        PlayerControlAggregator agg = new PlayerControlAggregator();
        helper.assertTrue(agg.admit(10L, 30L) == 30L, "first 30-tick control admitted");
        helper.assertTrue(agg.admit(60L, 20L) == 20L, "second 20-tick control admitted (50 busy total)");
        helper.assertTrue(agg.admit(100L, 20L) == 20L, "third 20-tick control admitted (70 busy = cap)");
        // 全部空隙 < 40 tick -> 无连续 2s 自由窗 (反例: 删 hasMinFreeWindow 的空隙扫描则恒 true, 此处必挂)。
        helper.assertTrue(!agg.hasMinFreeWindow(0L),
                "fragmented control leaves no >=2s (40-tick) continuous free window");

        // 进一步申请 (超 70 cap) 须被夹到 0 (额度耗尽)。
        helper.assertTrue(agg.admit(130L, 20L) == 0L, "control budget exhausted at cap 70 -> 0 granted");

        // clampSlow 恰封顶: 0.50 原样 (= 上限, 非夹下); 0.50001 夹回 0.50。
        helper.assertTrue(Math.abs(PlayerControlAggregator.clampSlow(0.50D) - 0.50D) < EPS, "0.50 slow at cap passes");
        helper.assertTrue(Math.abs(PlayerControlAggregator.clampSlow(0.6D) - 0.50D) < EPS, "0.60 slow clamps to 0.50");
        helper.assertTrue(PlayerControlAggregator.clampSlow(0.0D) == 0.0D, "0.0 slow passes through");
        helper.succeed();
    }

    // ============================================================
    // 贡献瓜分非整除权重: 末名吸收 round 余数保总和恒等 (ContributionPool)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void contributionDistributionRoundingRemainder(GameTestHelper helper) {
        // 三玩家等伤 [1,1,1] 瓜分 1000: 各权重 1/3, round(333.33)=333; 末名吸收余数 = 1000-666 = 334。
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        // bossHp=1000 -> 0.5%=5; teamAvg=1, 15%=0.15; 各伤 1>=0.15 走门槛二全合格。
        List<DamageContribution> contribs = List.of(
                new DamageContribution(a, 1.0D, 1L, true),
                new DamageContribution(b, 1.0D, 2L, true),
                new DamageContribution(c, 1.0D, 3L, true));
        Map<UUID, Long> payout = ContributionPool.distribute(contribs, 1_000.0D, 1_000L);
        helper.assertTrue(payout.size() == 3, "all three qualify via threshold-two (low team avg)");
        helper.assertTrue(payout.get(a) == 333L, "first weight round(1000/3) = 333");
        helper.assertTrue(payout.get(b) == 333L, "second weight round(1000/3) = 333");
        helper.assertTrue(payout.get(c) == 334L, "last absorbs +1 round remainder (1000-666) = 334");
        long total = payout.get(a) + payout.get(b) + payout.get(c);
        helper.assertTrue(total == 1_000L, "non-divisible weights still sum exactly to fixed pool (remainder absorbed)");

        // 全离线: 整池不发 (online=false 全没收)。
        List<DamageContribution> allOffline = List.of(
                new DamageContribution(UUID.randomUUID(), 5_000.0D, 1L, false),
                new DamageContribution(UUID.randomUUID(), 5_000.0D, 2L, false));
        Map<UUID, Long> offlinePayout = ContributionPool.distribute(allOffline, 100_000.0D, 4_000L);
        helper.assertTrue(offlinePayout.isEmpty(), "all-offline forfeits entire pool (no payout)");

        // 空池 (fixedPoolRaw 0): 即便有合格者也不发。
        Map<UUID, Long> emptyPool = ContributionPool.distribute(contribs, 1_000.0D, 0L);
        helper.assertTrue(emptyPool.isEmpty(), "zero fixed pool yields no payout");
        helper.succeed();
    }

    // ============================================================
    // 四池静态视图分区完备: 10/10/5/10 = 35 不重不漏且不可变 (AffixDef)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void affixPoolPartitionComplete(GameTestHelper helper) {
        Set<AffixDef> survival = AffixDef.survivalAffixes();
        Set<AffixDef> combat = AffixDef.combatAffixes();
        Set<AffixDef> mobility = AffixDef.mobilityAffixes();
        Set<AffixDef> skill = AffixDef.skillAffixes();

        // 各池基数硬值 (spec 第七章 7.1-7.4: 10/10/5/10)。
        helper.assertTrue(survival.size() == 10, "survival pool has 10 affixes");
        helper.assertTrue(combat.size() == 10, "combat pool has 10 affixes");
        helper.assertTrue(mobility.size() == 5, "mobility pool has 5 affixes");
        helper.assertTrue(skill.size() == 10, "skill pool has 10 affixes");

        // 合计 = 词条总数 35 (无遗漏)。
        int total = survival.size() + combat.size() + mobility.size() + skill.size();
        helper.assertTrue(total == AffixDef.values().length, "four pools partition all 35 affixes (no orphan)");
        helper.assertTrue(total == 35, "total affix count is 35");

        // 不重叠: 每条词条归属与其 pool() 一致 (生存视图全 SURVIVAL, 等)。
        for (AffixDef d : survival) {
            helper.assertTrue(d.pool() == AffixPool.SURVIVAL, d + " in survival view must be SURVIVAL pool");
        }
        for (AffixDef d : skill) {
            helper.assertTrue(d.pool() == AffixPool.SKILL, d + " in skill view must be SKILL pool");
            helper.assertTrue(d.isSkill(), d + " in skill view must be a skill affix");
        }

        // 不可变视图: 改动须抛 (不容外部污染权威表)。
        boolean immutable = false;
        try {
            survival.add(AffixDef.BURNING);
        } catch (UnsupportedOperationException expected) {
            immutable = true;
        }
        helper.assertTrue(immutable, "pool views are unmodifiable (defensive against table mutation)");
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

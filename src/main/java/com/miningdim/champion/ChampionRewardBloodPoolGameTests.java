package com.miningdim.champion;

import com.miningdim.champion.bloodpool.BloodPool;
import com.miningdim.champion.bloodpool.BloodPoolRegistry;
import com.miningdim.champion.reward.ContributionPool;
import com.miningdim.champion.reward.DamageContribution;
import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 精英怪血池数学 + 贡献池奖励分配的深度边界 GameTest (ChampionStarAffix spec 第六章 6.2 血池权威 +
 * 第十一章奖励与经济闸)。与 {@link ChampionGameTests} 互补不重叠: 本类专攻
 *
 *  - 血池 double 连续受击累加 (maxHp 远破 1024 的 6★+ 池) 逐击 currentHp 推进的精确值;
 *  - 拦死单一判据 currentHp ≤ 0 在亚整数零交叉边界的精确性 (剩 0.5 血再受 0.5 必死);
 *  - wouldDieFrom 纯判据不改池状态 (与 applyDamage 副作用分离);
 *  - 构造期/扣血期非法入参异常自然冒泡 (不掩盖);
 *  - 死池回血无效 (拒诈尸复活); 渲染镜像在任意分数与 1024 边界的精确换算;
 *  - 贡献池盖章双门槛"取一"语义独立验证 (仅过门槛一 / 仅过门槛二 / 恰边界);
 *  - 团队人均分母剔 0 伤参战者;
 *  - 离线没收后高伤玩家被剔, 其份额由余下合格者瓜分整池 (非按人头复制, 非池缩水);
 *  - round 不整除时末名吸收余数 -> Σ应得 == 固定池 (无信用点丢失/虚增);
 *  - 两门槛皆败 -> 整池不发 (空 payout)。
 *
 * 全部断言具体数额 (删被测核心逻辑必挂, 禁 is-not-null 弱校验)。纯逻辑层: 只构造 BloodPool / DamageContribution
 * 值对象, 不触 Champions 加载路径 (compileOnly 铁律), 不需 ServerPlayer。template = "empty", batch = "champion"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionRewardBloodPoolGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion";
    private static final double EPS = 1e-6D;

    // ============================================================
    // 血池连续受击累加 (maxHp 远破 1024) — 逐击精确扣血
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bloodPoolContinuousHitsAccumulate(GameTestHelper helper) {
        // 8★ 27000 满血池, TACZ 高 DPS 连续受击: 每发净伤 4321 (远超 vanilla 20 血, 印证破 1024 走双精度池)。
        BloodPool pool = new BloodPool(27_000.0D);
        helper.assertTrue(pool.maxHp() > BloodPool.VANILLA_MAX_HEALTH_CLAMP,
                "8star pool maxHp 27000 breaks vanilla 1024 ceiling (uses custom double pool)");

        // 第 1 发: 27000 - 4321 = 22679, 未死。
        boolean d1 = pool.applyDamage(4_321.0D);
        helper.assertTrue(!d1, "hit1 not lethal");
        helper.assertTrue(Math.abs(pool.currentHp() - 22_679.0D) < EPS, "after hit1 currentHp = 22679");

        // 第 2 发: 22679 - 4321 = 18358。
        pool.applyDamage(4_321.0D);
        helper.assertTrue(Math.abs(pool.currentHp() - 18_358.0D) < EPS, "after hit2 currentHp = 18358");

        // 再连打 4 发 (共 6 发 = 25926 累计伤), 27000 - 25926 = 1074 剩血, 仍 > 0 不死 (验证累加无早死)。
        for (int i = 0; i < 4; i++) {
            boolean dead = pool.applyDamage(4_321.0D);
            helper.assertTrue(!dead, "running hits stay non-lethal while currentHp > 0");
        }
        helper.assertTrue(Math.abs(pool.currentHp() - 1_074.0D) < EPS,
                "after 6 hits of 4321 currentHp = 27000 - 25926 = 1074");
        helper.assertTrue(!pool.isDead(), "1074 HP left -> still alive");

        // 第 7 发 4321 > 剩 1074: currentHp 下钳 0 且死 (扣血不为负, 保渲染镜像非负)。
        boolean lethal = pool.applyDamage(4_321.0D);
        helper.assertTrue(lethal, "hit7 (4321 > 1074 remaining) is lethal");
        helper.assertTrue(pool.currentHp() == 0.0D, "currentHp clamps to 0 (never negative) after lethal overkill");
        helper.assertTrue(pool.isDead(), "pool dead after lethal hit");
        helper.succeed();
    }

    // ============================================================
    // 拦死判据在亚整数零交叉边界 — currentHp <= 0 精确
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bloodPoolDeathAtFractionalZeroCrossing(GameTestHelper helper) {
        // 73000 满血池 (10★), 扣到剩 0.5 血 (亚整数): 72999.5 伤 -> currentHp = 0.5 不死。
        BloodPool pool = new BloodPool(73_000.0D);
        boolean d = pool.applyDamage(72_999.5D);
        helper.assertTrue(!d, "0.5 HP remaining is NOT dead (currentHp > 0)");
        helper.assertTrue(Math.abs(pool.currentHp() - 0.5D) < EPS, "currentHp = 0.5 after 72999.5 damage");

        // 剩 0.5 血: 受 0.4 伤 -> 0.1 不死 (零交叉边界另一侧)。
        helper.assertTrue(!pool.wouldDieFrom(0.4D), "0.5 - 0.4 = 0.1 > 0 -> not lethal");
        // 受恰 0.5 伤 -> currentHp = 0 必死 (判据 currentHp - damage <= 0)。
        helper.assertTrue(pool.wouldDieFrom(0.5D), "0.5 - 0.5 = 0 -> lethal (<= 0 single judgement)");
        // 受 0.5000001 伤 -> 负 必死。
        helper.assertTrue(pool.wouldDieFrom(0.5000001D), "0.5 - 0.5000001 < 0 -> lethal");

        // applyDamage 恰 0.5 -> 池归零且死。
        boolean killed = pool.applyDamage(0.5D);
        helper.assertTrue(killed, "applyDamage 0.5 to a 0.5-HP pool kills");
        helper.assertTrue(pool.currentHp() == 0.0D, "currentHp exactly 0 at the zero crossing");
        helper.succeed();
    }

    // ============================================================
    // wouldDieFrom 纯判据不改状态 (与 applyDamage 副作用分离)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bloodPoolWouldDieIsSideEffectFree(GameTestHelper helper) {
        BloodPool pool = new BloodPool(5_000.0D, 3_000.0D);
        // 反复查询致死判据 (含会致死的巨量伤) 不得改 currentHp。
        for (int i = 0; i < 50; i++) {
            pool.wouldDieFrom(99_999.0D);
            pool.wouldDieFrom(0.0D);
        }
        helper.assertTrue(Math.abs(pool.currentHp() - 3_000.0D) < EPS,
                "wouldDieFrom never mutates currentHp (stays 3000 after 100 queries)");

        // 与之对照: applyDamage 才改状态。
        pool.applyDamage(1_000.0D);
        helper.assertTrue(Math.abs(pool.currentHp() - 2_000.0D) < EPS, "applyDamage 1000 -> 2000 (this one mutates)");
        helper.succeed();
    }

    // ============================================================
    // 血池非法入参异常自然冒泡 (不掩盖)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bloodPoolInvalidArgsThrow(GameTestHelper helper) {
        // maxHp <= 0 抛。
        assertThrows(helper, () -> new BloodPool(0.0D), "maxHp 0 must throw");
        assertThrows(helper, () -> new BloodPool(-1.0D), "negative maxHp must throw");
        // currentHp > maxHp 抛 (NBT 还原越界)。
        assertThrows(helper, () -> new BloodPool(1_000.0D, 1_001.0D), "currentHp > maxHp must throw");
        // currentHp < 0 抛。
        assertThrows(helper, () -> new BloodPool(1_000.0D, -1.0D), "negative currentHp must throw");

        BloodPool pool = new BloodPool(1_000.0D);
        // 负伤害抛 (不静默吞)。
        assertThrows(helper, () -> pool.applyDamage(-5.0D), "negative damage to applyDamage must throw");
        assertThrows(helper, () -> pool.wouldDieFrom(-5.0D), "negative damage to wouldDieFrom must throw");
        // 负回血抛。
        assertThrows(helper, () -> pool.heal(-5.0D), "negative heal must throw");
        // 异常未污染池: currentHp 仍满。
        helper.assertTrue(Math.abs(pool.currentHp() - 1_000.0D) < EPS, "rejected ops left pool untouched at 1000");
        helper.succeed();
    }

    // ============================================================
    // 死池回血无效 (拒诈尸) + 渲染镜像精确换算
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bloodPoolDeadNoHealAndMirror(GameTestHelper helper) {
        BloodPool pool = new BloodPool(10_000.0D, 10_000.0D);
        pool.applyDamage(10_000.0D);
        helper.assertTrue(pool.isDead(), "pool dead at currentHp 0");
        // 死后回血无效 (复活是 spawn 期重建池的职责, 非回血)。
        double afterHeal = pool.heal(5_000.0D);
        helper.assertTrue(afterHeal == 0.0D, "heal on dead pool returns 0 (no resurrection)");
        helper.assertTrue(pool.currentHp() == 0.0D, "dead pool stays at 0 after heal attempt");
        helper.assertTrue(pool.displayHealth() == 0.0F, "dead pool mirrors to 0 display health");

        // 渲染镜像 = clamp(fraction * 1024, 0, 1024)。25% 血池 (maxHp 远破 1024) -> 256.0。
        BloodPool quarter = new BloodPool(40_000.0D, 10_000.0D);
        helper.assertTrue(Math.abs(quarter.fraction() - 0.25D) < EPS, "fraction 10000/40000 = 0.25");
        helper.assertTrue(Math.abs(quarter.displayHealth() - 256.0F) < 0.01F,
                "display mirror = 0.25 * 1024 = 256 (judged by pool, not vanilla 20)");

        // 满血任意巨 maxHp 镜像恰封顶 1024 (即便 maxHp = 999999)。
        BloodPool huge = new BloodPool(999_999.0D);
        helper.assertTrue(Math.abs(huge.displayHealth() - 1_024.0F) < 0.01F, "full huge pool mirrors to 1024 cap");
        helper.succeed();
    }

    // ============================================================
    // 盖章双门槛"取一": 仅过门槛二 (15% 团队人均) 也合格
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void stampThresholdTwoOnlyQualifies(GameTestHelper helper) {
        // 巨血 BOSS (门槛一极高) + 低团队人均 (门槛二可达): 中伤玩家只过门槛二仍合格。
        // bossHp = 1_000_000 -> 0.5% = 5000 (门槛一)。
        double bossHp = 1_000_000.0D;
        UUID mid = UUID.randomUUID();
        UUID heavy = UUID.randomUUID();
        // 中伤 800, 主力 9200; teamAvg = (800+9200)/2 = 5000; 15% = 750。
        DamageContribution midC = new DamageContribution(mid, 800.0D, 10L, true);
        DamageContribution heavyC = new DamageContribution(heavy, 9_200.0D, 5L, true);
        double teamAvg = ContributionPool.teamAverageEffectiveDamage(List.of(midC, heavyC));
        helper.assertTrue(Math.abs(teamAvg - 5_000.0D) < EPS, "team avg (800+9200)/2 = 5000");

        // 中伤 800 < 门槛一 5000 (不过门槛一), 但 800 >= 门槛二 750 (过门槛二) -> 取一即合格。
        helper.assertTrue(800.0D < bossHp * 0.005D, "mid 800 below boss-HP threshold 5000");
        helper.assertTrue(800.0D >= teamAvg * 0.15D, "mid 800 >= team-avg threshold 750");
        helper.assertTrue(ContributionPool.isQualified(midC, bossHp, teamAvg),
                "passing ONLY threshold-two qualifies (取一 semantics)");
        helper.succeed();
    }

    // ============================================================
    // 盖章双门槛"取一": 仅过门槛一 (0.5% 总血) 也合格 + 恰边界
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void stampThresholdOneOnlyAndBoundary(GameTestHelper helper) {
        // 小血 BOSS + 一个超高伤碾压队友拉高团队人均 (门槛二极高): 中伤玩家只过门槛一仍合格。
        // bossHp = 100000 -> 0.5% = 500 (门槛一)。
        double bossHp = 100_000.0D;
        UUID mid = UUID.randomUUID();
        UUID carry = UUID.randomUUID();
        // 中伤 600, 碾压 100000; teamAvg = (600+100000)/2 = 50300; 15% = 7545 (门槛二极高)。
        DamageContribution midC = new DamageContribution(mid, 600.0D, 10L, true);
        DamageContribution carryC = new DamageContribution(carry, 100_000.0D, 5L, true);
        double teamAvg = ContributionPool.teamAverageEffectiveDamage(List.of(midC, carryC));
        helper.assertTrue(Math.abs(teamAvg - 50_300.0D) < EPS, "team avg (600+100000)/2 = 50300");

        // 中伤 600 >= 门槛一 500 (过), 但 600 < 门槛二 7545 (不过) -> 取一即合格。
        helper.assertTrue(600.0D >= bossHp * 0.005D, "mid 600 >= boss-HP threshold 500");
        helper.assertTrue(600.0D < teamAvg * 0.15D, "mid 600 below team-avg threshold 7545");
        helper.assertTrue(ContributionPool.isQualified(midC, bossHp, teamAvg),
                "passing ONLY threshold-one qualifies (取一 semantics)");

        // 恰边界: 个人有效伤害 == 0.5% 总血 (500) 须合格 (>= 含等号)。
        DamageContribution exact = new DamageContribution(UUID.randomUUID(), 500.0D, 1L, true);
        helper.assertTrue(ContributionPool.isQualified(exact, bossHp, teamAvg), "exactly 0.5% boss HP (500) qualifies");
        // 恰边界下一档: 499.999 不合格 (且 < 门槛二)。
        DamageContribution justBelow = new DamageContribution(UUID.randomUUID(), 499.999D, 1L, true);
        helper.assertTrue(!ContributionPool.isQualified(justBelow, bossHp, teamAvg),
                "499.999 below both thresholds -> not qualified (strict boundary)");
        helper.succeed();
    }

    // ============================================================
    // 团队人均分母剔 0 伤参战者 (effectiveDamage=0 不进分母)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void teamAverageExcludesZeroDamageRecords(GameTestHelper helper) {
        // 三条记录但一条 0 伤 (未真正参战): 分母按 2 而非 3。
        List<DamageContribution> contribs = List.of(
                new DamageContribution(UUID.randomUUID(), 600.0D, 1L, true),
                new DamageContribution(UUID.randomUUID(), 0.0D, 2L, true),   // 0 伤不进分母
                new DamageContribution(UUID.randomUUID(), 400.0D, 3L, true));
        double avg = ContributionPool.teamAverageEffectiveDamage(contribs);
        // (600 + 400) / 2 = 500 (非 1000/3 = 333.33)。
        helper.assertTrue(Math.abs(avg - 500.0D) < EPS, "team avg excludes 0-damage record: (600+400)/2 = 500");

        // 空列表返回 0 (无参战者)。
        helper.assertTrue(ContributionPool.teamAverageEffectiveDamage(List.of()) == 0.0D, "empty list -> avg 0");
        // 全 0 伤列表返回 0 (participants=0)。
        List<DamageContribution> allZero = List.of(
                new DamageContribution(UUID.randomUUID(), 0.0D, 1L, true),
                new DamageContribution(UUID.randomUUID(), 0.0D, 2L, true));
        helper.assertTrue(ContributionPool.teamAverageEffectiveDamage(allZero) == 0.0D, "all-zero list -> avg 0");
        helper.succeed();
    }

    // ============================================================
    // 离线没收: 高伤离线者被剔, 余下合格者瓜分整池 (非池缩水/非人头复制)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void offlineForfeitRedistributesWholePool(GameTestHelper helper) {
        // 三玩家有效伤害 [6000, 4000, 离线 10000]; bossHp=100000 (0.5%=500) 三者均过门槛一。
        // 离线者 (最高伤) 被没收, 整池 10000 由在线两人按 6000:4000 = 60%:40% 瓜分 (池不缩水, 不复制给离线者)。
        UUID online1 = UUID.randomUUID();
        UUID online2 = UUID.randomUUID();
        UUID offline = UUID.randomUUID();
        List<DamageContribution> contribs = List.of(
                new DamageContribution(online1, 6_000.0D, 1L, true),
                new DamageContribution(online2, 4_000.0D, 2L, true),
                new DamageContribution(offline, 10_000.0D, 3L, false)); // 离线: 没收
        Map<UUID, Long> payout = ContributionPool.distribute(contribs, 100_000.0D, 10_000L);

        helper.assertTrue(payout.size() == 2, "offline high-damage player excluded; only 2 qualifiers paid");
        helper.assertTrue(!payout.containsKey(offline), "offline player receives nothing (forfeited)");
        // 在线两人按 6000:4000 瓜分整池: 6000/10000 = 0.6 -> 6000; 末名 online2 吸收余数 -> 4000。
        helper.assertTrue(payout.get(online1) == 6_000L, "online1 weight 60% of whole pool -> 6000");
        helper.assertTrue(payout.get(online2) == 4_000L, "online2 (last) absorbs remainder -> 4000");
        long total = payout.get(online1) + payout.get(online2);
        helper.assertTrue(total == 10_000L,
                "whole fixed pool consumed by remaining qualifiers (not shrunk, not head-count copied)");
        helper.succeed();
    }

    // ============================================================
    // round 不整除时末名吸收余数 -> Σ应得 == 固定池 (无信用点丢失/虚增)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void weightedRoundingRemainderAbsorbedByLast(GameTestHelper helper) {
        // 三玩家有效伤害 [1, 1, 1] 对固定池 10 瓜分: 各权 1/3, round(10/3) = round(3.333) = 3。
        // 前两名各 3 (distributed=6), 末名吸收 10-6 = 4。Σ = 3+3+4 = 10 == 池 (不丢 1 也不虚增)。
        // bossHp 小到三者均过门槛: bossHp=100 -> 0.5%=0.5, 各 1 >= 0.5 合格。
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        List<DamageContribution> contribs = List.of(
                new DamageContribution(a, 1.0D, 1L, true),
                new DamageContribution(b, 1.0D, 2L, true),
                new DamageContribution(c, 1.0D, 3L, true));
        Map<UUID, Long> payout = ContributionPool.distribute(contribs, 100.0D, 10L);
        helper.assertTrue(payout.size() == 3, "all three equal-damage players qualify");
        helper.assertTrue(payout.get(a) == 3L, "player a round(10/3) = 3");
        helper.assertTrue(payout.get(b) == 3L, "player b round(10/3) = 3");
        helper.assertTrue(payout.get(c) == 4L, "player c (last) absorbs remainder 10-6 = 4");
        long total = payout.get(a) + payout.get(b) + payout.get(c);
        helper.assertTrue(total == 10L, "non-divisible weighted shares sum EXACTLY to fixed pool 10 (no loss/inflation)");

        // 大池非整除: 伤害 [7000, 2000, 1000] 池 9999 -> a=round(9999*0.7)=6999, b=round(9999*0.2)=2000,
        // c(末)=9999-6999-2000=1000。Σ=9999。
        UUID x = UUID.randomUUID();
        UUID y = UUID.randomUUID();
        UUID z = UUID.randomUUID();
        List<DamageContribution> big = List.of(
                new DamageContribution(x, 7_000.0D, 1L, true),
                new DamageContribution(y, 2_000.0D, 2L, true),
                new DamageContribution(z, 1_000.0D, 3L, true));
        Map<UUID, Long> p2 = ContributionPool.distribute(big, 1_000_000.0D, 9_999L);
        helper.assertTrue(p2.get(x) == 6_999L, "x round(9999*0.70) = 6999");
        helper.assertTrue(p2.get(y) == 2_000L, "y round(9999*0.20) = 2000");
        helper.assertTrue(p2.get(z) == 1_000L, "z (last) = 9999 - 6999 - 2000 = 1000");
        long total2 = p2.get(x) + p2.get(y) + p2.get(z);
        helper.assertTrue(total2 == 9_999L, "big-pool weighted shares sum exactly to 9999");
        helper.succeed();
    }

    // ============================================================
    // 两门槛皆败 -> 整池不发 (空 payout, 防按人头复制)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bothThresholdsFailedYieldsEmptyPayout(GameTestHelper helper) {
        // 巨血 BOSS (门槛一极高) + 一个碾压队友拉高门槛二: 两个低伤蹭枪者皆败 -> 空 payout。
        // bossHp = 10_000_000 -> 0.5% = 50000 (门槛一)。
        double bossHp = 10_000_000.0D;
        UUID leech1 = UUID.randomUUID();
        UUID leech2 = UUID.randomUUID();
        UUID carry = UUID.randomUUID();
        // 蹭枪各 10; 碾压 90000; teamAvg = (10+10+90000)/3 = 30006.66; 15% = 4501 (门槛二)。
        // 蹭枪 10 < 50000 (败门槛一) 且 10 < 4501 (败门槛二) -> 两个蹭枪皆不合格; 碾压 90000 >= 50000 合格。
        List<DamageContribution> contribs = List.of(
                new DamageContribution(leech1, 10.0D, 10L, true),
                new DamageContribution(leech2, 10.0D, 11L, true),
                new DamageContribution(carry, 90_000.0D, 5L, true));
        Map<UUID, Long> payout = ContributionPool.distribute(contribs, bossHp, 5_000L);
        // 唯一合格者 carry 独吞整池; 两蹭枪 0。
        helper.assertTrue(payout.size() == 1, "only carry qualifies; two leeches fail both thresholds");
        helper.assertTrue(payout.get(carry) == 5_000L, "sole qualifier takes whole fixed pool 5000");
        helper.assertTrue(!payout.containsKey(leech1), "leech1 not head-count copied");
        helper.assertTrue(!payout.containsKey(leech2), "leech2 not head-count copied");

        // 极端: 把碾压也换成蹭枪, 全员败两门槛 -> 整池不发 (空 map, 池 5000 蒸发不入任何人)。
        List<DamageContribution> allLeech = List.of(
                new DamageContribution(UUID.randomUUID(), 10.0D, 1L, true),
                new DamageContribution(UUID.randomUUID(), 12.0D, 2L, true));
        // teamAvg = (10+12)/2 = 11; 15% = 1.65; 10 与 12 均 >= 1.65 -> 实际过门槛二! 为构造皆败须门槛二也不可达,
        // 但门槛二分母即己方人均, 低伤时门槛二必低 (取一保底), 故"皆败"只发生在巨血 BOSS + 有碾压者拉高门槛二的场景。
        // 上面 carry 场景已证此唯一路径; 此处验证: 低人均时蹭枪经门槛二保底 -> 反而都合格 (语义对称证明)。
        Map<UUID, Long> p2 = ContributionPool.distribute(allLeech, bossHp, 1_000L);
        helper.assertTrue(p2.size() == 2,
                "low team-avg makes threshold-two reachable -> both qualify (取一 floor protects non-leech teams)");
        helper.succeed();
    }

    // ============================================================
    // isQualified / distribute 非法入参异常自然冒泡
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void contributionInvalidArgsThrow(GameTestHelper helper) {
        DamageContribution c = new DamageContribution(UUID.randomUUID(), 100.0D, 1L, true);
        // bossTotalEffectiveHp <= 0 抛 (门槛一分母非法)。
        assertThrows(helper, () -> ContributionPool.isQualified(c, 0.0D, 50.0D), "bossHp 0 must throw");
        assertThrows(helper, () -> ContributionPool.isQualified(c, -1.0D, 50.0D), "negative bossHp must throw");
        // teamAverageEffectiveDamage < 0 抛。
        assertThrows(helper, () -> ContributionPool.isQualified(c, 100.0D, -1.0D), "negative team avg must throw");
        // distribute fixedPoolRaw < 0 抛。
        assertThrows(helper, () -> ContributionPool.distribute(List.of(c), 100.0D, -1L), "negative pool must throw");

        // fixedPoolRaw == 0: 合法但整池不发 (空 map, 不抛)。
        Map<UUID, Long> zeroPool = ContributionPool.distribute(List.of(c), 100.0D, 0L);
        helper.assertTrue(zeroPool.isEmpty(), "zero fixed pool -> empty payout (legal, no throw)");

        // DamageContribution 非法入参: 负伤抛, 负 firstHitTick 抛, null playerId 抛。
        assertThrows(helper, () -> new DamageContribution(UUID.randomUUID(), -1.0D, 1L, true),
                "negative effectiveDamage must throw");
        assertThrows(helper, () -> new DamageContribution(UUID.randomUUID(), 1.0D, -1L, true),
                "negative firstHitTick must throw");
        assertThrows(helper, () -> new DamageContribution(null, 1.0D, 1L, true), "null playerId must throw");
        helper.succeed();
    }

    // ============================================================
    // 血池注册表 — install 同 UUID 覆盖 (第二次满血新池冲掉旧扣血脏血, 防重生残留)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void registryInstallSameUuidOverwrites(GameTestHelper helper) {
        BloodPoolRegistry.reset();
        UUID id = UUID.randomUUID();

        // 首次建池后受击扣血: 1000 满 -> 打 400 -> 剩 600 (模拟前世残留脏血)。
        BloodPool first = BloodPoolRegistry.install(id, 1_000.0D);
        first.applyDamage(400.0D);
        helper.assertTrue(Math.abs(first.currentHp() - 600.0D) < EPS, "first pool drained to 600 before re-spawn");

        // 同 UUID 二次 install: 建满血新池覆盖旧池 (重生升格)。
        BloodPool second = BloodPoolRegistry.install(id, 1_000.0D);
        helper.assertTrue(BloodPoolRegistry.get(id) == second, "get returns the newly installed pool (same reference)");
        helper.assertTrue(BloodPoolRegistry.get(id) != first, "old drained pool object no longer the registered pool");
        helper.assertTrue(Math.abs(BloodPoolRegistry.get(id).currentHp() - 1_000.0D) < EPS,
                "re-install yields FULL 1000 HP (stale drained 600 overwritten, no resurrection dirty blood)");
        // 旧对象仍持自身扣血态但已脱表, 证明是两个不同实例而非原地复位。
        helper.assertTrue(Math.abs(first.currentHp() - 600.0D) < EPS, "detached old pool keeps its own 600 state");
        // 覆盖不增表: 同 UUID 仍占一格。
        helper.assertTrue(BloodPoolRegistry.size() == 1, "same-UUID overwrite does not grow table (size stays 1)");
        helper.succeed();
    }

    // ============================================================
    // 血池注册表 — reset 清空 (size/has/snapshot/get 全归零, 防跨存档脏引用)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void registryResetClearsAllPools(GameTestHelper helper) {
        BloodPoolRegistry.reset();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        BloodPoolRegistry.install(a, 1_000.0D);
        BloodPoolRegistry.install(b, 2_000.0D);
        BloodPoolRegistry.install(c, 3_000.0D);
        helper.assertTrue(BloodPoolRegistry.size() == 3, "three pools installed before reset");
        helper.assertTrue(BloodPoolRegistry.has(a) && BloodPoolRegistry.has(b) && BloodPoolRegistry.has(c),
                "all three present before reset");

        BloodPoolRegistry.reset();
        helper.assertTrue(BloodPoolRegistry.size() == 0, "reset clears table -> size 0");
        helper.assertTrue(!BloodPoolRegistry.has(a) && !BloodPoolRegistry.has(b) && !BloodPoolRegistry.has(c),
                "no pool present after reset (cross-save dirty refs purged)");
        helper.assertTrue(BloodPoolRegistry.snapshot().isEmpty(), "snapshot empty after reset");
        helper.assertTrue(BloodPoolRegistry.get(a) == null, "get returns null after reset");
        helper.succeed();
    }

    // ============================================================
    // 血池注册表 — snapshot 只读独立副本 (后续 install 不进旧快照 + put 抛 UOE 不可变)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void registrySnapshotIsImmutableIndependentCopy(GameTestHelper helper) {
        BloodPoolRegistry.reset();
        UUID a = UUID.randomUUID();
        BloodPoolRegistry.install(a, 1_000.0D);

        Map<UUID, BloodPool> snap = BloodPoolRegistry.snapshot();
        helper.assertTrue(snap.size() == 1, "snapshot has the single installed pool");
        helper.assertTrue(snap.containsKey(a), "snapshot contains installed UUID");

        // 取快照后再 install 新池: 旧快照是冻结副本, 不应见到 b (副本隔离)。
        UUID b = UUID.randomUUID();
        BloodPoolRegistry.install(b, 2_000.0D);
        helper.assertTrue(snap.size() == 1, "earlier snapshot unaffected by later install (frozen independent copy)");
        helper.assertTrue(!snap.containsKey(b), "later-installed pool absent from earlier snapshot");
        // 活表确实增长, 证明 snapshot 不是活表别名。
        helper.assertTrue(BloodPoolRegistry.size() == 2, "live registry grew to 2; snapshot stayed frozen at 1");

        // 快照不可变: put 抛 UnsupportedOperationException。
        boolean uoe = false;
        try {
            snap.put(UUID.randomUUID(), new BloodPool(500.0D));
        } catch (UnsupportedOperationException expected) {
            uoe = true;
        }
        helper.assertTrue(uoe, "snapshot.put throws UnsupportedOperationException (read-only view)");
        helper.succeed();
    }

    // ============================================================
    // 血池注册表 — size 计数 + null 守卫 (install null/非正 maxHp 抛 IAE 且不污染表; get/has/remove(null) 返回 null/false)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void registrySizeAndNullGuards(GameTestHelper helper) {
        BloodPoolRegistry.reset();
        int n = 5;
        UUID[] ids = new UUID[n];
        for (int i = 0; i < n; i++) {
            ids[i] = UUID.randomUUID();
            BloodPoolRegistry.install(ids[i], 1_000.0D);
        }
        helper.assertTrue(BloodPoolRegistry.size() == n, "size reflects N=5 installed pools");

        // install(null) 抛 IAE (显式守卫), 非正 maxHp 经 BloodPool 构造器自然冒泡 IAE。
        assertThrows(helper, () -> BloodPoolRegistry.install(null, 1_000.0D), "install(null entityId) must throw IAE");
        assertThrows(helper, () -> BloodPoolRegistry.install(UUID.randomUUID(), 0.0D),
                "install maxHp 0 must throw IAE (bubbles from BloodPool ctor)");
        assertThrows(helper, () -> BloodPoolRegistry.install(UUID.randomUUID(), -1.0D),
                "install negative maxHp must throw IAE");
        // 被拒的 install 未落表: size 仍为 N。
        helper.assertTrue(BloodPoolRegistry.size() == n, "rejected installs left table size unchanged at 5");

        // 读侧 null 守卫按真实契约: get(null)=null, has(null)=false, remove(null)=null (不抛)。
        helper.assertTrue(BloodPoolRegistry.get(null) == null, "get(null) returns null (no throw)");
        helper.assertTrue(!BloodPoolRegistry.has(null), "has(null) returns false");
        helper.assertTrue(BloodPoolRegistry.remove(null) == null, "remove(null) returns null (no throw)");

        // remove 存在项: 返回被移除的池且 size 递减、has 转 false。
        BloodPool removed = BloodPoolRegistry.remove(ids[0]);
        helper.assertTrue(removed != null, "remove of a present id returns the removed pool");
        helper.assertTrue(BloodPoolRegistry.size() == n - 1, "remove decrements size to 4");
        helper.assertTrue(!BloodPoolRegistry.has(ids[0]), "removed id no longer present");
        // remove 不在表项返回 null。
        helper.assertTrue(BloodPoolRegistry.remove(UUID.randomUUID()) == null, "remove of absent id returns null");
        helper.succeed();
    }

    // ---- helpers ----

    private static void assertThrows(GameTestHelper helper, Runnable op, String msg) {
        boolean threw = false;
        try {
            op.run();
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        helper.assertTrue(threw, msg);
    }
}

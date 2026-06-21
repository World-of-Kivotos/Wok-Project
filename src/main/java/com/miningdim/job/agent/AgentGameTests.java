package com.miningdim.job.agent;

import com.miningdim.champion.StarRank;
import com.miningdim.champion.reward.ChampionReward;
import com.miningdim.core.MiningConstants;
import com.miningdim.economy.AbuseGuard;
import com.miningdim.economy.EconomyConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * 特勤干员纯逻辑层 GameTest (SpecialAgent_Job_DesignSpec 七章测试拆分 + 实现手册 GameTest 范式)。
 *
 * 只测纯逻辑 (compileOnly 铁律: dev 不加载 Champions, 严禁触 top.theillusivec4.champions.*): 五支线查表 /
 * 加强奖励倍率 / 封印选择+窗口+门控 / 封印不叠加 / 悬赏计数+翻日翻周 / 伤害加成。断言具体业务结果 (金额/星级/
 * 计数/窗口/状态), 删被测核心逻辑必挂, 禁 is-not-null 弱校验。
 *
 * 纯逻辑不依赖结构, 用 template = "empty" (data/miningdim/structures/empty.nbt 空模板); batch = "agent" 专属。
 * 真探测/真封印 (setAffixes 临时移除/恢复) 须正式服 (Champions 已加载) 验, 不在 dev 断言。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class AgentGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "agent";

    // ============================================================
    // 五支线查表: 探测范围 / 脉冲 CD / 伤害加成% / 悬赏槽位 / 可接星级 / 世界 BOSS 门 (第四章总表)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void skillTableScanRange(GameTestHelper helper) {
        // 第四章范围列 64/96/128/160/200/256/320/384/448/跨区块。
        helper.assertTrue(AgentSkillTable.scanRangeBlocks(1) == 64, "L1 scan range 64");
        helper.assertTrue(AgentSkillTable.scanRangeBlocks(2) == 96, "L2 scan range 96");
        helper.assertTrue(AgentSkillTable.scanRangeBlocks(3) == 128, "L3 scan range 128");
        helper.assertTrue(AgentSkillTable.scanRangeBlocks(4) == 160, "L4 scan range 160");
        helper.assertTrue(AgentSkillTable.scanRangeBlocks(5) == 200, "L5 scan range 200");
        helper.assertTrue(AgentSkillTable.scanRangeBlocks(6) == 256, "L6 scan range 256");
        helper.assertTrue(AgentSkillTable.scanRangeBlocks(7) == 320, "L7 scan range 320");
        helper.assertTrue(AgentSkillTable.scanRangeBlocks(8) == 384, "L8 scan range 384");
        helper.assertTrue(AgentSkillTable.scanRangeBlocks(9) == 448, "L9 scan range 448");
        helper.assertTrue(AgentSkillTable.scanRangeBlocks(10) == AgentSkillTable.SCAN_RANGE_CROSS_CHUNK,
                "L10 scan range is cross-chunk sentinel (-1)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void skillTableScanPulseCd(GameTestHelper helper) {
        // 第五章: 60s -> 30s 随级缩 (线性, 端点精确)。
        helper.assertTrue(AgentSkillTable.scanPulseCdSeconds(1) == 60, "L1 pulse CD 60s");
        helper.assertTrue(AgentSkillTable.scanPulseCdSeconds(10) == 30, "L10 pulse CD 30s");
        // 单调不增 (高级缩短不变长)。
        for (int lv = 2; lv <= 10; lv++) {
            helper.assertTrue(AgentSkillTable.scanPulseCdSeconds(lv) <= AgentSkillTable.scanPulseCdSeconds(lv - 1),
                    "pulse CD must be monotonically non-increasing across levels at L" + lv);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void skillTableDamageBonus(GameTestHelper helper) {
        // 第四章列 5/6/7/8/9/10/11/12/13/15 (% — L10 多跳 1% 到 +15%)。
        int[] expected = {5, 6, 7, 8, 9, 10, 11, 12, 13, 15};
        for (int lv = 1; lv <= 10; lv++) {
            helper.assertTrue(AgentSkillTable.damageBonusPercent(lv) == expected[lv - 1],
                    "L" + lv + " damage bonus must be +" + expected[lv - 1] + "%");
        }
        // 末级关键断言: L10 = +15% (非线性 +14%; 多跳 1%)。
        helper.assertTrue(AgentSkillTable.damageBonusPercent(10) == 15,
                "L10 damage bonus is +15% (extra +1% jump), not +14%");
        helper.assertTrue(AgentSkillTable.damageBonusPercent(9) == 13, "L9 is +13% (linear before the L10 jump)");
        // 系数版 = %/100。
        helper.assertTrue(Math.abs(AgentSkillTable.damageBonusFraction(1) - 0.05D) < 1e-9, "L1 fraction 0.05");
        helper.assertTrue(Math.abs(AgentSkillTable.damageBonusFraction(10) - 0.15D) < 1e-9, "L10 fraction 0.15");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void skillTableBountySlots(GameTestHelper helper) {
        // 第四章日列 1/1/2/2/3/3/3/4/4/5。
        int[] daily = {1, 1, 2, 2, 3, 3, 3, 4, 4, 5};
        for (int lv = 1; lv <= 10; lv++) {
            helper.assertTrue(AgentSkillTable.dailyBountySlots(lv) == daily[lv - 1],
                    "L" + lv + " daily bounty slots must be " + daily[lv - 1]);
        }
        // 周常 L4 才解锁 (L1-L3 周槽 = 0; L4 起 >0)。
        helper.assertTrue(AgentSkillTable.weeklyBountySlots(1) == 0, "L1 weekly slots 0 (weekly locked)");
        helper.assertTrue(AgentSkillTable.weeklyBountySlots(3) == 0, "L3 weekly slots 0 (still locked)");
        helper.assertTrue(AgentSkillTable.weeklyBountySlots(4) == 1, "L4 weekly slots 1 (weekly unlocks)");
        helper.assertTrue(AgentSkillTable.weeklyBountySlots(7) == 2, "L7 weekly slots 2");
        helper.assertTrue(AgentSkillTable.weeklyBountySlots(10) == 3, "L10 weekly slots 3");
        helper.assertTrue(!AgentSkillTable.isWeeklyBountyUnlocked(3), "weekly bounty locked at L3");
        helper.assertTrue(AgentSkillTable.isWeeklyBountyUnlocked(4), "weekly bounty unlocks at L4");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void skillTableBountyStarAndWorldBoss(GameTestHelper helper) {
        // 可接星级 = 等级 (≤L★)。
        for (int lv = 1; lv <= 10; lv++) {
            helper.assertTrue(AgentSkillTable.maxBountyStar(lv) == lv, "L" + lv + " can accept bounty up to " + lv + "star");
        }
        // 世界 BOSS 悬赏 L8 起开放。
        helper.assertTrue(!AgentSkillTable.isWorldBossBountyUnlocked(7), "world boss bounty locked at L7");
        helper.assertTrue(AgentSkillTable.isWorldBossBountyUnlocked(8), "world boss bounty opens at L8");
        helper.assertTrue(AgentSkillTable.isWorldBossBountyUnlocked(10), "world boss bounty open at L10");
        helper.succeed();
    }

    // ============================================================
    // 探测分级解密 (AgentScanTier / AgentScanField)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void scanTierFieldUnlocks(GameTestHelper helper) {
        // L1: 词条 + 星级, 无有效血。
        helper.assertTrue(AgentScanTier.canDecrypt(1, AgentScanField.AFFIX_LIST), "L1 sees affix list");
        helper.assertTrue(AgentScanTier.canDecrypt(1, AgentScanField.STAR), "L1 sees star");
        helper.assertTrue(!AgentScanTier.canDecrypt(1, AgentScanField.EFFECTIVE_HP), "L1 does NOT see effective hp");
        // L3 解锁有效血。
        helper.assertTrue(AgentScanTier.canDecrypt(3, AgentScanField.EFFECTIVE_HP), "L3 sees effective hp");
        helper.assertTrue(!AgentScanTier.canDecrypt(3, AgentScanField.ARMOR_DR_PERCENT), "L3 does NOT see armor/DR%");
        // L4 护甲/减伤%。
        helper.assertTrue(AgentScanTier.canDecrypt(4, AgentScanField.ARMOR_DR_PERCENT), "L4 sees armor/DR%");
        // L5 技能名 + 子弹抗性。
        helper.assertTrue(AgentScanTier.canDecrypt(5, AgentScanField.SKILL_NAME), "L5 sees skill name");
        helper.assertTrue(AgentScanTier.canDecrypt(5, AgentScanField.BULLET_RESISTANCE), "L5 sees bullet resistance");
        // L6 攻击/移速 + 悬赏雷达。
        helper.assertTrue(AgentScanTier.canDecrypt(6, AgentScanField.BOUNTY_RADAR), "L6 sees bounty radar");
        // L7 技能机制。
        helper.assertTrue(AgentScanTier.canDecrypt(7, AgentScanField.SKILL_MECHANICS), "L7 sees skill mechanics");
        helper.assertTrue(!AgentScanTier.canDecrypt(7, AgentScanField.GLOWING_HIGHLIGHT), "L7 does NOT glow-highlight");
        // L8 全品质表 + Glowing。
        helper.assertTrue(AgentScanTier.canDecrypt(8, AgentScanField.GLOWING_HIGHLIGHT), "L8 glow-highlights");
        helper.assertTrue(AgentScanTier.canDecrypt(8, AgentScanField.QUALITY_TABLE), "L8 sees full quality table");
        // L9 实时数值。
        helper.assertTrue(AgentScanTier.canDecrypt(9, AgentScanField.REALTIME_NUMBERS), "L9 sees realtime numbers");
        helper.assertTrue(!AgentScanTier.canDecrypt(9, AgentScanField.REALTIME_ALL_ATTRIBUTES),
                "L9 does NOT see ALL realtime attributes");
        // L10 全属性实时。
        helper.assertTrue(AgentScanTier.canDecrypt(10, AgentScanField.REALTIME_ALL_ATTRIBUTES),
                "L10 sees all realtime attributes");
        // 字段集随级单调扩张 (高级看到的字段集 >= 低级)。
        helper.assertTrue(AgentScanTier.visibleFields(10).containsAll(AgentScanTier.visibleFields(1)),
                "L10 visible field set must superset L1");
        helper.assertTrue(AgentScanTier.visibleFields(5).size() > AgentScanTier.visibleFields(2).size(),
                "higher level decrypts strictly more fields");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void scanTierAffixCount(GameTestHelper helper) {
        // 第四章探测词条列: L1=1 / L2=2 / L3=3 / L4+ = ALL_AFFIXES 哨兵。
        helper.assertTrue(AgentScanTier.visibleAffixCount(1) == 1, "L1 sees 1 affix");
        helper.assertTrue(AgentScanTier.visibleAffixCount(2) == 2, "L2 sees 2 affixes");
        helper.assertTrue(AgentScanTier.visibleAffixCount(3) == 3, "L3 sees 3 affixes");
        helper.assertTrue(AgentScanTier.visibleAffixCount(4) == AgentScanTier.ALL_AFFIXES,
                "L4 sees all passive affixes (sentinel -1)");
        helper.assertTrue(AgentScanTier.visibleAffixCount(5) == AgentScanTier.ALL_AFFIXES,
                "L5 sees all affixes including skill (sentinel -1)");
        // 全被动 vs 全部 (含技能) 的解锁分界。
        helper.assertTrue(!AgentScanTier.showsAllPassiveAffixes(3), "L3 shows only first-N affixes");
        helper.assertTrue(AgentScanTier.showsAllPassiveAffixes(4), "L4 shows all passive affixes");
        helper.assertTrue(!AgentScanTier.showsSkillAffixes(4), "L4 does NOT yet show skill affixes");
        helper.assertTrue(AgentScanTier.showsSkillAffixes(5), "L5 shows skill affixes (full set)");
        helper.succeed();
    }

    // ============================================================
    // 加强奖励倍率 (AgentEnhancedReward) — 逐级系数 + 不含青辉石 + 随等级单调递增
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void enhancedRewardMultiplierLadder(GameTestHelper helper) {
        // 第四章倍率列 [1.0,1.25,1.5,1.75,2.0,2.25,2.5,2.7,2.85,3.0]。
        double[] mult = {1.0D, 1.25D, 1.5D, 1.75D, 2.0D, 2.25D, 2.5D, 2.7D, 2.85D, 3.0D};
        for (int lv = 1; lv <= 10; lv++) {
            helper.assertTrue(Math.abs(AgentSkillTable.enhancedRewardMultiplier(lv) - mult[lv - 1]) < 1e-9,
                    "L" + lv + " enhanced reward multiplier must be x" + mult[lv - 1]);
        }
        helper.assertTrue(Math.abs(AgentSkillTable.enhancedRewardMultiplier(1) - 1.0D) < 1e-9, "L1 = x1.0");
        helper.assertTrue(Math.abs(AgentSkillTable.enhancedRewardMultiplier(10) - 3.0D) < 1e-9, "L10 = x3.0 only at L10");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void enhancedRewardCreditOnly(GameTestHelper helper) {
        // raw = floor(star * 600 * multiplier). L1 (x1.0), 5★: 5*600*1.0 = 3000。
        helper.assertTrue(AgentEnhancedReward.extraCreditRaw(1, 5) == 3_000L,
                "L1 kill of 5star: 5*600*1.0 = 3000 credit raw");
        // L10 (x3.0), 10★: 10*600*3.0 = 18000。
        helper.assertTrue(AgentEnhancedReward.extraCreditRaw(10, 10) == 18_000L,
                "L10 kill of 10star: 10*600*3.0 = 18000 credit raw");
        // L5 (x2.0), 3★: 3*600*2.0 = 3600。
        helper.assertTrue(AgentEnhancedReward.extraCreditRaw(5, 3) == 3_600L,
                "L5 kill of 3star: 3*600*2.0 = 3600 credit raw");
        // 底值与精英怪固定池每星基数同源 (600/星), 防漂移。
        helper.assertTrue(AgentEnhancedReward.CREDIT_BASE_PER_STAR == ChampionReward.CREDIT_POOL_PER_STAR,
                "enhanced reward base per star reuses ChampionReward.CREDIT_POOL_PER_STAR (600)");
        // 同一初始星级下, raw 随干员等级单调递增 (倍率递增)。
        for (int lv = 2; lv <= 10; lv++) {
            helper.assertTrue(AgentEnhancedReward.extraCreditRaw(lv, 6) > AgentEnhancedReward.extraCreditRaw(lv - 1, 6),
                    "fixed 6star: enhanced credit strictly increases with agent level at L" + lv);
        }
        // x3.0 峰值仅 L10 (L9 同星严格更少)。
        helper.assertTrue(AgentEnhancedReward.extraCreditRaw(10, 8) > AgentEnhancedReward.extraCreditRaw(9, 8),
                "x3.0 peak applies only at L10 (L9 of same star yields strictly less)");
        helper.succeed();
    }

    // ============================================================
    // 封印选择 + 窗口 + 门控 (SealPlan)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sealPlanLevelAndCategoryGates(GameTestHelper helper) {
        // L1-L2 不可封任何词条 (封印 L3 起)。
        SealPlan.Result l1 = SealPlan.plan(1, 1, SealCategory.PASSIVE);
        helper.assertTrue(!l1.ok() && l1.reason() == SealPlan.FailReason.CATEGORY_LOCKED,
                "L1 cannot seal: passive seal locked until L3");
        SealPlan.Result l2 = SealPlan.plan(2, 2, SealCategory.PASSIVE);
        helper.assertTrue(!l2.ok() && l2.reason() == SealPlan.FailReason.CATEGORY_LOCKED, "L2 still cannot seal");
        // L3 被动可封, 窗口 8s / CD 30s。
        SealPlan.Result l3 = SealPlan.plan(3, 3, SealCategory.PASSIVE);
        helper.assertTrue(l3.ok(), "L3 can seal passive on a 3star");
        helper.assertTrue(l3.windowSeconds() == 8, "L3 passive seal window 8s");
        helper.assertTrue(l3.cooldownSeconds() == 30, "L3 passive seal CD 30s");
        // 机制类仅 L8+ 可封: L7 机制被锁。
        SealPlan.Result l7mech = SealPlan.plan(7, 5, SealCategory.MECHANIC);
        helper.assertTrue(!l7mech.ok() && l7mech.reason() == SealPlan.FailReason.CATEGORY_LOCKED,
                "L7 cannot seal mechanic affix (mechanic seal locked until L8)");
        // L8 机制窗口 3s。
        SealPlan.Result l8mech = SealPlan.plan(8, 8, SealCategory.MECHANIC);
        helper.assertTrue(l8mech.ok() && l8mech.windowSeconds() == 3, "L8 mechanic seal window 3s");
        // L9 机制窗口 4s, L10 机制窗口 5s + 机制 CD 45s。
        helper.assertTrue(SealPlan.plan(9, 9, SealCategory.MECHANIC).windowSeconds() == 4, "L9 mechanic window 4s");
        SealPlan.Result l10mech = SealPlan.plan(10, 10, SealCategory.MECHANIC);
        helper.assertTrue(l10mech.windowSeconds() == 5, "L10 mechanic window 5s");
        helper.assertTrue(l10mech.cooldownSeconds() == 45, "L10 mechanic seal CD 45s (only L10 gives mechanic CD)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sealPlanStarGate(GameTestHelper helper) {
        // 可封星级随级抬到 10★ (maxSealableStar(L)=L)。
        // L3 可封 ≤3★: 封 4★ 拒 (需更高等级)。
        SealPlan.Result l3on4 = SealPlan.plan(3, 4, SealCategory.PASSIVE);
        helper.assertTrue(!l3on4.ok() && l3on4.reason() == SealPlan.FailReason.STAR_TOO_HIGH,
                "L3 cannot seal a 4star (star too high, needs higher level)");
        // L3 封 3★ 可。
        helper.assertTrue(SealPlan.plan(3, 3, SealCategory.PASSIVE).ok(), "L3 can seal a 3star");
        // L10 可封 10★。
        helper.assertTrue(SealPlan.plan(10, 10, SealCategory.PASSIVE).ok(), "L10 can seal a 10star");
        // 被动窗口逐级 8->12s, 被动 CD 30->18s 抽样。
        helper.assertTrue(AgentSkillTable.sealWindowSeconds(10, SealCategory.PASSIVE) == 12, "L10 passive window 12s");
        helper.assertTrue(AgentSkillTable.sealCooldownSeconds(5, SealCategory.PASSIVE) == 26, "L5 passive CD 26s");
        helper.assertTrue(AgentSkillTable.sealCooldownSeconds(6, SealCategory.PASSIVE) == 24, "L6 passive CD 24s");
        helper.assertTrue(AgentSkillTable.sealCooldownSeconds(10, SealCategory.PASSIVE) == 18, "L10 passive CD 18s");
        helper.succeed();
    }

    // ============================================================
    // 封印不叠加 (SealRegistry) — 每精英 1 槽 / 8星+ 2 槽 / 先到先得 / 多干员不延长
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sealRegistryNoStackSingleSlot(GameTestHelper helper) {
        SealRegistry.reset();
        UUID champ = UUID.randomUUID();
        UUID agentA = UUID.randomUUID();
        UUID agentB = UUID.randomUUID();
        long now = 1000L;

        // L5 干员封 5★ 精英 (5★ < 8 -> 1 槽)。A 先封词条 frost。
        SealRegistry.ApplyResult a = SealRegistry.applySeal(champ, agentA, "frost", SealCategory.PASSIVE, 5, 5, now);
        helper.assertTrue(a.ok(), "agentA seals frost on a single-slot 5star");
        helper.assertTrue(SealRegistry.activeSealCount(champ, now) == 1, "one active seal occupies the only slot");

        // B 想封另一词条 armor: 槽已占 -> 拒 (防叠叠乐: 第二人封印被拒)。
        SealRegistry.ApplyResult b = SealRegistry.applySeal(champ, agentB, "armor", SealCategory.PASSIVE, 5, 5, now + 1);
        helper.assertTrue(!b.ok() && b.reason() == SealRegistry.FailReason.ALL_SLOTS_OCCUPIED,
                "second agent's seal is rejected (single slot occupied, no stacking by headcount)");
        helper.assertTrue(SealRegistry.activeSealCount(champ, now + 1) == 1,
                "champion still has exactly one seal regardless of multiple agents present");

        // A 想再封同词条 frost (互斥): 拒 (不因再封延长)。
        SealRegistry.ApplyResult dup = SealRegistry.applySeal(champ, agentA, "frost", SealCategory.PASSIVE, 5, 5, now + 2);
        helper.assertTrue(!dup.ok() && dup.reason() == SealRegistry.FailReason.AFFIX_ALREADY_SEALED,
                "re-sealing an already-sealed affix is rejected (mutual exclusion, no window extension)");
        SealRegistry.reset();
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sealRegistryWindowExpiryReleasesSlot(GameTestHelper helper) {
        SealRegistry.reset();
        UUID champ = UUID.randomUUID();
        UUID agentA = UUID.randomUUID();
        UUID agentB = UUID.randomUUID();
        long now = 0L;

        // L5 被动窗口 9s = 180 tick。A 封 frost。
        SealRegistry.ApplyResult a = SealRegistry.applySeal(champ, agentA, "frost", SealCategory.PASSIVE, 5, 5, now);
        helper.assertTrue(a.ok(), "agentA seals at t=0");
        long expiry = a.expiryTick();
        helper.assertTrue(expiry == 9L * 20L, "L5 passive window 9s -> expiry at 180 tick");
        // 窗口内 (t=100) 仍活跃, 槽占用。
        helper.assertTrue(SealRegistry.activeSealCount(champ, 100L) == 1, "seal active mid-window");
        // 到期 (t=180) 起槽释放: 活跃数归 0。
        helper.assertTrue(SealRegistry.activeSealCount(champ, 180L) == 0, "seal expired at window end, slot freed");
        // 到期后 B 可再封 (先到先得, 槽释放可再争抢)。
        SealRegistry.ApplyResult b = SealRegistry.applySeal(champ, agentB, "armor", SealCategory.PASSIVE, 5, 5, 200L);
        helper.assertTrue(b.ok(), "after expiry the freed slot can be re-sealed by another agent (first-come)");
        helper.assertTrue(SealRegistry.activeSealCount(champ, 200L) == 1, "re-sealed champion has one active seal");
        SealRegistry.reset();
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sealRegistryTwoSlotsForHighStar(GameTestHelper helper) {
        SealRegistry.reset();
        UUID champ = UUID.randomUUID();
        UUID agentA = UUID.randomUUID();
        UUID agentB = UUID.randomUUID();
        UUID agentC = UUID.randomUUID();
        long now = 500L;

        // 8★ 精英 + L9 干员 -> 2 槽 (sealSlots(9, 8) = 2)。
        helper.assertTrue(AgentSkillTable.sealSlots(9, 8) == 2, "8star + L9 agent yields 2 seal slots");
        helper.assertTrue(AgentSkillTable.sealSlots(8, 8) == 1, "8star but only L8 agent still 1 slot (2nd slot needs L9)");
        helper.assertTrue(AgentSkillTable.sealSlots(9, 7) == 1, "7star yields 1 slot even for L9 agent");

        SealRegistry.ApplyResult a = SealRegistry.applySeal(champ, agentA, "frost", SealCategory.PASSIVE, 9, 8, now);
        SealRegistry.ApplyResult b = SealRegistry.applySeal(champ, agentB, "armor", SealCategory.PASSIVE, 9, 8, now + 1);
        helper.assertTrue(a.ok() && b.ok(), "two distinct affixes fill both slots of an 8star");
        helper.assertTrue(SealRegistry.activeSealCount(champ, now + 1) == 2, "8star champion holds 2 active seals");
        // 第三人 C 想封第三词条: 两槽都满 -> 拒 (不因第三个干员在场加到 3 封)。
        SealRegistry.ApplyResult c = SealRegistry.applySeal(champ, agentC, "wither", SealCategory.PASSIVE, 9, 8, now + 2);
        helper.assertTrue(!c.ok() && c.reason() == SealRegistry.FailReason.ALL_SLOTS_OCCUPIED,
                "third seal rejected: 2-slot cap not extended by a third agent");
        SealRegistry.reset();
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sealRegistryTeardownNoLeak(GameTestHelper helper) {
        // 多次封解循环: 净修饰数守恒 (drainExpired 取出已到期供集成层恢复, 不残留/不重复)。
        SealRegistry.reset();
        UUID champ = UUID.randomUUID();
        UUID agent = UUID.randomUUID();

        int appliedCount = 0;
        int recoveredCount = 0;
        long t = 0L;
        // L5 被动窗口 9s = 180 tick。连续 5 轮 封->到期->恢复。
        for (int round = 0; round < 5; round++) {
            SealRegistry.ApplyResult r = SealRegistry.applySeal(champ, agent, "frost", SealCategory.PASSIVE, 5, 5, t);
            helper.assertTrue(r.ok(), "round " + round + " seal applies on freed slot");
            appliedCount++;
            // 到期后 drain: 恰好取出 1 条供恢复, 槽释放。
            long afterExpiry = t + 9L * 20L; // 180 tick 后到期。
            List<SealRegistry.ActiveSeal> expired = SealRegistry.drainExpired(champ, afterExpiry);
            helper.assertTrue(expired.size() == 1, "round " + round + " drains exactly one expired seal to recover");
            recoveredCount++;
            helper.assertTrue(SealRegistry.activeSealCount(champ, afterExpiry) == 0,
                    "round " + round + " slot fully freed after recovery");
            t = afterExpiry + 1L;
        }
        // 净守恒: 封印次数 == 恢复次数 (无修饰泄漏, 无重复恢复)。
        helper.assertTrue(appliedCount == recoveredCount && appliedCount == 5,
                "applied seals == recovered seals across 5 cycles (no AttributeModifier leak / no double-recover)");
        // drain 后无残留账本。
        helper.assertTrue(SealRegistry.trackedChampionCount() == 0, "no champion ledger residue after all recovered");
        SealRegistry.reset();
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sealRegistryAffixSealedQuery(GameTestHelper helper) {
        SealRegistry.reset();
        UUID champ = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        long now = 100L;
        SealRegistry.applySeal(champ, agent, "frost", SealCategory.PASSIVE, 5, 5, now);
        // 词条钩子短路判定: frost 封印中, armor 未封。
        helper.assertTrue(SealRegistry.isAffixSealed(champ, "frost", now + 10L), "frost is sealed mid-window");
        helper.assertTrue(!SealRegistry.isAffixSealed(champ, "armor", now + 10L), "armor is not sealed");
        // 窗口外 frost 解封 (恢复)。
        helper.assertTrue(!SealRegistry.isAffixSealed(champ, "frost", now + 9L * 20L + 1L),
                "frost no longer sealed after window expiry");
        SealRegistry.reset();
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sealRegistryHeadcountDoesNotMultiplySealCount(GameTestHelper helper) {
        // 用户硬约束直证: 同一精英的封印量绝不因在场干员人数翻倍。
        // 对照: 同一 7★ 精英 (1 槽), 五干员各自申请 -> 活跃封印数恒为 1 (取最强单份/互斥)。
        SealRegistry.reset();
        UUID champ = UUID.randomUUID();
        long now = 2_000L;
        // 五个不同干员各申请封不同词条 (模拟同怪多特勤在场)。
        UUID[] agents = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        String[] affixes = {"frost", "armor", "wither", "leech", "thorns"};
        int accepted = 0;
        for (int i = 0; i < agents.length; i++) {
            // L7 干员封 7★ 精英 -> sealSlots(7,7)=1 (单槽)。
            SealRegistry.ApplyResult r = SealRegistry.applySeal(champ, agents[i], affixes[i],
                    SealCategory.PASSIVE, 7, 7, now + i);
            if (r.ok()) {
                accepted++;
            } else {
                helper.assertTrue(r.reason() == SealRegistry.FailReason.ALL_SLOTS_OCCUPIED,
                        "extra agent " + i + " rejected for slot occupancy, not stacking");
            }
        }
        // 恰好 1 份被接受 (先到先得), 其余 4 人全被拒 (槽已占) —— 封印量 = 1, 与 5 人在场无关。
        helper.assertTrue(accepted == 1, "exactly one seal accepted out of five agents (first-come single slot)");
        helper.assertTrue(SealRegistry.activeSealCount(champ, now + 5L) == 1,
                "seal count stays 1 with five agents present (headcount does NOT multiply seals)");

        // 8★ 精英 (L10 干员 -> 2 槽): 即便十个干员在场, 封印量封顶 2, 不随人数继续涨。
        SealRegistry.reset();
        UUID bigChamp = UUID.randomUUID();
        long t = 3_000L;
        int acceptedBig = 0;
        for (int i = 0; i < 10; i++) {
            SealRegistry.ApplyResult r = SealRegistry.applySeal(bigChamp, UUID.randomUUID(), "affix_" + i,
                    SealCategory.PASSIVE, 10, 8, t + i);
            if (r.ok()) {
                acceptedBig++;
            }
        }
        helper.assertTrue(acceptedBig == 2, "8star caps at 2 accepted seals even with ten agents present");
        helper.assertTrue(SealRegistry.activeSealCount(bigChamp, t + 10L) == 2,
                "8star seal count capped at 2 (slot capacity), not scaled by 10 agents");
        SealRegistry.reset();
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sealRegistryWindowFromApplicantNotExtendedBySecondAgent(GameTestHelper helper) {
        // 不叠加铁律之"窗口不延长": 窗口时长取申请者自身能力 (最强单份), 第二人不延长在押封印的到期。
        SealRegistry.reset();
        UUID champ = UUID.randomUUID();
        UUID agentLow = UUID.randomUUID();
        UUID agentHigh = UUID.randomUUID();
        long now = 0L;

        // L3 干员封 3★ 精英 frost: 被动窗口 8s = 160 tick。
        SealRegistry.ApplyResult low = SealRegistry.applySeal(champ, agentLow, "frost", SealCategory.PASSIVE, 3, 3, now);
        helper.assertTrue(low.ok(), "L3 agent seals frost");
        helper.assertTrue(low.expiryTick() == 8L * 20L, "L3 passive window 8s -> expiry 160 tick");

        // 高等级 L10 干员 (窗口 12s) 同 tick 想再压 frost: 互斥拒 (词条已封), 绝不把到期延长到 12s。
        SealRegistry.ApplyResult high = SealRegistry.applySeal(champ, agentHigh, "frost", SealCategory.PASSIVE, 10, 3, now);
        helper.assertTrue(!high.ok() && high.reason() == SealRegistry.FailReason.AFFIX_ALREADY_SEALED,
                "second (higher-level) agent re-sealing same affix is rejected, window not extended");

        // 原封印仍按申请者 L3 的 8s 窗口到期 (t=160 释放), 高级干员未把它续到 240 tick。
        helper.assertTrue(SealRegistry.activeSealCount(champ, 159L) == 1, "original L3 seal active just before its own expiry");
        helper.assertTrue(SealRegistry.activeSealCount(champ, 160L) == 0,
                "original seal expires on the L3 applicant's 8s window, NOT extended to the L10 agent's 12s");
        SealRegistry.reset();
        helper.succeed();
    }

    // ============================================================
    // 悬赏完成计数 + 入池门槛门控 (BountyDefinition / BountyProgress)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bountyKillCountAndQualification(GameTestHelper helper) {
        // 讨伐 3 只 ≥5★ 精英 (日常)。
        BountyDefinition def = new BountyDefinition("daily_kill_5star", BountyDefinition.Period.DAILY,
                BountyDefinition.TargetType.KILL_STAR_AT_LEAST, 5, 3, 800L, 600L, 0L);
        BountyProgress prog = new BountyProgress(def, 100L);

        // 合格击杀 6★ -> 计数 (星级达标 + 入池门槛达标)。
        helper.assertTrue(prog.recordKill(6, true), "qualified kill of 6star counts toward >=5star bounty");
        helper.assertTrue(prog.killCount() == 1, "kill count 1");
        // 未达入池门槛击杀不计 (封印不计贡献 -> 封了没打不算合格)。
        helper.assertTrue(!prog.recordKill(7, false), "unqualified kill (below contribution threshold) does NOT count");
        helper.assertTrue(prog.killCount() == 1, "kill count stays 1 (unqualified ignored)");
        // 星级不达标 (4★ < 5★ min) 不计。
        helper.assertTrue(!prog.recordKill(4, true), "4star kill does NOT count toward >=5star bounty");
        helper.assertTrue(prog.killCount() == 1, "kill count stays 1 (below min star)");
        // 再两次合格 5★/8★ -> 达 3 完成。
        prog.recordKill(5, true);
        prog.recordKill(8, true);
        helper.assertTrue(prog.killCount() == 3 && prog.isComplete(), "3 qualified kills complete the bounty");
        // 完成后击杀不再增计 (封顶)。
        helper.assertTrue(!prog.recordKill(9, true), "completed bounty no longer increments");
        helper.assertTrue(prog.killCount() == 3, "kill count capped at requiredCount after completion");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bountyClaimOnce(GameTestHelper helper) {
        BountyDefinition def = new BountyDefinition("daily_kill_1star", BountyDefinition.Period.DAILY,
                BountyDefinition.TargetType.KILL_STAR_AT_LEAST, 1, 1, 400L, 500L, 0L);
        BountyProgress prog = new BountyProgress(def, 50L);
        // 未完成不可领。
        helper.assertTrue(!prog.tryClaim(), "cannot claim before completion");
        prog.recordKill(2, true);
        helper.assertTrue(prog.isComplete(), "one qualified kill completes a count-1 bounty");
        // 首次领取成功, 二次领取失败 (不重复发奖)。
        helper.assertTrue(prog.tryClaim(), "first claim of a completed bounty succeeds");
        helper.assertTrue(!prog.tryClaim(), "second claim fails (reward not granted twice)");
        helper.assertTrue(prog.claimed(), "bounty marked claimed");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bountyAzureWeeklyOnly(GameTestHelper helper) {
        // 周常悬赏可给青辉石 (合法)。
        BountyDefinition weekly = new BountyDefinition("weekly_worldboss", BountyDefinition.Period.WEEKLY,
                BountyDefinition.TargetType.KILL_WORLD_BOSS, 8, 1, 5000L, 3000L, 4L);
        helper.assertTrue(weekly.azureReward() == 4L, "weekly bounty may grant azure (PvE-bound)");
        // 日常给青辉石是非法定义 -> 构造抛 (青辉石仅周常出)。
        boolean threw = false;
        try {
            new BountyDefinition("bad_daily_azure", BountyDefinition.Period.DAILY,
                    BountyDefinition.TargetType.KILL_STAR_AT_LEAST, 6, 1, 1000L, 800L, 2L);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        helper.assertTrue(threw, "daily bounty granting azure must throw (azure is weekly-only)");
        helper.succeed();
    }

    // ============================================================
    // 悬赏 UTC 翻日 / ISO 周重置 (BountyProgress + AgentClock) — 注入固定戳验边界
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bountyDailyRollover(GameTestHelper helper) {
        BountyDefinition def = new BountyDefinition("daily_kill", BountyDefinition.Period.DAILY,
                BountyDefinition.TargetType.KILL_STAR_AT_LEAST, 3, 5, 800L, 600L, 0L);
        long day1 = 19_500L; // 任意 epochDay。
        BountyProgress prog = new BountyProgress(def, day1);
        prog.recordKill(4, true);
        prog.recordKill(4, true);
        helper.assertTrue(prog.killCount() == 2, "two kills accrued on day1");
        // 同日不重置。
        helper.assertTrue(!prog.rolloverIfStale(day1), "same day does not reset");
        helper.assertTrue(prog.killCount() == 2, "same-day count preserved");
        // 翻日重置 (计数清零 + claimed 复位)。
        helper.assertTrue(prog.rolloverIfStale(day1 + 1L), "crossing UTC day resets the daily bounty");
        helper.assertTrue(prog.killCount() == 0 && !prog.claimed(), "daily count reset to 0 and unclaimed on new day");
        helper.assertTrue(prog.periodStamp() == day1 + 1L, "period stamp advances to the new day");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bountyWeeklyRolloverIsoWeek(GameTestHelper helper) {
        BountyDefinition def = new BountyDefinition("weekly_kill", BountyDefinition.Period.WEEKLY,
                BountyDefinition.TargetType.KILL_STAR_AT_LEAST, 6, 3, 5000L, 3000L, 4L);
        // 用 AgentClock 把固定 epochDay 折成 ISO 周戳验边界。
        // 2024-01-01 是周一 (ISO 周首), epochDay = 19723; 同 ISO 周内多日同周戳。
        long mondayEpochDay = java.time.LocalDate.of(2024, 1, 1).toEpochDay();
        long sameWeekSunday = java.time.LocalDate.of(2024, 1, 7).toEpochDay(); // 同 ISO 周周日。
        long nextMonday = java.time.LocalDate.of(2024, 1, 8).toEpochDay(); // 下 ISO 周周一。

        long week1 = AgentClock.isoWeekStampOf(mondayEpochDay);
        long week1Sun = AgentClock.isoWeekStampOf(sameWeekSunday);
        long week2 = AgentClock.isoWeekStampOf(nextMonday);
        helper.assertTrue(week1 == week1Sun, "Mon..Sun of the same ISO week share one week stamp");
        helper.assertTrue(week2 != week1, "next Monday starts a new ISO week stamp");
        helper.assertTrue(week2 > week1, "ISO week stamp is monotonically increasing across weeks");

        BountyProgress prog = new BountyProgress(def, week1);
        prog.recordKill(7, true);
        helper.assertTrue(prog.killCount() == 1, "one weekly kill accrued in week1");
        // 同 ISO 周 (周日) 不重置。
        helper.assertTrue(!prog.rolloverIfStale(week1Sun), "same ISO week (Sunday) does not reset weekly bounty");
        helper.assertTrue(prog.killCount() == 1, "weekly count preserved within the same ISO week");
        // 跨 ISO 周重置。
        helper.assertTrue(prog.rolloverIfStale(week2), "crossing ISO week resets the weekly bounty");
        helper.assertTrue(prog.killCount() == 0, "weekly count reset on new ISO week");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void agentClockUtcDayStampMagnitude(GameTestHelper helper) {
        long stamp = AgentClock.currentUtcDayStamp();
        long expected = java.time.Instant.now().atZone(java.time.ZoneOffset.UTC).toLocalDate().toEpochDay();
        helper.assertTrue(Math.abs(stamp - expected) <= 1L,
                "currentUtcDayStamp must be UTC epoch-day (within midnight window)");
        helper.assertTrue(stamp >= 19_000L, "UTC epoch-day magnitude (>=19000), not gameTime/24000 or millis");
        // 周戳与日戳同口径派生。
        long week = AgentClock.currentUtcWeekStamp();
        helper.assertTrue(week == AgentClock.isoWeekStampOf(stamp),
                "currentUtcWeekStamp derives from the same UTC day stamp");
        helper.succeed();
    }

    // ============================================================
    // clampLevel 越界防御 (查表不越界)
    // ============================================================

    // ============================================================
    // 五支线全曲线逐级钉值 (端点+单调之外, 逐级断点精确; 删任一表行必挂)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void skillTableScanPulseCdFullCurve(GameTestHelper helper) {
        // 第五章脉冲 CD 线性插值 60 - (lv-1)*30/9 (整数除); 逐级精确, 非仅端点。
        int[] expected = {60, 57, 54, 50, 47, 44, 40, 37, 34, 30};
        for (int lv = 1; lv <= 10; lv++) {
            helper.assertTrue(AgentSkillTable.scanPulseCdSeconds(lv) == expected[lv - 1],
                    "L" + lv + " scan pulse CD must be " + expected[lv - 1] + "s");
        }
        // 关键断点: 中段 L5=47s (非粗略 45s), 验整数除法插值非错误的均匀步进。
        helper.assertTrue(AgentSkillTable.scanPulseCdSeconds(5) == 47, "L5 pulse CD is 47s (integer-div interpolation)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void skillTablePassiveSealWindowFullCurve(GameTestHelper helper) {
        // 第四章被动封印窗口 (L3..L10): 8/9/9/10/11/11/11/12s; L<3 = 0 (未解锁)。
        helper.assertTrue(AgentSkillTable.sealWindowSeconds(1, SealCategory.PASSIVE) == 0, "L1 passive window 0 (locked)");
        helper.assertTrue(AgentSkillTable.sealWindowSeconds(2, SealCategory.PASSIVE) == 0, "L2 passive window 0 (locked)");
        int[] window = {8, 9, 9, 10, 11, 11, 11, 12}; // L3..L10
        for (int lv = 3; lv <= 10; lv++) {
            helper.assertTrue(AgentSkillTable.sealWindowSeconds(lv, SealCategory.PASSIVE) == window[lv - 3],
                    "L" + lv + " passive seal window must be " + window[lv - 3] + "s");
        }
        // 窗口随级非减 (高级窗口不短于低级)。
        for (int lv = 4; lv <= 10; lv++) {
            helper.assertTrue(
                    AgentSkillTable.sealWindowSeconds(lv, SealCategory.PASSIVE)
                            >= AgentSkillTable.sealWindowSeconds(lv - 1, SealCategory.PASSIVE),
                    "passive seal window non-decreasing at L" + lv);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void skillTablePassiveSealCdFullCurve(GameTestHelper helper) {
        // 第四章被动封印 CD (L3..L10): 30/30/26/24/22/20/20/18s; L<3 = 0。
        helper.assertTrue(AgentSkillTable.sealCooldownSeconds(2, SealCategory.PASSIVE) == 0, "L2 passive CD 0 (locked)");
        int[] cd = {30, 30, 26, 24, 22, 20, 20, 18}; // L3..L10
        for (int lv = 3; lv <= 10; lv++) {
            helper.assertTrue(AgentSkillTable.sealCooldownSeconds(lv, SealCategory.PASSIVE) == cd[lv - 3],
                    "L" + lv + " passive seal CD must be " + cd[lv - 3] + "s");
        }
        // L4 CD 沿用 L3 的 30s (第四章未单列, 直到 L5 才缩到 26s); 钉死这两个平台断点。
        helper.assertTrue(AgentSkillTable.sealCooldownSeconds(4, SealCategory.PASSIVE) == 30, "L4 passive CD holds 30s");
        helper.assertTrue(AgentSkillTable.sealCooldownSeconds(9, SealCategory.PASSIVE) == 20, "L9 passive CD holds 20s");
        // CD 随级非增 (高级 CD 不长于低级)。
        for (int lv = 4; lv <= 10; lv++) {
            helper.assertTrue(
                    AgentSkillTable.sealCooldownSeconds(lv, SealCategory.PASSIVE)
                            <= AgentSkillTable.sealCooldownSeconds(lv - 1, SealCategory.PASSIVE),
                    "passive seal CD non-increasing at L" + lv);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void skillTableMechanicSealWindowAndCdGate(GameTestHelper helper) {
        // 机制窗口/CD 仅 L8+ 非 0; L<8 全 0 (机制类 L8 才解锁)。
        for (int lv = 1; lv <= 7; lv++) {
            helper.assertTrue(AgentSkillTable.sealWindowSeconds(lv, SealCategory.MECHANIC) == 0,
                    "L" + lv + " mechanic seal window 0 (mechanic locked until L8)");
            helper.assertTrue(AgentSkillTable.sealCooldownSeconds(lv, SealCategory.MECHANIC) == 0,
                    "L" + lv + " mechanic seal CD 0 (mechanic locked until L8)");
        }
        // 机制窗口 L8/L9/L10 = 3/4/5s。
        helper.assertTrue(AgentSkillTable.sealWindowSeconds(8, SealCategory.MECHANIC) == 3, "L8 mechanic window 3s");
        helper.assertTrue(AgentSkillTable.sealWindowSeconds(9, SealCategory.MECHANIC) == 4, "L9 mechanic window 4s");
        helper.assertTrue(AgentSkillTable.sealWindowSeconds(10, SealCategory.MECHANIC) == 5, "L10 mechanic window 5s");
        // 机制 CD: L8/L9 沿用被动 20s, 仅 L10 单列 45s (短窗长 CD: 机制类高度克制)。
        helper.assertTrue(AgentSkillTable.sealCooldownSeconds(8, SealCategory.MECHANIC) == 20, "L8 mechanic CD 20s");
        helper.assertTrue(AgentSkillTable.sealCooldownSeconds(9, SealCategory.MECHANIC) == 20, "L9 mechanic CD 20s");
        helper.assertTrue(AgentSkillTable.sealCooldownSeconds(10, SealCategory.MECHANIC) == 45, "L10 mechanic CD 45s");
        // 机制窗口恒短于同级被动窗口 (机制更克制)。
        for (int lv = 8; lv <= 10; lv++) {
            helper.assertTrue(
                    AgentSkillTable.sealWindowSeconds(lv, SealCategory.MECHANIC)
                            < AgentSkillTable.sealWindowSeconds(lv, SealCategory.PASSIVE),
                    "mechanic seal window strictly shorter than passive at L" + lv);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void skillTableDamageBonusFractionFullCurve(GameTestHelper helper) {
        // 系数版逐级 = %/100; 第四章 5..15% -> 0.05..0.15。逐级钉死, 非仅端点。
        double[] frac = {0.05D, 0.06D, 0.07D, 0.08D, 0.09D, 0.10D, 0.11D, 0.12D, 0.13D, 0.15D};
        for (int lv = 1; lv <= 10; lv++) {
            helper.assertTrue(Math.abs(AgentSkillTable.damageBonusFraction(lv) - frac[lv - 1]) < 1e-9,
                    "L" + lv + " damage bonus fraction must be " + frac[lv - 1]);
        }
        // L9->L10 多跳 1% 即系数从 0.13 跳到 0.15 (非线性 0.14), 钉死非均匀末级跳。
        helper.assertTrue(Math.abs(AgentSkillTable.damageBonusFraction(10) - AgentSkillTable.damageBonusFraction(9) - 0.02D) < 1e-9,
                "L9->L10 damage bonus fraction jumps +0.02 (extra +1% at cap), not +0.01");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void skillTableMaxSealableStarLadder(GameTestHelper helper) {
        // 可封星级 = 等级 (L3 起; L<3 = 0 未解锁封印)。
        helper.assertTrue(AgentSkillTable.maxSealableStar(1) == 0, "L1 cannot seal any star (0)");
        helper.assertTrue(AgentSkillTable.maxSealableStar(2) == 0, "L2 cannot seal any star (0)");
        for (int lv = 3; lv <= 10; lv++) {
            helper.assertTrue(AgentSkillTable.maxSealableStar(lv) == lv,
                    "L" + lv + " can seal up to " + lv + "star (maxSealableStar = level)");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void skillTableSealSlotsMatrix(GameTestHelper helper) {
        // 槽容量: L<3 = 0; 否则 1, 仅 (star>=8 && level>=9) = 2。逐格钉死 8★ 边界与 L9 解锁门。
        helper.assertTrue(AgentSkillTable.sealSlots(2, 10) == 0, "L2 has 0 seal slots (seal locked until L3)");
        helper.assertTrue(AgentSkillTable.sealSlots(3, 7) == 1, "L3 on a 7star: 1 slot");
        helper.assertTrue(AgentSkillTable.sealSlots(10, 7) == 1, "even L10 on a 7star: still 1 slot (2nd slot needs 8star+)");
        helper.assertTrue(AgentSkillTable.sealSlots(8, 8) == 1, "8star but only L8 agent: 1 slot (2nd slot needs L9)");
        helper.assertTrue(AgentSkillTable.sealSlots(9, 8) == 2, "8star + L9 agent: 2 slots unlock");
        helper.assertTrue(AgentSkillTable.sealSlots(10, 10) == 2, "10star + L10 agent: 2 slots");
        helper.assertTrue(AgentSkillTable.sealSlots(9, 9) == 2, "9star + L9 agent: 2 slots (star>=8 boundary inclusive)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void enhancedRewardStarArgumentValidation(GameTestHelper helper) {
        // raw = floor(star * 600 * mult). 边界星级 1★/10★ 在 L1/L10 精确。
        helper.assertTrue(AgentEnhancedReward.extraCreditRaw(1, 1) == 600L, "L1 kill of 1star: 1*600*1.0 = 600");
        helper.assertTrue(AgentEnhancedReward.extraCreditRaw(10, 1) == 1_800L, "L10 kill of 1star: 1*600*3.0 = 1800");
        // 中级倍率落 .x5 时按初始星级折算 floor (无浮点尾差): L4 (x1.75), 7★ = 7*600*1.75 = 7350 (整)。
        helper.assertTrue(AgentEnhancedReward.extraCreditRaw(4, 7) == 7_350L, "L4 kill of 7star: 7*600*1.75 = 7350");
        // L8 (x2.7), 3★ = 3*600*2.7 = 4860 (整, 无 floor 损失)。
        helper.assertTrue(AgentEnhancedReward.extraCreditRaw(8, 3) == 4_860L, "L8 kill of 3star: 3*600*2.7 = 4860");
        // 越界星级 (0 / 11) 抛 IllegalArgumentException 自然冒泡 (不掩盖)。
        boolean threwLow = false;
        try {
            AgentEnhancedReward.extraCreditRaw(5, 0);
        } catch (IllegalArgumentException expected) {
            threwLow = true;
        }
        helper.assertTrue(threwLow, "extraCreditRaw with star 0 throws (star out of [1,10])");
        boolean threwHigh = false;
        try {
            AgentEnhancedReward.extraCreditRaw(5, 11);
        } catch (IllegalArgumentException expected) {
            threwHigh = true;
        }
        helper.assertTrue(threwHigh, "extraCreditRaw with star 11 throws (star out of [1,10])");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void enhancedRewardScalesByInitialStarOneToThree(GameTestHelper helper) {
        // 加强奖励倍率按初始星级 ×1->×3 的语义: 固定 L10 倍率 ×3.0, raw 严格按初始星级线性放大 (1★->10★)。
        long oneStar = AgentEnhancedReward.extraCreditRaw(10, 1);   // 1*600*3.0 = 1800
        long tenStar = AgentEnhancedReward.extraCreditRaw(10, 10);  // 10*600*3.0 = 18000
        helper.assertTrue(oneStar == 1_800L, "L10 1star enhanced credit = 1800");
        helper.assertTrue(tenStar == 18_000L, "L10 10star enhanced credit = 18000");
        // 10★ 恰为 1★ 的 10 倍 (raw 按初始星级线性, 高星更多, 低星点缀)。
        helper.assertTrue(tenStar == oneStar * 10L, "enhanced credit scales linearly by initial star (10star = 10x 1star)");
        // 同等级下逐星严格递增 (高星初始更值钱)。
        for (int star = 2; star <= 10; star++) {
            helper.assertTrue(AgentEnhancedReward.extraCreditRaw(7, star) > AgentEnhancedReward.extraCreditRaw(7, star - 1),
                    "fixed L7: enhanced credit strictly increases with initial star at " + star + "star");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void clampLevelBounds(GameTestHelper helper) {
        helper.assertTrue(AgentSkillTable.clampLevel(0) == 1, "level 0 clamps to 1");
        helper.assertTrue(AgentSkillTable.clampLevel(-5) == 1, "negative level clamps to 1");
        helper.assertTrue(AgentSkillTable.clampLevel(11) == 10, "level 11 clamps to 10");
        helper.assertTrue(AgentSkillTable.clampLevel(100) == 10, "over-cap level clamps to 10");
        // 越界等级查表不抛 (夹断后取端点值)。
        helper.assertTrue(AgentSkillTable.damageBonusPercent(0) == 5, "clamped L0 -> L1 damage bonus +5%");
        helper.assertTrue(AgentSkillTable.damageBonusPercent(99) == 15, "clamped L99 -> L10 damage bonus +15%");
        helper.succeed();
    }

    // ============================================================
    // 周青辉石产出软上限门控 (AgentBountySavedData; 缺口 A 自实现, champions-free 可直测)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void weeklyAzureSoftCapWithinWeek(GameTestHelper helper) {
        AgentBountySavedData data = new AgentBountySavedData();
        UUID player = UUID.randomUUID();
        long week = 202401L;
        long cap = AgentBountySavedData.WEEKLY_AZURE_SOFT_CAP;

        // 首发未撞顶: 全额放行。
        long first = data.tryGrantWeeklyAzure(player, 20L, week);
        helper.assertTrue(first == 20L, "first weekly azure grant under cap passes in full (20)");
        helper.assertTrue(data.weeklyAzureGranted(player, week) == 20L, "weekly granted accrues to 20");

        // 第二发跨过软上限: 只发到撞顶的剩余额度 (软上限语义, 非整笔拒绝)。
        long second = data.tryGrantWeeklyAzure(player, cap, week);
        helper.assertTrue(second == cap - 20L, "second grant clamps to remaining cap (cap-20=" + (cap - 20L) + ")");
        helper.assertTrue(data.weeklyAzureGranted(player, week) == cap, "weekly granted now at cap");

        // 撞顶后再发: 0 (本周不再产)。
        long third = data.tryGrantWeeklyAzure(player, 5L, week);
        helper.assertTrue(third == 0L, "grant after hitting weekly cap returns 0 (no over-issue)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void weeklyAzureRolloverResetsCount(GameTestHelper helper) {
        AgentBountySavedData data = new AgentBountySavedData();
        UUID player = UUID.randomUUID();
        long cap = AgentBountySavedData.WEEKLY_AZURE_SOFT_CAP;

        // 第一周打满。
        long w1 = data.tryGrantWeeklyAzure(player, cap, 202401L);
        helper.assertTrue(w1 == cap, "week1 grant fills the cap");
        helper.assertTrue(data.tryGrantWeeklyAzure(player, 10L, 202401L) == 0L, "week1 capped: further grant 0");

        // 跨 ISO 周: 本周计数清零, 额度全恢复。
        long w2 = data.tryGrantWeeklyAzure(player, 30L, 202402L);
        helper.assertTrue(w2 == 30L, "new ISO week resets weekly azure count; full 30 passes");
        helper.assertTrue(data.weeklyAzureGranted(player, 202402L) == 30L, "week2 granted is 30 (week1 count gone)");
        // 旧周戳查询返 0 (不串)。
        helper.assertTrue(data.weeklyAzureGranted(player, 202401L) == 0L,
                "querying a stale week stamp returns 0 (no cross-week leakage)");
        helper.succeed();
    }

    // ============================================================
    // 入职标志门控 (AgentBountySavedData.activeAgents; 修福利泄漏 Major, champions-free 可直测)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void activeAgentFlagGatesEnhancedReward(GameTestHelper helper) {
        AgentBountySavedData data = new AgentBountySavedData();
        UUID neverAgent = UUID.randomUUID(); // 从未做过特勤工作 (全服普通玩家)。
        UUID didAgentWork = UUID.randomUUID(); // 执行过特勤活计 (如封印申请成功)。

        // 门: 未做过工作的玩家 isActiveAgent=false (即便框架等级对其默认返 1, 也不开特勤专属福利)。
        helper.assertTrue(!data.isActiveAgent(neverAgent),
                "a player who never did agent work is NOT an active agent (no welfare leak via default level)");

        // 置位: 玩家执行特勤活计 -> markActiveAgent 首次置位返 true。
        helper.assertTrue(data.markActiveAgent(didAgentWork),
                "marking a player who did agent work returns true on first set");
        helper.assertTrue(data.isActiveAgent(didAgentWork),
                "a player who did agent work IS an active agent (enhanced reward / damage bonus unlocked)");

        // 幂等: 已置位再调返 false (不重复落盘), 但标志保持。
        helper.assertTrue(!data.markActiveAgent(didAgentWork),
                "re-marking an already-active agent returns false (idempotent, no duplicate dirty)");
        helper.assertTrue(data.isActiveAgent(didAgentWork), "active flag persists after idempotent re-mark");

        // 隔离: 置位一名玩家不污染另一名 (按 UUID 独立)。
        helper.assertTrue(!data.isActiveAgent(neverAgent),
                "marking one player does not leak the active flag to an unrelated player");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void activeAgentFlagSurvivesNbtRoundTrip(GameTestHelper helper) {
        AgentBountySavedData data = new AgentBountySavedData();
        UUID didAgentWork = UUID.randomUUID();
        data.markActiveAgent(didAgentWork);
        // 同时落一笔周产, 验入职标志与周产计数各自独立持久化 (互不串)。
        data.tryGrantWeeklyAzure(didAgentWork, 7L, 202401L);

        net.minecraft.nbt.CompoundTag tag = data.save(new net.minecraft.nbt.CompoundTag());
        AgentBountySavedData reloaded = AgentBountySavedData.load(tag);

        helper.assertTrue(reloaded.isActiveAgent(didAgentWork),
                "active-agent flag survives NBT save/load round trip (persistent 'did agent work' fact)");
        helper.assertTrue(reloaded.weeklyAzureGranted(didAgentWork, 202401L) == 7L,
                "weekly azure count also survives the round trip (independent of the active flag)");
        helper.assertTrue(!reloaded.isActiveAgent(UUID.randomUUID()),
                "an unrelated player is not active after reload");
        helper.succeed();
    }

    // ============================================================
    // 封印接缝 dev 短路 (AgentSealSeam; Champions 未加载优雅退化, champions-free 可直测)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sealSeamShortCircuitsWhenUnbound(GameTestHelper helper) {
        // dev (Champions 未加载) 接缝未绑定: 封印申请优雅短路返 NOT_BOUND, 扫描快照返 null, 不触 Champions, 不抛。
        AgentSealSeam.unbind();
        helper.assertTrue(!AgentSealSeam.isBound(), "seam is unbound in dev (Champions not loaded)");
        AgentSealSeam.SealOutcome outcome = AgentSealSeam.requestSealResult(null, null, "miningdim:composite_armor");
        helper.assertTrue(outcome == AgentSealSeam.SealOutcome.NOT_BOUND,
                "unbound seam seal request short-circuits to NOT_BOUND (no Champions touch)");
        helper.assertTrue(AgentSealSeam.buildScanSnapshot(null, null) == null,
                "unbound seam scan snapshot short-circuits to null (no Champions touch)");
        // 服务端停止清理在未绑定时空操作, 不抛。
        AgentSealSeam.onServerStopping();
        helper.succeed();
    }

    // ============================================================
    // 加强奖励经主闸 (纯逻辑算额, 不实发): extraCreditRaw 喂 grantDaily(credit_faucet) 并入衰减主闸 +
    // 撞限后按地板 (7.1 + 第十一章决策 2: 全 faucet 共享每人每日衰减主闸 0.6/60000 档/1% 地板)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void enhancedRewardIsPureCreditFaucetAmount(GameTestHelper helper) {
        // 加强奖励是"纯逻辑算额, 不实发": extraCreditRaw 只产 CREDIT raw (绝不含青辉石), 交集成层喂 grantDaily。
        // 青辉石仅周常悬赏出, 加强奖励不碰; 本层只给"喂主闸的原始信用点", 故 raw 必 >0 且全 CREDIT。
        long raw = AgentEnhancedReward.extraCreditRaw(10, 10); // L10 x3.0 杀 10★ = 10*600*3.0 = 18000。
        helper.assertTrue(raw == 18_000L, "L10 vs 10star enhanced reward raw = 10*600*3.0 = 18000 (pure CREDIT)");

        // 该 raw 必须 >0 才喂 grantDaily (AgentRewardHandler 对 <=0 短路不发, 因 grantDaily 对 <=0 抛)。
        // 最低折算: L1 x1.0 杀 1★ = 1*600*1.0 = 600 > 0, 仍是可喂主闸的合法正额。
        helper.assertTrue(AgentEnhancedReward.extraCreditRaw(1, 1) == 600L,
                "L1 vs 1star raw = 1*600*1.0 = 600 (>0, a fundable faucet amount)");
        // 越界星级自然冒泡 (不静默兜底): star=0 / star=11 抛 IllegalArgumentException。
        boolean threwLow = false;
        try {
            AgentEnhancedReward.extraCreditRaw(5, StarRank.MIN_STAR - 1);
        } catch (IllegalArgumentException expected) {
            threwLow = true;
        }
        helper.assertTrue(threwLow, "star below MIN_STAR throws (no silent fallback amount)");
        boolean threwHigh = false;
        try {
            AgentEnhancedReward.extraCreditRaw(5, StarRank.MAX_STAR + 1);
        } catch (IllegalArgumentException expected) {
            threwHigh = true;
        }
        helper.assertTrue(threwHigh, "star above MAX_STAR throws (no silent clamp to a fundable amount)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void enhancedRewardThroughFaucetDecaysToFloor(GameTestHelper helper) {
        // 端到端纯逻辑: 加强奖励 raw (extraCreditRaw) 喂全服衰减主闸 (faucetCreditAfterDecay, 与集成层 grantDaily
        // 同一引擎/同一档值/同一键命名空间), 验"撞限后按地板"——当日累计毛收入推深后, 同一笔 raw 的净入账逐档递减
        // 直到夹住地板 (深档恒按地板系数, 不再随等级倍率线性膨胀)。这是"加强奖励不破每日天花板"的数值闸。
        AbuseGuard guard = new AbuseGuard();
        long tier = EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER; // 60000
        double floorRatio = EconomyConstants.ECONOMY_PRICE_FLOOR_RATIO; // 1% 地板 (credit faucet 权威值)

        // L10 杀 10★ 加强奖励 raw = 18000。
        long raw = AgentEnhancedReward.extraCreditRaw(10, 10);
        helper.assertTrue(raw == 18_000L, "enhanced reward raw fed to the faucet = 18000");

        // 当日尚未入账 (累计 0, 第 0 档系数 1.0): 18000 全额入账 (未撞限, 加强奖励首杀全得)。
        long fresh = guard.faucetCreditAfterDecay(0L, raw, tier);
        helper.assertTrue(fresh == 18_000L, "first kill of the day: enhanced reward credits in full (band0 x1.0 = 18000)");

        // 当日累计已推到第 1 档边界 (60000, 系数 0.6): 同一笔 18000 净入账 = floor(18000*0.6) = 10800 (递减, 仍未夹地板)。
        long band1 = guard.faucetCreditAfterDecay(tier, raw, tier);
        helper.assertTrue(band1 == 10_800L,
                "at band1 (cumulative 60000) the same 18000 reward decays to floor(18000*0.6) = 10800");

        // 极深档 (累计推到 100 档, 0.6^100 << 1% 地板): 整笔按地板 -> floor(18000*0.01) = 180 (撞限后按地板)。
        long deep = guard.faucetCreditAfterDecay(100L * tier, raw, tier);
        helper.assertTrue(deep == (long) Math.floor(raw * floorRatio),
                "deep band clamps the enhanced reward to the 1% floor: floor(18000*0.01)");
        helper.assertTrue(deep == 180L, "deep-band enhanced reward floored to 180 (no level-multiplier inflation past the floor)");

        // 单调: 同一笔 raw 随当日累计加深净入账非增 (全额 -> 0.6 档 -> 地板), 体现"撞限后按地板"的封顶语义。
        helper.assertTrue(fresh > band1 && band1 > deep,
                "the same reward yields strictly less as the day's faucet fills (full > band1 > floor)");
        // 地板非零 (1% 薄收益, 不归零): 深档仍发 180 > 0, 杜绝深档加强奖励被吞光 (软上限是递减不是硬墙)。
        helper.assertTrue(deep > 0L, "the floor keeps a thin non-zero income at depth (soft cap decays, not a hard wall)");
        helper.succeed();
    }

    // ============================================================
    // 伤害加成: 对精英名义伤害的少量乘子 ×(1+fraction), 上限 +15% (FF14 生产职铁律: 战斗只少量, 严防战力)
    // 镜像 AgentDamageBonusHandler 的纯逻辑 (event.setAmount(amount*(1+fraction))), 不触 Champions/事件
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void damageBonusIsSmallMultiplierWithCappedCeiling(GameTestHelper helper) {
        // AgentDamageBonusHandler 对精英名义伤害做 amount*(1+fraction) 单点放大; 这里以纯函数复算该乘子并断言"少量"+上限。
        float nominal = 100.0F; // 干员对精英的名义伤害 (后续走下游易伤/净减伤/有效血池)。

        // L1: +5% -> 100 放大到 105 (少量起点)。
        float l1 = nominal * (float) (1.0D + AgentSkillTable.damageBonusFraction(1));
        helper.assertTrue(Math.abs(l1 - 105.0F) < 1e-3F, "L1 amplifies 100 nominal damage to 105 (+5%)");
        // L10: +15% -> 100 放大到 115 (封顶档; 末级多跳到 +15% 而非线性 +14%)。
        float l10 = nominal * (float) (1.0D + AgentSkillTable.damageBonusFraction(10));
        helper.assertTrue(Math.abs(l10 - 115.0F) < 1e-3F, "L10 amplifies 100 nominal damage to 115 (+15%, the ceiling)");

        // "少量"上限铁律 (严防战力): 任何等级的放大乘子 <= 1.15 (最多 +15%), 即输出最多放大 15%, 绝不数值膨胀。
        for (int lv = 1; lv <= 10; lv++) {
            double mult = 1.0D + AgentSkillTable.damageBonusFraction(lv);
            helper.assertTrue(mult <= 1.15D + 1e-9D,
                    "L" + lv + " damage multiplier never exceeds 1.15 (small bonus only, no power creep)");
            helper.assertTrue(mult >= 1.05D - 1e-9D,
                    "L" + lv + " damage multiplier is at least 1.05 (the +5% floor of the bonus line)");
        }
        // 系数与百分比同源 (fraction = percent/100), 防两处漂移: L10 fraction 必 = 0.15。
        helper.assertTrue(Math.abs(AgentSkillTable.damageBonusFraction(10) - AgentSkillTable.damageBonusPercent(10) / 100.0D) < 1e-9D,
                "damageBonusFraction stays = damageBonusPercent/100 (single source, no drift)");
        // 乘子随等级单调不减 (高级加成不弱于低级), 但被 1.15 顶夹住。
        for (int lv = 2; lv <= 10; lv++) {
            helper.assertTrue(AgentSkillTable.damageBonusFraction(lv) >= AgentSkillTable.damageBonusFraction(lv - 1),
                    "damage bonus is monotonically non-decreasing across levels at L" + lv);
        }
        helper.succeed();
    }
}

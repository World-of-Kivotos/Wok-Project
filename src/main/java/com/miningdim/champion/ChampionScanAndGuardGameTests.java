package com.miningdim.champion;

import com.miningdim.champion.integration.AoeImmunityBuffer;
import com.miningdim.champion.reward.ContributionPool;
import com.miningdim.champion.reward.ContributionTracker;
import com.miningdim.champion.reward.DamageContribution;
import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * perf/champion-handlers 分支配套测试 (Full_Repo_Audit_2026-08 F102/F103/F104 TDD 钉子)。
 *
 * 只断言纯逻辑可见缝, 不碰 Champions/世界:
 * - {@link AoeImmunityBuffer#shouldGrantAfterAoeHit} 窗内不续窗不变式 (F102)。
 * - {@link ChampionCaesarSwapPlan#shouldSwap} 拒绝换位落地保护中目标的真值表 (F104)。
 * - {@link ContributionTracker#drain} 按首伤 tick 确定序 + {@link ContributionPool#distribute} 末名下钳不为负 (F103)。
 *
 * template = "empty", batch = "champion_chain"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionScanAndGuardGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_chain";

    // ============================================================
    // F102: AOE 免疫缓冲开窗判定 —— 窗内命中不得续窗
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void aoeBufferDoesNotReopenWindowMidWindow(GameTestHelper helper) {
        UUID player = UUID.randomUUID();

        // (a) 空账本: 窗外的首发是真实命中, 应开窗。
        ExpiryLedger emptyLedger = new ExpiryLedger();
        helper.assertTrue(AoeImmunityBuffer.shouldGrantAfterAoeHit(player, 100L, emptyLedger),
                "空账本 nowTick=100 -> 应开窗 (窗外首发为真实命中)");

        // (b) 窗内 (140 到期) 的 120 tick 再命中 -> 不应再开窗。
        ExpiryLedger windowLedger = new ExpiryLedger();
        windowLedger.grant(player, 140L);
        helper.assertTrue(!AoeImmunityBuffer.shouldGrantAfterAoeHit(player, 120L, windowLedger),
                "窗内 (120 < 140) 再命中 -> 不开窗 (F102 钉子: 删 !ledger.isActive(...) 取反判据, 本条必翻真)");

        // (c) 半开区间边界: 139 仍窗内, 140 到期即出窗 (与 ExpiryLedger 自身半开语义单一钉死一致)。
        helper.assertTrue(!AoeImmunityBuffer.shouldGrantAfterAoeHit(player, 139L, windowLedger),
                "边界 nowTick=139 仍在窗内 -> 不开窗");
        helper.assertTrue(AoeImmunityBuffer.shouldGrantAfterAoeHit(player, 140L, windowLedger),
                "边界 nowTick=140 到期出窗 -> 应开窗");

        // (d) 链式延长不变式 (F102 危害核心): 窗内再命中 -> 判定不开窗 -> 不调用 grant -> 到期点仍是原 140,
        // 不会被二次命中推迟到 160 (站在爆点不动无法无限续窗)。
        ExpiryLedger chainLedger = new ExpiryLedger();
        chainLedger.grant(player, 140L);
        boolean shouldReGrantAt120 = AoeImmunityBuffer.shouldGrantAfterAoeHit(player, 120L, chainLedger);
        helper.assertTrue(!shouldReGrantAt120, "窗内 120 tick 二次 AOE 命中: 判定不应开窗, 故不调用 grant");
        // 遵循判定结果不 grant (与真实 grantIfNotBuffered 行为一致): 145 tick (> 原到期 140) 应已出窗。
        helper.assertTrue(!chainLedger.isActive(player, 145L),
                "不续窗: 145 tick 应已出窗, 到期点未被二次命中推迟到 160 (F102 危害钉子)");

        // 对照组: 若旧行为无条件续窗 (忽略 shouldGrantAfterAoeHit 判定, 每发都强行 grant), 145 tick 会仍在窗内 ——
        // 用这组反差证明本判据确实堵死了"站在爆点里不动就能无限续窗"的漏洞。
        ExpiryLedger oldBehaviorLedger = new ExpiryLedger();
        oldBehaviorLedger.grant(player, 140L);
        oldBehaviorLedger.grant(player, 160L); // 模拟旧代码无条件续窗
        helper.assertTrue(oldBehaviorLedger.isActive(player, 145L),
                "对照组: 无条件续窗会让 145 tick 仍判定窗内 -> 反证本判据是防无限续窗的关键闸");

        helper.succeed();
    }

    // ============================================================
    // F104: 凯撒换位准入 —— 拒绝落地保护中的目标
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void caesarSwapRefusesLandingProtectedTarget(GameTestHelper helper) {
        // 完整真值表: 唯一放行组合是 目标未在落地保护窗内 + 双侧落点都安全。
        helper.assertTrue(ChampionCaesarSwapPlan.shouldSwap(false, true),
                "未落地保护 + 双侧安全 -> 换位 (唯一放行组合)");
        helper.assertTrue(!ChampionCaesarSwapPlan.shouldSwap(true, true),
                "目标仍在落地保护窗内 + 双侧安全 -> 不换位 (F104 钉子: 删 !targetLandingProtected 此处必翻真)");
        helper.assertTrue(!ChampionCaesarSwapPlan.shouldSwap(false, false),
                "未落地保护 + 双侧不安全 -> 不换位");
        helper.assertTrue(!ChampionCaesarSwapPlan.shouldSwap(true, false),
                "落地保护中 + 双侧不安全 -> 不换位 (双重否决)");

        // 回归护栏: bothLandingsSafe 语义未被本次改动破坏 (两侧都真才真; ChampionCaesarSwapGameTests 已覆盖,
        // 此处只作最小复核防止 shouldSwap 改动误伤其委托的既有语义)。
        helper.assertTrue(ChampionCaesarSwapPlan.bothLandingsSafe(true, true), "双侧都安全 -> true");
        helper.assertTrue(!ChampionCaesarSwapPlan.bothLandingsSafe(true, false), "冠军目的格不安全 -> false");
        helper.assertTrue(!ChampionCaesarSwapPlan.bothLandingsSafe(false, true), "玩家目的格不安全 -> false");
        helper.assertTrue(!ChampionCaesarSwapPlan.bothLandingsSafe(false, false), "双侧都不安全 -> false");

        helper.succeed();
    }

    // ============================================================
    // F103: 贡献 drain 确定序 (按首伤 tick) + ContributionPool 末名下钳不为负
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void contributionDrainIsOrderedByFirstHitTick(GameTestHelper helper) {
        // 固定 UUID (非 randomUUID): 让 ConcurrentHashMap 内部哈希桶序稳定可复现 (已用 javac/java 独立跑通实际
        // ContributionTracker/ContributionPool 源码核实: 这三个 UUID 在本 JDK 上的桶序恰是插入序 p1,p2,p3, 与
        // 期望的首伤序 p2,p3,p1 不同), 从而让"未排序直接吃哈希桶序"的回退在本机每次运行都确定性地被本断言逮到,
        // 而不是偶发通过。
        UUID p1 = UUID.fromString("11111111-1111-1111-1111-111111111111"); // 插入序第1, 首伤 tick=300 (最晚)
        UUID p2 = UUID.fromString("22222222-2222-2222-2222-222222222222"); // 插入序第2, 首伤 tick=100 (最早)
        UUID p3 = UUID.fromString("33333333-3333-3333-3333-333333333333"); // 插入序第3, 首伤 tick=200 (居中)
        UUID champ = UUID.randomUUID();
        try {
            // (a) 乱序 record: 插入序 (p1,p2,p3) 与首伤序 (p2,p3,p1) 相反。
            ContributionTracker.record(champ, p1, 10.0D, 300L);
            ContributionTracker.record(champ, p2, 10.0D, 100L);
            ContributionTracker.record(champ, p3, 10.0D, 200L);
            List<DamageContribution> drained = ContributionTracker.drain(champ, id -> true);
            helper.assertTrue(drained.size() == 3, "drain 应返回全部 3 条贡献记录");
            helper.assertTrue(drained.get(0).firstHitTick() == 100L && drained.get(0).playerId().equals(p2),
                    "下标0应是首伤最早的 p2 (tick=100) (F103 钉子: 删排序退回哈希桶序, 本条必挂)");
            helper.assertTrue(drained.get(1).firstHitTick() == 200L && drained.get(1).playerId().equals(p3),
                    "下标1应是次早的 p3 (tick=200)");
            helper.assertTrue(drained.get(2).firstHitTick() == 300L && drained.get(2).playerId().equals(p1),
                    "下标2应是最晚的 p1 (tick=300)");
        } finally {
            ContributionTracker.reset(); // 防跨 test 脏账本 (ChampionGameTests:604-606 同范式)。
        }

        // (b) 同 tick 兜底: 两名玩家首伤 tick 都是 500, 按 UUID.compareTo 升序排列。
        UUID t1 = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID t2 = UUID.fromString("55555555-5555-5555-5555-555555555555");
        helper.assertTrue(t1.compareTo(t2) < 0, "前提核实: t1 应小于 t2 (compareTo<0), 后续断言基于此确定序");
        UUID champ2 = UUID.randomUUID();
        try {
            // 插入序刻意与预期输出序相反 (先 t2 后 t1), 确认排序结果不是巧合沿用了插入序。
            ContributionTracker.record(champ2, t2, 5.0D, 500L);
            ContributionTracker.record(champ2, t1, 5.0D, 500L);
            List<DamageContribution> drained2 = ContributionTracker.drain(champ2, id -> true);
            helper.assertTrue(drained2.size() == 2, "同 tick 两条记录均应保留");
            helper.assertTrue(drained2.get(0).playerId().equals(t1),
                    "同 tick 兜底: UUID 较小的 t1 排在前 (compareTo 升序)");
            helper.assertTrue(drained2.get(1).playerId().equals(t2),
                    "同 tick 兜底: UUID 较大的 t2 排在后");
        } finally {
            ContributionTracker.reset();
        }

        // (c) 末名份额下钳不为负 (ContributionPool.distribute, F103 后半修复)。
        // 构造: 9 名合格玩家, 前 8 名有效伤害 100, 末位 1 (权重极小), boss 总有效血取小 (200) 令全员过门槛一
        // (0.5% x 200 = 1.0, 末位 1.0 恰好达标), fixedPoolRaw 取 7。
        List<DamageContribution> contribs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            contribs.add(new DamageContribution(UUID.randomUUID(), 100.0D, i, true));
        }
        contribs.add(new DamageContribution(UUID.randomUUID(), 1.0D, 8, true)); // 末位权重极小
        double bossTotalEffectiveHp = 200.0D;
        long fixedPoolRaw = 7L;
        Map<UUID, Long> payout = ContributionPool.distribute(contribs, bossTotalEffectiveHp, fixedPoolRaw);
        helper.assertTrue(payout.size() == 9, "9 名玩家全部合格入账 (末位 1.0 恰卡在 boss 门槛 1.0 上, >= 达标)");

        // 逐笔累计钳制 (F103 修复后): 前 7 名各占 round(7x100/801)=1 且未撞剩余预算; 第 8 名 (仍是 dmg=100 的
        // 一员) round 也是 1, 但此时 remaining 已耗尽至 0, 钳到 0; 末位 (dmg=1.0, 权重极小) 吃剩余预算 0。
        List<Long> shares = new ArrayList<>(payout.values());
        for (int i = 0; i < 7; i++) {
            helper.assertTrue(shares.get(i) == 1L,
                    "前7名各分得 round(7 x 100/801) = 1 且未撞剩余预算 (F103 钉子: 删逐笔 Math.min 钳制会让"
                            + "这些份额脱离预算校验)");
        }
        helper.assertTrue(shares.get(7) == 0L,
                "第8名 (仍是 dmg=100 的一员) round 结果也是 1, 但此时剩余预算已耗尽为 0, 被逐笔钳制到 0"
                        + " (F103 钉子: 删 Math.min(raw, remaining) 后此处会发出 1, 总和随之超池)");
        long lastShare = shares.get(8);
        helper.assertTrue(lastShare == 0L,
                "末位 (dmg=1.0 权重极小) 吃剩余预算, 此时剩余预算恰为 0");
        for (Long share : shares) {
            helper.assertTrue(share >= 0L, "distribute 返回的每一份额均不得为负");
        }

        // Σ应得 恒等于 fixedPoolRaw (F103 核心不变式): 逐笔钳制到剩余预算, 既不会因 round 上偏累计超池,
        // 也不会因末名兜底漏发而少于池。
        long sum = 0L;
        for (Long share : shares) {
            sum += share;
        }
        helper.assertTrue(sum == fixedPoolRaw,
                "Σ应得 必须恰等于 fixedPoolRaw=7 (F103 钉子: 删逐笔钳制退回「仅钳末名」会让 Σ=8 超池, 本条必挂)");

        helper.succeed();
    }
}

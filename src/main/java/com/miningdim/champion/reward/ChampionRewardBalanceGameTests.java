package com.miningdim.champion.reward;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.champion.bloodpool.BloodPoolRegistry;
import com.miningdim.core.MiningConstants;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 精英奖励调参方案 D1-D6 的回归钉子 (账本记净伤 / 账本随 capability 持久化 / 在线+近期资格与作废不回池 /
 * 最大余数法 / 特勤加成占比门槛 / 奖池查表)。
 *
 * 每条断言钉住一条新口径, 删掉被测那段生产逻辑必挂:
 *  - 重甲整次免疫吃掉的近战、原版血量之外的溢出伤害不进份额 (D1);
 *  - 冠军实体存盘读回 (等价服务端重启) 后账本原样还在, 分池分母是全部净伤, 只贡献 1% 净伤的人只拿 1% (D2 + D3);
 *  - 离线者与最近命中超出近期窗口者份额作废, 且不回池重分给在场者 (D3);
 *  - 蹭枪者即便过了奖池门槛也拿不到特勤加成 (D5);
 *  - 取整按最大余数法, 平手先比净伤再比 UUID, 与输入序无关 (D3);
 *  - 6-10★ 奖池查标定表, 缩放旋钮整体生效 (D6)。
 *
 * 需要非默认参数的断言一律直调纯逻辑层带显式参数的重载, 不改 miningdim-champion.toml (GameTest 里改配置会触发
 * Forge 自写自重载, 见分支协作.md 第四节)。template = "empty", batch = "champion_reward"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionRewardBalanceGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_reward";
    private static final double EPS = 1e-6D;
    private static final long RECENCY = ContributionPool.DEFAULT_RECENCY_TICKS;

    // ============================================================
    // D1: 重甲免疫与溢出伤害不计份额 (两条记账路径各验一遍)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void heavyArmorImmuneHitsEarnNoShare(GameTestHelper helper) {
        ServerPlayer poker = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);   // 只会 15 伤近战
        ServerPlayer bruiser = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper); // 打得穿重甲
        Map<AffixDef, AffixQuality> heavy = new EnumMap<>(AffixDef.class);
        heavy.put(AffixDef.HEAVY_ARMOR, AffixQuality.LEGENDARY); // 近战/爆炸单次净伤 < 22 整次免疫

        // (a) 6★+ 血池路径: ChampionBloodPoolHandler 算出净伤后记账。
        Zombie pooled = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        MiningChampionData pooledData = MiningChampions.get(pooled).orElseThrow();
        pooledData.promote(8, heavy, 2_000.0D);
        UUID pooledId = pooled.getUUID();
        BloodPoolRegistry.install(pooledId, 2_000.0D);
        try {
            meleeHit(pooled, poker, 15.0F);
            helper.assertTrue(Math.abs(BloodPoolRegistry.get(pooledId).currentHp() - 2_000.0D) < EPS,
                    "前提: 15 伤近战低于重甲阈值 22, 整次免疫, 影子血不动");
            helper.assertTrue(!ContributionTracker.hasLedger(pooledData),
                    "被重甲整次免疫的命中净伤为 0, 不开账本");

            meleeHit(pooled, bruiser, 500.0F);
            List<DamageContribution> pooledLedger = ContributionTracker.snapshot(pooledData, id -> true);
            helper.assertTrue(pooledLedger.size() == 1 && pooledLedger.get(0).playerId().equals(bruiser.getUUID()),
                    "账本里只有真正扣了血的那名玩家, 实得 " + pooledLedger.size() + " 条");
            helper.assertTrue(Math.abs(pooledLedger.get(0).effectiveDamage() - 500.0D) < EPS,
                    "500 近战无比例减伤, 净伤 500");

            Map<UUID, Long> payout = ContributionPool.distribute(pooledLedger, 2_000.0D, 16_500L,
                    helper.getLevel().getGameTime(), RECENCY);
            helper.assertTrue(payout.size() == 1 && payout.get(bruiser.getUUID()) == 16_500L,
                    "免疫命中拿不到份额, 整池只按真实杀伤归属");
            helper.assertTrue(!payout.containsKey(poker.getUUID()), "免疫命中拿不到任何份额");
        } finally {
            BloodPoolRegistry.remove(pooledId);
        }

        // (b) 无血池路径 (1-5★): ChampionRewardHandler 在 LivingDamageEvent 记 min(amount, 扣血前血量)。
        Zombie plain = helper.spawn(EntityType.ZOMBIE, new BlockPos(3, 1, 0));
        plain.getAttribute(Attributes.ARMOR).setBaseValue(0.0D); // 去掉僵尸自带 2 点护甲, 让数值可精确断言
        plain.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100.0D);
        plain.setHealth(100.0F);
        MiningChampionData plainData = MiningChampions.get(plain).orElseThrow();
        plainData.promote(3, heavy, 100.0D);

        meleeHit(plain, poker, 15.0F);
        helper.assertTrue(Math.abs(plain.getHealth() - 100.0F) < 1e-3F, "前提: 低星冠军同样吃重甲免疫, 血量不动");
        helper.assertTrue(!ContributionTracker.hasLedger(plainData), "免疫命中不开账本");

        meleeHit(plain, bruiser, 30.0F);
        helper.assertTrue(Math.abs(plain.getHealth() - 70.0F) < 1e-3F, "前提: 30 伤穿过重甲阈值, 血量 100 -> 70");

        // 致死击 500 只剩 70 血可扣: 溢出不计。结算会清账, 故用 HIGHEST 探针在结算前取快照。
        AtomicReference<List<DamageContribution>> captured = new AtomicReference<>();
        Object probe = new Object() {
            @SubscribeEvent(priority = EventPriority.HIGHEST)
            public void onDeath(LivingDeathEvent event) {
                if (event.getEntity() == plain) {
                    captured.set(ContributionTracker.snapshot(plainData, id -> true));
                }
            }
        };
        MinecraftForge.EVENT_BUS.register(probe);
        try {
            meleeHit(plain, bruiser, 500.0F);
        } finally {
            MinecraftForge.EVENT_BUS.unregister(probe);
        }
        List<DamageContribution> plainLedger = captured.get();
        helper.assertTrue(plainLedger != null && plainLedger.size() == 1, "低星冠军死亡时账本只有一名贡献者");
        helper.assertTrue(Math.abs(plainLedger.get(0).effectiveDamage() - 100.0D) < EPS,
                "净伤 = 30 + 剩余 70 = 100 (= 血量上限, 溢出的 430 不计), 实得 " + plainLedger.get(0).effectiveDamage());
        helper.assertTrue(Math.abs(plainLedger.get(0).grossDamage() - 530.0D) < EPS,
                "毛伤仍记 30 + 500 = 530, 只供诊断, 实得 " + plainLedger.get(0).grossDamage());
        helper.succeed();
    }

    // ============================================================
    // D2 + D3: 实体存盘读回 (模拟重启) 后账本仍在, 结算按全部净伤分池
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ledgerSurvivesSaveReloadAndLateFinisherGetsOwnShare(GameTestHelper helper) {
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        MiningChampionData data = MiningChampions.get(zombie).orElseThrow();
        data.promote(10, Map.of(), 10_000.0D);
        long t0 = helper.getLevel().getGameTime();

        // 四人打出净伤合计 9900 (剩 1%), 随后实体存盘读回 (模拟重启), 结算时四人均不在线。
        UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        UUID c = UUID.fromString("00000000-0000-0000-0000-00000000000c");
        UUID d = UUID.fromString("00000000-0000-0000-0000-00000000000d");
        ContributionTracker.record(data, a, 5_000.0D, 2_500.0D, t0);
        ContributionTracker.record(data, b, 5_000.0D, 2_500.0D, t0 + 1L);
        ContributionTracker.record(data, c, 4_900.0D, 2_450.0D, t0 + 2L);
        ContributionTracker.record(data, d, 4_900.0D, 2_450.0D, t0 + 3L);
        data.setCurrentHp(100.0D);
        List<DamageContribution> before = ContributionTracker.snapshot(data, id -> true);

        // 整只实体走一次存盘 -> 新实例读盘 (ForgeCaps 里的冠军 capability 随之序列化/反序列化)。
        CompoundTag saved = zombie.saveWithoutId(new CompoundTag());
        Zombie reloaded = EntityType.ZOMBIE.create(helper.getLevel());
        if (reloaded == null) {
            helper.fail("EntityType.ZOMBIE.create returned null");
            return;
        }
        reloaded.load(saved);
        MiningChampionData restored = MiningChampions.get(reloaded).orElseThrow();
        List<DamageContribution> after = ContributionTracker.snapshot(restored, id -> true);
        helper.assertTrue(after.size() == 4, "存盘读回后账本 4 条全部还在, 实得 " + after.size());
        for (int i = 0; i < 4; i++) {
            DamageContribution x = before.get(i);
            DamageContribution y = after.get(i);
            helper.assertTrue(x.playerId().equals(y.playerId())
                            && Math.abs(x.effectiveDamage() - y.effectiveDamage()) < EPS
                            && Math.abs(x.grossDamage() - y.grossDamage()) < EPS
                            && x.firstHitTick() == y.firstHitTick() && x.lastHitTick() == y.lastHitTick(),
                    "第 " + i + " 条的玩家/净伤/毛伤/首末命中 tick 原样读回");
        }
        helper.assertTrue(Math.abs(restored.currentHp() - 100.0D) < EPS, "前提: 影子血同样读回 (剩 1%)");

        // 旧存档 (本字段上线前盖章的冠军) 没有 contributions 键: 按空账本续战, 不报错、不影响其它字段。
        CompoundTag legacyTag = data.serializeNBT();
        legacyTag.remove("contributions");
        MiningChampionData legacy = new MiningChampionData();
        legacy.deserializeNBT(legacyTag);
        helper.assertTrue(legacy.star() == 10 && !ContributionTracker.hasLedger(legacy), "旧存档缺键 -> 空账本");

        // 第五人打出最后 1% (100 净伤), 结算时只有他在线: 分母仍是全部净伤 10000, 他拿 45000 × 100/10000 = 450。
        UUID finisher = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
        long killTick = t0 + 200L;
        ContributionTracker.record(restored, finisher, 100.0D, 100.0D, killTick);
        List<DamageContribution> atDeath = ContributionTracker.snapshot(restored, id -> id.equals(finisher));
        Map<UUID, Long> payout = ContributionPool.distribute(atDeath, 10_000.0D, 45_000L, killTick, RECENCY);
        helper.assertTrue(payout.size() == 1 && payout.get(finisher) == 450L,
                "只贡献 1% 净伤的人只拿自己那 1% = 450, 实得 " + payout);
        helper.succeed();
    }

    // ============================================================
    // D3: 离线/过期者份额作废不回池 + 账本过期清理与条目上限
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void offlineAndStaleSharesAreVoidedNotRepooled(GameTestHelper helper) {
        MiningChampionData data = new MiningChampionData();
        data.promote(8, Map.of(), 10_000.0D);
        UUID active = UUID.randomUUID();
        UUID stale = UUID.randomUUID();
        UUID offline = UUID.randomUUID();
        long t0 = 10_000L;
        ContributionTracker.record(data, stale, 3_000.0D, 3_000.0D, t0);          // 打完就走了
        ContributionTracker.record(data, offline, 2_000.0D, 2_000.0D, t0 + 5_000L); // 近期打过但下线了
        ContributionTracker.record(data, active, 5_000.0D, 5_000.0D, t0 + 6_000L);
        List<DamageContribution> contributions = ContributionTracker.snapshot(data, id -> !id.equals(offline));

        // 结算时刻距 stale 最近命中 7000 tick > 6000: 作废; offline 离线: 作废。分母仍是全部净伤 10000,
        // active 只拿 16500 × 5000/10000 = 8250, 作废的 8250 不回池。
        long killTick = t0 + 7_000L;
        Map<UUID, Long> payout = ContributionPool.distribute(contributions, 10_000.0D, 16_500L, killTick, RECENCY);
        helper.assertTrue(payout.size() == 1 && payout.get(active) == 8_250L,
                "离线与过期者份额作废且不回池: active 只得 8250, 实得 " + payout);

        // 边界: 距最近命中恰 6000 tick 仍算近期 (含等号)。
        Map<UUID, Long> boundary = ContributionPool.distribute(contributions, 10_000.0D, 16_500L, t0 + 6_000L, RECENCY);
        helper.assertTrue(boundary.get(stale) == 4_950L && boundary.get(active) == 8_250L
                        && !boundary.containsKey(offline),
                "恰 6000 tick 仍合格: stale 4950 + active 8250, 离线者仍作废, 实得 " + boundary);
        // 近期窗口 0 = 关闭近期门槛 (只查在线)。
        Map<UUID, Long> disabled = ContributionPool.distribute(contributions, 10_000.0D, 16_500L, killTick, 0L);
        helper.assertTrue(disabled.equals(boundary), "contributionRecencyTicks=0 关闭近期门槛");

        // 账本过期清理: 最近命中早于 now − 72000 的条目在下一次写入时删除 (恰等于边界仍保留)。
        MiningChampionData aging = new MiningChampionData();
        UUID old = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        ContributionTracker.record(aging, old, 50.0D, 50.0D, 1_000L);
        ContributionTracker.record(aging, fresh, 50.0D, 50.0D, 1_000L + ContributionLedger.EXPIRY_TICKS);
        helper.assertTrue(aging.contributions().size() == 2, "最近命中恰在 72000 tick 边界上: 保留");
        ContributionTracker.record(aging, fresh, 50.0D, 50.0D, 1_001L + ContributionLedger.EXPIRY_TICKS);
        List<DamageContribution> aged = ContributionTracker.snapshot(aging, id -> true);
        helper.assertTrue(aged.size() == 1 && aged.get(0).playerId().equals(fresh), "超过 72000 tick 的条目被清理");

        // 0 净伤命中 (整次免疫) 不开条目, 也不刷新最近命中 tick。
        ContributionTracker.record(aging, fresh, 30.0D, 0.0D, 2_000L + ContributionLedger.EXPIRY_TICKS);
        helper.assertTrue(ContributionTracker.snapshot(aging, id -> true).get(0).lastHitTick()
                        == 1_001L + ContributionLedger.EXPIRY_TICKS,
                "0 净伤命中不刷新近期门槛用的最近命中 tick");

        // 条目上限 64: 第 65 人写入后淘汰净伤最小者。
        MiningChampionData crowd = new MiningChampionData();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i <= ContributionLedger.MAX_ENTRIES; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            ContributionTracker.record(crowd, id, i + 1.0D, i + 1.0D, 100L); // 净伤 1..65
        }
        List<DamageContribution> kept = ContributionTracker.snapshot(crowd, id -> true);
        helper.assertTrue(kept.size() == ContributionLedger.MAX_ENTRIES, "条目数封顶 64, 实得 " + kept.size());
        helper.assertTrue(kept.stream().noneMatch(x -> x.playerId().equals(ids.get(0))), "被淘汰的是净伤最小 (1) 的那条");
        helper.succeed();
    }

    // ============================================================
    // D5: 蹭枪者拿不到特勤加成 (占比门槛 clamp(0.5/N, 5%, 25%))
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void leechBelowAgentShareThresholdGetsNoBonus(GameTestHelper helper) {
        double floor = ContributionPool.DEFAULT_AGENT_SHARE_FLOOR;
        double ceil = ContributionPool.DEFAULT_AGENT_SHARE_CEIL;
        double fair = ContributionPool.DEFAULT_AGENT_SHARE_FAIR_FRACTION;

        // 10★ 中位怪裸血 17672: 4 名正式队员各 4392.75, 蹭枪者约 101 净伤 (0.57%)。
        double effHp = 17_672.0D;
        UUID leech = UUID.randomUUID();
        List<DamageContribution> contributions = new ArrayList<>();
        List<UUID> mains = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UUID id = UUID.randomUUID();
            mains.add(id);
            contributions.add(new DamageContribution(id, 4_392.75D, i, true));
        }
        contributions.add(new DamageContribution(leech, 101.0D, 10L, true));

        // 蹭枪者过了奖池门槛 (101 ≥ 0.5% × 17672 = 88.4), 照常按净伤拿池份额 257 ——
        Map<UUID, Long> payout = ContributionPool.distribute(contributions, effHp, 45_000L, 10L, RECENCY);
        helper.assertTrue(payout.get(leech) == 257L, "蹭枪者池份额 = 45000 × 101/17672 取整 = 257, 实得 " + payout.get(leech));

        // —— 但 N 只数占比 ≥1% 的人 (4), 门槛 T = 0.5/4 = 12.5%, 0.57% 远低于门槛, 特勤加成为 0。
        helper.assertTrue(ContributionPool.agentParticipantCount(contributions) == 4, "N 不含 <1% 的蹭枪者");
        helper.assertTrue(Math.abs(ContributionPool.agentShareThreshold(4, floor, ceil, fair) - 0.125D) < EPS,
                "4 人门槛 12.5%");
        helper.assertTrue(!ContributionPool.meetsAgentShareThreshold(leech, contributions, floor, ceil, fair),
                "蹭枪者 (0.57%) 拿不到特勤加成");
        for (UUID main : mains) {
            helper.assertTrue(ContributionPool.meetsAgentShareThreshold(main, contributions, floor, ceil, fair),
                    "正式队员 (24.9%) 过门槛");
        }

        // 单人击杀占比恒 1, 恒满足。
        List<DamageContribution> solo = List.of(new DamageContribution(leech, 101.0D, 10L, true));
        helper.assertTrue(ContributionPool.meetsAgentShareThreshold(leech, solo, floor, ceil, fair), "单人恒满足");

        // 门槛随 N 变化并被夹在 [5%, 25%]: 1 人 25%、5 人 10%、8 人 6.25%、12 人压到 5%。
        helper.assertTrue(Math.abs(ContributionPool.agentShareThreshold(1, floor, ceil, fair) - 0.25D) < EPS, "N=1 -> 25%");
        helper.assertTrue(Math.abs(ContributionPool.agentShareThreshold(5, floor, ceil, fair) - 0.10D) < EPS, "N=5 -> 10%");
        helper.assertTrue(Math.abs(ContributionPool.agentShareThreshold(8, floor, ceil, fair) - 0.0625D) < EPS, "N=8 -> 6.25%");
        helper.assertTrue(Math.abs(ContributionPool.agentShareThreshold(12, floor, ceil, fair) - 0.05D) < EPS,
                "N=12 -> 压到下限 5% (小号灌人数最多压到这里)");

        // 离线者也计入 N (防"让队友掉线"抬高自己的门槛占比): 5 人各 20% 其中 1 人离线, N 仍是 5, 门槛 10%。
        List<DamageContribution> five = new ArrayList<>();
        UUID dropped = UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            five.add(new DamageContribution(UUID.randomUUID(), 2_000.0D, i, true));
        }
        five.add(new DamageContribution(dropped, 2_000.0D, 9L, false));
        helper.assertTrue(ContributionPool.agentParticipantCount(five) == 5, "离线参战者计入 N");
        helper.succeed();
    }

    // ============================================================
    // D3: 最大余数法取整 (平手先比净伤再比 UUID, 与输入序无关)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void largestRemainderRoundingIsFairAndOrderIndependent(GameTestHelper helper) {
        UUID u1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID u2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID u3 = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID u4 = UUID.fromString("00000000-0000-0000-0000-000000000004");

        // 10★ 青辉石 10 颗 4 人均分: 旧逐笔 round + 末名吃余 = [3,3,3,1]; 最大余数法 = [3,3,2,2]。输入序倒排,
        // 结果仍按 UUID 给前两名, 证明与输入序无关。
        List<DamageContribution> four = List.of(
                new DamageContribution(u4, 1_000.0D, 1L, true),
                new DamageContribution(u3, 1_000.0D, 2L, true),
                new DamageContribution(u2, 1_000.0D, 3L, true),
                new DamageContribution(u1, 1_000.0D, 4L, true));
        Map<UUID, Long> azure = ContributionPool.distribute(four, 10_000.0D, 10L, 4L, RECENCY);
        helper.assertTrue(azure.get(u1) == 3L && azure.get(u2) == 3L && azure.get(u3) == 2L && azure.get(u4) == 2L,
                "青辉石 10 颗 4 人均分 -> [3,3,2,2], 实得 " + azure);

        // 小数部分平手时净伤高者优先 (UUID 更大也一样): 池 2, 净伤 3:1 -> 配额 1.5 / 0.5, 剩 1 个名额给净伤 3 的人。
        UUID big = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff0");
        List<DamageContribution> uneven = List.of(
                new DamageContribution(u1, 1.0D, 1L, true),
                new DamageContribution(big, 3.0D, 2L, true));
        Map<UUID, Long> tie = ContributionPool.distribute(uneven, 10.0D, 2L, 2L, RECENCY);
        helper.assertTrue(tie.get(big) == 2L && tie.get(u1) == 0L, "平手先比净伤, 再比 UUID, 实得 " + tie);

        // 6★ 青辉石 2 颗 8 人均分: 按 UUID 给最小的两人各 1。
        List<DamageContribution> eight = new ArrayList<>();
        for (int i = 8; i >= 1; i--) {
            eight.add(new DamageContribution(UUID.fromString(String.format("00000000-0000-0000-0000-%012d", i)),
                    500.0D, i, true));
        }
        Map<UUID, Long> small = ContributionPool.distribute(eight, 1_000.0D, 2L, 9L, RECENCY);
        long ones = small.values().stream().filter(v -> v == 1L).count();
        helper.assertTrue(ones == 2L && small.get(u1) == 1L && small.get(u2) == 1L,
                "6★ 2 颗 8 人均分 -> UUID 最小的两人各 1, 实得 " + small);
        helper.assertTrue(small.values().stream().mapToLong(Long::longValue).sum() == 2L, "总和恒等于池");
        helper.succeed();
    }

    // ============================================================
    // D6: 奖池查表 + 整体缩放
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void creditPoolTableMatchesCalibration(GameTestHelper helper) {
        List<Long> table = ChampionReward.DEFAULT_CREDIT_POOL_BY_STAR;
        long[] expected = {600L, 1_200L, 1_800L, 2_400L, 3_000L, 4_200L, 9_400L, 16_500L, 27_400L, 45_000L};
        for (int star = 1; star <= 10; star++) {
            helper.assertTrue(ChampionReward.creditPoolRaw(star, table, 1.0D) == expected[star - 1],
                    star + "star pool = " + expected[star - 1]);
        }
        for (int star = 1; star <= 5; star++) {
            helper.assertTrue(ChampionReward.creditPoolRaw(star, table, 1.0D) == star * ChampionReward.CREDIT_POOL_PER_STAR,
                    "1-5star 保持 600 × 星");
        }
        helper.assertTrue(ChampionReward.creditPoolRaw(10, table, 0.5D) == 22_500L, "缩放 0.5: 10star 45000 -> 22500");
        helper.assertTrue(ChampionReward.creditPoolRaw(7, table, 1.25D) == 11_750L, "缩放 1.25: 7star 9400 -> 11750");
        helper.assertTrue(ChampionReward.creditPoolRaw(9, table, 0.0D) == 0L, "缩放 0 = 关停信用点池");

        boolean shortTable = false;
        try {
            ChampionReward.creditPoolRaw(1, List.of(600L), 1.0D);
        } catch (IllegalArgumentException expectedThrow) {
            shortTable = true;
        }
        helper.assertTrue(shortTable, "表长度不是 10 必须抛");
        boolean negativeScale = false;
        try {
            ChampionReward.creditPoolRaw(1, table, -1.0D);
        } catch (IllegalArgumentException expectedThrow) {
            negativeScale = true;
        }
        helper.assertTrue(negativeScale, "负缩放必须抛");
        helper.succeed();
    }

    private static void meleeHit(LivingEntity victim, ServerPlayer attacker, float amount) {
        victim.invulnerableTime = 0;
        victim.hurt(victim.level().damageSources().playerAttack(attacker), amount);
    }
}

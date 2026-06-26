package com.miningdim.marriage;

import com.miningdim.core.MiningConstants;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.EconomyException;
import com.miningdim.economy.IEconomyService;
import com.miningdim.entry.IMiningPlayerData;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.registry.ModItems;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 结婚系统阶段 1 (典礼最小闭环) GameTest (结婚系统 spec 第二/三/九章)。服务端纯逻辑, 不依赖真经济:
 * 用记账 mock {@link LedgerEconomy} 经 {@link EconomyServices} 定位器 swap/restore (仿 MarketGameTests),
 * 以精确断言"双方各被扣一半 weddingCost"且"成本不足时事务回滚, 双方净额不变"。
 *
 * 强断言 (删被测核心逻辑必挂, 禁永真弱校验):
 *  1. 典礼成功: 双方 capability marriageId 互指且相等 + MarriageRegistry.forPlayer 返回同一 MarriageState +
 *     双方戒指 NBT 为结婚态 (WEDDING_RING, 盖正确 spouseUUID/marriageId) + 双方各被精确扣一半 weddingCost。
 *     删 createMarriage / 删盖戒指 / 删扣费 任一处, 对应断言挂。
 *  2. 成本不足: 典礼失败不留半成品 —— 无 MarriageState 登记、戒指仍订婚态、双方净额均为 0 (付得起的一方被退回)。
 *     删事务回滚 (B 失败时不退 A) 则 A 净额 != 0, 断言挂。
 *
 * template = "empty" (纯逻辑/SavedData/capability 断言不依赖结构)。每用例独立 swapEconomy, finally 还原。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MarriageGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "marriage";

    /** 典礼总成本 (测试固定值, 不读 config 以隔离配置加载依赖; 双方各付一半 = 10000)。 */
    private static final long WEDDING_COST = 20_000L;

    // ============================================================
    // 1. 典礼成功: 互指 marriageId + 同一 MarriageState + 双方结婚戒指 + 各扣一半
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void weddingLinksBothPartnersAndChargesEachHalf(GameTestHelper helper) {
        LedgerEconomy eco = new LedgerEconomy();
        IEconomyService prev = swapEconomy(eco);
        try {
            ServerLevel overworld = helper.getLevel().getServer().overworld();
            ServerPlayer a = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            ServerPlayer b = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

            // 双方各持一枚订婚戒指 + 足额信用点 (各 50000 > 10000 半价)。
            giveEngagementRing(a);
            giveEngagementRing(b);
            eco.setBalance(a.getUUID(), 50_000L);
            eco.setBalance(b.getUUID(), 50_000L);
            long aBefore = eco.balanceOf(a.getUUID());
            long bBefore = eco.balanceOf(b.getUUID());

            MarriageEngine engine = new MarriageEngine(overworld);
            MarriageEngine.WeddingResult result = engine.wed(a, b, WEDDING_COST, null);

            helper.assertTrue(result.success(), "wedding with funds + rings succeeds");
            long marriageId = result.marriageId();
            helper.assertTrue(marriageId > 0L, "wedding returns a positive marriage id");

            // 双方 capability marriageId 互指且相等。
            IMiningPlayerData dataA = MiningCapabilities.get(a).orElseThrow();
            IMiningPlayerData dataB = MiningCapabilities.get(b).orElseThrow();
            helper.assertTrue(dataA.marriageId() == marriageId,
                    "partner A capability marriageId equals the new marriage id");
            helper.assertTrue(dataB.marriageId() == marriageId,
                    "partner B capability marriageId equals the new marriage id (both point to the same id)");
            helper.assertTrue(b.getUUID().equals(dataA.spouseUUID()),
                    "partner A spouseUUID points to B");
            helper.assertTrue(a.getUUID().equals(dataB.spouseUUID()),
                    "partner B spouseUUID points to A (cross-pointing)");

            // MarriageRegistry.forPlayer 对双方返回同一 MarriageState。
            MarriageRegistry registry = MarriageRegistry.get(overworld);
            MarriageState stateA = registry.forPlayer(a.getUUID());
            MarriageState stateB = registry.forPlayer(b.getUUID());
            helper.assertTrue(stateA != null && stateB != null, "registry resolves a marriage for both partners");
            helper.assertTrue(stateA == stateB, "both partners resolve to the same MarriageState instance");
            helper.assertTrue(stateA.marriageId() == marriageId, "the resolved state carries the new marriage id");
            helper.assertTrue(stateA.involves(a.getUUID()) && stateA.involves(b.getUUID()),
                    "the marriage state involves both partners");

            // 双方戒指 NBT 为结婚态 (WEDDING_RING 实例 + 盖正确 spouseUUID/marriageId)。
            ItemStack ringA = firstRing(a);
            ItemStack ringB = firstRing(b);
            helper.assertTrue(ringA.getItem() == ModItems.WEDDING_RING.get(),
                    "partner A's engagement ring is replaced by a wedding ring");
            helper.assertTrue(ringB.getItem() == ModItems.WEDDING_RING.get(),
                    "partner B's engagement ring is replaced by a wedding ring");
            helper.assertTrue(b.getUUID().equals(RingItem.spouseUUID(ringA)),
                    "A's wedding ring is stamped with spouse = B");
            helper.assertTrue(a.getUUID().equals(RingItem.spouseUUID(ringB)),
                    "B's wedding ring is stamped with spouse = A");
            helper.assertTrue(RingItem.marriageId(ringA) == marriageId && RingItem.marriageId(ringB) == marriageId,
                    "both wedding rings are stamped with the marriage id");

            // 双方各被精确扣一半 weddingCost (10000 each)。
            helper.assertTrue(aBefore - eco.balanceOf(a.getUUID()) == WEDDING_COST / 2,
                    "partner A is charged exactly half the wedding cost (" + (WEDDING_COST / 2) + ")");
            helper.assertTrue(bBefore - eco.balanceOf(b.getUUID()) == WEDDING_COST / 2,
                    "partner B is charged exactly half the wedding cost (" + (WEDDING_COST / 2) + ")");
            helper.assertTrue(eco.chargedTotal(a.getUUID()) == WEDDING_COST / 2,
                    "exactly one half-cost charge is recorded against A (no double charge)");
            helper.assertTrue(eco.chargedTotal(b.getUUID()) == WEDDING_COST / 2,
                    "exactly one half-cost charge is recorded against B (no double charge)");

            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 2. 成本不足: 典礼失败不留半成品 (无关系/戒指仍订婚/双方净额 0; 付得起一方被退回)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void weddingRollsBackWhenOnePartnerCannotPay(GameTestHelper helper) {
        LedgerEconomy eco = new LedgerEconomy();
        IEconomyService prev = swapEconomy(eco);
        try {
            ServerLevel overworld = helper.getLevel().getServer().overworld();
            ServerPlayer a = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            ServerPlayer b = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

            giveEngagementRing(a);
            giveEngagementRing(b);
            // A 付得起半价 (50000); B 付不起半价 (只有 9999 < 10000) -> 典礼失败, A 已扣须退回。
            eco.setBalance(a.getUUID(), 50_000L);
            eco.setBalance(b.getUUID(), WEDDING_COST / 2 - 1);
            long aBefore = eco.balanceOf(a.getUUID());
            long bBefore = eco.balanceOf(b.getUUID());

            MarriageEngine engine = new MarriageEngine(overworld);
            MarriageEngine.WeddingResult result = engine.wed(a, b, WEDDING_COST, null);

            helper.assertTrue(!result.success(), "wedding fails when one partner cannot pay half");
            helper.assertTrue(result.reason() == MarriageEngine.Reason.INSUFFICIENT_FUNDS,
                    "the failure reason is INSUFFICIENT_FUNDS");

            // 无 MarriageState 登记 (双方仍未婚)。
            MarriageRegistry registry = MarriageRegistry.get(overworld);
            helper.assertTrue(registry.forPlayer(a.getUUID()) == null,
                    "no marriage is registered for A after a failed ceremony");
            helper.assertTrue(registry.forPlayer(b.getUUID()) == null,
                    "no marriage is registered for B after a failed ceremony");

            // 双方 capability 指针仍未婚 (NO_MARRIAGE / spouse null)。
            IMiningPlayerData dataA = MiningCapabilities.get(a).orElseThrow();
            IMiningPlayerData dataB = MiningCapabilities.get(b).orElseThrow();
            helper.assertTrue(dataA.marriageId() == IMiningPlayerData.NO_MARRIAGE && dataA.spouseUUID() == null,
                    "A's capability marriage pointer is untouched on failure");
            helper.assertTrue(dataB.marriageId() == IMiningPlayerData.NO_MARRIAGE && dataB.spouseUUID() == null,
                    "B's capability marriage pointer is untouched on failure");

            // 戒指仍订婚态 (未被换成结婚戒指)。
            helper.assertTrue(firstRing(a).getItem() == ModItems.ENGAGEMENT_RING.get(),
                    "A's ring remains an engagement ring after a failed ceremony");
            helper.assertTrue(firstRing(b).getItem() == ModItems.ENGAGEMENT_RING.get(),
                    "B's ring remains an engagement ring after a failed ceremony");

            // 事务回滚: 付得起的 A 净额为 0 (扣了半价又退回), B 一分未动。
            helper.assertTrue(eco.balanceOf(a.getUUID()) == aBefore,
                    "partner A's net balance is unchanged: the half charged before B failed is refunded (transactional)");
            helper.assertTrue(eco.balanceOf(b.getUUID()) == bBefore,
                    "partner B's balance is unchanged (its charge was rejected for insufficient funds)");

            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 3. 共享背包并发同 slot 取放只出一份 (无 dupe; 主线程串行权威容器)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sharedBackpackConcurrentSameSlotYieldsOneCopy(GameTestHelper helper) {
        LedgerEconomy eco = new LedgerEconomy();
        IEconomyService prev = swapEconomy(eco);
        try {
            ServerLevel overworld = helper.getLevel().getServer().overworld();
            MarriageState state = weddedState(helper, overworld, eco);
            MarriageRegistry registry = MarriageRegistry.get(overworld);
            MarriageBackpackSessions sessions = new MarriageBackpackSessions();
            MarriageBackpackContainer container = sessions.containerFor(state, registry);

            // 在槽 0 放入一组 16 个红石 (普通材料, 白名单允许)。这是唯一一份权威内容。
            ItemStack redstone = new ItemStack(net.minecraft.world.item.Items.REDSTONE, 16);
            container.setItem(0, redstone);
            helper.assertTrue(container.getItem(0).getCount() == 16,
                    "shared backpack slot 0 holds the 16-count stack after put");

            // 模拟"双方各开同一权威容器并发取槽 0": 两个窗口操作同一个 container (主线程串行). 第一次全取走,
            // 第二次取空槽 -> 只可能出一份 (无 dupe)。
            MarriageBackpackContainer windowA = sessions.containerFor(state, registry);
            MarriageBackpackContainer windowB = sessions.containerFor(state, registry);
            helper.assertTrue(windowA == windowB && windowA == container,
                    "both open windows share the single authoritative container instance (no per-window mirror)");

            ItemStack takenA = windowA.removeItem(0, 64); // 串行第一步: 取走全部 16。
            ItemStack takenB = windowB.removeItem(0, 64); // 串行第二步: 槽已空, 取到 0。

            helper.assertTrue(takenA.getCount() == 16, "first take pulls exactly the 16 stored (no more)");
            helper.assertTrue(takenB.isEmpty(), "second take on the now-empty slot pulls nothing (no duplicate)");
            int totalOut = takenA.getCount() + takenB.getCount();
            helper.assertTrue(totalOut == 16,
                    "total items taken across both concurrent windows equals the single stored stack (16), not 32 (no dupe)");
            helper.assertTrue(container.getItem(0).isEmpty(), "authoritative slot 0 is empty after both takes");

            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 4. 内容白名单: 高级矿/绑定装备拒入, 普通材料允许
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sharedBackpackWhitelistRejectsBlockedAllowsNormal(GameTestHelper helper) {
        LedgerEconomy eco = new LedgerEconomy();
        IEconomyService prev = swapEconomy(eco);
        try {
            ServerLevel overworld = helper.getLevel().getServer().overworld();
            MarriageState state = weddedState(helper, overworld, eco);
            MarriageRegistry registry = MarriageRegistry.get(overworld);
            MarriageBackpackContainer container = new MarriageBackpackContainer(state, registry);

            // 高级矿 (钻石) 被拒。
            ItemStack diamond = new ItemStack(net.minecraft.world.item.Items.DIAMOND);
            helper.assertTrue(!container.canPlaceItem(0, diamond),
                    "shared backpack rejects diamonds (high-value ore directed laundering)");
            // 绿宝石/远古残骸/下界合金锭同拒。
            helper.assertTrue(!container.canPlaceItem(0, new ItemStack(net.minecraft.world.item.Items.EMERALD)),
                    "shared backpack rejects emeralds");
            helper.assertTrue(!container.canPlaceItem(0, new ItemStack(net.minecraft.world.item.Items.NETHERITE_INGOT)),
                    "shared backpack rejects netherite ingots");
            // 绑定装备 (带 OwnerUUID 盖章) 被拒: 造一张盖了 owner 的塔罗牌做样本 (任何 OwnerUUID 盖章物均拒)。
            ItemStack bound = new ItemStack(net.minecraft.world.item.Items.STICK);
            bound.getOrCreateTag().putUUID("OwnerUUID", java.util.UUID.randomUUID());
            helper.assertTrue(!container.canPlaceItem(0, bound),
                    "shared backpack rejects bound equipment (any OwnerUUID-stamped item)");
            // 结婚戒指本身 (带 MarriageId 盖章) 被拒。
            ItemStack weddingRing = RingItem.createWedding(ModItems.WEDDING_RING.get(),
                    java.util.UUID.randomUUID(), "h", java.util.UUID.randomUUID(), "s", 1L, 0L, null);
            helper.assertTrue(!container.canPlaceItem(0, weddingRing),
                    "shared backpack rejects wedding rings (MarriageId-stamped)");

            // 普通材料/食物允许。
            helper.assertTrue(container.canPlaceItem(0, new ItemStack(net.minecraft.world.item.Items.REDSTONE)),
                    "shared backpack allows redstone (normal material)");
            helper.assertTrue(container.canPlaceItem(0, new ItemStack(net.minecraft.world.item.Items.BREAD)),
                    "shared backpack allows bread (food)");
            helper.assertTrue(container.canPlaceItem(0, new ItemStack(net.minecraft.world.item.Items.IRON_INGOT)),
                    "shared backpack allows iron ingots (normal material, not high-value ore)");

            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 5. 传送蓄力: 受伤即取消; 伴侣移动/潜行即取消
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void teleportChannelCancelsOnHurt(GameTestHelper helper) {
        LedgerEconomy eco = new LedgerEconomy();
        IEconomyService prev = swapEconomy(eco);
        try {
            ServerLevel overworld = helper.getLevel().getServer().overworld();
            ServerPlayer a = makeMarriedPair(helper, overworld, eco).initiator;
            MarriageTeleport teleport = new MarriageTeleport();

            MarriageTeleport.StartResult start = teleport.tryStart(a, overworld);
            helper.assertTrue(start == MarriageTeleport.StartResult.STARTED,
                    "teleport channel starts when married, spouse online, same dimension, not on cooldown");
            helper.assertTrue(teleport.isChanneling(a.getUUID()), "channel is active right after start");

            // 发起方受伤 -> 蓄力取消 (战斗锁: 挨枪传不掉)。
            boolean cancelled = teleport.onHurt(a.getUUID(), overworld);
            helper.assertTrue(cancelled, "onHurt reports a channel was cancelled");
            helper.assertTrue(!teleport.isChanneling(a.getUUID()),
                    "channel is cleared after the initiator is hurt (interruptible combat lock)");

            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void teleportChannelCancelsOnPartnerMoveOrSneak(GameTestHelper helper) {
        LedgerEconomy eco = new LedgerEconomy();
        IEconomyService prev = swapEconomy(eco);
        try {
            ServerLevel overworld = helper.getLevel().getServer().overworld();
            MarriedPair pair = makeMarriedPair(helper, overworld, eco);
            MarriageTeleport teleport = new MarriageTeleport();

            // 伴侣移动 -> 取消。
            helper.assertTrue(teleport.tryStart(pair.initiator, overworld) == MarriageTeleport.StartResult.STARTED,
                    "channel starts");
            pair.partner.absMoveTo(pair.partner.getX() + 3.0D, pair.partner.getY(), pair.partner.getZ());
            teleport.tick(overworld);
            helper.assertTrue(!teleport.isChanneling(pair.initiator.getUUID()),
                    "channel is cancelled when the partner moves beyond the still threshold");

            // 复位伴侣位置, 重新蓄力, 验证潜行也取消。
            pair.partner.absMoveTo(pair.initiator.getX(), pair.initiator.getY(), pair.initiator.getZ() + 1.0D);
            helper.assertTrue(teleport.tryStart(pair.initiator, overworld) == MarriageTeleport.StartResult.STARTED,
                    "channel restarts after partner returns to rest");
            pair.partner.setShiftKeyDown(true);
            teleport.tick(overworld);
            helper.assertTrue(!teleport.isChanneling(pair.initiator.getUUID()),
                    "channel is cancelled when the partner sneaks (consent withdrawn)");
            pair.partner.setShiftKeyDown(false);

            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 6. 离婚后冷却内再婚被拒, 冷却过后放行
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void remarryBlockedWithinCooldownAllowedAfter(GameTestHelper helper) {
        LedgerEconomy eco = new LedgerEconomy();
        IEconomyService prev = swapEconomy(eco);
        try {
            ServerLevel overworld = helper.getLevel().getServer().overworld();
            MarriageRegistry registry = MarriageRegistry.get(overworld);
            MarriageHistory history = MarriageHistory.get(overworld);

            ServerPlayer a = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            ServerPlayer b = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            eco.setBalance(a.getUUID(), 1_000_000L);
            eco.setBalance(b.getUUID(), 1_000_000L);

            // 结婚 -> 离婚 (a 发起)。
            wedPair(overworld, a, b);
            MarriageDivorce divorce = new MarriageDivorce(overworld, new MarriageBackpackSessions());
            MarriageDivorce.Result divorceResult = divorce.divorce(a, MARRIAGE_DIVORCE_COST);
            helper.assertTrue(divorceResult == MarriageDivorce.Result.OK, "divorce succeeds for a married initiator");
            helper.assertTrue(registry.forPlayer(a.getUUID()) == null && registry.forPlayer(b.getUUID()) == null,
                    "both are unmarried after divorce");
            helper.assertTrue(history.divorceCount(a.getUUID()) == 1 && history.divorceCount(b.getUUID()) == 1,
                    "both partners' divorce counts incremented");

            // 冷却内再婚被拒 (再给订婚戒指, 双方均未婚但处于冷却)。
            giveEngagementRing(a);
            giveEngagementRing(b);
            long now = overworld.getGameTime();
            helper.assertTrue(history.isOnRemarryCooldown(a.getUUID(), now),
                    "a is on remarry cooldown immediately after divorce");
            MarriageEngine engine = new MarriageEngine(overworld);
            MarriageEngine.WeddingResult blocked = engine.wed(a, b, 20_000L, null);
            helper.assertTrue(!blocked.success() && blocked.reason() == MarriageEngine.Reason.REMARRY_COOLDOWN,
                    "remarriage within the cooldown is rejected with REMARRY_COOLDOWN");

            // 冷却过后放行: 推进 gameTime 越过冷却截止 (用历史的剩余冷却 tick 作偏移基准断言放行)。
            long remaining = history.remarryCooldownRemaining(a.getUUID(), now);
            helper.assertTrue(remaining > 0L, "cooldown has a positive remaining duration");
            long afterCooldown = now + remaining + 1L;
            helper.assertTrue(!history.isOnRemarryCooldown(a.getUUID(), afterCooldown),
                    "a is off cooldown once the remaining duration elapses");
            // 直接断言闸判定放行 (engine.wed 读 overworld.getGameTime 无法在测试快进, 故按 history 闸的纯函数断言)。
            helper.assertTrue(!history.isOnRemarryCooldown(b.getUUID(), afterCooldown),
                    "b is also off cooldown after the same elapsed duration");

            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 7. 同一对 UUID 离婚再婚不重发同一里程碑
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void samePairDoesNotRegrantMilestoneAcrossRemarriage(GameTestHelper helper) {
        LedgerEconomy eco = new LedgerEconomy();
        IEconomyService prev = swapEconomy(eco);
        try {
            ServerLevel overworld = helper.getLevel().getServer().overworld();
            MarriageHistory history = MarriageHistory.get(overworld);
            UUID a = java.util.UUID.randomUUID();
            UUID b = java.util.UUID.randomUUID();

            // 首次结婚: 该对首次领里程碑 -> true。
            boolean first = history.claimPairMilestone(a, b, MarriageEngine.MILESTONE_FIRST_MARRIAGE);
            helper.assertTrue(first, "first marriage of the pair claims the milestone (first time)");
            helper.assertTrue(history.hasPairClaimed(a, b, MarriageEngine.MILESTONE_FIRST_MARRIAGE),
                    "the pair is recorded as having claimed the milestone");

            // 离婚后再婚 (换 marriageId): 同一对再领同一里程碑 -> false (不重发)。键规范化, a/b 顺序无关。
            boolean again = history.claimPairMilestone(b, a, MarriageEngine.MILESTONE_FIRST_MARRIAGE);
            helper.assertTrue(!again,
                    "remarrying the same UUID pair does NOT re-grant the same milestone (dedup by normalized pair key)");

            // 不同对则可领 (确保去重是按对而非全局)。
            UUID c = java.util.UUID.randomUUID();
            helper.assertTrue(history.claimPairMilestone(a, c, MarriageEngine.MILESTONE_FIRST_MARRIAGE),
                    "a different pair can still claim the milestone (dedup is per-pair, not global)");

            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // helpers
    // ============================================================

    /** 离婚成本 (测试固定值, 不读 config)。 */
    private static final long MARRIAGE_DIVORCE_COST = 10_000L;

    /** 已婚的一对 (发起方 + 伴侣); 二者在测试 overworld 内, 已 wed 互指。 */
    private record MarriedPair(ServerPlayer initiator, ServerPlayer partner) {
    }

    /** 造一对已结婚的玩家 (双方足额 + 各持订婚戒指 -> wed)。两人初始同坐标 (传送蓄力静止基准)。 */
    private static MarriedPair makeMarriedPair(GameTestHelper helper, ServerLevel overworld, LedgerEconomy eco) {
        ServerPlayer a = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer b = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        eco.setBalance(a.getUUID(), 1_000_000L);
        eco.setBalance(b.getUUID(), 1_000_000L);
        // 同坐标静止 (蓄力期间双方须不动; 同点令初始锚点一致)。
        b.absMoveTo(a.getX(), a.getY(), a.getZ());
        wedPair(overworld, a, b);
        return new MarriedPair(a, b);
    }

    /** 已婚关系状态 (供共享背包/白名单测试; 不关心玩家后续, 只取 MarriageState)。 */
    private static MarriageState weddedState(GameTestHelper helper, ServerLevel overworld, LedgerEconomy eco) {
        MarriedPair pair = makeMarriedPair(helper, overworld, eco);
        return MarriageRegistry.get(overworld).forPlayer(pair.initiator.getUUID());
    }

    /** 给双方订婚戒指并 wed (经引擎; 双方须足额)。 */
    private static void wedPair(ServerLevel overworld, ServerPlayer a, ServerPlayer b) {
        giveEngagementRing(a);
        giveEngagementRing(b);
        MarriageEngine.WeddingResult result = new MarriageEngine(overworld).wed(a, b, 20_000L, null);
        if (!result.success()) {
            throw new IllegalStateException("test setup wedding failed: " + result.reason());
        }
    }

    private static void giveEngagementRing(ServerPlayer player) {
        player.getInventory().clearContent();
        player.getInventory().add(RingItem.createEngagement(ModItems.ENGAGEMENT_RING.get()));
    }

    /** 玩家主背包第一枚戒指 (订婚或结婚); 无则 EMPTY。 */
    private static ItemStack firstRing(ServerPlayer player) {
        for (ItemStack s : player.getInventory().items) {
            if (s.getItem() instanceof RingItem) {
                return s;
            }
        }
        return ItemStack.EMPTY;
    }

    private static IEconomyService swapEconomy(IEconomyService fake) {
        IEconomyService prev = EconomyServices.isRegistered() ? EconomyServices.economyService() : null;
        EconomyServices.registerEconomyService(fake);
        return prev;
    }

    private static void restoreEconomy(IEconomyService prev) {
        if (prev != null) {
            EconomyServices.registerEconomyService(prev);
        } else {
            EconomyServices.reset();
        }
    }

    /**
     * 记账 mock 经济门面: 仅实现典礼用到的 tryCharge (余额足才扣并记账, 不足返 false 不扣) + grant (退款/入账)。
     * 其余 IEconomyService 方法本测试不触, 抛 UnsupportedOperationException (暴露误用, 不静默返默认值)。
     * chargedTotal 累计净扣费 (扣 +, 退 -), 用于断言"恰扣一半且无重复扣"。
     */
    private static final class LedgerEconomy implements IEconomyService {

        private final Map<UUID, Long> balances = new HashMap<>();
        private final Map<UUID, Long> charged = new HashMap<>();

        void setBalance(UUID id, long amount) {
            balances.put(id, amount);
        }

        long balanceOf(UUID id) {
            return balances.getOrDefault(id, 0L);
        }

        /** 该玩家被净扣费总额 (tryCharge 累加, grant 回退抵扣); 双方各应恰为半价。 */
        long chargedTotal(UUID id) {
            return charged.getOrDefault(id, 0L);
        }

        @Override
        public boolean tryCharge(ServerPlayer player, Currency currency, long amount) {
            if (amount <= 0) {
                throw new EconomyException(EconomyException.Reason.ILLEGAL_AMOUNT, "amount must be > 0: " + amount);
            }
            UUID id = player.getUUID();
            long bal = balanceOf(id);
            if (bal < amount) {
                return false;
            }
            balances.put(id, bal - amount);
            charged.merge(id, amount, Long::sum);
            return true;
        }

        @Override
        public void grant(ServerPlayer player, Currency currency, long amount) {
            if (amount <= 0) {
                throw new EconomyException(EconomyException.Reason.ILLEGAL_AMOUNT, "amount must be > 0: " + amount);
            }
            UUID id = player.getUUID();
            balances.merge(id, amount, Long::sum);
            // 退款抵扣净扣费 (典礼回滚把 A 的半价退回 -> A 净扣费回 0)。
            charged.merge(id, -amount, Long::sum);
        }

        @Override
        public long creditBalance(ServerPlayer player) {
            return balanceOf(player.getUUID());
        }

        @Override
        public long heartstoneBalance(ServerPlayer player) {
            throw new UnsupportedOperationException("mock: heartstoneBalance not used by marriage tests");
        }

        @Override
        public boolean tryChargeDaily(ServerPlayer player, Currency currency, long amount, String dailyKey, long dailyCap) {
            throw new UnsupportedOperationException("mock: tryChargeDaily not used by marriage tests");
        }

        @Override
        public long settleOreSale(ServerPlayer player, com.miningdim.economy.EconomyConstants.HighValueOre ore,
                                  int countSoFar, double basePrice) {
            throw new UnsupportedOperationException("mock: settleOreSale not used by marriage tests");
        }

        @Override
        public int recordMinedOreDrops(ServerPlayer player, Block block, int producedCount) {
            throw new UnsupportedOperationException("mock: recordMinedOreDrops not used by marriage tests");
        }

        @Override
        public long grantDaily(ServerPlayer player, long rawCredit, String faucetKey, long dailyCap) {
            throw new UnsupportedOperationException("mock: grantDaily not used by marriage tests");
        }

        @Override
        public long grantAzureDaily(ServerPlayer player, long amount, long dailyCap) {
            throw new UnsupportedOperationException("mock: grantAzureDaily not used by marriage tests");
        }

        @Override
        public boolean isAfkFrozen(ServerPlayer player) {
            return false;
        }
    }
}

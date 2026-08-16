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
import net.minecraft.world.item.Item;
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

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void teleportChannelCancelsWhenMarriageDissolvedMidCharge(GameTestHelper helper) {
        LedgerEconomy eco = new LedgerEconomy();
        IEconomyService prev = swapEconomy(eco);
        try {
            ServerLevel overworld = helper.getLevel().getServer().overworld();
            MarriedPair pair = makeMarriedPair(helper, overworld, eco);
            MarriageTeleport teleport = new MarriageTeleport();

            helper.assertTrue(teleport.tryStart(pair.initiator, overworld) == MarriageTeleport.StartResult.STARTED,
                    "channel starts for a married pair");
            helper.assertTrue(teleport.isChanneling(pair.initiator.getUUID()), "channel active right after start");

            // 蓄力中离婚: 清双方 capability 婚姻指针 (等价 /marriage divorce 对 marriageId 的清除)。双方未移动/未潜行/未受伤,
            // 仅婚姻解除 -> tick 必须取消 (否则已离婚玩家仍被传送到前配偶身边)。
            clearMarriagePointer(pair.initiator);
            clearMarriagePointer(pair.partner);
            teleport.tick(overworld);
            helper.assertTrue(!teleport.isChanneling(pair.initiator.getUUID()),
                    "channel is cancelled when the marriage is dissolved mid-charge (no teleport to ex-spouse)");
            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    /** 清玩家 capability 婚姻指针 (等价离婚): marriageId=NO_MARRIAGE + spouseUUID=null。 */
    private static void clearMarriagePointer(ServerPlayer player) {
        MiningCapabilities.get(player).ifPresent(d -> {
            d.setMarriageId(IMiningPlayerData.NO_MARRIAGE);
            d.setSpouseUUID(null);
        });
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

            // 结婚 -> 离婚 (a 发起): file 开公示期, finalizeMatured 在公示期到期后使其生效 (escrow 三段式,
            // MarriageDivorce 已无旧的即时 divorce() 方法)。
            wedPair(overworld, a, b);
            MarriageDivorce divorce = new MarriageDivorce(overworld, new MarriageBackpackSessions());
            long filedTick = overworld.getGameTime();
            MarriageDivorce.Filing filing = divorce.file(a, MARRIAGE_DIVORCE_COST);
            helper.assertTrue(filing.result() == MarriageDivorce.Result.OK,
                    "divorce filing succeeds for a married initiator");
            int settledCount = divorce.finalizeMatured(filedTick + MarriageTuning.divorceEscrowTicks());
            helper.assertTrue(settledCount == 1,
                    "finalizeMatured settles exactly the filed divorce once its escrow window matures");
            helper.assertTrue(registry.forPlayer(a.getUUID()) == null && registry.forPlayer(b.getUUID()) == null,
                    "both are unmarried after the divorce escrow matures and settles");
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
    // 7. 离婚 escrow (公示期) 三段式: 提交不即时解除 / 撤回全额退费 / 配偶提前确认
    // ============================================================

    /**
     * 公示期内不解除: 提交后关系照旧、离婚次数未记、发起方恰被扣一次成本; 重复提交幂等 (不二次扣费);
     * 公示期到期前 finalizeMatured 不结算, 到期当刻结算恰一条。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void divorceFilingOpensEscrowWithoutDissolvingUntilMatured(GameTestHelper helper) {
        LedgerEconomy eco = new LedgerEconomy();
        IEconomyService prev = swapEconomy(eco);
        try {
            ServerLevel overworld = helper.getLevel().getServer().overworld();
            MarriageRegistry registry = MarriageRegistry.get(overworld);
            MarriageHistory history = MarriageHistory.get(overworld);
            MarriedPair pair = makeMarriedPair(helper, overworld, eco);
            ServerPlayer a = pair.initiator;
            ServerPlayer b = pair.partner;
            MarriageDivorce divorce = new MarriageDivorce(overworld, new MarriageBackpackSessions());

            long balanceBeforeFiling = eco.balanceOf(a.getUUID());
            long filedTick = overworld.getGameTime();
            MarriageDivorce.Filing filing = divorce.file(a, MARRIAGE_DIVORCE_COST);
            helper.assertTrue(filing.result() == MarriageDivorce.Result.OK, "first filing succeeds");
            helper.assertTrue(!filing.alreadyPending(), "first filing is not a repeat submission");

            helper.assertTrue(registry.forPlayer(a.getUUID()) != null,
                    "the marriage is still registered during the escrow window");
            helper.assertTrue(history.divorceCount(a.getUUID()) == 0,
                    "divorce is not counted until escrow matures and settles");
            long balanceAfterFiling = eco.balanceOf(a.getUUID());
            helper.assertTrue(balanceBeforeFiling - balanceAfterFiling == MARRIAGE_DIVORCE_COST,
                    "the initiator is charged exactly the divorce cost once, not twice");

            MarriageState state = registry.forPlayer(a.getUUID());
            helper.assertTrue(state.hasPendingDivorce(),
                    "hasPendingDivorce is true immediately after filing");

            // 重复提交 (幂等回执, 不二次扣费)。
            MarriageDivorce.Filing again = divorce.file(a, MARRIAGE_DIVORCE_COST);
            helper.assertTrue(again.alreadyPending(), "a second filing while pending reports alreadyPending");
            helper.assertTrue(eco.balanceOf(a.getUUID()) == balanceAfterFiling,
                    "the repeated filing does not charge a second time");

            long escrowTicks = MarriageTuning.divorceEscrowTicks();
            int tooEarly = divorce.finalizeMatured(filedTick + escrowTicks - 1L);
            helper.assertTrue(tooEarly == 0, "finalizeMatured settles nothing one tick before maturity");
            helper.assertTrue(registry.forPlayer(a.getUUID()) != null, "still married one tick before maturity");

            int settled = divorce.finalizeMatured(filedTick + escrowTicks);
            helper.assertTrue(settled == 1, "finalizeMatured settles exactly the one matured divorce");
            helper.assertTrue(registry.forPlayer(a.getUUID()) == null && registry.forPlayer(b.getUUID()) == null,
                    "both partners are unmarried once escrow matures");
            helper.assertTrue(history.divorceCount(a.getUUID()) == 1 && history.divorceCount(b.getUUID()) == 1,
                    "both partners' divorce counts increment exactly once, at maturity");

            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    /**
     * 撤回全额退费且关系存续: cancel 精确退回提交时扣的成本, hasPendingDivorce 归假, 关系继续存在
     * (即便推进到远超原公示期的 tick 也不结算)。非发起方 (配偶) 无法撤回, 且不改任何状态。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void divorceCancelRefundsFullyAndKeepsMarriageIntact(GameTestHelper helper) {
        LedgerEconomy eco = new LedgerEconomy();
        IEconomyService prev = swapEconomy(eco);
        try {
            ServerLevel overworld = helper.getLevel().getServer().overworld();
            MarriageRegistry registry = MarriageRegistry.get(overworld);
            MarriedPair pair = makeMarriedPair(helper, overworld, eco);
            ServerPlayer a = pair.initiator;
            ServerPlayer b = pair.partner;
            MarriageDivorce divorce = new MarriageDivorce(overworld, new MarriageBackpackSessions());

            long balanceBeforeFiling = eco.balanceOf(a.getUUID());
            long filedTick = overworld.getGameTime();
            MarriageDivorce.Filing filing = divorce.file(a, MARRIAGE_DIVORCE_COST);
            helper.assertTrue(filing.result() == MarriageDivorce.Result.OK, "filing succeeds");

            MarriageDivorce.PendingAction cancelled = divorce.cancel(a);
            helper.assertTrue(cancelled == MarriageDivorce.PendingAction.OK,
                    "the initiator can cancel their own pending divorce");
            helper.assertTrue(eco.balanceOf(a.getUUID()) == balanceBeforeFiling,
                    "cancelling refunds the divorce cost exactly, back to the pre-filing balance");
            MarriageState state = registry.forPlayer(a.getUUID());
            helper.assertTrue(state != null && !state.hasPendingDivorce(),
                    "the marriage has no pending divorce after cancellation");

            // 撤回后关系存续: 即使推进到远超原公示期的 tick, finalizeMatured 也无 pending 可结。
            long escrowTicks = MarriageTuning.divorceEscrowTicks();
            int settledAfterCancel = divorce.finalizeMatured(filedTick + escrowTicks * 10L);
            helper.assertTrue(settledAfterCancel == 0,
                    "finalizeMatured settles nothing once the pending divorce was cancelled");
            helper.assertTrue(registry.forPlayer(a.getUUID()) != null && registry.forPlayer(b.getUUID()) != null,
                    "the marriage remains intact well past the original escrow window");

            // 配偶 (非发起方) 无法撤回, 且不退钱不改状态。
            MarriageDivorce.Filing secondFiling = divorce.file(a, MARRIAGE_DIVORCE_COST);
            helper.assertTrue(secondFiling.result() == MarriageDivorce.Result.OK, "the second filing succeeds");
            long balanceAfterSecondFiling = eco.balanceOf(a.getUUID());
            MarriageDivorce.PendingAction spouseCancelled = divorce.cancel(b);
            helper.assertTrue(spouseCancelled == MarriageDivorce.PendingAction.NOT_INITIATOR,
                    "the spouse cannot cancel a divorce filed by the initiator");
            helper.assertTrue(eco.balanceOf(a.getUUID()) == balanceAfterSecondFiling,
                    "a rejected cancel by the non-initiator does not refund anything");
            helper.assertTrue(registry.forPlayer(a.getUUID()).hasPendingDivorce(),
                    "the pending divorce is untouched by the rejected cancel");

            // 测试收尾: 上面故意留了一条 pending 离婚才能验证"配偶无法撤回"; 但 MarriageRegistry/MarriageHistory
            // 是挂在 overworld 上的全局 SavedData, 同批次其它测试对 finalizeMatured 的扫描 (registry.all()) 不按
            // marriageId 过滤 —— 不清掉这条 pending, 它会在任意后续测试推进 nowTick 时被顺带结算掉, 让那些测试的
            // "settled == 1" 断言失真为 2。
            MarriageDivorce.PendingAction cleanup = divorce.cancel(a);
            helper.assertTrue(cleanup == MarriageDivorce.PendingAction.OK,
                    "test cleanup: cancelling the lingering pending divorce succeeds");

            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    /**
     * cost=0 时撤回不抛异常且正常清 pending (回归复核: divorceCost 下界为 0 是运维合法配置, file() 对应跳过扣费,
     * 若 cancel() 的退款无 cost&gt;0 守卫, grant(0) 会在 clearPendingDivorce 之前抛 ILLEGAL_AMOUNT, 让 pending
     * 永久卡死撤不回)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void divorceCancelWithZeroCostDoesNotThrowAndClearsPending(GameTestHelper helper) {
        LedgerEconomy eco = new LedgerEconomy();
        IEconomyService prev = swapEconomy(eco);
        try {
            ServerLevel overworld = helper.getLevel().getServer().overworld();
            MarriageRegistry registry = MarriageRegistry.get(overworld);
            MarriedPair pair = makeMarriedPair(helper, overworld, eco);
            ServerPlayer a = pair.initiator;
            MarriageDivorce divorce = new MarriageDivorce(overworld, new MarriageBackpackSessions());

            long balanceBeforeFiling = eco.balanceOf(a.getUUID());
            MarriageDivorce.Filing filing = divorce.file(a, 0L);
            helper.assertTrue(filing.result() == MarriageDivorce.Result.OK,
                    "filing with zero divorceCost still succeeds (config bounds explicitly allow 0)");
            helper.assertTrue(eco.balanceOf(a.getUUID()) == balanceBeforeFiling,
                    "zero cost charges nothing at filing time");

            MarriageDivorce.PendingAction cancelled = divorce.cancel(a);
            helper.assertTrue(cancelled == MarriageDivorce.PendingAction.OK,
                    "cancel must succeed (not throw ILLEGAL_AMOUNT) when the escrowed cost is zero, got " + cancelled);
            helper.assertTrue(eco.balanceOf(a.getUUID()) == balanceBeforeFiling,
                    "cancelling a zero-cost filing leaves the balance untouched (nothing to refund)");
            MarriageState state = registry.forPlayer(a.getUUID());
            helper.assertTrue(state != null && !state.hasPendingDivorce(),
                    "pending divorce is cleared after cancel even at zero cost (an unguarded grant(0) would throw "
                            + "before clearPendingDivorce runs, wedging pending forever)");

            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    /**
     * 配偶提前确认使离婚当场生效 (语义是"配偶同意提前生效", 不是发起方能自己确认自己的提交)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void divorceConfirmBySpouseDissolvesImmediately(GameTestHelper helper) {
        LedgerEconomy eco = new LedgerEconomy();
        IEconomyService prev = swapEconomy(eco);
        try {
            ServerLevel overworld = helper.getLevel().getServer().overworld();
            MarriageRegistry registry = MarriageRegistry.get(overworld);
            MarriedPair pair = makeMarriedPair(helper, overworld, eco);
            ServerPlayer a = pair.initiator;
            ServerPlayer b = pair.partner;
            MarriageDivorce divorce = new MarriageDivorce(overworld, new MarriageBackpackSessions());

            MarriageDivorce.Filing filing = divorce.file(a, MARRIAGE_DIVORCE_COST);
            helper.assertTrue(filing.result() == MarriageDivorce.Result.OK, "filing succeeds");

            MarriageDivorce.PendingAction selfConfirm = divorce.confirm(a);
            helper.assertTrue(selfConfirm == MarriageDivorce.PendingAction.NOT_SPOUSE,
                    "the initiator confirming their own filing is rejected as NOT_SPOUSE");
            helper.assertTrue(registry.forPlayer(a.getUUID()) != null,
                    "the marriage is untouched by the rejected self-confirm");

            MarriageDivorce.PendingAction spouseConfirm = divorce.confirm(b);
            helper.assertTrue(spouseConfirm == MarriageDivorce.PendingAction.OK,
                    "the spouse confirming the pending divorce succeeds");
            helper.assertTrue(registry.forPlayer(a.getUUID()) == null && registry.forPlayer(b.getUUID()) == null,
                    "the marriage dissolves immediately once the spouse confirms, before escrow matures");

            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 8. 离婚结算: 共享背包按槽归属清算 (含无归属槽的确定性平分) / 待领取物下发 / 公示期冻结共享背包
    // ============================================================

    /**
     * 清算按槽归属 ("谁放入谁取回"): A/B 各自经真菜单路径 (DepositAccountingSlot.set) 认领不同槽, 离婚生效后
     * 各自的待领取清算表只含自己放的那件, 绝不含对方放的那件 (删掉归属逻辑退回"全部归发起方"这条必须挂)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void divorceSettlementReturnsSharedBackpackContentsToTheirDepositor(GameTestHelper helper) {
        LedgerEconomy eco = new LedgerEconomy();
        IEconomyService prev = swapEconomy(eco);
        try {
            ServerLevel overworld = helper.getLevel().getServer().overworld();
            MarriageRegistry registry = MarriageRegistry.get(overworld);
            MarriedPair pair = makeMarriedPair(helper, overworld, eco);
            ServerPlayer a = pair.initiator;
            ServerPlayer b = pair.partner;
            MarriageState state = registry.forPlayer(a.getUUID());
            MarriageBackpackSessions sessions = new MarriageBackpackSessions();

            ItemStack ironA = new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 3);
            ItemStack goldB = new ItemStack(net.minecraft.world.item.Items.GOLD_INGOT, 5);
            net.minecraft.world.inventory.AbstractContainerMenu menuA =
                    new MarriageBackpackMenu.Provider(state, registry, sessions, overworld)
                            .createMenu(1, a.getInventory(), a);
            menuA.getSlot(0).set(ironA);
            net.minecraft.world.inventory.AbstractContainerMenu menuB =
                    new MarriageBackpackMenu.Provider(state, registry, sessions, overworld)
                            .createMenu(2, b.getInventory(), b);
            menuB.getSlot(1).set(goldB);

            helper.assertTrue(a.getUUID().equals(state.depositorOf(0)), "slot 0 is claimed by A after A places into it");
            helper.assertTrue(b.getUUID().equals(state.depositorOf(1)), "slot 1 is claimed by B after B places into it");

            MarriageDivorce divorce = new MarriageDivorce(overworld, sessions);
            long filedTick = overworld.getGameTime();
            MarriageDivorce.Filing filing = divorce.file(a, MARRIAGE_DIVORCE_COST);
            helper.assertTrue(filing.result() == MarriageDivorce.Result.OK, "filing succeeds");
            int settled = divorce.finalizeMatured(filedTick + MarriageTuning.divorceEscrowTicks());
            helper.assertTrue(settled == 1, "the divorce settles once escrow matures");

            // a/b 是注册在 playerList 里的在线 mock 玩家, MarriageDivorce.settle -> finalizeParty -> deliverClaims
            // 在结算这一刻就把待领取物直接下发进了背包 (在线方立即下发, 见 MarriageDivorce 类注释) —— 结算已把
            // MarriageHistory 的待领取表清空, 断言要看的是玩家背包而不是那张已经排空的表。
            int ironInA = countItemInInventory(a, net.minecraft.world.item.Items.IRON_INGOT);
            int goldInB = countItemInInventory(b, net.minecraft.world.item.Items.GOLD_INGOT);
            int goldInA = countItemInInventory(a, net.minecraft.world.item.Items.GOLD_INGOT);
            int ironInB = countItemInInventory(b, net.minecraft.world.item.Items.IRON_INGOT);
            helper.assertTrue(ironInA == 3,
                    "A receives exactly the 3 iron ingots A deposited, got " + ironInA);
            helper.assertTrue(goldInB == 5,
                    "B receives exactly the 5 gold ingots B deposited, got " + goldInB);
            helper.assertTrue(goldInA == 0,
                    "A must not receive the item B deposited "
                            + "(attribution by depositor, not a blanket grant to the initiator)");
            helper.assertTrue(ironInB == 0,
                    "B must not receive the item A deposited");

            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    /**
     * Shift 快移把同种物品并入配偶已认领的槽必须被拒绝 (回归复核: vanilla {@code moveItemStackTo} 的合并阶段
     * 只判 {@code isSameItemSameTags}, 不经 mayPlace/set, 是槽级归属记账唯一漏记的路径)。A 先放 16 个铁锭认领
     * slot 0; B 背包 48 个同种铁锭走真菜单 quickMoveStack: 修复前会并入 slot 0 (64), 归属仍判给 A; 修复后
     * slot 0 数量/归属都不变, B 的 48 个必须落进 B 新认领的空槽 —— 删掉归属闸后本用例必挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void quickMoveNeverMergesIntoSpousesClaimedSlot(GameTestHelper helper) {
        LedgerEconomy eco = new LedgerEconomy();
        IEconomyService prev = swapEconomy(eco);
        try {
            ServerLevel overworld = helper.getLevel().getServer().overworld();
            MarriageRegistry registry = MarriageRegistry.get(overworld);
            MarriedPair pair = makeMarriedPair(helper, overworld, eco);
            ServerPlayer a = pair.initiator;
            ServerPlayer b = pair.partner;
            MarriageState state = registry.forPlayer(a.getUUID());
            MarriageBackpackSessions sessions = new MarriageBackpackSessions();

            net.minecraft.world.inventory.AbstractContainerMenu menuA =
                    new MarriageBackpackMenu.Provider(state, registry, sessions, overworld)
                            .createMenu(1, a.getInventory(), a);
            menuA.getSlot(0).set(new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 16));
            helper.assertTrue(a.getUUID().equals(state.depositorOf(0)), "slot 0 is claimed by A after A places into it");

            MarriageBackpackMenu menuB = (MarriageBackpackMenu)
                    new MarriageBackpackMenu.Provider(state, registry, sessions, overworld)
                            .createMenu(2, b.getInventory(), b);
            b.getInventory().setItem(9, new ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 48));
            int playerStart = menuB.containerSlotCount();
            int playerIronMenuSlot = -1;
            for (int i = playerStart; i < menuB.slots.size(); i++) {
                if (menuB.slots.get(i).getItem().is(net.minecraft.world.item.Items.IRON_INGOT)) {
                    playerIronMenuSlot = i;
                    break;
                }
            }
            helper.assertTrue(playerIronMenuSlot >= 0, "B's iron ingots are visible in B's menu");

            menuB.quickMoveStack(b, playerIronMenuSlot);

            helper.assertTrue(state.sharedInv().get(0).getCount() == 16,
                    "A's already-claimed slot 0 must not absorb B's shift-clicked ingots, got "
                            + state.sharedInv().get(0).getCount());
            helper.assertTrue(a.getUUID().equals(state.depositorOf(0)),
                    "slot 0's depositor stays A after B's shift-click, not overwritten or left ambiguous");

            int bClaimedSlot = -1;
            for (int slot = 0; slot < state.sharedInv().size(); slot++) {
                if (b.getUUID().equals(state.depositorOf(slot))) {
                    bClaimedSlot = slot;
                    break;
                }
            }
            helper.assertTrue(bClaimedSlot >= 0 && bClaimedSlot != 0,
                    "B's shift-clicked ingots land in a slot claimed by B, not silently merged into A's slot 0");
            helper.assertTrue(state.sharedInv().get(bClaimedSlot).getCount() == 48,
                    "B's own claimed slot holds exactly the 48 ingots B shift-clicked in, got "
                            + state.sharedInv().get(bClaimedSlot).getCount());

            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    /**
     * 无归属槽确定性平分: 绕过菜单直接写共享容器 (模拟旧存档/非菜单写入路径, depositorOf 保持 null), 偶数槽归
     * partnerA, 奇数槽归 partnerB (spec 第六章闸 3: 按槽号奇偶而非全给发起方, 防"离婚资产抢劫")。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void divorceSettlementSplitsUnattributedSlotsDeterministicallyByParity(GameTestHelper helper) {
        LedgerEconomy eco = new LedgerEconomy();
        IEconomyService prev = swapEconomy(eco);
        try {
            ServerLevel overworld = helper.getLevel().getServer().overworld();
            MarriageRegistry registry = MarriageRegistry.get(overworld);
            MarriedPair pair = makeMarriedPair(helper, overworld, eco);
            ServerPlayer a = pair.initiator;
            ServerPlayer b = pair.partner;
            MarriageState state = registry.forPlayer(a.getUUID());

            ItemStack evenSlotStack = new ItemStack(net.minecraft.world.item.Items.REDSTONE, 4);
            ItemStack oddSlotStack = new ItemStack(net.minecraft.world.item.Items.LAPIS_LAZULI, 6);
            state.sharedInv().set(2, evenSlotStack);
            state.sharedInv().set(3, oddSlotStack);
            helper.assertTrue(state.depositorOf(2) == null && state.depositorOf(3) == null,
                    "direct writes to sharedInv leave no depositor record (legacy-save simulation)");

            MarriageDivorce divorce = new MarriageDivorce(overworld, new MarriageBackpackSessions());
            long filedTick = overworld.getGameTime();
            divorce.file(a, MARRIAGE_DIVORCE_COST);
            int settled = divorce.finalizeMatured(filedTick + MarriageTuning.divorceEscrowTicks());
            helper.assertTrue(settled == 1, "the divorce settles once escrow matures");

            // 同上一条测试: a/b 在线, 结算已把待领取物直接下发进背包, 断言看背包而不是已排空的 history 表。
            int redstoneInA = countItemInInventory(a, net.minecraft.world.item.Items.REDSTONE);
            int lapisInB = countItemInInventory(b, net.minecraft.world.item.Items.LAPIS_LAZULI);
            int lapisInA = countItemInInventory(a, net.minecraft.world.item.Items.LAPIS_LAZULI);
            int redstoneInB = countItemInInventory(b, net.minecraft.world.item.Items.REDSTONE);
            helper.assertTrue(redstoneInA == 4,
                    "the even slot lands in partnerA's (A's) inventory when unattributed, got " + redstoneInA);
            helper.assertTrue(lapisInB == 6,
                    "the odd slot lands in partnerB's (B's) inventory when unattributed, got " + lapisInB);
            helper.assertTrue(lapisInA == 0, "A does not receive the odd-slot item");
            helper.assertTrue(redstoneInB == 0, "B does not receive the even-slot item");

            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    /**
     * 待领取物下发: deliverClaims 把队列里的物品精确件数/数量下发到玩家背包, 并清空队列; 空队列时再调不重复发放。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void deliverClaimsGrantsExactItemsThenDrainsTheQueue(GameTestHelper helper) {
        LedgerEconomy eco = new LedgerEconomy();
        IEconomyService prev = swapEconomy(eco);
        try {
            ServerLevel overworld = helper.getLevel().getServer().overworld();
            MarriageHistory history = MarriageHistory.get(overworld);
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            player.getInventory().clearContent();

            history.queueSettlementClaim(player.getUUID(),
                    new ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE, 1));
            history.queueSettlementClaim(player.getUUID(), new ItemStack(net.minecraft.world.item.Items.BREAD, 7));
            helper.assertTrue(history.settlementClaimCount(player.getUUID()) == 2,
                    "two claims are queued before delivery");

            MarriageDivorce.deliverClaims(player);

            int pickaxeCount = 0;
            int breadCount = 0;
            for (ItemStack stack : player.getInventory().items) {
                if (stack.getItem() == net.minecraft.world.item.Items.DIAMOND_PICKAXE) {
                    pickaxeCount += stack.getCount();
                }
                if (stack.getItem() == net.minecraft.world.item.Items.BREAD) {
                    breadCount += stack.getCount();
                }
            }
            helper.assertTrue(pickaxeCount == 1,
                    "the delivered pickaxe lands in the player's inventory with exact count 1, got " + pickaxeCount);
            helper.assertTrue(breadCount == 7,
                    "the delivered bread lands in the player's inventory with exact count 7, got " + breadCount);
            helper.assertTrue(history.settlementClaimCount(player.getUUID()) == 0,
                    "the pending claim queue is drained after delivery");

            // 再次调用: 队列已空, 不重复下发。
            MarriageDivorce.deliverClaims(player);
            int pickaxeCountAfterSecondCall = 0;
            for (ItemStack stack : player.getInventory().items) {
                if (stack.getItem() == net.minecraft.world.item.Items.DIAMOND_PICKAXE) {
                    pickaxeCountAfterSecondCall += stack.getCount();
                }
            }
            helper.assertTrue(pickaxeCountAfterSecondCall == 1,
                    "calling deliverClaims again on an empty queue does not duplicate items, got "
                            + pickaxeCountAfterSecondCall);

            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    /**
     * 公示期冻结共享背包: 提交离婚后 canPlaceItem 对任何 (含白名单允许的) 物品一律拒绝; 撤回后恢复放行。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sharedBackpackFreezesDuringDivorceEscrowAndThawsOnCancel(GameTestHelper helper) {
        LedgerEconomy eco = new LedgerEconomy();
        IEconomyService prev = swapEconomy(eco);
        try {
            ServerLevel overworld = helper.getLevel().getServer().overworld();
            MarriageRegistry registry = MarriageRegistry.get(overworld);
            MarriedPair pair = makeMarriedPair(helper, overworld, eco);
            ServerPlayer a = pair.initiator;
            MarriageState state = registry.forPlayer(a.getUUID());
            MarriageBackpackContainer container = new MarriageBackpackContainer(state, registry);

            ItemStack redstone = new ItemStack(net.minecraft.world.item.Items.REDSTONE);
            helper.assertTrue(container.canPlaceItem(0, redstone),
                    "the shared backpack accepts normal materials before any divorce is filed");

            MarriageDivorce divorce = new MarriageDivorce(overworld, new MarriageBackpackSessions());
            MarriageDivorce.Filing filing = divorce.file(a, MARRIAGE_DIVORCE_COST);
            helper.assertTrue(filing.result() == MarriageDivorce.Result.OK, "filing succeeds");
            helper.assertTrue(!container.canPlaceItem(0, redstone),
                    "the shared backpack rejects deposits for the whole escrow window, even whitelisted items");

            MarriageDivorce.PendingAction cancelled = divorce.cancel(a);
            helper.assertTrue(cancelled == MarriageDivorce.PendingAction.OK, "cancelling the divorce succeeds");
            helper.assertTrue(container.canPlaceItem(0, redstone),
                    "the shared backpack thaws and accepts deposits again once the divorce is cancelled");

            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 9. 共享背包白名单下钻: 潜影盒内容物递归判 (拒钻/拒绑定/放行普通与空盒), 顶层裸物同拒
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sharedBackpackWhitelistDrillsIntoShulkerBoxContents(GameTestHelper helper) {
        ItemStack diamondBox = shulkerWith(new ItemStack(net.minecraft.world.item.Items.DIAMOND));
        helper.assertTrue(!SharedBackpackWhitelist.isAllowed(diamondBox),
                "a shulker box containing a diamond is rejected (drill-down defeats a top-level-only check)");

        ItemStack stoneBox = shulkerWith(new ItemStack(net.minecraft.world.item.Items.STONE, 32));
        helper.assertTrue(SharedBackpackWhitelist.isAllowed(stoneBox),
                "a shulker box containing plain stone is allowed");

        ItemStack emptyBox = new ItemStack(net.minecraft.world.item.Items.SHULKER_BOX);
        helper.assertTrue(SharedBackpackWhitelist.isAllowed(emptyBox),
                "an empty shulker box (no BlockEntityTag) is allowed");

        ItemStack boundStamped = new ItemStack(net.minecraft.world.item.Items.STICK);
        boundStamped.getOrCreateTag().putUUID("OwnerUUID", java.util.UUID.randomUUID());
        ItemStack boundBox = shulkerWith(boundStamped);
        helper.assertTrue(!SharedBackpackWhitelist.isAllowed(boundBox),
                "a shulker box containing an OwnerUUID-stamped (bound) item is rejected");

        ItemStack bareDiamond = new ItemStack(net.minecraft.world.item.Items.DIAMOND);
        helper.assertTrue(!SharedBackpackWhitelist.isAllowed(bareDiamond),
                "a top-level (uncontained) diamond is still rejected directly");

        helper.succeed();
    }

    /** 造一个只装一件内容物的潜影盒物品 (BlockEntityTag.Items 手写, 与市场包 shulkerWith 同形状但不共用工具类
     *  —— market 包属另一分支范围, 本轮不跨包收口)。 */
    private static ItemStack shulkerWith(ItemStack content) {
        ItemStack box = new ItemStack(net.minecraft.world.item.Items.SHULKER_BOX);
        net.minecraft.nbt.ListTag items = new net.minecraft.nbt.ListTag();
        net.minecraft.nbt.CompoundTag entry = content.save(new net.minecraft.nbt.CompoundTag());
        entry.putByte("Slot", (byte) 0);
        items.add(entry);
        net.minecraft.nbt.CompoundTag blockEntity = new net.minecraft.nbt.CompoundTag();
        blockEntity.put("Items", items);
        box.getOrCreateTag().put("BlockEntityTag", blockEntity);
        return box;
    }

    // ============================================================
    // 10. 共享背包打开会话回收: 唯一打开者关窗即整条 session 回收; 多打开者只关一个则会话存续
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void backpackSessionsReclaimAfterLastOpenerCloses(GameTestHelper helper) {
        LedgerEconomy eco = new LedgerEconomy();
        IEconomyService prev = swapEconomy(eco);
        try {
            ServerLevel overworld = helper.getLevel().getServer().overworld();
            MarriageRegistry registry = MarriageRegistry.get(overworld);
            MarriedPair pair = makeMarriedPair(helper, overworld, eco);
            ServerPlayer a = pair.initiator;
            ServerPlayer b = pair.partner;
            MarriageState state = registry.forPlayer(a.getUUID());
            MarriageBackpackSessions sessions = new MarriageBackpackSessions();

            MarriageBackpackContainer container = sessions.containerFor(state, registry);
            sessions.onOpened(state.marriageId(), a);
            helper.assertTrue(sessions.sessionCount() == 1, "one session is registered after the sole opener opens");
            helper.assertTrue(sessions.openerCount(state.marriageId()) == 1, "one opener is registered");
            helper.assertTrue(sessions.containerFor(state, registry) == container,
                    "containerFor reuses the same authoritative container instance while the session is alive");

            sessions.onClosed(state.marriageId(), a);
            helper.assertTrue(sessions.openerCount(state.marriageId()) == 0,
                    "openerCount drops to 0 after the sole opener closes");
            helper.assertTrue(sessions.sessionCount() == 0,
                    "the whole session is reclaimed once its last opener closes (no idle-session leak)");

            // 重开 (会话已回收, containerFor 会造一个新容器实例, 但仍以 state.sharedInv 为后备存储): 两人同时打开,
            // 只关一个, 会话须存续 (仍有一人在开)。
            sessions.containerFor(state, registry);
            sessions.onOpened(state.marriageId(), a);
            sessions.onOpened(state.marriageId(), b);
            helper.assertTrue(sessions.sessionCount() == 1, "one session is registered after two openers open");
            helper.assertTrue(sessions.openerCount(state.marriageId()) == 2, "two openers are registered");

            sessions.onClosed(state.marriageId(), a);
            helper.assertTrue(sessions.sessionCount() == 1,
                    "closing one of two openers keeps the session alive (the other opener is still in)");
            helper.assertTrue(sessions.openerCount(state.marriageId()) == 1, "exactly one opener remains");

            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 11. 婚约意向 (F098): reject 只按"确实指向本人"移除 / withdraw 返回原 target / clearInvolving 双向清
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void proposalRejectWithdrawAndClearInvolvingObeyOwnershipAndSessionBoundary(GameTestHelper helper) {
        MarriageProposals proposals = new MarriageProposals();
        UUID proposer = java.util.UUID.randomUUID();
        UUID target = java.util.UUID.randomUUID();
        UUID stranger = java.util.UUID.randomUUID();

        proposals.propose(proposer, target);

        // rejectFrom 指向错误目标: 不动表, 返回 false。
        helper.assertTrue(!proposals.rejectFrom(proposer, stranger),
                "rejectFrom against a non-matching target returns false");
        helper.assertTrue(target.equals(proposals.targetOf(proposer)),
                "the outgoing proposal is untouched by the rejected reject call");

        // rejectFrom 指向真目标: 移除, 返回 true。
        helper.assertTrue(proposals.rejectFrom(proposer, target),
                "rejectFrom against the real target returns true");
        helper.assertTrue(proposals.targetOf(proposer) == null,
                "the proposal is gone after a successful reject");

        // withdraw: 有意向返回原 target; 再次调用 (已无意向) 返回 null。
        proposals.propose(proposer, target);
        UUID withdrawn = proposals.withdraw(proposer);
        helper.assertTrue(target.equals(withdrawn), "withdraw returns the original target");
        UUID withdrawnAgain = proposals.withdraw(proposer);
        helper.assertTrue(withdrawnAgain == null, "withdrawing an already-empty outgoing proposal returns null");

        // clearInvolving(x): 同时清 x 发出的 outgoing 意向, 与所有指向 x 的 incoming 意向。
        UUID x = java.util.UUID.randomUUID();
        UUID p1 = java.util.UUID.randomUUID();
        UUID p2 = java.util.UUID.randomUUID();
        UUID outsideTarget = java.util.UUID.randomUUID();
        proposals.propose(p1, x);
        proposals.propose(p2, x);
        proposals.propose(x, outsideTarget);
        helper.assertTrue(proposals.proposersFor(x).size() == 2,
                "two proposers point at x before clearInvolving, got " + proposals.proposersFor(x).size());

        proposals.clearInvolving(x);
        helper.assertTrue(proposals.proposersFor(x).isEmpty(),
                "clearInvolving removes all incoming proposals pointed at x, count now "
                        + proposals.proposersFor(x).size());
        helper.assertTrue(proposals.targetOf(x) == null,
                "clearInvolving also removes x's own outgoing proposal");

        helper.succeed();
    }

    // ============================================================
    // 12. 同一对 UUID 离婚再婚不重发同一里程碑
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

    /**
     * 玩家主背包内某物品的总件数 (离婚清算下发断言用: 在线玩家的待领取物在 settle 内已被
     * {@link MarriageDivorce#deliverClaims} 直接下发进背包, 断言要看背包而非已排空的 {@link MarriageHistory} 待领取表)。
     * {@code Inventory} 无内建 countItem (javap 核实过, 1.20.1 该类只有 contains(ItemStack)/contains(TagKey)),
     * 故手动逐格求和。
     */
    private static int countItemInInventory(ServerPlayer player, Item item) {
        int total = 0;
        for (ItemStack s : player.getInventory().items) {
            if (s.getItem() == item) {
                total += s.getCount();
            }
        }
        return total;
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

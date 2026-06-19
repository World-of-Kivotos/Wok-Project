package com.miningdim.market;

import com.miningdim.core.MiningConstants;
import com.miningdim.economy.AbuseGuard;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyService;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.EconomyWalletData;
import com.miningdim.economy.IEconomyService;
import com.miningdim.economy.PlayerAbuseState;
import com.miningdim.market.store.MarketDaoSqlite;
import com.miningdim.market.store.MarketDb;
import com.miningdim.testutil.MockGameTestPlayers;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 跳蚤市场交易引擎核心逻辑 GameTest (共享契约第 8 节)。服务端纯逻辑, 用内存 SQLite (jdbc:sqlite::memory:, 经
 * {@link MarketDb#openInMemory}) 走与生产同一 DDL; 不 classload 任何 client.webui/MCEF (GameTest 是服务端进程)。
 *
 * 货币侧用真实 {@link EconomyService} 背靠内存 {@link EconomyWalletData} 账本 (余额是真账本, 可精确断言总量守恒),
 * 经 {@link EconomyServices} 定位器 swap/restore (仿 MinerGameTests: GameTest 在已启动服务端跑, 真实门面可能已注入,
 * 测后还原)。强断言 (删被测核心逻辑测试必挂, 禁 is-not-null 弱校验):
 *  1. 挂单->买入 happy path: 买家 -total / 卖家 +proceeds(=total-fee) / 手续费 fee 蒸发 (总量守恒: 减 total = 加 proceeds + 蒸发 fee),
 *     物品进买家库存, listing 变 SOLD, transactions 有 1 行。
 *  2. 余额不足: tryCharge 返 false 路径, 买入抛, listing 仍 ACTIVE, 双方余额不变, 物品未交付。
 *  3. AZURE 计价挂单被拒。
 *  4. 撤单: 物品退回卖家库存, listing CANCELLED。
 *  5. 铜/铁日 cap: 超 COPPER_IRON_DAILY_P2P_CAP 的铜挂单被拒。
 *  6. 离线卖家: 卖家不在线时买入 -> proceeds 进 pending_payout, settlePendingOnLogin 后卖家余额增 proceeds。
 *
 * template = "empty" (纯逻辑/SavedData 断言不依赖结构)。每个测试独立 openInMemory + swapEconomy, finally 关库 + 还原。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MarketGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "market";

    // ============================================================
    // 1. 挂单 -> 买入 happy path: sink 守恒 + 物品交付 + SOLD + 流水
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void placeThenBuyConservesFeeAsSink(GameTestHelper helper) {
        EconomyWalletData ledger = new EconomyWalletData();
        IEconomyService prev = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite dao = MarketDb.openInMemory();
        try {
            // MarketDb.openInMemory 内部已 initSchema (建表 + 索引), 此处不重复建表。
            ServerPlayer seller = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            ServerPlayer buyer = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            MarketEngine engine = new MarketEngine(dao, helper.getLevel().getServer());

            // 卖家挂 10 个钻石 @ 单价 100 -> total = 1000, fee = round(1000*0.05) = 50, proceeds = 950。
            seller.getInventory().clearContent();
            int slot = 0;
            seller.getInventory().setItem(slot, new ItemStack(Items.DIAMOND, 10));
            long listingId = engine.place(seller, slot, 10, 100L, "CREDIT");
            helper.assertTrue(listingId > 0L, "place returns a positive listing id");
            // 托管: 挂单后卖家该槽位被精确扣 10 个 (物品移出库存进 DB)。
            helper.assertTrue(seller.getInventory().getItem(slot).isEmpty(),
                    "escrow removes the listed stack from the seller inventory (slot now empty)");

            // 给买家足额信用点 (1200 > total 1000)。买前清买家库存确保有容量收物品。
            EconomyServices.economyService().grant(buyer, Currency.CREDIT, 1_200L);
            buyer.getInventory().clearContent();
            long buyerBefore = ledger.balance(buyer.getUUID(), Currency.CREDIT);   // 1200
            long sellerBefore = ledger.balance(seller.getUUID(), Currency.CREDIT); // 0

            MarketEngine.BuyResult result = engine.buy(buyer, listingId);

            // 回执字段精确。
            helper.assertTrue(result.total() == 1_000L, "buy total = unitPrice*count = 100*10 = 1000");
            helper.assertTrue(result.fee() == 50L, "buy fee = round(1000*0.05) = 50");
            helper.assertTrue(result.count() == 10, "buy delivered count = 10");

            long buyerAfter = ledger.balance(buyer.getUUID(), Currency.CREDIT);
            long sellerAfter = ledger.balance(seller.getUUID(), Currency.CREDIT);

            // 守恒断言 (sink): 买家减 total=1000, 卖家加 proceeds=950, 差额 50 = fee 蒸发 (不进任何人)。
            long buyerDelta = buyerBefore - buyerAfter;   // 1000
            long sellerDelta = sellerAfter - sellerBefore; // 950
            helper.assertTrue(buyerDelta == 1_000L, "buyer is charged exactly total (1000)");
            helper.assertTrue(sellerDelta == 950L, "online seller receives exactly proceeds = total - fee (950)");
            helper.assertTrue(buyerDelta - sellerDelta == 50L,
                    "the fee (50) vanishes: buyer outflow minus seller inflow equals the fee sink, credited to nobody");

            // 物品交付买家库存 (10 个钻石确实到手)。
            helper.assertTrue(countItem(buyer, Items.DIAMOND) == 10,
                    "the 10 listed diamonds are delivered into the buyer inventory");

            // listing 变 SOLD。
            helper.assertTrue("SOLD".equals(dao.findListing(listingId).status()),
                    "listing status flips to SOLD after purchase");
            // transactions 落了 1 行 (审计): 经 A 的 soldOrListedCountToday 读 transactions 的 SOLD 侧 —— 该方法对任意
            // item 集合统计 (ACTIVE listing.count + 今日 SOLD txn.count)。listing 已 SOLD (不再计入 listed 侧), 故返回值
            // 纯来自 transactions 的本次成交行 count=10 -> 证明恰有一条 count=10 的成交流水写入 (删 insertTxn 此值变 0 必挂)。
            int soldTxnCount = dao.soldOrListedCountToday(seller.getUUID(),
                    java.util.Set.of("minecraft:diamond"), MarketEngine.startOfTodayEpochMillis());
            helper.assertTrue(soldTxnCount == 10,
                    "exactly one transaction row (count=10) is recorded for the sale (read via the SOLD side of the audit table)");
            // 再买同一挂单: 已 SOLD, 抛"挂单不存在或已售" (markSold 的条件 UPDATE 已落, 不可二次成交)。
            boolean secondBuyThrew = false;
            try {
                engine.buy(buyer, listingId);
            } catch (IllegalStateException e) {
                secondBuyThrew = true;
            }
            helper.assertTrue(secondBuyThrew, "re-buying a SOLD listing throws (no double sale)");

            helper.succeed();
        } finally {
            MarketDb.close(dao);
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 2. 余额不足: tryCharge 返 false 路径, listing 仍 ACTIVE, 双方余额不变, 物品未交付
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buyWithInsufficientFundsLeavesEverythingUntouched(GameTestHelper helper) {
        EconomyWalletData ledger = new EconomyWalletData();
        IEconomyService prev = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite dao = MarketDb.openInMemory();
        try {
            // MarketDb.openInMemory 内部已 initSchema; 不重复建表。
            ServerPlayer seller = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            ServerPlayer buyer = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            MarketEngine engine = new MarketEngine(dao, helper.getLevel().getServer());

            seller.getInventory().clearContent();
            seller.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 5));
            // 单价 100, count 5 -> total 500。
            long listingId = engine.place(seller, 0, 5, 100L, "CREDIT");

            // 买家只有 499 (< total 500): tryCharge 必返 false。
            EconomyServices.economyService().grant(buyer, Currency.CREDIT, 499L);
            buyer.getInventory().clearContent();
            long buyerBefore = ledger.balance(buyer.getUUID(), Currency.CREDIT);   // 499
            long sellerBefore = ledger.balance(seller.getUUID(), Currency.CREDIT); // 0

            boolean threw = false;
            try {
                engine.buy(buyer, listingId);
            } catch (IllegalStateException e) {
                threw = true;
            }
            helper.assertTrue(threw, "buying with insufficient funds throws (余额不足), not silently no-ops");

            // 余额一分未动 (tryCharge 返 false = 未扣)。
            helper.assertTrue(ledger.balance(buyer.getUUID(), Currency.CREDIT) == buyerBefore,
                    "buyer balance is untouched on insufficient-funds (no charge)");
            helper.assertTrue(ledger.balance(seller.getUUID(), Currency.CREDIT) == sellerBefore,
                    "seller balance is untouched on insufficient-funds (no payout)");
            // listing 仍 ACTIVE (未 markSold)。
            helper.assertTrue("ACTIVE".equals(dao.findListing(listingId).status()),
                    "listing remains ACTIVE after a failed (insufficient-funds) purchase");
            // 物品未交付买家。
            helper.assertTrue(countItem(buyer, Items.IRON_INGOT) == 0,
                    "no item is delivered when the purchase fails on funds");
            // 无成交流水: soldOrListedCountToday(铁锭集) 现应纯来自仍 ACTIVE 的挂单 (listed 侧 count=5), SOLD 侧为 0。
            // 若失败路径误写了 txn 行, 该值会变 5(listed)+5(sold)=10; 断言 ==5 即证明无任何成交流水落入 transactions。
            int countAfterFail = dao.soldOrListedCountToday(seller.getUUID(),
                    java.util.Set.of("minecraft:iron_ingot"), MarketEngine.startOfTodayEpochMillis());
            helper.assertTrue(countAfterFail == 5,
                    "no transaction row is written for a failed purchase (only the still-ACTIVE listing counts: 5, not 10)");

            helper.succeed();
        } finally {
            MarketDb.close(dao);
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 3. AZURE 计价挂单被拒 (AZURE 不可转移; 市场只允许 CREDIT)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void azurePricedListingIsRejected(GameTestHelper helper) {
        EconomyWalletData ledger = new EconomyWalletData();
        IEconomyService prev = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite dao = MarketDb.openInMemory();
        try {
            // MarketDb.openInMemory 内部已 initSchema; 不重复建表。
            ServerPlayer seller = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            MarketEngine engine = new MarketEngine(dao, helper.getLevel().getServer());

            seller.getInventory().clearContent();
            seller.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 4));

            boolean threwAzure = false;
            try {
                engine.place(seller, 0, 4, 100L, "AZURE");
            } catch (IllegalArgumentException e) {
                threwAzure = true;
            }
            helper.assertTrue(threwAzure,
                    "an AZURE-priced listing is rejected (青辉石不可转移; market is CREDIT-only)");
            // 拒挂单后物品仍在卖家库存 (托管未发生, 物品不凭空消失)。
            helper.assertTrue(seller.getInventory().getItem(0).getCount() == 4,
                    "the rejected listing does not escrow the item (stack still in seller inventory)");
            // 无任何 ACTIVE 挂单写入。
            helper.assertTrue(dao.listingsBySeller(seller.getUUID(), "ACTIVE").isEmpty(),
                    "no listing row is created for a rejected AZURE listing");

            // 非 CREDIT 的任意 currency 同样被拒。
            boolean threwOther = false;
            try {
                engine.place(seller, 0, 4, 100L, "GOLD_COIN");
            } catch (IllegalArgumentException e) {
                threwOther = true;
            }
            helper.assertTrue(threwOther, "a non-CREDIT currency string is rejected too");

            helper.succeed();
        } finally {
            MarketDb.close(dao);
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 4. 撤单: 物品退回卖家库存, listing CANCELLED
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cancelRefundsItemAndMarksCancelled(GameTestHelper helper) {
        EconomyWalletData ledger = new EconomyWalletData();
        IEconomyService prev = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite dao = MarketDb.openInMemory();
        try {
            // MarketDb.openInMemory 内部已 initSchema; 不重复建表。
            ServerPlayer seller = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            MarketEngine engine = new MarketEngine(dao, helper.getLevel().getServer());

            seller.getInventory().clearContent();
            seller.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 8));
            long listingId = engine.place(seller, 0, 8, 50L, "CREDIT");
            // 托管后库存空。
            helper.assertTrue(countItem(seller, Items.DIAMOND) == 0,
                    "escrow empties the seller stack on place");

            MarketEngine.CancelResult result = engine.cancel(seller, listingId);
            helper.assertTrue(result.count() == 8, "cancel reports the escrowed count (8)");

            // 物品退回卖家库存 (8 个钻石回到手)。
            helper.assertTrue(countItem(seller, Items.DIAMOND) == 8,
                    "cancel refunds the escrowed 8 diamonds back into the seller inventory");
            // listing 变 CANCELLED。
            helper.assertTrue("CANCELLED".equals(dao.findListing(listingId).status()),
                    "listing status flips to CANCELLED after cancel");
            // 撤后无 ACTIVE 挂单。
            helper.assertTrue(dao.listingsBySeller(seller.getUUID(), "ACTIVE").isEmpty(),
                    "the cancelled listing is no longer ACTIVE");

            // 再撤同一挂单: 已非 ACTIVE, 抛 (不重复退物品 -> 防物品复制)。
            boolean threwSecond = false;
            try {
                engine.cancel(seller, listingId);
            } catch (IllegalStateException e) {
                threwSecond = true;
            }
            helper.assertTrue(threwSecond, "cancelling an already-cancelled listing throws (no double refund)");
            helper.assertTrue(countItem(seller, Items.DIAMOND) == 8,
                    "the second cancel does not duplicate the refund (still 8 diamonds)");

            helper.succeed();
        } finally {
            MarketDb.close(dao);
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 5. 铜/铁日 cap: 超 COPPER_IRON_DAILY_P2P_CAP 的铜挂单被拒
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void copperIronDailyCapRejectsOverflow(GameTestHelper helper) {
        EconomyWalletData ledger = new EconomyWalletData();
        IEconomyService prev = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite dao = MarketDb.openInMemory();
        try {
            // MarketDb.openInMemory 内部已 initSchema; 不重复建表。
            ServerPlayer seller = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            MarketEngine engine = new MarketEngine(dao, helper.getLevel().getServer());

            int cap = MarketConstants.COPPER_IRON_DAILY_P2P_CAP; // 512 (DRAFT)
            seller.getInventory().clearContent();

            // 先挂满 cap 个铜锭 (恰达上限, 成功)。给一组 64 stack 摆满前若干槽, 简化为单槽 setItem cap 个 (mock 允许超 64 单槽计数,
            // shrink 精确扣 cap 个; place 只校验 stack.getCount() >= count, 与单槽最大堆叠无关, 内存测试可行)。
            seller.getInventory().setItem(0, new ItemStack(Items.COPPER_INGOT, cap));
            long atCap = engine.place(seller, 0, cap, 10L, "CREDIT");
            helper.assertTrue(atCap > 0L, "listing exactly at the daily cap succeeds (达上限不拒)");
            // soldOrListedCountToday 现应 == cap (该 ACTIVE 挂单计入今日量)。
            helper.assertTrue(dao.soldOrListedCountToday(seller.getUUID(),
                            MarketConstants.COPPER_IRON_ITEM_IDS, MarketEngine.startOfTodayEpochMillis()) == cap,
                    "today's copper/iron P2P count equals the cap after the at-cap listing");

            // 再挂 1 个铁锭 (累计 cap+1 > cap): 被拒 (铜铁集合跨 item 共享同一日 cap)。
            seller.getInventory().setItem(1, new ItemStack(Items.IRON_INGOT, 1));
            boolean threw = false;
            try {
                engine.place(seller, 1, 1, 10L, "CREDIT");
            } catch (IllegalStateException e) {
                threw = true;
            }
            helper.assertTrue(threw,
                    "a listing that pushes the daily copper/iron P2P count over the cap is rejected (across item ids)");
            // 被拒挂单的铁锭仍在库存 (托管未发生)。
            helper.assertTrue(seller.getInventory().getItem(1).getCount() == 1,
                    "the over-cap listing does not escrow its item (iron ingot still in inventory)");

            // 非铜铁标的不受此 cap 约束: 同日再挂大量钻石成功 (cap 只管铜铁集合)。
            seller.getInventory().setItem(2, new ItemStack(Items.DIAMOND, cap));
            long diamondListing = engine.place(seller, 2, cap, 10L, "CREDIT");
            helper.assertTrue(diamondListing > 0L,
                    "non copper/iron items are not subject to the copper/iron daily cap");

            helper.succeed();
        } finally {
            MarketDb.close(dao);
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 6. 离线卖家: 买入 -> proceeds 进 pending_payout; settlePendingOnLogin 后卖家余额增 proceeds
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void offlineSellerPayoutIsDeferredAndSettledOnLogin(GameTestHelper helper) {
        EconomyWalletData ledger = new EconomyWalletData();
        IEconomyService prev = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite dao = MarketDb.openInMemory();
        try {
            // MarketDb.openInMemory 内部已 initSchema; 不重复建表。
            ServerPlayer buyer = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            MarketEngine engine = new MarketEngine(dao, helper.getLevel().getServer());

            // 离线卖家: 用一个不在 PlayerList 的 UUID, 直接经 DAO 写入 ACTIVE 挂单 (模拟挂单后卖家下线)。
            // 单价 200, count 5 -> total 1000, fee = round(1000*0.05)=50, proceeds = 950。
            UUID offlineSeller = UUID.randomUUID();
            helper.assertTrue(helper.getLevel().getServer().getPlayerList().getPlayer(offlineSeller) == null,
                    "the offline seller UUID is genuinely not in the player list");
            byte[] nbt = MarketEngine.serializeStack(new ItemStack(Items.DIAMOND, 5));
            long listingId = dao.insertListing(offlineSeller, "offline-seller",
                    "minecraft:diamond", nbt, 5, 200L, "CREDIT", System.currentTimeMillis());

            // 给买家足额信用点并清库存。
            EconomyServices.economyService().grant(buyer, Currency.CREDIT, 2_000L);
            buyer.getInventory().clearContent();

            MarketEngine.BuyResult result = engine.buy(buyer, listingId);
            helper.assertTrue(result.total() == 1_000L && result.fee() == 50L,
                    "offline-seller buy computes total=1000 fee=50 like the online path");

            // 离线分支: 卖家钱包未即时入账 (余额仍 0), proceeds 落 pending_payout。
            helper.assertTrue(ledger.balance(offlineSeller, Currency.CREDIT) == 0L,
                    "offline seller wallet is NOT credited immediately (payout deferred)");
            // 买家仍被正常扣 total, 物品正常交付 (买入路径与在线一致)。
            helper.assertTrue(ledger.balance(buyer.getUUID(), Currency.CREDIT) == 1_000L,
                    "buyer is charged total (2000 - 1000 = 1000 remaining)");
            helper.assertTrue(countItem(buyer, Items.DIAMOND) == 5,
                    "buyer receives the item even though the seller is offline");

            // 卖家登录结算: 用同一 UUID 造 mock 玩家, settlePendingOnLogin 把 pending 累加 grant -> 余额 = proceeds 950。
            ServerPlayer sellerOnLogin = makeMockPlayerWithUuid(helper, offlineSeller);
            engine.settlePendingOnLogin(sellerOnLogin);
            helper.assertTrue(ledger.balance(offlineSeller, Currency.CREDIT) == 950L,
                    "after login settlement the seller balance equals the deferred proceeds (950)");

            // 二次登录结算: pending 已 drain 清空, 不重复发放 (余额仍 950)。
            engine.settlePendingOnLogin(sellerOnLogin);
            helper.assertTrue(ledger.balance(offlineSeller, Currency.CREDIT) == 950L,
                    "a second login settlement does not double-pay (pending already drained): still 950");

            helper.succeed();
        } finally {
            MarketDb.close(dao);
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 定位器 swap/restore (仿 MinerGameTests: GameTest 在已启动服务端跑, 测后还原启动期绑定的真实门面)
    // ============================================================

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
     * 测试用 {@link PlayerAbuseState} 解析器 (与 EconomySystem.playerState 同纪律: 未知 UUID 惰性建态),
     * 供真实 {@link EconomyService} 的 isAfkFrozen 等取态。市场用例不触挖矿计数, 仅满足门面构造非空约束。
     */
    private static Function<UUID, PlayerAbuseState> newStateResolver() {
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        return id -> states.computeIfAbsent(id, k -> new PlayerAbuseState());
    }

    /** 某玩家主背包内某物品总数 (交付/退回断言用)。 */
    private static int countItem(ServerPlayer player, net.minecraft.world.item.Item item) {
        int total = 0;
        for (ItemStack s : player.getInventory().items) {
            if (s.is(item)) {
                total += s.getCount();
            }
        }
        return total;
    }

    /**
     * 造一个带指定 UUID 的 mock ServerPlayer (离线卖家登录结算用; settlePendingOnLogin 只读 seller.getUUID())。
     * 复用 {@link MockGameTestPlayers} 同款 EmbeddedChannel 修复 (规避 Forge NetworkFilters 对 null channel 的 NPE),
     * 但 GameProfile 用传入 UUID 而非随机, 使其与离线卖家 pending_payout 的 UUID 对齐。
     */
    private static ServerPlayer makeMockPlayerWithUuid(GameTestHelper helper, UUID uuid) {
        ServerLevel level = helper.getLevel();
        ServerPlayer serverPlayer = new ServerPlayer(
                level.getServer(), level, new GameProfile(uuid, "offline-seller")) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return true;
            }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        level.getServer().getPlayerList().placeNewPlayer(connection, serverPlayer);
        return serverPlayer;
    }
}

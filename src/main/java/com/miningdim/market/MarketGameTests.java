package com.miningdim.market;

import com.miningdim.core.MiningConstants;
import com.miningdim.economy.AbuseGuard;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyService;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.EconomyLedger;
import com.miningdim.economy.SqliteEconomyLedger;
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
 * 货币侧用真实 {@link EconomyService} 背靠内存 {@link com.miningdim.economy.SqliteEconomyLedger} 账本 (余额是真账本, 可精确断言总量守恒),
 * 经 {@link EconomyServices} 定位器 swap/restore (仿 MinerGameTests: GameTest 在已启动服务端跑, 真实门面可能已注入,
 * 测后还原)。强断言 (删被测核心逻辑测试必挂, 禁 is-not-null 弱校验):
 *  1. 挂单->买入 happy path: 挂单时向卖家收偏离费 listFee 蒸发 (sink, 上单即收), 买入时买家 -total / 卖家 +全额 total (买入不再收费),
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
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prev = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite dao = MarketDb.on(ledger.connection());
        try {
            // MarketDb.openInMemory 内部已 initSchema (建表 + 索引), 此处不重复建表。
            ServerPlayer seller = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            ServerPlayer buyer = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            MarketEngine engine = new MarketEngine(dao, helper.getLevel().getServer());

            // 卖家挂 10 个钻石 @ 单价 100 (基准价 V0=500, 贱卖) -> 挂单时按偏离费向卖家收 listFee (sink, 上单即收)。
            seller.getInventory().clearContent();
            int slot = 0;
            seller.getInventory().setItem(slot, new ItemStack(Items.DIAMOND, 10));
            long expectedListFee = MarketFee.listingFee(
                    DefaultBaseValues.resolve("minecraft:diamond"), 100L, 10);
            helper.assertTrue(expectedListFee > 0L, "deviation listing fee for under-priced diamonds is positive");
            // 给卖家足额信用点付挂单手续费 (10000 > listFee)。
            EconomyServices.economyService().grant(seller, Currency.CREDIT, 10_000L);
            long sellerFunded = ledger.balance(seller.getUUID(), Currency.CREDIT); // 10000

            MarketEngine.PlaceResult placed = engine.place(seller, slot, 10, 100L, "CREDIT");
            long listingId = placed.listingId();
            helper.assertTrue(listingId > 0L, "place returns a positive listing id");
            helper.assertTrue(placed.listFee() == expectedListFee,
                    "place charges exactly the computed deviation listing fee");
            // 上单即收 sink: 卖家挂单后余额精确减 listFee (蒸发, 不进任何人)。
            helper.assertTrue(ledger.balance(seller.getUUID(), Currency.CREDIT) == sellerFunded - expectedListFee,
                    "the listing fee is charged from the seller at place time and vanishes (sink)");
            // 托管: 挂单后卖家该槽位被精确扣 10 个 (物品移出库存进 DB)。
            helper.assertTrue(seller.getInventory().getItem(slot).isEmpty(),
                    "escrow removes the listed stack from the seller inventory (slot now empty)");

            // 给买家足额信用点 (1200 > total 1000)。买前清买家库存确保有容量收物品。
            EconomyServices.economyService().grant(buyer, Currency.CREDIT, 1_200L);
            buyer.getInventory().clearContent();
            long buyerBefore = ledger.balance(buyer.getUUID(), Currency.CREDIT);     // 1200
            long sellerBeforeBuy = ledger.balance(seller.getUUID(), Currency.CREDIT); // 10000 - listFee

            MarketEngine.BuyResult result = engine.buy(buyer, listingId, 10);

            // 回执字段精确: 买入不再收费 (fee 已挪到挂单时), 卖家实收全额 total。
            helper.assertTrue(result.total() == 1_000L, "buy total = unitPrice*count = 100*10 = 1000");
            helper.assertTrue(result.fee() == 0L, "buy no longer charges a fee (moved to place time): fee=0");
            helper.assertTrue(result.count() == 10, "buy delivered count = 10");

            long buyerAfter = ledger.balance(buyer.getUUID(), Currency.CREDIT);
            long sellerAfter = ledger.balance(seller.getUUID(), Currency.CREDIT);

            // 买家减 total=1000; 卖家加全额 total=1000 (买入侧无 sink, sink 在挂单时已收)。
            helper.assertTrue(buyerBefore - buyerAfter == 1_000L, "buyer is charged exactly total (1000)");
            helper.assertTrue(sellerAfter - sellerBeforeBuy == 1_000L,
                    "online seller receives the full total at buy time (no second fee; fee taken at place)");

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
                engine.buy(buyer, listingId, 10);
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
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prev = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite dao = MarketDb.on(ledger.connection());
        try {
            // MarketDb.openInMemory 内部已 initSchema; 不重复建表。
            ServerPlayer seller = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            ServerPlayer buyer = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            MarketEngine engine = new MarketEngine(dao, helper.getLevel().getServer());

            seller.getInventory().clearContent();
            seller.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 5));
            // 铁锭无内置基准价 V0 -> 挂单手续费走平率 round(0.20*100*5)=100; 先给卖家足额付挂单费。
            EconomyServices.economyService().grant(seller, Currency.CREDIT, 1_000L);
            // 单价 100, count 5 -> total 500。
            long listingId = engine.place(seller, 0, 5, 100L, "CREDIT").listingId();

            // 买家只有 499 (< total 500): tryCharge 必返 false。
            EconomyServices.economyService().grant(buyer, Currency.CREDIT, 499L);
            buyer.getInventory().clearContent();
            long buyerBefore = ledger.balance(buyer.getUUID(), Currency.CREDIT);   // 499
            long sellerBefore = ledger.balance(seller.getUUID(), Currency.CREDIT); // 1000 - 100(平率挂单费) = 900

            boolean threw = false;
            try {
                engine.buy(buyer, listingId, 5);
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
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prev = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite dao = MarketDb.on(ledger.connection());
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
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prev = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite dao = MarketDb.on(ledger.connection());
        try {
            // MarketDb.openInMemory 内部已 initSchema; 不重复建表。
            ServerPlayer seller = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            MarketEngine engine = new MarketEngine(dao, helper.getLevel().getServer());

            seller.getInventory().clearContent();
            seller.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 8));
            long expectedListFee = MarketFee.listingFee(
                    DefaultBaseValues.resolve("minecraft:diamond"), 50L, 8);
            EconomyServices.economyService().grant(seller, Currency.CREDIT, 10_000L);
            long sellerFunded = ledger.balance(seller.getUUID(), Currency.CREDIT); // 10000
            MarketEngine.PlaceResult placed = engine.place(seller, 0, 8, 50L, "CREDIT");
            long listingId = placed.listingId();
            helper.assertTrue(placed.listFee() == expectedListFee,
                    "place charges the deviation listing fee for the under-priced diamonds");
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
            // 挂单手续费不退 (EFT 非退性): 撤单退回物品, 但 listFee 已蒸发, 不随撤单返还。
            helper.assertTrue(ledger.balance(seller.getUUID(), Currency.CREDIT) == sellerFunded - expectedListFee,
                    "cancel refunds the item but NOT the listing fee (fee is a non-refundable sink)");
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
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prev = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite dao = MarketDb.on(ledger.connection());
        try {
            // MarketDb.openInMemory 内部已 initSchema; 不重复建表。
            ServerPlayer seller = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            MarketEngine engine = new MarketEngine(dao, helper.getLevel().getServer());

            int cap = MarketConstants.COPPER_IRON_DAILY_P2P_CAP; // 512 (DRAFT)
            seller.getInventory().clearContent();
            // 挂单手续费上单即收: 给卖家足额信用点 (铜锭平率费 1024 + 后面 512 个钻石 @10 极端贱卖偏离费 ~207912 均需先付;
            // 定稿 K=0.04 下该贱卖偏离费远超旧值, 故 funding 由 20w 提到 50w, 确保被拒因 cap 而非挂单费不足)。
            EconomyServices.economyService().grant(seller, Currency.CREDIT, 500_000L);

            // 先挂满 cap 个铜锭 (恰达上限, 成功)。给一组 64 stack 摆满前若干槽, 简化为单槽 setItem cap 个 (mock 允许超 64 单槽计数,
            // shrink 精确扣 cap 个; place 只校验 stack.getCount() >= count, 与单槽最大堆叠无关, 内存测试可行)。
            seller.getInventory().setItem(0, new ItemStack(Items.COPPER_INGOT, cap));
            long atCap = engine.place(seller, 0, cap, 10L, "CREDIT").listingId();
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
            long diamondListing = engine.place(seller, 2, cap, 10L, "CREDIT").listingId();
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
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prev = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite dao = MarketDb.on(ledger.connection());
        try {
            // MarketDb.openInMemory 内部已 initSchema; 不重复建表。
            ServerPlayer buyer = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            MarketEngine engine = new MarketEngine(dao, helper.getLevel().getServer());

            // 离线卖家: 用一个不在 PlayerList 的 UUID, 直接经 DAO 写入 ACTIVE 挂单 (模拟挂单后卖家下线; 绕过 place 故无挂单费)。
            // 单价 200, count 5 -> total 1000; 买入不再收费, 卖家实收全额 proceeds = total = 1000。
            UUID offlineSeller = UUID.randomUUID();
            helper.assertTrue(helper.getLevel().getServer().getPlayerList().getPlayer(offlineSeller) == null,
                    "the offline seller UUID is genuinely not in the player list");
            byte[] nbt = MarketEngine.serializeStack(new ItemStack(Items.DIAMOND, 5));
            long listingId = dao.insertListing(offlineSeller, "offline-seller",
                    "minecraft:diamond", nbt, 5, 200L, "CREDIT", System.currentTimeMillis());

            // 给买家足额信用点并清库存。
            EconomyServices.economyService().grant(buyer, Currency.CREDIT, 2_000L);
            buyer.getInventory().clearContent();

            MarketEngine.BuyResult result = engine.buy(buyer, listingId, 5);
            helper.assertTrue(result.total() == 1_000L && result.fee() == 0L,
                    "offline-seller buy computes total=1000 fee=0 like the online path (fee moved to place)");

            // 离线分支: 卖家钱包未即时入账 (余额仍 0), proceeds 落 pending_payout。
            helper.assertTrue(ledger.balance(offlineSeller, Currency.CREDIT) == 0L,
                    "offline seller wallet is NOT credited immediately (payout deferred)");
            // 买家仍被正常扣 total, 物品正常交付 (买入路径与在线一致)。
            helper.assertTrue(ledger.balance(buyer.getUUID(), Currency.CREDIT) == 1_000L,
                    "buyer is charged total (2000 - 1000 = 1000 remaining)");
            helper.assertTrue(countItem(buyer, Items.DIAMOND) == 5,
                    "buyer receives the item even though the seller is offline");

            // 卖家登录结算: 用同一 UUID 造 mock 玩家, settlePendingOnLogin 把 pending 累加 grant -> 余额 = proceeds = 全额 total 1000 (买入侧不收费)。
            ServerPlayer sellerOnLogin = makeMockPlayerWithUuid(helper, offlineSeller);
            engine.settlePendingOnLogin(sellerOnLogin);
            helper.assertTrue(ledger.balance(offlineSeller, Currency.CREDIT) == 1_000L,
                    "after login settlement the seller balance equals the deferred proceeds = full total (1000)");

            // 二次登录结算: pending 已 drain 清空, 不重复发放 (余额仍 1000)。
            engine.settlePendingOnLogin(sellerOnLogin);
            helper.assertTrue(ledger.balance(offlineSeller, Currency.CREDIT) == 1_000L,
                    "a second login settlement does not double-pay (pending already drained): still 1000");

            helper.succeed();
        } finally {
            MarketDb.close(dao);
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 8. 部分购买: 10 个里买 4 -> 交付 4, 挂单余 6 仍 ACTIVE; 再买 6 -> SOLD; 卖家累计收全额; 越界拒
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void partialBuySplitsListingAndConservesTotals(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prev = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite dao = MarketDb.on(ledger.connection());
        try {
            ServerPlayer seller = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            ServerPlayer buyer = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            MarketEngine engine = new MarketEngine(dao, helper.getLevel().getServer());

            // 卖家挂 10 个铁锭 @ 单价 50 (铁无锚 -> 平率挂单费); 给卖家付挂单费, 给买家足额。
            seller.getInventory().clearContent();
            seller.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 10));
            EconomyServices.economyService().grant(seller, Currency.CREDIT, 10_000L);
            long listingId = engine.place(seller, 0, 10, 50L, "CREDIT").listingId();
            long sellerAfterPlace = ledger.balance(seller.getUUID(), Currency.CREDIT);

            EconomyServices.economyService().grant(buyer, Currency.CREDIT, 10_000L);
            buyer.getInventory().clearContent();

            // 第一笔: 买 4 个 (10 个里的 4) -> total = 50*4 = 200。
            MarketEngine.BuyResult r1 = engine.buy(buyer, listingId, 4);
            helper.assertTrue(r1.count() == 4 && r1.total() == 200L && r1.fee() == 0L,
                    "partial buy reports count=4 total=200 fee=0");
            helper.assertTrue(countItem(buyer, Items.IRON_INGOT) == 4,
                    "buyer receives exactly the 4 purchased iron ingots (not the whole stack)");
            // 挂单仍 ACTIVE, 剩余 count = 6。
            com.miningdim.market.store.ListingRow afterFirst = dao.findListing(listingId);
            helper.assertTrue("ACTIVE".equals(afterFirst.status()) && afterFirst.count() == 6,
                    "after the partial buy the listing stays ACTIVE with remaining count 6");
            helper.assertTrue(ledger.balance(seller.getUUID(), Currency.CREDIT) == sellerAfterPlace + 200L,
                    "seller receives the full 200 for the partial sale (fee was already taken at place)");

            // 第二笔: 买下剩余 6 -> total = 300, 挂单转 SOLD。
            MarketEngine.BuyResult r2 = engine.buy(buyer, listingId, 6);
            helper.assertTrue(r2.count() == 6 && r2.total() == 300L,
                    "buying the remaining 6 reports count=6 total=300");
            helper.assertTrue(countItem(buyer, Items.IRON_INGOT) == 10,
                    "buyer now holds all 10 iron ingots across the two partial buys (no item lost/duped)");
            helper.assertTrue("SOLD".equals(dao.findListing(listingId).status()),
                    "the listing flips to SOLD once the remaining quantity is bought out");
            helper.assertTrue(ledger.balance(seller.getUUID(), Currency.CREDIT) == sellerAfterPlace + 500L,
                    "seller cumulative proceeds equal the full 10*50 = 500 (200 + 300)");

            // 越界: 买超过挂单剩余 -> 抛且不动挂单 (校验在扣款前, 无副作用)。
            seller.getInventory().clearContent();
            seller.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 3));
            long small = engine.place(seller, 0, 3, 50L, "CREDIT").listingId();
            boolean overThrew = false;
            try {
                engine.buy(buyer, small, 5); // 想买 5 但只剩 3
            } catch (IllegalArgumentException e) {
                overThrew = true;
            }
            helper.assertTrue(overThrew, "buying more than the listing's remaining count throws (越界)");
            com.miningdim.market.store.ListingRow afterOver = dao.findListing(small);
            helper.assertTrue("ACTIVE".equals(afterOver.status()) && afterOver.count() == 3,
                    "the rejected over-count buy leaves the listing untouched (still ACTIVE, count 3)");

            helper.succeed();
        } finally {
            MarketDb.close(dao);
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 6.5 成交与离线结算的事务原子性 (钱与市场表已同库同连接)
    // ============================================================

    /**
     * 成交过程中卖家入账失败时, 买家扣款、挂单状态与流水必须一并回滚。
     *
     * 卖家余额已达 Long.MAX_VALUE 是真实可达的入账失败路径 (不靠桩)。此前扣款、markSold、insertTxn、卖家
     * 入账各走 autocommit, 卖家入账抛出时前三步已经落盘 —— 买家钱没了、挂单显示已售、物品也没交付。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void failedSellerPayoutRollsBackTheWholeBuy(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prev = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite dao = MarketDb.on(ledger.connection());
        try {
            ServerPlayer seller = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            ServerPlayer buyer = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            MarketEngine engine = new MarketEngine(dao, helper.getLevel().getServer());

            seller.getInventory().clearContent();
            seller.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 4));
            EconomyServices.economyService().grant(seller, Currency.CREDIT, 10_000L);
            long listingId = engine.place(seller, 0, 4, 50L, "CREDIT").listingId();

            EconomyServices.economyService().grant(buyer, Currency.CREDIT, 1_000L);
            buyer.getInventory().clearContent();
            long buyerBefore = ledger.balance(buyer.getUUID(), Currency.CREDIT);

            // 把卖家余额顶到 long 上界: 成交时的卖家入账必然溢出。
            ledger.credit(seller.getUUID(), Currency.CREDIT,
                    Long.MAX_VALUE - ledger.balance(seller.getUUID(), Currency.CREDIT));

            boolean failed = false;
            try {
                engine.buy(buyer, listingId, 4);
            } catch (RuntimeException expected) {
                failed = true;
            }
            helper.assertTrue(failed, "卖家入账溢出必须让整笔成交失败");
            helper.assertTrue(ledger.balance(buyer.getUUID(), Currency.CREDIT) == buyerBefore,
                    "回滚后买家余额必须原封不动, 实为 " + ledger.balance(buyer.getUUID(), Currency.CREDIT)
                            + " (应为 " + buyerBefore + ")");
            helper.assertTrue("ACTIVE".equals(dao.findListing(listingId).status()),
                    "回滚后挂单必须仍是 ACTIVE, 不能显示已售");
            helper.assertTrue(countItem(buyer, Items.IRON_INGOT) == 0,
                    "失败的成交不得交付任何物品");
            helper.assertTrue(rowCount(ledger, "SELECT COUNT(*) FROM transactions WHERE listing_id="
                    + listingId) == 0L, "回滚后不得留下脏流水行");
            helper.succeed();
        } finally {
            MarketDb.close(dao);
            restoreEconomy(prev);
        }
    }

    /**
     * 登录结算入账失败时, 待结款行必须还在。
     *
     * 这是"先物理删除再发钱"的直接回归测试: 修复前 drainPendingPayout 自己提交了 SELECT + DELETE, 之后才
     * grant, 崩在两步之间卖家的离线收入永久消失且无从追溯。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void failedLoginSettlementKeepsPendingPayout(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prev = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite dao = MarketDb.on(ledger.connection());
        try {
            MarketEngine engine = new MarketEngine(dao, helper.getLevel().getServer());
            UUID sellerId = UUID.randomUUID();
            dao.insertPendingPayout(sellerId, 777L, MarketConstants.CURRENCY_CREDIT, System.currentTimeMillis());

            ServerPlayer seller = makeMockPlayerWithUuid(helper, sellerId);
            // 余额顶到 long 上界: 结算入账必然溢出。
            ledger.credit(sellerId, Currency.CREDIT, Long.MAX_VALUE);

            boolean failed = false;
            try {
                engine.settlePendingOnLogin(seller);
            } catch (RuntimeException expected) {
                failed = true;
            }
            helper.assertTrue(failed, "结算入账溢出必须抛出而不是静默吞掉");
            helper.assertTrue(rowCount(ledger, "SELECT COUNT(*) FROM pending_payout WHERE seller_uuid='"
                            + sellerId + "'") == 1L,
                    "入账失败时待结款行必须原样保留, 否则这笔钱永久消失且无从追溯");
            helper.assertTrue(ledger.balance(sellerId, Currency.CREDIT) == Long.MAX_VALUE,
                    "失败的结算不得改动余额");
            helper.succeed();
        } finally {
            MarketDb.close(dao);
            restoreEconomy(prev);
        }
    }

    /** 直接查库计数 (市场 DAO 不暴露这些只读统计, 测试自己查)。 */
    private static long rowCount(SqliteEconomyLedger ledger, String sql) {
        try (java.sql.Statement st = ledger.connection().createStatement();
             java.sql.ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) {
                throw new IllegalStateException("计数查询无结果: " + sql);
            }
            return rs.getLong(1);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("计数查询失败: " + sql, e);
        }
    }

    // ============================================================
    // 7. 偏离费校准 (纯函数): 平价=平率地板 / 极端偏离≈物品价值 / 两端对称 / 小幅偏离便宜 / 无锚退平率
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void deviationFeeCalibrationMatchesIntent(GameTestHelper helper) {
        // 平价 (VR==V0): ln(1)=0, 费率落到 FEE_RATE 地板 = round(0.20 * V0 * count) = round(0.20*100000*1) = 20000。
        long parity = MarketFee.deviationFee(100_000L, 100_000L, 1);
        helper.assertTrue(parity == 20_000L,
                "at parity (VR==V0) the fee is the flat base-rate floor 0.20*100000 = 20000, got " + parity);

        // 贱卖到极端 (基准 10w 的物挂 1 块): 定稿 K=0.04 下偏离惩罚陡, 费 ≈ 5.5x 物品价值 (远超物品自身, 对敲必净亏)。
        // round(100000*1*(0.20 + 0.04*ln(1/100000)^2)) = round(100000*5.501898...) = 550190。断言确切值 + "远超物品价值"。
        long firesale = MarketFee.deviationFee(100_000L, 1L, 1);
        helper.assertTrue(firesale == 550_190L,
                "firesale (V0=100000 listed at 1) fee = 0.04 deviation = 550190, got " + firesale);
        helper.assertTrue(firesale > 100_000L,
                "the extreme-deviation fee far exceeds the item's own value (launder is a net loss), got " + firesale);

        // 对称: 天价 (基准 1 挂 10w) 与贱卖同费 (用户决策: 对称惩罚)。
        long overprice = MarketFee.deviationFee(1L, 100_000L, 1);
        helper.assertTrue(overprice == firesale,
                "deviation fee is symmetric: overprice equals firesale, got over=" + overprice + " fire=" + firesale);

        // 小幅偏离 (2 倍): round(1000*1*(0.20 + 0.04*ln(1000/500)^2)) = round(1000*0.219218...) = 219。
        // 远低于极端偏离 (550190) 的千分之一 —— 诚实还价的代价比洗钱式极端偏离低三个数量级 (校准意图: 偏离越大越陡)。
        long mild = MarketFee.deviationFee(500L, 1_000L, 1);
        helper.assertTrue(mild == 219L,
                "a mild 2x deviation costs round(0.20+0.04*ln(2)^2)*1000 = 219, got " + mild);
        helper.assertTrue(mild < firesale / 1_000L,
                "a mild deviation stays below 1/1000 of the extreme-deviation penalty (orders of magnitude cheaper), got " + mild);

        // 无锚兜底 = 平率费 round(0.20*unitPrice*count) = round(0.20*100*10) = 200。
        long flat = MarketFee.listingFee(java.util.OptionalLong.empty(), 100L, 10);
        helper.assertTrue(flat == 200L, "no-anchor listing fee falls back to flat 0.20*total = 200, got " + flat);

        helper.succeed();
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

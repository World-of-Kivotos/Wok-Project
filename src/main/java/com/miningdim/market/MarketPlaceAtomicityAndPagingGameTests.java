package com.miningdim.market;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.economy.AbuseGuard;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyService;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import com.miningdim.economy.PlayerAbuseState;
import com.miningdim.economy.SqliteEconomyLedger;
import com.miningdim.market.store.ListingRow;
import com.miningdim.market.store.MarketDao;
import com.miningdim.market.store.MarketDaoSqlite;
import com.miningdim.market.store.MarketDb;
import com.miningdim.market.store.MarketStoreException;
import com.miningdim.market.store.SoldOrListedSplit;
import com.miningdim.market.store.TxnRow;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 两处修复的回归测试: {@link MarketEngine#place} 的落库顺序, 与 {@link MarketActions#LIST} 的分页钳制。
 *
 * 一、place 的落库顺序 (insertListing 先于 stack.shrink)。挂单期物品的唯一所在就是 listings 那一行。
 * 先扣库存再落库的话, insertListing 抛 (磁盘满 / 库被锁) 时物品既不在背包也不在库里 —— 连同已扣的手续费
 * 一起蒸发, 玩家手上没有任何凭据。用一个"只有 insertListing 必抛"的 DAO 替身把这个中间态钉住:
 *  1. 落库失败后卖家那一摞物品必须一件不少 (顺序回退成 shrink 在前, 这条当场挂);
 *  2. listings 表不得留下任何行;
 *  3. 手续费已被扣走 —— 如实断言现状而不是假装它也回滚了。tryCharge 与 insertListing 分属两次独立提交,
 *     彻底消除要把扣费与落库裹进同一个跨存储事务 (属经济层改动, PR 描述里作遗留报备)。本次改动把损失面
 *     从"物品 + 手续费"收窄到"手续费";
 *  4. 反向的正常路径 (真 DAO 挂单成功后背包确实少了 count 个): 没有它, 把 shrink 整个删掉也能让第 1 条过。
 *
 * 二、market.list 的分页钳制 (与 market.history 同口径)。负 pageSize 会让 SQLite 的 {@code LIMIT -1} 变成
 * 不限行, 一次拉回<b>全服</b>所有 ACTIVE 挂单, 每一行还要 NbtIo.read 反序列化整个托管 ItemStack, 全程在
 * 服务器主线程上, 而这条 action 无冷却可被反复触发。造 105 行 (严格超过单页上限 100) 才分得开"钳住了"
 * 与"恰好全返"。
 *
 * template = "empty" (纯逻辑断言不依赖结构)。每条用例独立开内存库 + swap 门面, finally 关库 + 原样还原。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MarketPlaceAtomicityAndPagingGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "market";

    /** 卖家手里这一摞的数量; 挂单只挂其中 {@link #LISTED_COUNT} 个, 故"扣了没扣"看得见差额。 */
    private static final int STACK_COUNT = 64;
    private static final int LISTED_COUNT = 10;
    /** 挂单单价 (刻意低于钻石的代码预设锚 500, 使偏离费必为正, 手续费那条断言才不是在比 0 == 0)。 */
    private static final long UNIT_PRICE = 100L;

    /** 市场浏览的单页行数上限 (与 {@link MarketActions} 的 MAX_PAGE_SIZE 同值; 这里写死是因为那边是私有常量)。 */
    private static final int MAX_PAGE_SIZE = 100;
    /** 缺省页大小 (契约第 6 节 market.list 的 UI 友好默认)。 */
    private static final int DEFAULT_PAGE_SIZE = 20;
    /** 造的 ACTIVE 挂单行数, 必须严格大于单页上限, 否则"钳住了"与"恰好全返"分不开。 */
    private static final int ACTIVE_ROWS = 105;

    // ============================================================
    // 1. 落库失败: 物品必须还在卖家背包里 (shrink 排在 insertListing 之后)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void placeKeepsTheStackWhenInsertListingFails(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEconomy = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite realDao = MarketDb.on(ledger.connection());
        AtomicInteger insertAttempts = new AtomicInteger();
        MarketEngine engine = new MarketEngine(
                new InsertFailingMarketDao(realDao, insertAttempts), helper.getLevel().getServer());
        try {
            ServerPlayer seller = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            seller.getInventory().clearContent();
            seller.getInventory().setItem(0, new ItemStack(Items.DIAMOND, STACK_COUNT));
            EconomyServices.economyService().grant(seller, Currency.CREDIT, 1_000_000L);
            long funded = ledger.balance(seller.getUUID(), Currency.CREDIT);

            long expectedFee = MarketFee.listingFee(
                    DefaultBaseValues.resolve("minecraft:diamond"), UNIT_PRICE, LISTED_COUNT);
            helper.assertTrue(expectedFee > 0L,
                    "贱卖钻石 (锚 500 挂 " + UNIT_PRICE + ") 的挂单手续费必须为正, 否则下面的扣费断言在比 0 == 0");

            boolean storeFailureBubbled = false;
            try {
                engine.place(seller, 0, LISTED_COUNT, UNIT_PRICE, MarketConstants.CURRENCY_CREDIT);
            } catch (MarketStoreException storeFailure) {
                storeFailureBubbled = true;
            }
            helper.assertTrue(storeFailureBubbled,
                    "落库失败必须以 MarketStoreException 自然冒泡 (引擎内严禁 try-catch 生吞), 实际却当成挂单成功返回了");

            // 失败源确实是 insertListing 而不是更早的某一步 —— 否则"物品还在"是白捡的, 证明不了顺序。
            helper.assertTrue(insertAttempts.get() == 1,
                    "place 必须走到 insertListing 这一步恰一次 (实得 " + insertAttempts.get()
                            + " 次); 更早抛出的话本用例的探针是空的");

            // 本次改动的核心: 落库没成, 物品的唯一凭据就只剩卖家背包里这一摞, 一件都不许少。
            ItemStack afterFailure = seller.getInventory().getItem(0);
            helper.assertTrue(afterFailure.is(Items.DIAMOND),
                    "落库失败后原格必须还是那摞钻石, 实得 " + afterFailure);
            helper.assertTrue(afterFailure.getCount() == STACK_COUNT,
                    "落库失败后卖家那一摞一件不少 (应为 " + STACK_COUNT + ", 实得 " + afterFailure.getCount()
                            + ") —— 顺序回退成 shrink 在前, 这里会少掉 " + LISTED_COUNT + " 个且库里也没有");

            // 库里同样不许留下半行 (物品既不在包里也不在库里, 才是真正的凭空消失)。
            helper.assertTrue(realDao.listingsBySeller(seller.getUUID(), null).isEmpty(),
                    "落库失败的挂单不得留下任何 listing 行");
            helper.assertTrue(realDao.queryActive(null, "newest", 0, MAX_PAGE_SIZE).isEmpty(),
                    "全服 ACTIVE 挂单里也不得出现这一单");

            // 手续费如实断言现状: tryCharge 已提交, 落库失败不会把它退回来 (已知遗留, 见类注释)。
            helper.assertTrue(ledger.balance(seller.getUUID(), Currency.CREDIT) == funded - expectedFee,
                    "落库失败仍扣走了挂单手续费 " + expectedFee + " (扣费与落库分属两次独立提交, 已知遗留), 余额应为 "
                            + (funded - expectedFee) + ", 实为 " + ledger.balance(seller.getUUID(), Currency.CREDIT));

            helper.succeed();
        } finally {
            MarketDb.close(realDao);
            restoreEconomy(prevEconomy);
        }
    }

    // ============================================================
    // 2. 正常路径: 挂单成功后背包确实少了 count 个 (防"把 shrink 整个删掉"也能过第 1 条)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void placeStillEscrowsTheStackOnTheHappyPath(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEconomy = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite dao = MarketDb.on(ledger.connection());
        MarketEngine engine = new MarketEngine(dao, helper.getLevel().getServer());
        try {
            ServerPlayer seller = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            seller.getInventory().clearContent();
            seller.getInventory().setItem(0, new ItemStack(Items.DIAMOND, STACK_COUNT));
            EconomyServices.economyService().grant(seller, Currency.CREDIT, 1_000_000L);
            long funded = ledger.balance(seller.getUUID(), Currency.CREDIT);
            long expectedFee = MarketFee.listingFee(
                    DefaultBaseValues.resolve("minecraft:diamond"), UNIT_PRICE, LISTED_COUNT);

            MarketEngine.PlaceResult placed =
                    engine.place(seller, 0, LISTED_COUNT, UNIT_PRICE, MarketConstants.CURRENCY_CREDIT);

            ItemStack remainder = seller.getInventory().getItem(0);
            helper.assertTrue(remainder.getCount() == STACK_COUNT - LISTED_COUNT,
                    "挂单成功后原格精确少掉 " + LISTED_COUNT + " 个 (应剩 " + (STACK_COUNT - LISTED_COUNT)
                            + ", 实得 " + remainder.getCount() + ") —— 删掉 shrink 这条当场挂");
            helper.assertTrue(remainder.is(Items.DIAMOND),
                    "剩下的仍是同一种物品, 实得 " + remainder);

            List<ListingRow> mine = dao.listingsBySeller(seller.getUUID(), "ACTIVE");
            helper.assertTrue(mine.size() == 1,
                    "挂单成功必须恰好留下 1 行 ACTIVE 挂单, 实得 " + mine.size());
            ListingRow row = mine.get(0);
            helper.assertTrue(row.id() == placed.listingId(),
                    "落库行的 id 必须就是回执里的 " + placed.listingId() + ", 实得 " + row.id());
            helper.assertTrue("ACTIVE".equals(row.status()) && row.count() == LISTED_COUNT
                            && row.unitPrice() == UNIT_PRICE,
                    "落库行必须逐字对上这一单 (ACTIVE / " + LISTED_COUNT + " 个 / 单价 " + UNIT_PRICE
                            + "), 实得 " + row.status() + " / " + row.count() + " / " + row.unitPrice());
            helper.assertTrue("minecraft:diamond".equals(row.itemId()),
                    "落库标的是钻石, 实得 " + row.itemId());

            // 托管的 NBT 里是"收紧到挂单量"的那一摞, 不是卖家原来的整摞 —— 否则买家会收到 64 个。
            ItemStack escrow = MarketEngine.deserializeStack(row.itemNbt());
            helper.assertTrue(escrow.is(Items.DIAMOND) && escrow.getCount() == LISTED_COUNT,
                    "托管的 ItemStack 数量收紧为挂单量 " + LISTED_COUNT + ", 实得 " + escrow);

            // 背包扣的与库里托管的必须是同一批货: 两者相加恰好等于挂单前那一摞。
            helper.assertTrue(remainder.getCount() + escrow.getCount() == STACK_COUNT,
                    "背包剩下的 (" + remainder.getCount() + ") 加托管的 (" + escrow.getCount()
                            + ") 必须守恒等于原有的 " + STACK_COUNT + " 个");

            helper.assertTrue(placed.listFee() == expectedFee
                            && ledger.balance(seller.getUUID(), Currency.CREDIT) == funded - expectedFee,
                    "挂单成功照收手续费 " + expectedFee + " (上单即收 sink), 回执 " + placed.listFee()
                            + ", 余额 " + ledger.balance(seller.getUUID(), Currency.CREDIT));

            helper.succeed();
        } finally {
            MarketDb.close(dao);
            restoreEconomy(prevEconomy);
        }
    }

    // ============================================================
    // 3. market.list 分页钳制: 负/零 pageSize 不得变成"把全服挂单一次拉回来"
    // ============================================================

    /**
     * 断言按两层写: 一层是不依赖库存量的不变量 (任何入参下返回行数 &lt;= 100 且不抛), 另一层是钳制后的确切值
     * (pageSize=-1/0 钳到 1, 1000 钳到 100, page=-3 落回第 0 页)。前者对"库里恰好有多少行"免疫, 后者才真正
     * 分得开"钳住了"与"库里本来就不多"。
     *
     * 挂单行刻意登记在一个与调用者无关的 UUID 名下: market.list 查的是<b>全服</b> ACTIVE 挂单, 不是"我的挂单",
     * 这也正是不限行时的危害面 —— 拉回的是全服的量, 而不只是调用者自己的那几行。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void listClampsPagingInsteadOfDumpingEveryActiveListing(GameTestHelper helper) {
        MarketDaoSqlite dao = MarketDb.openInMemory();
        MarketEngine engine = new MarketEngine(dao, helper.getLevel().getServer());
        MarketEngine prevMarket = swapMarket(engine);
        try {
            ServerPlayer viewer = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

            int baselineRows = handle(MarketActions.LIST, viewer, pagePayload(0, MAX_PAGE_SIZE))
                    .getAsJsonArray("listings").size();
            helper.assertTrue(baselineRows == 0,
                    "本用例开的是独立内存库, 造数据前应无任何 ACTIVE 挂单, 实得 " + baselineRows);

            // 别人的 105 条在售挂单 (刻意超过单页上限 100)。created_at 逐条递增, 使默认的 created_at DESC 排序确定。
            UUID stranger = UUID.randomUUID();
            byte[] escrowNbt = MarketEngine.serializeStack(new ItemStack(Items.DIAMOND, 1));
            long firstId = 0L;
            long newestId = 0L;
            for (int i = 0; i < ACTIVE_ROWS; i++) {
                long id = dao.insertListing(stranger, "Stranger", "minecraft:diamond", escrowNbt,
                        1, 10L + i, MarketConstants.CURRENCY_CREDIT, 1_000L + i);
                if (i == 0) {
                    firstId = id;
                }
                newestId = id;
            }
            helper.assertTrue(firstId != newestId,
                    "造数据必须落成 " + ACTIVE_ROWS + " 行不同的挂单, 实得首尾同一个 id " + firstId);

            // --- pageSize = -1: SQLite 的 LIMIT -1 等于不限行, 未钳制会一次拉回全服 105 行 ---
            JsonObject unlimited = handle(MarketActions.LIST, viewer, pagePayload(0, -1));
            JsonArray unlimitedRows = unlimited.getAsJsonArray("listings");
            helper.assertTrue(unlimitedRows.size() <= MAX_PAGE_SIZE,
                    "任何入参下单次返回行数都不得超过 " + MAX_PAGE_SIZE + ", 实得 " + unlimitedRows.size());
            helper.assertTrue(unlimitedRows.size() == 1,
                    "pageSize=-1 下钳到 1 行, 实得 " + unlimitedRows.size()
                            + " (未钳制时这里会是全服 " + ACTIVE_ROWS + " 行)");
            helper.assertTrue(unlimited.get("pageSize").getAsInt() == 1,
                    "回执必须回显钳制后的 pageSize 而不是原样回显 -1, 实得 " + unlimited.get("pageSize"));
            helper.assertTrue(unlimitedRows.get(0).getAsJsonObject().get("id").getAsLong() == newestId,
                    "钳的是行数不是排序, 首行仍是最新那条 (id=" + newestId + "), 实得 "
                            + unlimitedRows.get(0).getAsJsonObject().get("id"));

            // --- pageSize = 0: 同样下钳到 1 (回空表会让前端以为全服无人挂单) ---
            JsonObject zero = handle(MarketActions.LIST, viewer, pagePayload(0, 0));
            helper.assertTrue(zero.get("pageSize").getAsInt() == 1
                            && zero.getAsJsonArray("listings").size() == 1,
                    "pageSize=0 下钳到 1 行, 实得 pageSize=" + zero.get("pageSize")
                            + " 行数=" + zero.getAsJsonArray("listings").size());

            // --- pageSize = 1000: 上钳到 100, 且必须严格少于全部 105 行 ---
            JsonObject huge = handle(MarketActions.LIST, viewer, pagePayload(0, 1_000));
            JsonArray hugeRows = huge.getAsJsonArray("listings");
            helper.assertTrue(huge.get("pageSize").getAsInt() == MAX_PAGE_SIZE,
                    "pageSize=1000 上钳到 " + MAX_PAGE_SIZE + " (与单页上限同口径), 实得 " + huge.get("pageSize"));
            helper.assertTrue(hugeRows.size() == MAX_PAGE_SIZE,
                    "上钳之后只返回 " + MAX_PAGE_SIZE + " 行, 实得 " + hugeRows.size());
            helper.assertTrue(hugeRows.size() < ACTIVE_ROWS,
                    "必须严格少于全服 " + ACTIVE_ROWS + " 行, 否则钳制根本没生效");

            // --- page = -3: 不抛, 且落回第 0 页 (负 offset 在 SQLite 里被当 0 用, 不如在入口收住) ---
            JsonObject negativePage = handle(MarketActions.LIST, viewer, pagePayload(-3, 5));
            JsonArray negativeRows = negativePage.getAsJsonArray("listings");
            helper.assertTrue(negativePage.get("page").getAsInt() == 0,
                    "page 下钳到 0, 实得 " + negativePage.get("page") + " (未钳制时原样回显 -3)");
            helper.assertTrue(negativeRows.size() == 5,
                    "落回第 0 页后照常返回 5 行, 实得 " + negativeRows.size());
            JsonArray firstPageRows = handle(MarketActions.LIST, viewer, pagePayload(0, 5))
                    .getAsJsonArray("listings");
            helper.assertTrue(idsOf(negativeRows).equals(idsOf(firstPageRows)),
                    "负页码取回的必须逐行等于第 0 页, 实得 " + idsOf(negativeRows) + " vs " + idsOf(firstPageRows));

            // --- 缺省分页不得被钳制误伤 ---
            JsonObject byDefault = handle(MarketActions.LIST, viewer, new JsonObject());
            helper.assertTrue(byDefault.get("page").getAsInt() == 0
                            && byDefault.get("pageSize").getAsInt() == DEFAULT_PAGE_SIZE,
                    "缺省分页仍是 page=0 pageSize=" + DEFAULT_PAGE_SIZE + ", 实得 "
                            + byDefault.get("page") + "/" + byDefault.get("pageSize"));
            helper.assertTrue(byDefault.getAsJsonArray("listings").size() == DEFAULT_PAGE_SIZE,
                    "缺省页返回 " + DEFAULT_PAGE_SIZE + " 行, 实得 " + byDefault.getAsJsonArray("listings").size());

            // --- 正常翻页不受影响: 第 1 页接着第 0 页往下, 不重不漏 ---
            JsonArray secondPageRows = handle(MarketActions.LIST, viewer, pagePayload(1, 5))
                    .getAsJsonArray("listings");
            helper.assertTrue(secondPageRows.size() == 5,
                    "第 1 页照常 5 行, 实得 " + secondPageRows.size());
            helper.assertTrue(Collections.disjoint(idsOf(firstPageRows), idsOf(secondPageRows)),
                    "钳制不得把 offset 算错 (两页不得有重叠行), 实得 " + idsOf(firstPageRows)
                            + " 与 " + idsOf(secondPageRows));

            helper.succeed();
        } finally {
            restoreMarket(prevMarket);
            MarketDb.close(dao);
        }
    }

    // ============================================================
    // 工具
    // ============================================================

    /**
     * 只有 {@code insertListing} 必抛的 DAO 替身 (范式同 {@code FarmerWebUiGameTests.PayoutFailingEconomy}):
     * 其余方法一律原样转发给真 DAO, 保证被测路径上只有这一处失败源, 断言到的中间态就只可能由它造成。
     *
     * {@code insertAttempts} 不是装饰: 没有它, "物品还在背包里"这条断言在 place 更早的某一步抛出时同样成立,
     * 用例就变成了白捡的绿。
     */
    private record InsertFailingMarketDao(MarketDao delegate, AtomicInteger insertAttempts) implements MarketDao {

        @Override
        public long insertListing(UUID seller, String sellerName, String itemId, byte[] nbt,
                                  int count, long unitPrice, String currency, long createdAt) {
            insertAttempts.incrementAndGet();
            // 与真 DAO 的包装纪律一致: 底层 SQLException 转译成领域异常上抛 (磁盘满 / 库被锁)。
            throw new MarketStoreException("模拟 insertListing 落库失败 (磁盘满 / 库被锁)",
                    new SQLException("simulated disk I/O error"));
        }

        @Override
        public ListingRow findListing(long id) {
            return delegate.findListing(id);
        }

        @Override
        public List<ListingRow> queryActive(String itemFilterOrNull, String sortKey, int offset, int limit) {
            return delegate.queryActive(itemFilterOrNull, sortKey, offset, limit);
        }

        @Override
        public List<ListingRow> listingsBySeller(UUID seller, String statusOrNull) {
            return delegate.listingsBySeller(seller, statusOrNull);
        }

        @Override
        public boolean markSold(long id) {
            return delegate.markSold(id);
        }

        @Override
        public boolean markCancelled(long id, UUID seller) {
            return delegate.markCancelled(id, seller);
        }

        @Override
        public boolean reduceListing(long id, int newCount, byte[] newNbt) {
            return delegate.reduceListing(id, newCount, newNbt);
        }

        @Override
        public void insertTxn(long listingId, UUID buyer, UUID seller, String itemId, int count,
                              long unitPrice, long total, long fee, long createdAt) {
            delegate.insertTxn(listingId, buyer, seller, itemId, count, unitPrice, total, fee, createdAt);
        }

        @Override
        public int soldOrListedCountToday(UUID seller, Set<String> itemIds, long dayStartEpoch) {
            return delegate.soldOrListedCountToday(seller, itemIds, dayStartEpoch);
        }

        @Override
        public SoldOrListedSplit soldOrListedSplitToday(UUID seller, Set<String> itemIds, long dayStartEpoch) {
            return delegate.soldOrListedSplitToday(seller, itemIds, dayStartEpoch);
        }

        @Override
        public List<TxnRow> transactionsByPlayer(UUID player, int offset, int limit) {
            return delegate.transactionsByPlayer(player, offset, limit);
        }

        @Override
        public int transactionsCountByPlayer(UUID player) {
            return delegate.transactionsCountByPlayer(player);
        }

        @Override
        public void insertPendingPayout(UUID seller, long amount, String currency, long createdAt) {
            delegate.insertPendingPayout(seller, amount, currency, createdAt);
        }

        @Override
        public List<long[]> drainPendingPayout(UUID seller) {
            return delegate.drainPendingPayout(seller);
        }

        @Override
        public List<long[]> peekPendingPayout(UUID seller) {
            return delegate.peekPendingPayout(seller);
        }

        @Override
        public void upsertBaseValue(String itemId, long v0, String updatedBy, long updatedAt) {
            delegate.upsertBaseValue(itemId, v0, updatedBy, updatedAt);
        }

        @Override
        public Long getBaseValue(String itemId) {
            return delegate.getBaseValue(itemId);
        }

        @Override
        public Map<String, Long> allBaseValues() {
            return delegate.allBaseValues();
        }
    }

    /** 一页回执里的挂单 id 列表 (按返回顺序), 供跨页比对不重不漏。 */
    private static List<Long> idsOf(JsonArray listings) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < listings.size(); i++) {
            ids.add(listings.get(i).getAsJsonObject().get("id").getAsLong());
        }
        return ids;
    }

    private static JsonObject handle(WebUiAction action, ServerPlayer sender, JsonObject payload) {
        return JsonParser.parseString(action.handle(sender, payload)).getAsJsonObject();
    }

    private static JsonObject pagePayload(int page, int pageSize) {
        JsonObject payload = new JsonObject();
        payload.addProperty("page", page);
        payload.addProperty("pageSize", pageSize);
        return payload;
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

    private static MarketEngine swapMarket(MarketEngine fake) {
        MarketEngine prev = MarketServices.isRegistered() ? MarketServices.marketEngine() : null;
        MarketServices.registerMarketEngine(fake);
        return prev;
    }

    private static void restoreMarket(MarketEngine prev) {
        if (prev != null) {
            MarketServices.registerMarketEngine(prev);
        } else {
            MarketServices.reset();
        }
    }

    private static Function<UUID, PlayerAbuseState> newStateResolver() {
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        return id -> states.computeIfAbsent(id, k -> new PlayerAbuseState());
    }
}

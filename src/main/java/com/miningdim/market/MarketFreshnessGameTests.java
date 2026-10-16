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
import com.miningdim.freshness.FreshnessCategories;
import com.miningdim.freshness.FreshnessData;
import com.miningdim.freshness.FreshnessNested;
import com.miningdim.market.store.ListingRow;
import com.miningdim.market.store.MarketDaoSqlite;
import com.miningdim.market.store.MarketDb;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 市场的腐烂消费点 GameTest (规格「消费点改造 · 市场」)。与 {@link MarketW2GameTests} 同 batch、同款内存 SQLite
 * (账本与市场表同连接) 与经济/市场门面 swap-restore 范式, 直接调引擎与 action 拿回执解析断言。
 *
 * 每条用例守的是这条接线上**唯一的失败模式**:
 * <ol>
 *   <li><b>阈值三分</b>: 不足 6 小时拒 (且拒的时候钱和货都不许动)、不足 12 小时只提示、够 12 小时不提示;
 *       不腐烂的东西 (石头) 既不拒也不提示 —— 把"没有易腐层"当成"剩余 0"是这条路上最容易犯的错;</li>
 *   <li><b>下钻</b>: 潜影盒本身不腐烂, 里面那件剩 3 小时的面包必须能拦下整单 —— 不下钻的话把烂货塞进盒子
 *       就能绕过全部消费点; 同时守盖章真的写回容器 NBT (读出来的是副本, 不回写等于什么都没干);</li>
 *   <li><b>购买拒收但不动挂单</b>: 拒绝时挂单必须还 ACTIVE、买家一分钱不扣 (挂单被顺手改状态是这里唯一
 *       不可挽回的失败模式);</li>
 *   <li><b>浏览隐藏 / 我的挂单标注</b>: 同一份托管物在两个页面必须给出同一个结论 (只在一个页面上过滤,
 *       玩家就会看到"列表里没有、我的挂单里还在"的假象);</li>
 *   <li><b>预判与硬拒同源</b>: market.tradable 说不新鲜, {@link MarketEngine#place} 必须也拒;</li>
 *   <li><b>30 天巡检</b>: 只关烂够 30 天的, 且按间隔节流 (把"刚烂一天"的也关掉, 等于替玩家销毁还没到期的货)。</li>
 * </ol>
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MarketFreshnessGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "market";

    /** 上架单价与数量 (挑小数字: 挂单费 = 20% x 单价 x 数量, 扣费是否发生一眼看得出)。 */
    private static final long UNIT_PRICE = 10L;
    private static final int LIST_COUNT = 1;

    // ============================================================
    // 1. 上架阈值三分
    // ============================================================

    /**
     * 上架的新鲜度三分: 剩 5 小时拒 (码 FRESHNESS_TOO_SHORT, 钱货不动)、剩 10 小时挂上但提示、剩 30 小时
     * 静默通过; 不腐烂的石头既不拒也不提示。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void placeRejectsTooShortWarnsOnNearMissAndIgnoresNonPerishables(GameTestHelper helper) {
        Fixture fx = Fixture.open(helper);
        try {
            ServerPlayer seller = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            long now = fx.engine.nowMinute();
            EconomyServices.economyService().grant(seller, Currency.CREDIT, 1_000L);

            // 剩 5 小时: 拒。拒绝必须发生在扣费与扣货之前 —— 钱少一分、货少一件都是玩家看得见的损失。
            ItemStack shortBread = stampedFood(Items.BREAD, 1, now, 5L * 60L, helper);
            seller.getInventory().setItem(0, shortBread);
            long before = EconomyServices.economyService().creditBalance(seller);
            WebUiBusinessException rejected = expectRejection(helper, fx.engine, seller, 0);
            helper.assertTrue(WebUiErrorCodes.FRESHNESS_TOO_SHORT.equals(rejected.errorCode()),
                    "剩 5 小时应当以「来不及卖」拒 (FRESHNESS_TOO_SHORT), 实得 " + rejected.errorCode());
            helper.assertTrue(EconomyServices.economyService().creditBalance(seller) == before,
                    "被拒的上架不许扣挂单费, 实得余额 " + EconomyServices.economyService().creditBalance(seller)
                            + " (原本 " + before + ")");
            helper.assertTrue(seller.getInventory().getItem(0).getCount() == 1,
                    "被拒的上架不许动库存, 实得 " + seller.getInventory().getItem(0).getCount());

            // 剩 10 小时: 能挂, 但回执必须带提示。
            seller.getInventory().setItem(1, stampedFood(Items.BREAD, 1, now, 10L * 60L, helper));
            MarketEngine.PlaceResult warn = fx.engine.place(seller, 1, LIST_COUNT, UNIT_PRICE,
                    MarketConstants.CURRENCY_CREDIT);
            helper.assertTrue(warn.freshWarning(), "剩 10 小时要提示「可能来不及卖出」, 实得 minMinutes="
                    + warn.freshMinMinutes());
            helper.assertTrue(warn.freshMinMinutes() >= 9L * 60L && warn.freshMinMinutes() <= 10L * 60L,
                    "回执里的最少剩余应落在 9~10 小时 (整点章最多差一小时), 实得 " + warn.freshMinMinutes());

            // 剩 30 小时: 静默通过 (提示只在 6~12 小时之间出现)。
            seller.getInventory().setItem(2, stampedFood(Items.BREAD, 1, now, 30L * 60L, helper));
            MarketEngine.PlaceResult quiet = fx.engine.place(seller, 2, LIST_COUNT, UNIT_PRICE,
                    MarketConstants.CURRENCY_CREDIT);
            helper.assertTrue(!quiet.freshWarning(),
                    "剩 30 小时不该提示, 实得 minMinutes=" + quiet.freshMinMinutes());

            // 石头: 不腐烂 -> 既不拒也不提示 (把"没有易腐层"当成"剩余 0"会误伤所有工具与矿物)。
            seller.getInventory().setItem(3, new ItemStack(Items.STONE, 64));
            MarketEngine.PlaceResult stone = fx.engine.place(seller, 3, 64, UNIT_PRICE,
                    MarketConstants.CURRENCY_CREDIT);
            helper.assertTrue(!stone.freshWarning(), "石头不该提示来不及卖");
            helper.assertTrue(stone.freshMinMinutes() == Long.MAX_VALUE,
                    "没有易腐层时最少剩余是 MAX (不是 0), 实得 " + stone.freshMinMinutes());
            helper.succeed();
        } finally {
            fx.close();
        }
    }

    // ============================================================
    // 2. 嵌套容器下钻与盖章写回
    // ============================================================

    /**
     * 潜影盒里的东西必须一起判、一起盖章。
     *
     * 两件事: (a) 盒子里那件剩 3 小时的面包要能拦下整单 (盒子自己不会烂, 不下钻就整包过关);
     * (b) 挂上去的那一单里, **盒子里**的面包也必须带上章 —— {@link ItemStack#of} 读出来的内容物是副本,
     * 只给副本盖章等于什么都没做, 托管期间那件面包就会"永不腐烂"。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void placeDescendsIntoContainersAndStampsTheNestedCopies(GameTestHelper helper) {
        Fixture fx = Fixture.open(helper);
        try {
            ServerPlayer seller = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            long now = fx.engine.nowMinute();
            EconomyServices.economyService().grant(seller, Currency.CREDIT, 1_000L);

            // (a) 盒子里剩 3 小时 -> 整单拒 (潜影盒本身不在任何易腐类别里)。
            seller.getInventory().setItem(0,
                    shulkerWith(stampedFood(Items.BREAD, 1, now, 3L * 60L, helper)));
            WebUiBusinessException rejected = expectRejection(helper, fx.engine, seller, 0);
            helper.assertTrue(WebUiErrorCodes.FRESHNESS_TOO_SHORT.equals(rejected.errorCode()),
                    "盒子里的面包剩 3 小时, 整单该拒, 实得 " + rejected.errorCode());

            // (b) 盒子里剩 30 小时 -> 能挂, 且盒子里那一件必须真的被盖上章。
            ItemStack nested = stampedFood(Items.BREAD, 1, now, 30L * 60L, helper);
            helper.assertTrue(FreshnessData.isStamped(nested), "夹具本身先要有章 (下面要比的是批次变没变)");
            long batchBefore = FreshnessData.batchMinute(nested);
            seller.getInventory().setItem(1, shulkerWith(nested));
            MarketEngine.PlaceResult placed = fx.engine.place(seller, 1, LIST_COUNT, UNIT_PRICE,
                    MarketConstants.CURRENCY_CREDIT);

            ListingRow row = fx.dao.findListing(placed.listingId());
            ItemStack escrow = MarketEngine.deserializeStack(row.itemNbt());
            helper.assertTrue(!escrow.isEmpty(), "托管物必须能反序列化回来");
            helper.assertTrue(!FreshnessData.isStamped(escrow),
                    "潜影盒自己不是易腐品, 不该被盖章 (章只盖在会烂的东西上)");
            java.util.List<ItemStack> layers = FreshnessNested.flatten(escrow);
            helper.assertTrue(layers.size() == 2, "托管物应当展开成「盒子 + 里面那件」两层, 实得 " + layers.size());
            ItemStack nestedEscrow = layers.get(1);
            helper.assertTrue(FreshnessData.isStamped(nestedEscrow),
                    "盒子里的面包必须带章 —— 不带章的货在托管期间永不腐烂, 市场就成了冰箱");
            helper.assertTrue(FreshnessData.batchMinute(nestedEscrow) == batchBefore,
                    "已经有章的层不该被重盖章 (批次时刻应当原样保留), 实得 "
                            + FreshnessData.batchMinute(nestedEscrow) + " 原本 " + batchBefore);
            long remaining = FreshnessNested.minRemainingMinutes(escrow, now);
            helper.assertTrue(remaining >= 29L * 60L && remaining <= 30L * 60L,
                    "托管物的最少剩余应落在 29~30 小时, 实得 " + remaining);
            helper.succeed();
        } finally {
            fx.close();
        }
    }

    // ============================================================
    // 3. 购买拒收
    // ============================================================

    /**
     * 买入一单烂货: 拒绝要带 FRESHNESS_ROTTEN, 且**挂单还 ACTIVE、买家一分钱没花** —— 拒绝时顺手改状态或
     * 扣款是这条路上不可挽回的两种损失 (货与钱至少要有一样还在原处)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buyRefusesRottenEscrowWithoutTouchingTheListing(GameTestHelper helper) {
        Fixture fx = Fixture.open(helper);
        try {
            ServerPlayer seller = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            ServerPlayer buyer = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            long now = fx.engine.nowMinute();
            EconomyServices.economyService().grant(buyer, Currency.CREDIT, 1_000L);

            ItemStack rotten = new ItemStack(Items.BREAD, 1);
            stampWithRemaining(rotten, now, -60L); // 烂了一小时
            long rottenId = insertListing(fx.dao, seller, rotten, 1, now);

            long balanceBefore = EconomyServices.economyService().creditBalance(buyer);
            WebUiBusinessException rejected = buyRejection(helper, fx.engine, buyer, rottenId);
            helper.assertTrue(WebUiErrorCodes.FRESHNESS_ROTTEN.equals(rejected.errorCode()),
                    "烂货必须以 FRESHNESS_ROTTEN 拒, 实得 " + rejected.errorCode());
            helper.assertTrue("true".equals(rejected.params().get("listingId")) == false
                            && Long.toString(rottenId).equals(rejected.params().get("listingId")),
                    "拒绝参数里要带上挂单号 (前端据此把提示挂到那一行), 实得 " + rejected.params());
            helper.assertTrue(EconomyServices.economyService().creditBalance(buyer) == balanceBefore,
                    "被拒的购买不许扣钱, 实得余额 " + EconomyServices.economyService().creditBalance(buyer));
            ListingRow stillThere = fx.dao.findListing(rottenId);
            helper.assertTrue("ACTIVE".equals(stillThere.status()),
                    "被拒之后挂单必须还是 ACTIVE (卖家自己撤), 实得 " + stillThere.status());
            helper.assertTrue(stillThere.count() == 1, "被拒之后挂单数量不许变, 实得 " + stillThere.count());

            // 对照: 同一张单子换成新鲜的面包就能买走 —— 证明上面拒的是"烂", 不是"这条路径坏了"。
            ItemStack fresh = new ItemStack(Items.BREAD, 1);
            stampWithRemaining(fresh, now, 30L * 60L);
            long freshId = insertListing(fx.dao, seller, fresh, 1, now);
            MarketEngine.BuyResult bought = fx.engine.buy(buyer, freshId, 1);
            helper.assertTrue(bought.count() == 1, "新鲜的那单应当能买, 实得 " + bought.count());
            helper.succeed();
        } finally {
            fx.close();
        }
    }

    // ============================================================
    // 4. 浏览隐藏 / 我的挂单标注
    // ============================================================

    /**
     * 一单新鲜一单烂: market.list 只显示新鲜的 (并报出隐去了几条), market.mine 两条都显示且烂的那条
     * 带 freshRotten=true —— 卖家要能看见它、自己撤单, 但不能让它出现在买家的列表里。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void listHidesRottenRowsWhileMineStillMarksThem(GameTestHelper helper) {
        Fixture fx = Fixture.open(helper);
        try {
            ServerPlayer seller = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            ServerPlayer viewer = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            long now = fx.engine.nowMinute();

            ItemStack fresh = new ItemStack(Items.BREAD, 1);
            stampWithRemaining(fresh, now, 20L * 60L);
            long freshId = insertListing(fx.dao, seller, fresh, 1, now);
            ItemStack rotten = new ItemStack(Items.BREAD, 1);
            stampWithRemaining(rotten, now, -120L);
            long rottenId = insertListing(fx.dao, seller, rotten, 1, now);

            JsonObject list = handle(MarketActions.LIST, viewer, new JsonObject());
            JsonArray rows = list.getAsJsonArray("listings");
            helper.assertTrue(rows.size() == 1, "浏览页只该显示新鲜的那一单, 实得 " + rows.size());
            helper.assertTrue(list.get("hiddenRotten").getAsInt() == 1,
                    "被隐去的烂单数要如实上报 (前端才能说清「还有货只是烂了」), 实得 "
                            + list.get("hiddenRotten"));
            JsonObject freshRow = rows.get(0).getAsJsonObject();
            helper.assertTrue(freshRow.get("id").getAsLong() == freshId,
                    "留下的应当是新鲜那一单, 实得 " + freshRow.get("id"));
            helper.assertTrue(!freshRow.get("freshRotten").getAsBoolean(), "新鲜的那单不该标已腐烂");
            helper.assertTrue(!freshRow.get("freshUntimed").getAsBoolean(), "面包是有保质期的, 不是 untimed");
            double hours = freshRow.get("freshHours").getAsDouble();
            helper.assertTrue(hours >= 19.0D && hours <= 20.0D,
                    "剩余小时应当现算并下发 (19~20 之间), 实得 " + hours);

            JsonObject mine = handle(MarketActions.MINE, seller, new JsonObject());
            JsonArray mineRows = mine.getAsJsonArray("listings");
            helper.assertTrue(mineRows.size() == 2, "我的挂单要两条都在, 实得 " + mineRows.size());
            boolean sawRotten = false;
            boolean sawFresh = false;
            for (int i = 0; i < mineRows.size(); i++) {
                JsonObject row = mineRows.get(i).getAsJsonObject();
                if (row.get("id").getAsLong() == rottenId) {
                    sawRotten = true;
                    helper.assertTrue(row.get("freshRotten").getAsBoolean(), "烂的那条要标已腐烂");
                    helper.assertTrue(row.get("freshHours").getAsDouble() == 0.0D,
                            "已腐烂的剩余小时显示 0, 实得 " + row.get("freshHours"));
                }
                if (row.get("id").getAsLong() == freshId) {
                    sawFresh = true;
                    helper.assertTrue(!row.get("freshRotten").getAsBoolean(), "新鲜那条不该被标烂");
                }
            }
            helper.assertTrue(sawRotten && sawFresh, "我的挂单必须同时含两条 (烂 " + sawRotten + " / 新 " + sawFresh + ")");
            helper.succeed();
        } finally {
            fx.close();
        }
    }

    // ============================================================
    // 5. 预判与硬拒同源
    // ============================================================

    /**
     * market.tradable 与 {@link MarketEngine#place} 对同一格东西必须给同一个结论:
     * 剩 5 小时 -> 预判 tradable=false + FRESHNESS_TOO_SHORT, 硬挂也拒; 剩 10 小时 -> 预判 true + 提示。
     *
     * 只灰前端而服务端放行 (或反过来) 会让页面上的规则变成假的 —— 命令行照挂不误。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tradablePreviewAndPlaceAgreeOnFreshness(GameTestHelper helper) {
        Fixture fx = Fixture.open(helper);
        try {
            ServerPlayer seller = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            long now = fx.engine.nowMinute();
            EconomyServices.economyService().grant(seller, Currency.CREDIT, 1_000L);

            seller.getInventory().setItem(0, stampedFood(Items.BREAD, 1, now, 5L * 60L, helper));
            JsonObject shortPreview = handle(MarketActions.TRADABLE, seller, slotPayload(0));
            helper.assertTrue(!shortPreview.get("tradable").getAsBoolean(),
                    "剩 5 小时的货预判必须是不能挂");
            helper.assertTrue("FRESHNESS_TOO_SHORT".equals(shortPreview.get("reasonCode").getAsString()),
                    "预判的理由码应当是 FRESHNESS_TOO_SHORT (前端按值分句), 实得 "
                            + shortPreview.get("reasonCode"));
            WebUiBusinessException rejected = expectRejection(helper, fx.engine, seller, 0);
            helper.assertTrue(WebUiErrorCodes.FRESHNESS_TOO_SHORT.equals(rejected.errorCode()),
                    "预判说不能挂, 硬挂也必须拒同一个理由, 实得 " + rejected.errorCode());

            seller.getInventory().setItem(1, stampedFood(Items.BREAD, 1, now, 10L * 60L, helper));
            JsonObject warnPreview = handle(MarketActions.TRADABLE, seller, slotPayload(1));
            helper.assertTrue(warnPreview.get("tradable").getAsBoolean(),
                    "剩 10 小时仍然能挂 (只是提示), 实得 " + warnPreview.get("reasonCode"));
            helper.assertTrue(warnPreview.get("freshWarning").getAsBoolean(),
                    "预判也要给出「可能来不及卖出」的提示");
            helper.succeed();
        } finally {
            fx.close();
        }
    }

    // ============================================================
    // 6. 30 天巡检
    // ============================================================

    /**
     * 巡检只关**烂够 30 天**的挂单 (状态 EXPIRED), 且按间隔节流: 刚烂一天的不许被顺手销毁。
     * 同一趟里"已腐烂但还没到期"的那条仍留在卖家的腐烂清单里 (登录通知与自动关闭是两件事)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sweepClosesOnlyWhatHasBeenRottenForThirtyDays(GameTestHelper helper) {
        Fixture fx = Fixture.open(helper);
        try {
            ServerPlayer seller = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            long now = fx.engine.nowMinute();

            ItemStack ancient = new ItemStack(Items.BREAD, 1);
            stampWithRemaining(ancient, now, -(40L * 24L * 60L));
            long ancientId = insertListing(fx.dao, seller, ancient, 1, now);
            ItemStack recent = new ItemStack(Items.BREAD, 1);
            stampWithRemaining(recent, now, -(24L * 60L));
            long recentId = insertListing(fx.dao, seller, recent, 1, now);

            MarketRottenSweep.resetForTest();
            int closed = MarketRottenSweep.tick(fx.dao, helper.getLevel().getServer(), 1_000L, now);
            helper.assertTrue(closed == 1, "只该关掉烂满 30 天的那一单, 实得 " + closed);
            helper.assertTrue(MarketRottenSweep.STATUS_EXPIRED.equals(fx.dao.findListing(ancientId).status()),
                    "烂了 40 天的挂单状态应为 EXPIRED, 实得 " + fx.dao.findListing(ancientId).status());
            helper.assertTrue("ACTIVE".equals(fx.dao.findListing(recentId).status()),
                    "刚烂一天的不许被关 (它还有撤单挽回的余地), 实得 " + fx.dao.findListing(recentId).status());

            // 节流: 间隔没到就再跑一次, 什么都不做。
            helper.assertTrue(MarketRottenSweep.tick(fx.dao, helper.getLevel().getServer(), 1_100L, now) == 0,
                    "没到巡检间隔不许再扫一遍");

            // 登录通知的口径: 已腐烂的 ACTIVE 挂单算 1 条 (被关掉的那条不算 —— 它已经不是挂单了)。
            helper.assertTrue(MarketRottenSweep.rottenOfSeller(fx.dao, seller.getUUID(), now).size() == 1,
                    "卖家名下已腐烂的挂单应当只剩 1 条");
            helper.assertTrue(MarketRottenSweep.rottenOfSeller(fx.dao, UUID.randomUUID(), now).isEmpty(),
                    "别人的挂单不该算进这个卖家的通知里");
            helper.succeed();
        } finally {
            fx.close();
        }
    }

    // ============================================================
    // 夹具
    // ============================================================

    /** 一趟用例的全部外部依赖 (内存账本 + 市场 DAO + 引擎), close 时原样还原门面。 */
    private record Fixture(SqliteEconomyLedger ledger, MarketDaoSqlite dao, MarketEngine engine,
                           IEconomyService previousEconomy, MarketEngine previousMarket) {

        static Fixture open(GameTestHelper helper) {
            SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
            IEconomyService previousEconomy = EconomyServices.isRegistered()
                    ? EconomyServices.economyService() : null;
            EconomyServices.registerEconomyService(
                    new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
            MarketDaoSqlite dao = MarketDb.on(ledger.connection());
            MarketEngine previousMarket = MarketServices.isRegistered() ? MarketServices.marketEngine() : null;
            MarketEngine engine = new MarketEngine(dao, helper.getLevel().getServer());
            MarketServices.registerMarketEngine(engine);
            return new Fixture(ledger, dao, engine, previousEconomy, previousMarket);
        }

        void close() {
            if (previousMarket != null) {
                MarketServices.registerMarketEngine(previousMarket);
            } else {
                MarketServices.reset();
            }
            MarketDb.close(dao);
            if (previousEconomy != null) {
                EconomyServices.registerEconomyService(previousEconomy);
            } else {
                EconomyServices.reset();
            }
        }
    }

    /**
     * 一件「盖了章、剩余正好多少分钟」的易腐品。剩余 = 批次 + 停表 + 保质期 − 现在, 所以批次时刻由剩余反推;
     * 负数表示已经烂了多久。
     */
    private static ItemStack stampedFood(Item item, int count, long nowMinute, long remainingMinutes,
                                        GameTestHelper helper) {
        ItemStack stack = new ItemStack(item, count);
        int shelfLife = FreshnessCategories.shelfLifeHours(stack);
        helper.assertTrue(shelfLife > 0, item + " 必须是易腐品 (夹具前提), 实得保质期 " + shelfLife);
        stampWithRemaining(stack, nowMinute, remainingMinutes);
        return stack;
    }

    private static void stampWithRemaining(ItemStack stack, long nowMinute, long remainingMinutes) {
        int shelfLife = FreshnessCategories.shelfLifeHours(stack);
        FreshnessData.stampAt(stack, nowMinute - ((long) shelfLife * 60L - remainingMinutes), 0);
    }


    /** 直接往库里塞一单 (跳过 place: 这些用例要的正是"库里已经有一单"的起点)。 */
    private static long insertListing(MarketDaoSqlite dao, ServerPlayer seller, ItemStack escrow, int count,
                                      long nowMinute) {
        return dao.insertListing(seller.getUUID(), seller.getName().getString(), MarketEngine.itemIdOf(escrow),
                MarketEngine.serializeStack(escrow), count, UNIT_PRICE, MarketConstants.CURRENCY_CREDIT,
                System.currentTimeMillis());
    }

    /** 造一个装着这些物品的潜影盒 (内容物存在 BlockEntityTag.Items 里, 与走私者手改 NBT 同形状)。 */
    private static ItemStack shulkerWith(ItemStack... contents) {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        ListTag items = new ListTag();
        for (int i = 0; i < contents.length; i++) {
            CompoundTag entry = contents[i].save(new CompoundTag());
            entry.putByte("Slot", (byte) i);
            items.add(entry);
        }
        CompoundTag blockEntity = new CompoundTag();
        blockEntity.put("Items", items);
        box.getOrCreateTag().put("BlockEntityTag", blockEntity);
        return box;
    }

    private static WebUiBusinessException expectRejection(GameTestHelper helper, MarketEngine engine,
                                                          ServerPlayer seller, int slot) {
        try {
            engine.place(seller, slot, LIST_COUNT, UNIT_PRICE, MarketConstants.CURRENCY_CREDIT);
        } catch (WebUiBusinessException rejected) {
            return rejected;
        }
        helper.fail("槽位 " + slot + " 的东西本应被新鲜度门槛拒掉, 实际却挂上了");
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static WebUiBusinessException buyRejection(GameTestHelper helper, MarketEngine engine,
                                                       ServerPlayer buyer, long listingId) {
        try {
            engine.buy(buyer, listingId, 1);
        } catch (WebUiBusinessException rejected) {
            return rejected;
        }
        helper.fail("这一单本应被腐烂守卫拒掉, 实际却买成了 (listingId=" + listingId + ")");
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static JsonObject handle(WebUiAction action, ServerPlayer sender, JsonObject payload) {
        return JsonParser.parseString(action.handle(sender, payload)).getAsJsonObject();
    }

    private static JsonObject slotPayload(int slot) {
        JsonObject payload = new JsonObject();
        payload.addProperty("slot", slot);
        return payload;
    }

    private static Function<UUID, PlayerAbuseState> newStateResolver() {
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        return id -> states.computeIfAbsent(id, k -> new PlayerAbuseState());
    }
}

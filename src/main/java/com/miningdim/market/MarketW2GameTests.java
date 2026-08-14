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
import com.miningdim.job.tarot.TarotCardItem;
import com.miningdim.job.tarot.TarotQuality;
import com.miningdim.job.tarot.TarotRegistry;
import com.miningdim.market.store.MarketDao;
import com.miningdim.market.store.MarketDaoSqlite;
import com.miningdim.market.store.MarketDb;
import com.miningdim.market.store.SoldOrListedSplit;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Function;

/**
 * W2 市场增量五条 action 的 GameTest (market.feePreview / market.p2pCap / market.history /
 * market.pendingPayout / market.tradable)。与 {@link MarketGameTests} / {@link MarketBridgeGameTests} 同 batch、
 * 同款内存 SQLite (账本与市场表同连接) 与经济/市场门面 swap-restore 范式, 同样直接调 handle 拿 resultJson 解析断言。
 *
 * 每条用例守的是"这条 action 唯一的失败模式", 不是形状:
 *  1. <b>白名单同源</b> (最关键): 同一张 SR/闪耀塔罗牌, market.tradable 说不能挂, {@link MarketEngine#place}
 *     也必须真的拒 —— 只灰前端而服务端放行, 等于页面显示的规则是假的, 玩家用命令行照挂不误;
 *     且闪耀必须与 SR 同样被拒 (把判据从枚举常量比较换成 tierIndex() &lt;= 0, 闪耀的 -1 就会被误放行);
 *  2. 创造模式直给的裸牌 / 品质序号越界的脏牌走 tradable <b>不抛</b>, 且给出与"品质不够低"不同的另一句话;
 *  3. <b>peek 只读</b>: 查完待结货款再登录结算, 钱必须一分不少地到账 —— peek 顺手清空的话这里只会到账 0;
 *  4. history 的 total 覆盖买卖两侧且分页稳定, 空流水回空数组不报错;
 *  5. feePreview 的 listFee 与 place 实扣<b>逐位相等</b> (预览与实收对不上是这条 action 唯一的失败模式,
 *     而费是上单即收不退的), admin 改锚后两者仍同步;
 *  6. p2pCap 的 remaining 正是 place 还肯放行的量 (面板说还剩 12 而挂 12 被拒, 在玩家眼里就是系统在骗人)。
 *
 * 对抗复核补齐的四条 (用例 9-14), 每条对着一个真实可被利用的绕过或假承诺:
 *  7. <b>容器下钻</b>: 27 张 UR 塞进潜影盒后顶层只是 minecraft:shulker_box, 不下钻就整包过关, 且 tradable 也回 true;
 *     被规避的不只是"挂不挂得上", 而是"高品质必须自己合成"——买来的牌能直接当合成材料。同时守不误伤 (装钻石/
 *     装 R 牌/空盒必须放行) 与脏 NBT 不抛 (这条路同时服务只读预判, 抛出去就是面板打不开);
 *  8. <b>V0 单一真源</b>: feePreview 与 baseValue 的 (v0,source) 必须与 place 吃的那份分层同源, 且用一个
 *     "点查看得见覆盖、全表 map 是空的"的 DAO 视图把手抄分层直接照出来;
 *  9. <b>p2pCap 拆段</b>: activeHeld + soldToday 恒等于 usedToday, 而 resetsAt 只对得起 soldToday ——
 *     在挂的量不看 created_at, 翻日一件不掉, 撤单才释放;
 * 10. <b>history 钳制</b>: pageSize=-1 会让 SQLite 的 LIMIT -1 变成不限行, 把该玩家全部流水拉回主线程序列化。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MarketW2GameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "market";

    /** 主背包槽数 (与 market.place / player.itemDetail 同一索引空间)。 */
    private static final int MAIN_INVENTORY_SIZE = 36;

    /** 塔罗牌的唯一注册 id (220 张牌 x 5 档品质全共用它, 品质只活在 NBT 里 —— 这正是 tradable 收 slot 不收 itemId 的原因)。 */
    private static final String TAROT_ITEM_ID = "miningdim:tarot_card";

    /**
     * 两条拒绝规则名。写死字面量而不是引用 {@link MarketTradeWhitelist} 的常量: 它们是前端 errorText.ts 按值分句
     * 的契约面, 引用常量的话改了值两边一起变, 测试还是绿的。
     */
    private static final String RULE_QUALITY_ABOVE_R = "TAROT_QUALITY_ABOVE_R";
    private static final String RULE_IDENTITY_UNREADABLE = "TAROT_IDENTITY_UNREADABLE";

    /** W2 新接的 5 条 action 的契约名 (前端 SERVER_ACTIONS 逐字发的就是这些字符串)。 */
    private static final String[] W2_ACTION_NAMES = {
            "market.feePreview", "market.p2pCap", "market.history",
            "market.pendingPayout", "market.tradable"};

    /** 容器下钻用例里的载体 (原版唯一能把内容物随身带走的方块容器)。 */
    private static final String SHULKER_ITEM_ID = "minecraft:shulker_box";

    /**
     * V0 单一真源用例的三个探针值。金锭刻意选"既有代码预设又被 admin 覆盖"的标的:
     * 覆盖 777 与预设 120 必须是两个不同的数, 漏掉覆盖层的实现才会当场分叉 (两者相等就什么都证明不了)。
     */
    private static final String GOLD_ITEM_ID = "minecraft:gold_ingot";
    private static final long PRESET_GOLD_V0 = 120L;
    private static final long OVERRIDDEN_GOLD_V0 = 777L;
    /** 钻石的代码预设是 500, 覆盖成 777 才能把"读点查"与"读全表 map"两条路分开。 */
    private static final long OVERRIDDEN_DIAMOND_V0 = 777L;

    /** 分页钳制用例造的流水行数 (必须严格大于单页上限 100, 否则"钳住了"与"恰好全返"分不开)。 */
    private static final int HISTORY_ROWS = 105;

    /** 受铜铁日 cap 约束的 6 个标的, 按服务端下发的字典序 (面板照这个顺序列受限标的)。 */
    private static final String[] EXPECTED_CAP_SCOPE = {
            "minecraft:copper_ingot", "minecraft:copper_ore",
            "minecraft:iron_ingot", "minecraft:iron_ore",
            "minecraft:raw_copper", "minecraft:raw_iron"};

    // ============================================================
    // 1. M3 同源: tradable 说不能挂的那一格, place 必须真的拒 (含闪耀)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tarotWhitelistBindsTradableAndPlaceToOneVerdict(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEconomy = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite dao = MarketDb.on(ledger.connection());
        MarketEngine engine = new MarketEngine(dao, helper.getLevel().getServer());
        MarketEngine prevMarket = swapMarket(engine);
        try {
            ServerPlayer seller = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            seller.getInventory().clearContent();
            seller.getInventory().setItem(0, card(7, TarotQuality.SR, seller.getUUID()));
            seller.getInventory().setItem(1, card(3, TarotQuality.R, seller.getUUID()));
            seller.getInventory().setItem(2, card(11, TarotQuality.SHINY, seller.getUUID()));
            // 挂单费远够 (塔罗无锚 -> 平率 round(0.20*100*1)=20), 使被拒只可能因为白名单。
            EconomyServices.economyService().grant(seller, Currency.CREDIT, 10_000L);
            long funded = ledger.balance(seller.getUUID(), Currency.CREDIT);

            // --- SR: 面板侧 ---
            JsonObject srVerdict = handle(MarketActions.TRADABLE, seller, slotPayload(0));
            helper.assertTrue(!srVerdict.get("tradable").getAsBoolean(),
                    "SR 塔罗牌不得可挂 (只有最低品质 R 可挂)");
            helper.assertTrue(WebUiErrorCodes.ITEM_NOT_TRADABLE.equals(srVerdict.get("reasonCode").getAsString()),
                    "拒绝码必须是 ITEM_NOT_TRADABLE (与 place 抛的码同一个值), 实得 " + srVerdict.get("reasonCode"));
            helper.assertTrue(TAROT_ITEM_ID.equals(srVerdict.get("itemId").getAsString()),
                    "回执必须带该格的注册 id, 实得 " + srVerdict.get("itemId"));
            helper.assertTrue(srVerdict.get("slot").getAsInt() == 0,
                    "回执必须回显入参 slot 供前端对回选中格, 实得 " + srVerdict.get("slot"));

            // --- SR: 挂单侧 (M3 的落点; 只灰按钮不拦服务端等于规则是假的) ---
            WebUiBusinessException srRejected = placeRejection(helper, engine, seller, 0, 1, 100L);
            helper.assertTrue(WebUiErrorCodes.ITEM_NOT_TRADABLE.equals(srRejected.errorCode()),
                    "place 拒绝 SR 牌必须用与 tradable 同一个稳定码, 实得 " + srRejected.errorCode());
            helper.assertTrue(RULE_QUALITY_ABOVE_R.equals(srRejected.params().get("rule")),
                    "拒绝必须带 rule=" + RULE_QUALITY_ABOVE_R + " 供前端分句, 实得 " + srRejected.params());
            helper.assertTrue(TAROT_ITEM_ID.equals(srRejected.params().get("itemId")),
                    "拒绝必须带 itemId, 实得 " + srRejected.params());
            // 拒绝时状态干净: 判定插在扣费与 shrink 之前。
            helper.assertTrue(seller.getInventory().getItem(0).getCount() == 1,
                    "被拒的挂单不得托管物品 (牌必须还在原格)");
            helper.assertTrue(ledger.balance(seller.getUUID(), Currency.CREDIT) == funded,
                    "被拒的挂单不得收走挂单手续费, 余额应仍为 " + funded
                            + ", 实为 " + ledger.balance(seller.getUUID(), Currency.CREDIT));
            helper.assertTrue(dao.listingsBySeller(seller.getUUID(), null).isEmpty(),
                    "被拒的挂单不得留下任何 listing 行");

            // --- 闪耀: 与 SR 同罚 (拿 tierIndex() 判"最低"会把 -1 的闪耀误放行, 这条就是那个变异的探针) ---
            JsonObject shinyVerdict = handle(MarketActions.TRADABLE, seller, slotPayload(2));
            helper.assertTrue(!shinyVerdict.get("tradable").getAsBoolean(),
                    "闪耀塔罗牌不算最低品质, 一并禁止挂单");
            WebUiBusinessException shinyRejected = placeRejection(helper, engine, seller, 2, 1, 100L);
            helper.assertTrue(RULE_QUALITY_ABOVE_R.equals(shinyRejected.params().get("rule")),
                    "闪耀被拒的规则名与 SR 同一条 (品质高于最低档 R), 实得 " + shinyRejected.params());
            helper.assertTrue(seller.getInventory().getItem(2).getCount() == 1,
                    "被拒的闪耀牌必须还在原格");

            // --- R: 两边都放行 ---
            JsonObject rVerdict = handle(MarketActions.TRADABLE, seller, slotPayload(1));
            helper.assertTrue(rVerdict.get("tradable").getAsBoolean(),
                    "最低品质 R 的塔罗牌必须可挂 (保留新手入门渠道)");
            helper.assertTrue(rVerdict.get("reasonCode").isJsonNull() && rVerdict.get("reason").isJsonNull(),
                    "可交易时 reasonCode/reason 必须是 JSON null 而不是空串, 实得 "
                            + rVerdict.get("reasonCode") + " / " + rVerdict.get("reason"));

            long listingId = engine.place(seller, 1, 1, 100L, MarketConstants.CURRENCY_CREDIT).listingId();
            helper.assertTrue(listingId > 0L, "R 品质塔罗牌必须真的挂得上去");
            helper.assertTrue(seller.getInventory().getItem(1).isEmpty(),
                    "挂上后 R 牌被托管, 原格清空");
            helper.assertTrue(dao.findListing(listingId).itemId().equals(TAROT_ITEM_ID),
                    "落库的挂单标的是塔罗牌");

            helper.succeed();
        } finally {
            restoreMarket(prevMarket);
            MarketDb.close(dao);
            restoreEconomy(prevEconomy);
        }
    }

    // ============================================================
    // 2. 脏 NBT 的牌: tradable 不抛, 且与"品质不够低"是两句不同的话
    // ============================================================

    /**
     * 判定链第二步 (身份可读探针) 既是安全线也是不抛的前提: {@link TarotCardItem#quality} 对缺键抛
     * IllegalStateException、对越界序号经 {@link TarotQuality#byOrdinal} 抛 IllegalArgumentException。
     * 本用例先<b>正面证明</b>这两个 getter 确实会抛, 再证明 judge 没被它们炸掉 —— 去掉那道守卫, 两个
     * tradable 调用都会以异常告终而不是回一个裁决。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tradableDegradesDirtyTarotInsteadOfThrowing(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();

        // 槽 0: 创造模式直给的裸牌 (整份 NBT 缺席)。
        ItemStack bare = new ItemStack(TarotRegistry.TAROT_CARD.get());
        // 槽 1: 三键齐全但品质序号越界 (跨版本删档 / 手改 NBT 的老牌)。
        ItemStack corrupted = card(5, TarotQuality.R, player.getUUID());
        corruptQualityOrdinal(corrupted, TarotQuality.values().length + 3);
        // 槽 2: 非塔罗物品 (白名单只管塔罗, 其余一律放行)。
        player.getInventory().setItem(0, bare);
        player.getInventory().setItem(1, corrupted);
        player.getInventory().setItem(2, new ItemStack(Items.DIAMOND, 4));
        // 槽 3: 正常 SR 牌, 用来对照两种拒绝的文案必须不同。
        player.getInventory().setItem(3, card(9, TarotQuality.SR, player.getUUID()));

        helper.assertTrue(throwsOnQuality(bare),
                "裸牌读品质本就会抛 (故 judge 必须先过身份可读探针, 否则整条 action 崩)");
        helper.assertTrue(throwsOnQuality(corrupted),
                "越界品质序号读起来本就会抛 (byOrdinal 不静默回退默认档)");

        JsonObject bareVerdict = handle(MarketActions.TRADABLE, player, slotPayload(0));
        helper.assertTrue(!bareVerdict.get("tradable").getAsBoolean(),
                "数据不完整的牌不得放行 —— 证不出它是 R, 就不满足放行条件");
        helper.assertTrue(WebUiErrorCodes.ITEM_NOT_TRADABLE.equals(bareVerdict.get("reasonCode").getAsString()),
                "裸牌的拒绝码同样是 ITEM_NOT_TRADABLE, 实得 " + bareVerdict.get("reasonCode"));

        JsonObject corruptedVerdict = handle(MarketActions.TRADABLE, player, slotPayload(1));
        helper.assertTrue(!corruptedVerdict.get("tradable").getAsBoolean(),
                "越界品质序号的牌同样被拒且不抛");

        JsonObject diamondVerdict = handle(MarketActions.TRADABLE, player, slotPayload(2));
        helper.assertTrue(diamondVerdict.get("tradable").getAsBoolean(),
                "非塔罗物品一律放行 (本轮白名单只管塔罗牌)");
        helper.assertTrue("minecraft:diamond".equals(diamondVerdict.get("itemId").getAsString()),
                "非塔罗物品的回执照样带自己的注册 id");

        // 两种拒绝必须给出不同的话: 前端拿 rule 分句, 玩家才知道该去合成还是该扔掉这张坏牌。
        String dirtyReason = bareVerdict.get("reason").getAsString();
        String qualityReason = handle(MarketActions.TRADABLE, player, slotPayload(3)).get("reason").getAsString();
        helper.assertTrue(!dirtyReason.isEmpty() && !qualityReason.isEmpty(),
                "两种拒绝都必须给出中文原因");
        helper.assertTrue(!dirtyReason.equals(qualityReason),
                "\"牌数据不完整\"与\"品质不够低\"必须是两句不同的话, 实得同一句: " + dirtyReason);

        helper.succeed();
    }

    /**
     * place 对脏牌的拒绝必须带 {@link #RULE_IDENTITY_UNREADABLE} 而不是品质那条规则名 ——
     * 两种情形共用一个 errorCode, rule 是前端唯一能把它们分开的字段。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void placeRejectsDirtyTarotWithItsOwnRuleName(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEconomy = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite dao = MarketDb.on(ledger.connection());
        MarketEngine engine = new MarketEngine(dao, helper.getLevel().getServer());
        try {
            ServerPlayer seller = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            seller.getInventory().clearContent();
            seller.getInventory().setItem(0, new ItemStack(TarotRegistry.TAROT_CARD.get()));
            EconomyServices.economyService().grant(seller, Currency.CREDIT, 10_000L);
            long funded = ledger.balance(seller.getUUID(), Currency.CREDIT);

            WebUiBusinessException rejected = placeRejection(helper, engine, seller, 0, 1, 100L);
            helper.assertTrue(WebUiErrorCodes.ITEM_NOT_TRADABLE.equals(rejected.errorCode()),
                    "裸牌挂单被拒的码是 ITEM_NOT_TRADABLE, 实得 " + rejected.errorCode());
            helper.assertTrue(RULE_IDENTITY_UNREADABLE.equals(rejected.params().get("rule")),
                    "裸牌走的是身份不可读那条规则, 不是品质规则, 实得 " + rejected.params());
            helper.assertTrue(ledger.balance(seller.getUUID(), Currency.CREDIT) == funded
                            && !seller.getInventory().getItem(0).isEmpty(),
                    "被拒时既不扣挂单费也不托管物品");

            helper.succeed();
        } finally {
            MarketDb.close(dao);
            restoreEconomy(prevEconomy);
        }
    }

    // ============================================================
    // 3. market.tradable 的槽位拒绝码 (与 player.itemDetail 逐字同形)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tradableRejectsBadSlotsWithStableCodes(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();

        WebUiBusinessException missing = rejection(helper, MarketActions.TRADABLE, player, new JsonObject());
        helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(missing.errorCode())
                        && "slot".equals(missing.params().get("field")),
                "缺 slot 应回 INVALID_REQUEST 并指名字段, 实得 " + missing.errorCode() + " " + missing.params());

        WebUiBusinessException below = rejection(helper, MarketActions.TRADABLE, player, slotPayload(-1));
        helper.assertTrue(WebUiErrorCodes.SLOT_OUT_OF_RANGE.equals(below.errorCode()),
                "slot=-1 是越界而不是空槽, 实得 " + below.errorCode());
        helper.assertTrue("-1".equals(below.params().get("slot"))
                        && Integer.toString(MAIN_INVENTORY_SIZE).equals(below.params().get("size")),
                "越界拒绝必须带 slot 与 size 两个实参, 实得 " + below.params());

        WebUiBusinessException above =
                rejection(helper, MarketActions.TRADABLE, player, slotPayload(MAIN_INVENTORY_SIZE));
        helper.assertTrue(WebUiErrorCodes.SLOT_OUT_OF_RANGE.equals(above.errorCode()),
                "slot=" + MAIN_INVENTORY_SIZE + " 已越上界 (合法域 [0,36)), 实得 " + above.errorCode());

        WebUiBusinessException empty = rejection(helper, MarketActions.TRADABLE, player, slotPayload(5));
        helper.assertTrue(WebUiErrorCodes.SLOT_EMPTY.equals(empty.errorCode()),
                "合法但空的槽位应回 SLOT_EMPTY, 实得 " + empty.errorCode());
        helper.assertTrue("5".equals(empty.params().get("slot")) && !empty.params().containsKey("size"),
                "空槽拒绝只带 slot 一个实参, 实得 " + empty.params());

        helper.succeed();
    }

    // ============================================================
    // 4. M4 红线: pendingPayout 只读, 查完钱必须还在
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pendingPayoutPeekNeverConsumesTheMoney(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEconomy = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite dao = MarketDb.on(ledger.connection());
        MarketEngine engine = new MarketEngine(dao, helper.getLevel().getServer());
        MarketEngine prevMarket = swapMarket(engine);
        try {
            ServerPlayer seller = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            long now = System.currentTimeMillis();

            JsonObject beforeAnySale = handle(MarketActions.PENDING_PAYOUT, seller);
            helper.assertTrue(beforeAnySale.get("credit").getAsLong() == 0L
                            && beforeAnySale.get("entryCount").getAsInt() == 0,
                    "没有待结款时回 0 笔 0 点而不是报错, 实得 " + beforeAnySale);

            // 两笔离线成交 (数额刻意不同, 求和写错成"取第一笔"或"条目数"都会被抓出来)。
            dao.insertPendingPayout(seller.getUUID(), 300L, MarketConstants.CURRENCY_CREDIT, now);
            dao.insertPendingPayout(seller.getUUID(), 450L, MarketConstants.CURRENCY_CREDIT, now + 1L);
            // 别人的待结款不得混进来。
            dao.insertPendingPayout(UUID.randomUUID(), 9_999L, MarketConstants.CURRENCY_CREDIT, now + 2L);

            JsonObject first = handle(MarketActions.PENDING_PAYOUT, seller);
            helper.assertTrue(first.get("credit").getAsLong() == 750L,
                    "待结合计必须是两笔之和 750 (不含别人的 9999), 实得 " + first.get("credit"));
            helper.assertTrue(first.get("entryCount").getAsInt() == 2,
                    "待结条目数是 2 笔, 实得 " + first.get("entryCount"));
            helper.assertTrue(ledger.balance(seller.getUUID(), Currency.CREDIT) == 0L,
                    "查看待结款不得顺手发钱 (真实发放只在登录结算时发生)");

            JsonObject second = handle(MarketActions.PENDING_PAYOUT, seller);
            helper.assertTrue(second.get("credit").getAsLong() == 750L
                            && second.get("entryCount").getAsInt() == 2,
                    "连查两次数额不变 (peek 不是取即删), 第二次实得 " + second);

            // M4 的落点: 查过之后登录结算, 钱必须一分不少地到账。peek 若复用了 drain, 这里只会到账 0。
            engine.settlePendingOnLogin(seller);
            helper.assertTrue(ledger.balance(seller.getUUID(), Currency.CREDIT) == 750L,
                    "查过收件箱之后登录结算仍须到账全额 750, 实为 "
                            + ledger.balance(seller.getUUID(), Currency.CREDIT));

            JsonObject afterSettle = handle(MarketActions.PENDING_PAYOUT, seller);
            helper.assertTrue(afterSettle.get("credit").getAsLong() == 0L
                            && afterSettle.get("entryCount").getAsInt() == 0,
                    "结算之后待结款清空 (这次是 drain 干的), 实得 " + afterSettle);

            helper.succeed();
        } finally {
            restoreMarket(prevMarket);
            MarketDb.close(dao);
            restoreEconomy(prevEconomy);
        }
    }

    // ============================================================
    // 5. market.history: 买卖双侧 + total + 稳定分页 + 对手方在线/离线
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void historyPagesBothSidesWithTotal(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEconomy = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite dao = MarketDb.on(ledger.connection());
        MarketEngine engine = new MarketEngine(dao, helper.getLevel().getServer());
        MarketEngine prevMarket = swapMarket(engine);
        try {
            ServerPlayer self = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            ServerPlayer partner = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            UUID offline = UUID.randomUUID();
            UUID stranger = UUID.randomUUID();
            helper.assertTrue(helper.getLevel().getServer().getPlayerList().getPlayer(offline) == null,
                    "离线对手方的 UUID 确实不在 PlayerList 里");

            JsonObject blank = handle(MarketActions.HISTORY, self);
            helper.assertTrue(blank.getAsJsonArray("transactions").size() == 0
                            && blank.get("total").getAsInt() == 0,
                    "没有任何流水时回空数组 + total=0, 而不是报错, 实得 " + blank);
            helper.assertTrue(blank.get("page").getAsInt() == 0 && blank.get("pageSize").getAsInt() == 20,
                    "缺省分页是 page=0 pageSize=20, 实得 " + blank.get("page") + "/" + blank.get("pageSize"));

            // 时间戳从新到旧: A(5000) > B(4000) > C(3000) > D(2000) > E(1000); 噪声行 N(6000) 最新但与 self 无关。
            dao.insertTxn(101L, self.getUUID(), partner.getUUID(), "minecraft:diamond", 2, 50L, 100L, 0L, 5_000L);
            dao.insertTxn(102L, partner.getUUID(), self.getUUID(), "minecraft:iron_ingot", 3, 20L, 60L, 0L, 4_000L);
            dao.insertTxn(103L, self.getUUID(), offline, "minecraft:gold_ingot", 1, 10L, 10L, 0L, 3_000L);
            dao.insertTxn(104L, partner.getUUID(), self.getUUID(), "minecraft:emerald", 4, 7L, 28L, 0L, 2_000L);
            dao.insertTxn(105L, self.getUUID(), partner.getUUID(), "minecraft:copper_ingot", 5, 3L, 15L, 0L, 1_000L);
            dao.insertTxn(106L, stranger, offline, "minecraft:diamond", 9, 90L, 810L, 0L, 6_000L);

            JsonObject page0 = handle(MarketActions.HISTORY, self, pagePayload(0, 2));
            JsonArray rows0 = page0.getAsJsonArray("transactions");
            helper.assertTrue(page0.get("total").getAsInt() == 5,
                    "total 必须同时数上买入侧与卖出侧 (5 条; 只数一侧会得到 3 或 2), 实得 " + page0.get("total"));
            helper.assertTrue(rows0.size() == 2,
                    "pageSize=2 只返回 2 行, 实得 " + rows0.size());
            helper.assertTrue(page0.get("page").getAsInt() == 0 && page0.get("pageSize").getAsInt() == 2,
                    "回执必须回显本次分页参数");

            JsonObject newest = rows0.get(0).getAsJsonObject();
            helper.assertTrue(newest.get("listingId").getAsLong() == 101L,
                    "第一行是最新的那笔 (created_at 倒序), 实得 listingId=" + newest.get("listingId"));
            helper.assertTrue("buy".equals(newest.get("role").getAsString()),
                    "self 在这一行是买家, role 必须是 buy, 实得 " + newest.get("role"));
            helper.assertTrue(partner.getUUID().toString().equals(newest.get("counterpartyUuid").getAsString()),
                    "买入行的对手方是卖家, 实得 " + newest.get("counterpartyUuid"));
            helper.assertTrue(partner.getName().getString().equals(newest.get("counterpartyName").getAsString()),
                    "对手方在线时必须解出玩家名, 实得 " + newest.get("counterpartyName"));
            helper.assertTrue("minecraft:diamond".equals(newest.get("itemId").getAsString())
                            && "item.minecraft.diamond".equals(newest.get("descriptionId").getAsString()),
                    "流水行必须带 itemId 与翻译键 (专用服务端不加载 lang, 名字由客户端 i18n 解), 实得 "
                            + newest.get("itemId") + " / " + newest.get("descriptionId"));
            helper.assertTrue(newest.get("count").getAsInt() == 2
                            && newest.get("unitPrice").getAsLong() == 50L
                            && newest.get("total").getAsLong() == 100L
                            && newest.get("fee").getAsLong() == 0L,
                    "数量/单价/总价/手续费必须逐字来自表行 (2 x 50 = 100, fee 0), 实得 " + newest);
            helper.assertTrue(newest.get("createdAt").getAsLong() == 5_000L,
                    "成交时刻原样下发, 实得 " + newest.get("createdAt"));
            helper.assertTrue(newest.get("txnId").getAsLong() > 0L,
                    "txnId 是表的自增主键, 必须为正, 实得 " + newest.get("txnId"));

            JsonObject asSeller = rows0.get(1).getAsJsonObject();
            helper.assertTrue("sell".equals(asSeller.get("role").getAsString()),
                    "同一张表里 self 是卖家的那行 role 必须是 sell, 实得 " + asSeller.get("role"));
            helper.assertTrue(partner.getUUID().toString().equals(asSeller.get("counterpartyUuid").getAsString()),
                    "卖出行的对手方是买家, 实得 " + asSeller.get("counterpartyUuid"));

            JsonObject page1 = handle(MarketActions.HISTORY, self, pagePayload(1, 2));
            JsonArray rows1 = page1.getAsJsonArray("transactions");
            helper.assertTrue(rows1.size() == 2
                            && rows1.get(0).getAsJsonObject().get("listingId").getAsLong() == 103L
                            && rows1.get(1).getAsJsonObject().get("listingId").getAsLong() == 104L,
                    "第二页接着第一页往下翻, 不重不漏 (应为 103/104), 实得 " + rows1);
            helper.assertTrue(page1.get("total").getAsInt() == 5,
                    "total 与页码无关, 恒为 5");
            helper.assertTrue(rows1.get(0).getAsJsonObject().get("counterpartyName").isJsonNull(),
                    "对手方离线时 counterpartyName 是 JSON null (不编名不拿 UUID 冒充), 实得 "
                            + rows1.get(0).getAsJsonObject().get("counterpartyName"));
            helper.assertTrue(offline.toString()
                            .equals(rows1.get(0).getAsJsonObject().get("counterpartyUuid").getAsString()),
                    "离线对手方仍然给出 UUID 供前端降级展示");

            JsonObject page2 = handle(MarketActions.HISTORY, self, pagePayload(2, 2));
            helper.assertTrue(page2.getAsJsonArray("transactions").size() == 1
                            && page2.getAsJsonArray("transactions").get(0).getAsJsonObject()
                                .get("listingId").getAsLong() == 105L,
                    "最后一页只剩 1 行 (105), 实得 " + page2.getAsJsonArray("transactions"));

            // 与 self 无关的那笔虽然最新, 但一页都不该出现在它的流水里。
            helper.assertTrue(!containsListing(rows0, 106L) && !containsListing(rows1, 106L)
                            && !containsListing(page2.getAsJsonArray("transactions"), 106L),
                    "别人之间的成交绝不能出现在本人的流水里 (它还是全表最新的一行)");

            helper.succeed();
        } finally {
            restoreMarket(prevMarket);
            MarketDb.close(dao);
            restoreEconomy(prevEconomy);
        }
    }

    // ============================================================
    // 6. market.feePreview 与 place 实扣逐位相等
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void feePreviewEqualsWhatPlaceActuallyCharges(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEconomy = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite dao = MarketDb.on(ledger.connection());
        MarketEngine engine = new MarketEngine(dao, helper.getLevel().getServer());
        MarketEngine prevMarket = swapMarket(engine);
        try {
            ServerPlayer seller = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            seller.getInventory().clearContent();
            EconomyServices.economyService().grant(seller, Currency.CREDIT, 1_000_000L);

            // --- 有预设锚的物品: 预览 -> 真挂 -> 逐位比对 ---
            JsonObject preview = handle(MarketActions.FEE_PREVIEW, seller,
                    feePayload("minecraft:diamond", 100L, 10));
            long previewedFee = preview.get("listFee").getAsLong();
            helper.assertTrue(preview.get("v0").getAsLong() == 500L
                            && "preset".equals(preview.get("source").getAsString()),
                    "钻石命中代码预设锚 V0=500 source=preset, 实得 " + preview.get("v0")
                            + " / " + preview.get("source"));
            helper.assertTrue("minecraft:diamond".equals(preview.get("itemId").getAsString()),
                    "回执回显 itemId 供前端对回当前标的");
            helper.assertTrue(previewedFee > 0L,
                    "贱卖钻石 (锚 500 挂 100) 的偏离费必须为正, 实得 " + previewedFee);
            helper.assertTrue(Double.compare(preview.get("ratio").getAsDouble(),
                            (double) previewedFee / 1_000.0D) == 0,
                    "ratio 的分母是玩家挂的总价 unitPrice*count=1000, 实得 " + preview.get("ratio"));

            seller.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 10));
            long beforePlace = ledger.balance(seller.getUUID(), Currency.CREDIT);
            MarketEngine.PlaceResult placed =
                    engine.place(seller, 0, 10, 100L, MarketConstants.CURRENCY_CREDIT);
            helper.assertTrue(placed.listFee() == previewedFee,
                    "预览费必须与挂单实收逐位相等 (预览 " + previewedFee + " 实收 " + placed.listFee() + ")");
            helper.assertTrue(beforePlace - ledger.balance(seller.getUUID(), Currency.CREDIT) == previewedFee,
                    "卖家账上真正少掉的就是预览的那个数, 实少 "
                            + (beforePlace - ledger.balance(seller.getUUID(), Currency.CREDIT)));

            // --- admin 改锚之后两者仍同步 (预览若自己缓存一份 V0, 这里就会分叉) ---
            MarketServices.marketEngine().setBaseValueOverride("minecraft:diamond", 999L, UUID.randomUUID());
            JsonObject overridden = handle(MarketActions.FEE_PREVIEW, seller,
                    feePayload("minecraft:diamond", 100L, 10));
            long overriddenFee = overridden.get("listFee").getAsLong();
            helper.assertTrue(overridden.get("v0").getAsLong() == 999L
                            && "override".equals(overridden.get("source").getAsString()),
                    "admin 覆盖优先于代码预设, 实得 " + overridden.get("v0") + " / " + overridden.get("source"));
            helper.assertTrue(overriddenFee != previewedFee,
                    "换了锚就该换个费 (否则说明预览根本没读锚), 两次都是 " + overriddenFee);
            seller.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 10));
            helper.assertTrue(engine.place(seller, 0, 10, 100L, MarketConstants.CURRENCY_CREDIT).listFee()
                            == overriddenFee,
                    "改锚之后预览与实收仍须逐位相等");

            // --- 无锚物品: 退平率 round(0.20 * 7 * 3) = 4, v0 为 JSON null ---
            JsonObject noAnchor = handle(MarketActions.FEE_PREVIEW, seller,
                    feePayload("minecraft:cobblestone", 7L, 3));
            helper.assertTrue(noAnchor.get("v0").isJsonNull()
                            && "none".equals(noAnchor.get("source").getAsString()),
                    "无锚物品的 v0 必须是 JSON null 而不是丢键或填 0, 实得 " + noAnchor);
            helper.assertTrue(noAnchor.get("listFee").getAsLong() == 4L,
                    "无锚退平率 round(0.20*7*3)=4, 实得 " + noAnchor.get("listFee"));

            // --- ratio 可以 > 1: 极端贱卖时费超过挂单总价 (前端不得按 0..1 钳死进度条) ---
            JsonObject firesale = handle(MarketActions.FEE_PREVIEW, seller,
                    feePayload("minecraft:diamond", 1L, 1));
            helper.assertTrue(firesale.get("ratio").getAsDouble() > 1.0D,
                    "锚 999 的钻石挂 1 块, 手续费必然超过挂单总价 (ratio > 1), 实得 " + firesale.get("ratio"));
            helper.assertTrue(firesale.get("listFee").getAsLong() > 1L,
                    "该费本身也远超这一单的标价 1");

            // --- 非法入参一律抛, 绝不编一个 0 出来 ---
            WebUiBusinessException zeroPrice = rejection(helper, MarketActions.FEE_PREVIEW, seller,
                    feePayload("minecraft:diamond", 0L, 1));
            helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(zeroPrice.errorCode())
                            && "unitPrice".equals(zeroPrice.params().get("field"))
                            && "0".equals(zeroPrice.params().get("value")),
                    "unitPrice=0 应回 INVALID_REQUEST 并指名字段与取值, 实得 "
                            + zeroPrice.errorCode() + " " + zeroPrice.params());
            WebUiBusinessException zeroCount = rejection(helper, MarketActions.FEE_PREVIEW, seller,
                    feePayload("minecraft:diamond", 100L, 0));
            helper.assertTrue("count".equals(zeroCount.params().get("field"))
                            && "0".equals(zeroCount.params().get("value")),
                    "count=0 应指名 count 字段, 实得 " + zeroCount.params());
            WebUiBusinessException negative = rejection(helper, MarketActions.FEE_PREVIEW, seller,
                    feePayload("minecraft:diamond", -5L, 1));
            helper.assertTrue("unitPrice".equals(negative.params().get("field"))
                            && "-5".equals(negative.params().get("value")),
                    "负单价同样被拒并回显原值, 实得 " + negative.params());

            helper.succeed();
        } finally {
            restoreMarket(prevMarket);
            MarketDb.close(dao);
            restoreEconomy(prevEconomy);
        }
    }

    // ============================================================
    // 7. market.p2pCap 的剩余额度 = place 还肯放行的量
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void p2pCapRemainingMatchesWhatPlaceStillAllows(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEconomy = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite dao = MarketDb.on(ledger.connection());
        MarketEngine engine = new MarketEngine(dao, helper.getLevel().getServer());
        MarketEngine prevMarket = swapMarket(engine);
        try {
            ServerPlayer seller = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            seller.getInventory().clearContent();
            EconomyServices.economyService().grant(seller, Currency.CREDIT, 1_000_000L);
            int cap = MarketConstants.COPPER_IRON_DAILY_P2P_CAP;

            JsonObject fresh = handle(MarketActions.P2P_CAP, seller);
            helper.assertTrue(fresh.get("capPerDay").getAsInt() == cap,
                    "capPerDay 必须是常量 " + cap + ", 实得 " + fresh.get("capPerDay"));
            helper.assertTrue(fresh.get("usedToday").getAsInt() == 0
                            && fresh.get("remaining").getAsInt() == cap,
                    "还没挂过任何铜铁时已用 0 剩余满额, 实得 " + fresh);
            assertCapScope(helper, fresh.getAsJsonArray("scopeItemIds"));
            assertResetsAtIsLocalMidnightTomorrow(helper, fresh.get("resetsAt").getAsLong());

            // 挂 500 个铜锭: 额度按 ACTIVE 挂单量即时占用 (不等成交)。
            seller.getInventory().setItem(0, new ItemStack(Items.COPPER_INGOT, 500));
            engine.place(seller, 0, 500, 10L, MarketConstants.CURRENCY_CREDIT);
            JsonObject used = handle(MarketActions.P2P_CAP, seller);
            helper.assertTrue(used.get("usedToday").getAsInt() == 500,
                    "挂出的 500 个铜锭即时计入今日已用, 实得 " + used.get("usedToday"));
            int remaining = used.get("remaining").getAsInt();
            helper.assertTrue(remaining == cap - 500,
                    "剩余 = " + (cap - 500) + ", 实得 " + remaining);

            // 面板说还剩 remaining, 那么多挂一个就必须被拒 —— 这是"面板数字与挂单阈值同源"的落点。
            seller.getInventory().setItem(1, new ItemStack(Items.IRON_INGOT, remaining + 1));
            boolean overThrew = false;
            try {
                engine.place(seller, 1, remaining + 1, 10L, MarketConstants.CURRENCY_CREDIT);
            } catch (IllegalStateException rejected) {
                overThrew = true;
            }
            helper.assertTrue(overThrew,
                    "挂 remaining+1 (" + (remaining + 1) + ") 必须被日 cap 拒绝, 否则面板的剩余额度是假的");
            helper.assertTrue(seller.getInventory().getItem(1).getCount() == remaining + 1,
                    "被 cap 拒绝的挂单不得托管物品");

            // 恰好挂满剩余额度必须成功, 之后剩余归零。
            long okListing = engine.place(seller, 1, remaining, 10L, MarketConstants.CURRENCY_CREDIT).listingId();
            helper.assertTrue(okListing > 0L,
                    "恰好挂满剩余额度 (" + remaining + ") 必须成功, 面板不能虚报额度");
            JsonObject exhausted = handle(MarketActions.P2P_CAP, seller);
            helper.assertTrue(exhausted.get("usedToday").getAsInt() == cap
                            && exhausted.get("remaining").getAsInt() == 0,
                    "额度用尽后已用 = cap 且剩余 0, 实得 " + exhausted);

            // 非铜铁标的不占这份额度 (面板文案敢写"仅铜铁受限"的前提)。
            seller.getInventory().setItem(2, new ItemStack(Items.DIAMOND, 64));
            engine.place(seller, 2, 64, 10L, MarketConstants.CURRENCY_CREDIT);
            helper.assertTrue(handle(MarketActions.P2P_CAP, seller).get("usedToday").getAsInt() == cap,
                    "挂钻石不该动铜铁额度, 已用应仍为 " + cap);

            helper.succeed();
        } finally {
            restoreMarket(prevMarket);
            MarketDb.close(dao);
            restoreEconomy(prevEconomy);
        }
    }

    // ============================================================
    // 8. 五条 action 确实以契约里的名字注册进派发器
    // ============================================================

    /**
     * 其余用例全都直接调 {@code MarketActions.XXX} 常量, 注册表里的名字打错一个字母也照样全绿, 而前端发的是名字。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void w2ActionsAreRegisteredUnderContractNames(GameTestHelper helper) {
        ensureMarketActionsRegistered();
        for (String name : W2_ACTION_NAMES) {
            helper.assertTrue(WebUiServerDispatcher.resolve(name) != null,
                    name + " 必须由 MarketActions.registerAll 注册进派发器");
        }
        // M1: 成交流水归位到既有的 market.history, 不另开一条同义 action。
        helper.assertTrue(WebUiServerDispatcher.resolve("market.transactions") == null,
                "流水查询归位到 market.history, 不得另注册一条 market.transactions");
        helper.assertTrue(WebUiServerDispatcher.resolve("market.feepreview") == null,
                "action 名大小写敏感, 不得另注册一个全小写别名");
        helper.succeed();
    }

    // ============================================================
    // 9. F1 容器下钻: 潜影盒里的高品质牌照样挂不上去 (tradable 与 place 一起拒)
    // ============================================================

    /**
     * 只看顶层 {@code stack.getItem()} 的白名单等于没有白名单: 27 张 UR 塞进一个潜影盒, 顶层是
     * minecraft:shulker_box, 整包过关, 且 market.tradable 也回 true —— 前端按钮亮着, 两条路一起瞎。
     * 代价不止"买家打不出效果" (ownerUUID 绑定只管使用): 买来的高品质牌能直接当合成材料,
     * "高品质必须自己合成"就被规避成"买 UR 直接合闪耀"。
     *
     * 本用例守四件事, 缺一即回到走私状态:
     *  1. 装高品质牌的容器两条路 (market.tradable 只读预判 / {@link MarketEngine#place} 硬提交) 一起拒;
     *  2. 拒绝沿用<b>内层</b>规则名 (前端 errorText.ts 按 rule 分句的那套文案不必为容器再加一套);
     *  3. 内容物不止看第一格 (牌放在第 3 格照样要被扫出来);
     *  4. 装正常物品的容器与空容器绝不误伤 —— 粗暴禁掉一切带内容物的容器会挂在这一条上。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shulkerBoxCannotSmuggleHighQualityTarot(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEconomy = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite dao = MarketDb.on(ledger.connection());
        MarketEngine engine = new MarketEngine(dao, helper.getLevel().getServer());
        MarketEngine prevMarket = swapMarket(engine);
        try {
            ServerPlayer seller = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            seller.getInventory().clearContent();
            UUID owner = seller.getUUID();
            EconomyServices.economyService().grant(seller, Currency.CREDIT, 10_000L);
            long funded = ledger.balance(owner, Currency.CREDIT);

            ItemStack srCard = card(7, TarotQuality.SR, owner);
            ItemStack boxWithSr = shulkerWith(srCard);
            // 牌刻意放在第 3 格: 只判 Items 第一行的实现会在这里放行。
            ItemStack boxWithUrDeep = shulkerWith(new ItemStack(Items.DIAMOND, 3),
                    new ItemStack(Items.COPPER_INGOT, 5), card(13, TarotQuality.UR, owner));
            ItemStack boxWithBareCard = shulkerWith(new ItemStack(TarotRegistry.TAROT_CARD.get()));
            ItemStack boxWithLegitGoods = shulkerWith(new ItemStack(Items.DIAMOND, 16), card(3, TarotQuality.R, owner));
            ItemStack emptyBox = shulkerWith();
            // 从未装过东西的盒子连 NBT 都没有 (下钻的 tag == null 分支)。
            ItemStack untouchedBox = new ItemStack(Items.SHULKER_BOX);

            seller.getInventory().setItem(0, boxWithSr);
            seller.getInventory().setItem(1, boxWithLegitGoods);
            seller.getInventory().setItem(2, emptyBox);
            seller.getInventory().setItem(3, boxWithUrDeep);
            seller.getInventory().setItem(4, boxWithBareCard);
            seller.getInventory().setItem(5, untouchedBox);

            // --- 裁决层: 拒得对, 且理由沿用内层 ---
            MarketTradeWhitelist.Verdict innerSr = MarketTradeWhitelist.judge(srCard);
            MarketTradeWhitelist.Verdict boxed = MarketTradeWhitelist.judge(boxWithSr);
            helper.assertTrue(!boxed.tradable(),
                    "装着 SR 牌的潜影盒必须整包被拒 (只判顶层 item 的话白名单形同虚设)");
            helper.assertTrue(RULE_QUALITY_ABOVE_R.equals(boxed.rule()),
                    "容器被拒必须沿用内层规则名 " + RULE_QUALITY_ABOVE_R + " (前端按 rule 分句), 实得 " + boxed.rule());
            helper.assertTrue(WebUiErrorCodes.ITEM_NOT_TRADABLE.equals(boxed.reasonCode()),
                    "容器的拒绝码与裸牌同一个值, 实得 " + boxed.reasonCode());
            helper.assertTrue(boxed.reason().contains(innerSr.reason()),
                    "外层原因必须把内层那句话原样带出来 (玩家要知道是包里的哪件东西不合规), 实得 " + boxed.reason());
            helper.assertTrue(!boxed.reason().equals(innerSr.reason()),
                    "外层还得说清被拒的是容器里的东西, 不能与裸牌一字不差");

            MarketTradeWhitelist.Verdict deep = MarketTradeWhitelist.judge(boxWithUrDeep);
            helper.assertTrue(!deep.tradable() && RULE_QUALITY_ABOVE_R.equals(deep.rule()),
                    "UR 牌放在容器第 3 格照样要被扫出来 (只看第一格的实现会放行), 实得 "
                            + deep.tradable() + " / " + deep.rule());

            MarketTradeWhitelist.Verdict bareBoxed = MarketTradeWhitelist.judge(boxWithBareCard);
            helper.assertTrue(!bareBoxed.tradable() && RULE_IDENTITY_UNREADABLE.equals(bareBoxed.rule()),
                    "包里装的是数据不完整的裸牌时, 沿用的是身份不可读那条规则而不是品质规则, 实得 "
                            + bareBoxed.rule());

            // --- 不误伤: 装正常物品 / 装 R 牌 / 空盒 / 无 NBT 的盒子一律放行 ---
            helper.assertTrue(MarketTradeWhitelist.judge(boxWithLegitGoods).tradable(),
                    "装钻石与 R 牌的潜影盒必须放行 —— 不许用\"禁掉一切带内容物的容器\"这种粗暴修法");
            helper.assertTrue(MarketTradeWhitelist.judge(emptyBox).tradable(), "空潜影盒必须放行");
            helper.assertTrue(MarketTradeWhitelist.judge(untouchedBox).tradable(),
                    "从未装过东西 (整份 NBT 缺席) 的潜影盒必须放行");

            // --- 面板侧 ---
            JsonObject smuggler = handle(MarketActions.TRADABLE, seller, slotPayload(0));
            helper.assertTrue(!smuggler.get("tradable").getAsBoolean(),
                    "market.tradable 必须对走私包回 false, 否则前端按钮是亮的");
            helper.assertTrue(WebUiErrorCodes.ITEM_NOT_TRADABLE.equals(smuggler.get("reasonCode").getAsString()),
                    "走私包的拒绝码同样是 ITEM_NOT_TRADABLE, 实得 " + smuggler.get("reasonCode"));
            helper.assertTrue(SHULKER_ITEM_ID.equals(smuggler.get("itemId").getAsString()),
                    "回执的 itemId 是容器本体的注册 id, 实得 " + smuggler.get("itemId"));
            JsonObject legit = handle(MarketActions.TRADABLE, seller, slotPayload(1));
            helper.assertTrue(legit.get("tradable").getAsBoolean() && legit.get("reasonCode").isJsonNull(),
                    "装正常物品的容器在面板上必须是可挂的, 实得 " + legit);
            helper.assertTrue(handle(MarketActions.TRADABLE, seller, slotPayload(2)).get("tradable").getAsBoolean(),
                    "空容器在面板上必须是可挂的");

            // --- 挂单侧 (M3 同源: 只灰前端而服务端放行, 等于页面显示的规则是假的) ---
            WebUiBusinessException rejected = placeRejection(helper, engine, seller, 0, 1, 100L);
            helper.assertTrue(WebUiErrorCodes.ITEM_NOT_TRADABLE.equals(rejected.errorCode()),
                    "place 拒绝走私包用的是与 tradable 同一个稳定码, 实得 " + rejected.errorCode());
            helper.assertTrue(RULE_QUALITY_ABOVE_R.equals(rejected.params().get("rule")),
                    "拒绝必须带内层 rule 供前端分句, 实得 " + rejected.params());
            helper.assertTrue(SHULKER_ITEM_ID.equals(rejected.params().get("itemId")),
                    "拒绝带的 itemId 是容器本体, 实得 " + rejected.params());
            WebUiBusinessException deepRejected = placeRejection(helper, engine, seller, 3, 1, 100L);
            helper.assertTrue(RULE_QUALITY_ABOVE_R.equals(deepRejected.params().get("rule")),
                    "第 3 格藏牌的包在 place 侧同样被拒, 实得 " + deepRejected.params());
            WebUiBusinessException bareRejected = placeRejection(helper, engine, seller, 4, 1, 100L);
            helper.assertTrue(RULE_IDENTITY_UNREADABLE.equals(bareRejected.params().get("rule")),
                    "包里是裸牌时 place 给的是身份不可读那条规则, 实得 " + bareRejected.params());

            helper.assertTrue(seller.getInventory().getItem(0).getCount() == 1
                            && seller.getInventory().getItem(3).getCount() == 1
                            && seller.getInventory().getItem(4).getCount() == 1,
                    "被拒的走私包必须还在原格 (判定插在扣费与 shrink 之前)");
            helper.assertTrue(ledger.balance(owner, Currency.CREDIT) == funded,
                    "被拒的挂单不得收走挂单手续费, 余额应仍为 " + funded
                            + ", 实为 " + ledger.balance(owner, Currency.CREDIT));
            helper.assertTrue(dao.listingsBySeller(owner, null).isEmpty(),
                    "三次被拒的挂单不得留下任何 listing 行");

            // 装正常物品的容器必须真的挂得上去 —— 下钻不得把日常交易一并堵死。
            long listingId = engine.place(seller, 1, 1, 100L, MarketConstants.CURRENCY_CREDIT).listingId();
            helper.assertTrue(listingId > 0L, "装钻石与 R 牌的潜影盒必须挂得上去");
            helper.assertTrue(SHULKER_ITEM_ID.equals(dao.findListing(listingId).itemId()),
                    "落库的标的是容器本体 " + SHULKER_ITEM_ID + ", 实得 " + dao.findListing(listingId).itemId());
            helper.assertTrue(seller.getInventory().getItem(1).isEmpty(),
                    "挂上后容器被托管, 原格清空");

            helper.succeed();
        } finally {
            restoreMarket(prevMarket);
            MarketDb.close(dao);
            restoreEconomy(prevEconomy);
        }
    }

    // ============================================================
    // 10. F1 脏容器 NBT: 下钻不许抛, 坏行跳过但不许中断扫描
    // ============================================================

    /**
     * 下钻读的是玩家可写的 NBT (创造给物 / 第三方 mod / 手改存档), 形状不受任何约束。这条路同时服务
     * market.tradable 的只读预判 —— 抛出去就是整块面板打不开, 比放行更糟。
     *
     * 同时守住"坏行跳过 != 扫描中断": 一行坏 NBT 之后的那张 SSR 牌必须照样被扫出来, 否则走私者只要在
     * 潜影盒第一格塞一行坏 NBT 就能让后面的牌全部隐身。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void containerDrillDownSurvivesDirtyBlockEntityNbt(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();

        // 槽 0: BlockEntityTag 根本不是复合标签。
        ItemStack tagNotCompound = new ItemStack(Items.SHULKER_BOX);
        tagNotCompound.getOrCreateTag().putString("BlockEntityTag", "not-a-compound");

        // 槽 1: BlockEntityTag 在, 但 Items 是个 int 而不是列表。
        ItemStack itemsNotList = new ItemStack(Items.SHULKER_BOX);
        CompoundTag intItems = new CompoundTag();
        intItems.putInt("Items", 7);
        itemsNotList.getOrCreateTag().put("BlockEntityTag", intItems);

        // 槽 2: Items 是列表, 但元素类型是字符串 (getList 带类型参数才挡得住)。
        ItemStack itemsWrongElementType = new ItemStack(Items.SHULKER_BOX);
        ListTag strings = new ListTag();
        strings.add(StringTag.valueOf("minecraft:diamond"));
        CompoundTag stringItems = new CompoundTag();
        stringItems.put("Items", strings);
        itemsWrongElementType.getOrCreateTag().put("BlockEntityTag", stringItems);

        // 槽 3: 两行坏 NBT (id 指向不存在的物品 / 空复合标签) 之后跟一张 SSR 牌 —— 牌必须仍被扫出来。
        ItemStack brokenRowsThenCard = shulkerWithRawEntries(unknownItemEntry(), new CompoundTag(),
                card(9, TarotQuality.SSR, player.getUUID()).save(new CompoundTag()));

        // 槽 4: 只有坏行的容器 —— 当作没装东西, 放行。
        ItemStack onlyBrokenRows = shulkerWithRawEntries(unknownItemEntry(), new CompoundTag());

        player.getInventory().setItem(0, tagNotCompound);
        player.getInventory().setItem(1, itemsNotList);
        player.getInventory().setItem(2, itemsWrongElementType);
        player.getInventory().setItem(3, brokenRowsThenCard);
        player.getInventory().setItem(4, onlyBrokenRows);

        // 裁决层: 脏 NBT 一律"当作没装东西"而不是抛 (下面四条只要有一条抛, 整个用例就以异常告终)。
        helper.assertTrue(MarketTradeWhitelist.judge(tagNotCompound).tradable(),
                "BlockEntityTag 不是复合标签时当作没装东西, 放行且不抛");
        helper.assertTrue(MarketTradeWhitelist.judge(itemsNotList).tradable(),
                "Items 不是列表时当作没装东西, 放行且不抛");
        helper.assertTrue(MarketTradeWhitelist.judge(itemsWrongElementType).tradable(),
                "Items 列表元素类型不对时当作没装东西, 放行且不抛");
        helper.assertTrue(MarketTradeWhitelist.judge(onlyBrokenRows).tradable(),
                "整包只有坏行时当作没装东西, 放行且不抛");

        MarketTradeWhitelist.Verdict mixed = MarketTradeWhitelist.judge(brokenRowsThenCard);
        helper.assertTrue(!mixed.tradable() && RULE_QUALITY_ABOVE_R.equals(mixed.rule()),
                "坏行只能被跳过, 不能中断扫描 —— 它后面的 SSR 牌必须照样被拒, 实得 "
                        + mixed.tradable() + " / " + mixed.rule());

        // 面板侧: 同样五个槽位走一遍 action, 任一抛出即整块面板打不开。
        helper.assertTrue(handle(MarketActions.TRADABLE, player, slotPayload(0)).get("tradable").getAsBoolean()
                        && handle(MarketActions.TRADABLE, player, slotPayload(1)).get("tradable").getAsBoolean()
                        && handle(MarketActions.TRADABLE, player, slotPayload(2)).get("tradable").getAsBoolean()
                        && handle(MarketActions.TRADABLE, player, slotPayload(4)).get("tradable").getAsBoolean(),
                "脏 NBT 的容器走 market.tradable 必须回可挂而不是抛异常");
        helper.assertTrue(!handle(MarketActions.TRADABLE, player, slotPayload(3)).get("tradable").getAsBoolean(),
                "坏行之后藏着 SSR 牌的容器在面板上必须是不可挂的");

        helper.succeed();
    }

    // ============================================================
    // 11. F2 单一真源: feePreview 与 baseValue 读的是同一份 V0 分层
    // ============================================================

    /**
     * 两条 action 曾各自手抄一遍分层 (admin 覆盖 map -&gt; 代码预设), 而 {@link MarketEngine#place} 走
     * {@link BaseValueResolver}。{@link BaseValueResolver} 的类注释明写第 3 层"市场成交中位数"是后续要加的 ——
     * 那一层落地时 place 立刻吃新锚、预览仍在旧两层里打转, 玩家看"预计 800"实扣 3400, 而这笔费上单即收、撤单不退。
     *
     * 本用例把两条 action 的 (v0, source) 与唯一入口 {@link MarketEngine#lookupBaseValue} 对齐, 三层各验一次:
     * admin 覆盖层用的金锭刻意有预设 (120) 且覆盖值 (777) 与之不同 —— 漏掉覆盖层的实现会掉回 120, 当场挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void feePreviewAndBaseValueReadOneAnchorResolution(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEconomy = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite dao = MarketDb.on(ledger.connection());
        MarketEngine engine = new MarketEngine(dao, helper.getLevel().getServer());
        MarketEngine prevMarket = swapMarket(engine);
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            dao.upsertBaseValue(GOLD_ITEM_ID, OVERRIDDEN_GOLD_V0, "gametest", System.currentTimeMillis());

            assertSameAnchorEverywhere(helper, player, "minecraft:diamond", "500", "preset");
            assertSameAnchorEverywhere(helper, player, GOLD_ITEM_ID, Long.toString(OVERRIDDEN_GOLD_V0), "override");
            assertSameAnchorEverywhere(helper, player, "minecraft:cobblestone", null, "none");

            // 费必须由同一个锚算出来, 而不是"回执里贴一个 v0, 算费另用一个数"。
            JsonObject gold = handle(MarketActions.FEE_PREVIEW, player, feePayload(GOLD_ITEM_ID, 300L, 4));
            long byOverride = MarketFee.listingFee(OptionalLong.of(OVERRIDDEN_GOLD_V0), 300L, 4);
            long byPreset = MarketFee.listingFee(OptionalLong.of(PRESET_GOLD_V0), 300L, 4);
            helper.assertTrue(byOverride != byPreset,
                    "预设 " + PRESET_GOLD_V0 + " 与覆盖 " + OVERRIDDEN_GOLD_V0
                            + " 必须给出不同的费, 否则本用例证明不了预览读的是覆盖层");
            helper.assertTrue(gold.get("listFee").getAsLong() == byOverride,
                    "预览费必须按 admin 覆盖的锚算 (应为 " + byOverride + "), 实得 " + gold.get("listFee"));

            helper.succeed();
        } finally {
            restoreMarket(prevMarket);
            MarketDb.close(dao);
            restoreEconomy(prevEconomy);
        }
    }

    // ============================================================
    // 12. F2 变异探针: 预览绝不能退回"全表覆盖 map"那份手抄分层
    // ============================================================

    /**
     * 上一条只能证明"两处此刻给的数一样", 抄第三份等价实现照样绿。本条直指被删掉的那个实现:
     * 旧的 FEE_PREVIEW / BASE_VALUE 走的是 {@link MarketEngine#baseValueOverrides} 全表 SELECT + HashMap,
     * 而唯一入口 {@link MarketEngine#lookupBaseValue} 走 {@code dao.getBaseValue} 点查。
     *
     * 于是给引擎装一个"点查看得见覆盖、全表 map 却是空的"的 DAO: 走点查的实现回 777/override, 走全表 map 的
     * 实现回 500/preset。谁把手抄分层搬回来, 这里当场分叉。顺带把 place 的实收也拉进来比一遍 —— 预览与实收
     * 对不上才是 F2 真正的代价 (费上单即收、撤单不退)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void feePreviewNeverFallsBackToTheOverrideMapCopy(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEconomy = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite dao = MarketDb.on(ledger.connection());
        dao.upsertBaseValue("minecraft:diamond", OVERRIDDEN_DIAMOND_V0, "gametest", System.currentTimeMillis());
        MarketDao pointLookupOnly = daoHidingOverrideMap(dao);
        MarketEngine engine = new MarketEngine(pointLookupOnly, helper.getLevel().getServer());
        MarketEngine prevMarket = swapMarket(engine);
        try {
            // 探针自证有效: 删掉这层遮蔽本用例就成了摆设, 故先断言遮蔽确实生效。
            helper.assertTrue(dao.allBaseValues().containsKey("minecraft:diamond"),
                    "真 DAO 必须确有这条 admin 覆盖, 否则本用例的探针是空的");
            helper.assertTrue(pointLookupOnly.allBaseValues().isEmpty(),
                    "代理必须把全表覆盖 map 抹成空, 否则测不出手抄分层");
            helper.assertTrue(Long.valueOf(OVERRIDDEN_DIAMOND_V0).equals(pointLookupOnly.getBaseValue("minecraft:diamond")),
                    "点查这条路必须仍然看得见覆盖值 " + OVERRIDDEN_DIAMOND_V0);

            ServerPlayer seller = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            seller.getInventory().clearContent();
            EconomyServices.economyService().grant(seller, Currency.CREDIT, 1_000_000L);

            JsonObject preview = handle(MarketActions.FEE_PREVIEW, seller,
                    feePayload("minecraft:diamond", 100L, 10));
            helper.assertTrue(preview.get("v0").getAsLong() == OVERRIDDEN_DIAMOND_V0
                            && "override".equals(preview.get("source").getAsString()),
                    "预览的锚只能来自点查 (应为 " + OVERRIDDEN_DIAMOND_V0
                            + "/override; 退回全表 map 的手抄分层会得到 500/preset), 实得 "
                            + preview.get("v0") + " / " + preview.get("source"));
            JsonObject base = handle(MarketActions.BASE_VALUE, seller, itemIdPayload("minecraft:diamond"));
            helper.assertTrue(base.get("v0").getAsLong() == OVERRIDDEN_DIAMOND_V0
                            && "override".equals(base.get("source").getAsString()),
                    "market.baseValue 与预览同一个入口, 实得 " + base.get("v0") + " / " + base.get("source"));

            seller.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 10));
            long charged = engine.place(seller, 0, 10, 100L, MarketConstants.CURRENCY_CREDIT).listFee();
            helper.assertTrue(charged == preview.get("listFee").getAsLong(),
                    "预览 " + preview.get("listFee") + " 与实收 " + charged + " 必须逐位相等");
            helper.assertTrue(charged == MarketFee.listingFee(OptionalLong.of(OVERRIDDEN_DIAMOND_V0), 100L, 10),
                    "实收必须是按覆盖锚 " + OVERRIDDEN_DIAMOND_V0 + " 算出的偏离费, 实收 " + charged);

            helper.succeed();
        } finally {
            restoreMarket(prevMarket);
            MarketDb.close(dao);
            restoreEconomy(prevEconomy);
        }
    }

    // ============================================================
    // 13. F3 p2pCap 拆段: resetsAt 只承诺 soldToday, activeHeld 撤单才释放
    // ============================================================

    /**
     * 计数的 listing 侧不看 created_at, ACTIVE 即占额度。只下发总量 + resetsAt 的话, 挂着 500 铜锭不撤单的玩家
     * 到了次日零点一件也不会释放, 面板等于承诺了一件不会发生的事 —— 玩家的合理结论是"系统在骗人"。
     *
     * 三条断言各守一段:
     *  1. activeHeld + soldToday 恒等于 usedToday, 且 cap 判定吃的仍是这个总量 (拆分不改口径);
     *  2. 把窗口起点换成明天零点再数一次 (即模拟日切): 成交那段归零, 在挂那 300 一件不掉 ——
     *     若有人给 listing 侧 SQL 补上 created_at 过滤 ("顺手统一口径"), 这条当场挂;
     *  3. 撤单才是 activeHeld 的释放条件, 且撤单不动今日已成交那段。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void p2pCapSplitsHeldFromSoldAndOnlySoldFollowsResetsAt(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEconomy = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite dao = MarketDb.on(ledger.connection());
        MarketEngine engine = new MarketEngine(dao, helper.getLevel().getServer());
        MarketEngine prevMarket = swapMarket(engine);
        try {
            ServerPlayer seller = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            seller.getInventory().clearContent();
            EconomyServices.economyService().grant(seller, Currency.CREDIT, 1_000_000L);

            seller.getInventory().setItem(0, new ItemStack(Items.COPPER_INGOT, 300));
            long listingId = engine.place(seller, 0, 300, 10L, MarketConstants.CURRENCY_CREDIT).listingId();
            // 今日已成交那段: 直接落一行今日流水 (成交全链路另有用例覆盖, 这里只要"今日卖出过 50 个"这个状态)。
            dao.insertTxn(9_001L, UUID.randomUUID(), seller.getUUID(), "minecraft:copper_ingot",
                    50, 10L, 500L, 0L, System.currentTimeMillis());

            JsonObject cap = handle(MarketActions.P2P_CAP, seller);
            int held = cap.get("activeHeld").getAsInt();
            int sold = cap.get("soldToday").getAsInt();
            int used = cap.get("usedToday").getAsInt();
            helper.assertTrue(held == 300,
                    "在挂的 300 个铜锭算 activeHeld, 实得 " + held);
            helper.assertTrue(sold == 50,
                    "今日成交的 50 个算 soldToday, 实得 " + sold);
            helper.assertTrue(used == 350 && used == held + sold,
                    "两段之和必须恒等于 usedToday (350), 实得 " + used + " vs " + held + "+" + sold);
            helper.assertTrue(cap.get("remaining").getAsInt()
                            == MarketConstants.COPPER_IRON_DAILY_P2P_CAP - 350,
                    "剩余额度按总量算, 应为 " + (MarketConstants.COPPER_IRON_DAILY_P2P_CAP - 350)
                            + ", 实得 " + cap.get("remaining"));
            helper.assertTrue(engine.copperIronUsedToday(seller.getUUID()) == used,
                    "place 的 cap 判定必须吃拆分版求和出来的同一个总量, 实得 "
                            + engine.copperIronUsedToday(seller.getUUID()) + " vs " + used);
            helper.assertTrue(cap.get("resetsAt").getAsLong() == MarketEngine.startOfTomorrowEpochMillis(),
                    "resetsAt 是本地次日零点, 实得 " + cap.get("resetsAt"));

            // 日切模拟: 把窗口起点推到明天零点 (口径与引擎次日再调时逐字一致)。
            SoldOrListedSplit afterRollover = dao.soldOrListedSplitToday(seller.getUUID(),
                    MarketConstants.COPPER_IRON_ITEM_IDS, MarketEngine.startOfTomorrowEpochMillis());
            helper.assertTrue(afterRollover.soldToday() == 0,
                    "翻日后今日成交那段归零 —— resetsAt 承诺的正是这一段, 实得 " + afterRollover.soldToday());
            helper.assertTrue(afterRollover.activeHeld() == 300 && afterRollover.total() == 300,
                    "在挂的 300 不看 created_at, 翻日照样占额度 (这正是 resetsAt 不敢承诺总量的原因), 实得 "
                            + afterRollover);

            // 撤单才释放。
            engine.cancel(seller, listingId);
            JsonObject afterCancel = handle(MarketActions.P2P_CAP, seller);
            helper.assertTrue(afterCancel.get("activeHeld").getAsInt() == 0,
                    "撤单即释放在挂那段, 实得 " + afterCancel.get("activeHeld"));
            helper.assertTrue(afterCancel.get("soldToday").getAsInt() == 50
                            && afterCancel.get("usedToday").getAsInt() == 50,
                    "撤单不影响今日已成交那段 (它只随日切归零), 实得 " + afterCancel);

            helper.succeed();
        } finally {
            restoreMarket(prevMarket);
            MarketDb.close(dao);
            restoreEconomy(prevEconomy);
        }
    }

    // ============================================================
    // 14. F4 分页钳制: 负 pageSize 不得变成"把全部流水拉回来"
    // ============================================================

    /**
     * SQLite 的 {@code LIMIT -1} 等于不限行: 未钳制时一次 market.history 会把该玩家全部流水查回内存, 在主线程
     * 逐行序列化下发, 而这条 action 无冷却可被反复触发。造 105 行 (刻意超过单页上限 100) 才证明得了"钳住了"
     * 而不是"恰好全返"。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void historyClampsPagingInsteadOfDumpingEveryRow(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prevEconomy = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        MarketDaoSqlite dao = MarketDb.on(ledger.connection());
        MarketEngine engine = new MarketEngine(dao, helper.getLevel().getServer());
        MarketEngine prevMarket = swapMarket(engine);
        try {
            ServerPlayer self = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            UUID counterparty = UUID.randomUUID();
            for (int i = 0; i < HISTORY_ROWS; i++) {
                dao.insertTxn(200L + i, self.getUUID(), counterparty, "minecraft:diamond",
                        1, 10L, 10L, 0L, 1_000L + i);
            }
            long newestListingId = 200L + HISTORY_ROWS - 1;

            JsonObject unlimited = handle(MarketActions.HISTORY, self, pagePayload(0, -1));
            helper.assertTrue(unlimited.getAsJsonArray("transactions").size() == 1,
                    "pageSize=-1 会让 LIMIT -1 变成不限行, 必须被钳成 1 行, 实得 "
                            + unlimited.getAsJsonArray("transactions").size());
            helper.assertTrue(unlimited.get("pageSize").getAsInt() == 1,
                    "回执必须回显钳制后的 pageSize 而不是原样回显 -1, 实得 " + unlimited.get("pageSize"));
            helper.assertTrue(unlimited.get("total").getAsInt() == HISTORY_ROWS,
                    "total 是流水总条数, 不受分页钳制影响, 应为 " + HISTORY_ROWS + ", 实得 " + unlimited.get("total"));
            helper.assertTrue(unlimited.getAsJsonArray("transactions").get(0).getAsJsonObject()
                            .get("listingId").getAsLong() == newestListingId,
                    "钳制之后返回的仍是最新那笔 (钳的是行数不是排序), 实得 "
                            + unlimited.getAsJsonArray("transactions").get(0));

            JsonObject zero = handle(MarketActions.HISTORY, self, pagePayload(0, 0));
            helper.assertTrue(zero.get("pageSize").getAsInt() == 1
                            && zero.getAsJsonArray("transactions").size() == 1,
                    "pageSize=0 下钳到 1 行 (回空表会让前端以为没有流水), 实得 " + zero.get("pageSize"));

            JsonObject huge = handle(MarketActions.HISTORY, self, pagePayload(0, 1_000));
            helper.assertTrue(huge.get("pageSize").getAsInt() == 100,
                    "pageSize=1000 上钳到 100 (与既有单页上限同口径), 实得 " + huge.get("pageSize"));
            helper.assertTrue(huge.getAsJsonArray("transactions").size() == 100,
                    "上钳之后只返回 100 行, 实得 " + huge.getAsJsonArray("transactions").size());
            helper.assertTrue(huge.getAsJsonArray("transactions").size() < HISTORY_ROWS,
                    "必须严格少于全部 " + HISTORY_ROWS + " 行, 否则钳制没有生效");

            JsonObject negativePage = handle(MarketActions.HISTORY, self, pagePayload(-3, 5));
            helper.assertTrue(negativePage.get("page").getAsInt() == 0,
                    "page 下钳到 0 (负 offset 在 SQLite 里被当 0, 不如在入口收住), 实得 " + negativePage.get("page"));
            helper.assertTrue(negativePage.getAsJsonArray("transactions").size() == 5
                            && negativePage.getAsJsonArray("transactions").get(0).getAsJsonObject()
                                .get("listingId").getAsLong() == newestListingId,
                    "钳到第 0 页后拿到的就是首页 5 行, 实得 " + negativePage.getAsJsonArray("transactions").size());

            JsonObject byDefault = handle(MarketActions.HISTORY, self);
            helper.assertTrue(byDefault.get("pageSize").getAsInt() == 20
                            && byDefault.getAsJsonArray("transactions").size() == 20,
                    "钳制不得动缺省分页 (page=0 pageSize=20), 实得 " + byDefault.get("pageSize"));

            helper.succeed();
        } finally {
            restoreMarket(prevMarket);
            MarketDb.close(dao);
            restoreEconomy(prevEconomy);
        }
    }

    // ============================================================
    // 工具
    // ============================================================

    /**
     * 幂等注册 (范式同 {@code PlayerWebUiW1GameTests.ensurePlayerActionsRegistered}): 派发器的注册表是
     * <b>进程级静态</b>, register 用 putIfAbsent 守卫, 重复注册直接抛。故已注册就什么都不做。
     */
    private static void ensureMarketActionsRegistered() {
        if (WebUiServerDispatcher.resolve("market.list") == null) {
            MarketActions.registerAll();
        }
    }

    private static ItemStack card(int cardId, TarotQuality quality, UUID owner) {
        return TarotCardItem.create(TarotRegistry.TAROT_CARD.get(), cardId, quality, true, owner);
    }

    /**
     * 把一张牌的品质序号改成越界值 (等价于跨版本删档位 / 手改 NBT 的老牌)。
     *
     * 刻意不照抄 {@link TarotCardItem} 的私有键名 —— 照抄一份就是第二份真源, 改名之后本用例还会绿。改按
     * "刚盖进去的品质序号"反查落点: 造牌时 cardId 取 5、品质取 R (序号 0), 整份 NBT 里取值为 0 的 int 键
     * 因此只有品质一个 (EffectTooltipVersion 是 1)。
     */
    private static void corruptQualityOrdinal(ItemStack card, int badOrdinal) {
        CompoundTag tag = card.getTag();
        if (tag == null) {
            throw new IllegalStateException("create 出来的牌必须带 NBT, 无法构造越界品质的脏牌");
        }
        String qualityKey = null;
        for (String key : tag.getAllKeys()) {
            if (tag.contains(key, Tag.TAG_INT) && tag.getInt(key) == TarotQuality.R.ordinal()) {
                qualityKey = key;
                break;
            }
        }
        if (qualityKey == null) {
            throw new IllegalStateException("塔罗牌 NBT 里找不到品质序号键 (取值 0 的 int), 无法构造脏牌");
        }
        tag.putInt(qualityKey, badOrdinal);
    }

    /**
     * 造一个装着这些物品的潜影盒 (物品形态: 内容物存在 {@code BlockEntityTag.Items} 里)。
     *
     * 手写 NBT 而不是走 ShulkerBoxBlockEntity.saveToItem: 那要先在世界里摆一个方块实体, 而本用例要测的是
     * "服务端拿到一个物品栈之后怎么判", 与方块无关; 手写的形状同时就是走私者手改 NBT 时的形状。
     */
    private static ItemStack shulkerWith(ItemStack... contents) {
        CompoundTag[] entries = new CompoundTag[contents.length];
        for (int i = 0; i < contents.length; i++) {
            entries[i] = contents[i].save(new CompoundTag());
        }
        return shulkerWithRawEntries(entries);
    }

    /** 同上, 但直接给出每一行的原始复合标签 (用于构造坏行: 不存在的物品 id / 空标签)。 */
    private static ItemStack shulkerWithRawEntries(CompoundTag... entries) {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        ListTag items = new ListTag();
        for (int i = 0; i < entries.length; i++) {
            entries[i].putByte("Slot", (byte) i);
            items.add(entries[i]);
        }
        CompoundTag blockEntity = new CompoundTag();
        blockEntity.put("Items", items);
        box.getOrCreateTag().put("BlockEntityTag", blockEntity);
        return box;
    }

    /** 一行读不出物品的内容物 (id 指向不存在的注册项 -> ItemStack.of 兜成 EMPTY)。 */
    private static CompoundTag unknownItemEntry() {
        CompoundTag entry = new CompoundTag();
        entry.putString("id", "miningdim:no_such_item_at_all");
        entry.putByte("Count", (byte) 1);
        return entry;
    }

    /**
     * 同一个 itemId 在三处解出的 (v0, source) 必须逐字相同: 唯一入口 {@link MarketEngine#lookupBaseValue}、
     * market.feePreview 的回执、market.baseValue 的回执。拼成一个字符串比对, 是为了让失败信息直接给出
     * "谁和谁不一样"而不是三条各自为政的断言。
     */
    private static void assertSameAnchorEverywhere(GameTestHelper helper, ServerPlayer player,
                                                   String itemId, String expectedV0OrNull, String expectedSource) {
        String expected = (expectedV0OrNull == null ? "null" : expectedV0OrNull) + "/" + expectedSource;
        String fromEngine = anchorOf(MarketServices.marketEngine().lookupBaseValue(itemId));
        String fromPreview = anchorOf(handle(MarketActions.FEE_PREVIEW, player, feePayload(itemId, 50L, 2)));
        String fromBaseValue = anchorOf(handle(MarketActions.BASE_VALUE, player, itemIdPayload(itemId)));
        helper.assertTrue(expected.equals(fromEngine),
                itemId + " 的唯一分层入口应解出 " + expected + ", 实得 " + fromEngine);
        helper.assertTrue(expected.equals(fromPreview),
                "market.feePreview 的 v0/source 必须与唯一入口一致 (" + expected + "), 实得 " + fromPreview);
        helper.assertTrue(expected.equals(fromBaseValue),
                "market.baseValue 的 v0/source 必须与唯一入口一致 (" + expected + "), 实得 " + fromBaseValue);
    }

    /** 回执里的锚 (v0/source); 无锚时 v0 是 JSON null, 折成字面 "null"。 */
    private static String anchorOf(JsonObject receipt) {
        return (receipt.get("v0").isJsonNull() ? "null" : receipt.get("v0").getAsString())
                + "/" + receipt.get("source").getAsString();
    }

    /** 引擎解出的锚, 与上面同一种拼法 (两边形状一致才能直接比字符串)。 */
    private static String anchorOf(MarketEngine.BaseValueLookup lookup) {
        return (lookup.v0().isEmpty() ? "null" : Long.toString(lookup.v0().getAsLong()))
                + "/" + lookup.source();
    }

    /**
     * 一个"点查看得见 admin 覆盖、全表覆盖 map 却是空的"的 DAO 视图。
     *
     * 它是 F2 的变异探针: 唯一入口 {@link MarketEngine#lookupBaseValue} 走 {@code dao.getBaseValue} 点查,
     * 而被删掉的那份手抄分层走 {@code dao.allBaseValues()} 全表 map。装上这个视图之后, 两条路会解出不同的
     * V0 —— 谁把手抄分层搬回来, 用例当场挂。用动态代理而不是手写 17 个方法的委托类, 是因为要委托的是
     * {@link MarketDao} 的全部方法, 只有一个要改。
     */
    private static MarketDao daoHidingOverrideMap(MarketDao real) {
        return (MarketDao) Proxy.newProxyInstance(
                MarketDao.class.getClassLoader(),
                new Class<?>[]{MarketDao.class},
                (proxy, method, args) -> {
                    if ("allBaseValues".equals(method.getName())) {
                        return Map.of();
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException wrapped) {
                        // 反射给被调方法的异常包了一层; 剥掉再抛, 免得业务异常在测试里变成 InvocationTargetException。
                        throw wrapped.getCause();
                    }
                });
    }

    /** 读这张牌的品质是否会抛 (证明白名单的身份守卫不是装饰)。 */
    private static boolean throwsOnQuality(ItemStack card) {
        try {
            TarotCardItem.quality(card);
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }

    private static void assertCapScope(GameTestHelper helper, JsonArray scope) {
        helper.assertTrue(scope.size() == EXPECTED_CAP_SCOPE.length,
                "受限标的恰 " + EXPECTED_CAP_SCOPE.length + " 项 (铜铁的原矿/粗矿/锭三态), 实得 " + scope.size());
        for (int i = 0; i < EXPECTED_CAP_SCOPE.length; i++) {
            helper.assertTrue(EXPECTED_CAP_SCOPE[i].equals(scope.get(i).getAsString()),
                    "受限标的必须按字典序下发 (第 " + i + " 项应为 " + EXPECTED_CAP_SCOPE[i]
                            + ", 实得 " + scope.get(i).getAsString() + ") —— 顺序不定的话面板每次刷新都在跳");
        }
    }

    /**
     * 重置时刻必须落在服务器本地时区的<b>次日零点</b>。按属性验而不是照抄公式: 若实现退回
     * "今日零点 + 86400000", 夏令时切换那天的这个值就不再是本地零点, 本断言即挂。
     */
    private static void assertResetsAtIsLocalMidnightTomorrow(GameTestHelper helper, long resetsAt) {
        helper.assertTrue(resetsAt > System.currentTimeMillis(),
                "额度重置时刻必须在未来, 实得 " + resetsAt);
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime reset = Instant.ofEpochMilli(resetsAt).atZone(zone);
        helper.assertTrue(reset.toLocalTime().equals(LocalTime.MIDNIGHT),
                "重置时刻必须正好是本地零点 (不是 UTC 翻日也不是加 24 小时常数), 实得 " + reset);
        helper.assertTrue(reset.toLocalDate().equals(LocalDate.now(zone).plusDays(1)),
                "重置日期必须是本地的明天, 实得 " + reset.toLocalDate());
    }

    private static boolean containsListing(JsonArray rows, long listingId) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).getAsJsonObject().get("listingId").getAsLong() == listingId) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject handle(WebUiAction action, ServerPlayer sender) {
        return handle(action, sender, new JsonObject());
    }

    private static JsonObject handle(WebUiAction action, ServerPlayer sender, JsonObject payload) {
        return JsonParser.parseString(action.handle(sender, payload)).getAsJsonObject();
    }

    /** 调 action 并要求它抛业务拒绝; 没抛就地判失败 (返回值必非 null, 调用方可直接取字段)。 */
    private static WebUiBusinessException rejection(GameTestHelper helper, WebUiAction action,
                                                    ServerPlayer sender, JsonObject payload) {
        try {
            action.handle(sender, payload);
        } catch (WebUiBusinessException rejected) {
            return rejected;
        }
        helper.fail("该请求本应被业务拒绝, 实际却成功返回了: " + payload);
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    /** 直接调引擎挂单并要求它抛白名单拒绝 (M3 的服务端侧落点; 没抛就地判失败)。 */
    private static WebUiBusinessException placeRejection(GameTestHelper helper, MarketEngine engine,
                                                         ServerPlayer seller, int slot, int count, long unitPrice) {
        try {
            engine.place(seller, slot, count, unitPrice, MarketConstants.CURRENCY_CREDIT);
        } catch (WebUiBusinessException rejected) {
            return rejected;
        }
        helper.fail("槽位 " + slot + " 的物品本应被市场白名单拒绝挂单, 实际却挂上了");
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static JsonObject slotPayload(int slot) {
        JsonObject payload = new JsonObject();
        payload.addProperty("slot", slot);
        return payload;
    }

    private static JsonObject pagePayload(int page, int pageSize) {
        JsonObject payload = new JsonObject();
        payload.addProperty("page", page);
        payload.addProperty("pageSize", pageSize);
        return payload;
    }

    private static JsonObject itemIdPayload(String itemId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("itemId", itemId);
        return payload;
    }

    private static JsonObject feePayload(String itemId, long unitPrice, int count) {
        JsonObject payload = new JsonObject();
        payload.addProperty("itemId", itemId);
        payload.addProperty("unitPrice", unitPrice);
        payload.addProperty("count", count);
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

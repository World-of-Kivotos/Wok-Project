package com.miningdim.economy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.economy.EconomyConstants.HighValueOre;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * W5 玩家侧经济面板三条 economy.* action 的 GameTest。
 *
 * 主线是"面板上的数必须是账本与玩家态里的那个数":
 *  - economy.status 的冻结位随真实 {@link PlayerAbuseState#afkFrozen} 翻转, 距上次挖掘的 tick 数是真差值,
 *    从未挖过时发 null 而不是 0 (0 的意思是"刚刚挖过", 与"从没挖过"相反);
 *  - economy.today 的信用点那栏是衰减主闸打折<b>之前</b>的毛额 (与实发额刻意不等), 且当前档系数随累计推进;
 *  - economy.priceTable 的"下一颗值多少"必须是两层串联的真实结果: 逐矿 steering (第 65 颗钻石 ×0.97 = 485)
 *    再乘主闸档系数 (推进一整档后 485 × 0.6 = 291)。任何一层被漏掉都会被这两个数抓出来。
 *
 * 另锁一条纯查询纪律: 面板读到跨日的旧计数时按 0 展示, 但绝不替 tick 巡检把它清零 —— 一次查询洗掉玩家当日
 * 计数是灾难性副作用。
 *
 * 经济门面在用例内 swap 成内存库账本, 但 stateResolver 仍指向真实 {@link EconomySystem#playerState},
 * 逐字复现生产接线 (生产环境门面与面板读的就是同一份玩家态); 各自造一份态的话, 这几条断言测的就是两套假数据。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class EconomyWebUiGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "webui_economy";

    private static final String STATUS_ACTION = "economy.status";
    private static final String TODAY_ACTION = "economy.today";
    private static final String PRICE_TABLE_ACTION = "economy.priceTable";

    /** 浮点断言容差: 主闸是全程 double 积分, 291.0 这类值不保证逐位相等。 */
    private static final double EPSILON = 1.0E-6D;

    // ============================================================
    // 1. economy.status: 冻结位 + 距上次有效挖掘的真实 tick 差
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void statusMirrorsFreezeFlagAndTicksSinceLastMine(GameTestHelper helper) {
        EconomySystem system = EconomyWebUiActions.system();
        IEconomyService prev = swapEconomy(newLedgerBackedEconomy(system));
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            PlayerAbuseState state = system.playerState(player.getUUID());

            JsonObject fresh = handle(helper, STATUS_ACTION, player);
            helper.assertTrue(fresh.get("ticksSinceLastMine").isJsonNull(),
                    "从未有过有效挖掘时 ticksSinceLastMine 必须是 null (发 0 等于宣称刚刚挖过)");
            helper.assertTrue(!fresh.get("afkFrozen").getAsBoolean(),
                    "新登入玩家不处于挂机冻结态");
            helper.assertTrue(fresh.get("afkNoMineTicks").getAsInt() == EconomyConstants.AFK_NO_BREAK_TICKS
                            && fresh.get("afkNoMineTicks").getAsInt() == 2400,
                    "无挖掘判据阈值必须原样发 18.4 的 2400 tick, 实得 " + fresh.get("afkNoMineTicks").getAsInt());
            helper.assertTrue(fresh.get("ticksPerSecond").getAsInt() == 20,
                    "tick/秒换算率随服务端发, 前端不得自己写死");

            // 距上次挖掘 137 tick: 用例体在同一个服务器 tick 内跑完, 故差值恒为写入时的那个偏移量。
            long now = player.serverLevel().getGameTime();
            state.setLastBreakTick(now - 137L);
            state.setAfkFrozen(true);

            JsonObject frozen = handle(helper, STATUS_ACTION, player);
            helper.assertTrue(frozen.get("ticksSinceLastMine").getAsLong() == 137L,
                    "ticksSinceLastMine 必须是 gameTime 与 lastBreakTick 的真差值 137, 实得 "
                            + frozen.get("ticksSinceLastMine").getAsLong());
            helper.assertTrue(frozen.get("afkFrozen").getAsBoolean(),
                    "冻结位必须随真实玩家态翻成 true (面板不得自行判定挂机)");

            state.setAfkFrozen(false);
            helper.assertTrue(!handle(helper, STATUS_ACTION, player).get("afkFrozen").getAsBoolean(),
                    "解冻后同一条 action 必须立刻翻回 false (证明是实时读态而非一次性快照)");
            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 2. economy.today: 毛额口径 + 当前档系数
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void todayReportsPreDecayGrossAndCurrentBandFactor(GameTestHelper helper) {
        EconomySystem system = EconomyWebUiActions.system();
        IEconomyService prev = swapEconomy(newLedgerBackedEconomy(system));
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            IEconomyService economy = EconomyServices.economyService();
            long tier = EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER;

            JsonObject before = handle(helper, TODAY_ACTION, player);
            helper.assertTrue(before.get("todayCreditFaucetGross").getAsLong() == 0L,
                    "今日尚无入账时毛额是 0");
            helper.assertTrue(Math.abs(before.get("creditFaucetNextFactor").getAsDouble() - 1.0D) < EPSILON,
                    "第 0 档系数是 1.0 (未进入衰减), 实得 " + before.get("creditFaucetNextFactor").getAsDouble());
            helper.assertTrue(before.get("creditFaucetTier").getAsLong() == tier
                            && before.get("creditFaucetTier").getAsLong() == 60000L,
                    "档大小必须原样发 60000 毛收入/档");
            helper.assertTrue(EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY
                            .equals(before.get("creditFaucetKey").getAsString()),
                    "全部信用点 faucet 共用同一个计数键, 面板必须发这个键");

            // 跨两个完整档 (2 × 60000 毛): 只有这样毛额与实发额才不相等, 两者混用才会被本条抓出来。
            long net = economy.grantDaily(player, 2L * tier,
                    EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY, tier);
            long azure = economy.grantAzureDaily(player, 5L, EconomyConstants.AZURE_DAILY_FAUCET_CAP);
            helper.assertTrue(net > 0L && net < 2L * tier,
                    "前提校验: 跨档后实发额必须严格小于毛额 (实发 " + net + " / 毛额 " + (2L * tier) + ")");
            helper.assertTrue(azure == 5L, "前提校验: 未撞上限时青辉石应全额发放");

            JsonObject today = handle(helper, TODAY_ACTION, player);
            helper.assertTrue(today.get("todayCreditFaucetGross").getAsLong() == 2L * tier,
                    "todayCreditFaucetGross 必须是衰减前毛额 " + (2L * tier)
                            + ", 实得 " + today.get("todayCreditFaucetGross").getAsLong());
            helper.assertTrue(today.get("todayCreditFaucetGross").getAsLong() != net,
                    "毛额栏绝不能被写成实发额 (两者此刻分别是 " + (2L * tier) + " 与 " + net + ")");
            // 累计毛收入 120000 -> 第 2 档 -> 系数 0.6^2 = 0.36。系数写死的实现会停在 1.0。
            helper.assertTrue(Math.abs(today.get("creditFaucetNextFactor").getAsDouble() - 0.36D) < EPSILON,
                    "跨两档后当前档系数必须是 0.36, 实得 " + today.get("creditFaucetNextFactor").getAsDouble());
            helper.assertTrue(today.get("todayAzureIn").getAsLong() == 5L,
                    "青辉石那栏走硬截断, 记的就是实发额 5, 实得 " + today.get("todayAzureIn").getAsLong());
            helper.assertTrue(today.get("azureDailyCap").getAsLong() == EconomyConstants.AZURE_DAILY_FAUCET_CAP
                            && today.get("azureDailyCap").getAsLong() == 30L,
                    "青辉石日上限必须原样发 30 (硬截断, 不是衰减)");

            long dayStamp = economy.currentDayStamp();
            helper.assertTrue(today.get("dayStamp").getAsLong() == dayStamp,
                    "日戳必须取经济子系统的时钟, 不得另算一套 UTC epochDay");
            helper.assertTrue(today.get("resetsAtUtcMillis").getAsLong() == (dayStamp + 1L) * 86_400_000L,
                    "翻日时刻 = (当前 epochDay + 1) 天的 UTC 零点");
            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 3. economy.priceTable: 逐矿 steering × 主闸档系数 两层串联
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void priceTableChainsPerOreSteeringWithTheFaucetGate(GameTestHelper helper) {
        EconomySystem system = EconomyWebUiActions.system();
        IEconomyService prev = swapEconomy(newLedgerBackedEconomy(system));
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            IEconomyService economy = EconomyServices.economyService();
            PlayerAbuseState state = system.playerState(player.getUUID());
            long tier = EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER;
            // 与降频 tick 巡检同一口径地把玩家态对齐到今天 (对齐后计数才被面板视为"当日")。
            state.rolloverIfNewDay(economy.currentDayStamp());

            JsonObject clean = anchorOf(helper, handle(helper, PRICE_TABLE_ACTION, player), HighValueOre.DIAMOND);
            helper.assertTrue(clean.get("minedToday").getAsInt() == 0,
                    "今日一颗未挖时 minedToday 是 0");
            helper.assertTrue(clean.get("dailySoftCap").getAsInt() == EconomyConstants.DAILY_SOFTCAP_DIAMOND
                            && clean.get("dailySoftCap").getAsInt() == 64,
                    "钻石每日软上限必须原样发 64");
            helper.assertTrue(Math.abs(clean.get("anchorPrice").getAsDouble() - 500.0D) < EPSILON,
                    "钻石锚价必须是 ShopPriceTable 的 500");
            helper.assertTrue(clean.get("nextUnitGrossCredit").getAsLong() == 500L,
                    "软上限内的下一颗按全额锚价 500 收, 实得 " + clean.get("nextUnitGrossCredit").getAsLong());
            helper.assertTrue(Math.abs(clean.get("nextUnitNetCredit").getAsDouble() - 500.0D) < EPSILON,
                    "第 0 档主闸不打折, 净额应等于毛值 500, 实得 " + clean.get("nextUnitNetCredit").getAsDouble());

            // 当日已产 64 颗 (正好软上限): 第 65 颗进入逐矿 steering, 500 × 0.97 = 485。
            state.addDailyOreCount(HighValueOre.DIAMOND, 64);
            JsonObject steered = anchorOf(helper, handle(helper, PRICE_TABLE_ACTION, player), HighValueOre.DIAMOND);
            helper.assertTrue(steered.get("minedToday").getAsInt() == 64,
                    "minedToday 必须读事件路径正在写的那一份当日计数, 实得 " + steered.get("minedToday").getAsInt());
            helper.assertTrue(steered.get("nextUnitGrossCredit").getAsLong() == 485L,
                    "超软上限第 1 颗的逐矿单价是 floor(500 × 0.97) = 485, 实得 "
                            + steered.get("nextUnitGrossCredit").getAsLong());
            helper.assertTrue(Math.abs(steered.get("nextUnitNetCredit").getAsDouble() - 485.0D) < EPSILON,
                    "主闸仍在第 0 档时净额等于毛值 485, 实得 " + steered.get("nextUnitNetCredit").getAsDouble());

            // 再把主闸推进一整档 (累计毛收入 60000): 同一颗钻石的净额掉到 485 × 0.6 = 291, 而毛值不变。
            economy.grantDaily(player, tier, EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY, tier);
            JsonObject gated = anchorOf(helper, handle(helper, PRICE_TABLE_ACTION, player), HighValueOre.DIAMOND);
            helper.assertTrue(gated.get("nextUnitGrossCredit").getAsLong() == 485L,
                    "主闸不影响逐矿层, 毛值仍是 485, 实得 " + gated.get("nextUnitGrossCredit").getAsLong());
            helper.assertTrue(Math.abs(gated.get("nextUnitNetCredit").getAsDouble() - 291.0D) < EPSILON,
                    "推进一整档后净额是 485 × 0.6 = 291, 实得 " + gated.get("nextUnitNetCredit").getAsDouble());

            JsonObject table = handle(helper, PRICE_TABLE_ACTION, player);
            helper.assertTrue(table.get("todayCreditFaucetGross").getAsLong() == tier,
                    "价目表必须一并发主闸的自变量 (当日累计毛收入 " + tier + ")");

            // 三种高价矿一条不落, 且发的是产物 (锚价挂在产物上, 不是矿石方块上)。
            JsonObject gold = anchorOf(helper, table, HighValueOre.GOLD);
            helper.assertTrue("minecraft:gold_ingot".equals(gold.get("itemId").getAsString()),
                    "金的锚价挂在金锭上, 实得 " + gold.get("itemId").getAsString());
            helper.assertTrue(Math.abs(gold.get("anchorPrice").getAsDouble() - 120.0D) < EPSILON
                            && gold.get("dailySoftCap").getAsInt() == 256,
                    "金锭锚价 120 / 软上限 256");
            JsonObject scrap = anchorOf(helper, table, HighValueOre.NETHERITE_SCRAP);
            helper.assertTrue("minecraft:netherite_scrap".equals(scrap.get("itemId").getAsString()),
                    "残骸的锚价挂在下界残骸上, 实得 " + scrap.get("itemId").getAsString());
            helper.assertTrue(Math.abs(scrap.get("anchorPrice").getAsDouble() - 4500.0D) < EPSILON
                            && scrap.get("dailySoftCap").getAsInt() == 8,
                    "残骸锚价 4500 / 软上限 8");
            JsonObject diamond = anchorOf(helper, table, HighValueOre.DIAMOND);
            helper.assertTrue("minecraft:diamond".equals(diamond.get("itemId").getAsString())
                            && "item.minecraft.diamond".equals(diamond.get("descriptionId").getAsString()),
                    "发翻译键供前端出中文名 (专用服务端不加载 lang), 实得 "
                            + diamond.get("descriptionId").getAsString());
            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 4. 纯查询不得替 tick 巡检翻日
    // ============================================================

    /**
     * 跨日的旧计数按 0 展示, 但那一行必须原样留给降频 tick 去清 —— 面板顺手翻日等于一次纯查询把玩家的当日
     * 计数洗掉, 而清掉的那一刻收购价会凭空回到全额。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void priceTableReadsStaleDayAsZeroWithoutClearingIt(GameTestHelper helper) {
        EconomySystem system = EconomyWebUiActions.system();
        IEconomyService prev = swapEconomy(newLedgerBackedEconomy(system));
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            PlayerAbuseState state = system.playerState(player.getUUID());
            // 把玩家态钉在昨天并留下 64 颗的旧计数 (真实场景: 玩家昨天挖满后没再进过矿山, tick 巡检够不到他)。
            state.rolloverIfNewDay(EconomyServices.economyService().currentDayStamp() - 1L);
            state.addDailyOreCount(HighValueOre.DIAMOND, 64);

            JsonObject diamond = anchorOf(helper, handle(helper, PRICE_TABLE_ACTION, player), HighValueOre.DIAMOND);
            helper.assertTrue(diamond.get("minedToday").getAsInt() == 0,
                    "昨天的计数不是今天的产量, 面板必须按 0 读, 实得 " + diamond.get("minedToday").getAsInt());
            helper.assertTrue(diamond.get("nextUnitGrossCredit").getAsLong() == 500L,
                    "按 0 读之后下一颗回到全额 500, 实得 " + diamond.get("nextUnitGrossCredit").getAsLong());
            helper.assertTrue(state.dailyOreCount(HighValueOre.DIAMOND) == 64,
                    "纯查询绝不能把旧计数清零 (清零权归降频 tick), 实得 "
                            + state.dailyOreCount(HighValueOre.DIAMOND));
            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 5. 注册名 (契约面是 action 名, 不是 Java 常量)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void economyActionsAreRegisteredUnderContractNames(GameTestHelper helper) {
        ensureEconomyActionsRegistered(helper);
        for (String name : new String[] {STATUS_ACTION, TODAY_ACTION, PRICE_TABLE_ACTION}) {
            helper.assertTrue(WebUiServerDispatcher.resolve(name) != null,
                    name + " 必须由 EconomyWebUiActions.registerAll 注册进派发器");
        }
        helper.assertTrue(WebUiServerDispatcher.resolve("economy.pricetable") == null,
                "action 名大小写敏感, 不得另注册一个全小写别名");
        helper.assertTrue(WebUiServerDispatcher.resolve("economy.prices") == null,
                "价目表只有 economy.priceTable 一条 action, 不得另注册别名");
        helper.succeed();
    }

    // ============================================================
    // 工具
    // ============================================================

    /**
     * 幂等注册守卫 (派发器注册表是进程级静态, register 用 putIfAbsent 守卫)。
     *
     * 与其它 WebUi 用例的 ensure 不同, 这里不在测试侧补注册: registerAll 需要活的 {@link EconomySystem}
     * 实例, 测试侧现造一个顶上会让面板读到一份与事件路径无关的空玩家态 —— 那样测出来的绿是假的。
     * 没注册就是 {@link EconomySystem#register} 的接线掉了, 必须直接失败。
     */
    private static void ensureEconomyActionsRegistered(GameTestHelper helper) {
        if (WebUiServerDispatcher.resolve(STATUS_ACTION) == null) {
            helper.fail("economy.* action 未注册: EconomySystem.register 没有调用 EconomyWebUiActions.registerAll");
        }
    }

    private static JsonObject handle(GameTestHelper helper, String action, ServerPlayer sender) {
        ensureEconomyActionsRegistered(helper);
        WebUiAction handler = WebUiServerDispatcher.resolve(action);
        if (handler == null) {
            helper.fail("action " + action + " 未注册进派发器");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return JsonParser.parseString(handler.handle(sender, new JsonObject())).getAsJsonObject();
    }

    /** 取价目表里某矿种那一行; 缺行就地判失败 (三种高价矿一条都不许少)。 */
    private static JsonObject anchorOf(GameTestHelper helper, JsonObject table, HighValueOre ore) {
        for (JsonElement element : table.getAsJsonArray("anchors")) {
            JsonObject row = element.getAsJsonObject();
            if (ore.name().equals(row.get("oreId").getAsString())) {
                return row;
            }
        }
        helper.fail("价目表里缺 " + ore.name() + " 这一行");
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    /**
     * 内存库账本 + <b>真实</b>玩家态解析器: 逐字复现生产接线 (EconomySystem 把 this::playerState 交给
     * EconomyService), 使门面读到的挂机位与面板读到的当日计数是同一份态。
     */
    private static IEconomyService newLedgerBackedEconomy(EconomySystem system) {
        return new EconomyService(SqliteEconomyLedger.openInMemory(), system.abuseGuard(), system::playerState);
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
}

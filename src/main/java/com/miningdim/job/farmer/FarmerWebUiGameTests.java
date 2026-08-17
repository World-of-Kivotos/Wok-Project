package com.miningdim.job.farmer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.economy.AbuseGuard;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyConstants;
import com.miningdim.economy.EconomyLedger;
import com.miningdim.economy.EconomyService;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import com.miningdim.economy.PlayerAbuseState;
import com.miningdim.economy.SqliteEconomyLedger;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.job.JobId;
import com.miningdim.job.farmer.block.FarmerBlocks;
import com.miningdim.job.farmer.item.FarmerItems;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * W3 职业一的 job.farmer.state / job.farmer.sell GameTest。
 *
 * K5 (卖菜复用 /farmer sell 的服务端结算) 是本文件的主线, 副作用逐条真验:
 *  1. 卖成功后背包真少了那么多株、钱包真多了那么多信用点、当日已售株数真涨了 (三者数目互相锚定);
 *  2. 实发额是<b>过了全服 faucet 衰减主闸</b>之后的数 —— 先把主闸推进两整档再卖, 实发必须是 0.6^2 档,
 *     绕开 grantDaily 自己算就会得到全额, 本条即挂;
 *  3. 收购曲线跌到地板后 soldCount&gt;0 而 credited==0 <b>仍是成功回执</b> (物品照扣发币为 0), 不许当失败;
 *  4. 四条失败态各有稳定 errorCode 且<b>一株都不许扣</b>: INVALID_REQUEST / ECONOMY_OFFLINE /
 *     NOTHING_TO_SELL / SELL_LEVEL_TOO_LOW。
 *
 * 另: 契约 K5 写的"精通等级门 level&gt;=2"已落地, 由 {@link #farmerSellIsGatedByMasteryLevel} 锁死两侧
 * (1 级被专用码拒绝且零副作用, 到门槛立刻成交)。凡卖菜成功路径的用例都须先 {@code setFarmerLevel} 到门槛 ——
 * mock 玩家的 capability 默认 1 级, 不设就会撞在这道门上。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FarmerWebUiGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "webui_w3";

    private static final String STATE_ACTION = "job.farmer.state";
    private static final String SELL_ACTION = "job.farmer.sell";

    private static final String WHEAT_ID = "miningdim:farmer_wheat";

    /** 五档耕地的契约顺序与定稿数值 (写死在测试里: 面板就是照这张表画的)。 */
    private static final int[] TIER_UNLOCK_LEVELS = {1, 3, 5, 7, 9};
    private static final int[] TIER_GROWTH_MINUTES = {10, 8, 6, 5, 4};
    private static final int[] TIER_YIELD_PER_HARVEST = {2, 3, 4, 5, 6};
    private static final double[] TIER_WHEAT_PER_HOUR = {12.0D, 22.5D, 40.0D, 60.0D, 90.0D};

    // ============================================================
    // 1. job.farmer.state
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void farmerStateReportsCurveAnchorsAndFiveFarmlandTiers(GameTestHelper helper) {
        IEconomyService prev = currentEconomy();
        registerFreshEconomy();
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            JsonObject state = handle(helper, STATE_ACTION, player, new JsonObject());

            helper.assertTrue(state.get("level").getAsInt() == 1, "新号农夫 1 级");

            JsonObject crop = state.getAsJsonObject("crop");
            helper.assertTrue(WHEAT_ID.equals(crop.get("itemId").getAsString()),
                    "全服唯一可卖收获物是 " + WHEAT_ID + ", 实得 " + crop.get("itemId").getAsString());
            helper.assertTrue(FarmerItems.FARMER_WHEAT.get().getDescriptionId()
                            .equals(crop.get("descriptionId").getAsString()),
                    "crop 发翻译键而不是中文名 (专用服务端不加载 lang)");

            helper.assertTrue(state.get("soldToday").getAsInt() == 0, "新号当日已售 0 株");
            helper.assertTrue(state.get("dailySoftCap").getAsInt() == FarmerConstants.WHEAT_DAILY_SOFTCAP
                            && state.get("dailySoftCap").getAsInt() == 2160,
                    "软上限是 2160 株档, 不是 60000 CP 档 (两条曲线量纲不同), 实得 "
                            + state.get("dailySoftCap").getAsInt());
            helper.assertTrue(state.get("basePrice").getAsLong() == FarmerConstants.WHEAT_BASE_PRICE,
                    "锚价取农夫常量单一真源");
            helper.assertTrue(Math.abs(state.get("priceFloorRatio").getAsDouble()
                            - FarmerConstants.WHEAT_PRICE_FLOOR_RATIO) < 1.0E-9D,
                    "地板比例用来画'最多跌到哪', 取 EconomyConstants 的全服统一地板单一真源");
            helper.assertTrue(state.get("nextUnitPrice").getAsLong()
                            == FarmerWheatBuyback.wheatBuyPrice(1, FarmerConstants.WHEAT_BASE_PRICE),
                    "下一株单价 = 收购曲线第 (soldToday+1) 株的价 (含本株, 故 +1)");

            JsonArray tiers = state.getAsJsonArray("farmlandTiers");
            helper.assertTrue(tiers.size() == FarmerTier.values().length && tiers.size() == 5,
                    "耕地恒 5 档, 实得 " + tiers.size());
            for (int i = 0; i < FarmerTier.values().length; i++) {
                FarmerTier tier = FarmerTier.values()[i];
                JsonObject row = tiers.get(i).getAsJsonObject();
                helper.assertTrue(tier.id().equals(row.get("tierId").getAsString()),
                        "第 " + i + " 档必须是 " + tier.id() + ", 实得 " + row.get("tierId").getAsString());
                helper.assertTrue(FarmerBlocks.farmland(tier).get().getDescriptionId()
                                .equals(row.get("nameKey").getAsString()),
                        tier.id() + " 的名字发方块翻译键");
                helper.assertTrue(row.get("unlockLevel").getAsInt() == TIER_UNLOCK_LEVELS[i],
                        tier.id() + " 解锁等级应为 " + TIER_UNLOCK_LEVELS[i]);
                helper.assertTrue(row.get("growthMinutes").getAsInt() == TIER_GROWTH_MINUTES[i]
                                && row.get("yieldPerHarvest").getAsInt() == TIER_YIELD_PER_HARVEST[i],
                        tier.id() + " 的成长分钟/每次产量必须是定稿值");
                helper.assertTrue(Math.abs(row.get("wheatPerHour").getAsDouble() - TIER_WHEAT_PER_HOUR[i]) < 1.0E-9D,
                        tier.id() + " 每小时吞吐应为 " + TIER_WHEAT_PER_HOUR[i]
                                + ", 实得 " + row.get("wheatPerHour").getAsDouble());
                // L1 玩家只解锁最低那一档 (放置门控真源, 与耕地档解锁级同一判据)。
                helper.assertTrue(row.get("unlocked").getAsBoolean() == (i == 0),
                        tier.id() + " 在 1 级的解锁态错了 (只有 low 该是 true)");
            }

            // 升到 5 级后前三档解锁, 后两档仍锁 —— 解锁位是算出来的, 不是写死的。
            setFarmerLevel(player, 5);
            JsonArray atFive = handle(helper, STATE_ACTION, player, new JsonObject())
                    .getAsJsonArray("farmlandTiers");
            for (int i = 0; i < 5; i++) {
                helper.assertTrue(atFive.get(i).getAsJsonObject().get("unlocked").getAsBoolean() == (i <= 2),
                        "5 级时第 " + i + " 档的解锁态错了 (应解锁前三档)");
            }
            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 2. job.farmer.sell 成功路径: 先扣物后发钱
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void farmerSellChargesWheatBeforeCreditingAndMovesTheCurve(GameTestHelper helper) {
        IEconomyService prev = currentEconomy();
        EconomyLedger ledger = registerFreshEconomy();
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            // 卖菜有精通等级门 (SELL_MIN_MASTERY_LEVEL); 本例测的不是那道门, 先把等级设到门槛让它过去。
            setFarmerLevel(player, FarmerConstants.SELL_MIN_MASTERY_LEVEL);
            player.getInventory().clearContent();
            player.getInventory().add(new ItemStack(FarmerItems.FARMER_WHEAT.get(), 100));

            JsonObject result = handle(helper, SELL_ACTION, player, countPayload(40));

            helper.assertTrue(result.get("soldCount").getAsInt() == 40,
                    "只卖请求的 40 株, 实得 " + result.get("soldCount").getAsInt());
            // 收购 softCap 内全价 base=1, 主闸首档系数 1.0 -> 实发 40。
            helper.assertTrue(result.get("credited").getAsLong() == 40L,
                    "40 株全价且未触任何衰减档 -> 实发 40, 实得 " + result.get("credited").getAsLong());
            helper.assertTrue(wheatInInventory(player) == 60,
                    "背包必须真少 40 株 (先扣物后发钱), 实得 " + wheatInInventory(player));
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 40L,
                    "钱包余额必须真涨 40, 实得 " + ledger.balance(player.getUUID(), Currency.CREDIT));
            helper.assertTrue(result.get("soldToday").getAsInt() == 40,
                    "回执的当日已售是结算后重读的值, 实得 " + result.get("soldToday").getAsInt());
            helper.assertTrue(FarmerSavedData.get(player.server.overworld())
                            .wheatSoldToday(player.getUUID(), FarmerClock.currentUtcDayStamp()) == 40,
                    "收购曲线计数必须真的落进农夫持久层");
            helper.assertTrue(result.get("nextUnitPrice").getAsLong()
                            == FarmerWheatBuyback.wheatBuyPrice(41, FarmerConstants.WHEAT_BASE_PRICE),
                    "下一株单价按结算后的档位重算");
            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    /**
     * 实发额必须是过了全服每人每日信用点衰减主闸之后的数。
     *
     * 手法同 FarmerGameTests.sellSharesPerPlayerDailyFaucetCapWithOtherFaucets: 先用同一 faucetKey 把当日
     * 累计原始毛收入推满两整档, 再卖 100 株。绕开 grantDaily 自己按收购曲线发钱, 这里会得到 100 而不是 36。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void farmerSellCreditsGoThroughTheGlobalFaucetGate(GameTestHelper helper) {
        IEconomyService prev = currentEconomy();
        EconomyLedger ledger = registerFreshEconomy();
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            // 卖菜有精通等级门 (SELL_MIN_MASTERY_LEVEL); 本例测的不是那道门, 先把等级设到门槛让它过去。
            setFarmerLevel(player, FarmerConstants.SELL_MIN_MASTERY_LEVEL);
            player.getInventory().clearContent();
            long tier = FarmerConstants.DAILY_CREDIT_FAUCET_CAP;
            String sharedKey = FarmerConstants.WHEAT_SELL_FAUCET_KEY;
            helper.assertTrue(tier == EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER
                            && sharedKey.equals(EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY),
                    "卖菜用的档值与 faucetKey 就是全服主闸那一份");

            EconomyServices.economyService().grantDaily(player, tier, sharedKey, tier);
            EconomyServices.economyService().grantDaily(player, tier, sharedKey, tier);
            long balanceBefore = ledger.balance(player.getUUID(), Currency.CREDIT);

            player.getInventory().add(new ItemStack(FarmerItems.FARMER_WHEAT.get(), 100));
            JsonObject result = handle(helper, SELL_ACTION, player, countPayload(100));

            helper.assertTrue(result.get("soldCount").getAsInt() == 100, "100 株全部卖出");
            helper.assertTrue(FarmerWheatBuyback.totalBuyPrice(0, 100, FarmerConstants.WHEAT_BASE_PRICE) == 100L,
                    "前置校验: 收购曲线毛收是 100 (株档 softCap 未触发)");
            // 主闸第 2 档 0.6^2 = 0.36 -> floor(100*0.36) = 36。自己按毛收发钱会得到 100。
            helper.assertTrue(result.get("credited").getAsLong() == 36L,
                    "实发额必须是主闸第 2 档衰减后的 36 (毛收 100), 实得 " + result.get("credited").getAsLong());
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) - balanceBefore == 36L,
                    "钱包只该多 36 (回执与账本同源)");
            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    /**
     * 收购曲线跌到地板后单株单价下取整为 0: 物品照扣、发币为 0, 这是<b>成功</b>回执而不是失败。
     * 把它转成错误码会让玩家以为没卖出去, 而背包里的小麦确实已经没了。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void farmerSellStillSucceedsWhenTheCurveFloorsThePriceToZero(GameTestHelper helper) {
        IEconomyService prev = currentEconomy();
        EconomyLedger ledger = registerFreshEconomy();
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            // 卖菜有精通等级门 (SELL_MIN_MASTERY_LEVEL); 本例测的不是那道门, 先把等级设到门槛让它过去。
            setFarmerLevel(player, FarmerConstants.SELL_MIN_MASTERY_LEVEL);
            player.getInventory().clearContent();
            long today = FarmerClock.currentUtcDayStamp();
            // 把当日已售推到软上限: 之后每一株的单价 floor(1 * 0.97^n) 都是 0。
            FarmerSavedData.get(player.server.overworld())
                    .recordWheatSale(player.getUUID(), FarmerConstants.WHEAT_DAILY_SOFTCAP, today);
            helper.assertTrue(FarmerWheatBuyback.wheatBuyPrice(
                            FarmerConstants.WHEAT_DAILY_SOFTCAP + 1, FarmerConstants.WHEAT_BASE_PRICE) == 0L,
                    "前置校验: 软上限之后的第一株单价已经下取整到 0");
            player.getInventory().add(new ItemStack(FarmerItems.FARMER_WHEAT.get(), 5));

            JsonObject result = handle(helper, SELL_ACTION, player, countPayload(5));

            helper.assertTrue(result.get("soldCount").getAsInt() == 5,
                    "5 株照卖 (曲线到地板不等于拒收)");
            helper.assertTrue(result.get("credited").getAsLong() == 0L,
                    "发币 0 是边际收益归零的真实结果, 实得 " + result.get("credited").getAsLong());
            helper.assertTrue(wheatInInventory(player) == 0, "物品照扣: 背包里的 5 株必须真的没了");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 0L,
                    "曲线到地板时不得凭空发币");
            helper.assertTrue(result.get("soldToday").getAsInt() == FarmerConstants.WHEAT_DAILY_SOFTCAP + 5,
                    "当日已售仍要计数 (曲线继续往下走)");
            helper.assertTrue(result.get("nextUnitPrice").getAsLong() == 0L,
                    "下一株仍是 0 (如实显示, 不许回填成 basePrice)");
            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 3. job.farmer.sell 失败态: 三条稳定码, 一株都不许扣
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void farmerSellRejectsIllegalCountWithoutTouchingInventoryOrWallet(GameTestHelper helper) {
        IEconomyService prev = currentEconomy();
        EconomyLedger ledger = registerFreshEconomy();
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            player.getInventory().clearContent();
            player.getInventory().add(new ItemStack(FarmerItems.FARMER_WHEAT.get(), 10));

            WebUiBusinessException missing = rejection(helper, SELL_ACTION, player, new JsonObject());
            helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(missing.errorCode())
                            && "count".equals(missing.params().get("field")),
                    "缺 count 应回 INVALID_REQUEST 且在 params.field 里指名字段, 实得 "
                            + missing.errorCode() + " " + missing.params());

            WebUiBusinessException zero = rejection(helper, SELL_ACTION, player, countPayload(0));
            helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(zero.errorCode())
                            && "count".equals(zero.params().get("field"))
                            && "0".equals(zero.params().get("value")),
                    "count=0 是取值域外, 必须回显被拒的值, 实得 " + zero.params());

            WebUiBusinessException negative = rejection(helper, SELL_ACTION, player, countPayload(-3));
            helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(negative.errorCode()),
                    "负数 count 同样 INVALID_REQUEST, 实得 " + negative.errorCode());

            JsonObject fractional = new JsonObject();
            fractional.addProperty("count", 1.5D);
            WebUiBusinessException notAnInt = rejection(helper, SELL_ACTION, player, fractional);
            helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(notAnInt.errorCode())
                            && "count".equals(notAnInt.params().get("field")),
                    "非整数 count 必须在到达结算服务前就被拒, 实得 " + notAnInt.errorCode());

            helper.assertTrue(wheatInInventory(player) == 10,
                    "被拒的四次一株都不许扣, 实得剩余 " + wheatInInventory(player));
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 0L,
                    "被拒的四次一个信用点都不许发");
            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void farmerSellReportsNothingToSellWhenInventoryHasNoWheat(GameTestHelper helper) {
        IEconomyService prev = currentEconomy();
        EconomyLedger ledger = registerFreshEconomy();
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            // 卖菜有精通等级门 (SELL_MIN_MASTERY_LEVEL); 本例测的不是那道门, 先把等级设到门槛让它过去。
            setFarmerLevel(player, FarmerConstants.SELL_MIN_MASTERY_LEVEL);
            player.getInventory().clearContent();

            WebUiBusinessException empty = rejection(helper, SELL_ACTION, player, countPayload(8));
            helper.assertTrue(WebUiErrorCodes.NOTHING_TO_SELL.equals(empty.errorCode()),
                    "背包没货应回 NOTHING_TO_SELL (与经济掉线区分开), 实得 " + empty.errorCode());
            helper.assertTrue(WHEAT_ID.equals(empty.params().get("itemId")),
                    "拒绝必须指名缺的是哪种作物, 实得 " + empty.params());
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 0L,
                    "没卖出东西就不许发币");
            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void farmerSellReportsEconomyOfflineWithoutChargingWheat(GameTestHelper helper) {
        IEconomyService prev = currentEconomy();
        EconomyServices.reset(); // 经济未注册 = 本次不扣物不发币。
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            player.getInventory().clearContent();
            player.getInventory().add(new ItemStack(FarmerItems.FARMER_WHEAT.get(), 12));
            long today = FarmerClock.currentUtcDayStamp();
            int soldBefore = FarmerSavedData.get(player.server.overworld())
                    .wheatSoldToday(player.getUUID(), today);

            WebUiBusinessException offline = rejection(helper, SELL_ACTION, player, countPayload(12));
            helper.assertTrue(WebUiErrorCodes.ECONOMY_OFFLINE.equals(offline.errorCode()),
                    "经济未就绪应回 ECONOMY_OFFLINE, 实得 " + offline.errorCode());
            helper.assertTrue(offline.params().isEmpty(),
                    "经济掉线没有占位符实参 (它跟玩家的输入无关), 实得 " + offline.params());
            helper.assertTrue(wheatInInventory(player) == 12,
                    "经济掉线时一株都不许扣, 实得剩余 " + wheatInInventory(player));
            helper.assertTrue(FarmerSavedData.get(player.server.overworld())
                            .wheatSoldToday(player.getUUID(), today) == soldBefore,
                    "经济掉线时不许记进收购曲线计数");
            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 4. 契约与代码不符的一处: 卖菜没有精通等级门
    // ============================================================

    /**
     * 契约 K5 的"精通等级门 level&gt;=2"现已落地 (见 {@link FarmerWheatSellService#sell} 的
     * {@link FarmerConstants#SELL_MIN_MASTERY_LEVEL} 判据, 反洗钱身份门), 本用例随之从"立据无门"翻成"锁死有门"。
     *
     * 三条断言各锁一件事, 删掉服务层那道门则三条全挂:
     *  1. 1 级农夫被拒, 且 errorCode 是专用的 SELL_LEVEL_TOO_LOW 而非 NOTHING_TO_SELL —— 复用后者会让面板
     *     对着一个背包里明明有小麦的玩家说"没有可卖的东西";
     *  2. 拒绝时零副作用: 一株不扣、一分不发 (门排在扣料之前);
     *  3. 同一个玩家换成 2 级立刻卖得动 —— 证明拒绝的原因确实是等级而不是别的什么把这条路堵死了。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void farmerSellIsGatedByMasteryLevel(GameTestHelper helper) {
        IEconomyService prev = currentEconomy();
        EconomyLedger ledger = registerFreshEconomy();
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            player.getInventory().clearContent();
            player.getInventory().add(new ItemStack(FarmerItems.FARMER_WHEAT.get(), 3));
            helper.assertTrue(handle(helper, STATE_ACTION, player, new JsonObject())
                            .get("level").getAsInt() == 1,
                    "前置条件: 这是个 1 级农夫");

            WebUiBusinessException denied = rejection(helper, SELL_ACTION, player, countPayload(3));
            helper.assertTrue(WebUiErrorCodes.SELL_LEVEL_TOO_LOW.equals(denied.errorCode()),
                    "1 级卖菜必须回专用码 SELL_LEVEL_TOO_LOW, 实得 " + denied.errorCode());
            helper.assertTrue(wheatInInventory(player) == 3,
                    "被等级门拒绝时一株都不许扣, 实得剩 " + wheatInInventory(player));
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 0L,
                    "被等级门拒绝时一分都不许发, 实得 " + ledger.balance(player.getUUID(), Currency.CREDIT));

            // 同一玩家升到门槛等级: 立刻卖得动, 证明上面那条拒绝的成因确实是等级。
            setFarmerLevel(player, FarmerConstants.SELL_MIN_MASTERY_LEVEL);
            JsonObject result = handle(helper, SELL_ACTION, player, countPayload(3));
            helper.assertTrue(result.get("soldCount").getAsInt() == 3 && result.get("credited").getAsLong() == 3L,
                    "达到 SELL_MIN_MASTERY_LEVEL 后 3 株全价成交, 实得 soldCount="
                            + result.get("soldCount").getAsInt() + " credited=" + result.get("credited").getAsLong());
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 3L,
                    "过门后的信用点真进了钱包, 实得 " + ledger.balance(player.getUUID(), Currency.CREDIT));
            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void farmerActionsAreRegisteredUnderContractNames(GameTestHelper helper) {
        ensureFarmerActionsRegistered();
        helper.assertTrue(WebUiServerDispatcher.resolve(STATE_ACTION) != null
                        && WebUiServerDispatcher.resolve(SELL_ACTION) != null,
                "job.farmer.state 与 job.farmer.sell 必须由 FarmerWebUiActions.registerAll 注册进派发器");
        helper.assertTrue(WebUiServerDispatcher.resolve("farmer.sell") == null
                        && WebUiServerDispatcher.resolve("job.farmer.sellWheat") == null,
                "不得注册卖菜的别名 action");
        helper.succeed();
    }

    // ============================================================
    // 工具
    // ============================================================

    /** 幂等注册: 派发器注册表是进程级静态, register 用 putIfAbsent 守卫, 重复注册直接抛。 */
    private static void ensureFarmerActionsRegistered() {
        if (WebUiServerDispatcher.resolve(STATE_ACTION) == null) {
            FarmerWebUiActions.registerAll();
        }
    }

    private static JsonObject handle(GameTestHelper helper, String action, ServerPlayer sender, JsonObject payload) {
        return JsonParser.parseString(handler(helper, action).handle(sender, payload)).getAsJsonObject();
    }

    private static WebUiBusinessException rejection(GameTestHelper helper, String action,
                                                    ServerPlayer sender, JsonObject payload) {
        try {
            handler(helper, action).handle(sender, payload);
        } catch (WebUiBusinessException rejected) {
            return rejected;
        }
        helper.fail("该请求本应被业务拒绝, 实际却成功返回了: " + action + " " + payload);
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static WebUiAction handler(GameTestHelper helper, String action) {
        ensureFarmerActionsRegistered();
        WebUiAction handler = WebUiServerDispatcher.resolve(action);
        if (handler == null) {
            helper.fail("action " + action + " 未注册进派发器");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return handler;
    }

    private static JsonObject countPayload(int count) {
        JsonObject payload = new JsonObject();
        payload.addProperty("count", count);
        return payload;
    }

    private static int wheatInInventory(ServerPlayer player) {
        return player.getInventory().clearOrCountMatchingItems(
                stack -> stack.is(FarmerItems.FARMER_WHEAT.get()), 0, new SimpleContainer(0));
    }

    private static void setFarmerLevel(ServerPlayer player, int level) {
        MiningCapabilities.get(player)
                .orElseThrow(() -> new IllegalStateException("mock 玩家没有挂上矿山玩家数据 capability"))
                .jobProgress(JobId.FARMER).setLevel(level);
    }

    /** 每条用例一套全新的内存账本 + 空的滥用状态 (跨用例零串扰), 范式同 FarmerGameTests。 */
    private static EconomyLedger registerFreshEconomy() {
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        Function<UUID, PlayerAbuseState> resolver = id -> states.computeIfAbsent(id, k -> new PlayerAbuseState());
        EconomyServices.registerEconomyService(new EconomyService(ledger, new AbuseGuard(), resolver));
        return ledger;
    }

    /** 当前门面 (未注册返回 null), 用例结束后必须原样放回 —— 定位器是进程级静态, 留个空的会串到后面的批次。 */
    private static IEconomyService currentEconomy() {
        return EconomyServices.isRegistered() ? EconomyServices.economyService() : null;
    }

    /**
     * 发币抛出时: 已扣的小麦必须全额补回, 当日收购曲线不得被推深, 而异常本身仍要冒泡。
     *
     * 这条守的是一个真实的中间态 —— 小麦是内存里的背包操作, 没法跟着 SQLite 事务一起回滚, 而 grantDaily
     * 抛出时物品已经离手。不补的话玩家净损失一批作物, 且 Gateway 在 handler 之前就把 requestId 烧进了防重放
     * 窗口, 他连原样重试都做不到 (换新 requestId 重试则会再扣一批)。
     *
     * 把 FarmerWheatSellService 里那次 refundWheat 删掉, 本条立刻挂在"小麦必须全额补回"上。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void farmerSellRefundsWheatWhenPayoutFails(GameTestHelper helper) {
        IEconomyService prev = currentEconomy();
        registerFreshEconomy();
        IEconomyService working = EconomyServices.economyService();
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            // 卖菜有精通等级门 (SELL_MIN_MASTERY_LEVEL); 本例测的不是那道门, 先把等级设到门槛让它过去。
            setFarmerLevel(player, FarmerConstants.SELL_MIN_MASTERY_LEVEL);
            player.getInventory().clearContent();
            player.getInventory().add(new ItemStack(FarmerItems.FARMER_WHEAT.get(), 100));

            long today = FarmerClock.currentUtcDayStamp();
            FarmerSavedData data = FarmerSavedData.get(player.server.overworld());
            int soldBefore = data.wheatSoldToday(player.getUUID(), today);

            // 换成 grantDaily 必抛的门面 (模拟库被锁 / 磁盘满)。
            EconomyServices.reset();
            EconomyServices.registerEconomyService(new PayoutFailingEconomy(working));

            boolean threw = false;
            try {
                FarmerWheatSellService.sell(player, 40);
            } catch (IllegalStateException expected) {
                threw = true;
            }

            helper.assertTrue(threw, "发币失败必须让异常冒泡, 不许静默当成'卖出 0 株'");
            helper.assertTrue(wheatInInventory(player) == 100,
                    "已扣的 40 株必须全额补回, 实得剩余 " + wheatInInventory(player));
            helper.assertTrue(data.wheatSoldToday(player.getUUID(), today) == soldBefore,
                    "发币失败不得推深当日收购曲线, 实得 " + data.wheatSoldToday(player.getUUID(), today));
        } finally {
            restoreEconomy(prev);
        }
        helper.succeed();
    }

    /** 只让 grantDaily 抛的门面替身: 其余方法一律原样转发, 保证被测路径上只有这一处失败源。 */
    private record PayoutFailingEconomy(IEconomyService delegate) implements IEconomyService {

        @Override
        public long grantDaily(ServerPlayer player, long rawCredit, String faucetKey, long dailyCap) {
            throw new IllegalStateException("模拟发币失败: 账本事务未能提交");
        }

        @Override
        public long creditBalance(ServerPlayer player) {
            return delegate.creditBalance(player);
        }

        @Override
        public long heartstoneBalance(ServerPlayer player) {
            return delegate.heartstoneBalance(player);
        }

        @Override
        public boolean tryCharge(ServerPlayer player, com.miningdim.economy.Currency currency, long amount) {
            return delegate.tryCharge(player, currency, amount);
        }

        @Override
        public void grant(ServerPlayer player, com.miningdim.economy.Currency currency, long amount) {
            delegate.grant(player, currency, amount);
        }

        @Override
        public boolean tryChargeDaily(ServerPlayer player, com.miningdim.economy.Currency currency,
                                      long amount, String dailyKey, long dailyCap) {
            return delegate.tryChargeDaily(player, currency, amount, dailyKey, dailyCap);
        }

        @Override
        public long settleOreSale(ServerPlayer player, com.miningdim.economy.EconomyConstants.HighValueOre ore,
                                  int countSoFar, double basePrice) {
            return delegate.settleOreSale(player, ore, countSoFar, basePrice);
        }

        @Override
        public int recordMinedOreDrops(ServerPlayer player,
                                       net.minecraft.world.level.block.Block block, int producedCount) {
            return delegate.recordMinedOreDrops(player, block, producedCount);
        }

        @Override
        public long grantAzureDaily(ServerPlayer player, long amount, long dailyCap) {
            return delegate.grantAzureDaily(player, amount, dailyCap);
        }

        @Override
        public boolean isAfkFrozen(ServerPlayer player) {
            return delegate.isAfkFrozen(player);
        }
    }

    private static void restoreEconomy(IEconomyService prev) {
        if (prev != null) {
            EconomyServices.registerEconomyService(prev);
        } else {
            EconomyServices.reset();
        }
    }

}

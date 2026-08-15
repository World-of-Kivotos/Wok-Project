package com.miningdim.job.tarot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.economy.AbuseGuard;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyLedger;
import com.miningdim.economy.EconomyService;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import com.miningdim.economy.PlayerAbuseState;
import com.miningdim.economy.SqliteEconomyLedger;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.job.JobId;
import com.miningdim.job.tarot.craft.TarotCraftService;
import com.miningdim.job.tarot.pack.PackKind;
import com.miningdim.job.tarot.pack.TarotPackClock;
import com.miningdim.job.tarot.pack.TarotPackItem;
import com.miningdim.job.tarot.pack.TarotPackSavedData;
import com.miningdim.job.tarot.pack.TarotPackService;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * W4c 塔罗页的 job.tarot.state / job.tarot.buyPack GameTest。
 *
 * 三条主线, 每条都锁真实副作用:
 *  1. 持有量的两栏语义 ({@link #tarotStateSeparatesOwnerBoundCardsFromForeignOnes}): 别人名下的牌打不出,
 *     不计 owned; 但开包判重复看的是"背包里有没有同 cardId", 故仍要在 inInventory 里看得见。把 owner 过滤
 *     删掉, 或把两栏合成一栏, 本条立刻挂。
 *  2. 买包复用 {@link TarotPackService#buy} 的唯一结算 ({@link #tarotBuyPackChargesTheRightCurrencyAndDeliversPacks}):
 *     信用点/青辉石按包种分流、钱包真少、包真进背包且绑定买家、每日计数真涨。
 *  3. 三条失败态一分钱不动一个包不发: 余额不足 / 撞每日上限 / 入参非法。
 *
 * 冷却剩余量不在断言范围内 —— 服务端目前没有只读 peek (见交付报告 blockers), 面板绝不允许调
 * {@link TarotCooldownManager#tryUse} 去"看一眼", 那一调就把玩家的冷却吃掉了。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class TarotWebUiGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "webui_w4";

    private static final String STATE_ACTION = "job.tarot.state";
    private static final String BUY_PACK_ACTION = "job.tarot.buyPack";

    /** 用牌等级门的契约值, 写死在测试里 (spec 9.4: L1/L3/L5/L8/L10 对应 R/SR/SSR/UR/闪耀)。 */
    private static final int[] QUALITY_REQUIRED_LEVELS = {1, 3, 5, 8, 10};

    // ============================================================
    // 1. job.tarot.state 的形状: 22 张牌 + 5 档品质门
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tarotStateReportsTwentyTwoArcanaRowsAndQualityGates(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();

        JsonObject state = handle(helper, STATE_ACTION, player, new JsonObject());
        helper.assertTrue(state.get("level").getAsInt() == 1, "新号塔罗师 1 级");

        JsonArray deck = state.getAsJsonArray("deck");
        helper.assertTrue(deck.size() == TarotArcana.COUNT && deck.size() == 22,
                "大阿卡纳恒 22 行, 实得 " + deck.size());
        for (int i = 0; i < TarotArcana.COUNT; i++) {
            TarotArcana arcana = TarotArcana.values()[i];
            JsonObject row = deck.get(i).getAsJsonObject();
            helper.assertTrue(row.get("cardId").getAsInt() == i
                            && arcana.id().equals(row.get("arcanaId").getAsString()),
                    "第 " + i + " 行必须是 cardId=" + i + " 的 " + arcana.id()
                            + ", 实得 " + row.get("arcanaId").getAsString());
            helper.assertTrue(("tooltip.miningdim.tarot.arcana." + arcana.id())
                            .equals(row.get("nameKey").getAsString()),
                    arcana.id() + " 必须发牌面翻译键而不是中文 (专用服务端不加载 lang)");
            helper.assertTrue(row.getAsJsonArray("ownedByQuality").size() == TarotQuality.values().length,
                    arcana.id() + " 的持有数组恒 5 项 (下标 = 品质 ordinal), 实得 "
                            + row.getAsJsonArray("ownedByQuality").size());
            helper.assertTrue(row.get("owned").getAsInt() == 0 && row.get("inInventory").getAsInt() == 0,
                    "空背包时 " + arcana.id() + " 的两栏持有量都必须是 0");
        }

        JsonArray qualities = state.getAsJsonArray("qualities");
        helper.assertTrue(qualities.size() == TarotQuality.values().length && qualities.size() == 5,
                "品质恒 5 档, 实得 " + qualities.size());
        for (int i = 0; i < TarotQuality.values().length; i++) {
            TarotQuality quality = TarotQuality.values()[i];
            JsonObject row = qualities.get(i).getAsJsonObject();
            helper.assertTrue(quality.id().equals(row.get("qualityId").getAsString())
                            && row.get("tierIndex").getAsInt() == quality.tierIndex(),
                    "第 " + i + " 档必须是 " + quality.id() + " 且 tierIndex=" + quality.tierIndex());
            helper.assertTrue(("tooltip.miningdim.tarot.quality." + quality.id())
                            .equals(row.get("nameKey").getAsString()),
                    quality.id() + " 发品质翻译键而不是中文");
            helper.assertTrue(row.get("requiredLevel").getAsInt() == QUALITY_REQUIRED_LEVELS[i],
                    quality.id() + " 的用牌门应为 L" + QUALITY_REQUIRED_LEVELS[i]
                            + ", 实得 " + row.get("requiredLevel").getAsInt());
            helper.assertTrue(row.get("rawXp").getAsLong() == TarotLeveling.rawXpFor(quality),
                    quality.id() + " 的单牌原始经验取 config 实时值");
            helper.assertTrue(row.get("usable").getAsBoolean() == (i == 0),
                    "1 级塔罗师只打得出 R, " + quality.id() + " 的可用位错了");
        }

        // 门是算出来的不是写死的: L8 解锁到 UR, 闪耀仍锁在 L10。
        setTarotLevel(player, 8);
        JsonObject atEight = handle(helper, STATE_ACTION, player, new JsonObject());
        helper.assertTrue(atEight.get("level").getAsInt() == 8, "等级取职业进度真值");
        JsonArray gates = atEight.getAsJsonArray("qualities");
        for (int i = 0; i < TarotQuality.values().length; i++) {
            helper.assertTrue(gates.get(i).getAsJsonObject().get("usable").getAsBoolean() == (i <= 3),
                    "L8 应解锁 R/SR/SSR/UR 且闪耀仍锁, 第 " + i + " 档判错");
        }
        helper.succeed();
    }

    /**
     * 冷却栏: 三档满 CD 与 GCD 取 config 实时值; 每张牌的分档与闪耀 CD 取 datapack 真值。
     *
     * 期望值直接读 classpath 上的那 22 份 JSON (与被测实现的取数路径无关的独立来源), 故把分档映射写错、
     * 或把闪耀 CD 与普通 CD 弄反, 本条都会挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tarotStateReportsCooldownTableAndDatapackCategories(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        JsonObject state = handle(helper, STATE_ACTION, player, new JsonObject());

        JsonObject cooldowns = state.getAsJsonObject("cooldownTicks");
        helper.assertTrue(cooldowns.get("gcd").getAsInt() == TarotConfig.GCD_TICKS.get()
                        && cooldowns.get("utility").getAsInt() == TarotConfig.CD_UTILITY_TICKS.get()
                        && cooldowns.get("buff").getAsInt() == TarotConfig.CD_BUFF_TICKS.get()
                        && cooldowns.get("combat").getAsInt() == TarotConfig.CD_COMBAT_TICKS.get(),
                "四个 CD 旋钮必须取 TarotConfig 实时值, 实得 " + cooldowns);

        boolean loaded = state.get("cardDataLoaded").getAsBoolean();
        JsonArray deck = state.getAsJsonArray("deck");
        for (int i = 0; i < TarotArcana.COUNT; i++) {
            TarotArcana arcana = TarotArcana.values()[i];
            JsonObject row = deck.get(i).getAsJsonObject();
            helper.assertTrue(row.has("cooldownCategory") && row.has("shinyCooldownTicks"),
                    arcana.id() + " 的两栏必须始终存在 (无值时发 null, 不许整键丢掉)");
            if (loaded) {
                JsonObject raw = loadCardJson(arcana);
                String expectedCategory = raw.get("cooldownCategory").getAsString();
                int expectedShinyCd = raw.getAsJsonObject("shiny").get("cooldownTicks").getAsInt();
                helper.assertTrue(expectedCategory.equals(row.get("cooldownCategory").getAsString()),
                        arcana.id() + " 的 CD 分档应为 " + expectedCategory
                                + ", 实得 " + row.get("cooldownCategory"));
                helper.assertTrue(row.get("shinyCooldownTicks").getAsInt() == expectedShinyCd,
                        arcana.id() + " 的闪耀 CD 应为 " + expectedShinyCd + " tick, 实得 "
                                + row.get("shinyCooldownTicks"));
            } else {
                helper.assertTrue(row.get("cooldownCategory").isJsonNull()
                                && row.get("shinyCooldownTicks").isJsonNull(),
                        "牌效表未加载时 " + arcana.id() + " 的两栏必须是真 null (0 会被画成零冷却)");
            }
        }
        helper.succeed();
    }

    // ============================================================
    // 2. 持有量: 绑定本人的才算持有, 别人的牌只影响开包重复判定
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tarotStateSeparatesOwnerBoundCardsFromForeignOnes(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();
        UUID stranger = UUID.randomUUID();
        player.getInventory().add(card(TarotArcana.FOOL, TarotQuality.SSR, true, player.getUUID()));
        player.getInventory().add(card(TarotArcana.FOOL, TarotQuality.SSR, false, player.getUUID()));
        player.getInventory().add(card(TarotArcana.WORLD, TarotQuality.SHINY, true, player.getUUID()));
        player.getInventory().add(card(TarotArcana.MAGICIAN, TarotQuality.R, true, stranger));

        JsonArray deck = handle(helper, STATE_ACTION, player, new JsonObject()).getAsJsonArray("deck");

        JsonObject fool = deck.get(TarotArcana.FOOL.cardId()).getAsJsonObject();
        JsonArray foolCounts = fool.getAsJsonArray("ownedByQuality");
        helper.assertTrue(foolCounts.get(TarotQuality.SSR.ordinal()).getAsInt() == 2,
                "两张自己的 SSR 愚者必须计进 SSR 那一格, 实得 " + foolCounts);
        helper.assertTrue(foolCounts.get(TarotQuality.R.ordinal()).getAsInt() == 0
                        && foolCounts.get(TarotQuality.SHINY.ordinal()).getAsInt() == 0,
                "愚者只有 SSR 两张, 其余品质格必须是 0, 实得 " + foolCounts);
        helper.assertTrue(fool.get("owned").getAsInt() == 2 && fool.get("inInventory").getAsInt() == 2,
                "愚者两栏都应是 2, 实得 owned=" + fool.get("owned") + " inInventory=" + fool.get("inInventory"));

        JsonObject world = deck.get(TarotArcana.WORLD.cardId()).getAsJsonObject();
        helper.assertTrue(world.getAsJsonArray("ownedByQuality")
                        .get(TarotQuality.SHINY.ordinal()).getAsInt() == 1
                        && world.get("owned").getAsInt() == 1,
                "闪耀世界必须落在闪耀那一格 (品质按 NBT 真值分格)");

        JsonObject magician = deck.get(TarotArcana.MAGICIAN.cardId()).getAsJsonObject();
        helper.assertTrue(magician.get("owned").getAsInt() == 0,
                "别人名下的魔术师打不出 (owner 闸门), 不许计进持有, 实得 " + magician.get("owned"));
        helper.assertTrue(magician.get("inInventory").getAsInt() == 1,
                "但它就在背包里, 开包判重复看得见它, inInventory 必须是 1, 实得 "
                        + magician.get("inInventory"));
        helper.succeed();
    }

    /** 碎片余额与兑换成本: 与 {@code /tarot exchange} 数的是同一个计数器, 且每次调用实时重数背包。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tarotStateReportsShardBalanceFromTheExchangeCounter(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();
        player.getInventory().add(TarotCraftService.makeShards(17));

        JsonObject state = handle(helper, STATE_ACTION, player, new JsonObject());
        helper.assertTrue(state.get("shards").getAsInt() == 17
                        && state.get("shards").getAsInt() == TarotShardExchange.countShards(player),
                "碎片余额必须与兑换路径同一个计数器, 实得 " + state.get("shards"));
        helper.assertTrue(state.get("shardExchangeCost").getAsInt() == TarotConfig.SHARD_EXCHANGE_COST.get(),
                "兑换成本取 config 实时值");
        helper.assertTrue(state.get("duplicateShardRefund").getAsInt()
                        == TarotConfig.DUPLICATE_SHARD_REFUND.get(),
                "重复牌返还张数取 config 实时值");

        player.getInventory().add(TarotCraftService.makeShards(5));
        helper.assertTrue(handle(helper, STATE_ACTION, player, new JsonObject())
                        .get("shards").getAsInt() == 22,
                "再进 5 张碎片后必须变成 22 (证明是每次实时重数, 不是首屏快照)");
        helper.succeed();
    }

    // ============================================================
    // 3. 卡包经济位: 售价表 + 每日限购计数
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tarotStatePackTableMatchesPurchasePricesAndDailyCounter(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        JsonObject state = handle(helper, STATE_ACTION, player, new JsonObject());

        JsonArray packs = state.getAsJsonArray("packs");
        helper.assertTrue(packs.size() == PackKind.values().length && packs.size() == 3,
                "卡包恒 3 种, 实得 " + packs.size());
        for (int i = 0; i < PackKind.values().length; i++) {
            PackKind kind = PackKind.values()[i];
            JsonObject row = packs.get(i).getAsJsonObject();
            helper.assertTrue(kind.id().equals(row.get("packKind").getAsString()),
                    "第 " + i + " 种必须是 " + kind.id());
            helper.assertTrue(("miningdim:tarot_pack_" + kind.id()).equals(row.get("itemId").getAsString())
                            && ("item.miningdim.tarot_pack_" + kind.id())
                            .equals(row.get("nameKey").getAsString()),
                    kind.id() + " 的注册名与翻译键必须与真实物品一致, 实得 " + row.get("itemId"));
            helper.assertTrue(row.get("unitPrice").getAsLong() == TarotPackService.price(kind),
                    kind.id() + " 的单价必须等于购买路径实际收的 " + TarotPackService.price(kind));
            String expectedCurrency = kind == PackKind.SHINY ? "AZURE" : "CREDIT";
            helper.assertTrue(expectedCurrency.equals(row.get("currency").getAsString()),
                    kind.id() + " 应收 " + expectedCurrency + " (闪耀包永不收信用点), 实得 "
                            + row.get("currency"));
        }

        int cap = TarotConfig.DAILY_PACK_LIMIT.get();
        helper.assertTrue(cap >= 3, "前置条件: 每日购包上限至少 3 (当前 " + cap + ")");
        helper.assertTrue(state.get("packDailyLimit").getAsInt() == cap
                        && state.get("packsBoughtToday").getAsInt() == 0
                        && state.get("packsRemainingToday").getAsInt() == cap,
                "新号当日购包 0 个, 剩余 = 上限");

        TarotPackSavedData.get(player.server.overworld())
                .recordAcquired(player.getUUID(), 3, cap, TarotPackClock.currentUtcDayStamp());
        JsonObject after = handle(helper, STATE_ACTION, player, new JsonObject());
        helper.assertTrue(after.get("packsBoughtToday").getAsInt() == 3,
                "已购计数必须来自 TarotPackSavedData 真值, 实得 " + after.get("packsBoughtToday"));
        helper.assertTrue(after.get("packsRemainingToday").getAsInt() == cap - 3,
                "剩余额度应为 " + (cap - 3) + ", 实得 " + after.get("packsRemainingToday"));
        helper.succeed();
    }

    // ============================================================
    // 4. job.tarot.buyPack 成功路径: 钱真扣, 包真发, 计数真涨
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tarotBuyPackChargesTheRightCurrencyAndDeliversPacks(GameTestHelper helper) {
        IEconomyService prev = currentEconomy();
        EconomyLedger ledger = registerFreshEconomy();
        try {
            helper.assertFalse(TarotConfig.TEST_MODE.get(),
                    "前置条件: 测试模式必须关 (开着买包免费且不计日限, 本条断言的全是收费口径)");
            int cap = TarotConfig.DAILY_PACK_LIMIT.get();
            helper.assertTrue(cap >= 3, "前置条件: 每日购包上限至少 3 (本条要连买 3 个, 当前 " + cap + ")");
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            player.getInventory().clearContent();

            long commonPrice = TarotPackService.price(PackKind.COMMON);
            long shinyPrice = TarotPackService.price(PackKind.SHINY);
            long creditFunds = commonPrice * 2 + 77;
            long azureFunds = shinyPrice + 9;
            EconomyServices.economyService().grant(player, Currency.CREDIT, creditFunds);
            EconomyServices.economyService().grant(player, Currency.AZURE, azureFunds);

            JsonObject bought = handle(helper, BUY_PACK_ACTION, player, buyPayload("common", 2));
            helper.assertTrue(bought.get("count").getAsInt() == 2
                            && "common".equals(bought.get("packKind").getAsString()),
                    "买了 2 个普通包, 实得 " + bought.get("count"));
            helper.assertTrue(bought.get("totalPrice").getAsLong() == commonPrice * 2
                            && bought.get("unitPrice").getAsLong() == commonPrice
                            && "CREDIT".equals(bought.get("currency").getAsString()),
                    "普通包按信用点单价 x 张数收费, 实得 " + bought.get("totalPrice"));
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == creditFunds - commonPrice * 2,
                    "钱包必须真少 " + (commonPrice * 2) + ", 实得余额 "
                            + ledger.balance(player.getUUID(), Currency.CREDIT));
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.AZURE) == azureFunds,
                    "买普通包不许动青辉石");
            helper.assertTrue(packCount(player, PackKind.COMMON) == 2,
                    "背包里必须真进 2 个普通包, 实得 " + packCount(player, PackKind.COMMON));
            helper.assertTrue(boughtToday(player) == 2 && bought.get("packsBoughtToday").getAsInt() == 2,
                    "每日已购必须真涨到 2 (回执与持久层同源), 实得 " + boughtToday(player));
            helper.assertTrue(bought.get("packsRemainingToday").getAsInt() == cap - 2
                            && bought.get("packDailyLimit").getAsInt() == cap,
                    "剩余额度应为 " + (cap - 2) + ", 实得 " + bought.get("packsRemainingToday"));
            helper.assertTrue(player.getUUID().equals(TarotPackItem.owner(firstPack(player, PackKind.COMMON))),
                    "发出的包必须盖买家 ownerUUID (别人捡走开不了)");

            // 闪耀包走青辉石: 币种分流写错 (拿信用点扣) 本条即挂。
            JsonObject shiny = handle(helper, BUY_PACK_ACTION, player, buyPayload("shiny", 1));
            helper.assertTrue("AZURE".equals(shiny.get("currency").getAsString())
                            && shiny.get("totalPrice").getAsLong() == shinyPrice,
                    "闪耀包必须收青辉石, 实得 " + shiny.get("currency"));
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.AZURE) == azureFunds - shinyPrice,
                    "青辉石必须真少 " + shinyPrice + ", 实得 "
                            + ledger.balance(player.getUUID(), Currency.AZURE));
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == creditFunds - commonPrice * 2,
                    "买闪耀包不许再动信用点");
            helper.assertTrue(packCount(player, PackKind.SHINY) == 1 && boughtToday(player) == 3,
                    "闪耀包同样计进每日限购, 实得已购 " + boughtToday(player));
            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 5. 三条失败态: 一分钱不动, 一个包不发
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tarotBuyPackRejectsInsufficientFundsWithoutDeliveringPacks(GameTestHelper helper) {
        IEconomyService prev = currentEconomy();
        EconomyLedger ledger = registerFreshEconomy();
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            player.getInventory().clearContent();
            long price = TarotPackService.price(PackKind.COMMON);
            helper.assertTrue(price > 0L, "前置条件: 普通包售价 > 0 (免费包无从验证余额不足)");
            // 差 1 个信用点。售价恰为 1 时余额留 0, 此时不能调 grant —— 入账 0 是非法金额, 会抛 EconomyException。
            if (price > 1L) {
                EconomyServices.economyService().grant(player, Currency.CREDIT, price - 1);
            }

            WebUiBusinessException poor = rejection(helper, BUY_PACK_ACTION, player, buyPayload("common", 1));
            helper.assertTrue(WebUiErrorCodes.INSUFFICIENT_FUNDS.equals(poor.errorCode()),
                    "余额不足应回 INSUFFICIENT_FUNDS, 实得 " + poor.errorCode());
            helper.assertTrue("CREDIT".equals(poor.params().get("currency"))
                            && Long.toString(price).equals(poor.params().get("totalPrice"))
                            && "common".equals(poor.params().get("packKind")),
                    "拒绝必须指名缺哪种币、缺多少, 实得 " + poor.params());
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == price - 1,
                    "被拒时一分钱都不许扣, 实得余额 " + ledger.balance(player.getUUID(), Currency.CREDIT));
            helper.assertTrue(packCount(player, PackKind.COMMON) == 0,
                    "扣款失败必须不发包, 实得 " + packCount(player, PackKind.COMMON) + " 个");
            helper.assertTrue(boughtToday(player) == 0,
                    "被拒的一次不许计进每日限购, 实得 " + boughtToday(player));
            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tarotBuyPackRejectsWhenTheDailyLimitIsExhausted(GameTestHelper helper) {
        IEconomyService prev = currentEconomy();
        EconomyLedger ledger = registerFreshEconomy();
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            player.getInventory().clearContent();
            int cap = TarotConfig.DAILY_PACK_LIMIT.get();
            helper.assertTrue(cap >= 1, "前置条件: 每日购包上限至少 1 (当前 " + cap + ")");
            // 钱管够: 这样被拒只可能来自日限, 不会与余额不足混淆。
            long funds = TarotPackService.price(PackKind.COMMON) * 10 + 1000;
            EconomyServices.economyService().grant(player, Currency.CREDIT, funds);
            TarotPackSavedData.get(player.server.overworld())
                    .recordAcquired(player.getUUID(), cap, cap, TarotPackClock.currentUtcDayStamp());

            WebUiBusinessException capped = rejection(helper, BUY_PACK_ACTION, player, buyPayload("common", 1));
            helper.assertTrue(WebUiErrorCodes.RATE_LIMITED.equals(capped.errorCode()),
                    "撞每日购包上限应回 RATE_LIMITED (暂借; 专属码见交付报告), 实得 " + capped.errorCode());
            helper.assertTrue("0".equals(capped.params().get("remainingToday"))
                            && Integer.toString(cap).equals(capped.params().get("dailyLimit"))
                            && "1".equals(capped.params().get("requested")),
                    "拒绝必须带上剩余额度与上限, 实得 " + capped.params());
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == funds,
                    "日限拦截发生在扣款之前, 余额必须原封不动, 实得 "
                            + ledger.balance(player.getUUID(), Currency.CREDIT));
            helper.assertTrue(packCount(player, PackKind.COMMON) == 0, "撞上限时一个包都不许发");
            helper.assertTrue(boughtToday(player) == cap,
                    "被拒的一次不许再推高已购计数, 实得 " + boughtToday(player));
            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tarotBuyPackRejectsIllegalPayloadWithoutTouchingWallet(GameTestHelper helper) {
        IEconomyService prev = currentEconomy();
        EconomyLedger ledger = registerFreshEconomy();
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            player.getInventory().clearContent();
            long funds = TarotPackService.price(PackKind.COMMON) * 5 + 500;
            EconomyServices.economyService().grant(player, Currency.CREDIT, funds);

            JsonObject noKind = new JsonObject();
            noKind.addProperty("count", 1);
            WebUiBusinessException missingKind = rejection(helper, BUY_PACK_ACTION, player, noKind);
            helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(missingKind.errorCode())
                            && "kind".equals(missingKind.params().get("field")),
                    "缺 kind 应回 INVALID_REQUEST 且指名字段, 实得 "
                            + missingKind.errorCode() + " " + missingKind.params());

            WebUiBusinessException badKind = rejection(helper, BUY_PACK_ACTION, player,
                    buyPayload("legendary", 1));
            helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(badKind.errorCode())
                            && "kind".equals(badKind.params().get("field"))
                            && "legendary".equals(badKind.params().get("value")),
                    "未知包种必须回显被拒的值, 实得 " + badKind.params());

            JsonObject noCount = new JsonObject();
            noCount.addProperty("kind", "common");
            WebUiBusinessException missingCount = rejection(helper, BUY_PACK_ACTION, player, noCount);
            helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(missingCount.errorCode())
                            && "count".equals(missingCount.params().get("field")),
                    "缺 count 同样 INVALID_REQUEST, 实得 " + missingCount.params());

            WebUiBusinessException zero = rejection(helper, BUY_PACK_ACTION, player, buyPayload("common", 0));
            helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(zero.errorCode())
                            && "0".equals(zero.params().get("value")),
                    "count=0 是取值域外, 实得 " + zero.params());

            WebUiBusinessException tooMany = rejection(helper, BUY_PACK_ACTION, player, buyPayload("common", 65));
            helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(tooMany.errorCode())
                            && "65".equals(tooMany.params().get("value")),
                    "count=65 越过单次上限 64, 必须在到达购买服务前就被拒 (裸 IAE 没有 errorCode), 实得 "
                            + tooMany.errorCode() + " " + tooMany.params());

            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == funds,
                    "被拒的五次一分钱都不许扣, 实得余额 "
                            + ledger.balance(player.getUUID(), Currency.CREDIT));
            helper.assertTrue(packCount(player, PackKind.COMMON) == 0 && boughtToday(player) == 0,
                    "被拒的五次不许发包也不许计数");
            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 6. 注册名
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tarotActionsAreRegisteredUnderContractNames(GameTestHelper helper) {
        ensureTarotActionsRegistered();
        helper.assertTrue(WebUiServerDispatcher.resolve(STATE_ACTION) != null
                        && WebUiServerDispatcher.resolve(BUY_PACK_ACTION) != null,
                "job.tarot.state 与 job.tarot.buyPack 必须由 TarotWebUiActions.registerAll 注册进派发器");
        helper.assertTrue(WebUiServerDispatcher.resolve("job.tarot.buy") == null
                        && WebUiServerDispatcher.resolve("tarot.state") == null
                        && WebUiServerDispatcher.resolve("job.tarot.openPack") == null,
                "塔罗页只有这两条 action, 不得注册别名 (开包仍是物品右键, 没有 action 入口)");
        helper.succeed();
    }

    // ============================================================
    // 工具
    // ============================================================

    /**
     * 注册守卫。<b>刻意不在测试侧补注册</b>: 补上了, "TarotSystem.register 忘了调 TarotWebUiActions.registerAll"
     * 这一类装配缺陷就永远测不出来 —— 把生产侧那一行删掉, 本文件全绿, 而真服上前端调 job.tarot.* action 只会拿到
     * 派发器的 "unknown Web UI action" 失败回执, 整个面板全黑。
     *
     * 没注册就是 TarotSystem.register 的接线掉了, 直接炸。
     */
    private static void ensureTarotActionsRegistered() {
        if (WebUiServerDispatcher.resolve(STATE_ACTION) == null) {
            throw new IllegalStateException(
                    "job.tarot.* action 未注册: TarotSystem.register 没有调用 TarotWebUiActions.registerAll");
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
        ensureTarotActionsRegistered();
        WebUiAction handler = WebUiServerDispatcher.resolve(action);
        if (handler == null) {
            helper.fail("action " + action + " 未注册进派发器");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return handler;
    }

    private static JsonObject buyPayload(String kind, int count) {
        JsonObject payload = new JsonObject();
        payload.addProperty("kind", kind);
        payload.addProperty("count", count);
        return payload;
    }

    private static ItemStack card(TarotArcana arcana, TarotQuality quality, boolean upright, UUID owner) {
        return TarotCardItem.create(TarotRegistry.TAROT_CARD.get(), arcana.cardId(), quality, upright, owner);
    }

    private static int packCount(ServerPlayer player, PackKind kind) {
        Item item = packItem(kind);
        int total = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static ItemStack firstPack(ServerPlayer player, PackKind kind) {
        Item item = packItem(kind);
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty() && stack.getItem() == item) {
                return stack;
            }
        }
        throw new IllegalStateException("背包里没有 " + kind.id() + " 卡包");
    }

    private static Item packItem(PackKind kind) {
        return switch (kind) {
            case COMMON -> TarotRegistry.PACK_COMMON.get();
            case ADVANCED -> TarotRegistry.PACK_ADVANCED.get();
            case SHINY -> TarotRegistry.PACK_SHINY.get();
        };
    }

    /** 当日已购包数 (持久层真值, 与购买路径同一口径)。 */
    private static int boughtToday(ServerPlayer player) {
        return TarotPackSavedData.get(player.server.overworld())
                .acquiredToday(player.getUUID(), TarotPackClock.currentUtcDayStamp());
    }

    private static void setTarotLevel(ServerPlayer player, int level) {
        MiningCapabilities.get(player)
                .orElseThrow(() -> new IllegalStateException("mock 玩家没有挂上矿山玩家数据 capability"))
                .jobProgress(JobId.TAROT).setLevel(level);
    }

    /**
     * 直接从 classpath 读那 22 份牌效 JSON (手法同 {@code TarotGameTests.loadCard}): 期望值必须来自与被测实现
     * 无关的独立来源, 拿 TarotRuntime 的加载器当期望等于用被测代码验被测代码。
     */
    private static JsonObject loadCardJson(TarotArcana arcana) {
        ResourceLocation key = arcana.dataKey();
        String path = "/data/" + key.getNamespace() + "/" + key.getPath() + ".json";
        try (InputStream in = TarotWebUiGameTests.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("tarot card resource not found on classpath: " + path);
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed reading tarot card resource: " + path, e);
        }
    }

    /** 每条用例一套全新的内存账本 + 空的滥用状态 (跨用例零串扰), 范式同 FarmerWebUiGameTests。 */
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

    private static void restoreEconomy(IEconomyService prev) {
        if (prev != null) {
            EconomyServices.registerEconomyService(prev);
        } else {
            EconomyServices.reset();
        }
    }
}

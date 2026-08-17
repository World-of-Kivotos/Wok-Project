package com.miningdim.quest;

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
import com.miningdim.quest.objective.TurnInItemObjective;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** quest.* WebUI 契约的强断言 GameTest。 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class QuestWebUiGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "quest_webui";
    private static final String BOARD_ACTION = "quest.board";
    private static final String CLAIM_ACTION = "quest.claim";
    private static final String REFRESH_ACTION = "quest.refresh";
    private static final String TURN_IN_ACTION = "quest.turnIn";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void boardRowsMatchTheAuthoritativeQuestPool(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        JsonObject result = handle(helper, BOARD_ACTION, player, new JsonObject());
        JsonArray daily = result.getAsJsonArray("daily");

        helper.assertTrue(daily.size() == QuestConfig.DAILY_SLOTS.get(),
                "日常行数必须等于配置槽位数 " + QuestConfig.DAILY_SLOTS.get() + ", 实得 " + daily.size());
        QuestPool pool = QuestServices.service().pool();
        for (int i = 0; i < daily.size(); i++) {
            JsonObject row = daily.get(i).getAsJsonObject();
            String questId = row.get("questId").getAsString();
            QuestDefinition definition = Objects.requireNonNull(pool.byId(questId),
                    "WebUI 返回了内容池中不存在的任务 id: " + questId);
            helper.assertTrue(row.get("requiredCount").getAsInt() == definition.objective().requiredCount(),
                    questId + " 的 requiredCount 必须来自内容池, 实得 "
                            + row.get("requiredCount").getAsInt() + ", 应为 "
                            + definition.objective().requiredCount());
            helper.assertTrue(row.get("creditReward").getAsLong() == QuestRewards.creditFor(definition),
                    questId + " 的 creditReward 必须等于权威奖励公式, 实得 "
                            + row.get("creditReward").getAsLong() + ", 应为 "
                            + QuestRewards.creditFor(definition));
            // 物品奖励与信用点同为任务卡上的对外承诺, 同样按权威真源锁死 (面板照这个数画, 不许分叉)。
            assertItemRewardMatchesSource(helper, row, definition);
        }
        helper.succeed();
    }

    /**
     * 物品奖励档位必须真的随来源分档, 不是一律发同一个值。
     *
     * 单开这条的原因是变异实测: 只用 daily 那一组断言时, 把 tier 写死成 "IRON" 能让 1267 条全绿通过 ——
     * 因为没有任何用例跑过 DIAMOND 档的行, 那条"必须等于 QuestItemRewards.tier(source)"的断言对档位区分
     * 是空的。这里显式取两侧: daily 必须 IRON, weekly 必须 DIAMOND, 且两者必须不相等 (最后这条锁死"写死成
     * 任意一个字面量"这整类退化, 不管写死的是哪一个)。附魔书掉率同理取两侧比对。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void boardItemRewardTierActuallyDiffersPerSource(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        JsonObject result = handle(helper, BOARD_ACTION, player, new JsonObject());

        JsonArray daily = result.getAsJsonArray("daily");
        JsonArray weekly = result.getAsJsonArray("weekly");
        helper.assertTrue(daily.size() > 0 && weekly.size() > 0,
                "本用例要求日常与周常各至少一行才能比出档位差异, 实得 daily=" + daily.size()
                        + " weekly=" + weekly.size());

        JsonObject dailyReward = daily.get(0).getAsJsonObject().getAsJsonObject("itemReward");
        JsonObject weeklyReward = weekly.get(0).getAsJsonObject().getAsJsonObject("itemReward");
        String dailyTier = dailyReward.get("tier").getAsString();
        String weeklyTier = weeklyReward.get("tier").getAsString();

        helper.assertTrue(QuestItemRewards.Tier.IRON.name().equals(dailyTier),
                "日常任务必须报 IRON 档, 实得 " + dailyTier);
        helper.assertTrue(QuestItemRewards.Tier.DIAMOND.name().equals(weeklyTier),
                "周常任务必须报 DIAMOND 档, 实得 " + weeklyTier);
        helper.assertFalse(dailyTier.equals(weeklyTier),
                "日常与周常的物品档位必须不同 —— 相同即说明 tier 被写死成了某个字面量, 实得两侧都是 " + dailyTier);

        double dailyChance = dailyReward.get("bookChance").getAsDouble();
        double weeklyChance = weeklyReward.get("bookChance").getAsDouble();
        helper.assertTrue(dailyChance == QuestConfig.DAILY_BOOK_CHANCE.get()
                        && weeklyChance == QuestConfig.WEEKLY_BOOK_CHANCE.get(),
                "两档附魔书掉率必须分别取各自的配置键, 实得 daily=" + dailyChance + " weekly=" + weeklyChance);
        helper.assertFalse(dailyChance == weeklyChance,
                "两档掉率相同即说明 bookChance 没按来源取, 实得均为 " + dailyChance);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void boardMarksTurnInObjectivesWithoutGuessingFromText(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        QuestService service = QuestServices.service();
        QuestDefinition turnIn = Objects.requireNonNull(service.pool().byId("daily.turnin.rotten"));
        QuestDefinition mining = Objects.requireNonNull(service.pool().byId("daily.mine.iron"));
        service.boardOf(player).restorePeriodic(
                QuestClock.currentUtcDayStamp(),
                List.of(new QuestProgress(turnIn), new QuestProgress(mining)),
                QuestClock.currentUtcWeekStamp(),
                List.of());

        JsonArray daily = handle(helper, BOARD_ACTION, player, new JsonObject()).getAsJsonArray("daily");
        JsonObject turnInRow = rowById(daily, turnIn.id());
        JsonObject miningRow = rowById(daily, mining.id());
        helper.assertTrue(turnInRow.get("turnIn").getAsBoolean(),
                "上交类任务必须回 turnIn=true: " + turnIn.id());
        helper.assertTrue(!miningRow.get("turnIn").getAsBoolean(),
                "挖矿任务必须回 turnIn=false: " + mining.id());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void turnInReturnsExactSuccessAndNotFoundShapes(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        QuestService service = QuestServices.service();
        QuestDefinition definition = Objects.requireNonNull(service.pool().byId("daily.turnin.rotten"));
        TurnInItemObjective objective = (TurnInItemObjective) definition.objective();
        QuestBoard board = service.boardOf(player);
        board.restorePeriodic(
                QuestClock.currentUtcDayStamp(), List.of(new QuestProgress(definition)),
                QuestClock.currentUtcWeekStamp(), List.of());
        player.getInventory().clearContent();
        int offered = 7;
        helper.assertTrue(player.getInventory().add(new ItemStack(objective.item(), offered)),
                "空背包必须能放入本用例的上交物品");

        JsonObject turnedIn = handle(helper, TURN_IN_ACTION, player, questIdPayload(definition.id()));
        helper.assertTrue("TURNED_IN".equals(turnedIn.get("outcome").getAsString()),
                "背包有物品时 quest.turnIn 必须回 TURNED_IN, 实得 " + turnedIn);
        helper.assertTrue(definition.id().equals(turnedIn.get("questId").getAsString())
                        && definition.title().equals(turnedIn.get("title").getAsString()),
                "上交成功回执必须保留任务 id 与标题, 实得 " + turnedIn);
        helper.assertTrue(turnedIn.get("count").getAsInt() == offered,
                "上交回执 count 必须是本次实扣 " + offered + ", 实得 " + turnedIn.get("count"));
        helper.assertTrue(Objects.requireNonNull(board.find(definition.id())).count() == offered,
                "上交动作必须把实扣数推进到权威任务进度");
        helper.assertTrue(countInMainInventory(player, objective.item()) == 0,
                "上交动作必须从背包精确扣掉本次上交物品");

        String missingId = "daily.missing.webui";
        JsonObject missing = handle(helper, TURN_IN_ACTION, player, questIdPayload(missingId));
        helper.assertTrue("NOT_FOUND".equals(missing.get("outcome").getAsString())
                        && missingId.equals(missing.get("questId").getAsString()),
                "不存在的上交任务必须回 NOT_FOUND 并原样回显 id, 实得 " + missing);
        helper.assertTrue(missing.has("title") && missing.get("title").isJsonNull(),
                "NOT_FOUND 的 title 必须显式为 JSON null, 不能缺键, 实得 " + missing);
        helper.assertTrue(missing.get("count").getAsInt() == 0,
                "NOT_FOUND 的 count 必须精确为 0, 实得 " + missing.get("count"));
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void claimRefusesIncompleteQuestWithoutChangingBalance(GameTestHelper helper) {
        IEconomyService previous = currentEconomy();
        registerFreshEconomy();
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            QuestService service = QuestServices.service();
            QuestDefinition definition = Objects.requireNonNull(service.pool().byId("daily.mine.iron"));
            service.boardOf(player).restorePeriodic(
                    QuestClock.currentUtcDayStamp(), List.of(new QuestProgress(definition)),
                    QuestClock.currentUtcWeekStamp(), List.of());

            IEconomyService economy = EconomyServices.economyService();
            long before = economy.creditBalance(player);
            JsonObject result = handle(helper, CLAIM_ACTION, player, questIdPayload(definition.id()));

            helper.assertTrue("NOT_COMPLETE".equals(result.get("outcome").getAsString()),
                    "未完成任务必须回 NOT_COMPLETE, 实得 " + result.get("outcome").getAsString());
            helper.assertTrue(result.get("credit").getAsLong() == 0L,
                    "未完成任务回执不得声称发了信用点, 实得 " + result.get("credit").getAsLong());
            helper.assertTrue(economy.creditBalance(player) == before,
                    "未完成任务领取后余额必须逐分不变, 前 " + before + " 后 "
                            + economy.creditBalance(player));
        } finally {
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void claimNotFoundKeepsExplicitNullAndEmptyItems(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        String missingId = "daily.missing.webui";
        JsonObject result = handle(helper, CLAIM_ACTION, player, questIdPayload(missingId));

        helper.assertTrue("NOT_FOUND".equals(result.get("outcome").getAsString())
                        && missingId.equals(result.get("questId").getAsString()),
                "不存在的任务必须回 NOT_FOUND 并原样回显 id, 实得 " + result);
        helper.assertTrue(result.has("title") && result.get("title").isJsonNull(),
                "claim NOT_FOUND 的 title 必须显式为 JSON null, 不能缺键, 实得 " + result);
        helper.assertTrue(result.get("credit").getAsLong() == 0L,
                "claim NOT_FOUND 的 credit 必须精确为 0, 实得 " + result.get("credit"));
        helper.assertTrue(result.has("items") && result.get("items").isJsonArray()
                        && result.getAsJsonArray("items").size() == 0,
                "claim NOT_FOUND 的 items 必须是显式空数组, 实得 " + result);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void claimPaysTheExactRewardReportedByTheAction(GameTestHelper helper) {
        IEconomyService previous = currentEconomy();
        registerFreshEconomy();
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            QuestService service = QuestServices.service();
            QuestDefinition definition = Objects.requireNonNull(service.pool().byId("daily.mine.iron"));
            service.boardOf(player).restorePeriodic(
                    QuestClock.currentUtcDayStamp(),
                    List.of(new QuestProgress(definition, definition.objective().requiredCount(), false)),
                    QuestClock.currentUtcWeekStamp(), List.of());

            IEconomyService economy = EconomyServices.economyService();
            long before = economy.creditBalance(player);
            JsonObject result = handle(helper, CLAIM_ACTION, player, questIdPayload(definition.id()));
            long expected = QuestRewards.creditFor(definition);
            long reported = result.get("credit").getAsLong();

            helper.assertTrue("CLAIMED".equals(result.get("outcome").getAsString()),
                    "达标任务必须回 CLAIMED, 实得 " + result.get("outcome").getAsString());
            helper.assertTrue(reported == expected,
                    "回执 credit 必须等于权威奖励 " + expected + ", 实得 " + reported);
            helper.assertTrue(economy.creditBalance(player) - before == reported,
                    "钱包增量必须与回执逐分一致, 回执 " + reported + ", 实际增量 "
                            + (economy.creditBalance(player) - before));
        } finally {
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void claimProjectsGuaranteedMaterialAndEnchantedBook(GameTestHelper helper) {
        IEconomyService previousEconomy = currentEconomy();
        double previousBookChance = QuestConfig.DAILY_BOOK_CHANCE.get();
        registerFreshEconomy();
        QuestConfig.DAILY_BOOK_CHANCE.set(1.0D);
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            player.getInventory().clearContent();
            QuestService service = QuestServices.service();
            QuestDefinition definition = Objects.requireNonNull(service.pool().byId("daily.mine.iron"));
            service.boardOf(player).restorePeriodic(
                    QuestClock.currentUtcDayStamp(),
                    List.of(new QuestProgress(definition, definition.objective().requiredCount(), false)),
                    QuestClock.currentUtcWeekStamp(), List.of());

            JsonObject result = handle(helper, CLAIM_ACTION, player, questIdPayload(definition.id()));
            JsonArray items = result.getAsJsonArray("items");
            helper.assertTrue("CLAIMED".equals(result.get("outcome").getAsString()),
                    "强制附魔书掉率后达标任务必须领取成功, 实得 " + result);
            helper.assertTrue(items.size() == 2,
                    "掉率 100% 时 items 必须精确包含一份材料与一本附魔书, 实得 " + items);

            JsonObject bookRow = null;
            for (int i = 0; i < items.size(); i++) {
                JsonObject itemRow = items.get(i).getAsJsonObject();
                ResourceLocation itemId = new ResourceLocation(itemRow.get("itemId").getAsString());
                Item registeredItem = ForgeRegistries.ITEMS.getValue(itemId);
                helper.assertTrue(registeredItem != null,
                        "items 中每个 itemId 都必须能在 Forge 物品注册表解析, 实得 " + itemId);
                if (registeredItem == null) {
                    throw new IllegalStateException("unreachable: registered item assertion already failed");
                }
                helper.assertTrue(registeredItem.getDescriptionId().equals(
                                itemRow.get("descriptionId").getAsString()),
                        itemId + " 的 descriptionId 必须来自实际 ItemStack, 实得 " + itemRow);
                helper.assertTrue(itemRow.get("count").getAsInt() > 0,
                        itemId + " 的奖励数量必须为正, 实得 " + itemRow.get("count"));
                if (registeredItem == Items.ENCHANTED_BOOK) {
                    bookRow = itemRow;
                }
            }

            helper.assertTrue(bookRow != null, "items 必须含 minecraft:enchanted_book 行, 实得 " + items);
            if (bookRow == null) {
                throw new IllegalStateException("unreachable: enchanted book assertion already failed");
            }
            helper.assertTrue(bookRow.get("count").getAsInt() == 1,
                    "附魔书奖励数量必须精确为 1, 实得 " + bookRow.get("count"));
            helper.assertTrue(bookRow.has("enchantments") && bookRow.get("enchantments").isJsonArray()
                            && bookRow.getAsJsonArray("enchantments").size() == 1,
                    "附魔书必须投影唯一的 enchantments 条目, 删除附魔序列化时本断言必须失败, 实得 " + bookRow);
            JsonObject enchantmentRow = bookRow.getAsJsonArray("enchantments").get(0).getAsJsonObject();
            String enchantmentId = enchantmentRow.get("id").getAsString();
            int level = enchantmentRow.get("level").getAsInt();
            Enchantment enchantment = ForgeRegistries.ENCHANTMENTS.getValue(new ResourceLocation(enchantmentId));
            helper.assertTrue(enchantment != null,
                    "附魔注册名必须能在 Forge 注册表解析, 实得 " + enchantmentId);
            helper.assertTrue(QuestItemRewards.bookPool().stream().anyMatch(
                            drop -> drop.enchantment() == enchantment && drop.level() == level),
                    "附魔 id/level 必须逐字对应权威奖励池条目, 实得 " + enchantmentRow);
        } finally {
            QuestConfig.DAILY_BOOK_CHANCE.set(previousBookChance);
            restoreEconomy(previousEconomy);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void refreshRejectsOutOfRangeSlotBeforeCharging(GameTestHelper helper) {
        IEconomyService previous = currentEconomy();
        registerFreshEconomy();
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            QuestBoard board = QuestServices.service().boardOf(player);
            long cost = QuestRewards.refreshCost(QuestSource.DAILY);
            helper.assertTrue(cost > 0L, "本用例要求日常重摇费用为正, 实得 " + cost);
            EconomyServices.economyService().grant(player, Currency.CREDIT, cost * 2L);
            long before = EconomyServices.economyService().creditBalance(player);

            JsonObject payload = new JsonObject();
            payload.addProperty("source", "daily");
            payload.addProperty("slot", board.daily().size());
            WebUiBusinessException rejected = rejection(helper, REFRESH_ACTION, player, payload);

            helper.assertTrue(WebUiErrorCodes.SLOT_OUT_OF_RANGE.equals(rejected.errorCode()),
                    "越界槽位必须回 SLOT_OUT_OF_RANGE, 实得 " + rejected.errorCode());
            helper.assertTrue(EconomyServices.economyService().creditBalance(player) == before,
                    "越界请求必须先拒后扣, 余额前 " + before + " 后 "
                            + EconomyServices.economyService().creditBalance(player));
        } finally {
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void refreshReturnsExactInsufficientAndReplacementShapes(GameTestHelper helper) {
        IEconomyService previous = currentEconomy();
        registerFreshEconomy();
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            QuestBoard board = QuestServices.service().boardOf(player);
            int slot = 0;
            String beforeId = board.daily().get(slot).definition().id();
            long cost = QuestRewards.refreshCost(QuestSource.DAILY);
            helper.assertTrue(cost > 0L, "本用例要求日常重摇费用为正, 实得 " + cost);
            JsonObject payload = refreshPayload("daily", slot);

            JsonObject insufficient = handle(helper, REFRESH_ACTION, player, payload);
            helper.assertTrue("NOT_ENOUGH_CREDIT".equals(insufficient.get("outcome").getAsString()),
                    "零余额重摇必须回 NOT_ENOUGH_CREDIT, 实得 " + insufficient);
            helper.assertTrue(insufficient.get("cost").getAsLong() == cost,
                    "余额不足回执仍须报告权威重摇费用 " + cost + ", 实得 " + insufficient.get("cost"));
            helper.assertTrue(insufficient.has("replacement")
                            && insufficient.get("replacement").isJsonNull(),
                    "余额不足时 replacement 必须显式为 JSON null, 不能缺键, 实得 " + insufficient);
            helper.assertTrue(beforeId.equals(board.daily().get(slot).definition().id()),
                    "余额不足不得替换原槽任务 " + beforeId);

            EconomyServices.economyService().grant(player, Currency.CREDIT, cost);
            JsonObject refreshed = handle(helper, REFRESH_ACTION, player, payload);
            helper.assertTrue("REFRESHED".equals(refreshed.get("outcome").getAsString()),
                    "余额足够时重摇必须回 REFRESHED, 实得 " + refreshed);
            helper.assertTrue(refreshed.get("cost").getAsLong() == cost,
                    "成功重摇回执 cost 必须等于实扣 " + cost + ", 实得 " + refreshed.get("cost"));
            helper.assertTrue(refreshed.has("replacement")
                            && refreshed.get("replacement").isJsonObject(),
                    "成功重摇必须带 replacement 任务行, 实得 " + refreshed);
            QuestProgress replacement = board.daily().get(slot);
            helper.assertTrue(!beforeId.equals(replacement.definition().id()),
                    "成功重摇必须真的换掉原槽任务 " + beforeId);
            assertRowMatchesProgress(helper, refreshed.getAsJsonObject("replacement"), replacement);
            helper.assertTrue(EconomyServices.economyService().creditBalance(player) == 0L,
                    "按精确费用充值后成功重摇应把余额扣回 0, 实得 "
                            + EconomyServices.economyService().creditBalance(player));
        } finally {
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void refreshRejectsSpecialWithStructuredInvalidRequest(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        JsonObject payload = new JsonObject();
        payload.addProperty("source", "special");
        payload.addProperty("slot", 0);

        WebUiBusinessException rejected = rejection(helper, REFRESH_ACTION, player, payload);
        helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(rejected.errorCode()),
                "special 来源必须回 INVALID_REQUEST, 实得 " + rejected.errorCode());
        helper.assertTrue("source".equals(rejected.params().get("field"))
                        && "special".equals(rejected.params().get("value")),
                "非法来源必须带 params.field=source 与原值, 实得 " + rejected.params());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyQuestActionRejectsWhileQuestServicesIsInactive(GameTestHelper helper) {
        QuestService previous = QuestServices.service();
        QuestServices.reset();
        try {
            Map<String, JsonObject> payloads = Map.of(
                    BOARD_ACTION, new JsonObject(),
                    CLAIM_ACTION, questIdPayload("daily.mine.iron"),
                    TURN_IN_ACTION, questIdPayload("daily.turnin.rotten"),
                    REFRESH_ACTION, refreshPayload("daily", 0));
            for (Map.Entry<String, JsonObject> entry : payloads.entrySet()) {
                WebUiBusinessException rejected = rejection(
                        helper, entry.getKey(), MockGameTestPlayers.makeMockServerPlayerWithChannel(helper),
                        entry.getValue());
                helper.assertTrue(WebUiErrorCodes.QUEST_DISABLED.equals(rejected.errorCode()),
                        entry.getKey() + " 在 QuestServices inactive 时必须统一回 QUEST_DISABLED, 实得 "
                                + rejected.errorCode());
                helper.assertTrue(!rejected.retrySameOpeningId() && rejected.params().isEmpty(),
                        entry.getKey() + " 的 QUEST_DISABLED 必须是不可同 id 重试且无 params, 实得 "
                                + rejected.params());
            }
        } finally {
            QuestServices.register(previous);
        }
        helper.succeed();
    }

    private static JsonObject rowById(JsonArray rows, String questId) {
        for (int i = 0; i < rows.size(); i++) {
            JsonObject row = rows.get(i).getAsJsonObject();
            if (questId.equals(row.get("questId").getAsString())) {
                return row;
            }
        }
        throw new IllegalStateException("quest.board 未返回预置任务 " + questId);
    }

    /**
     * 物品奖励三字段必须逐个来自权威真源, 不许前端按 source 自己推档位。
     *
     * 删掉 itemRewardRow 的下发 -> row 少一个键, 上面的字段集断言先挂; 把 tier 改成写死的字面量或让它与
     * QuestItemRewards.tier 分叉 -> 本方法挂。bookChance 用 == 精确比: 它就是同一个 config 读出来的 double,
     * 中间不该有任何换算, 有换算就是精度损失, 而玩家看到的百分比正是照这个数画的。
     */
    private static void assertItemRewardMatchesSource(GameTestHelper helper, JsonObject row,
                                                      QuestDefinition definition) {
        JsonObject reward = row.getAsJsonObject("itemReward");
        Set<String> expectedKeys = Set.of("tier", "materialStacks", "bookChance");
        helper.assertTrue(reward.keySet().equals(expectedKeys),
                "itemReward 必须只含 tier/materialStacks/bookChance 三字段, 实得 " + reward.keySet());

        QuestItemRewards.Tier expectedTier = QuestItemRewards.tier(definition.source());
        helper.assertTrue(expectedTier.name().equals(reward.get("tier").getAsString()),
                definition.id() + " 的物品档位必须取 QuestItemRewards.tier(" + definition.source()
                        + ")=" + expectedTier + ", 实得 " + reward.get("tier").getAsString());
        helper.assertTrue(reward.get("materialStacks").getAsInt()
                        == QuestItemRewards.GUARANTEED_MATERIAL_STACKS,
                "必得材料份数必须取权威常量 " + QuestItemRewards.GUARANTEED_MATERIAL_STACKS
                        + ", 实得 " + reward.get("materialStacks").getAsInt());
        double expectedChance = QuestItemRewards.bookChance(definition.source());
        helper.assertTrue(reward.get("bookChance").getAsDouble() == expectedChance,
                definition.id() + " 的附魔书掉率必须等于 QuestItemRewards.bookChance("
                        + definition.source() + ")=" + expectedChance
                        + ", 实得 " + reward.get("bookChance").getAsDouble());
    }

    private static void assertRowMatchesProgress(GameTestHelper helper, JsonObject row,
                                                 QuestProgress progress) {
        QuestDefinition definition = progress.definition();
        Set<String> expectedKeys = Set.of(
                "questId", "title", "objective", "difficulty", "count", "requiredCount",
                "complete", "claimed", "turnIn", "creditReward", "itemReward");
        helper.assertTrue(row.keySet().equals(expectedKeys),
                "replacement 必须只含完整 QuestRow 十一字段, 实得 " + row.keySet());
        assertItemRewardMatchesSource(helper, row, definition);
        helper.assertTrue(definition.id().equals(row.get("questId").getAsString())
                        && definition.title().equals(row.get("title").getAsString())
                        && definition.objective().describe().equals(row.get("objective").getAsString()),
                "replacement 的 id/title/objective 必须来自新任务定义, 实得 " + row);
        helper.assertTrue(row.get("difficulty").getAsInt() == definition.difficulty()
                        && row.get("requiredCount").getAsInt() == definition.objective().requiredCount()
                        && row.get("creditReward").getAsLong() == QuestRewards.creditFor(definition),
                "replacement 的难度/目标数/奖励必须来自权威任务定义, 实得 " + row);
        helper.assertTrue(row.get("count").getAsInt() == progress.count()
                        && row.get("complete").getAsBoolean() == progress.isComplete()
                        && row.get("claimed").getAsBoolean() == progress.claimed(),
                "replacement 的进度状态必须与任务板新槽一致, 实得 " + row);
        helper.assertTrue(row.get("turnIn").getAsBoolean()
                        == (definition.objective() instanceof TurnInItemObjective),
                "replacement.turnIn 必须按目标真实类型投影, 实得 " + row.get("turnIn"));
    }

    private static JsonObject handle(GameTestHelper helper, String action,
                                     ServerPlayer sender, JsonObject payload) {
        return JsonParser.parseString(handler(helper, action).handle(sender, payload)).getAsJsonObject();
    }

    private static WebUiBusinessException rejection(GameTestHelper helper, String action,
                                                    ServerPlayer sender, JsonObject payload) {
        try {
            handler(helper, action).handle(sender, payload);
        } catch (WebUiBusinessException rejected) {
            return rejected;
        }
        helper.fail("该请求本应被业务拒绝, 实际却成功返回了: " + action);
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static WebUiAction handler(GameTestHelper helper, String action) {
        WebUiAction handler = WebUiServerDispatcher.resolve(action);
        if (handler == null) {
            helper.fail("action " + action + " 未注册进派发器 (QuestSystem.register 漏了 registerAll?)");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return handler;
    }

    private static JsonObject questIdPayload(String questId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("questId", questId);
        return payload;
    }

    private static JsonObject refreshPayload(String source, int slot) {
        JsonObject payload = new JsonObject();
        payload.addProperty("source", source);
        payload.addProperty("slot", slot);
        return payload;
    }

    private static int countInMainInventory(ServerPlayer player, Item item) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty() && stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static void registerFreshEconomy() {
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        Function<UUID, PlayerAbuseState> resolver = id -> states.computeIfAbsent(id, key -> new PlayerAbuseState());
        EconomyServices.reset();
        EconomyServices.registerEconomyService(new EconomyService(ledger, new AbuseGuard(), resolver));
    }

    private static IEconomyService currentEconomy() {
        return EconomyServices.isRegistered() ? EconomyServices.economyService() : null;
    }

    private static void restoreEconomy(IEconomyService previous) {
        EconomyServices.reset();
        if (previous != null) {
            EconomyServices.registerEconomyService(previous);
        }
    }
}

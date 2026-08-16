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
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        }
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

    private static JsonObject rowById(JsonArray rows, String questId) {
        for (int i = 0; i < rows.size(); i++) {
            JsonObject row = rows.get(i).getAsJsonObject();
            if (questId.equals(row.get("questId").getAsString())) {
                return row;
            }
        }
        throw new IllegalStateException("quest.board 未返回预置任务 " + questId);
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

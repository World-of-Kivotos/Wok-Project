package com.miningdim.quest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.miningdim.economy.EconomyServices;
import com.miningdim.quest.objective.TurnInItemObjective;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiItemJson;
import com.miningdim.webui.server.WebUiPayloads;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 任务面板的 quest.* WebUiAction。 */
public final class QuestWebUiActions {

    /** 可空字段是契约真值, 必须显式序列化为 JSON null。 */
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private QuestWebUiActions() {
    }

    /** 把四条 quest.* action 注册进派发器。 */
    public static void registerAll() {
        WebUiServerDispatcher.register("quest.board", BOARD);
        WebUiServerDispatcher.register("quest.claim", CLAIM);
        WebUiServerDispatcher.register("quest.turnIn", TURN_IN);
        WebUiServerDispatcher.register("quest.refresh", REFRESH);
    }

    static final WebUiAction BOARD = (sender, payload) -> {
        requireActive();
        QuestBoard board = QuestServices.service().boardOf(sender);

        JsonObject result = new JsonObject();
        result.addProperty("dailyRefreshCost", QuestRewards.refreshCost(QuestSource.DAILY));
        result.addProperty("weeklyRefreshCost", QuestRewards.refreshCost(QuestSource.WEEKLY));
        result.addProperty("creditBalance", EconomyServices.economyService().creditBalance(sender));
        result.add("daily", progressRows(board.daily()));
        result.add("weekly", progressRows(board.weekly()));
        result.add("special", progressRows(board.special()));

        JsonArray chains = new JsonArray();
        for (QuestChainState state : board.chains()) {
            chains.add(chainRow(state));
        }
        result.add("chains", chains);
        return GSON.toJson(result);
    };

    static final WebUiAction CLAIM = (sender, payload) -> {
        requireActive();
        String questId = WebUiPayloads.requiredString(payload, "questId");
        QuestService.ClaimResult claim = QuestServices.service().claim(sender, questId);

        JsonObject result = new JsonObject();
        result.addProperty("outcome", claim.outcome().name());
        result.addProperty("questId", questId);
        if (claim.definition() == null) {
            result.add("title", JsonNull.INSTANCE);
        } else {
            result.addProperty("title", claim.definition().title());
        }
        result.addProperty("credit", claim.credit());

        JsonArray items = new JsonArray();
        for (ItemStack stack : claim.items()) {
            items.add(itemRow(stack));
        }
        result.add("items", items);
        return GSON.toJson(result);
    };

    static final WebUiAction TURN_IN = (sender, payload) -> {
        requireActive();
        String questId = WebUiPayloads.requiredString(payload, "questId");
        QuestService.TurnInResult turnIn = QuestServices.service().turnIn(sender, questId);

        JsonObject result = new JsonObject();
        result.addProperty("outcome", turnIn.outcome().name());
        result.addProperty("questId", questId);
        if (turnIn.definition() == null) {
            result.add("title", JsonNull.INSTANCE);
        } else {
            result.addProperty("title", turnIn.definition().title());
        }
        result.addProperty("count", turnIn.count());
        return GSON.toJson(result);
    };

    static final WebUiAction REFRESH = (sender, payload) -> {
        requireActive();
        QuestSource source = parseRefreshSource(payload);
        int slot = WebUiPayloads.requiredInt(payload, "slot");
        QuestService service = QuestServices.service();
        QuestBoard board = service.boardOf(sender);
        int size = source == QuestSource.DAILY ? board.daily().size() : board.weekly().size();
        if (slot < 0 || slot >= size) {
            throw new WebUiBusinessException(WebUiErrorCodes.SLOT_OUT_OF_RANGE,
                    "任务槽位超出范围: " + slot, false,
                    Map.of("slot", String.valueOf(slot), "size", String.valueOf(size)));
        }

        QuestService.RefreshResult refresh = service.refresh(sender, source, slot);
        JsonObject result = new JsonObject();
        result.addProperty("outcome", refresh.outcome().name());
        result.addProperty("cost", refresh.cost());
        if (refresh.replacement() == null) {
            result.add("replacement", JsonNull.INSTANCE);
        } else {
            result.add("replacement", progressRow(refresh.replacement()));
        }
        return GSON.toJson(result);
    };

    private static JsonArray progressRows(List<QuestProgress> progresses) {
        JsonArray rows = new JsonArray();
        for (QuestProgress progress : progresses) {
            rows.add(progressRow(progress));
        }
        return rows;
    }

    private static JsonObject progressRow(QuestProgress progress) {
        QuestDefinition definition = progress.definition();
        JsonObject row = new JsonObject();
        row.addProperty("questId", definition.id());
        row.addProperty("title", definition.title());
        row.addProperty("objective", definition.objective().describe());
        row.addProperty("difficulty", definition.difficulty());
        row.addProperty("count", progress.count());
        row.addProperty("requiredCount", progress.requiredCount());
        row.addProperty("complete", progress.isComplete());
        row.addProperty("claimed", progress.claimed());
        row.addProperty("turnIn", definition.objective() instanceof TurnInItemObjective);
        row.addProperty("creditReward", QuestRewards.creditFor(definition));
        row.add("itemReward", itemRewardRow(definition.source()));
        return row;
    }

    /**
     * 物品奖励的可展示形态: 档位 + 必得材料份数 + 附魔书概率。
     *
     * 刻意不发"会掉哪几样": 物品是领奖那一刻按权重掷的 ({@link QuestItemRewards#roll}), 领之前根本不存在
     * 确定答案。把整张掉落表塞进每一行更不行 —— 六行任务各带一份十六条的表纯属浪费带宽, 那是图鉴页的事。
     * 发档位与概率, 玩家据此判断"这条值不值得留着", 且每一个字都是真的。
     */
    private static JsonObject itemRewardRow(QuestSource source) {
        JsonObject reward = new JsonObject();
        reward.addProperty("tier", QuestItemRewards.tier(source).name());
        reward.addProperty("materialStacks", QuestItemRewards.GUARANTEED_MATERIAL_STACKS);
        reward.addProperty("bookChance", QuestItemRewards.bookChance(source));
        return reward;
    }

    private static JsonObject chainRow(QuestChainState state) {
        QuestChain chain = state.chain();
        JsonObject row = new JsonObject();
        row.addProperty("chainId", chain.id());
        row.addProperty("title", chain.title());
        row.addProperty("finished", state.finished());
        row.addProperty("stageIndex", state.stageIndex());
        row.addProperty("stageCount", chain.stageCount());
        if (state.current() == null) {
            row.add("current", JsonNull.INSTANCE);
        } else {
            row.add("current", progressRow(state.current()));
        }
        return row;
    }

    private static JsonObject itemRow(ItemStack stack) {
        ResourceLocation itemId = Objects.requireNonNull(ForgeRegistries.ITEMS.getKey(stack.getItem()),
                "quest reward item is not registered: " + stack.getItem());
        JsonObject row = new JsonObject();
        row.addProperty("itemId", itemId.toString());
        row.addProperty("descriptionId", stack.getDescriptionId());
        row.addProperty("count", stack.getCount());

        Map<Enchantment, Integer> enchantments = EnchantmentHelper.getEnchantments(stack);
        if (!enchantments.isEmpty()) {
            JsonArray entries = new JsonArray();
            for (Map.Entry<Enchantment, Integer> entry : enchantments.entrySet()) {
                ResourceLocation enchantmentId = Objects.requireNonNull(
                        ForgeRegistries.ENCHANTMENTS.getKey(entry.getKey()),
                        "quest reward enchantment is not registered: " + entry.getKey());
                JsonObject enchantment = new JsonObject();
                enchantment.addProperty("id", enchantmentId.toString());
                enchantment.addProperty("level", entry.getValue());
                entries.add(enchantment);
            }
            row.add("enchantments", entries);
        }
        WebUiItemJson.appendVariant(row, stack);
        return row;
    }

    private static QuestSource parseRefreshSource(JsonObject payload) {
        String raw = WebUiPayloads.requiredString(payload, "source");
        if ("daily".equalsIgnoreCase(raw)) {
            return QuestSource.DAILY;
        }
        if ("weekly".equalsIgnoreCase(raw)) {
            return QuestSource.WEEKLY;
        }
        throw WebUiPayloads.illegalValue("source", raw, "未知的任务来源: " + raw);
    }

    private static void requireActive() {
        if (!QuestServices.active()) {
            throw new WebUiBusinessException(WebUiErrorCodes.QUEST_DISABLED,
                    "任务系统当前未启用", false);
        }
    }
}

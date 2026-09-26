package com.miningdim.achievement.trigger;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.miningdim.achievement.AchievementIds;
import com.miningdim.quest.QuestSource;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.SerializationContext;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * {@code miningdim:quest_complete} (6.2): 领取了一份任务奖励。条件字段都可选: {@code source} (daily / weekly /
 * special / hidden)、{@code quest_id} (任务定义 id)、{@code chain} (隐藏任务线 id)、{@code chain_finished} (为 true 时
 * 要求这次领奖走完了整条任务线)。
 */
public final class QuestCompleteTrigger extends SimpleCriterionTrigger<QuestCompleteTrigger.TriggerInstance> {

    static final ResourceLocation ID = AchievementIds.id("quest_complete");

    QuestCompleteTrigger() {
    }

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    protected TriggerInstance createInstance(JsonObject json, ContextAwarePredicate player,
                                             DeserializationContext context) {
        QuestSource source = null;
        if (json.has("source")) {
            String raw = GsonHelper.getAsString(json, "source");
            source = parseSource(raw);
        }
        return new TriggerInstance(player, source, TriggerJson.optionalNonBlank(json, "quest_id"),
                TriggerJson.optionalNonBlank(json, "chain"), GsonHelper.getAsBoolean(json, "chain_finished", false));
    }

    /**
     * 领取了一份任务奖励。
     *
     * @param source        任务来源
     * @param questId       任务定义 id; 上线追溯只知道任务线走完、不知道具体阶段时为 null (quest_id 条件不满足)
     * @param chainId       所属任务线; 不属于任何任务线为 null
     * @param chainFinished 是否走完了整条任务线
     */
    public void trigger(ServerPlayer player, QuestSource source, @Nullable String questId, @Nullable String chainId,
                        boolean chainFinished) {
        trigger(player, instance -> instance.matches(source, questId, chainId, chainFinished));
    }

    private static QuestSource parseSource(String raw) {
        for (QuestSource source : QuestSource.values()) {
            if (sourceId(source).equals(raw)) {
                return source;
            }
        }
        throw new JsonSyntaxException("unknown quest source '" + raw + "', expected daily, weekly, special or hidden");
    }

    private static String sourceId(QuestSource source) {
        return source.name().toLowerCase(Locale.ROOT);
    }

    public static final class TriggerInstance extends AbstractCriterionTriggerInstance {

        @Nullable
        private final QuestSource source;
        @Nullable
        private final String questId;
        @Nullable
        private final String chain;
        private final boolean chainFinished;

        public TriggerInstance(ContextAwarePredicate player, @Nullable QuestSource source, @Nullable String questId,
                               @Nullable String chain, boolean chainFinished) {
            super(ID, player);
            this.source = source;
            this.questId = questId;
            this.chain = chain;
            this.chainFinished = chainFinished;
        }

        /** 领取一份该来源的任务。 */
        public static TriggerInstance fromSource(QuestSource source) {
            return new TriggerInstance(ContextAwarePredicate.ANY, source, null, null, false);
        }

        /** 走完整条任务线 chain。 */
        public static TriggerInstance chainFinished(String chain) {
            return new TriggerInstance(ContextAwarePredicate.ANY, null, null, chain, true);
        }

        boolean matches(QuestSource claimedSource, @Nullable String claimedQuestId, @Nullable String claimedChain,
                        boolean claimedChainFinished) {
            if (source != null && source != claimedSource) {
                return false;
            }
            if (questId != null && !questId.equals(claimedQuestId)) {
                return false;
            }
            if (chain != null && !chain.equals(claimedChain)) {
                return false;
            }
            return !chainFinished || claimedChainFinished;
        }

        @Override
        public JsonObject serializeToJson(SerializationContext context) {
            JsonObject json = super.serializeToJson(context);
            if (source != null) {
                json.addProperty("source", sourceId(source));
            }
            if (questId != null) {
                json.addProperty("quest_id", questId);
            }
            if (chain != null) {
                json.addProperty("chain", chain);
            }
            if (chainFinished) {
                json.addProperty("chain_finished", true);
            }
            return json;
        }
    }
}

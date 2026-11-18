package com.miningdim.achievement.trigger;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.miningdim.achievement.AchievementIds;
import com.miningdim.job.tarot.codex.TarotCodex;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.SerializationContext;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** 历史图鉴驱动收集成就，牌被消耗或重复牌转碎片后仍保留已经点亮的进度。 */
public final class TarotCodexTrigger extends SimpleCriterionTrigger<TarotCodexTrigger.TriggerInstance> {
    private static final ResourceLocation ID = AchievementIds.id("tarot_codex");

    public enum Milestone {
        STARDUST("stardust"), SHINING("shining"), STARMASTER("starmaster");

        private final String id;

        Milestone(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public ResourceLocation advancementId() {
            return AchievementIds.id("profession/tarot_" + id);
        }

        boolean matches(TarotCodex.Summary summary) {
            return switch (this) {
                case STARDUST -> summary.arcana() == summary.arcanaTotal();
                case SHINING -> summary.shiny();
                case STARMASTER -> summary.count() == summary.total();
            };
        }
    }

    TarotCodexTrigger() {
    }

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    protected TriggerInstance createInstance(JsonObject json, ContextAwarePredicate player,
                                             DeserializationContext context) {
        if (!json.has("milestone")) {
            throw new JsonSyntaxException("tarot_codex requires a milestone");
        }
        Milestone milestone = JobTriggerJson.optionalId(json, "milestone", Milestone.values(), Milestone::id);
        return new TriggerInstance(player, milestone);
    }

    public void trigger(ServerPlayer player, TarotCodex.Summary summary) {
        trigger(player, instance -> instance.milestone.matches(summary));
    }

    public static final class TriggerInstance extends AbstractCriterionTriggerInstance {
        private final Milestone milestone;

        public TriggerInstance(ContextAwarePredicate player, Milestone milestone) {
            super(ID, player);
            this.milestone = milestone;
        }

        public static TriggerInstance of(Milestone milestone) {
            return new TriggerInstance(ContextAwarePredicate.ANY, milestone);
        }

        @Override
        public JsonObject serializeToJson(SerializationContext context) {
            JsonObject json = super.serializeToJson(context);
            json.addProperty("milestone", milestone.id());
            return json;
        }
    }
}

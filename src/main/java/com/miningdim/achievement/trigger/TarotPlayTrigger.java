package com.miningdim.achievement.trigger;

import com.google.gson.JsonObject;
import com.miningdim.achievement.AchievementIds;
import com.miningdim.job.tarot.TarotArcana;
import com.miningdim.job.tarot.TarotQuality;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.SerializationContext;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * {@code miningdim:tarot_play} (6.2): 打出一张塔罗牌 (揭牌结算之后)。条件字段:
 * <ul>
 *   <li>{@code min_quality}: 可选, 品质不低于这一档 (r / sr / ssr / ur / shiny, 由低到高);</li>
 *   <li>{@code card_id}: 可选, 大阿卡纳编号 0~21 (预留给第二批的塔罗系列成就)。</li>
 * </ul>
 */
public final class TarotPlayTrigger extends SimpleCriterionTrigger<TarotPlayTrigger.TriggerInstance> {

    static final ResourceLocation ID = AchievementIds.id("tarot_play");

    TarotPlayTrigger() {
    }

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    protected TriggerInstance createInstance(JsonObject json, ContextAwarePredicate player,
                                             DeserializationContext context) {
        Integer cardId = json.has("card_id")
                ? TriggerJson.requiredIntInRange(json, "card_id", 0, TarotArcana.COUNT - 1) : null;
        return new TriggerInstance(player,
                JobTriggerJson.optionalId(json, "min_quality", TarotQuality.values(), TarotQuality::id), cardId);
    }

    /** 塔罗师打出的一张牌已揭牌结算。 */
    public void trigger(ServerPlayer player, int cardId, TarotQuality quality) {
        trigger(player, instance -> instance.matches(cardId, quality));
    }

    public static final class TriggerInstance extends AbstractCriterionTriggerInstance {

        @Nullable
        private final TarotQuality minQuality;
        @Nullable
        private final Integer cardId;

        public TriggerInstance(ContextAwarePredicate player, @Nullable TarotQuality minQuality,
                               @Nullable Integer cardId) {
            super(ID, player);
            this.minQuality = minQuality;
            this.cardId = cardId;
        }

        /** 任意一张牌。 */
        public static TriggerInstance any() {
            return new TriggerInstance(ContextAwarePredicate.ANY, null, null);
        }

        /** 品质按 {@link TarotQuality} 的声明顺序 (即合成链 R -> 闪耀) 由低到高比较。 */
        boolean matches(int playedCardId, TarotQuality quality) {
            return (minQuality == null || quality.compareTo(minQuality) >= 0)
                    && (cardId == null || cardId == playedCardId);
        }

        @Override
        public JsonObject serializeToJson(SerializationContext context) {
            JsonObject json = super.serializeToJson(context);
            if (minQuality != null) {
                json.addProperty("min_quality", minQuality.id());
            }
            if (cardId != null) {
                json.addProperty("card_id", cardId);
            }
            return json;
        }
    }
}

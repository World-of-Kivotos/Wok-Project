package com.miningdim.achievement.trigger;

import com.google.gson.JsonObject;
import com.miningdim.achievement.AchievementIds;
import com.miningdim.job.engineer.NanoTier;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.SerializationContext;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * {@code miningdim:nano_plate_produced} (6.2): 生产台结算出了纳米维修套件 (护甲板)。条件字段 {@code min_tier} 可选,
 * 板的档位不低于这一档 (low / medium / high / superior / transcendent / radiant, 即 {@link NanoTier} 名小写)。
 */
public final class NanoPlateProducedTrigger extends SimpleCriterionTrigger<NanoPlateProducedTrigger.TriggerInstance> {

    static final ResourceLocation ID = AchievementIds.id("nano_plate_produced");

    NanoPlateProducedTrigger() {
    }

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    protected TriggerInstance createInstance(JsonObject json, ContextAwarePredicate player,
                                             DeserializationContext context) {
        return new TriggerInstance(player,
                JobTriggerJson.optionalId(json, "min_tier", NanoTier.values(), JobTriggerJson::lowerName));
    }

    /** 生产者在生产台出了板。 */
    public void trigger(ServerPlayer engineer, NanoTier tier) {
        trigger(engineer, instance -> instance.matches(tier));
    }

    public static final class TriggerInstance extends AbstractCriterionTriggerInstance {

        @Nullable
        private final NanoTier minTier;

        public TriggerInstance(ContextAwarePredicate player, @Nullable NanoTier minTier) {
            super(ID, player);
            this.minTier = minTier;
        }

        /** 任意档位。 */
        public static TriggerInstance any() {
            return new TriggerInstance(ContextAwarePredicate.ANY, null);
        }

        boolean matches(NanoTier tier) {
            return minTier == null || tier.index() >= minTier.index();
        }

        @Override
        public JsonObject serializeToJson(SerializationContext context) {
            JsonObject json = super.serializeToJson(context);
            if (minTier != null) {
                json.addProperty("min_tier", JobTriggerJson.lowerName(minTier));
            }
            return json;
        }
    }
}

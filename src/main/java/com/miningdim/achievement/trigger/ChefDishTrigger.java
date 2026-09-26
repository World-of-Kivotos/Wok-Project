package com.miningdim.achievement.trigger;

import com.google.gson.JsonObject;
import com.miningdim.achievement.AchievementIds;
import com.miningdim.job.chef.ChefQuality;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.SerializationContext;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * {@code miningdim:chef_dish} (6.2): 在调味台完成一道料理。条件字段 (取值都是厨师品质的稳定 id: low / medium /
 * high / extraordinary / radiant):
 * <ul>
 *   <li>{@code min_quality}: 可选, 成品品质不低于这一档;</li>
 *   <li>{@code target}: 可选, 开工时选定的挑战目标恰为这一档。</li>
 * </ul>
 */
public final class ChefDishTrigger extends SimpleCriterionTrigger<ChefDishTrigger.TriggerInstance> {

    static final ResourceLocation ID = AchievementIds.id("chef_dish");

    ChefDishTrigger() {
    }

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    protected TriggerInstance createInstance(JsonObject json, ContextAwarePredicate player,
                                             DeserializationContext context) {
        return new TriggerInstance(player,
                JobTriggerJson.optionalId(json, "min_quality", ChefQuality.values(), ChefQuality::id),
                JobTriggerJson.optionalId(json, "target", ChefQuality.values(), ChefQuality::id));
    }

    /** 厨师在调味台出了一道菜。 */
    public void trigger(ServerPlayer chef, ChefQuality quality, ChefQuality target) {
        trigger(chef, instance -> instance.matches(quality, target));
    }

    public static final class TriggerInstance extends AbstractCriterionTriggerInstance {

        @Nullable
        private final ChefQuality minQuality;
        @Nullable
        private final ChefQuality target;

        public TriggerInstance(ContextAwarePredicate player, @Nullable ChefQuality minQuality,
                               @Nullable ChefQuality target) {
            super(ID, player);
            this.minQuality = minQuality;
            this.target = target;
        }

        /** 任意一道菜。 */
        public static TriggerInstance any() {
            return new TriggerInstance(ContextAwarePredicate.ANY, null, null);
        }

        /** 成品不低于 minQuality。 */
        public static TriggerInstance atLeast(ChefQuality minQuality) {
            return new TriggerInstance(ContextAwarePredicate.ANY, minQuality, null);
        }

        boolean matches(ChefQuality quality, ChefQuality challengedTarget) {
            return (minQuality == null || quality.atLeast(minQuality))
                    && (target == null || target == challengedTarget);
        }

        @Override
        public JsonObject serializeToJson(SerializationContext context) {
            JsonObject json = super.serializeToJson(context);
            if (minQuality != null) {
                json.addProperty("min_quality", minQuality.id());
            }
            if (target != null) {
                json.addProperty("target", target.id());
            }
            return json;
        }
    }
}

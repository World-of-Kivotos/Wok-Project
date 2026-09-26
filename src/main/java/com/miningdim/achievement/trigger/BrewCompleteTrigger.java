package com.miningdim.achievement.trigger;

import com.google.gson.JsonObject;
import com.miningdim.achievement.AchievementIds;
import com.miningdim.job.brewer.WineQuality;
import com.miningdim.job.brewer.WineType;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.SerializationContext;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * {@code miningdim:brew_complete} (6.2): 在酿酒台亲手酿成一瓶酒 (操作者在线才算, 与酿酒经验同一口径)。条件字段:
 * <ul>
 *   <li>{@code wine_type}: 可选, 九种酒之一 ({@link WineType} 的稳定 id, 如 {@code maotai});</li>
 *   <li>{@code min_quality}: 可选, 品质不低于这一档 (low / mid / high / superb / brilliant)。</li>
 * </ul>
 */
public final class BrewCompleteTrigger extends SimpleCriterionTrigger<BrewCompleteTrigger.TriggerInstance> {

    static final ResourceLocation ID = AchievementIds.id("brew_complete");

    BrewCompleteTrigger() {
    }

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    protected TriggerInstance createInstance(JsonObject json, ContextAwarePredicate player,
                                             DeserializationContext context) {
        return new TriggerInstance(player,
                JobTriggerJson.optionalId(json, "wine_type", WineType.values(), WineType::id),
                JobTriggerJson.optionalId(json, "min_quality", WineQuality.values(), WineQuality::id));
    }

    /** 在线操作者酿成了一瓶酒。 */
    public void trigger(ServerPlayer brewer, WineType type, WineQuality quality) {
        trigger(brewer, instance -> instance.matches(type, quality));
    }

    public static final class TriggerInstance extends AbstractCriterionTriggerInstance {

        @Nullable
        private final WineType wineType;
        @Nullable
        private final WineQuality minQuality;

        public TriggerInstance(ContextAwarePredicate player, @Nullable WineType wineType,
                               @Nullable WineQuality minQuality) {
            super(ID, player);
            this.wineType = wineType;
            this.minQuality = minQuality;
        }

        /** 任意一瓶酒。 */
        public static TriggerInstance any() {
            return new TriggerInstance(ContextAwarePredicate.ANY, null, null);
        }

        /** 酿成这一种酒 (任意品质)。 */
        public static TriggerInstance ofType(WineType wineType) {
            return new TriggerInstance(ContextAwarePredicate.ANY, wineType, null);
        }

        /** 品质不低于 minQuality (任意酒)。 */
        public static TriggerInstance atLeast(WineQuality minQuality) {
            return new TriggerInstance(ContextAwarePredicate.ANY, null, minQuality);
        }

        /** 品质按 {@link WineQuality} 的声明顺序由低到高比较。 */
        boolean matches(WineType type, WineQuality quality) {
            return (wineType == null || wineType == type)
                    && (minQuality == null || quality.compareTo(minQuality) >= 0);
        }

        @Override
        public JsonObject serializeToJson(SerializationContext context) {
            JsonObject json = super.serializeToJson(context);
            if (wineType != null) {
                json.addProperty("wine_type", wineType.id());
            }
            if (minQuality != null) {
                json.addProperty("min_quality", minQuality.id());
            }
            return json;
        }
    }
}

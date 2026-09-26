package com.miningdim.achievement.trigger;

import com.google.gson.JsonObject;
import com.miningdim.achievement.AchievementIds;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.SerializationContext;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * {@code miningdim:spouse_teleport} (6.2): 用结婚戒指传送到了伴侣身边。条件字段 {@code min_distance} (可选) 是传送
 * 蓄力开始时双方在同一维度内的水平距离 (只算 dx、dz) 下限。
 */
public final class SpouseTeleportTrigger extends SimpleCriterionTrigger<SpouseTeleportTrigger.TriggerInstance> {

    static final ResourceLocation ID = AchievementIds.id("spouse_teleport");

    SpouseTeleportTrigger() {
    }

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    protected TriggerInstance createInstance(JsonObject json, ContextAwarePredicate player,
                                             DeserializationContext context) {
        return new TriggerInstance(player, TriggerJson.optionalNonNegative(json, "min_distance"));
    }

    /** 玩家从 horizontalDistance 格外传送到了伴侣身边。 */
    public void trigger(ServerPlayer player, double horizontalDistance) {
        trigger(player, instance -> instance.matches(horizontalDistance));
    }

    public static final class TriggerInstance extends AbstractCriterionTriggerInstance {

        @Nullable
        private final Double minDistance;

        public TriggerInstance(ContextAwarePredicate player, @Nullable Double minDistance) {
            super(ID, player);
            this.minDistance = minDistance;
        }

        /** 从至少 minDistance 格外传送。 */
        public static TriggerInstance beyond(double minDistance) {
            return new TriggerInstance(ContextAwarePredicate.ANY, minDistance);
        }

        boolean matches(double horizontalDistance) {
            return minDistance == null || horizontalDistance >= minDistance;
        }

        @Override
        public JsonObject serializeToJson(SerializationContext context) {
            JsonObject json = super.serializeToJson(context);
            if (minDistance != null) {
                json.addProperty("min_distance", minDistance);
            }
            return json;
        }
    }
}

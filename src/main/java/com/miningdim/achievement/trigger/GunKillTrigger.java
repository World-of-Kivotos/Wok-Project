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
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

/**
 * {@code miningdim:gun_kill} (6.2): 在矿区用枪击杀一只满足 6.4 击杀过滤的生物。条件字段
 * {@code min_horizontal_distance} (击杀瞬间 dx、dz 的水平距离下限) 与 {@code headshot} (为 true 时要求爆头) 都可选。
 *
 * <p>触发器本身不引用 TaCZ, 无条件注册; 只有 TaCZ 在场时击杀钩子才会调到这里, 依赖它的进度另在 JSON 上加
 * {@code forge:mod_loaded} 加载条件 (6.2)。
 */
public final class GunKillTrigger extends SimpleCriterionTrigger<GunKillTrigger.TriggerInstance> {

    static final ResourceLocation ID = AchievementIds.id("gun_kill");

    GunKillTrigger() {
    }

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    protected TriggerInstance createInstance(JsonObject json, ContextAwarePredicate player,
                                             DeserializationContext context) {
        return new TriggerInstance(player,
                TriggerJson.optionalNonNegative(json, "min_horizontal_distance"),
                GsonHelper.getAsBoolean(json, "headshot", false));
    }

    /**
     * 玩家用枪击杀了一只生物。
     *
     * @param horizontalDistance 击杀瞬间射手与目标的水平距离 (只算 dx、dz)
     * @param headshot           是否爆头
     */
    public void trigger(ServerPlayer player, double horizontalDistance, boolean headshot) {
        trigger(player, instance -> instance.matches(horizontalDistance, headshot));
    }

    public static final class TriggerInstance extends AbstractCriterionTriggerInstance {

        @Nullable
        private final Double minHorizontalDistance;
        private final boolean headshot;

        public TriggerInstance(ContextAwarePredicate player, @Nullable Double minHorizontalDistance,
                               boolean headshot) {
            super(ID, player);
            this.minHorizontalDistance = minHorizontalDistance;
            this.headshot = headshot;
        }

        /** 在水平距离 minHorizontalDistance 以外爆头击杀。 */
        public static TriggerInstance headshotBeyond(double minHorizontalDistance) {
            return new TriggerInstance(ContextAwarePredicate.ANY, minHorizontalDistance, true);
        }

        boolean matches(double horizontalDistance, boolean wasHeadshot) {
            if (headshot && !wasHeadshot) {
                return false;
            }
            return minHorizontalDistance == null || horizontalDistance >= minHorizontalDistance;
        }

        @Override
        public JsonObject serializeToJson(SerializationContext context) {
            JsonObject json = super.serializeToJson(context);
            if (minHorizontalDistance != null) {
                json.addProperty("min_horizontal_distance", minHorizontalDistance);
            }
            if (headshot) {
                json.addProperty("headshot", true);
            }
            return json;
        }
    }
}

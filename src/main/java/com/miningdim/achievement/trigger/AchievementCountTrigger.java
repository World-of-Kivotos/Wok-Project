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

/**
 * {@code miningdim:achievement_count} (6.2): 已获得的成就数量达到 {@code count}。数量按 6.5 的口径计算 (不计各页签根与
 * meta 页签自己的成就, 计入隐藏成就), 由调用方用 {@code AchievementCatalog#countEarned} 算好后传进来。
 */
public final class AchievementCountTrigger extends SimpleCriterionTrigger<AchievementCountTrigger.TriggerInstance> {

    static final ResourceLocation ID = AchievementIds.id("achievement_count");

    AchievementCountTrigger() {
    }

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    protected TriggerInstance createInstance(JsonObject json, ContextAwarePredicate player,
                                             DeserializationContext context) {
        return new TriggerInstance(player, TriggerJson.requiredIntInRange(json, "count", 1, Integer.MAX_VALUE));
    }

    /** 玩家当前已获得的成就数量 (6.5 口径)。 */
    public void trigger(ServerPlayer player, int earnedCount) {
        trigger(player, instance -> instance.matches(earnedCount));
    }

    public static final class TriggerInstance extends AbstractCriterionTriggerInstance {

        private final int count;

        public TriggerInstance(ContextAwarePredicate player, int count) {
            super(ID, player);
            this.count = count;
        }

        /** 获得 count 个成就。 */
        public static TriggerInstance atLeast(int count) {
            return new TriggerInstance(ContextAwarePredicate.ANY, count);
        }

        boolean matches(int earnedCount) {
            return earnedCount >= count;
        }

        @Override
        public JsonObject serializeToJson(SerializationContext context) {
            JsonObject json = super.serializeToJson(context);
            json.addProperty("count", count);
            return json;
        }
    }
}

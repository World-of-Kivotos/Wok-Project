package com.miningdim.achievement.trigger;

import com.google.gson.JsonObject;
import com.miningdim.achievement.AchievementIds;
import com.miningdim.core.Difficulty;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.SerializationContext;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * {@code miningdim:enter_mining} (6.2): 玩家从其他维度进入矿区。条件字段 {@code difficulty} 可选 (easy / medium /
 * hard), 缺省表示任意难度。
 */
public final class EnterMiningTrigger extends SimpleCriterionTrigger<EnterMiningTrigger.TriggerInstance> {

    static final ResourceLocation ID = AchievementIds.id("enter_mining");

    EnterMiningTrigger() {
    }

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    protected TriggerInstance createInstance(JsonObject json, ContextAwarePredicate player,
                                             DeserializationContext context) {
        return new TriggerInstance(player, TriggerJson.optionalDifficulty(json, "difficulty"));
    }

    /** 玩家进入了某难度的矿区。 */
    public void trigger(ServerPlayer player, Difficulty difficulty) {
        trigger(player, instance -> instance.matches(difficulty));
    }

    public static final class TriggerInstance extends AbstractCriterionTriggerInstance {

        @Nullable
        private final Difficulty difficulty;

        public TriggerInstance(ContextAwarePredicate player, @Nullable Difficulty difficulty) {
            super(ID, player);
            this.difficulty = difficulty;
        }

        /** 进入任意难度的矿区。 */
        public static TriggerInstance any() {
            return new TriggerInstance(ContextAwarePredicate.ANY, null);
        }

        boolean matches(Difficulty entered) {
            return difficulty == null || difficulty == entered;
        }

        @Override
        public JsonObject serializeToJson(SerializationContext context) {
            JsonObject json = super.serializeToJson(context);
            if (difficulty != null) {
                json.addProperty("difficulty", difficulty.configName());
            }
            return json;
        }
    }
}

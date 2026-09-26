package com.miningdim.achievement.trigger;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.miningdim.achievement.AchievementIds;
import com.miningdim.core.Difficulty;
import com.miningdim.ore.OreType;
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
 * {@code miningdim:mine_ore} (6.2): 在矿区亲手挖到某种矿石。条件字段 {@code ore} 必填, 取矿区的 16 种矿石之一
 * ({@link OreType} 的小写名, 如 {@code ancient_debris}; 石头版与深板岩版在 {@link OreType} 里本就是同一种);
 * {@code difficulty} 可选。
 */
public final class MineOreTrigger extends SimpleCriterionTrigger<MineOreTrigger.TriggerInstance> {

    static final ResourceLocation ID = AchievementIds.id("mine_ore");

    MineOreTrigger() {
    }

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    protected TriggerInstance createInstance(JsonObject json, ContextAwarePredicate player,
                                             DeserializationContext context) {
        return new TriggerInstance(player, parseOre(GsonHelper.getAsString(json, "ore")),
                TriggerJson.optionalDifficulty(json, "difficulty"));
    }

    /** 玩家在某难度的矿区挖到了一块矿石。 */
    public void trigger(ServerPlayer player, OreType ore, Difficulty difficulty) {
        trigger(player, instance -> instance.matches(ore, difficulty));
    }

    /** 矿种在 JSON 里的写法 (枚举名小写)。 */
    public static String oreId(OreType ore) {
        return ore.name().toLowerCase(Locale.ROOT);
    }

    private static OreType parseOre(String raw) {
        for (OreType ore : OreType.values()) {
            if (oreId(ore).equals(raw)) {
                return ore;
            }
        }
        throw new JsonSyntaxException("unknown ore '" + raw + "'");
    }

    public static final class TriggerInstance extends AbstractCriterionTriggerInstance {

        private final OreType ore;
        @Nullable
        private final Difficulty difficulty;

        public TriggerInstance(ContextAwarePredicate player, OreType ore, @Nullable Difficulty difficulty) {
            super(ID, player);
            this.ore = ore;
            this.difficulty = difficulty;
        }

        /** 任意难度下挖到该矿种。 */
        public static TriggerInstance of(OreType ore) {
            return new TriggerInstance(ContextAwarePredicate.ANY, ore, null);
        }

        boolean matches(OreType mined, Difficulty minedIn) {
            return ore == mined && (difficulty == null || difficulty == minedIn);
        }

        @Override
        public JsonObject serializeToJson(SerializationContext context) {
            JsonObject json = super.serializeToJson(context);
            json.addProperty("ore", oreId(ore));
            if (difficulty != null) {
                json.addProperty("difficulty", difficulty.configName());
            }
            return json;
        }
    }
}

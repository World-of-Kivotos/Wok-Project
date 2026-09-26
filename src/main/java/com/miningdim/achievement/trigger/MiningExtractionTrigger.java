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
 * {@code miningdim:mining_extraction} (6.2): 一次<b>有效撤离</b> (6.3)。有效撤离的基本条件 (未阵亡、停留时长、计数挖掘数)
 * 由调用方在触发前判定, 只有有效撤离才会调到这里, 所以这些条件对每个实例始终生效, 不是可选字段。
 *
 * <p>可选条件字段:
 * <ul>
 *   <li>{@code difficulty}: 这次行程的难度;</li>
 *   <li>{@code max_health_ratio}: 撤离时 当前血量 / 最大血量 不超过该值 (0~1);</li>
 *   <li>{@code threat_hit_within_ticks}: 撤离前这么多 tick 内被怪物或陷阱打过 (与上一项一起用, 如"死里逃生")。</li>
 * </ul>
 */
public final class MiningExtractionTrigger extends SimpleCriterionTrigger<MiningExtractionTrigger.TriggerInstance> {

    static final ResourceLocation ID = AchievementIds.id("mining_extraction");

    MiningExtractionTrigger() {
    }

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    protected TriggerInstance createInstance(JsonObject json, ContextAwarePredicate player,
                                             DeserializationContext context) {
        return new TriggerInstance(player,
                TriggerJson.optionalDifficulty(json, "difficulty"),
                TriggerJson.optionalRatio(json, "max_health_ratio"),
                TriggerJson.optionalTicks(json, "threat_hit_within_ticks"));
    }

    /**
     * 一次有效撤离。
     *
     * @param difficulty          行程的难度 (进入时记下的)
     * @param healthRatio         撤离时 当前血量 / 最大血量
     * @param ticksSinceThreatHit 距最近一次被怪物或陷阱打中过去的 tick 数; 本次行程没被打过传 {@link Long#MAX_VALUE}
     */
    public void trigger(ServerPlayer player, Difficulty difficulty, double healthRatio, long ticksSinceThreatHit) {
        trigger(player, instance -> instance.matches(difficulty, healthRatio, ticksSinceThreatHit));
    }

    public static final class TriggerInstance extends AbstractCriterionTriggerInstance {

        @Nullable
        private final Difficulty difficulty;
        @Nullable
        private final Double maxHealthRatio;
        @Nullable
        private final Long threatHitWithinTicks;

        public TriggerInstance(ContextAwarePredicate player, @Nullable Difficulty difficulty,
                               @Nullable Double maxHealthRatio, @Nullable Long threatHitWithinTicks) {
            super(ID, player);
            this.difficulty = difficulty;
            this.maxHealthRatio = maxHealthRatio;
            this.threatHitWithinTicks = threatHitWithinTicks;
        }

        /** 任意难度的一次有效撤离。 */
        public static TriggerInstance any() {
            return new TriggerInstance(ContextAwarePredicate.ANY, null, null, null);
        }

        /** 指定难度的一次有效撤离。 */
        public static TriggerInstance in(Difficulty difficulty) {
            return new TriggerInstance(ContextAwarePredicate.ANY, difficulty, null, null);
        }

        /** 任意难度下残血撤离, 且撤离前 threatWithinTicks 内被怪物或陷阱打过。 */
        public static TriggerInstance narrowEscape(double maxHealthRatio, long threatWithinTicks) {
            return new TriggerInstance(ContextAwarePredicate.ANY, null, maxHealthRatio, threatWithinTicks);
        }

        boolean matches(Difficulty tripDifficulty, double healthRatio, long ticksSinceThreatHit) {
            if (difficulty != null && difficulty != tripDifficulty) {
                return false;
            }
            if (maxHealthRatio != null && healthRatio > maxHealthRatio) {
                return false;
            }
            return threatHitWithinTicks == null || ticksSinceThreatHit <= threatHitWithinTicks;
        }

        @Override
        public JsonObject serializeToJson(SerializationContext context) {
            JsonObject json = super.serializeToJson(context);
            if (difficulty != null) {
                json.addProperty("difficulty", difficulty.configName());
            }
            if (maxHealthRatio != null) {
                json.addProperty("max_health_ratio", maxHealthRatio);
            }
            if (threatHitWithinTicks != null) {
                json.addProperty("threat_hit_within_ticks", threatHitWithinTicks);
            }
            return json;
        }
    }
}

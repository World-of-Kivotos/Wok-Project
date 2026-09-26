package com.miningdim.achievement.trigger;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.miningdim.achievement.AchievementIds;
import com.miningdim.caseopening.CaseRarity;
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
 * {@code miningdim:case_open} (6.2): 一次已结算的开箱。条件字段 {@code min_rarity} (按 {@link CaseRarity} 的顺序
 * blue &lt; purple &lt; pink &lt; red &lt; gold, 开出的品质不低于它) 与 {@code max_credit_after} (开箱后信用点余额不超过它)
 * 都可选。
 *
 * <p>{@code max_credit_after} 只认刚完成的这一次正常开箱: 恢复流程补结算的、上线追溯查到的历史开箱, 那一刻的余额与
 * "开箱后余额"无关, 一律不满足它 (宁可少发)。开箱类成就依赖 TaCZ, 触发器本身不引用 TaCZ, 无条件注册。
 */
public final class CaseOpenTrigger extends SimpleCriterionTrigger<CaseOpenTrigger.TriggerInstance> {

    static final ResourceLocation ID = AchievementIds.id("case_open");

    CaseOpenTrigger() {
    }

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    protected TriggerInstance createInstance(JsonObject json, ContextAwarePredicate player,
                                             DeserializationContext context) {
        CaseRarity minRarity = null;
        if (json.has("min_rarity")) {
            String raw = GsonHelper.getAsString(json, "min_rarity");
            try {
                minRarity = CaseRarity.byId(raw);
            } catch (IllegalArgumentException unknown) {
                throw new JsonSyntaxException("unknown case rarity '" + raw
                        + "', expected blue, purple, pink, red or gold");
            }
        }
        return new TriggerInstance(player, minRarity, TriggerJson.optionalLongAtLeast(json, "max_credit_after", 0L));
    }

    /**
     * 一次开箱已结算。
     *
     * @param rarity      开出的品质
     * @param fresh       是否是刚完成的正常开箱 (见类注释)
     * @param creditAfter 开箱后的信用点余额; fresh 为 false 时不读
     */
    public void trigger(ServerPlayer player, CaseRarity rarity, boolean fresh, long creditAfter) {
        trigger(player, instance -> instance.matches(rarity, fresh, creditAfter));
    }

    public static final class TriggerInstance extends AbstractCriterionTriggerInstance {

        @Nullable
        private final CaseRarity minRarity;
        @Nullable
        private final Long maxCreditAfter;

        public TriggerInstance(ContextAwarePredicate player, @Nullable CaseRarity minRarity,
                               @Nullable Long maxCreditAfter) {
            super(ID, player);
            this.minRarity = minRarity;
            this.maxCreditAfter = maxCreditAfter;
        }

        /** 任意一次开箱。 */
        public static TriggerInstance any() {
            return new TriggerInstance(ContextAwarePredicate.ANY, null, null);
        }

        /** 开出不低于 rarity 的品质。 */
        public static TriggerInstance atLeast(CaseRarity rarity) {
            return new TriggerInstance(ContextAwarePredicate.ANY, rarity, null);
        }

        /** 正常开箱后信用点余额不超过 credit。 */
        public static TriggerInstance creditAfterAtMost(long credit) {
            return new TriggerInstance(ContextAwarePredicate.ANY, null, credit);
        }

        boolean matches(CaseRarity rarity, boolean fresh, long creditAfter) {
            if (minRarity != null && rarity.ordinal() < minRarity.ordinal()) {
                return false;
            }
            return maxCreditAfter == null || (fresh && creditAfter <= maxCreditAfter);
        }

        @Override
        public JsonObject serializeToJson(SerializationContext context) {
            JsonObject json = super.serializeToJson(context);
            if (minRarity != null) {
                json.addProperty("min_rarity", minRarity.id());
            }
            if (maxCreditAfter != null) {
                json.addProperty("max_credit_after", maxCreditAfter);
            }
            return json;
        }
    }
}

package com.miningdim.achievement.trigger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.miningdim.achievement.AchievementIds;
import com.miningdim.champion.AffixDef;
import com.miningdim.job.agent.SealCategory;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.SerializationContext;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * {@code miningdim:agent_seal} (6.2): 特勤干员成功封印一条精英怪词条。条件字段:
 * <ul>
 *   <li>{@code category}: 可选, 封印类别 passive / mechanic;</li>
 *   <li>{@code affixes}: 可选, 词条名数组 ({@link AffixDef} 名, 与 champion_kill 的 {@code affix} 同一写法),
 *       封印的词条属于其中之一即成立; 不能是空数组 (空的"其中之一"永远达不成, 按写错处理);</li>
 *   <li>{@code min_star}: 可选, 目标精英的最低星级 1~10。</li>
 * </ul>
 */
public final class AgentSealTrigger extends SimpleCriterionTrigger<AgentSealTrigger.TriggerInstance> {

    static final ResourceLocation ID = AchievementIds.id("agent_seal");

    AgentSealTrigger() {
    }

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    protected TriggerInstance createInstance(JsonObject json, ContextAwarePredicate player,
                                             DeserializationContext context) {
        Integer minStar = json.has("min_star") ? TriggerJson.requiredIntInRange(json, "min_star",
                ChampionKillTrigger.MIN_STAR, ChampionKillTrigger.MAX_STAR) : null;
        return new TriggerInstance(player,
                JobTriggerJson.optionalId(json, "category", SealCategory.values(), JobTriggerJson::lowerName),
                json.has("affixes") ? parseAffixes(GsonHelper.getAsJsonArray(json, "affixes")) : null,
                minStar);
    }

    /** 干员的一次封印申请成功了。 */
    public void trigger(ServerPlayer agent, int star, AffixDef affix, SealCategory category) {
        trigger(agent, instance -> instance.matches(star, affix, category));
    }

    private static Set<AffixDef> parseAffixes(JsonArray raw) {
        if (raw.size() == 0) {
            throw new JsonSyntaxException("'affixes' must not be empty");
        }
        Set<AffixDef> affixes = EnumSet.noneOf(AffixDef.class);
        for (JsonElement element : raw) {
            String name = GsonHelper.convertToString(element, "affixes[]");
            try {
                affixes.add(AffixDef.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                throw new JsonSyntaxException("unknown champion affix '" + name + "' in 'affixes'");
            }
        }
        return affixes;
    }

    public static final class TriggerInstance extends AbstractCriterionTriggerInstance {

        @Nullable
        private final SealCategory category;
        @Nullable
        private final Set<AffixDef> affixes;
        @Nullable
        private final Integer minStar;

        public TriggerInstance(ContextAwarePredicate player, @Nullable SealCategory category,
                               @Nullable Set<AffixDef> affixes, @Nullable Integer minStar) {
            super(ID, player);
            this.category = category;
            this.affixes = affixes == null ? null : Collections.unmodifiableSet(EnumSet.copyOf(affixes));
            this.minStar = minStar;
        }

        /** 任意一次成功的封印。 */
        public static TriggerInstance any() {
            return new TriggerInstance(ContextAwarePredicate.ANY, null, null, null);
        }

        boolean matches(int star, AffixDef affix, SealCategory sealedCategory) {
            return (category == null || category == sealedCategory)
                    && (affixes == null || affixes.contains(affix))
                    && (minStar == null || star >= minStar);
        }

        @Override
        public JsonObject serializeToJson(SerializationContext context) {
            JsonObject json = super.serializeToJson(context);
            if (category != null) {
                json.addProperty("category", JobTriggerJson.lowerName(category));
            }
            if (affixes != null) {
                JsonArray names = new JsonArray();
                affixes.forEach(affix -> names.add(affix.name()));
                json.add("affixes", names);
            }
            if (minStar != null) {
                json.addProperty("min_star", minStar);
            }
            return json;
        }
    }
}

package com.miningdim.achievement.trigger;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.miningdim.achievement.AchievementIds;
import com.miningdim.champion.AffixDef;
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
 * {@code miningdim:champion_kill} (6.2): 作为有效贡献者击倒一只精英怪。条件字段:
 * <ul>
 *   <li>{@code min_star}: 最低星级 1~10, 缺省 1;</li>
 *   <li>{@code affix}: 死亡时持有的词条 ({@link AffixDef} 名, 如 {@code GIGANTISM});</li>
 *   <li>{@code min_share}: 自己的记录伤害占全部记录伤害的最低比例 (0~1);</li>
 *   <li>{@code solo}: 为 true 时要求独自击杀 (6.4);</li>
 *   <li>{@code max_fight_ticks}: 死亡时间 - 首次命中时间 的上限。</li>
 * </ul>
 * 十星世界 BOSS 的"参与讨伐且输出不少于 5%"即 {@code min_star=10, min_share=0.05} ({@link TriggerInstance#minStarWithShare});
 * 10 星精英只由管理员召唤的世界 BOSS 产生, 击杀过滤在任意维度接受世界 BOSS (6.4)。
 */
public final class ChampionKillTrigger extends SimpleCriterionTrigger<ChampionKillTrigger.TriggerInstance> {

    static final ResourceLocation ID = AchievementIds.id("champion_kill");

    /** 精英怪星级的上下界。 */
    public static final int MIN_STAR = 1;
    public static final int MAX_STAR = 10;

    ChampionKillTrigger() {
    }

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    protected TriggerInstance createInstance(JsonObject json, ContextAwarePredicate player,
                                             DeserializationContext context) {
        AffixDef affix = json.has("affix") ? parseAffix(GsonHelper.getAsString(json, "affix")) : null;
        return new TriggerInstance(player,
                TriggerJson.intInRange(json, "min_star", MIN_STAR, MIN_STAR, MAX_STAR),
                affix,
                TriggerJson.optionalRatio(json, "min_share"),
                GsonHelper.getAsBoolean(json, "solo", false),
                TriggerJson.optionalTicks(json, "max_fight_ticks"));
    }

    /** 某名有效贡献者参与击倒了一只精英怪。 */
    public void trigger(ServerPlayer player, ChampionKill kill) {
        trigger(player, instance -> instance.matches(kill));
    }

    private static AffixDef parseAffix(String raw) {
        try {
            return AffixDef.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new JsonSyntaxException("unknown champion affix '" + raw + "'");
        }
    }

    public static final class TriggerInstance extends AbstractCriterionTriggerInstance {

        private final int minStar;
        @Nullable
        private final AffixDef affix;
        @Nullable
        private final Double minShare;
        private final boolean solo;
        @Nullable
        private final Long maxFightTicks;

        public TriggerInstance(ContextAwarePredicate player, int minStar, @Nullable AffixDef affix,
                               @Nullable Double minShare, boolean solo, @Nullable Long maxFightTicks) {
            super(ID, player);
            this.minStar = minStar;
            this.affix = affix;
            this.minShare = minShare;
            this.solo = solo;
            this.maxFightTicks = maxFightTicks;
        }

        /** 击倒 minStar 星及以上的精英怪。 */
        public static TriggerInstance minStar(int minStar) {
            return new TriggerInstance(ContextAwarePredicate.ANY, minStar, null, null, false, null);
        }

        /** 击倒死亡时持有该词条的精英怪 (任意星级)。 */
        public static TriggerInstance withAffix(AffixDef affix) {
            return new TriggerInstance(ContextAwarePredicate.ANY, MIN_STAR, affix, null, false, null);
        }

        /** 参与击倒 minStar 星及以上的精英怪, 且自己的记录伤害占全部记录伤害不少于 minShare。 */
        public static TriggerInstance minStarWithShare(int minStar, double minShare) {
            return new TriggerInstance(ContextAwarePredicate.ANY, minStar, null, minShare, false, null);
        }

        /** 在 maxFightTicks 内独自击倒 minStar 星及以上的精英怪。 */
        public static TriggerInstance soloWithin(int minStar, long maxFightTicks) {
            return new TriggerInstance(ContextAwarePredicate.ANY, minStar, null, null, true, maxFightTicks);
        }

        boolean matches(ChampionKill kill) {
            if (kill.star() < minStar) {
                return false;
            }
            if (affix != null && !kill.affixes().contains(affix)) {
                return false;
            }
            if (minShare != null && kill.share() < minShare) {
                return false;
            }
            if (solo && !kill.solo()) {
                return false;
            }
            return maxFightTicks == null || (kill.fightTicks() >= 0L && kill.fightTicks() <= maxFightTicks);
        }

        @Override
        public JsonObject serializeToJson(SerializationContext context) {
            JsonObject json = super.serializeToJson(context);
            json.addProperty("min_star", minStar);
            if (affix != null) {
                json.addProperty("affix", affix.name());
            }
            if (minShare != null) {
                json.addProperty("min_share", minShare);
            }
            if (solo) {
                json.addProperty("solo", true);
            }
            if (maxFightTicks != null) {
                json.addProperty("max_fight_ticks", maxFightTicks);
            }
            return json;
        }
    }
}

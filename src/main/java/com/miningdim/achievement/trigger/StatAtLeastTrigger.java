package com.miningdim.achievement.trigger;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.miningdim.achievement.AchievementIds;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.SerializationContext;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.StatsCounter;
import net.minecraft.stats.Stats;
import net.minecraft.util.GsonHelper;

/**
 * {@code miningdim:stat_at_least} (6.2): 某个自定义统计项达到阈值。计数类成就一律用它读原版统计, 不自己另存计数 (6.1)。
 *
 * <p>条件字段 {@code stat} 是已注册的自定义统计项 id, {@code value} 是阈值 (≥ 1)。统计项未注册时整条进度加载失败,
 * 不会退化成永远达不成或一开服就达成的条件。
 *
 * <p>{@link net.minecraft.stats.StatType} 的统计对象表是按引用 (IdentityHashMap) 存的, 所以实例里保存的是注册表里
 * 那一个 ResourceLocation 对象本身, 不是解析 JSON 时新建的等值对象。
 */
public final class StatAtLeastTrigger extends SimpleCriterionTrigger<StatAtLeastTrigger.TriggerInstance> {

    static final ResourceLocation ID = AchievementIds.id("stat_at_least");

    StatAtLeastTrigger() {
    }

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    protected TriggerInstance createInstance(JsonObject json, ContextAwarePredicate player,
                                             DeserializationContext context) {
        String raw = GsonHelper.getAsString(json, "stat");
        ResourceLocation parsed = ResourceLocation.tryParse(raw);
        ResourceLocation registered = parsed == null ? null : BuiltInRegistries.CUSTOM_STAT.get(parsed);
        if (registered == null) {
            throw new JsonSyntaxException("unknown custom stat '" + raw + "'");
        }
        return new TriggerInstance(player, registered,
                TriggerJson.requiredIntInRange(json, "value", 1, Integer.MAX_VALUE));
    }

    /**
     * 重新核对该玩家尚未完成的 stat_at_least 条件。统计项递增后调用 ({@link AchievementStats#award} 已代劳);
     * 原版只给未完成的条件挂监听, 每个条件只做一次统计表读取。
     */
    public void trigger(ServerPlayer player) {
        StatsCounter stats = player.getStats();
        trigger(player, instance -> instance.matches(stats));
    }

    public static final class TriggerInstance extends AbstractCriterionTriggerInstance {

        private final ResourceLocation stat;
        private final int value;

        /**
         * @param stat  注册表里的自定义统计项对象 (见类注释)
         * @param value 阈值
         */
        public TriggerInstance(ContextAwarePredicate player, ResourceLocation stat, int value) {
            super(ID, player);
            this.stat = stat;
            this.value = value;
        }

        /** 统计项达到 value。 */
        public static TriggerInstance of(ResourceLocation registeredStat, int value) {
            return new TriggerInstance(ContextAwarePredicate.ANY, registeredStat, value);
        }

        boolean matches(StatsCounter stats) {
            return stats.getValue(Stats.CUSTOM.get(stat)) >= value;
        }

        @Override
        public JsonObject serializeToJson(SerializationContext context) {
            JsonObject json = super.serializeToJson(context);
            json.addProperty("stat", stat.toString());
            json.addProperty("value", value);
            return json;
        }
    }
}

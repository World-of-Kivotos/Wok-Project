package com.miningdim.achievement.trigger;

import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
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

import java.util.Locale;

/**
 * {@code miningdim:market_trade} (6.2): 市场累计达标。只看 6.6 口径下的合格交易 (单笔不低于 1,000、交易时买卖双方
 * 连接 IP 不同、不是夫妻), 计数口径由 {@link MarketTradeHooks} 算好成 {@link MarketStanding} 交进来。
 *
 * <p>条件字段: {@code role} (any / buyer / seller, 缺省 any); {@code count} (该角色下的合格交易笔数, 缺省 1);
 * {@code volume} (卖方计数成交额); {@code partners} (贡献达标的不同买家人数) 与 {@code partner_min} (一位买家至少
 * 贡献多少计数成交额才算, 缺省只要做过合格交易就算)。后三项描述的都是卖方, 只能与 {@code role=seller} 同用,
 * {@code partner_min} 只能与 {@code partners} 同用; 写错时整条进度加载失败。
 */
public final class MarketTradeTrigger extends SimpleCriterionTrigger<MarketTradeTrigger.TriggerInstance> {

    static final ResourceLocation ID = AchievementIds.id("market_trade");

    MarketTradeTrigger() {
    }

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    protected TriggerInstance createInstance(JsonObject json, ContextAwarePredicate player,
                                             DeserializationContext context) {
        Role role = json.has("role") ? Role.byId(GsonHelper.getAsString(json, "role")) : Role.ANY;
        int count = TriggerJson.intInRange(json, "count", 1, 1, Integer.MAX_VALUE);
        Long volume = TriggerJson.optionalLongAtLeast(json, "volume", 1L);
        Integer partners = json.has("partners")
                ? TriggerJson.requiredIntInRange(json, "partners", 1, Integer.MAX_VALUE) : null;
        Long partnerMin = TriggerJson.optionalLongAtLeast(json, "partner_min", 1L);
        if ((volume != null || partners != null) && role != Role.SELLER) {
            throw new JsonSyntaxException("'volume' and 'partners' describe the seller side and need role=seller");
        }
        if (partnerMin != null && partners == null) {
            throw new JsonSyntaxException("'partner_min' is only meaningful together with 'partners'");
        }
        return new TriggerInstance(player, role, count, volume, partners, partnerMin);
    }

    /** 该玩家的市场累计刚发生了变化 (一笔合格交易之后, 或登录时补查)。 */
    public void trigger(ServerPlayer player, MarketStanding standing) {
        trigger(player, instance -> instance.matches(standing));
    }

    /** 交易角色。 */
    public enum Role {
        ANY,
        BUYER,
        SELLER;

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Role byId(String raw) {
            for (Role role : values()) {
                if (role.id().equals(raw)) {
                    return role;
                }
            }
            throw new JsonSyntaxException("unknown market role '" + raw + "', expected any, buyer or seller");
        }
    }

    public static final class TriggerInstance extends AbstractCriterionTriggerInstance {

        private final Role role;
        private final int count;
        @Nullable
        private final Long volume;
        @Nullable
        private final Integer partners;
        @Nullable
        private final Long partnerMin;

        public TriggerInstance(ContextAwarePredicate player, Role role, int count, @Nullable Long volume,
                               @Nullable Integer partners, @Nullable Long partnerMin) {
            super(ID, player);
            this.role = role;
            this.count = count;
            this.volume = volume;
            this.partners = partners;
            this.partnerMin = partnerMin;
        }

        /** 该角色下完成 count 笔合格交易。 */
        public static TriggerInstance trades(Role role, int count) {
            return new TriggerInstance(ContextAwarePredicate.ANY, role, count, null, null, null);
        }

        /** 作为卖家累计计数成交额达到 volume。 */
        public static TriggerInstance sold(long volume) {
            return new TriggerInstance(ContextAwarePredicate.ANY, Role.SELLER, 1, volume, null, null);
        }

        /** 作为卖家累计计数成交额达到 volume, 且至少 partners 位买家各贡献不少于 partnerMin。 */
        public static TriggerInstance soldToPartners(long volume, int partners, long partnerMin) {
            return new TriggerInstance(ContextAwarePredicate.ANY, Role.SELLER, 1, volume, partners, partnerMin);
        }

        boolean matches(MarketStanding standing) {
            if (standing.trades(role) < count) {
                return false;
            }
            if (volume != null && standing.sellerVolume() < volume) {
                return false;
            }
            return partners == null || standing.partnersAtLeast(partnerMin == null ? 0L : partnerMin) >= partners;
        }

        @Override
        public JsonObject serializeToJson(SerializationContext context) {
            JsonObject json = super.serializeToJson(context);
            json.addProperty("role", role.id());
            json.addProperty("count", count);
            if (volume != null) {
                json.addProperty("volume", volume);
            }
            if (partners != null) {
                json.addProperty("partners", partners);
            }
            if (partnerMin != null) {
                json.addProperty("partner_min", partnerMin);
            }
            return json;
        }
    }
}

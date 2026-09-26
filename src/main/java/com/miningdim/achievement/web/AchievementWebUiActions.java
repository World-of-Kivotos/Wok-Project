package com.miningdim.achievement.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.miningdim.achievement.AchievementServices;
import com.miningdim.achievement.reward.AchievementReward;
import com.miningdim.achievement.reward.AchievementRewardRepository;
import com.miningdim.achievement.reward.AchievementRewardService;
import com.miningdim.achievement.reward.ClaimResult;
import com.miningdim.achievement.reward.PointBalance;
import com.miningdim.achievement.shop.AchievementPointShop;
import com.miningdim.achievement.shop.PointShopGoods;
import com.miningdim.achievement.shop.PurchaseResult;
import com.miningdim.title.TitleServices;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiItemJson;
import com.miningdim.webui.server.WebUiPayloads;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import com.miningdim.webui.server.WebUiTextJson;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * G 面板成就点商店页的 achievement.* WebUiAction (Achievement_System_DesignSpec 8.2), 由 {@code AchievementSystem} 注册。
 *
 * <ul>
 *   <li>{@code achievement.pointShop} (只读, 可进批): 成就点余额与累计获得、待领取奖励、上架商品 (含本人的已兑换次数与
 *       剩余限购、买不买得起、前置成就是否已获得);</li>
 *   <li>{@code achievement.pointShopBuy}: {@code {goodsId}}, 经 {@link AchievementPointShop#buy} 兑换;</li>
 *   <li>{@code achievement.claimRewards}: {@code {advancementIds: string[] | "all"}}, 经
 *       {@link AchievementRewardService} 领取 (与聊天里的 [领取] 是同一个服务方法)。</li>
 * </ul>
 *
 * 失败一律是带稳定码的业务拒绝 (码表见 {@link WebUiErrorCodes} 的成就点商店一节), 前端认码不认文本。成就标题、称号徽记
 * 经 {@link WebUiTextJson} 发成"片段 + 颜色 + 粗体", 页面按游戏里的样子原样画。全部可空字段显式序列化为 JSON null。
 */
public final class AchievementWebUiActions {

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    /** achievement.claimRewards 一次最多指定的条数。首批成就 70 条, 留出余量; 只为挡住离谱的数组。 */
    private static final int MAX_CLAIM_IDS = 128;
    private static final String CLAIM_ALL = "all";

    private AchievementWebUiActions() {
    }

    /** 把三条 achievement.* action 注册进派发器。 */
    public static void registerAll() {
        WebUiServerDispatcher.register("achievement.pointShop", POINT_SHOP);
        WebUiServerDispatcher.register("achievement.pointShopBuy", POINT_SHOP_BUY);
        WebUiServerDispatcher.register("achievement.claimRewards", CLAIM_REWARDS);
    }

    static final WebUiAction POINT_SHOP = (sender, payload) -> {
        UUID uuid = sender.getUUID();
        AchievementRewardRepository rewards = AchievementServices.rewards();
        PointBalance points = rewards.points(uuid);

        JsonArray pending = new JsonArray();
        for (AchievementReward reward : rewards.pending(uuid)) {
            JsonObject row = new JsonObject();
            row.addProperty("advancementId", reward.advancementId().toString());
            row.addProperty("tier", reward.tier().id());
            row.addProperty("points", reward.points());
            row.addProperty("earnedAt", reward.earnedAt());
            row.add("name", advancementName(sender.server, reward.advancementId()));
            ResourceLocation titleId = reward.titleId();
            row.addProperty("titleId", titleId == null ? null : titleId.toString());
            row.add("titleBadge", titleId == null ? JsonNull.INSTANCE : titleBadge(titleId));
            pending.add(row);
        }

        JsonObject result = new JsonObject();
        result.addProperty("balance", points.balance());
        result.addProperty("lifetime", points.lifetime());
        result.add("pending", pending);
        result.add("goods", goodsRows(sender, points));
        return GSON.toJson(result);
    };

    static final WebUiAction POINT_SHOP_BUY = (sender, payload) -> {
        ResourceLocation goodsId = resourceId(payload, "goodsId");
        PurchaseResult outcome = AchievementPointShop.buy(sender, goodsId);
        String goodsText = goodsId.toString();
        switch (outcome.status()) {
            case PURCHASED -> {
                PointShopGoods goods = Objects.requireNonNull(outcome.goods(), "purchased goods");
                PointBalance balance = Objects.requireNonNull(outcome.balance(), "balance after purchase");
                Integer limit = goods.effectiveLimit();
                JsonObject result = new JsonObject();
                result.addProperty("goodsId", goodsText);
                result.addProperty("type", goods.type().id());
                result.addProperty("price", goods.price());
                result.addProperty("balance", balance.balance());
                result.addProperty("lifetime", balance.lifetime());
                result.addProperty("purchased", outcome.purchased());
                result.addProperty("remaining", limit == null ? null : Math.max(0, limit - outcome.purchased()));
                return GSON.toJson(result);
            }
            case GOODS_UNKNOWN -> throw new WebUiBusinessException(WebUiErrorCodes.GOODS_UNKNOWN,
                    "商品不存在或已下架: " + goodsText, false, Map.of("goodsId", goodsText));
            case REQUIREMENT_UNMET -> throw new WebUiBusinessException(WebUiErrorCodes.GOODS_REQUIREMENT_UNMET,
                    "还没有获得兑换这件商品所需的成就", false, Map.of("goodsId", goodsText, "advancementId",
                    String.valueOf(Objects.requireNonNull(outcome.goods()).requiresAdvancement())));
            case TITLE_OWNED -> throw new WebUiBusinessException(WebUiErrorCodes.GOODS_LIMIT_REACHED,
                    "已经拥有这个称号", false, Map.of("goodsId", goodsText, "limit", "1", "reason", "TITLE_OWNED"));
            case LIMIT_REACHED -> throw new WebUiBusinessException(WebUiErrorCodes.GOODS_LIMIT_REACHED,
                    "已达每人限购", false, Map.of("goodsId", goodsText,
                    "limit", String.valueOf(Objects.requireNonNull(outcome.goods()).effectiveLimit()),
                    "purchased", Integer.toString(outcome.purchased())));
            case POINTS_INSUFFICIENT -> throw new WebUiBusinessException(WebUiErrorCodes.POINTS_INSUFFICIENT,
                    "成就点不足", false, Map.of("goodsId", goodsText,
                    "price", Integer.toString(Objects.requireNonNull(outcome.goods()).price()),
                    "balance", Long.toString(Objects.requireNonNull(outcome.balance()).balance())));
            case INVENTORY_FULL -> throw new WebUiBusinessException(WebUiErrorCodes.INVENTORY_FULL,
                    "背包已满, 未扣成就点", false, Map.of("goodsId", goodsText));
            case STORE_FAILED -> throw storeFailed();
            default -> throw new IllegalStateException("unhandled purchase result " + outcome.status());
        }
    };

    static final WebUiAction CLAIM_REWARDS = (sender, payload) -> {
        JsonElement raw = WebUiPayloads.requiredField(payload, "advancementIds");
        boolean all;
        ClaimResult outcome;
        if (raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString()) {
            if (!CLAIM_ALL.equals(raw.getAsString())) {
                throw WebUiPayloads.illegalValue("advancementIds", raw.getAsString(),
                        "advancementIds 只接受 \"all\" 或进度 id 数组");
            }
            all = true;
            outcome = AchievementRewardService.claimAll(sender);
        } else if (raw.isJsonArray()) {
            all = false;
            outcome = AchievementRewardService.claimSelected(sender, advancementIds(raw.getAsJsonArray()));
        } else {
            throw WebUiPayloads.wrongType("advancementIds", "\"all\" 或字符串数组");
        }
        switch (outcome.status()) {
            case CLAIMED -> {
                return GSON.toJson(claimedResult(outcome));
            }
            case ALREADY_CLAIMED -> throw new WebUiBusinessException(WebUiErrorCodes.REWARD_ALREADY_CLAIMED,
                    "这条奖励已经领取过了", false, Map.of("advancementId", String.valueOf(outcome.advancementId())));
            case NOT_FOUND -> throw WebUiPayloads.illegalValue("advancementIds",
                    String.valueOf(outcome.advancementId()), "没有这条奖励: " + outcome.advancementId());
            case NOTHING_PENDING -> throw new WebUiBusinessException(WebUiErrorCodes.REWARD_NONE_PENDING,
                    "没有待领取的奖励", false);
            case TITLE_UNAVAILABLE -> throw new WebUiBusinessException(WebUiErrorCodes.REWARD_TITLE_UNAVAILABLE,
                    "奖励附带的称号暂时无法发放, 本次领取已全部撤销", false, Map.of(
                    "advancementId", String.valueOf(outcome.advancementId()),
                    "titleId", String.valueOf(outcome.titleId()),
                    "scope", all ? "all" : "selected"));
            case STORE_FAILED -> throw storeFailed();
            default -> throw new IllegalStateException("unhandled claim result " + outcome.status());
        }
    };

    private static JsonObject claimedResult(ClaimResult outcome) {
        PointBalance balance = Objects.requireNonNull(outcome.balance(), "balance after claim");
        JsonArray claimed = new JsonArray();
        JsonArray titles = new JsonArray();
        for (AchievementReward reward : outcome.claimed()) {
            JsonObject row = new JsonObject();
            row.addProperty("advancementId", reward.advancementId().toString());
            row.addProperty("tier", reward.tier().id());
            row.addProperty("points", reward.points());
            ResourceLocation titleId = reward.titleId();
            row.addProperty("titleId", titleId == null ? null : titleId.toString());
            claimed.add(row);
            if (titleId != null) {
                titles.add(titleId.toString());
            }
        }
        JsonObject result = new JsonObject();
        result.add("claimed", claimed);
        result.addProperty("points", outcome.points());
        result.addProperty("balance", balance.balance());
        result.addProperty("lifetime", balance.lifetime());
        result.add("titles", titles);
        return result;
    }

    /**
     * 上架商品, 按展示顺序。称号类商品引用的称号定义此刻没有加载时不列出 (兑换同样会被拒): 宁可少列一件,
     * 也不让玩家看见一件点下去必然失败的商品。
     */
    private static JsonArray goodsRows(ServerPlayer sender, PointBalance points) {
        UUID uuid = sender.getUUID();
        Map<ResourceLocation, Integer> purchased = new HashMap<>(
                AchievementServices.pointShopRepository().purchaseCounts(uuid));
        Set<ResourceLocation> ownedTitles = TitleServices.isRegistered()
                ? TitleServices.titleService().owned(uuid)
                : Set.of();
        JsonArray rows = new JsonArray();
        for (PointShopGoods goods : AchievementServices.pointShop().all()) {
            boolean title = goods.type() == PointShopGoods.Type.TITLE;
            if (title && !AchievementPointShop.titleDefined(goods.titleId())) {
                continue;
            }
            int bought = purchased.getOrDefault(goods.id(), 0);
            Integer limit = goods.effectiveLimit();
            boolean titleOwned = title && ownedTitles.contains(goods.titleId());
            Integer remaining = limit == null ? null : titleOwned ? 0 : Math.max(0, limit - bought);

            JsonObject row = new JsonObject();
            row.addProperty("goodsId", goods.id().toString());
            row.addProperty("type", goods.type().id());
            row.addProperty("price", goods.price());
            row.addProperty("sort", goods.sort());
            row.addProperty("limit", limit);
            row.addProperty("purchased", bought);
            row.addProperty("remaining", remaining);
            row.addProperty("affordable", points.balance() >= goods.price());
            row.add("requirement", requirementRow(sender, goods));
            row.add("item", title ? JsonNull.INSTANCE : itemRow(goods.createStack(uuid)));
            if (title) {
                JsonObject titleRow = new JsonObject();
                ResourceLocation titleId = Objects.requireNonNull(goods.titleId(), "title goods title id");
                titleRow.addProperty("titleId", titleId.toString());
                titleRow.add("badge", titleBadge(titleId));
                titleRow.addProperty("owned", titleOwned);
                row.add("title", titleRow);
            } else {
                row.add("title", JsonNull.INSTANCE);
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * 商品的前置成就。隐藏成就在玩家获得之前不发名字 (name 为 null, hidden 为 true), 与称号说明
     * "由一项隐藏成就获得"同一口径, 不在成就点商店里提前泄露隐藏成就。
     */
    private static JsonElement requirementRow(ServerPlayer sender, PointShopGoods goods) {
        ResourceLocation required = goods.requiresAdvancement();
        if (required == null) {
            return JsonNull.INSTANCE;
        }
        Advancement advancement = sender.server.getAdvancements().getAdvancement(required);
        DisplayInfo display = advancement == null ? null : advancement.getDisplay();
        boolean met = AchievementPointShop.requirementMet(sender, goods);
        boolean hidden = display != null && display.isHidden();
        JsonObject row = new JsonObject();
        row.addProperty("advancementId", required.toString());
        row.addProperty("met", met);
        row.addProperty("hidden", hidden);
        row.add("name", display == null || (hidden && !met) ? JsonNull.INSTANCE
                : WebUiTextJson.segments(display.getTitle()));
        return row;
    }

    private static JsonObject itemRow(ItemStack stack) {
        ResourceLocation itemId = Objects.requireNonNull(ForgeRegistries.ITEMS.getKey(stack.getItem()),
                "point shop item is not registered: " + stack.getItem());
        JsonObject row = new JsonObject();
        row.addProperty("itemId", itemId.toString());
        row.addProperty("descriptionId", stack.getDescriptionId());
        row.addProperty("count", stack.getCount());
        WebUiItemJson.appendVariant(row, stack);
        return row;
    }

    /** 进度标题 (与 L 键界面、Toast 同一个 Component, 带档位颜色); 服务端没有加载该进度时为 null。 */
    private static JsonElement advancementName(MinecraftServer server, ResourceLocation advancementId) {
        Advancement advancement = server.getAdvancements().getAdvancement(advancementId);
        if (advancement == null || advancement.getDisplay() == null) {
            return JsonNull.INSTANCE;
        }
        return WebUiTextJson.segments(advancement.getDisplay().getTitle());
    }

    /** 称号徽记 {@code [称号]}; 称号门面未注入或定义缺失时为 null (页面退回显示 id)。 */
    private static JsonElement titleBadge(ResourceLocation titleId) {
        if (!TitleServices.isRegistered()) {
            return JsonNull.INSTANCE;
        }
        Component badge = TitleServices.titleService().badge(titleId).orElse(null);
        return badge == null ? JsonNull.INSTANCE : WebUiTextJson.segments(badge);
    }

    private static List<ResourceLocation> advancementIds(JsonArray raw) {
        if (raw.isEmpty() || raw.size() > MAX_CLAIM_IDS) {
            throw WebUiPayloads.illegalValue("advancementIds", Integer.toString(raw.size()),
                    "advancementIds 须含 1 ~ " + MAX_CLAIM_IDS + " 个进度 id");
        }
        List<ResourceLocation> ids = new ArrayList<>(raw.size());
        for (JsonElement element : raw) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw WebUiPayloads.wrongType("advancementIds", "\"all\" 或字符串数组");
            }
            ids.add(parseId("advancementIds", element.getAsString()));
        }
        return ids;
    }

    private static ResourceLocation resourceId(JsonObject payload, String field) {
        return parseId(field, WebUiPayloads.requiredString(payload, field));
    }

    private static ResourceLocation parseId(String field, String text) {
        ResourceLocation parsed = ResourceLocation.tryParse(text);
        if (parsed == null) {
            throw WebUiPayloads.illegalValue(field, text, field + " 不是合法的资源 id: " + text);
        }
        return parsed;
    }

    private static WebUiBusinessException storeFailed() {
        return new WebUiBusinessException(WebUiErrorCodes.STORE_FAILED, "数据库读写失败, 本次没有任何改动", false);
    }
}

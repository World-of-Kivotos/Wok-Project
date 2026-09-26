package com.miningdim.achievement.shop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.miningdim.title.CustomTitle;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.ArmorStandItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.HangingEntityItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MinecartItem;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 一件成就点商店商品 (Achievement_System_DesignSpec 8.3), 来自 {@code data/<ns>/achievement_point_shop/<path>.json},
 * 商品 id 即资源路径。
 *
 * <pre>
 * { "type": "item", "item": { "id": "miningdim:deco_trophy_gold", "count": 1, "nbt": "{...}" },
 *   "price": 300, "limit_per_player": 1, "sort": 100, "requires_advancement": "miningdim:mining/deep_regular" }
 * { "type": "title", "title": "miningdim:mining/ore_codex", "price": 500 }
 * </pre>
 *
 * 物品类商品兑换出的每一件都打上绑定盖章 ({@link #createStack}): {@value #OWNER_UUID_TAG} 记兑换者 (婚姻共享背包的
 * 黑名单按这个键拦截绑定物), {@value #GOODS_TAG} 记商品 id (市场白名单按这个键拒绝上架, 8.4 的经济隔离硬约束)。盖章只在
 * 物品堆上, 所以放置进世界就会丢章的物品 (方块、挂画、盔甲架等, 见 {@link #losesStampWhenPlaced}) 加载时即拒收 ——
 * 上面示例里的装饰奖杯若做成方块, 要先有能把盖章带回掉落物的专用方块。
 *
 * @param id                  商品 id
 * @param type                物品或称号
 * @param item                物品类商品的物品; 称号类为 null
 * @param count               物品数量 (1 ~ 该物品的最大堆叠数); 称号类为 1
 * @param nbt                 物品的附加 NBT (datapack 里写 SNBT 字符串); 可空
 * @param titleId             称号类商品发放的称号; 物品类为 null
 * @param price               成就点价格, 至少 1
 * @param limitPerPlayer      每人限购; null 为不限。称号类恒为 1
 * @param sort                排序权重, 越大越靠前 (与称号的 sort 同一语义)
 * @param requiresAdvancement 兑换前须已获得的成就; 可空
 */
public record PointShopGoods(ResourceLocation id, Type type, @Nullable Item item, int count, @Nullable CompoundTag nbt,
                             @Nullable ResourceLocation titleId, int price, @Nullable Integer limitPerPlayer, int sort,
                             @Nullable ResourceLocation requiresAdvancement) {

    /** 兑换者 UUID 的盖章键, 与婚姻共享背包黑名单、塔罗牌的绑定盖章同一个键。 */
    public static final String OWNER_UUID_TAG = "OwnerUUID";

    /**
     * 商品 id 的盖章键。市场白名单 ({@code market.MarketTradeWhitelist}) 按同名键拒绝上架; 两个模块之间不互相引用,
     * 键名由成就模块的 GameTest 经真实的 market.tradable / market.place 锁住。
     */
    public static final String GOODS_TAG = "PointShopGoods";

    public PointShopGoods {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        nbt = nbt == null ? null : nbt.copy();
    }

    /** 商品种类, 落在 datapack 的 {@code type} 字段。 */
    public enum Type {
        ITEM,
        TITLE;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 附加 NBT 的副本 (CompoundTag 可变, 不把内部那份交出去)。 */
    @Override
    @Nullable
    public CompoundTag nbt() {
        return nbt == null ? null : nbt.copy();
    }

    /** 实际生效的每人限购: 称号类恒为 1 (8.3), 物品类取定义值, null 为不限。 */
    @Nullable
    public Integer effectiveLimit() {
        return type == Type.TITLE ? Integer.valueOf(1) : limitPerPlayer;
    }

    /**
     * 造一件兑换给 owner 的物品, 带绑定盖章 (类注释)。datapack 里的 NBT 若写了同名键, 以盖章为准。
     *
     * @throws IllegalStateException 这是称号类商品
     */
    public ItemStack createStack(UUID owner) {
        if (item == null) {
            throw new IllegalStateException("point shop goods " + id + " is not an item goods");
        }
        ItemStack stack = new ItemStack(item, count);
        if (nbt != null) {
            stack.setTag(nbt.copy());
        }
        CompoundTag tag = stack.getOrCreateTag();
        tag.putUUID(OWNER_UUID_TAG, owner);
        tag.putString(GOODS_TAG, id.toString());
        return stack;
    }

    /**
     * 解析一件商品; 任何不合格都抛 {@link JsonParseException} 并说明原因 (加载器据此跳过该条)。
     *
     * 校验: type 只收 item / title, 且与 item / title 字段一一对应; price 至少 1; limit_per_player 至少 1, 称号类只能
     * 缺省或写 1; 物品必须是已注册的非空气物品, 且不是放置进世界就会丢掉绑定盖章的那几类 ({@link #losesStampWhenPlaced}),
     * 数量在 1 ~ 最大堆叠数之间, NBT 必须是合法 SNBT; 称号不得是专属称号
     * (专属称号不可发放, Title_System_DesignSpec 13.2)。称号定义此刻是否已加载不在这里判断 —— 两个数据包加载器的
     * 先后没有保证, 兑换与列表在用到时才查。
     */
    public static PointShopGoods fromJson(ResourceLocation id, JsonElement json) {
        JsonObject root = GsonHelper.convertToJsonObject(json, "point shop goods");
        String typeName = GsonHelper.getAsString(root, "type");
        Type type = switch (typeName) {
            case "item" -> Type.ITEM;
            case "title" -> Type.TITLE;
            default -> throw new JsonParseException("unknown goods type '" + typeName + "', expected item or title");
        };
        int price = GsonHelper.getAsInt(root, "price");
        if (price < 1) {
            throw new JsonParseException("price must be at least 1, got " + price);
        }
        Integer limit = null;
        if (root.has("limit_per_player")) {
            limit = GsonHelper.getAsInt(root, "limit_per_player");
            if (limit < 1) {
                throw new JsonParseException("limit_per_player must be at least 1, got " + limit);
            }
        }
        int sort = GsonHelper.getAsInt(root, "sort", 0);
        ResourceLocation requires = root.has("requires_advancement")
                ? parseId(GsonHelper.getAsString(root, "requires_advancement"), "requires_advancement")
                : null;

        if (type == Type.TITLE) {
            if (root.has("item")) {
                throw new JsonParseException("title goods must not declare 'item'");
            }
            if (limit != null && limit != 1) {
                throw new JsonParseException("title goods are limited to 1 per player, got limit_per_player " + limit);
            }
            ResourceLocation titleId = parseId(GsonHelper.getAsString(root, "title"), "title");
            if (CustomTitle.isCustomId(titleId)) {
                throw new JsonParseException("sponsor custom titles cannot be sold: " + titleId);
            }
            return new PointShopGoods(id, type, null, 1, null, titleId, price, null, sort, requires);
        }

        if (root.has("title")) {
            throw new JsonParseException("item goods must not declare 'title'");
        }
        JsonObject itemJson = GsonHelper.getAsJsonObject(root, "item");
        ResourceLocation itemId = parseId(GsonHelper.getAsString(itemJson, "id"), "item.id");
        Item item = ForgeRegistries.ITEMS.containsKey(itemId) ? ForgeRegistries.ITEMS.getValue(itemId) : null;
        if (item == null || item == Items.AIR) {
            throw new JsonParseException("unknown item " + itemId);
        }
        if (losesStampWhenPlaced(item)) {
            throw new JsonParseException("item " + itemId + " is placed into the world as a block or entity and drops a"
                    + " fresh stack without the binding stamp, so it could be listed on the market");
        }
        int count = GsonHelper.getAsInt(itemJson, "count", 1);
        int maxStack = new ItemStack(item).getMaxStackSize();
        if (count < 1 || count > maxStack) {
            throw new JsonParseException("item count must be 1.." + maxStack + " for " + itemId + ", got " + count);
        }
        CompoundTag nbt = null;
        if (itemJson.has("nbt")) {
            try {
                nbt = TagParser.parseTag(GsonHelper.getAsString(itemJson, "nbt"));
            } catch (CommandSyntaxException invalid) {
                throw new JsonParseException("item.nbt is not valid SNBT: " + invalid.getMessage());
            }
        }
        return new PointShopGoods(id, type, item, count, nbt, null, price, limit, sort, requires);
    }

    /**
     * 放置进世界就会丢掉绑定盖章的物品 (8.4 经济隔离): 方块物品放下再挖掉、挂画 / 展示框 / 盔甲架 / 刷怪蛋 / 船 / 矿车放出
     * 的实体被打掉、桶倒出或装进流体之后, 回到背包的都是按类型新造的物品堆, 不带 {@value #OWNER_UUID_TAG} 与
     * {@value #GOODS_TAG}, 市场白名单与婚姻共享背包黑名单都认不出来。这几类按物品类型就能认出, 加载时整类拒收, 直到有能把
     * 盖章带回掉落物的专用方块 / 实体; 其余会被消耗后换回别的物品的东西 (鞍、拴绳之类) 按类型认不出来, 由选品把关。
     */
    static boolean losesStampWhenPlaced(Item item) {
        return item instanceof BlockItem || item instanceof HangingEntityItem || item instanceof ArmorStandItem
                || item instanceof SpawnEggItem || item instanceof BoatItem || item instanceof MinecartItem
                || item instanceof BucketItem;
    }

    private static ResourceLocation parseId(String raw, String field) {
        ResourceLocation parsed = ResourceLocation.tryParse(raw);
        if (parsed == null) {
            throw new JsonParseException(field + " is not a valid resource id: '" + raw + "'");
        }
        return parsed;
    }
}

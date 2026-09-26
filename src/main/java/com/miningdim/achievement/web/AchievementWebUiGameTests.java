package com.miningdim.achievement.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.achievement.AchievementIds;
import com.miningdim.achievement.AchievementServices;
import com.miningdim.achievement.reward.AchievementRewardRepository;
import com.miningdim.achievement.reward.AchievementRewardService;
import com.miningdim.achievement.reward.PointBalance;
import com.miningdim.achievement.reward.SqliteAchievementRewardRepository;
import com.miningdim.achievement.shop.AchievementPointShop;
import com.miningdim.achievement.shop.PointShopCatalog;
import com.miningdim.achievement.shop.PointShopGoods;
import com.miningdim.achievement.shop.PointShopLoader;
import com.miningdim.achievement.shop.PointShopRepository;
import com.miningdim.achievement.shop.SqlitePointShopRepository;
import com.miningdim.achievement.trigger.DailyCounterRepository;
import com.miningdim.core.MiningConstants;
import com.miningdim.store.MiningDb;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.testutil.TempStoreDb;
import com.miningdim.title.ITitleService;
import com.miningdim.title.TitleDefinition;
import com.miningdim.title.TitleDefinitions;
import com.miningdim.title.TitleService;
import com.miningdim.title.TitleServices;
import com.miningdim.title.TitleSource;
import com.miningdim.title.store.SqliteTitleRepository;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.advancements.Advancement;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 成就点商店与奖励领取的 GameTest (Achievement_System_DesignSpec 第八章、第十二章, batch {@code achievement_webui}),
 * 仿照 {@code QuestWebUiGameTests}: 一律经派发器里真实注册的 handler 驱动 achievement.* action。
 *
 * <p>每个用例把奖励仓库、成就点商店仓库与称号门面换成 {@link TempStoreDb} 临时统一库上的实现, 商品换成用例自己的夹具
 * (模组本身不带商品, 夹具经 {@link PointShopGoods#fromJson} 解析, 与数据包走同一条解析路径)。收尾时先让 mock 玩家下线,
 * 再恢复原来的仓库、商品与称号门面。强断言 (删被测核心逻辑必挂):
 * <ol>
 *   <li>achievement.pointShop: 余额、累计获得、待领取奖励 (成就标题与称号徽记按游戏里的颜色拍平)、商品 (剩余限购、
 *       买不买得起、前置成就, 隐藏的前置成就在获得前不发名字); 空目录回空数组; 引用未加载称号的商品不列出;</li>
 *   <li>achievement.pointShopBuy: 扣点与 shop_buy 流水同一事务; 物品带 OwnerUUID 与商品 id 盖章进背包; 限购按流水计
 *       (预先写进流水的一条也算); 余额不足、背包已满、前置成就未获得、商品未知、称号已拥有都不扣点且逐条回稳定码;</li>
 *   <li>称号类商品经 grantInTransaction 与扣点同事务发放 (来源 point_shop), 提交后提示一次;</li>
 *   <li>事务里任何一步失败整体回滚: 写流水失败时扣掉的点退回, 发称号失败时点与流水都不留, 也不发提示;
 *       调用方已开着事务时拒绝;</li>
 *   <li>兑换出的物品经真实的 market.tradable / market.place 被拒上架 (rule=POINT_SHOP_BOUND), 装进潜影盒也一样,
 *       同种未绑定的物品照常可挂;</li>
 *   <li>achievement.claimRewards: 指定 id 与 "all" 两种领取, 已领取 / 没有待领取 / 称号发不出去 (区分 all 与 selected) /
 *       写库失败 / 入参非法逐条回稳定码, 失败时一条都不领;</li>
 *   <li>只有 achievement.pointShop 能进 system.batch, 两条写操作在批内被逐条拒且不产生副作用;</li>
 *   <li>商品加载器逐条跳过写坏的定义, 目录按 sort 降序、id 升序。</li>
 * </ol>
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class AchievementWebUiGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "achievement_webui";

    private static final String POINT_SHOP = "achievement.pointShop";
    private static final String BUY = "achievement.pointShopBuy";
    private static final String CLAIM = "achievement.claimRewards";

    private static final ResourceLocation FIRST_ENTRY = AchievementIds.id("mining/first_entry");
    private static final ResourceLocation TRAP_SPRUNG = AchievementIds.id("mining/trap_sprung");
    private static final ResourceLocation BLOCKS_100K = AchievementIds.id("mining/blocks_100k");
    private static final ResourceLocation HARD_ACTIVE_100H = AchievementIds.id("mining/hard_active_100h");
    private static final ResourceLocation SOLO_STAR_9 = AchievementIds.id("combat/solo_star_9");
    private static final ResourceLocation ORE_CODEX_TITLE = AchievementIds.id("mining/ore_codex");

    private static final ResourceLocation AMETHYST = id("test/amethyst");
    private static final ResourceLocation LANTERN = id("test/lantern");
    private static final ResourceLocation SECRET = id("test/secret");
    private static final ResourceLocation CODEX = id("test/codex_title");
    private static final ResourceLocation MISSING_TITLE = id("test/missing_title");

    /** 市场白名单对成就点商店绑定物的规则名。写死字面量: 它是前端 errorText 按值分句的契约面。 */
    private static final String RULE_POINT_SHOP_BOUND = "POINT_SHOP_BOUND";
    private static final String TITLE_OBTAINED_KEY = "title.miningdim.message.obtained";

    private AchievementWebUiGameTests() {
    }

    // ---- 1. achievement.pointShop ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pointShopReportsBalancePendingRewardsAndAnEmptyCatalog(GameTestHelper helper) {
        ShopEnv env = new ShopEnv(helper, List.of(), Set.of());
        try {
            ServerPlayer player = env.player("ach-web-empty");
            JsonObject empty = handle(helper, POINT_SHOP, player, new JsonObject());
            helper.assertTrue(empty.keySet().equals(Set.of("balance", "lifetime", "pending", "goods")),
                    "achievement.pointShop 只含 balance/lifetime/pending/goods 四个键, 实为 " + empty.keySet());
            helper.assertTrue(empty.get("balance").getAsLong() == 0L && empty.get("lifetime").getAsLong() == 0L
                            && empty.getAsJsonArray("pending").isEmpty() && empty.getAsJsonArray("goods").isEmpty(),
                    "新玩家、空目录: 余额 0、没有待领取、商品为空数组 (不是缺键), 实为 " + empty);

            AchievementRewardService.addPoints(player.getUUID(), 40, "test");
            grant(env.server, player, FIRST_ENTRY);
            grant(env.server, player, SOLO_STAR_9);
            JsonObject result = handle(helper, POINT_SHOP, player, new JsonObject());
            helper.assertTrue(result.get("balance").getAsLong() == 40L && result.get("lifetime").getAsLong() == 40L,
                    "余额与累计获得取自成就点账户, 实为 " + result);
            JsonArray pending = result.getAsJsonArray("pending");
            helper.assertTrue(pending.size() == 2, "两条待领取奖励, 实为 " + pending);

            JsonObject firstEntry = rowWith(pending, "advancementId", FIRST_ENTRY.toString());
            helper.assertTrue(firstEntry.keySet().equals(Set.of("advancementId", "tier", "points", "earnedAt", "name",
                            "titleId", "titleBadge")),
                    "待领取奖励行的键集不对, 实为 " + firstEntry.keySet());
            helper.assertTrue("bronze".equals(firstEntry.get("tier").getAsString())
                            && firstEntry.get("points").getAsInt() == 10 && firstEntry.get("earnedAt").getAsLong() > 0L
                            && firstEntry.get("titleId").isJsonNull() && firstEntry.get("titleBadge").isJsonNull(),
                    "初入矿区: 铜档 10 点、没有称号 (titleId/titleBadge 显式为 null), 实为 " + firstEntry);
            JsonArray bronzeName = firstEntry.getAsJsonArray("name");
            helper.assertTrue(bronzeName.size() == 1
                            && AchievementIds.titleKey(FIRST_ENTRY).equals(segment(bronzeName, 0).get("k").getAsString())
                            && "#C8834A".equals(segment(bronzeName, 0).get("color").getAsString())
                            && !segment(bronzeName, 0).get("bold").getAsBoolean(),
                    "单色档成就标题保留翻译键、整段铜色, 实为 " + bronzeName);

            JsonObject legend = rowWith(pending, "advancementId", SOLO_STAR_9.toString());
            JsonArray legendName = legend.getAsJsonArray("name");
            helper.assertTrue(legendName.size() > 1
                            && "#FF3D3D".equals(segment(legendName, 0).get("color").getAsString())
                            && "#FFD23F".equals(segment(legendName, legendName.size() - 1).get("color").getAsString())
                            && segment(legendName, 0).get("bold").getAsBoolean(),
                    "传说档成就标题逐字渐变、首尾字恰为首尾色标、粗体, 实为 " + legendName);
            JsonArray badge = legend.getAsJsonArray("titleBadge");
            helper.assertTrue(SOLO_STAR_9.toString().equals(legend.get("titleId").getAsString())
                            && "[".equals(segment(badge, 0).get("t").getAsString())
                            && "]".equals(segment(badge, badge.size() - 1).get("t").getAsString()),
                    "附带称号的奖励带渲染好的徽记 [一人成军], 实为 " + legend);
        } finally {
            env.close();
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pointShopListsGoodsWithLimitsAffordabilityAndRequirements(GameTestHelper helper) {
        ShopEnv env = new ShopEnv(helper, List.of(amethystGoods(), lanternGoods(), secretGoods(), codexGoods(),
                missingTitleGoods()), Set.of());
        try {
            ServerPlayer player = env.player("ach-web-list");
            AchievementRewardService.addPoints(player.getUUID(), 200, "test");
            JsonArray goods = handle(helper, POINT_SHOP, player, new JsonObject()).getAsJsonArray("goods");
            List<String> order = new ArrayList<>();
            goods.forEach(element -> order.add(element.getAsJsonObject().get("goodsId").getAsString()));
            helper.assertTrue(order.equals(List.of(CODEX.toString(), AMETHYST.toString(), LANTERN.toString(),
                            SECRET.toString())),
                    "商品按 sort 降序、id 升序; 引用未加载称号的商品不列出, 实为 " + order);

            JsonObject amethyst = rowWith(goods, "goodsId", AMETHYST.toString());
            helper.assertTrue(amethyst.keySet().equals(Set.of("goodsId", "type", "price", "sort", "limit", "purchased",
                            "remaining", "affordable", "requirement", "item", "title")),
                    "商品行的键集不对, 实为 " + amethyst.keySet());
            helper.assertTrue("item".equals(amethyst.get("type").getAsString())
                            && amethyst.get("price").getAsInt() == 120 && amethyst.get("limit").getAsInt() == 2
                            && amethyst.get("purchased").getAsInt() == 0 && amethyst.get("remaining").getAsInt() == 2
                            && amethyst.get("affordable").getAsBoolean() && amethyst.get("requirement").isJsonNull()
                            && amethyst.get("title").isJsonNull(),
                    "物品商品: 价格、限购、剩余、买得起、无前置成就, 实为 " + amethyst);
            JsonObject item = amethyst.getAsJsonObject("item");
            helper.assertTrue("minecraft:amethyst_shard".equals(item.get("itemId").getAsString())
                            && Items.AMETHYST_SHARD.getDescriptionId().equals(item.get("descriptionId").getAsString())
                            && item.get("count").getAsInt() == 3,
                    "物品行带 itemId / descriptionId / count, 实为 " + item);

            JsonObject codex = rowWith(goods, "goodsId", CODEX.toString());
            helper.assertTrue("title".equals(codex.get("type").getAsString()) && codex.get("limit").getAsInt() == 1
                            && codex.get("remaining").getAsInt() == 1 && !codex.get("affordable").getAsBoolean()
                            && codex.get("item").isJsonNull(),
                    "称号商品: 限购恒为 1, 300 点买不起, 没有物品行, 实为 " + codex);
            JsonObject title = codex.getAsJsonObject("title");
            helper.assertTrue(ORE_CODEX_TITLE.toString().equals(title.get("titleId").getAsString())
                            && !title.get("owned").getAsBoolean() && title.getAsJsonArray("badge").size() == 3,
                    "称号行带 titleId、未拥有、单色徽记 [ + 键 + ], 实为 " + title);

            JsonObject lantern = rowWith(goods, "goodsId", LANTERN.toString());
            JsonObject requirement = lantern.getAsJsonObject("requirement");
            helper.assertTrue(lantern.get("limit").isJsonNull() && lantern.get("remaining").isJsonNull()
                            && FIRST_ENTRY.toString().equals(requirement.get("advancementId").getAsString())
                            && !requirement.get("met").getAsBoolean() && !requirement.get("hidden").getAsBoolean()
                            && requirement.get("name").isJsonArray(),
                    "不限购的商品 limit/remaining 为 null; 前置成就未获得但不隐藏时照发名字, 实为 " + lantern);
            JsonObject secret = rowWith(goods, "goodsId", SECRET.toString()).getAsJsonObject("requirement");
            helper.assertTrue(secret.get("hidden").getAsBoolean() && !secret.get("met").getAsBoolean()
                            && secret.get("name").isJsonNull(),
                    "隐藏的前置成就在获得前不发名字, 实为 " + secret);

            grant(env.server, player, FIRST_ENTRY);
            grant(env.server, player, TRAP_SPRUNG);
            JsonArray after = handle(helper, POINT_SHOP, player, new JsonObject()).getAsJsonArray("goods");
            helper.assertTrue(rowWith(after, "goodsId", LANTERN.toString()).getAsJsonObject("requirement")
                            .get("met").getAsBoolean(),
                    "获得前置成就后 met=true");
            JsonObject revealed = rowWith(after, "goodsId", SECRET.toString()).getAsJsonObject("requirement");
            helper.assertTrue(revealed.get("met").getAsBoolean() && revealed.get("name").isJsonArray(),
                    "隐藏成就获得之后才发名字, 实为 " + revealed);
        } finally {
            env.close();
        }
        helper.succeed();
    }

    // ---- 2. achievement.pointShopBuy ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buyItemDebitsOnceStampsTheItemAndCountsTheLimitFromTheLedger(GameTestHelper helper) {
        ShopEnv env = new ShopEnv(helper, List.of(amethystGoods(), lanternGoods()), Set.of());
        try {
            ServerPlayer player = env.player("ach-web-buy");
            UUID uuid = player.getUUID();
            player.getInventory().clearContent();
            AchievementRewardService.addPoints(uuid, 500, "test");

            JsonObject bought = handle(helper, BUY, player, goodsPayload(AMETHYST));
            helper.assertTrue(bought.keySet().equals(Set.of("goodsId", "type", "price", "balance", "lifetime",
                            "purchased", "remaining")),
                    "兑换回执的键集不对, 实为 " + bought.keySet());
            helper.assertTrue(bought.get("balance").getAsLong() == 380L && bought.get("lifetime").getAsLong() == 500L
                            && bought.get("purchased").getAsInt() == 1 && bought.get("remaining").getAsInt() == 1,
                    "兑换回执: 余额 380、累计获得不变、第 1 次、还剩 1 次, 实为 " + bought);
            helper.assertTrue(env.rewards.points(uuid).equals(new PointBalance(380L, 500L)), "余额只减 120, 累计获得不变");
            helper.assertTrue(ledger(env.connection, uuid).contains(new LedgerRow(-120L, "shop_buy", AMETHYST.toString())),
                    "扣点写一条 shop_buy 流水, ref 为商品 id, 实为 " + ledger(env.connection, uuid));
            ItemStack stack = stackOf(player, Items.AMETHYST_SHARD);
            helper.assertTrue(stack.getCount() == 3, "兑换的 3 个紫水晶碎片进了背包");
            CompoundTag tag = Objects.requireNonNull(stack.getTag(), "兑换出的物品必须带盖章 NBT");
            helper.assertTrue(tag.hasUUID(PointShopGoods.OWNER_UUID_TAG)
                            && uuid.equals(tag.getUUID(PointShopGoods.OWNER_UUID_TAG))
                            && AMETHYST.toString().equals(tag.getString(PointShopGoods.GOODS_TAG)),
                    "物品盖 OwnerUUID (兑换者) 与商品 id, 实为 " + tag);

            handle(helper, BUY, player, goodsPayload(AMETHYST));
            WebUiBusinessException limited = rejection(helper, BUY, player, goodsPayload(AMETHYST));
            helper.assertTrue(WebUiErrorCodes.GOODS_LIMIT_REACHED.equals(limited.errorCode())
                            && "2".equals(limited.params().get("limit")) && "2".equals(limited.params().get("purchased")),
                    "第 3 次超出限购 2, 实为 " + limited.errorCode() + " " + limited.params());
            helper.assertTrue(env.rewards.points(uuid).balance() == 260L && shopRows(env.connection, uuid) == 2,
                    "限购拒绝不扣点、不写流水");

            // 限购只数流水: 预先写进流水的一条兑换记录同样占掉灯笼的限购 (限购 1 的灯笼改成夹具 limit=1)。
            ShopEnv.install(List.of(amethystGoods(), limitedLanternGoods()));
            execute(env.connection, "INSERT INTO achievement_point_ledger (player_uuid, delta, reason, ref, at) VALUES ('"
                    + uuid + "', -10, 'shop_buy', '" + LANTERN + "', 1)");
            grant(env.server, player, FIRST_ENTRY);
            WebUiBusinessException fromLedger = rejection(helper, BUY, player, goodsPayload(LANTERN));
            helper.assertTrue(WebUiErrorCodes.GOODS_LIMIT_REACHED.equals(fromLedger.errorCode())
                            && "1".equals(fromLedger.params().get("purchased")),
                    "限购按流水计: 流水里已有一条兑换即达上限, 实为 " + fromLedger.params());
        } finally {
            env.close();
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buyRefusesWithoutChargingWhenAnyPreconditionFails(GameTestHelper helper) {
        ShopEnv env = new ShopEnv(helper, List.of(amethystGoods(), lanternGoods(), missingTitleGoods()), Set.of());
        try {
            ServerPlayer player = env.player("ach-web-refuse");
            UUID uuid = player.getUUID();
            player.getInventory().clearContent();
            AchievementRewardService.addPoints(uuid, 50, "test");

            WebUiBusinessException poor = rejection(helper, BUY, player, goodsPayload(AMETHYST));
            helper.assertTrue(WebUiErrorCodes.POINTS_INSUFFICIENT.equals(poor.errorCode())
                            && "120".equals(poor.params().get("price")) && "50".equals(poor.params().get("balance")),
                    "余额不足回 POINTS_INSUFFICIENT 并带价格与余额, 实为 " + poor.errorCode() + " " + poor.params());
            assertUntouched(helper, env, player, 50L, "余额不足");

            AchievementRewardService.addPoints(uuid, 450, "test");
            for (int slot = 0; slot < player.getInventory().items.size(); slot++) {
                player.getInventory().items.set(slot, new ItemStack(Items.DIRT, 64));
            }
            WebUiBusinessException full = rejection(helper, BUY, player, goodsPayload(AMETHYST));
            helper.assertTrue(WebUiErrorCodes.INVENTORY_FULL.equals(full.errorCode())
                            && AMETHYST.toString().equals(full.params().get("goodsId")),
                    "背包已满回 INVENTORY_FULL, 实为 " + full.errorCode());
            helper.assertTrue(env.rewards.points(uuid).balance() == 500L && shopRows(env.connection, uuid) == 0,
                    "背包已满: 扣点之前就拒绝, 不扣点不写流水");
            player.getInventory().clearContent();

            WebUiBusinessException locked = rejection(helper, BUY, player, goodsPayload(LANTERN));
            helper.assertTrue(WebUiErrorCodes.GOODS_REQUIREMENT_UNMET.equals(locked.errorCode())
                            && FIRST_ENTRY.toString().equals(locked.params().get("advancementId")),
                    "前置成就未获得回 GOODS_REQUIREMENT_UNMET 并指出是哪个成就, 实为 " + locked.params());
            for (ResourceLocation unknown : List.of(id("test/nope"), MISSING_TITLE)) {
                WebUiBusinessException missing = rejection(helper, BUY, player, goodsPayload(unknown));
                helper.assertTrue(WebUiErrorCodes.GOODS_UNKNOWN.equals(missing.errorCode())
                                && unknown.toString().equals(missing.params().get("goodsId")),
                        unknown + " 应回 GOODS_UNKNOWN, 实为 " + missing.errorCode());
            }
            WebUiBusinessException malformed = rejection(helper, BUY, player, stringPayload("goodsId", "Not An Id"));
            helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(malformed.errorCode())
                            && "goodsId".equals(malformed.params().get("field")),
                    "写不成资源 id 的 goodsId 回 INVALID_REQUEST, 实为 " + malformed.errorCode());
            assertUntouched(helper, env, player, 500L, "前置成就 / 未知商品");
        } finally {
            env.close();
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buyTitleGrantsInTheSameTransactionAndNotifiesOnce(GameTestHelper helper) {
        ShopEnv env = new ShopEnv(helper, List.of(codexGoods()), Set.of());
        try {
            ServerPlayer player = env.player("ach-web-title");
            UUID uuid = player.getUUID();
            AchievementRewardService.addPoints(uuid, 700, "test");
            drainSystemMessages(player);

            JsonObject bought = handle(helper, BUY, player, goodsPayload(CODEX));
            helper.assertTrue("title".equals(bought.get("type").getAsString())
                            && bought.get("balance").getAsLong() == 400L && bought.get("remaining").getAsInt() == 0,
                    "称号兑换: 扣 300 点、限购用完, 实为 " + bought);
            helper.assertTrue(env.titles.owned(uuid).contains(ORE_CODEX_TITLE), "称号已发放");
            helper.assertTrue(List.of("point_shop", CODEX.toString()).equals(titleSource(env.connection, uuid,
                            ORE_CODEX_TITLE)),
                    "称号来源记 point_shop, source_ref 为商品 id");
            helper.assertTrue(messagesWithKey(drainSystemMessages(player), TITLE_OBTAINED_KEY).size() == 1,
                    "提交后给玩家本人发一次获得称号提示");

            WebUiBusinessException again = rejection(helper, BUY, player, goodsPayload(CODEX));
            helper.assertTrue(WebUiErrorCodes.GOODS_LIMIT_REACHED.equals(again.errorCode())
                            && "TITLE_OWNED".equals(again.params().get("reason")),
                    "已拥有的称号不能再兑换, 实为 " + again.params());
            helper.assertTrue(env.rewards.points(uuid).balance() == 400L, "再次兑换不扣点");

            ServerPlayer granted = env.player("ach-web-title-admin");
            env.titles.grant(granted.getUUID(), ORE_CODEX_TITLE, TitleSource.ADMIN, "test");
            AchievementRewardService.addPoints(granted.getUUID(), 700, "test");
            WebUiBusinessException owned = rejection(helper, BUY, granted, goodsPayload(CODEX));
            helper.assertTrue(WebUiErrorCodes.GOODS_LIMIT_REACHED.equals(owned.errorCode())
                            && "TITLE_OWNED".equals(owned.params().get("reason"))
                            && env.rewards.points(granted.getUUID()).balance() == 700L,
                    "管理员发过的称号同样不能用成就点再买一次, 且不扣点");
        } finally {
            env.close();
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void failedPurchasesRollBackEverything(GameTestHelper helper) {
        ShopEnv env = new ShopEnv(helper, List.of(amethystGoods(), codexGoods()), Set.of());
        try {
            ServerPlayer player = env.player("ach-web-rollback");
            UUID uuid = player.getUUID();
            player.getInventory().clearContent();
            AchievementRewardService.addPoints(uuid, 1_000, "test");
            drainSystemMessages(player);

            // 写流水失败: 发生在扣点之后, 扣掉的点必须随事务退回, 物品也不能发。
            execute(env.connection, "CREATE TRIGGER fail_shop_ledger BEFORE INSERT ON achievement_point_ledger "
                    + "WHEN NEW.reason = 'shop_buy' BEGIN SELECT RAISE(ABORT, 'test ledger failure'); END");
            WebUiBusinessException ledgerFailed = rejection(helper, BUY, player, goodsPayload(AMETHYST));
            helper.assertTrue(WebUiErrorCodes.STORE_FAILED.equals(ledgerFailed.errorCode()),
                    "写流水失败回 STORE_FAILED, 实为 " + ledgerFailed.errorCode());
            assertUntouched(helper, env, player, 1_000L, "写流水失败");
            execute(env.connection, "DROP TRIGGER fail_shop_ledger");

            // 发称号失败: 发生在扣点与写流水之后, 两者都要回滚, 也不发提示。
            execute(env.connection, "CREATE TRIGGER fail_title_owned BEFORE INSERT ON title_owned "
                    + "BEGIN SELECT RAISE(ABORT, 'test title failure'); END");
            WebUiBusinessException titleFailed = rejection(helper, BUY, player, goodsPayload(CODEX));
            helper.assertTrue(WebUiErrorCodes.STORE_FAILED.equals(titleFailed.errorCode()),
                    "发称号失败回 STORE_FAILED, 实为 " + titleFailed.errorCode());
            assertUntouched(helper, env, player, 1_000L, "发称号失败");
            helper.assertTrue(!env.titles.owned(uuid).contains(ORE_CODEX_TITLE)
                            && messagesWithKey(drainSystemMessages(player), TITLE_OBTAINED_KEY).isEmpty(),
                    "发称号失败: 在线持有集合里没有它, 也没有提示");
            execute(env.connection, "DROP TRIGGER fail_title_owned");

            // 调用方已开着事务: 兑换自己的回滚会被外层吞掉, 必须当场拒绝。
            boolean refused = false;
            try {
                env.rewards.inTransaction(tx -> AchievementPointShop.buy(player, AMETHYST));
            } catch (IllegalStateException expected) {
                refused = true;
            }
            helper.assertTrue(refused, "调用方已开着事务时兑换必须拒绝");
            assertUntouched(helper, env, player, 1_000L, "嵌套事务");

            // 故障撤掉之后照常可买: 前面的失败没有留下任何半截状态。
            helper.assertTrue(handle(helper, BUY, player, goodsPayload(AMETHYST)).get("balance").getAsLong() == 880L,
                    "故障撤掉后兑换照常成功");
        } finally {
            env.close();
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void boughtItemsAreBoundAndCannotBeListedOnTheMarket(GameTestHelper helper) {
        ShopEnv env = new ShopEnv(helper, List.of(amethystGoods()), Set.of());
        try {
            ServerPlayer player = env.player("ach-web-market");
            player.getInventory().clearContent();
            AchievementRewardService.addPoints(player.getUUID(), 500, "test");
            handle(helper, BUY, player, goodsPayload(AMETHYST));
            int boundSlot = slotOf(player, Items.AMETHYST_SHARD);
            helper.assertTrue(boundSlot >= 0, "兑换的物品应在背包里");

            JsonObject verdict = handle(helper, "market.tradable", player, slotPayload(boundSlot));
            helper.assertTrue(!verdict.get("tradable").getAsBoolean()
                            && WebUiErrorCodes.ITEM_NOT_TRADABLE.equals(verdict.get("reasonCode").getAsString()),
                    "成就点商店兑换的物品在 market.tradable 里必须判为不可挂, 实为 " + verdict);
            JsonObject place = slotPayload(boundSlot);
            place.addProperty("count", 1);
            place.addProperty("unitPrice", 100L);
            WebUiBusinessException rejected = rejection(helper, "market.place", player, place);
            helper.assertTrue(WebUiErrorCodes.ITEM_NOT_TRADABLE.equals(rejected.errorCode())
                            && RULE_POINT_SHOP_BOUND.equals(rejected.params().get("rule")),
                    "market.place 必须以 ITEM_NOT_TRADABLE / POINT_SHOP_BOUND 拒绝, 实为 " + rejected.errorCode()
                            + " " + rejected.params());
            helper.assertTrue(player.getInventory().items.get(boundSlot).getCount() == 3, "被拒的挂单不得托管物品");

            // 对照: 同种物品没有盖章就照常可挂 —— 被拒只因为绑定, 不是因为物品本身。
            int plainSlot = player.getInventory().getFreeSlot();
            player.getInventory().items.set(plainSlot, new ItemStack(Items.AMETHYST_SHARD, 3));
            helper.assertTrue(handle(helper, "market.tradable", player, slotPayload(plainSlot)).get("tradable")
                    .getAsBoolean(), "未绑定的同种物品应可挂");

            // 装进潜影盒也不能绕过。
            ItemStack box = new ItemStack(Items.SHULKER_BOX);
            CompoundTag inner = player.getInventory().items.get(boundSlot).save(new CompoundTag());
            inner.putByte("Slot", (byte) 0);
            ListTag items = new ListTag();
            items.add(inner);
            box.getOrCreateTagElement("BlockEntityTag").put("Items", items);
            int boxSlot = player.getInventory().getFreeSlot();
            player.getInventory().items.set(boxSlot, box);
            helper.assertTrue(!handle(helper, "market.tradable", player, slotPayload(boxSlot)).get("tradable")
                    .getAsBoolean(), "装着绑定物品的潜影盒同样不可挂");
        } finally {
            env.close();
        }
        helper.succeed();
    }

    // ---- 3. achievement.claimRewards ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void claimRewardsClaimsSelectedAndAllAndMapsEveryRefusal(GameTestHelper helper) {
        ShopEnv env = new ShopEnv(helper, List.of(), Set.of());
        try {
            ServerPlayer player = env.player("ach-web-claim");
            UUID uuid = player.getUUID();
            grant(env.server, player, FIRST_ENTRY);
            grant(env.server, player, BLOCKS_100K);

            JsonObject single = handle(helper, CLAIM, player, idsPayload(FIRST_ENTRY, FIRST_ENTRY));
            helper.assertTrue(single.keySet().equals(Set.of("claimed", "points", "balance", "lifetime", "titles")),
                    "领取回执的键集不对, 实为 " + single.keySet());
            helper.assertTrue(single.getAsJsonArray("claimed").size() == 1 && single.get("points").getAsLong() == 10L
                            && single.get("balance").getAsLong() == 10L && single.getAsJsonArray("titles").isEmpty(),
                    "指定 id 领取 (重复的 id 只算一次): 1 条、10 点, 实为 " + single);
            WebUiBusinessException twice = rejection(helper, CLAIM, player, idsPayload(FIRST_ENTRY));
            helper.assertTrue(WebUiErrorCodes.REWARD_ALREADY_CLAIMED.equals(twice.errorCode())
                            && FIRST_ENTRY.toString().equals(twice.params().get("advancementId")),
                    "重复领取回 REWARD_ALREADY_CLAIMED, 实为 " + twice.errorCode());
            WebUiBusinessException unknown = rejection(helper, CLAIM, player, idsPayload(AchievementIds.id("mining/nope")));
            helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(unknown.errorCode())
                            && "advancementIds".equals(unknown.params().get("field")),
                    "没有这条奖励回 INVALID_REQUEST, 实为 " + unknown.errorCode());

            JsonObject all = handle(helper, CLAIM, player, stringPayload("advancementIds", "all"));
            helper.assertTrue(all.get("points").getAsLong() == 200L && all.get("balance").getAsLong() == 210L
                            && all.getAsJsonArray("titles").size() == 1
                            && BLOCKS_100K.toString().equals(all.getAsJsonArray("titles").get(0).getAsString()),
                    "全部领取: 地脉行者 200 点与称号, 实为 " + all);
            helper.assertTrue(env.titles.owned(uuid).contains(BLOCKS_100K), "领取发放了称号");
            WebUiBusinessException none = rejection(helper, CLAIM, player, stringPayload("advancementIds", "all"));
            helper.assertTrue(WebUiErrorCodes.REWARD_NONE_PENDING.equals(none.errorCode()),
                    "没有待领取时全部领取回 REWARD_NONE_PENDING, 实为 " + none.errorCode());

            JsonObject number = new JsonObject();
            number.addProperty("advancementIds", 3);
            JsonArray malformedIds = new JsonArray();
            malformedIds.add(FIRST_ENTRY.toString());
            malformedIds.add("Not An Id");
            JsonArray numbers = new JsonArray();
            numbers.add(3);
            for (JsonObject bad : List.of(stringPayload("advancementIds", "everything"), number, new JsonObject(),
                    arrayPayload(new JsonArray()), arrayPayload(malformedIds), arrayPayload(numbers))) {
                WebUiBusinessException invalid = rejection(helper, CLAIM, player, bad);
                helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(invalid.errorCode()),
                        "非法入参 " + bad + " 应回 INVALID_REQUEST, 实为 " + invalid.errorCode());
            }
            helper.assertTrue(env.rewards.points(uuid).equals(new PointBalance(210L, 210L)), "拒绝的请求不改动任何东西");
        } finally {
            env.close();
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void claimRewardsReportsTitleAndStoreFailuresWithoutClaiming(GameTestHelper helper) {
        ShopEnv env = new ShopEnv(helper, List.of(), Set.of(HARD_ACTIVE_100H));
        try {
            ServerPlayer player = env.player("ach-web-claim-fail");
            UUID uuid = player.getUUID();
            grant(env.server, player, FIRST_ENTRY);
            grant(env.server, player, HARD_ACTIVE_100H);

            WebUiBusinessException all = rejection(helper, CLAIM, player, stringPayload("advancementIds", "all"));
            helper.assertTrue(WebUiErrorCodes.REWARD_TITLE_UNAVAILABLE.equals(all.errorCode())
                            && HARD_ACTIVE_100H.toString().equals(all.params().get("advancementId"))
                            && HARD_ACTIVE_100H.toString().equals(all.params().get("titleId"))
                            && "all".equals(all.params().get("scope")),
                    "全部领取因称号发不出去整体回滚, 回 scope=all, 实为 " + all.params());
            WebUiBusinessException selected = rejection(helper, CLAIM, player, idsPayload(FIRST_ENTRY, HARD_ACTIVE_100H));
            helper.assertTrue(WebUiErrorCodes.REWARD_TITLE_UNAVAILABLE.equals(selected.errorCode())
                            && "selected".equals(selected.params().get("scope")),
                    "指定几条领取同样回滚, 回 scope=selected, 实为 " + selected.params());
            helper.assertTrue(env.rewards.points(uuid).equals(PointBalance.ZERO) && env.rewards.pending(uuid).size() == 2,
                    "称号失败: 一条都没有领, 初入矿区也仍待领取");

            execute(env.connection, "DROP TABLE achievement_point_ledger");
            WebUiBusinessException store = rejection(helper, CLAIM, player, idsPayload(FIRST_ENTRY));
            helper.assertTrue(WebUiErrorCodes.STORE_FAILED.equals(store.errorCode()),
                    "写库失败回 STORE_FAILED, 实为 " + store.errorCode());
            helper.assertTrue(env.rewards.points(uuid).equals(PointBalance.ZERO)
                            && !env.rewards.reward(uuid, FIRST_ENTRY).orElseThrow().isClaimed(),
                    "写库失败: 标记已领取与入账都回滚");
        } finally {
            env.close();
        }
        helper.succeed();
    }

    // ---- 4. 批量白名单 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void onlyThePointShopReadIsBatchable(GameTestHelper helper) {
        ShopEnv env = new ShopEnv(helper, List.of(amethystGoods()), Set.of());
        try {
            ServerPlayer player = env.player("ach-web-batch");
            player.getInventory().clearContent();
            grant(env.server, player, FIRST_ENTRY);
            AchievementRewardService.addPoints(player.getUUID(), 500, "test");
            JsonArray calls = new JsonArray();
            calls.add(call(POINT_SHOP, new JsonObject()));
            calls.add(call(BUY, goodsPayload(AMETHYST)));
            calls.add(call(CLAIM, stringPayload("advancementIds", "all")));
            JsonObject batch = new JsonObject();
            batch.add("calls", calls);
            JsonArray results = handle(helper, "system.batch", player, batch).getAsJsonArray("results");

            helper.assertTrue(results.get(0).getAsJsonObject().get("ok").getAsBoolean(),
                    "achievement.pointShop 是只读的, 可以进批, 实为 " + results.get(0));
            for (int index = 1; index < 3; index++) {
                JsonObject entry = results.get(index).getAsJsonObject();
                helper.assertTrue(!entry.get("ok").getAsBoolean() && WebUiErrorCodes.ACTION_NOT_BATCHABLE.equals(
                                entry.getAsJsonObject("error").get("errorCode").getAsString()),
                        "写操作不许进批 (批内没有防重放保护), 实为 " + entry);
            }
            helper.assertTrue(env.rewards.points(player.getUUID()).balance() == 500L
                            && env.rewards.pending(player.getUUID()).size() == 1 && slotOf(player, Items.AMETHYST_SHARD) < 0,
                    "被拒的批内写操作没有任何副作用");
        } finally {
            env.close();
        }
        helper.succeed();
    }

    // ---- 5. 商品加载 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pointShopLoaderSkipsInvalidGoodsAndOrdersTheCatalog(GameTestHelper helper) {
        Map<ResourceLocation, JsonElement> files = new LinkedHashMap<>();
        files.put(id("valid/b"), json("{\"type\":\"item\",\"item\":{\"id\":\"minecraft:lantern\"},\"price\":5,"
                + "\"sort\":10}"));
        files.put(id("valid/a"), json("{\"type\":\"item\",\"item\":{\"id\":\"minecraft:lantern\"},\"price\":5,"
                + "\"sort\":10}"));
        files.put(id("valid/top"), json("{\"type\":\"title\",\"title\":\"miningdim:mining/ore_codex\",\"price\":9,"
                + "\"sort\":99,\"limit_per_player\":1}"));
        files.put(id("valid/nbt"), json("{\"type\":\"item\",\"item\":{\"id\":\"minecraft:paper\",\"count\":2,"
                + "\"nbt\":\"{CustomModelData:7}\"},\"price\":1}"));
        List<String> invalid = List.of(
                "{\"type\":\"food\",\"price\":5}",
                "{\"type\":\"item\",\"item\":{\"id\":\"minecraft:lantern\"},\"price\":0}",
                "{\"type\":\"item\",\"item\":{\"id\":\"minecraft:no_such_item\"},\"price\":5}",
                "{\"type\":\"item\",\"item\":{\"id\":\"minecraft:lantern\",\"count\":65},\"price\":5}",
                "{\"type\":\"item\",\"item\":{\"id\":\"minecraft:lantern\",\"nbt\":\"{broken\"},\"price\":5}",
                "{\"type\":\"item\",\"item\":{\"id\":\"minecraft:lantern\"},\"price\":5,\"limit_per_player\":0}",
                "{\"type\":\"item\",\"item\":{\"id\":\"minecraft:lantern\"},\"title\":\"miningdim:x\",\"price\":5}",
                "{\"type\":\"title\",\"title\":\"miningdim:mining/ore_codex\",\"price\":5,\"limit_per_player\":3}",
                "{\"type\":\"title\",\"title\":\"miningdim:custom/00000000-0000-0000-0000-000000000000\",\"price\":5}",
                "{\"type\":\"item\",\"item\":{\"id\":\"minecraft:lantern\"},\"price\":5,\"requires_advancement\":\"Bad Id\"}");
        for (int index = 0; index < invalid.size(); index++) {
            files.put(id("invalid/" + index), json(invalid.get(index)));
        }
        Map<ResourceLocation, PointShopGoods> loaded = PointShopLoader.parseAll(files);
        helper.assertTrue(loaded.keySet().equals(Set.of(id("valid/a"), id("valid/b"), id("valid/top"), id("valid/nbt"))),
                "只有四条合格的商品被加载, 写坏的逐条跳过, 实为 " + loaded.keySet());
        PointShopCatalog catalog = PointShopCatalog.of(loaded.values());
        List<ResourceLocation> order = catalog.all().stream().map(PointShopGoods::id).toList();
        helper.assertTrue(order.equals(List.of(id("valid/top"), id("valid/a"), id("valid/b"), id("valid/nbt"))),
                "目录按 sort 降序、同 sort 按 id 升序, 实为 " + order);
        PointShopGoods withNbt = loaded.get(id("valid/nbt"));
        ItemStack stamped = withNbt.createStack(UUID.randomUUID());
        helper.assertTrue(stamped.getCount() == 2 && stamped.getTag() != null
                        && stamped.getTag().getInt("CustomModelData") == 7
                        && stamped.getTag().contains(PointShopGoods.GOODS_TAG),
                "物品商品的数量与 SNBT 照写, 并叠加绑定盖章, 实为 " + stamped.getTag());
        helper.assertTrue(Objects.requireNonNull(loaded.get(id("valid/top")).effectiveLimit()) == 1
                        && loaded.get(id("valid/a")).effectiveLimit() == null,
                "称号商品限购恒为 1, 物品商品不写限购即不限");
        helper.succeed();
    }

    // ---- 环境与夹具 ----

    /**
     * 一个用例的临时环境: 临时统一库上的奖励仓库、成就点商店仓库与称号门面注入全局定位器, 商品换成用例的夹具; 称号定义
     * 抄自服务端当前加载的那一份 (去掉 withoutTitles)。{@link #close()} 先让 mock 玩家下线 (称号登出钩子还要用临时门面),
     * 再恢复原来的仓库、商品与门面, 最后关库删目录。
     */
    private static final class ShopEnv {

        final MinecraftServer server;
        final Connection connection;
        final SqliteAchievementRewardRepository rewards;
        final TitleService titles;
        private final GameTestHelper helper;
        private final Path dir;
        private final AchievementRewardRepository previousRewards;
        private final DailyCounterRepository previousCounters;
        private final PointShopRepository previousShop;
        private final PointShopCatalog previousCatalog;
        private final ITitleService previousTitles;
        private final List<ServerPlayer> players = new ArrayList<>();

        ShopEnv(GameTestHelper helper, Collection<PointShopGoods> goods, Set<ResourceLocation> withoutTitles) {
            this.helper = helper;
            this.server = helper.getLevel().getServer();
            this.previousRewards = AchievementServices.rewards();
            this.previousCounters = AchievementServices.dailyCounters();
            this.previousShop = AchievementServices.pointShopRepository();
            this.previousCatalog = AchievementServices.pointShop();
            this.previousTitles = TitleServices.titleService();
            Map<ResourceLocation, TitleDefinition> loaded = new LinkedHashMap<>();
            for (TitleDefinition definition : previousTitles.definitions()) {
                if (!withoutTitles.contains(definition.id())) {
                    loaded.put(definition.id(), definition);
                }
            }
            TitleDefinitions definitions = new TitleDefinitions();
            definitions.install(loaded);
            this.dir = TempStoreDb.createTempDir();
            this.connection = TempStoreDb.openUnified(dir.resolve("achievement-shop.db"));
            this.rewards = new SqliteAchievementRewardRepository(connection);
            this.titles = new TitleService(new SqliteTitleRepository(connection), definitions, server);
            AchievementServices.bindRepositories(rewards, previousCounters);
            AchievementServices.bindPointShopRepository(new SqlitePointShopRepository(connection));
            install(goods);
            TitleServices.registerTitleService(titles);
        }

        static void install(Collection<PointShopGoods> goods) {
            AchievementServices.installPointShop(PointShopCatalog.of(goods));
        }

        ServerPlayer player(String name) {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper,
                    new GameProfile(UUID.randomUUID(), name));
            players.add(player);
            drainSystemMessages(player);
            return player;
        }

        void close() {
            try {
                for (ServerPlayer player : players) {
                    server.getPlayerList().remove(player);
                }
            } finally {
                AchievementServices.bindRepositories(previousRewards, previousCounters);
                AchievementServices.bindPointShopRepository(previousShop);
                AchievementServices.installPointShop(previousCatalog);
                TitleServices.registerTitleService(previousTitles);
                MiningDb.close(connection);
                TempStoreDb.deleteQuietly(dir);
            }
        }
    }

    /** 紫水晶碎片 x3, 120 点, 限购 2, sort 50。 */
    private static PointShopGoods amethystGoods() {
        return goods(AMETHYST, "{\"type\":\"item\",\"item\":{\"id\":\"minecraft:amethyst_shard\",\"count\":3},"
                + "\"price\":120,\"limit_per_player\":2,\"sort\":50}");
    }

    /** 灯笼, 10 点, 不限购, 要求先获得"初入矿区"。 */
    private static PointShopGoods lanternGoods() {
        return goods(LANTERN, "{\"type\":\"item\",\"item\":{\"id\":\"minecraft:lantern\"},\"price\":10,"
                + "\"requires_advancement\":\"" + FIRST_ENTRY + "\"}");
    }

    /** 同一件灯笼, 改成限购 1 (验证限购按流水计)。 */
    private static PointShopGoods limitedLanternGoods() {
        return goods(LANTERN, "{\"type\":\"item\",\"item\":{\"id\":\"minecraft:lantern\"},\"price\":10,"
                + "\"limit_per_player\":1,\"requires_advancement\":\"" + FIRST_ENTRY + "\"}");
    }

    /** 要求先获得隐藏成就"这矿不对劲"的商品。 */
    private static PointShopGoods secretGoods() {
        return goods(SECRET, "{\"type\":\"item\",\"item\":{\"id\":\"minecraft:paper\"},\"price\":1,\"sort\":-1,"
                + "\"requires_advancement\":\"" + TRAP_SPRUNG + "\"}");
    }

    /** 称号"矿物学者", 300 点, sort 100。 */
    private static PointShopGoods codexGoods() {
        return goods(CODEX, "{\"type\":\"title\",\"title\":\"" + ORE_CODEX_TITLE + "\",\"price\":300,\"sort\":100}");
    }

    /** 引用一个没有加载定义的称号。 */
    private static PointShopGoods missingTitleGoods() {
        return goods(MISSING_TITLE, "{\"type\":\"title\",\"title\":\"miningdim:test/no_such_title\",\"price\":1,"
                + "\"sort\":200}");
    }

    private static PointShopGoods goods(ResourceLocation id, String json) {
        return PointShopGoods.fromJson(id, json(json));
    }

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation(MiningConstants.MODID, path);
    }

    // ---- 断言工具 ----

    /** 前面的拒绝什么都没改: 余额、shop_buy 流水、背包里的兑换物。 */
    private static void assertUntouched(GameTestHelper helper, ShopEnv env, ServerPlayer player, long balance,
                                        String when) {
        UUID uuid = player.getUUID();
        helper.assertTrue(env.rewards.points(uuid).balance() == balance,
                when + ": 余额应仍为 " + balance + ", 实为 " + env.rewards.points(uuid));
        helper.assertTrue(shopRows(env.connection, uuid) == 0, when + ": 不得留下 shop_buy 流水");
        helper.assertTrue(slotOf(player, Items.AMETHYST_SHARD) < 0, when + ": 不得发出兑换物");
    }

    private static JsonObject rowWith(JsonArray rows, String key, String value) {
        for (JsonElement element : rows) {
            JsonObject row = element.getAsJsonObject();
            if (value.equals(row.get(key).getAsString())) {
                return row;
            }
        }
        throw new AssertionError("没有 " + key + "=" + value + " 的行: " + rows);
    }

    private static JsonObject segment(JsonArray segments, int index) {
        return segments.get(index).getAsJsonObject();
    }

    private static int slotOf(ServerPlayer player, Item item) {
        List<ItemStack> items = player.getInventory().items;
        for (int slot = 0; slot < items.size(); slot++) {
            if (items.get(slot).is(item)) {
                return slot;
            }
        }
        return -1;
    }

    private static ItemStack stackOf(ServerPlayer player, Item item) {
        int slot = slotOf(player, item);
        if (slot < 0) {
            throw new AssertionError("背包里没有 " + item);
        }
        return player.getInventory().items.get(slot);
    }

    // ---- 调用工具 ----

    private static JsonObject handle(GameTestHelper helper, String action, ServerPlayer sender, JsonObject payload) {
        return JsonParser.parseString(handler(helper, action).handle(sender, payload)).getAsJsonObject();
    }

    private static WebUiBusinessException rejection(GameTestHelper helper, String action, ServerPlayer sender,
                                                    JsonObject payload) {
        try {
            handler(helper, action).handle(sender, payload);
        } catch (WebUiBusinessException rejected) {
            return rejected;
        }
        helper.fail("该请求本应被业务拒绝, 实际却成功返回了: " + action + " " + payload);
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static WebUiAction handler(GameTestHelper helper, String action) {
        WebUiAction handler = WebUiServerDispatcher.resolve(action);
        if (handler == null) {
            helper.fail("action " + action + " 未注册进派发器");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return handler;
    }

    private static JsonObject goodsPayload(ResourceLocation goodsId) {
        return stringPayload("goodsId", goodsId.toString());
    }

    private static JsonObject stringPayload(String field, String value) {
        JsonObject payload = new JsonObject();
        payload.addProperty(field, value);
        return payload;
    }

    private static JsonObject idsPayload(ResourceLocation... ids) {
        JsonArray array = new JsonArray();
        for (ResourceLocation id : ids) {
            array.add(id.toString());
        }
        return arrayPayload(array);
    }

    private static JsonObject arrayPayload(JsonArray array) {
        JsonObject payload = new JsonObject();
        payload.add("advancementIds", array);
        return payload;
    }

    private static JsonObject slotPayload(int slot) {
        JsonObject payload = new JsonObject();
        payload.addProperty("slot", slot);
        return payload;
    }

    private static JsonObject call(String action, JsonObject payload) {
        JsonObject call = new JsonObject();
        call.addProperty("action", action);
        call.add("payload", payload);
        return call;
    }

    // ---- 进度、消息与数据库工具 ----

    /** 经原版 PlayerAdvancements 授予全部条件, 由原版在完成时发出 AdvancementEarnEvent。 */
    private static void grant(MinecraftServer server, ServerPlayer player, ResourceLocation id) {
        Advancement advancement = Objects.requireNonNull(server.getAdvancements().getAdvancement(id),
                () -> "进度未加载: " + id);
        for (String criterion : advancement.getCriteria().keySet()) {
            player.getAdvancements().award(advancement, criterion);
        }
    }

    /** 读空 mock 连接的出站队列, 按顺序返回其中的系统聊天消息 (其余封包一并丢弃)。 */
    private static List<Component> drainSystemMessages(ServerPlayer player) {
        EmbeddedChannel channel = (EmbeddedChannel) player.connection.connection.channel();
        List<Component> messages = new ArrayList<>();
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            try {
                if (outbound instanceof ClientboundSystemChatPacket packet) {
                    messages.add(packet.content());
                }
            } finally {
                ReferenceCountUtil.release(outbound);
            }
        }
        return messages;
    }

    private static List<Component> messagesWithKey(List<Component> messages, String key) {
        return messages.stream().filter(message -> message.getContents() instanceof TranslatableContents translatable
                && translatable.getKey().equals(key)).toList();
    }

    /** 一行成就点流水。 */
    private record LedgerRow(long delta, String reason, String ref) {
    }

    private static List<LedgerRow> ledger(Connection connection, UUID player) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT delta, reason, ref FROM achievement_point_ledger WHERE player_uuid=? ORDER BY id")) {
            statement.setString(1, player.toString());
            List<LedgerRow> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new LedgerRow(result.getLong(1), result.getString(2), result.getString(3)));
                }
            }
            return rows;
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static int shopRows(Connection connection, UUID player) {
        return (int) ledger(connection, player).stream().filter(row -> "shop_buy".equals(row.reason())).count();
    }

    /** title_owned 里某个称号的 [source, source_ref]; 没有这一行为空表。 */
    private static List<String> titleSource(Connection connection, UUID player, ResourceLocation titleId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT source, source_ref FROM title_owned WHERE player_uuid=? AND title_id=?")) {
            statement.setString(1, player.toString());
            statement.setString(2, titleId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? List.of(result.getString(1), result.getString(2)) : List.of();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void execute(Connection connection, String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

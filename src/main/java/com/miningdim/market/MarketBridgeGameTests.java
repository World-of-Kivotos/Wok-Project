package com.miningdim.market;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.economy.AbuseGuard;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyService;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.EconomyLedger;
import com.miningdim.economy.SqliteEconomyLedger;
import com.miningdim.economy.IEconomyService;
import com.miningdim.economy.PlayerAbuseState;
import com.miningdim.job.munitions.ModMunitionsItems;
import com.miningdim.job.munitions.gunsmith.GunsmithPartItem;
import com.miningdim.job.munitions.gunsmith.GunsmithPartQuality;
import com.miningdim.job.munitions.gunsmith.GunsmithPlatform;
import com.miningdim.job.munitions.gunsmith.GunsmithPressPart;
import com.miningdim.market.store.MarketDaoSqlite;
import com.miningdim.market.store.MarketDb;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 让游戏内真桥脱离前端 Mock 的 4 个服务端 action 的 GameTest (player.inventory / player.wallet / market.baseValue /
 * market.categories)。与 {@link MarketGameTests} 同 batch ("market") + 同款内存 SQLite + 经济门面 swap/restore 范式。
 *
 * 直接调 {@link com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction#handle} 拿 resultJson 再 Gson 解析断言
 * (服务端纯逻辑校验, 不经网络层), 与 {@link MarketAdminGameTests} 调 handle 同纪律。强断言 (删被测核心逻辑必挂):
 *  1. player.wallet 回发送者真实 CREDIT/AZURE 账本余额。
 *  2. player.inventory 回发送者非空主背包槽位 (slot/itemId/count), 改名物品带 displayName, 普通物品不带。
 *  3. market.baseValue 三层解析 + source 标注: preset (钻石 500/preset) / none (鹅卵石 null/none) / override (覆盖后 override)。
 *  4. market.categories 只出分支骨架 (leafCount 自洽, 零叶子); market.categoryItems 按 categoryId 分页取叶子
 *     (排序 / 分页钳制 / ores 并集语义 / 未知分类拒绝 / 32767 字符体积上限, 见本文件 categoryItems 系列测试)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MarketBridgeGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "market";

    // ============================================================
    // 1. player.wallet: 回发送者真实 CREDIT/AZURE 账本余额
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void playerWalletReadsLedgerBalances(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prev = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            // CREDIT 经门面 grant, AZURE 直写账本 (青辉石无 faucet 入口, 测试直接铺账本)。
            EconomyServices.economyService().grant(player, Currency.CREDIT, 7_531L);
            ledger.credit(player.getUUID(), Currency.AZURE, 42L);

            JsonObject result = handle(PlayerWebUiActions.WALLET, player, new JsonObject());
            helper.assertTrue(result.get("credit").getAsLong() == 7_531L,
                    "player.wallet returns the exact CREDIT ledger balance (7531), got " + result.get("credit"));
            helper.assertTrue(result.get("azure").getAsLong() == 42L,
                    "player.wallet returns the exact AZURE ledger balance (42), got " + result.get("azure"));

            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 2. player.inventory: 非空主背包槽位 + 改名物品带 displayName
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void playerInventoryListsNonEmptySlotsWithNbtSummary(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();
        // 槽 0: 普通钻石 (无自定义名 -> 无 displayName); 槽 3: 改名铁锭 (自定义名 -> 带 displayName)。
        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
        ItemStack renamed = new ItemStack(Items.IRON_INGOT, 2);
        renamed.setHoverName(net.minecraft.network.chat.Component.literal("传家宝铁锭"));
        player.getInventory().setItem(3, renamed);

        JsonObject result = handle(PlayerWebUiActions.INVENTORY, player, new JsonObject());
        JsonArray items = result.getAsJsonArray("items");
        // 恰两个非空槽 (空槽不输出)。
        helper.assertTrue(items.size() == 2,
                "player.inventory lists exactly the 2 non-empty main slots, got " + items.size());

        JsonObject diamond = findBySlot(items, 0);
        helper.assertTrue(diamond != null
                        && "minecraft:diamond".equals(diamond.get("itemId").getAsString())
                        && diamond.get("count").getAsInt() == 5,
                "slot 0 reports the diamond stack (itemId + count=5)");
        helper.assertTrue(diamond != null && !diamond.has("displayName"),
                "a plain (un-renamed) item omits displayName (client resolves name via i18n)");
        // 翻译键必须随行下发: 前端只有 itemId 推不出它 (物品 item.<ns>.<path> / 方块 block.<ns>.<path>),
        // 没有它 client.i18n 就无从解出中文名。删 descriptionId 字段本断言必挂。
        helper.assertTrue(diamond != null && diamond.has("descriptionId")
                        && "item.minecraft.diamond".equals(diamond.get("descriptionId").getAsString()),
                "slot 0 carries the translation key for client-side i18n, got "
                        + (diamond == null || !diamond.has("descriptionId")
                            ? "<missing>" : diamond.get("descriptionId").getAsString()));

        JsonObject iron = findBySlot(items, 3);
        helper.assertTrue(iron != null
                        && "minecraft:iron_ingot".equals(iron.get("itemId").getAsString())
                        && iron.get("count").getAsInt() == 2,
                "slot 3 reports the iron ingot stack (itemId + count=2)");
        helper.assertTrue(iron != null && iron.has("displayName")
                        && "传家宝铁锭".equals(iron.get("displayName").getAsString()),
                "a custom-named item carries its NBT-derived displayName as the nbt summary");

        // 普通物品不带变体字段: 绝大多数物品一个 id 一张贴图一个名字, 多发两个字段只是给前端制造分支。
        helper.assertTrue(diamond != null && !diamond.has("customModelData") && !diamond.has("nameParts"),
                "a plain item omits both variant fields (its Item-level id and name already say everything)");

        helper.succeed();
    }

    // ============================================================
    // 2b. player.inventory: NBT 变体件 (枪匠零件) 必须带 customModelData 与 nameParts
    // ============================================================

    /**
     * 这一条守的是一个真实踩过的坑: 枪匠零件的 195 种变体<b>全部</b>注册在同一个 miningdim:gunsmith_part
     * 之下, 平台/部位/品质由 NBT 决定。只发 itemId + descriptionId 的话, 前端拿到的 195 行是同名同图标的
     * —— 名字全是 Item 级翻译键解出来的"枪匠零件", 图标全是模型默认的那一张。
     *
     * <p>两个断言各守一半:
     * customModelData 守图标 (前端按它查模型 overrides 生成的映射表取变体贴图);
     * nameParts 守名字, 且必须是<b>多项</b>的键序列 —— {@code GunsmithPartItem#getName} 拼的是
     * "平台键 + 部位键 + 字面空格 + 品质键", 塌成一项就说明 Component 拍平丢了东西。
     *
     * <p>名字为什么不能在服务端解成字符串: 专用服务端不加载 mod 的 lang, 解出来是原始键。见 WebUiItemJson。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void playerInventoryCarriesNbtVariantFields(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.getInventory().clearContent();

        ItemStack part = GunsmithPartItem.createStack(ModMunitionsItems.GUNSMITH_PART.get(),
                GunsmithPlatform.AR, GunsmithPressPart.CORE, GunsmithPartQuality.LEGENDARY);
        player.getInventory().setItem(0, part);

        JsonObject result = handle(PlayerWebUiActions.INVENTORY, player, new JsonObject());
        JsonObject row = findBySlot(result.getAsJsonArray("items"), 0);
        helper.assertTrue(row != null, "the gunsmith part occupies slot 0");

        // 图标: 必须与物品自己写进 NBT 的那个编号逐位相等, 差一位就取到别的品质的贴图。
        int expected = part.getOrCreateTag().getInt("CustomModelData");
        helper.assertTrue(row != null && row.has("customModelData")
                        && row.get("customModelData").getAsInt() == expected,
                "the variant carries its exact CustomModelData (" + expected + "), got "
                        + (row == null || !row.has("customModelData")
                            ? "<missing>" : row.get("customModelData").getAsString()));

        // 名字: 多项键序列, 且必须与 Item 级默认名不同 —— 后者正是这个 bug 的症状。
        helper.assertTrue(row != null && row.has("nameParts"), "the variant carries nameParts");
        JsonArray nameParts = row == null ? new JsonArray() : row.getAsJsonArray("nameParts");
        helper.assertTrue(nameParts.size() >= 2,
                "nameParts keeps every segment of the composed name (>=2), got " + nameParts.size());
        boolean hasQualityKey = false;
        for (JsonElement element : nameParts) {
            JsonObject segment = element.getAsJsonObject();
            helper.assertTrue(segment.has("k") || segment.has("t"),
                    "every nameParts segment is either a translation key (k) or a literal (t)");
            if (segment.has("k")
                    && GunsmithPartQuality.LEGENDARY.labelKey().equals(segment.get("k").getAsString())) {
                hasQualityKey = true;
            }
        }
        helper.assertTrue(hasQualityKey,
                "nameParts contains the quality translation key (" + GunsmithPartQuality.LEGENDARY.labelKey()
                        + ") so the client can tell one quality tier from another");
        helper.assertTrue(!"item.miningdim.gunsmith_part".equals(row == null ? "" : row.get("descriptionId").getAsString())
                        || nameParts.size() >= 2,
                "the Item-level descriptionId alone cannot distinguish variants; nameParts is what does");

        helper.succeed();
    }

    // ============================================================
    // 3. market.baseValue: 三层解析 (preset / none / override) + source 标注
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void marketBaseValueResolvesLayersWithSource(GameTestHelper helper) {
        MarketDaoSqlite dao = MarketDb.openInMemory();
        MarketEngine prevMarket = swapMarket(new MarketEngine(dao, helper.getLevel().getServer()));
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            // preset: 钻石 -> 代码预设 500, source=preset。
            JsonObject diamond = handle(MarketActions.BASE_VALUE, player, payloadItem("minecraft:diamond"));
            helper.assertTrue(diamond.get("v0").getAsLong() == 500L
                            && "preset".equals(diamond.get("source").getAsString()),
                    "market.baseValue resolves diamond to preset V0=500 with source=preset");

            // none: 鹅卵石无锚 -> v0=null, source=none。
            JsonObject cobble = handle(MarketActions.BASE_VALUE, player, payloadItem("minecraft:cobblestone"));
            helper.assertTrue(cobble.get("v0").isJsonNull()
                            && "none".equals(cobble.get("source").getAsString()),
                    "market.baseValue resolves an unanchored item (cobblestone) to v0=null source=none");

            // override: admin 写覆盖后 -> v0=覆盖值, source=override (覆盖 > 预设)。
            MarketServices.marketEngine().setBaseValueOverride("minecraft:diamond", 999L, UUID.randomUUID());
            JsonObject overridden = handle(MarketActions.BASE_VALUE, player, payloadItem("minecraft:diamond"));
            helper.assertTrue(overridden.get("v0").getAsLong() == 999L
                            && "override".equals(overridden.get("source").getAsString()),
                    "after an admin override market.baseValue returns v0=999 source=override (override beats preset)");

            helper.succeed();
        } finally {
            restoreMarket(prevMarket);
            MarketDb.close(dao);
        }
    }

    // ============================================================
    // 4. market.categories: 只出分支骨架 (F041) —— 零叶子, ores 挂三个固定子分支
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void marketCategoriesBuildsBranchSkeletonWithoutLeaves(GameTestHelper helper) {
        // buildSkeleton() 枚举真实物品注册表 (GameTest 在已注册全物品的服务端跑), 不依赖门面/账本。
        JsonArray tree = MarketCategoryTree.buildSkeleton();
        helper.assertTrue(tree.size() > 0, "category tree has at least one top-level category");

        // 顶层"矿物与材料" (ores) 存在且是分支 (无 itemId, 有 children)。
        JsonObject ores = findById(tree, "ores");
        helper.assertTrue(ores != null, "the ores top-level category is present");
        helper.assertTrue(ores != null && !ores.has("itemId") && ores.has("children"),
                "a branch node carries no itemId and has children");

        // ores 下含固定的三个子分支: 原矿 (ore) / 锭 (ingot) / 宝石 (gem)。
        JsonArray oresChildren = ores.getAsJsonArray("children");
        helper.assertTrue(findById(oresChildren, "ore") != null, "ores 下含 ore 子分支");
        helper.assertTrue(findById(oresChildren, "ingot") != null, "ores 下含 ingot 子分支");
        helper.assertTrue(findById(oresChildren, "gem") != null, "ores 下含 gem 子分支");

        // 递归遍历整棵骨架: 零个节点带 itemId (删掉"骨架只出分支"这条实施, 任何一处混入叶子本断言必挂)。
        assertNoLeaves(helper, tree);

        helper.succeed();
    }

    // ============================================================
    // 4b. market.categories 骨架体积必须严格小于下行 32767 字符硬闸 (F041 核心判据)
    // ============================================================

    /**
     * 前提校验先证明这不是小注册表下的永真断言: 若骨架仍像旧实现那样把全部叶子内嵌其中, 按每条叶子 JSON
     * 最短可能形态 (40 字符) 估算, 当前注册表规模下必然超限 —— 也就是说旧实现在本测试环境里本来就会被
     * 这条体积断言当场抓到, 而不是靠"真服才炸"才发现。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void marketCategoriesSkeletonFitsUnderStringLimit(GameTestHelper helper) {
        int itemCount = ForgeRegistries.ITEMS.getKeys().size();
        helper.assertTrue((long) itemCount * 40L > FriendlyByteBuf.MAX_STRING_LENGTH,
                "前提校验: 注册表含 " + itemCount + " 个物品, 按最短叶子 JSON (40 字符/条) 估算必然超过 "
                        + FriendlyByteBuf.MAX_STRING_LENGTH + " 字符上限 (证明骨架不能再内嵌全部叶子)");

        String skeletonJson = new Gson().toJson(MarketCategoryTree.buildSkeleton());
        helper.assertTrue(skeletonJson.length() < FriendlyByteBuf.MAX_STRING_LENGTH,
                "market.categories 骨架体积必须严格小于下行硬闸 " + FriendlyByteBuf.MAX_STRING_LENGTH
                        + " 字符, 实得 " + skeletonJson.length());

        helper.succeed();
    }

    // ============================================================
    // 4c. market.categoryItems: 排序 + 分页 + pageSize 钳制
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void marketCategoryItemsPaginatesIngotLeavesDeterministically(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        // 先取一页够大的 (100, 硬上限) 拿 ingot 分类的完整顺序与真实 total, 验证字典序 (copper 排在 iron 之前)。
        JsonObject full = handle(MarketActions.CATEGORY_ITEMS, player, categoryPayload("ingot", 0, 100));
        JsonArray fullItems = full.getAsJsonArray("items");
        int total = full.get("total").getAsInt();
        helper.assertTrue(total > 2,
                "ingot 分类真实叶子数必须 > 2 (否则后续分页/去重断言测不出东西), 实得 total=" + total);
        int copperIdx = indexOfItemId(fullItems, "minecraft:copper_ingot");
        int ironIdx = indexOfItemId(fullItems, "minecraft:iron_ingot");
        helper.assertTrue(copperIdx >= 0 && ironIdx >= 0 && copperIdx < ironIdx,
                "items 按 itemId 字典序排列 (copper_ingot 排在 iron_ingot 之前), 实得 copperIdx=" + copperIdx
                        + " ironIdx=" + ironIdx);

        // page=0 pageSize=2 恰回 2 条, 且第一页必含字典序最小的 copper_ingot; total 与 offset/limit 无关。
        JsonObject page0 = handle(MarketActions.CATEGORY_ITEMS, player, categoryPayload("ingot", 0, 2));
        JsonArray items0 = page0.getAsJsonArray("items");
        helper.assertTrue(items0.size() == 2,
                "page=0 pageSize=2 必须恰回 2 条, 实得 " + items0.size());
        helper.assertTrue(findByItemId(items0, "minecraft:copper_ingot") != null,
                "page=0 必须含字典序最小的 copper_ingot");
        helper.assertTrue(page0.get("total").getAsInt() == total,
                "total 必须恒为该分类真实总数, 与 offset/limit 无关, 实得 " + page0.get("total"));

        // page=1 与 page=0 的 itemId 集合互斥 (删掉 offset 计算, page=1 会与 page=0 撞车, 本断言必挂)。
        JsonObject page1 = handle(MarketActions.CATEGORY_ITEMS, player, categoryPayload("ingot", 1, 2));
        JsonArray items1 = page1.getAsJsonArray("items");
        Set<String> ids0 = itemIdsOf(items0);
        Set<String> ids1 = itemIdsOf(items1);
        helper.assertTrue(Collections.disjoint(ids0, ids1),
                "page=1 与 page=0 的 itemId 集合不相交, 实得 page0=" + ids0 + " page1=" + ids1);

        // pageSize 钳制: 传 1000 必须被钳到 100, 回执条数不得超过钳制后的上限 (删掉 clamp 必挂)。
        JsonObject clamped = handle(MarketActions.CATEGORY_ITEMS, player, categoryPayload("ingot", 0, 1000));
        helper.assertTrue(clamped.get("pageSize").getAsInt() == 100,
                "pageSize=1000 必须被钳到 100, 实得 " + clamped.get("pageSize"));
        helper.assertTrue(clamped.getAsJsonArray("items").size() <= 100,
                "回执 items 实际条数不得超过钳制后的 100, 实得 " + clamped.getAsJsonArray("items").size());

        helper.succeed();
    }

    // ============================================================
    // 4d. market.categoryItems: ores 的并集语义 + 与骨架 leafCount 同源自洽
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void marketCategoryItemsOresUnionMatchesSkeletonLeafCount(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        int oreTotal = handle(MarketActions.CATEGORY_ITEMS, player, categoryPayload("ore", 0, 1))
                .get("total").getAsInt();
        int ingotTotal = handle(MarketActions.CATEGORY_ITEMS, player, categoryPayload("ingot", 0, 1))
                .get("total").getAsInt();
        int gemTotal = handle(MarketActions.CATEGORY_ITEMS, player, categoryPayload("gem", 0, 1))
                .get("total").getAsInt();
        int oresTotal = handle(MarketActions.CATEGORY_ITEMS, player, categoryPayload("ores", 0, 1))
                .get("total").getAsInt();

        // 并集语义 (两条独立路径同源): "ores" 单查的 total 必须等于三个子分类各自单查 total 之和。
        helper.assertTrue(oresTotal == oreTotal + ingotTotal + gemTotal,
                "ores 分类 total 必须等于 ore+ingot+gem 三次单查 total 之和, 实得 ores=" + oresTotal
                        + " 三者之和=" + (oreTotal + ingotTotal + gemTotal));

        // leafCount 自洽: 骨架里 ores.leafCount == 其三个子分支 leafCount 之和 == 上面 ores 分类查询回的 total。
        JsonArray tree = MarketCategoryTree.buildSkeleton();
        JsonObject ores = findById(tree, "ores");
        helper.assertTrue(ores != null, "ores 顶层分支必须存在于骨架里");
        int oresLeafCount = ores.get("leafCount").getAsInt();
        int childLeafCountSum = 0;
        for (JsonElement child : ores.getAsJsonArray("children")) {
            childLeafCountSum += child.getAsJsonObject().get("leafCount").getAsInt();
        }
        helper.assertTrue(childLeafCountSum == oresLeafCount,
                "ores.leafCount 必须等于其子分支 leafCount 之和, 实得子分支之和=" + childLeafCountSum
                        + " ores.leafCount=" + oresLeafCount);
        helper.assertTrue(oresLeafCount == oresTotal,
                "骨架 ores.leafCount 必须与 market.categoryItems(ores) 的 total 同源一致, 实得 leafCount="
                        + oresLeafCount + " total=" + oresTotal);

        helper.succeed();
    }

    // ============================================================
    // 4e. market.categoryItems: 未知 categoryId 必须抛 INVALID_REQUEST 并指名字段
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void marketCategoryItemsRejectsUnknownCategory(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        WebUiBusinessException rejected = rejection(helper, MarketActions.CATEGORY_ITEMS, player,
                categoryPayload("no_such_bucket", 0, 20));

        helper.assertTrue(WebUiErrorCodes.INVALID_REQUEST.equals(rejected.errorCode()),
                "未知 categoryId 必须回 INVALID_REQUEST, 实得 " + rejected.errorCode());
        helper.assertTrue("categoryId".equals(rejected.params().get("field")),
                "params.field 必须指名 categoryId, 实得 " + rejected.params());

        helper.succeed();
    }

    // ============================================================
    // bucketOf 纯函数 (不依赖注册表): 归类启发式与前端 categoryIdsOf 对齐
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void categoryBucketingMatchesFrontendHeuristic(GameTestHelper helper) {
        helper.assertTrue("ingot".equals(MarketCategoryTree.bucketOf("minecraft:gold_ingot").subId()),
                "gold_ingot buckets into ores/ingot");
        helper.assertTrue("ingot".equals(MarketCategoryTree.bucketOf("minecraft:netherite_scrap").subId()),
                "netherite_scrap buckets into ores/ingot (explicit, not an _ingot suffix)");
        helper.assertTrue("ore".equals(MarketCategoryTree.bucketOf("minecraft:raw_copper").subId()),
                "raw_copper buckets into ores/ore");
        helper.assertTrue("ore".equals(MarketCategoryTree.bucketOf("minecraft:iron_ore").subId()),
                "iron_ore buckets into ores/ore");
        helper.assertTrue("gem".equals(MarketCategoryTree.bucketOf("minecraft:emerald").subId()),
                "emerald buckets into ores/gem");
        helper.assertTrue("weapons".equals(MarketCategoryTree.bucketOf("tacz:modern_kinetic_gun").topId()),
                "a gun-id item buckets into weapons (full-id contains match, incl mod prefix)");
        helper.assertTrue("ammo".equals(MarketCategoryTree.bucketOf("tacz:ammo_9mm").topId()),
                "an ammo-id item buckets into ammo");
        helper.assertTrue("gear".equals(MarketCategoryTree.bucketOf("minecraft:diamond_chestplate").topId()),
                "a chestplate buckets into gear");
        helper.assertTrue("food".equals(MarketCategoryTree.bucketOf("minecraft:bread").topId()),
                "bread buckets into food");
        // 无命中 -> other (单归属兜底)。
        helper.assertTrue("other".equals(MarketCategoryTree.bucketOf("minecraft:cobblestone").topId()),
                "an item matching no rule falls into other");

        helper.succeed();
    }

    // ============================================================
    // 工具: 调 action handle 得 JsonObject; 找节点/叶子; 门面 swap/restore
    // ============================================================

    private static JsonObject handle(com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction action,
                                     ServerPlayer sender, JsonObject payload) {
        return JsonParser.parseString(action.handle(sender, payload)).getAsJsonObject();
    }

    private static JsonObject payloadItem(String itemId) {
        JsonObject p = new JsonObject();
        p.addProperty("itemId", itemId);
        return p;
    }

    private static JsonObject categoryPayload(String categoryId, int page, int pageSize) {
        JsonObject p = new JsonObject();
        p.addProperty("categoryId", categoryId);
        p.addProperty("page", page);
        p.addProperty("pageSize", pageSize);
        return p;
    }

    private static Set<String> itemIdsOf(JsonArray items) {
        Set<String> ids = new HashSet<>();
        for (JsonElement e : items) {
            ids.add(e.getAsJsonObject().get("itemId").getAsString());
        }
        return ids;
    }

    /** 递归断言骨架里零个节点带 itemId (骨架只出分支, 叶子改由 market.categoryItems 按需取)。 */
    private static void assertNoLeaves(GameTestHelper helper, JsonArray nodes) {
        for (JsonElement e : nodes) {
            JsonObject node = e.getAsJsonObject();
            helper.assertTrue(!node.has("itemId"),
                    "骨架节点 " + node.get("id").getAsString() + " 不应带 itemId (骨架只出分支, 无叶子)");
            assertNoLeaves(helper, node.getAsJsonArray("children"));
        }
    }

    /** 调 action 并要求它抛业务拒绝; 没抛就地判失败 (返回值必非 null, 调用方可直接取字段)。 */
    private static WebUiBusinessException rejection(GameTestHelper helper,
                                                     com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction action,
                                                     ServerPlayer sender, JsonObject payload) {
        try {
            action.handle(sender, payload);
        } catch (WebUiBusinessException rejected) {
            return rejected;
        }
        helper.fail("该请求本应被业务拒绝, 实际却成功返回了: " + payload);
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static JsonObject findBySlot(JsonArray items, int slot) {
        for (JsonElement e : items) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("slot").getAsInt() == slot) {
                return o;
            }
        }
        return null;
    }

    private static JsonObject findById(JsonArray nodes, String id) {
        for (JsonElement e : nodes) {
            JsonObject o = e.getAsJsonObject();
            if (id.equals(o.get("id").getAsString())) {
                return o;
            }
        }
        return null;
    }

    private static JsonObject findByItemId(JsonArray nodes, String itemId) {
        for (JsonElement e : nodes) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("itemId") && itemId.equals(o.get("itemId").getAsString())) {
                return o;
            }
        }
        return null;
    }

    private static int indexOfItemId(JsonArray nodes, String itemId) {
        for (int i = 0; i < nodes.size(); i++) {
            JsonObject o = nodes.get(i).getAsJsonObject();
            if (o.has("itemId") && itemId.equals(o.get("itemId").getAsString())) {
                return i;
            }
        }
        return -1;
    }

    private static IEconomyService swapEconomy(IEconomyService fake) {
        IEconomyService prev = EconomyServices.isRegistered() ? EconomyServices.economyService() : null;
        EconomyServices.registerEconomyService(fake);
        return prev;
    }

    private static void restoreEconomy(IEconomyService prev) {
        if (prev != null) {
            EconomyServices.registerEconomyService(prev);
        } else {
            EconomyServices.reset();
        }
    }

    private static MarketEngine swapMarket(MarketEngine fake) {
        MarketEngine prev = MarketServices.isRegistered() ? MarketServices.marketEngine() : null;
        MarketServices.registerMarketEngine(fake);
        return prev;
    }

    private static void restoreMarket(MarketEngine prev) {
        if (prev != null) {
            MarketServices.registerMarketEngine(prev);
        } else {
            MarketServices.reset();
        }
    }

    private static Function<UUID, PlayerAbuseState> newStateResolver() {
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        return id -> states.computeIfAbsent(id, k -> new PlayerAbuseState());
    }
}

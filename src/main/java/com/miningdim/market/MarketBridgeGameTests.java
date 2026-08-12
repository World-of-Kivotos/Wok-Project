package com.miningdim.market;

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
import com.miningdim.market.store.MarketDaoSqlite;
import com.miningdim.market.store.MarketDb;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.Map;
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
 *  4. market.categories 树构建: 顶层含矿物与材料 + 钻石下钻到 ores/gem 叶子 (带 itemId), 分支节点无 itemId, 叶子有序。
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
    // 4. market.categories: 树构建 + 下钻到带 itemId 的叶子 + 分支无 itemId
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void marketCategoriesBuildsTreeDrillingToLeaves(GameTestHelper helper) {
        // build() 枚举真实物品注册表 (GameTest 在已注册全物品的服务端跑), 不依赖门面/账本。
        JsonArray tree = MarketCategoryTree.build();
        helper.assertTrue(tree.size() > 0, "category tree has at least one top-level category");

        // 顶层"矿物与材料" (ores) 存在且是分支 (无 itemId, 有 children)。
        JsonObject ores = findById(tree, "ores");
        helper.assertTrue(ores != null, "the ores top-level category is present");
        helper.assertTrue(ores != null && !ores.has("itemId") && ores.has("children"),
                "a branch node carries no itemId and has children");

        // ores 下有"宝石" (gem) 子分支, 其下钻到钻石叶子 (带 itemId=minecraft:diamond, label=翻译键)。
        JsonObject gem = findById(ores.getAsJsonArray("children"), "gem");
        helper.assertTrue(gem != null && gem.has("children"), "the gem sub-branch exists under ores");
        JsonObject diamondLeaf = findByItemId(gem.getAsJsonArray("children"), "minecraft:diamond");
        helper.assertTrue(diamondLeaf != null,
                "diamond drills down to a leaf under ores/gem (registry-driven bucketing)");
        helper.assertTrue(diamondLeaf != null
                        && diamondLeaf.get("label").getAsString().equals("item.minecraft.diamond"),
                "a leaf's label is the item translation key (resolved client-side via i18n)");

        // 锭子分支含铁锭叶子 (按 _ingot 后缀归入 ores/ingot)。
        JsonObject ingot = findById(ores.getAsJsonArray("children"), "ingot");
        helper.assertTrue(ingot != null
                        && findByItemId(ingot.getAsJsonArray("children"), "minecraft:iron_ingot") != null,
                "iron ingot drills down to ores/ingot by its _ingot suffix");

        // 叶子按 item_id 字典序 (确定性): copper_ingot 应排在 iron_ingot 之前 (c < i)。
        JsonArray ingotLeaves = ingot.getAsJsonArray("children");
        int copperIdx = indexOfItemId(ingotLeaves, "minecraft:copper_ingot");
        int ironIdx = indexOfItemId(ingotLeaves, "minecraft:iron_ingot");
        helper.assertTrue(copperIdx >= 0 && ironIdx >= 0 && copperIdx < ironIdx,
                "leaves are sorted by item_id lexicographically (copper before iron), deterministic tree");

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

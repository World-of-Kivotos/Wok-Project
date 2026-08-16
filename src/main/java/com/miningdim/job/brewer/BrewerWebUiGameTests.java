package com.miningdim.job.brewer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.job.JobId;
import com.miningdim.job.brewer.station.BrewRecipes;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * W3 职业一的 job.brewer.state GameTest。
 *
 * 三条维度纪律各有断言:
 *  1. 月光词条是 <b>per-player 一组</b>, 只能挂在回执顶层 —— 挂进每行酒会在面板上渲染出"伏特加带着月光词条"
 *     这种维度错误, 故每行酒的字段集必须恰好是四个键;
 *  2. 陈酿没有 per-配方天数, 九种酒共用同一套现实挂钟, 回执只发 millisPerVintageYear;
 *  3. 酒名/词条名一律发翻译键 (专用服务端不加载 lang), 且键必须在两份 lang 里都存在, 否则面板显示原始键。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class BrewerWebUiGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "webui_w3";

    private static final String STATE_ACTION = "job.brewer.state";

    /** 单行酒的契约字段全集: 多一个 moonshineAffixes 就是把 per-player 的词条挂到了酒上。 */
    private static final Set<String> BREW_KEYS = Set.of("wineId", "itemId", "descriptionId", "permanentStacks");

    /** 单条配方的契约字段全集: 不发 recipeId (与 wineId 同源, 发两个 id 迟早分叉), 不发 agingDays。 */
    private static final Set<String> RECIPE_KEYS = Set.of("wineId", "inputs");

    // ============================================================
    // 1. 形状: 九种酒 + 九条配方 + 挂钟
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void brewerStateReportsNineBrewsRecipesAndTheVintageClock(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        JsonObject state = handle(helper, player);

        helper.assertTrue(state.get("level").getAsInt() == 1, "新号酿酒师 1 级");
        helper.assertTrue(state.get("maxLayersPerType").getAsInt() == BrewerConstants.MAX_LAYERS_PER_TYPE
                        && state.get("maxLayersPerType").getAsInt() == 5,
                "每种酒的永久层数上限恒 5 (顶层发一次, 不逐行重复)");
        helper.assertTrue(state.get("millisPerVintageYear").getAsLong()
                        == BrewerConstants.MILLIS_PER_VINTAGE_YEAR
                        && state.get("millisPerVintageYear").getAsLong() == 86_400_000L,
                "陈酿速率 = 1 现实天 1 年份 (九种酒共用同一套挂钟, 没有 per-配方陈酿天数)");

        JsonArray brews = state.getAsJsonArray("brews");
        JsonArray recipes = state.getAsJsonArray("recipes");
        helper.assertTrue(brews.size() == WineType.values().length && brews.size() == 9,
                "brews 恒 9 条, 实得 " + brews.size());
        helper.assertTrue(recipes.size() == WineType.values().length,
                "recipes 恒 9 条, 实得 " + recipes.size());

        for (int i = 0; i < WineType.values().length; i++) {
            WineType type = WineType.values()[i];
            JsonObject brew = brews.get(i).getAsJsonObject();
            helper.assertTrue(type.id().equals(brew.get("wineId").getAsString()),
                    "brews 第 " + i + " 条必须是 " + type.id() + ", 实得 " + brew.get("wineId").getAsString());
            helper.assertTrue(BREW_KEYS.equals(brew.keySet()),
                    type.id() + " 行的字段集必须恰好是 " + BREW_KEYS + " (月光词条不许挂在酒上), 实得 "
                            + brew.keySet());
            helper.assertTrue(("miningdim:wine_" + type.id()).equals(brew.get("itemId").getAsString()),
                    type.id() + " 的物品 id 是 miningdim:wine_" + type.id()
                            + ", 实得 " + brew.get("itemId").getAsString());
            helper.assertTrue(BrewerItems.itemFor(type).getDescriptionId()
                            .equals(brew.get("descriptionId").getAsString()),
                    type.id() + " 发翻译键而不是中文名");
            helper.assertTrue(brew.get("permanentStacks").getAsInt() == 0,
                    "新号每种酒都是 0 层, 实得 " + brew.get("permanentStacks").getAsInt());

            JsonObject recipe = recipes.get(i).getAsJsonObject();
            helper.assertTrue(type.id().equals(recipe.get("wineId").getAsString())
                            && RECIPE_KEYS.equals(recipe.keySet()),
                    "recipes 第 " + i + " 条必须是 " + type.id() + " 且只带 wineId/inputs, 实得 "
                            + recipe.keySet());
            List<BrewRecipes.Ingredient> expected = BrewRecipes.recipeFor(type);
            JsonArray inputs = recipe.getAsJsonArray("inputs");
            helper.assertTrue(inputs.size() == expected.size(),
                    type.id() + " 的原料条数应为 " + expected.size() + ", 实得 " + inputs.size());
            for (int j = 0; j < expected.size(); j++) {
                JsonObject input = inputs.get(j).getAsJsonObject();
                BrewRecipes.Ingredient ingredient = expected.get(j);
                helper.assertTrue(ForgeRegistries.ITEMS.getKey(ingredient.item()).toString()
                                .equals(input.get("itemId").getAsString())
                                && input.get("count").getAsInt() == ingredient.count(),
                        type.id() + " 第 " + j + " 味原料必须逐字等于配方表 (精确匹配的量, 少一个都不出酒)");
                helper.assertTrue(ingredient.item().getDescriptionId()
                                .equals(input.get("descriptionId").getAsString()),
                        type.id() + " 的原料发翻译键");
            }
        }

        // 抽两条对着定稿配方核对: 数量是精确匹配的判据, 面板发错量玩家就照着错的下料。
        assertIngredient(helper, recipes, WineType.VODKA, 0, "miningdim:farmer_wheat", 32);
        assertIngredient(helper, recipes, WineType.CHAMPAGNE, 0, "miningdim:farmer_wheat", 16);
        assertIngredient(helper, recipes, WineType.CHAMPAGNE, 1,
                ForgeRegistries.ITEMS.getKey(Items.SUGAR).toString(), 4);
        assertIngredient(helper, recipes, WineType.CHAMPAGNE, 2,
                ForgeRegistries.ITEMS.getKey(Items.APPLE).toString(), 2);

        helper.assertTrue(state.getAsJsonArray("moonshinePerks").isEmpty(),
                "月光未满层时词条是空数组 (前端据此说明'未满层'), 实得 " + state.getAsJsonArray("moonshinePerks"));
        helper.succeed();
    }

    // ============================================================
    // 2. 真实层数与月光词条
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void brewerStateMirrorsPermanentLayersAndPlayerWideMoonshinePerks(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setBrewerLevel(player, 7);
        BrewBuffStore store = BrewBuffStore.get(player.server.overworld());
        // 年份 >= T3 一次加 3 层 (纯逻辑入口, 不必真去酒窖等一天)。
        int vodkaLayers = store.addLayersForVintage(player.getUUID(), WineType.VODKA,
                BrewerConstants.VINTAGE_LAYER_T3);
        store.setMoonshinePerks(player.getUUID(), List.of(MoonshinePerk.SWIFT, MoonshinePerk.PLATED));

        JsonObject state = handle(helper, player);
        helper.assertTrue(state.get("level").getAsInt() == 7, "等级取职业框架门面的真值");
        helper.assertTrue(vodkaLayers == 3, "前置校验: 25 年份的一瓶闪耀酒加 3 层, 实得 " + vodkaLayers);

        JsonArray brews = state.getAsJsonArray("brews");
        for (int i = 0; i < WineType.values().length; i++) {
            WineType type = WineType.values()[i];
            int stacks = brews.get(i).getAsJsonObject().get("permanentStacks").getAsInt();
            helper.assertTrue(stacks == (type == WineType.VODKA ? 3 : 0),
                    type.id() + " 的层数错了 (只有伏特加加过层), 实得 " + stacks);
        }

        JsonArray perks = state.getAsJsonArray("moonshinePerks");
        helper.assertTrue(perks.size() == 2, "固化了两条月光词条, 实得 " + perks.size());
        helper.assertTrue(MoonshinePerk.SWIFT.id().equals(perks.get(0).getAsJsonObject().get("perkId").getAsString())
                        && MoonshinePerk.PLATED.id().equals(
                                perks.get(1).getAsJsonObject().get("perkId").getAsString()),
                "词条顺序按存进去的那一组原样下发, 实得 " + perks);
        helper.assertTrue(("brewer.moonshine." + MoonshinePerk.SWIFT.id())
                        .equals(perks.get(0).getAsJsonObject().get("labelKey").getAsString()),
                "词条发翻译键而不是中文");
        helper.succeed();
    }

    // ============================================================
    // 3. 翻译键与注册名
    // ============================================================

    /**
     * 八条月光词条的翻译键在两份 lang 里都必须存在: 服务端只发键, 缺键时面板直接显示 brewer.moonshine.swift。
     * 池里新增一条词条却忘了补 lang, 本条即挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void moonshinePerkLabelKeysExistInBothLangFiles(GameTestHelper helper) {
        JsonObject zh = loadJsonResource("/assets/miningdim/lang/zh_cn.json");
        JsonObject en = loadJsonResource("/assets/miningdim/lang/en_us.json");
        for (MoonshinePerk perk : MoonshinePerk.values()) {
            String key = "brewer.moonshine." + perk.id();
            helper.assertTrue(zh.has(key), "zh_cn.json 缺月光词条翻译键 " + key);
            helper.assertTrue(en.has(key), "en_us.json 缺月光词条翻译键 " + key);
        }
        for (WineType type : WineType.values()) {
            String key = BrewerItems.itemFor(type).getDescriptionId();
            helper.assertTrue(zh.has(key) && en.has(key), "两份 lang 都必须有酒名翻译键 " + key);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void brewerStateIsRegisteredUnderTheContractName(GameTestHelper helper) {
        ensureBrewerActionRegistered();
        helper.assertTrue(WebUiServerDispatcher.resolve(STATE_ACTION) != null,
                STATE_ACTION + " 必须由 BrewerWebUiActions.registerAll 注册进派发器");
        helper.assertTrue(WebUiServerDispatcher.resolve("job.brewer.recipes") == null
                        && WebUiServerDispatcher.resolve("brewer.state") == null,
                "酿酒师页只有 job.brewer.state 一条 action, 不得另注册别名");
        helper.succeed();
    }

    // ============================================================
    // 工具
    // ============================================================

    /** 幂等注册: 派发器注册表是进程级静态, register 用 putIfAbsent 守卫, 重复注册直接抛。 */
    private static void ensureBrewerActionRegistered() {
        if (WebUiServerDispatcher.resolve(STATE_ACTION) == null) {
            BrewerWebUiActions.registerAll();
        }
    }

    private static JsonObject handle(GameTestHelper helper, ServerPlayer sender) {
        ensureBrewerActionRegistered();
        WebUiAction handler = WebUiServerDispatcher.resolve(STATE_ACTION);
        if (handler == null) {
            helper.fail("action " + STATE_ACTION + " 未注册进派发器");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return JsonParser.parseString(handler.handle(sender, new JsonObject())).getAsJsonObject();
    }

    private static void assertIngredient(GameTestHelper helper, JsonArray recipes, WineType type,
                                         int index, String itemId, int count) {
        JsonObject inputs = null;
        for (int i = 0; i < recipes.size(); i++) {
            JsonObject row = recipes.get(i).getAsJsonObject();
            if (type.id().equals(row.get("wineId").getAsString())) {
                inputs = row;
                break;
            }
        }
        if (inputs == null) {
            helper.fail("回执缺配方 " + type.id());
            return;
        }
        JsonObject input = inputs.getAsJsonArray("inputs").get(index).getAsJsonObject();
        helper.assertTrue(itemId.equals(input.get("itemId").getAsString())
                        && input.get("count").getAsInt() == count,
                type.id() + " 的第 " + index + " 味原料应为 " + count + " 个 " + itemId + ", 实得 "
                        + input.get("count").getAsInt() + " 个 " + input.get("itemId").getAsString());
    }

    private static void setBrewerLevel(ServerPlayer player, int level) {
        MiningCapabilities.get(player)
                .orElseThrow(() -> new IllegalStateException("mock 玩家没有挂上矿山玩家数据 capability"))
                .jobProgress(JobId.BREWER).setLevel(level);
    }

    private static JsonObject loadJsonResource(String path) {
        try (InputStream in = BrewerWebUiGameTests.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("JSON resource not found on classpath: " + path);
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new IllegalStateException("failed reading JSON resource: " + path, e);
        }
    }
}

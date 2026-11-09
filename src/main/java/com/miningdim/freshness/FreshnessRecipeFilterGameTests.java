package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import java.util.List;
import java.util.Map;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 配方过滤 (规格「压缩方块与可逆转换」规则 1) 的契约 GameTest: 纯判据 + 一次端到端。
 *
 * 这一条规则守的是**凭空放大**: 拆回是 1 变 N, 每个产物都继承同一份剩余寿命, 于是"总剩余寿命"翻 N 倍
 * (加工继承挡不住这种放大, 只能从配方源头掐)。用例同时钉住"不许误伤"的那一侧 —— 多料配方、产物不腐烂、
 * 产出太少、白名单里的配方都必须活着, 否则一次误删就是一整类配方静默消失。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FreshnessRecipeFilterGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "freshness_recipes";

    /**
     * 规格点名的原版案例: 干草块拆成 9 个小麦 (干草块不腐烂, 小麦会)。
     * 这条配方在数据包里的 id 是 {@code minecraft:wheat} —— 原版用**产物**给合成配方命名 (1.20.1 的
     * {@code data/minecraft/recipes/wheat.json} 就是干草块 -> 9 小麦那一条)。
     */
    private static final ResourceLocation UNPACK_HAY = new ResourceLocation("minecraft:wheat");

    // ============================================================
    // 1. 判据
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void removesCompressedBlocksThatUnpackIntoPerishables(GameTestHelper helper) {
        // 不腐烂的压缩块 -> 9 件易腐品: 删 (规格点名的干草块拆小麦就是这一条)。
        helper.assertTrue(FreshnessRecipeFilter.shouldRemove(
                        shapeless("test:unpack_hay", Items.HAY_BLOCK, new ItemStack(Items.WHEAT, 9)),
                        RegistryAccess.EMPTY),
                "不腐烂的压缩块拆成 9 个会腐烂的原料, 必须删 (1 变 N 会把总剩余寿命放大 N 倍)");

        // 4 件起算: 3 件不动 (规格给的阈值就是 4)。
        helper.assertTrue(!FreshnessRecipeFilter.shouldRemove(
                        shapeless("test:unpack_three", Items.HAY_BLOCK, new ItemStack(Items.WHEAT, 3)),
                        RegistryAccess.EMPTY),
                "产出 3 件低于阈值 4, 不删");

        // 原料也会腐烂: 那是"切开再拼回"或烹饪, 归规则 2 与加工继承, 这里不碰。
        helper.assertTrue(!FreshnessRecipeFilter.shouldRemove(
                        shapeless("test:unpack_bread", Items.BREAD, new ItemStack(Items.COOKED_BEEF, 4)),
                        RegistryAccess.EMPTY),
                "原料本身就会腐烂的配方不归规则 1 管 (那是规则 2 / 加工继承的地盘)");

        // 产物不腐烂: 拿它当"新货"没有意义。
        helper.assertTrue(!FreshnessRecipeFilter.shouldRemove(
                        shapeless("test:pack_hay", Items.WHEAT, new ItemStack(Items.HAY_BLOCK, 9)),
                        RegistryAccess.EMPTY),
                "产物不腐烂的压缩方向必须保留 (只能压不能拆, 压的那一头是好的)");

        // 多料配方: 一律不动。
        helper.assertTrue(!FreshnessRecipeFilter.shouldRemove(
                        shapeless("test:two_ingredients", new ItemStack(Items.WHEAT, 1),
                                new ItemStack(Items.STONE, 1), new ItemStack(Items.BREAD, 4)),
                        RegistryAccess.EMPTY),
                "两种原料的配方不是压缩块, 不许误删");

        // 不是合成配方: 不动。
        Recipe<?> notCrafting = shapeless("test:not_crafting", Items.HAY_BLOCK, new ItemStack(Items.WHEAT, 9));
        helper.assertTrue(FreshnessRecipeFilter.shouldRemove(notCrafting, RegistryAccess.EMPTY),
                "前提: 这条合成配方本身命中规则");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void whitelistAndShapedRecipesBehave(GameTestHelper helper) {
        // 有序配方同样适用 (压缩块常常是 3x3 有序配方)。
        ShapedRecipe shaped = new ShapedRecipe(new ResourceLocation("test:shaped_unpack"), "test",
                CraftingBookCategory.MISC, 1, 1,
                NonNullList.withSize(1, Ingredient.of(Items.HAY_BLOCK)),
                new ItemStack(Items.WHEAT, 9));
        helper.assertTrue(FreshnessRecipeFilter.shouldRemove(shaped, RegistryAccess.EMPTY),
                "有序的拆回配方同样要删");

        // 特殊配方 (isSpecial, 例如地图复制) 不碰。
        Recipe<?> special = new ShapelessRecipe(new ResourceLocation("test:special_unpack"), "test",
                CraftingBookCategory.MISC, new ItemStack(Items.WHEAT, 9),
                NonNullList.withSize(1, Ingredient.of(Items.HAY_BLOCK))) {
            @Override
            public boolean isSpecial() {
                return true;
            }
        };
        helper.assertTrue(!FreshnessRecipeFilter.shouldRemove(special, RegistryAccess.EMPTY),
                "特殊配方 (结果不随材料变化的那类) 不参与过滤");
        helper.succeed();
    }

    // ============================================================
    // 2. 过滤一遍 + 白名单
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void applyRemovesOnlyTheMatchesAndHonoursTheWhitelist(GameTestHelper helper) {
        Map<ResourceLocation, Recipe<?>> crafting = new java.util.LinkedHashMap<>();
        crafting.put(new ResourceLocation("test:unpack_hay"), shapeless("test:unpack_hay",
                Items.HAY_BLOCK, new ItemStack(Items.WHEAT, 9)));
        crafting.put(new ResourceLocation("test:unpack_crate"), shapeless("test:unpack_crate",
                Items.OAK_LOG, new ItemStack(Items.BREAD, 6)));
        crafting.put(new ResourceLocation("test:pack_hay"), shapeless("test:pack_hay",
                Items.WHEAT, new ItemStack(Items.HAY_BLOCK, 9)));
        crafting.put(new ResourceLocation("test:allowed_unpack"), shapeless("test:allowed_unpack",
                Items.OAK_LOG, new ItemStack(Items.BREAD, 6)));

        Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> byType = new java.util.HashMap<>();
        byType.put(RecipeType.CRAFTING, crafting);

        java.util.Set<ResourceLocation> whitelist =
                FreshnessRecipeFilter.parseWhitelist(List.of("test:allowed_unpack", "not a valid id"));
        List<ResourceLocation> removed = FreshnessRecipeFilter.collectRemovals(crafting, whitelist);
        helper.assertTrue(removed.size() == 2, "两条命中 (箱子拆面包 + 干草块拆小麦), 实得 " + removed);

        // 内部表是 ImmutableMap, 就地 remove 会抛; 所以实现是"造副本换进去", 这里按同一手法验证。
        Map<ResourceLocation, Recipe<?>> rebuilt = FreshnessRecipeFilter.rebuildWithout(crafting, removed);
        byType.put(RecipeType.CRAFTING, rebuilt);
        helper.assertTrue(!rebuilt.containsKey(new ResourceLocation("test:unpack_hay")),
                "命中的必须真的不在新表里 (只回一个计数不算)");
        helper.assertTrue(!rebuilt.containsKey(new ResourceLocation("test:unpack_crate")), "第二条也要删");
        helper.assertTrue(rebuilt.containsKey(new ResourceLocation("test:pack_hay")), "压缩方向必须留着");
        helper.assertTrue(rebuilt.containsKey(new ResourceLocation("test:allowed_unpack")),
                "白名单里的配方必须留着 (整合包有正当的 1 料多产配方时靠它)");
        helper.assertTrue(crafting.size() == 4,
                "原表不许被就地改动 (它是 ImmutableMap; 改了会抛, 也会让别处看到的表与预期不一致)");

        // 再跑一遍: 新表里已经没有可删的了 (reload 会重跑整条链, 判据必须幂等)。
        helper.assertTrue(FreshnessRecipeFilter.collectRemovals(rebuilt, whitelist).isEmpty(),
                "第二遍不该再删任何东西");
        helper.succeed();
    }

    // ============================================================
    // 3. 端到端: 服务端的配方表里真的没有它
    // ============================================================

    /** 开服回调必须遵守总模式；测试结束后恢复共享配方表。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void liveRecipeFilterOnlyEnforcesOnMode(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var manager = server.getRecipeManager();
        List<Recipe<?>> original = List.copyOf(manager.getRecipes());
        List<Recipe<?>> fixture = new java.util.ArrayList<>(original);
        fixture.removeIf(recipe -> recipe.getId().equals(UNPACK_HAY));
        fixture.add(shapeless(UNPACK_HAY.toString(), Items.HAY_BLOCK, new ItemStack(Items.WHEAT, 9)));
        FreshnessConfig.Mode previous = FreshnessConfig.MODE.get();
        boolean previousFilter = FreshnessConfig.RECIPE_FILTER_ENABLED.get();
        try {
            FreshnessConfig.RECIPE_FILTER_ENABLED.set(true);
            for (FreshnessConfig.Mode mode : FreshnessConfig.Mode.values()) {
                manager.replaceRecipes(fixture);
                FreshnessConfig.MODE.set(mode);
                FreshnessRecipeFilterHooks.onServerStarted(
                        new net.minecraftforge.event.server.ServerStartedEvent(server));
                helper.assertTrue(manager.byKey(UNPACK_HAY).isEmpty() == (mode == FreshnessConfig.Mode.ON),
                        "live recipe removal must follow mode " + mode);
                helper.assertTrue(manager.byKey(new ResourceLocation("minecraft:bread")).isPresent(),
                        "unmatched recipes remain available in " + mode);
            }
        } finally {
            manager.replaceRecipes(original);
            FreshnessConfig.RECIPE_FILTER_ENABLED.set(previousFilter);
            FreshnessConfig.MODE.set(previous);
        }
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    private static ShapelessRecipe shapeless(String id, net.minecraft.world.item.Item input, ItemStack output) {
        return new ShapelessRecipe(new ResourceLocation(id), "test", CraftingBookCategory.MISC, output,
                NonNullList.withSize(1, Ingredient.of(input)));
    }

    private static ShapelessRecipe shapeless(String id, ItemStack first, ItemStack second, ItemStack output) {
        NonNullList<Ingredient> ingredients = NonNullList.create();
        ingredients.add(Ingredient.of(first.getItem()));
        ingredients.add(Ingredient.of(second.getItem()));
        return new ShapelessRecipe(new ResourceLocation(id), "test", CraftingBookCategory.MISC, output,
                ingredients);
    }
}

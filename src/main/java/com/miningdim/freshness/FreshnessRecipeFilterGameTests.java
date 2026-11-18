package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import net.minecraft.commands.Commands;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraftforge.event.AddReloadListenerEvent;
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
        FreshnessConfig.Mode previousMode = FreshnessConfig.MODE.get();
        FreshnessConfig.MODE.set(FreshnessConfig.Mode.DRY_RUN);
        try {
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
        } finally {
            FreshnessConfig.MODE.set(previousMode);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void whitelistAndShapedRecipesBehave(GameTestHelper helper) {
        FreshnessConfig.Mode previousMode = FreshnessConfig.MODE.get();
        FreshnessConfig.MODE.set(FreshnessConfig.Mode.DRY_RUN);
        try {
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
        } finally {
            FreshnessConfig.MODE.set(previousMode);
        }
    }

    // ============================================================
    // 2. 过滤一遍 + 白名单
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void applyRemovesOnlyTheMatchesAndHonoursTheWhitelist(GameTestHelper helper) {
        FreshnessConfig.Mode previousMode = FreshnessConfig.MODE.get();
        FreshnessConfig.MODE.set(FreshnessConfig.Mode.DRY_RUN);
        try {
            var recipes = List.of(
                    shapeless("test:unpack_hay", Items.HAY_BLOCK, new ItemStack(Items.WHEAT, 9)),
                    shapeless("test:unpack_crate", Items.OAK_LOG, new ItemStack(Items.BREAD, 6)),
                    shapeless("test:pack_hay", Items.WHEAT, new ItemStack(Items.HAY_BLOCK, 9)),
                    shapeless("test:allowed_unpack", Items.OAK_LOG, new ItemStack(Items.BREAD, 6)));
            var whitelist = FreshnessRecipeFilter.parseWhitelist(List.of("test:allowed_unpack", "not a valid id"));
            var removed = FreshnessRecipeFilter.collectRemovals(recipes, whitelist, helper.getLevel().registryAccess());
            helper.assertTrue(removed.equals(Set.of(new ResourceLocation("test:unpack_hay"),
                            new ResourceLocation("test:unpack_crate"))),
                    "必须只删除命中的两个拆回配方，保留压缩方向和白名单");
            helper.succeed();
        } finally {
            FreshnessConfig.MODE.set(previousMode);
        }
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

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void reloadFiltersTheIncomingRecipeManager(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var incoming = new ReloadableServerResources(server.registryAccess(), helper.getLevel().enabledFeatures(),
                Commands.CommandSelection.DEDICATED, 2);
        var next = incoming.getRecipeManager();
        var retained = shapeless("test:pack_hay", Items.WHEAT, new ItemStack(Items.HAY_BLOCK, 9));
        next.replaceRecipes(List.of(retained, shapeless(UNPACK_HAY.toString(), Items.HAY_BLOCK, new ItemStack(Items.WHEAT, 9))));
        var previousRecipe = server.getRecipeManager().byKey(UNPACK_HAY);
        var event = new AddReloadListenerEvent(incoming, server.registryAccess());
        FreshnessRecipeFilterHooks.onAddReloadListener(event);
        var previousMode = FreshnessConfig.MODE.get();
        boolean previousFilter = FreshnessConfig.RECIPE_FILTER_ENABLED.get();
        try {
            FreshnessConfig.MODE.set(FreshnessConfig.Mode.ON);
            FreshnessConfig.RECIPE_FILTER_ENABLED.set(true);
            PreparableReloadListener.PreparationBarrier barrier = new PreparableReloadListener.PreparationBarrier() {
                @Override
                public <T> CompletableFuture<T> wait(T value) {
                    return CompletableFuture.completedFuture(value);
                }
            };
            event.getListeners().get(0).reload(barrier, server.getResourceManager(), InactiveProfiler.INSTANCE,
                    InactiveProfiler.INSTANCE, Runnable::run, Runnable::run).join();
            helper.assertTrue(next.byKey(UNPACK_HAY).isEmpty()
                            && next.getAllRecipesFor(RecipeType.CRAFTING).equals(List.of(retained)),
                    "重载后按 id 和类型都只能查到保留配方");
            helper.assertTrue(server.getRecipeManager().byKey(UNPACK_HAY).equals(previousRecipe),
                    "旧服务端配方表必须保持原样");
        } finally {
            FreshnessConfig.RECIPE_FILTER_ENABLED.set(previousFilter);
            FreshnessConfig.MODE.set(previousMode);
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

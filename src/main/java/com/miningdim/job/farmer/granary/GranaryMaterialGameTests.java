package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.farmer.granary.block.GranaryBinBlockEntity;
import com.miningdim.job.farmer.granary.block.GranaryBlocks;
import com.miningdim.job.farmer.granary.block.GranaryCenterBlockEntity;
import com.miningdim.job.farmer.granary.block.GranaryVentBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.UUID;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranaryMaterialGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_materials";
    private static final int SLOT = 64;

    @net.minecraft.gametest.framework.AfterBatch(batch = BATCH)
    public static void clearLedger(ServerLevel level) {
        GranaryTestLedger.clear(level);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyStoneVariantCreatesAllEntityTypesAndMixesInOneGranary(GameTestHelper helper) {
        List<GranaryBlocks.MaterialVariant> stones = GranaryBlocks.MATERIAL_VARIANTS.stream()
                .filter(variant -> !variant.wood()).toList();
        helper.assertTrue(GranaryBlocks.MATERIAL_VARIANTS.size() == 31, "粮仓登记共 31 种木材与石材");
        helper.assertTrue(GranaryBlocks.WOOD_VARIANTS.size() == 11 && stones.size() == 20,
                "木材 11 种、石材 20 种");
        for (int index = 0; index < stones.size(); index++) {
            GranaryBlocks.MaterialVariant variant = stones.get(index);
            BlockPos bin = new BlockPos(index * 3, 1, 32);
            BlockPos center = new BlockPos(index * 3, 1, 34);
            BlockPos vent = new BlockPos(index * 3, 1, 36);
            helper.setBlock(bin, variant.bin().get());
            helper.setBlock(center, variant.center().get());
            helper.setBlock(vent, variant.vent().get());
            helper.assertTrue(helper.getLevel().getBlockEntity(helper.absolutePos(bin)) instanceof GranaryBinBlockEntity,
                    variant.material() + " 储粮囤创建方块实体");
            helper.assertTrue(helper.getLevel().getBlockEntity(helper.absolutePos(center)) instanceof GranaryCenterBlockEntity,
                    variant.material() + " 账台创建方块实体");
            helper.assertTrue(helper.getLevel().getBlockEntity(helper.absolutePos(vent)) instanceof GranaryVentBlockEntity,
                    variant.material() + " 气窗创建方块实体");
        }

        int slot = 0;
        buildRoom(helper, slot);
        GranaryBlocks.MaterialVariant quartz = stones.get(0);
        GranaryBlocks.MaterialVariant deepslate = stones.get(9);
        helper.setBlock(center(slot), quartz.center().get());
        helper.setBlock(bin(slot, 1, 1), quartz.bin().get());
        helper.setBlock(bin(slot, 3, 3), deepslate.bin().get());
        helper.setBlock(vent(slot), deepslate.vent().get());
        GranaryStructureScanner.ScanResult result = GranaryStructureScanner.scan(helper.getLevel(),
                helper.absolutePos(center(slot)));
        helper.assertTrue(result.ok(), "石英账台可与深板岩储粮囤组成粮仓: " + result.failure());
        helper.assertTrue(result.bins() == 2 && result.vents() == 1, "混合材质结构计入 2 个囤与 1 个气窗");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void stoneRecipesUseTheirMaterialAndPackingReturnsTheSameVariant(GameTestHelper helper) {
        List<GranaryBlocks.MaterialVariant> stones = GranaryBlocks.MATERIAL_VARIANTS.stream()
                .filter(variant -> !variant.wood()).toList();
        for (GranaryBlocks.MaterialVariant variant : stones) {
            String ingredientId = variant.material().equals("quartz") ? "quartz_block" : variant.material();
            Item material = ForgeRegistries.ITEMS.getValue(new ResourceLocation("minecraft", ingredientId));
            if (material == null) {
                throw new IllegalStateException("Missing vanilla granary ingredient for " + variant.material());
            }
            assertRecipe(helper, variant.bin().getId(), variant.binItem().get(), material,
                    Items.BARREL, null, 8, 4, 1, 3, 0);
            assertRecipe(helper, variant.center().getId(), variant.centerItem().get(), material,
                    Items.CHEST, Items.BOOK, 9, 4, 1, 3, 1);
            assertVentRecipe(helper, variant.vent().getId(), variant.ventItem().get(), material);
        }

        ServerLevel level = helper.getLevel();
        GranarySavedData data = GranarySavedData.get(level);
        GranaryBlocks.MaterialVariant variant = stones.get(0);
        BlockPos relative = new BlockPos(65, 1, 1);
        BlockPos pos = helper.absolutePos(relative);
        helper.setBlock(relative, variant.bin().get());
        UUID owner = UUID.randomUUID();
        long gid = data.nextGranaryId();
        helper.assertTrue(data.claimBin(level.dimension().location(), pos, owner, data.nextSeq()), "登记石材囤额度");
        data.linkBin(level.dimension().location(), pos, gid);
        ((GranaryBinBlockEntity) level.getBlockEntity(pos)).setItem(0, new ItemStack(Items.WHEAT, 7));
        GranaryTransfer.takeDown(level, pos, GranaryPending.Reason.LEFT, GranaryClock.nowHour(level));
        List<ItemStack> returned = data.pendingOf(owner).get(0).items();
        helper.assertTrue(returned.stream().anyMatch(stack -> stack.is(variant.binItem().get())),
                "打包粮单返还对应材质的储粮囤");
        helper.assertTrue(returned.stream().anyMatch(stack -> stack.is(Items.WHEAT) && stack.getCount() == 7),
                "打包粮单保留囤内小麦");
        data.setPending(owner, List.of());
        helper.succeed();
    }

    private static void assertRecipe(GameTestHelper helper, ResourceLocation path, Item expectedResult, Item material,
                                    Item fixtureOne, Item fixtureTwo, int ingredientSlots, int materialCount,
                                    int fixtureOneCount, int wheatCount, int expectedOtherCount) {
        ResourceLocation recipeId = new ResourceLocation(MiningConstants.MODID, "farmer/" + path.getPath());
        Recipe<?> recipe = helper.getLevel().getRecipeManager().byKey(recipeId)
                .orElseThrow(() -> new IllegalStateException("Missing granary recipe " + recipeId));
        List<Ingredient> ingredients = recipe.getIngredients();
        helper.assertTrue(ingredients.size() == ingredientSlots, recipeId + " 原料槽数量正确");
        helper.assertTrue(count(ingredients, material) == materialCount, recipeId + " 使用四个对应材质方块");
        helper.assertTrue(count(ingredients, fixtureOne) == fixtureOneCount, recipeId + " 使用指定基础容器");
        helper.assertTrue(count(ingredients, Items.WHEAT) == wheatCount, recipeId + " 使用三份小麦");
        if (fixtureTwo != null) {
            helper.assertTrue(count(ingredients, fixtureTwo) == expectedOtherCount, recipeId + " 使用指定附加物品");
        }
        ItemStack output = recipe.getResultItem(helper.getLevel().registryAccess());
        helper.assertTrue(output.is(expectedResult) && output.getCount() == 1, recipeId + " 产出对应材质粮仓部件");
    }

    private static void assertVentRecipe(GameTestHelper helper, ResourceLocation path, Item expectedResult, Item material) {
        ResourceLocation recipeId = new ResourceLocation(MiningConstants.MODID, "farmer/" + path.getPath());
        Recipe<?> recipe = helper.getLevel().getRecipeManager().byKey(recipeId)
                .orElseThrow(() -> new IllegalStateException("Missing granary recipe " + recipeId));
        List<Ingredient> ingredients = recipe.getIngredients();
        helper.assertTrue(ingredients.size() == 2 && count(ingredients, material) == 2,
                recipeId + " 使用上下相邻的两个对应材质方块");
        ItemStack output = recipe.getResultItem(helper.getLevel().registryAccess());
        helper.assertTrue(output.is(expectedResult) && output.getCount() == 2, recipeId + " 产出两个对应材质气窗");
    }

    private static int count(List<Ingredient> ingredients, Item item) {
        return (int) ingredients.stream().filter(ingredient -> ingredient.test(new ItemStack(item))).count();
    }

    private static BlockPos center(int slot) {
        return new BlockPos(slot * SLOT + 2, 2, 2);
    }

    private static BlockPos vent(int slot) {
        return new BlockPos(slot * SLOT, 2, 2);
    }

    private static BlockPos bin(int slot, int x, int z) {
        return new BlockPos(slot * SLOT + x, 1, z);
    }

    private static void buildRoom(GameTestHelper helper, int slot) {
        int base = slot * SLOT;
        int low = 1;
        int high = 3;
        for (int x = low - 1; x <= high + 1; x++) {
            for (int y = low - 1; y <= high + 1; y++) {
                for (int z = low - 1; z <= high + 1; z++) {
                    boolean shell = x == low - 1 || x == high + 1 || y == low - 1 || y == high + 1
                            || z == low - 1 || z == high + 1;
                    BlockState state = shell ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    helper.setBlock(new BlockPos(base + x, y, z), state);
                }
            }
        }
    }
}

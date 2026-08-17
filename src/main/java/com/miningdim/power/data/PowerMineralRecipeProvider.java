package com.miningdim.power.data;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.mineral.PowerMineral;
import com.miningdim.power.mineral.PowerMineralRegistry;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.FinishedRecipe;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.SimpleCookingRecipeBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.ItemLike;

import java.util.List;
import java.util.function.Consumer;

/** 六种金属的矿石与原矿烧炼链；硼砂仅作为提纯耗材，不生成冶炼配方。 */
final class PowerMineralRecipeProvider extends RecipeProvider {

    PowerMineralRecipeProvider(PackOutput output) {
        super(output);
    }

    @Override
    protected void buildRecipes(Consumer<FinishedRecipe> recipes) {
        for (PowerMineral mineral : PowerMineral.values()) {
            if (!mineral.hasIngot()) {
                continue;
            }
            List<ItemLike> inputs = List.of(
                    PowerMineralRegistry.ore(mineral).get(),
                    PowerMineralRegistry.deepslateOre(mineral).get(),
                    PowerMineralRegistry.rawMaterial(mineral).get());
            ItemLike result = PowerMineralRegistry.ingot(mineral).get();
            String group = mineral.name().toLowerCase(java.util.Locale.ROOT) + "_ingot";
            Ingredient ingredient = Ingredient.of(inputs.toArray(ItemLike[]::new));
            SimpleCookingRecipeBuilder.smelting(ingredient, RecipeCategory.MISC, result, 0.7F, 200)
                    .group(group)
                    .unlockedBy("has_" + mineral.rawMaterialId(), has(PowerMineralRegistry.rawMaterial(mineral).get()))
                    .save(recipes, recipeId(mineral, "smelting"));
            SimpleCookingRecipeBuilder.blasting(ingredient, RecipeCategory.MISC, result, 0.7F, 100)
                    .group(group)
                    .unlockedBy("has_" + mineral.rawMaterialId(), has(PowerMineralRegistry.rawMaterial(mineral).get()))
                    .save(recipes, recipeId(mineral, "blasting"));
        }
    }

    private ResourceLocation recipeId(PowerMineral mineral, String method) {
        return new ResourceLocation(MiningConstants.MODID,
                mineral.rawMaterialId() + "_to_" + mineral.ingotId() + "_" + method);
    }
}

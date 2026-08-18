package com.miningdim.power.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miningdim.core.MiningConstants;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;

import java.util.concurrent.CompletableFuture;

/**
 * PVC 与 PE 的配方是已批准的“从橡胶加工”生存接线默认值：各用一份橡胶和一种原版辅料，
 * 不产出信用点，也不替代后续经济总表对正式工业链的平衡裁决。
 * 原始 JSON Provider 避开 Forge 对多个同名 RecipeProvider 的注册冲突。
 */
final class PowerRubberRecipeProvider implements DataProvider {

    private final PackOutput.PathProvider recipes;

    PowerRubberRecipeProvider(PackOutput output) {
        recipes = output.createPathProvider(PackOutput.Target.DATA_PACK, "recipes");
    }

    @Override
    public CompletableFuture<?> run(CachedOutput output) {
        return CompletableFuture.allOf(
                save(output, "rubber_from_latex_smelting", smelting("miningdim:latex", "miningdim:rubber")),
                save(output, "rubber_planks", shapeless("building", "miningdim:rubber_planks", 4,
                        new String[]{"miningdim:rubber_log"})),
                save(output, "rubber_tapping_knife", tappingKnife()),
                save(output, "insulation_pvc", shapeless("misc", "miningdim:insulation_pvc", 1,
                        new String[]{"miningdim:rubber", "minecraft:clay_ball"})),
                save(output, "insulation_pe", shapeless("misc", "miningdim:insulation_pe", 1,
                        new String[]{"miningdim:rubber", "minecraft:charcoal"})));
    }

    @Override
    public String getName() {
        return "能源橡胶配方";
    }

    private CompletableFuture<?> save(CachedOutput output, String id, JsonObject recipe) {
        return DataProvider.saveStable(output, recipe, recipes.json(new ResourceLocation(MiningConstants.MODID, id)));
    }

    private JsonObject smelting(String ingredientItem, String resultItem) {
        JsonObject recipe = new JsonObject();
        recipe.addProperty("type", "minecraft:smelting");
        recipe.add("ingredient", ingredient(ingredientItem));
        recipe.addProperty("result", resultItem);
        recipe.addProperty("experience", 0.0F);
        recipe.addProperty("cookingtime", 200);
        return recipe;
    }

    private JsonObject shapeless(String category, String resultItem, int count, String[] ingredientItems) {
        JsonObject recipe = new JsonObject();
        recipe.addProperty("type", "minecraft:crafting_shapeless");
        recipe.addProperty("category", category);
        JsonArray ingredients = new JsonArray();
        for (String ingredientItem : ingredientItems) {
            ingredients.add(ingredient(ingredientItem));
        }
        recipe.add("ingredients", ingredients);
        recipe.add("result", result(resultItem, count));
        return recipe;
    }

    private JsonObject tappingKnife() {
        JsonObject recipe = new JsonObject();
        recipe.addProperty("type", "minecraft:crafting_shaped");
        recipe.addProperty("category", "equipment");
        JsonArray pattern = new JsonArray();
        pattern.add(" I");
        pattern.add("S ");
        recipe.add("pattern", pattern);
        JsonObject key = new JsonObject();
        key.add("I", ingredient("minecraft:iron_ingot"));
        key.add("S", ingredient("minecraft:stick"));
        recipe.add("key", key);
        recipe.add("result", result("miningdim:rubber_tapping_knife", 1));
        return recipe;
    }

    private JsonObject ingredient(String item) {
        JsonObject result = new JsonObject();
        result.addProperty("item", item);
        return result;
    }

    private JsonObject result(String item, int count) {
        JsonObject result = new JsonObject();
        result.addProperty("item", item);
        if (count != 1) {
            result.addProperty("count", count);
        }
        return result;
    }
}

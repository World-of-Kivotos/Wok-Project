package com.miningdim.power.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miningdim.core.MiningConstants;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;

import java.util.concurrent.CompletableFuture;

/** 终局超导材料、低温控制器与镍铬保险丝的默认数据包配方。 */
final class PowerEndgameRecipeProvider implements DataProvider {

    private final PackOutput.PathProvider recipes;

    PowerEndgameRecipeProvider(PackOutput output) {
        recipes = output.createPathProvider(PackOutput.Target.DATA_PACK, "recipes");
    }

    @Override
    public CompletableFuture<?> run(CachedOutput output) {
        return CompletableFuture.allOf(
                save(output, "graphene_sheet", shapeless("misc", "miningdim:graphene_sheet", 2,
                        new String[]{"minecraft:charcoal", "minecraft:charcoal", "minecraft:charcoal", "minecraft:charcoal",
                                "minecraft:quartz", "minecraft:quartz", "minecraft:blaze_powder"})),
                save(output, "superconductor_precursor", shapeless("misc", "miningdim:superconductor_precursor", 1,
                        new String[]{"miningdim:nickel_ingot", "miningdim:tungsten_ingot", "minecraft:ender_pearl", "minecraft:quartz"})),
                save(output, "nbti_conductor", shaped("misc", "miningdim:nbti_conductor", 4,
                        new String[]{"ONO", "NCN", "ONO"},
                        new String[]{"O", "miningdim:ofe_copper_ingot", "N", "miningdim:nickel_ingot",
                                "C", "miningdim:superconductor_precursor"})),
                save(output, "ybco_tape", shaped("misc", "miningdim:ybco_tape", 4,
                        new String[]{"PCP", "GBG", "PCP"},
                        new String[]{"P", "miningdim:superconductor_precursor", "C", "miningdim:ofe_copper_ingot",
                                "G", "miningdim:gold_4n_ingot", "B", "minecraft:blaze_powder"})),
                save(output, "low_temperature_controller", shaped("misc", "miningdim:low_temperature_controller", 1,
                        new String[]{"PNP", "OLO", "PNP"},
                        new String[]{"P", "miningdim:phosphorus_deoxidized_copper_ingot", "N", "miningdim:nbti_conductor",
                                "O", "miningdim:ofe_copper_ingot", "L", "miningdim:liquid_nitrogen_canister"})),
                save(output, "nichrome_fuse", shapeless("misc", "miningdim:nichrome_fuse", 4,
                        new String[]{"miningdim:nickel_ingot", "miningdim:nickel_ingot", "miningdim:nickel_ingot",
                                "miningdim:nickel_ingot", "miningdim:chromium_ingot"})));
    }

    @Override
    public String getName() {
        return "终局能源配方";
    }

    private CompletableFuture<?> save(CachedOutput output, String id, JsonObject recipe) {
        return DataProvider.saveStable(output, recipe,
                recipes.json(new ResourceLocation(MiningConstants.MODID, id)));
    }

    private JsonObject shaped(String category, String resultItem, int resultCount, String[] pattern, String[] keys) {
        JsonObject recipe = new JsonObject();
        recipe.addProperty("type", "minecraft:crafting_shaped");
        recipe.addProperty("category", category);
        JsonArray patternJson = new JsonArray();
        for (String row : pattern) {
            patternJson.add(row);
        }
        recipe.add("pattern", patternJson);
        JsonObject keyJson = new JsonObject();
        for (int index = 0; index < keys.length; index += 2) {
            keyJson.add(keys[index], ingredient(keys[index + 1]));
        }
        recipe.add("key", keyJson);
        recipe.add("result", result(resultItem, resultCount));
        return recipe;
    }

    private JsonObject shapeless(String category, String resultItem, int resultCount, String[] ingredients) {
        JsonObject recipe = new JsonObject();
        recipe.addProperty("type", "minecraft:crafting_shapeless");
        recipe.addProperty("category", category);
        JsonArray ingredientsJson = new JsonArray();
        for (String item : ingredients) {
            ingredientsJson.add(ingredient(item));
        }
        recipe.add("ingredients", ingredientsJson);
        recipe.add("result", result(resultItem, resultCount));
        return recipe;
    }

    private JsonObject ingredient(String item) {
        JsonObject ingredient = new JsonObject();
        ingredient.addProperty("item", item);
        return ingredient;
    }

    private JsonObject result(String item, int count) {
        JsonObject result = new JsonObject();
        result.addProperty("item", item);
        if (count > 1) {
            result.addProperty("count", count);
        }
        return result;
    }
}

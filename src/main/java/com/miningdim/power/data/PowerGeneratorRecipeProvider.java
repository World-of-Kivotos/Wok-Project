package com.miningdim.power.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miningdim.core.MiningConstants;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;

import java.util.concurrent.CompletableFuture;

/** 仅开放工业档的默认生存配方；现代与未来档随各自分期进入。 */
final class PowerGeneratorRecipeProvider implements DataProvider {

    private final PackOutput.PathProvider recipes;

    PowerGeneratorRecipeProvider(PackOutput output) {
        recipes = output.createPathProvider(PackOutput.Target.DATA_PACK, "recipes");
    }

    @Override
    public CompletableFuture<?> run(CachedOutput output) {
        return CompletableFuture.allOf(
                save(output, "industrial_fuel_core", shaped("misc", "miningdim:industrial_fuel_core",
                        new String[]{"CRC", "RIR", "CRC"},
                        new String[]{"C", "tag:minecraft:coals", "R", "minecraft:redstone", "I", "minecraft:iron_ingot"})),
                save(output, "industrial_generator", shaped("misc", "miningdim:industrial_generator",
                        new String[]{"ICI", "IRI", "IFI"},
                        new String[]{"I", "minecraft:iron_ingot", "C", "minecraft:copper_ingot",
                                "R", "minecraft:redstone", "F", "minecraft:furnace"})));
    }

    @Override
    public String getName() {
        return "能源发电机配方";
    }

    private CompletableFuture<?> save(CachedOutput output, String id, JsonObject recipe) {
        return DataProvider.saveStable(output, recipe,
                recipes.json(new ResourceLocation(MiningConstants.MODID, id)));
    }

    private JsonObject shaped(String category, String resultItem, String[] pattern, String[] keys) {
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
            String symbol = keys[index];
            String ingredientId = keys[index + 1];
            keyJson.add(symbol, ingredient(ingredientId));
        }
        recipe.add("key", keyJson);
        recipe.add("result", result(resultItem));
        return recipe;
    }

    private JsonObject ingredient(String itemOrTag) {
        JsonObject ingredient = new JsonObject();
        if (itemOrTag.startsWith("tag:")) {
            ingredient.addProperty("tag", itemOrTag.substring("tag:".length()));
        } else {
            ingredient.addProperty("item", itemOrTag);
        }
        return ingredient;
    }

    private JsonObject result(String item) {
        JsonObject result = new JsonObject();
        result.addProperty("item", item);
        return result;
    }
}

package com.miningdim.power.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miningdim.core.MiningConstants;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;

import java.util.concurrent.CompletableFuture;

/** 提纯、空分及两台机器的默认生存配方。运行参数由服务端 profile 配置决定。 */
final class PowerMachineRecipeProvider implements DataProvider {

    private final PackOutput.PathProvider recipes;

    PowerMachineRecipeProvider(PackOutput output) {
        recipes = output.createPathProvider(PackOutput.Target.DATA_PACK, "recipes");
    }

    @Override
    public CompletableFuture<?> run(CachedOutput output) {
        return CompletableFuture.allOf(
                save(output, "copper_to_deoxidized", purifying("copper_to_deoxidized",
                        "minecraft:copper_ingot", "miningdim:borax", "miningdim:borax",
                        "miningdim:deoxidized_copper_ingot")),
                save(output, "deoxidized_to_ofc", purifying("deoxidized_to_ofc",
                        "miningdim:deoxidized_copper_ingot", "miningdim:borax", "miningdim:borax",
                        "miningdim:ofc_copper_ingot")),
                save(output, "deoxidized_to_phosphorus", purifying("deoxidized_to_phosphorus",
                        "miningdim:deoxidized_copper_ingot", "miningdim:phosphorus", "minecraft:bone_meal",
                        "miningdim:phosphorus_deoxidized_copper_ingot")),
                save(output, "ofc_to_ofe", purifying("ofc_to_ofe",
                        "miningdim:ofc_copper_ingot", "miningdim:argon", "miningdim:argon_canister",
                        "miningdim:ofe_copper_ingot")),
                save(output, "gold_to_4n", purifying("gold_to_4n",
                        "minecraft:gold_ingot", "miningdim:argon", "miningdim:argon_canister",
                        "miningdim:gold_4n_ingot")),
                save(output, "air_separation_argon", airSeparating("argon", "miningdim:argon_canister")),
                save(output, "air_separation_liquid_nitrogen",
                        airSeparating("liquid_nitrogen", "miningdim:liquid_nitrogen_canister")),
                save(output, "metallurgic_purifier", shaped("misc", "miningdim:metallurgic_purifier",
                        new String[]{"IFI", "CRC", "IRI"},
                        new String[]{"I", "minecraft:iron_ingot", "F", "minecraft:furnace",
                                "C", "minecraft:copper_ingot", "R", "minecraft:redstone"})),
                save(output, "air_separation_unit", shaped("misc", "miningdim:air_separation_unit",
                        new String[]{"PIP", "PCP", "PRP"},
                        new String[]{"P", "miningdim:phosphorus_deoxidized_copper_ingot",
                                "I", "minecraft:iron_ingot", "C", "miningdim:metallurgic_purifier",
                                "R", "minecraft:redstone"})));
    }

    @Override
    public String getName() {
        return "能源加工机器配方";
    }

    private CompletableFuture<?> save(CachedOutput output, String id, JsonObject recipe) {
        return DataProvider.saveStable(output, recipe,
                recipes.json(new ResourceLocation(MiningConstants.MODID, id)));
    }

    private JsonObject purifying(String profile, String base, String infusionType,
                                 String infusionIngredient, String result) {
        JsonObject recipe = new JsonObject();
        recipe.addProperty("type", MiningConstants.MODID + ":metallurgic_purifying");
        recipe.addProperty("profile", profile);
        recipe.add("base", ingredient(base));
        JsonObject infusion = new JsonObject();
        infusion.addProperty("type", infusionType);
        infusion.add("ingredient", ingredient(infusionIngredient));
        recipe.add("infusion", infusion);
        recipe.add("result", result(result));
        return recipe;
    }

    private JsonObject airSeparating(String mode, String result) {
        JsonObject recipe = new JsonObject();
        recipe.addProperty("type", MiningConstants.MODID + ":air_separating");
        recipe.addProperty("mode", mode);
        recipe.add("result", result(result));
        return recipe;
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
            keyJson.add(keys[index], ingredient(keys[index + 1]));
        }
        recipe.add("key", keyJson);
        recipe.add("result", result(resultItem));
        return recipe;
    }

    private JsonObject ingredient(String item) {
        JsonObject ingredient = new JsonObject();
        ingredient.addProperty("item", item);
        return ingredient;
    }

    private JsonObject result(String item) {
        JsonObject result = new JsonObject();
        result.addProperty("item", item);
        return result;
    }
}

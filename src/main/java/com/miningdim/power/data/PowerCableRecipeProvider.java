package com.miningdim.power.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miningdim.core.MiningConstants;
import com.miningdim.power.PowerRegistry;
import com.miningdim.power.cable.ConductorMaterial;
import com.miningdim.power.mineral.PowerMineral;
import com.miningdim.power.mineral.PowerMineralRegistry;
import com.miningdim.power.rubber.PowerRubberRegistry;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;

import java.util.concurrent.CompletableFuture;

/** P1 导线与线缆配方，固定三进六出产量。 */
final class PowerCableRecipeProvider implements DataProvider {

    private final PackOutput.PathProvider recipes;

    PowerCableRecipeProvider(PackOutput output) {
        recipes = output.createPathProvider(PackOutput.Target.DATA_PACK, "recipes");
    }

    @Override
    public CompletableFuture<?> run(CachedOutput output) {
        CompletableFuture<?>[] saves = PowerRegistry.CABLES.keySet().stream()
                .flatMap(material -> java.util.stream.Stream.of(
                        save(output, material.id() + "_wire", shapeless("misc",
                                itemId(PowerRegistry.WIRE_ITEMS.get(material).get()), 6,
                                itemId(conductorIngot(material)), "3")),
                        save(output, material.blockId(), shapeless("building",
                                itemId(PowerRegistry.CABLE_ITEMS.get(material).get()), 6,
                                itemId(PowerRegistry.WIRE_ITEMS.get(material).get()), "3",
                                itemId(insulation(material)), "3"))))
                .toArray(CompletableFuture<?>[]::new);
        return CompletableFuture.allOf(saves);
    }

    @Override
    public String getName() {
        return "能源P1线缆配方";
    }

    private CompletableFuture<?> save(CachedOutput output, String id, JsonObject recipe) {
        return DataProvider.saveStable(output, recipe,
                recipes.json(new ResourceLocation(MiningConstants.MODID, id)));
    }

    private JsonObject shapeless(String category, String resultItem, int resultCount, String... inputs) {
        JsonObject recipe = new JsonObject();
        recipe.addProperty("type", "minecraft:crafting_shapeless");
        recipe.addProperty("category", category);
        JsonArray ingredients = new JsonArray();
        for (int i = 0; i < inputs.length; i += 2) {
            JsonObject ingredient = new JsonObject();
            ingredient.addProperty("item", inputs[i]);
            for (int count = 1; count < Integer.parseInt(inputs[i + 1]); count++) {
                ingredients.add(ingredient.deepCopy());
            }
            ingredients.add(ingredient);
        }
        recipe.add("ingredients", ingredients);
        JsonObject result = new JsonObject();
        result.addProperty("item", resultItem);
        result.addProperty("count", resultCount);
        recipe.add("result", result);
        return recipe;
    }

    private net.minecraft.world.level.ItemLike conductorIngot(ConductorMaterial material) {
        return switch (material) {
            case IRON -> net.minecraft.world.item.Items.IRON_INGOT;
            case ALUMINUM -> PowerMineralRegistry.ingot(PowerMineral.BAUXITE).get();
            case COPPER -> net.minecraft.world.item.Items.COPPER_INGOT;
            default -> throw new IllegalStateException("P1 recipe requested for unopened conductor " + material);
        };
    }

    private net.minecraft.world.level.ItemLike insulation(ConductorMaterial material) {
        return switch (material.insulation()) {
            case PVC -> PowerRubberRegistry.INSULATION_PVC.get();
            case PE -> PowerRubberRegistry.INSULATION_PE.get();
            default -> throw new IllegalStateException("P1 recipe requested for unsupported insulation "
                    + material.insulation());
        };
    }

    private String itemId(net.minecraft.world.level.ItemLike item) {
        ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item.asItem());
        if (key == null) {
            throw new IllegalStateException("missing item registry key for " + item);
        }
        return key.toString();
    }
}

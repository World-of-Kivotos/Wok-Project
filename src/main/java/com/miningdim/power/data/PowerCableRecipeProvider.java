package com.miningdim.power.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miningdim.core.MiningConstants;
import com.miningdim.power.PowerRegistry;
import com.miningdim.power.cable.ConductorMaterial;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;

import java.util.concurrent.CompletableFuture;

/** 已开放导线与线缆配方；常规导线三进六出，镀层导线八根等量升级。 */
final class PowerCableRecipeProvider implements DataProvider {

    private final PackOutput.PathProvider recipes;

    PowerCableRecipeProvider(PackOutput output) {
        recipes = output.createPathProvider(PackOutput.Target.DATA_PACK, "recipes");
    }

    @Override
    public CompletableFuture<?> run(CachedOutput output) {
        CompletableFuture<?>[] saves = PowerRegistry.CABLES.keySet().stream()
                .flatMap(material -> java.util.stream.Stream.of(
                        wireRecipe(output, material),
                        save(output, material.blockId(), shapeless("building",
                                itemId(PowerRegistry.CABLE_ITEMS.get(material).get()), 6,
                                itemId(PowerRegistry.WIRE_ITEMS.get(material).get()), "3",
                                insulationId(material), "3"))))
                .toArray(CompletableFuture<?>[]::new);
        return CompletableFuture.allOf(saves);
    }

    @Override
    public String getName() {
        return "能源线缆配方";
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

    private CompletableFuture<?> wireRecipe(CachedOutput output, ConductorMaterial material) {
        return switch (material) {
            case TINNED_COPPER -> save(output, material.id() + "_wire", shapeless("misc",
                    itemId(PowerRegistry.WIRE_ITEMS.get(material).get()), 8,
                    "miningdim:copper_wire", "8", "miningdim:tin_ingot", "1"));
            case SILVER_PLATED_COPPER -> save(output, material.id() + "_wire", shapeless("misc",
                    itemId(PowerRegistry.WIRE_ITEMS.get(material).get()), 8,
                    "miningdim:ofe_copper_wire", "8", "miningdim:silver_ingot", "1"));
            default -> save(output, material.id() + "_wire", shapeless("misc",
                    itemId(PowerRegistry.WIRE_ITEMS.get(material).get()), 6,
                    conductorIngotId(material), "3"));
        };
    }

    private String conductorIngotId(ConductorMaterial material) {
        return switch (material) {
            case IRON -> "minecraft:iron_ingot";
            case ALUMINUM -> "miningdim:aluminum_ingot";
            case COPPER -> "minecraft:copper_ingot";
            case OFC_COPPER -> "miningdim:ofc_copper_ingot";
            case OFE_COPPER -> "miningdim:ofe_copper_ingot";
            case GOLD -> "miningdim:gold_4n_ingot";
            case SILVER -> "miningdim:silver_ingot";
            default -> throw new IllegalStateException("wire recipe requested for plated or unopened conductor "
                    + material);
        };
    }

    private String insulationId(ConductorMaterial material) {
        return switch (material.insulation()) {
            case PVC -> "miningdim:insulation_pvc";
            case PE -> "miningdim:insulation_pe";
            case EPR -> "miningdim:insulation_epr";
            case XLPE -> "miningdim:insulation_xlpe";
            case SILICONE -> "miningdim:insulation_silicone";
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

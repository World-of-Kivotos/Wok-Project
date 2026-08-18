package com.miningdim.power.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miningdim.core.MiningConstants;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;

import java.util.concurrent.CompletableFuture;

/** 通过真实 datapack 特征和 Forge biome modifier 将橡胶树稀疏加入丛林生物群系。 */
final class PowerRubberWorldgenProvider implements DataProvider {

    private static final ResourceLocation TREE = new ResourceLocation(MiningConstants.MODID, "rubber_tree");
    private static final ResourceLocation PLACED_TREE = new ResourceLocation(MiningConstants.MODID, "rubber_tree_placed");
    private static final ResourceLocation BIOME_MODIFIER = new ResourceLocation(MiningConstants.MODID, "add_rubber_tree");

    private final PackOutput.PathProvider configuredFeatures;
    private final PackOutput.PathProvider placedFeatures;
    private final PackOutput.PathProvider biomeModifiers;

    PowerRubberWorldgenProvider(PackOutput output) {
        configuredFeatures = output.createPathProvider(PackOutput.Target.DATA_PACK, "worldgen/configured_feature");
        placedFeatures = output.createPathProvider(PackOutput.Target.DATA_PACK, "worldgen/placed_feature");
        biomeModifiers = output.createPathProvider(PackOutput.Target.DATA_PACK, "forge/biome_modifier");
    }

    @Override
    public CompletableFuture<?> run(CachedOutput output) {
        return CompletableFuture.allOf(
                DataProvider.saveStable(output, configuredTree(), configuredFeatures.json(TREE)),
                DataProvider.saveStable(output, placedTree(), placedFeatures.json(PLACED_TREE)),
                DataProvider.saveStable(output, jungleBiomeModifier(), biomeModifiers.json(BIOME_MODIFIER)));
    }

    @Override
    public String getName() {
        return "能源橡胶树世界生成";
    }

    private JsonObject configuredTree() {
        JsonObject root = new JsonObject();
        root.addProperty("type", "minecraft:tree");
        JsonObject config = new JsonObject();
        config.add("trunk_provider", stateProvider("miningdim:rubber_log"));
        config.add("trunk_placer", straightTrunkPlacer());
        config.add("foliage_provider", stateProvider("miningdim:rubber_leaves"));
        config.add("foliage_placer", blobFoliagePlacer());
        config.add("dirt_provider", stateProvider("minecraft:dirt"));
        JsonObject minimumSize = new JsonObject();
        minimumSize.addProperty("type", "minecraft:two_layers_feature_size");
        minimumSize.addProperty("limit", 1);
        minimumSize.addProperty("lower_size", 0);
        minimumSize.addProperty("upper_size", 1);
        config.add("minimum_size", minimumSize);
        config.addProperty("ignore_vines", true);
        config.add("decorators", new JsonArray());
        root.add("config", config);
        return root;
    }

    private JsonObject straightTrunkPlacer() {
        JsonObject result = new JsonObject();
        result.addProperty("type", "minecraft:straight_trunk_placer");
        result.addProperty("base_height", 4);
        result.addProperty("height_rand_a", 2);
        result.addProperty("height_rand_b", 1);
        return result;
    }

    private JsonObject blobFoliagePlacer() {
        JsonObject result = new JsonObject();
        result.addProperty("type", "minecraft:blob_foliage_placer");
        result.add("radius", constant(2));
        result.add("offset", constant(0));
        result.addProperty("height", 3);
        return result;
    }

    private JsonObject stateProvider(String blockId) {
        JsonObject result = new JsonObject();
        result.addProperty("type", "minecraft:simple_state_provider");
        JsonObject state = new JsonObject();
        state.addProperty("Name", blockId);
        result.add("state", state);
        return result;
    }

    private JsonObject constant(int value) {
        JsonObject result = new JsonObject();
        result.addProperty("type", "minecraft:constant");
        result.addProperty("value", value);
        return result;
    }

    private JsonObject placedTree() {
        JsonObject root = new JsonObject();
        root.addProperty("feature", TREE.toString());
        JsonArray placement = new JsonArray();
        JsonObject rarity = placementType("minecraft:rarity_filter");
        rarity.addProperty("chance", 24);
        placement.add(rarity);
        placement.add(placementType("minecraft:in_square"));
        JsonObject waterDepth = placementType("minecraft:surface_water_depth_filter");
        waterDepth.addProperty("max_water_depth", 0);
        placement.add(waterDepth);
        JsonObject heightmap = placementType("minecraft:heightmap");
        heightmap.addProperty("heightmap", "MOTION_BLOCKING");
        placement.add(heightmap);
        placement.add(placementType("minecraft:biome"));
        root.add("placement", placement);
        return root;
    }

    private JsonObject jungleBiomeModifier() {
        JsonObject root = new JsonObject();
        root.addProperty("type", "forge:add_features");
        root.addProperty("biomes", "#minecraft:is_jungle");
        root.addProperty("features", PLACED_TREE.toString());
        root.addProperty("step", "vegetal_decoration");
        return root;
    }

    private JsonObject placementType(String type) {
        JsonObject result = new JsonObject();
        result.addProperty("type", type);
        return result;
    }
}

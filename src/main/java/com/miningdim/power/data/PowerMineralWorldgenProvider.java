package com.miningdim.power.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miningdim.core.Difficulty;
import com.miningdim.core.MiningConstants;
import com.miningdim.power.mineral.PowerMineral;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 生成可由同 ID 数据包覆盖的矿石特征，难度矿脉数来自 PowerMineral 枚举。 */
final class PowerMineralWorldgenProvider implements DataProvider {

    private final PackOutput.PathProvider configuredFeatures;
    private final PackOutput.PathProvider placedFeatures;

    PowerMineralWorldgenProvider(PackOutput output) {
        configuredFeatures = output.createPathProvider(PackOutput.Target.DATA_PACK, "worldgen/configured_feature");
        placedFeatures = output.createPathProvider(PackOutput.Target.DATA_PACK, "worldgen/placed_feature");
    }

    @Override
    public CompletableFuture<?> run(CachedOutput output) {
        List<CompletableFuture<?>> writes = new ArrayList<>();
        for (PowerMineral mineral : PowerMineral.values()) {
            ResourceLocation featureId = featureId(mineral);
            writes.add(DataProvider.saveStable(output, configuredFeature(mineral), configuredFeatures.json(featureId)));
            for (Difficulty difficulty : Difficulty.values()) {
                int count = attempts(mineral, difficulty);
                if (count > 0) {
                    ResourceLocation placedId = placedFeatureId(mineral, difficulty);
                    writes.add(DataProvider.saveStable(output, placedFeature(featureId, count), placedFeatures.json(placedId)));
                }
            }
        }
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
    }

    @Override
    public String getName() {
        return "能源基础矿物世界生成";
    }

    private JsonObject configuredFeature(PowerMineral mineral) {
        JsonObject root = new JsonObject();
        root.addProperty("type", "minecraft:ore");
        JsonObject config = new JsonObject();
        config.addProperty("discard_chance_on_air_exposure", 0.0D);
        config.addProperty("size", mineral.veinSize());
        JsonArray targets = new JsonArray();
        targets.add(target(mineral.oreKey(), "minecraft:stone_ore_replaceables"));
        targets.add(target(mineral.deepslateOreKey(), "minecraft:deepslate_ore_replaceables"));
        config.add("targets", targets);
        root.add("config", config);
        return root;
    }

    private JsonObject target(ResourceLocation block, String replaceables) {
        JsonObject target = new JsonObject();
        JsonObject state = new JsonObject();
        state.addProperty("Name", block.toString());
        target.add("state", state);
        JsonObject predicate = new JsonObject();
        predicate.addProperty("predicate_type", "minecraft:tag_match");
        predicate.addProperty("tag", replaceables);
        target.add("target", predicate);
        return target;
    }

    private JsonObject placedFeature(ResourceLocation featureId, int count) {
        JsonObject root = new JsonObject();
        root.addProperty("feature", featureId.toString());
        JsonArray placement = new JsonArray();
        JsonObject countPlacement = new JsonObject();
        countPlacement.addProperty("type", "minecraft:count");
        countPlacement.addProperty("count", count);
        placement.add(countPlacement);
        placement.add(placementType("minecraft:in_square"));
        placement.add(heightRange());
        placement.add(placementType("minecraft:biome"));
        root.add("placement", placement);
        return root;
    }

    private JsonObject heightRange() {
        JsonObject placement = placementType("minecraft:height_range");
        JsonObject height = new JsonObject();
        height.addProperty("type", "minecraft:uniform");
        height.add("min_inclusive", verticalAnchor("above_bottom", 8));
        height.add("max_inclusive", verticalAnchor("below_top", 8));
        placement.add("height", height);
        return placement;
    }

    private JsonObject placementType(String type) {
        JsonObject placement = new JsonObject();
        placement.addProperty("type", type);
        return placement;
    }

    private JsonObject verticalAnchor(String anchor, int value) {
        JsonObject result = new JsonObject();
        result.addProperty(anchor, value);
        return result;
    }

    private ResourceLocation featureId(PowerMineral mineral) {
        return new ResourceLocation(MiningConstants.MODID, "ore_" + mineral.name().toLowerCase(java.util.Locale.ROOT));
    }

    private ResourceLocation placedFeatureId(PowerMineral mineral, Difficulty difficulty) {
        return new ResourceLocation(MiningConstants.MODID,
                "ore_" + mineral.name().toLowerCase(java.util.Locale.ROOT) + "_"
                        + difficulty.name().toLowerCase(java.util.Locale.ROOT));
    }

    private int attempts(PowerMineral mineral, Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> mineral.easyAttemptsPerChunk();
            case MEDIUM -> mineral.mediumAttemptsPerChunk();
            case HARD -> mineral.hardAttemptsPerChunk();
        };
    }
}

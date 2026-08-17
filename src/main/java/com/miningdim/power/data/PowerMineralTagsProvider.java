package com.miningdim.power.data;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.mineral.PowerMineral;
import com.miningdim.power.mineral.PowerMineralRegistry;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.ItemTagsProvider;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.Tags;
import net.minecraftforge.common.data.BlockTagsProvider;
import net.minecraftforge.common.data.ExistingFileHelper;

import java.util.concurrent.CompletableFuture;

/** 矿石均使用镐采掘并要求铁镐，供原版和 Forge 标签消费者识别。 */
final class PowerMineralTagsProvider extends BlockTagsProvider {

    private static final TagKey<Block> POWER_MINERALS = TagKey.create(
            Registries.BLOCK, new ResourceLocation(MiningConstants.MODID, "power_minerals"));

    PowerMineralTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider,
                             ExistingFileHelper existingFiles) {
        super(output, lookupProvider, MiningConstants.MODID, existingFiles);
    }

    @Override
    protected void addTags(HolderLookup.Provider lookupProvider) {
        var powerMinerals = tag(POWER_MINERALS);
        var needsIron = tag(BlockTags.NEEDS_IRON_TOOL);
        var ores = tag(Tags.Blocks.ORES);
        for (PowerMineral mineral : PowerMineral.values()) {
            powerMinerals.add(PowerMineralRegistry.ore(mineral).get(), PowerMineralRegistry.deepslateOre(mineral).get());
            needsIron.add(PowerMineralRegistry.ore(mineral).get(), PowerMineralRegistry.deepslateOre(mineral).get());
            ores.add(PowerMineralRegistry.ore(mineral).get(), PowerMineralRegistry.deepslateOre(mineral).get());
        }
    }
}

/** 矿物原料标签使后续线缆、机器与第三方配方可按类别消费这些物品。 */
final class PowerMineralItemTagsProvider extends ItemTagsProvider {

    PowerMineralItemTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider,
                                 CompletableFuture<TagsProvider.TagLookup<Block>> blockTags,
                                 ExistingFileHelper existingFiles) {
        super(output, lookupProvider, blockTags, MiningConstants.MODID, existingFiles);
    }

    @Override
    protected void addTags(HolderLookup.Provider lookupProvider) {
        var ores = tag(Tags.Items.ORES);
        var rawMaterials = tag(Tags.Items.RAW_MATERIALS);
        var ingots = tag(Tags.Items.INGOTS);
        for (PowerMineral mineral : PowerMineral.values()) {
            ores.add(PowerMineralRegistry.oreItem(mineral).get(),
                    PowerMineralRegistry.deepslateOre(mineral).get().asItem());
            rawMaterials.add(PowerMineralRegistry.rawMaterial(mineral).get());
            if (mineral.hasIngot()) {
                ingots.add(PowerMineralRegistry.ingot(mineral).get());
            }
        }
    }
}

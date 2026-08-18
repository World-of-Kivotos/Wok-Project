package com.miningdim.power.data;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.rubber.PowerRubberRegistry;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.ItemTagsProvider;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.data.BlockTagsProvider;
import net.minecraftforge.common.data.ExistingFileHelper;

import java.util.concurrent.CompletableFuture;

/** 橡胶木按原版木材分类接入燃烧、工具与树木标签。 */
final class PowerRubberTagsProvider extends BlockTagsProvider {

    PowerRubberTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider,
                            ExistingFileHelper existingFiles) {
        super(output, lookupProvider, MiningConstants.MODID, existingFiles);
    }

    @Override
    protected void addTags(HolderLookup.Provider lookupProvider) {
        tag(BlockTags.LOGS).add(PowerRubberRegistry.RUBBER_LOG.get());
        tag(BlockTags.LOGS_THAT_BURN).add(PowerRubberRegistry.RUBBER_LOG.get());
        tag(BlockTags.LEAVES).add(PowerRubberRegistry.RUBBER_LEAVES.get());
        tag(BlockTags.SAPLINGS).add(PowerRubberRegistry.RUBBER_SAPLING.get());
        tag(BlockTags.PLANKS).add(PowerRubberRegistry.RUBBER_PLANKS.get());
        tag(BlockTags.MINEABLE_WITH_AXE).add(PowerRubberRegistry.RUBBER_LOG.get(), PowerRubberRegistry.RUBBER_PLANKS.get());
    }

    @Override
    public String getName() {
        return "橡胶方块标签: " + MiningConstants.MODID;
    }
}

/** 方块标签的物品镜像，供原版燃料和其他数据包配方以木材类别引用。 */
final class PowerRubberItemTagsProvider extends ItemTagsProvider {

    PowerRubberItemTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider,
                                CompletableFuture<TagsProvider.TagLookup<Block>> blockTags,
                                ExistingFileHelper existingFiles) {
        super(output, lookupProvider, blockTags, MiningConstants.MODID, existingFiles);
    }

    @Override
    protected void addTags(HolderLookup.Provider lookupProvider) {
        tag(ItemTags.LOGS).add(PowerRubberRegistry.RUBBER_LOG.get().asItem());
        tag(ItemTags.LOGS_THAT_BURN).add(PowerRubberRegistry.RUBBER_LOG.get().asItem());
        tag(ItemTags.SAPLINGS).add(PowerRubberRegistry.RUBBER_SAPLING.get().asItem());
        tag(ItemTags.PLANKS).add(PowerRubberRegistry.RUBBER_PLANKS.get().asItem());
    }

    @Override
    public String getName() {
        return "橡胶物品标签: " + MiningConstants.MODID;
    }
}

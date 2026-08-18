package com.miningdim.power.data;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.PowerRegistry;
import com.miningdim.power.cable.ConductorMaterial;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.ItemTagsProvider;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.data.BlockTagsProvider;
import net.minecraftforge.common.data.ExistingFileHelper;

import java.util.concurrent.CompletableFuture;

/** P1 线缆方块及物品分类标签。 */
final class PowerCableTagsProvider extends BlockTagsProvider {

    static final TagKey<Block> ENERGY_CABLES = TagKey.create(
            Registries.BLOCK, new ResourceLocation(MiningConstants.MODID, "energy_cables"));
    static final TagKey<Item> ENERGY_CABLE_ITEMS = TagKey.create(
            Registries.ITEM, new ResourceLocation(MiningConstants.MODID, "energy_cables"));
    static final TagKey<Item> CONDUCTOR_WIRES = TagKey.create(
            Registries.ITEM, new ResourceLocation(MiningConstants.MODID, "conductor_wires"));

    PowerCableTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider,
                           ExistingFileHelper existingFiles) {
        super(output, lookupProvider, MiningConstants.MODID, existingFiles);
    }

    @Override
    protected void addTags(HolderLookup.Provider lookupProvider) {
        var cables = tag(ENERGY_CABLES);
        for (ConductorMaterial material : PowerRegistry.CABLES.keySet()) {
            Block block = PowerRegistry.CABLES.get(material).get();
            cables.add(block);
        }
        cables.add(PowerRegistry.TUNGSTEN_HEAT_RESISTANT_CABLE.get());
    }

    @Override
    public String getName() {
        return "能源线缆方块标签: " + MiningConstants.MODID;
    }
}

/** 线缆物品镜像标签，供配方和第三方数据包按类别消费。 */
final class PowerCableItemTagsProvider extends ItemTagsProvider {

    PowerCableItemTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider,
                               CompletableFuture<TagsProvider.TagLookup<Block>> blockTags,
                               ExistingFileHelper existingFiles) {
        super(output, lookupProvider, blockTags, MiningConstants.MODID, existingFiles);
    }

    @Override
    protected void addTags(HolderLookup.Provider lookupProvider) {
        var cables = tag(PowerCableTagsProvider.ENERGY_CABLE_ITEMS);
        var wires = tag(PowerCableTagsProvider.CONDUCTOR_WIRES);
        for (ConductorMaterial material : PowerRegistry.CABLES.keySet()) {
            Item item = PowerRegistry.CABLE_ITEMS.get(material).get();
            cables.add(item);
            wires.add(PowerRegistry.WIRE_ITEMS.get(material).get());
        }
        cables.add(PowerRegistry.TUNGSTEN_HEAT_RESISTANT_CABLE_ITEM.get());
    }

    @Override
    public String getName() {
        return "能源线缆物品标签: " + MiningConstants.MODID;
    }
}

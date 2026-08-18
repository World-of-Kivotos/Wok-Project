package com.miningdim.power.data;

import com.miningdim.power.PowerRegistry;
import com.miningdim.power.cable.ConductorMaterial;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.block.Block;
import net.minecraft.data.loot.BlockLootSubProvider;

import java.util.Set;

/** P1 线缆方块自掉落，保留已有世界中的铁/铜物品 ID。 */
final class PowerCableLootProvider extends BlockLootSubProvider {

    PowerCableLootProvider() {
        super(Set.of(), FeatureFlags.REGISTRY.allFlags());
    }

    @Override
    protected void generate() {
        for (ConductorMaterial material : PowerRegistry.CABLES.keySet()) {
            add(PowerRegistry.CABLES.get(material).get(), this::createSelfDrop);
        }
    }

    private net.minecraft.world.level.storage.loot.LootTable.Builder createSelfDrop(Block block) {
        return createSingleItemTable(block.asItem());
    }

    @Override
    protected Iterable<Block> getKnownBlocks() {
        return PowerRegistry.CABLES.values().stream()
                .map(registryObject -> (Block) registryObject.get())
                .toList();
    }
}

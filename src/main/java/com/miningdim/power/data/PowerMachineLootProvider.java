package com.miningdim.power.data;

import com.miningdim.power.PowerMachineRegistry;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.block.Block;
import net.minecraft.data.loot.BlockLootSubProvider;

import java.util.Set;

/** 提纯机与空分机破坏时掉落自身方块物品。 */
final class PowerMachineLootProvider extends BlockLootSubProvider {

    PowerMachineLootProvider() {
        super(Set.of(), FeatureFlags.REGISTRY.allFlags());
    }

    @Override
    protected void generate() {
        add(PowerMachineRegistry.PURIFIER_BLOCK.get(), this::createSelfDrop);
        add(PowerMachineRegistry.AIR_SEPARATOR_BLOCK.get(), this::createSelfDrop);
    }

    @Override
    protected Iterable<Block> getKnownBlocks() {
        return Set.of(PowerMachineRegistry.PURIFIER_BLOCK.get(), PowerMachineRegistry.AIR_SEPARATOR_BLOCK.get());
    }

    private net.minecraft.world.level.storage.loot.LootTable.Builder createSelfDrop(Block block) {
        return createSingleItemTable(block.asItem());
    }
}

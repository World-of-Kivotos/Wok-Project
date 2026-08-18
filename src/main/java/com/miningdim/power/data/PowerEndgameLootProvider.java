package com.miningdim.power.data;

import com.miningdim.power.PowerRegistry;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.block.Block;

import java.util.Set;

/** 低温控制器破坏后掉落自身。 */
final class PowerEndgameLootProvider extends BlockLootSubProvider {

    PowerEndgameLootProvider() {
        super(Set.of(), FeatureFlags.REGISTRY.allFlags());
    }

    @Override
    protected void generate() {
        add(PowerRegistry.LOW_TEMPERATURE_CONTROLLER.get(), this::createSelfDrop);
    }

    @Override
    protected Iterable<Block> getKnownBlocks() {
        return Set.of(PowerRegistry.LOW_TEMPERATURE_CONTROLLER.get());
    }

    private net.minecraft.world.level.storage.loot.LootTable.Builder createSelfDrop(Block block) {
        return createSingleItemTable(block.asItem());
    }
}

package com.miningdim.power.data;

import com.miningdim.power.mineral.PowerMineral;
import com.miningdim.power.mineral.PowerMineralRegistry;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.data.loot.BlockLootSubProvider;

import java.util.Set;

/** 丝触保留矿石，其余采掘掉落原矿物并应用时运。 */
final class PowerMineralLootProvider extends BlockLootSubProvider {

    PowerMineralLootProvider() {
        super(Set.of(), FeatureFlags.REGISTRY.allFlags());
    }

    @Override
    protected void generate() {
        for (PowerMineral mineral : PowerMineral.values()) {
            Item rawMaterial = PowerMineralRegistry.rawMaterial(mineral).get();
            add(PowerMineralRegistry.ore(mineral).get(), block -> createOreDrop(block, rawMaterial));
            add(PowerMineralRegistry.deepslateOre(mineral).get(), block -> createOreDrop(block, rawMaterial));
        }
    }

    @Override
    protected Iterable<Block> getKnownBlocks() {
        return java.util.Arrays.stream(PowerMineral.values())
                .flatMap(mineral -> java.util.stream.Stream.of(
                        PowerMineralRegistry.ore(mineral).get(),
                        PowerMineralRegistry.deepslateOre(mineral).get()))
                .toList();
    }
}

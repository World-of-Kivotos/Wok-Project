package com.miningdim.power.data;

import com.miningdim.power.rubber.PowerRubberRegistry;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.CopyNbtFunction;
import net.minecraft.world.level.storage.loot.providers.nbt.ContextNbtProvider;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;

import java.util.List;
import java.util.Set;

/** 原木掉落把绝对采胶时间写回 BlockEntityTag，拆放不会绕开 24,000 tick 冷却。 */
final class PowerRubberLootProvider extends BlockLootSubProvider {

    PowerRubberLootProvider() {
        super(Set.of(), FeatureFlags.REGISTRY.allFlags());
    }

    @Override
    protected void generate() {
        add(PowerRubberRegistry.RUBBER_LOG.get(), this::rubberLogDrop);
        dropSelf(PowerRubberRegistry.RUBBER_PLANKS.get());
        add(PowerRubberRegistry.RUBBER_LEAVES.get(), block -> createLeavesDrops(block,
                PowerRubberRegistry.RUBBER_SAPLING.get(), 0.05F));
        dropSelf(PowerRubberRegistry.RUBBER_SAPLING.get());
    }

    private LootTable.Builder rubberLogDrop(Block block) {
        return LootTable.lootTable().withPool(applyExplosionCondition(block, LootPool.lootPool()
                .setRolls(ConstantValue.exactly(1))
                .add(LootItem.lootTableItem(block).apply(CopyNbtFunction.copyData(ContextNbtProvider.BLOCK_ENTITY)
                        .copy("NextTapGameTime", "BlockEntityTag.NextTapGameTime")
                        .copy("WasTapped", "BlockEntityTag.WasTapped")))));
    }

    @Override
    protected Iterable<Block> getKnownBlocks() {
        return List.of(
                PowerRubberRegistry.RUBBER_LOG.get(),
                PowerRubberRegistry.RUBBER_PLANKS.get(),
                PowerRubberRegistry.RUBBER_LEAVES.get(),
                PowerRubberRegistry.RUBBER_SAPLING.get());
    }
}

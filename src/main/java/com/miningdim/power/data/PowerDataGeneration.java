package com.miningdim.power.data;

import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.data.event.GatherDataEvent;

import java.util.List;
import java.util.Set;

/** 能源基础矿物的数据包与资源数据生成入口。 */
public final class PowerDataGeneration {

    private PowerDataGeneration() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(PowerDataGeneration::gatherData);
    }

    private static void gatherData(GatherDataEvent event) {
        var generator = event.getGenerator();
        var output = generator.getPackOutput();
        var existingFiles = event.getExistingFileHelper();

        generator.addProvider(event.includeClient(),
                new PowerMineralBlockStateProvider(output, existingFiles));
        generator.addProvider(event.includeClient(),
                new PowerMineralItemModelProvider(output, existingFiles));
        generator.addProvider(event.includeClient(),
                new PowerRubberBlockStateProvider(output, existingFiles));
        generator.addProvider(event.includeClient(),
                new PowerRubberItemModelProvider(output, existingFiles));
        generator.addProvider(event.includeClient(),
                new PowerCableBlockStateProvider(output, existingFiles));
        generator.addProvider(event.includeClient(),
                new PowerCableItemModelProvider(output, existingFiles));
        generator.addProvider(event.includeClient(),
                new PowerGeneratorItemModelProvider(output, existingFiles));
        generator.addProvider(event.includeClient(),
                new PowerMachineBlockStateProvider(output, existingFiles));
        generator.addProvider(event.includeClient(),
                new PowerMachineItemModelProvider(output, existingFiles));

        generator.addProvider(event.includeServer(),
                new LootTableProvider(output, Set.of(), List.of(
                        new LootTableProvider.SubProviderEntry(PowerMineralLootProvider::new, LootContextParamSets.BLOCK),
                        new LootTableProvider.SubProviderEntry(PowerRubberLootProvider::new, LootContextParamSets.BLOCK),
                        new LootTableProvider.SubProviderEntry(PowerCableLootProvider::new, LootContextParamSets.BLOCK),
                        new LootTableProvider.SubProviderEntry(PowerMachineLootProvider::new, LootContextParamSets.BLOCK))));
        PowerMineralTagsProvider blockTags = generator.addProvider(event.includeServer(),
                new PowerMineralTagsProvider(output, event.getLookupProvider(), existingFiles));
        generator.addProvider(event.includeServer(), new PowerMineralItemTagsProvider(output, event.getLookupProvider(),
                blockTags.contentsGetter(), existingFiles));
        PowerRubberTagsProvider rubberBlockTags = generator.addProvider(event.includeServer(),
                new PowerRubberTagsProvider(output, event.getLookupProvider(), existingFiles));
        generator.addProvider(event.includeServer(), new PowerRubberItemTagsProvider(output, event.getLookupProvider(),
                rubberBlockTags.contentsGetter(), existingFiles));
        PowerCableTagsProvider cableBlockTags = generator.addProvider(event.includeServer(),
                new PowerCableTagsProvider(output, event.getLookupProvider(), existingFiles));
        generator.addProvider(event.includeServer(), new PowerCableItemTagsProvider(output, event.getLookupProvider(),
                cableBlockTags.contentsGetter(), existingFiles));
        generator.addProvider(event.includeServer(), new PowerMineralRecipeProvider(output));
        generator.addProvider(event.includeServer(), new PowerRubberRecipeProvider(output));
        generator.addProvider(event.includeServer(), new PowerCableRecipeProvider(output));
        generator.addProvider(event.includeServer(), new PowerGeneratorRecipeProvider(output));
        generator.addProvider(event.includeServer(), new PowerMachineRecipeProvider(output));
        generator.addProvider(event.includeServer(), new PowerMineralWorldgenProvider(output));
        generator.addProvider(event.includeServer(), new PowerRubberWorldgenProvider(output));
    }
}

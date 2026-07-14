package com.miningdim.power;

import com.miningdim.core.MiningConstants;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Block and item registrations owned by the power subsystem. */
public final class PowerRegistry {

    public static final DeferredRegister<net.minecraft.world.level.block.Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MiningConstants.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MiningConstants.MODID);

    public static final RegistryObject<GeneratorMultiblockBlock> INDUSTRIAL_GENERATOR =
            registerGenerator("industrial_generator");
    public static final RegistryObject<GeneratorMultiblockBlock> MODERN_GENERATOR =
            registerGenerator("modern_generator");
    public static final RegistryObject<GeneratorMultiblockBlock> FUTURE_ENERGY_GENERATOR =
            registerGenerator("future_energy_generator");

    public static final RegistryObject<Item> INDUSTRIAL_GENERATOR_ITEM =
            registerBlockItem("industrial_generator", INDUSTRIAL_GENERATOR);
    public static final RegistryObject<Item> MODERN_GENERATOR_ITEM =
            registerBlockItem("modern_generator", MODERN_GENERATOR);
    public static final RegistryObject<Item> FUTURE_ENERGY_GENERATOR_ITEM =
            registerBlockItem("future_energy_generator", FUTURE_ENERGY_GENERATOR);

    private PowerRegistry() {
    }

    private static RegistryObject<GeneratorMultiblockBlock> registerGenerator(String name) {
        return BLOCKS.register(name, () -> new GeneratorMultiblockBlock(
                BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK)
                        .noOcclusion()
                        .pushReaction(PushReaction.BLOCK)));
    }

    private static RegistryObject<Item> registerBlockItem(
            String name, RegistryObject<GeneratorMultiblockBlock> block) {
        return ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
    }
}

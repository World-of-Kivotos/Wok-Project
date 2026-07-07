package com.miningdim.job.munitions;

import com.miningdim.core.MiningConstants;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

public final class ModMunitionsItems {

    private ModMunitionsItems() {
    }

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MiningConstants.MODID);

    public static final RegistryObject<Item> MUNITIONS_BENCH_ITEM = ITEMS.register("munitions_bench",
            () -> new BlockItem(ModMunitionsBlocks.MUNITIONS_BENCH.get(), new Item.Properties()));
    public static final RegistryObject<Item> MUNITIONS_BENCH_MEDIUM_ITEM = ITEMS.register("munitions_bench_medium",
            () -> new BlockItem(ModMunitionsBlocks.MUNITIONS_BENCH_MEDIUM.get(), new Item.Properties()));
    public static final RegistryObject<Item> MUNITIONS_BENCH_HIGH_ITEM = ITEMS.register("munitions_bench_high",
            () -> new BlockItem(ModMunitionsBlocks.MUNITIONS_BENCH_HIGH.get(), new Item.Properties()));
    public static final RegistryObject<Item> MUNITIONS_BENCH_SUPERIOR_ITEM = ITEMS.register("munitions_bench_superior",
            () -> new BlockItem(ModMunitionsBlocks.MUNITIONS_BENCH_SUPERIOR.get(), new Item.Properties()));
    public static final RegistryObject<Item> MUNITIONS_BENCH_TRANSCENDENT_ITEM = ITEMS.register("munitions_bench_transcendent",
            () -> new BlockItem(ModMunitionsBlocks.MUNITIONS_BENCH_TRANSCENDENT.get(), new Item.Properties()));
    public static final RegistryObject<Item> MUNITIONS_BENCH_RADIANT_ITEM = ITEMS.register("munitions_bench_radiant",
            () -> new BlockItem(ModMunitionsBlocks.MUNITIONS_BENCH_RADIANT.get(), new Item.Properties()));

    public static final List<RegistryObject<Item>> ALL_BENCH_ITEMS = List.of(
            MUNITIONS_BENCH_ITEM,
            MUNITIONS_BENCH_MEDIUM_ITEM,
            MUNITIONS_BENCH_HIGH_ITEM,
            MUNITIONS_BENCH_SUPERIOR_ITEM,
            MUNITIONS_BENCH_TRANSCENDENT_ITEM,
            MUNITIONS_BENCH_RADIANT_ITEM);

    public static final RegistryObject<Item> PRIMER = ITEMS.register("primer",
            () -> new Item(new Item.Properties().stacksTo(64)));

    public static final RegistryObject<Item> CASING = ITEMS.register("casing",
            () -> new Item(new Item.Properties().stacksTo(64)));

    public static final RegistryObject<Item> BULLET_HEAD = ITEMS.register("bullet_head",
            () -> new Item(new Item.Properties().stacksTo(64)));

    public static final RegistryObject<Item> PROPELLANT = ITEMS.register("propellant",
            () -> new Item(new Item.Properties().stacksTo(64)));

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}

package com.miningdim.job.chef;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.chef.station.CookingStationBlock;
import com.miningdim.job.chef.station.CookingStationItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

/**
 * 厨师 BlockItem 的 DeferredRegister holder (5 档调味台与 9 台烹饪台的物品形态; 厨师包自有, 不碰中央 ModItems)。
 *
 * BlockItem 在 lambda 内 .get() (注册后求值, 遵循工程范式禁静态初始化期 .get())。物品名与方块同 id。
 */
public final class ChefItems {

    private ChefItems() {
    }

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MiningConstants.MODID);

    public static final RegistryObject<Item> SEASONING_TABLE_LOW =
            ITEMS.register("seasoning_table_low",
                    () -> new BlockItem(ChefBlocks.SEASONING_TABLE_LOW.get(), new Item.Properties()));
    public static final RegistryObject<Item> SEASONING_TABLE_MEDIUM =
            ITEMS.register("seasoning_table_medium",
                    () -> new BlockItem(ChefBlocks.SEASONING_TABLE_MEDIUM.get(), new Item.Properties()));
    public static final RegistryObject<Item> SEASONING_TABLE_HIGH =
            ITEMS.register("seasoning_table_high",
                    () -> new BlockItem(ChefBlocks.SEASONING_TABLE_HIGH.get(), new Item.Properties()));
    public static final RegistryObject<Item> SEASONING_TABLE_EXTRAORDINARY =
            ITEMS.register("seasoning_table_extraordinary",
                    () -> new BlockItem(ChefBlocks.SEASONING_TABLE_EXTRAORDINARY.get(), new Item.Properties()));
    public static final RegistryObject<Item> SEASONING_TABLE_RADIANT =
            ITEMS.register("seasoning_table_radiant",
                    () -> new BlockItem(ChefBlocks.SEASONING_TABLE_RADIANT.get(), new Item.Properties()));

    // 九台烹饪台的物品形态, 顺序与 ChefBlocks.COOKING_STATIONS 一致; 物品提示写明功能种类与摆放要求。
    public static final RegistryObject<Item> DEEP_FRYER_RUSTIC =
            ITEMS.register("deep_fryer_rustic",
                    () -> new CookingStationItem(station(ChefBlocks.DEEP_FRYER_RUSTIC), new Item.Properties()));
    public static final RegistryObject<Item> DEEP_FRYER_STEEL =
            ITEMS.register("deep_fryer_steel",
                    () -> new CookingStationItem(station(ChefBlocks.DEEP_FRYER_STEEL), new Item.Properties()));
    public static final RegistryObject<Item> DEEP_FRYER_CHINESE =
            ITEMS.register("deep_fryer_chinese",
                    () -> new CookingStationItem(station(ChefBlocks.DEEP_FRYER_CHINESE), new Item.Properties()));
    public static final RegistryObject<Item> BAKING_OVEN_RUSTIC =
            ITEMS.register("baking_oven_rustic",
                    () -> new CookingStationItem(station(ChefBlocks.BAKING_OVEN_RUSTIC), new Item.Properties()));
    public static final RegistryObject<Item> BAKING_OVEN_STEEL =
            ITEMS.register("baking_oven_steel",
                    () -> new CookingStationItem(station(ChefBlocks.BAKING_OVEN_STEEL), new Item.Properties()));
    public static final RegistryObject<Item> BAKING_OVEN_CHINESE =
            ITEMS.register("baking_oven_chinese",
                    () -> new CookingStationItem(station(ChefBlocks.BAKING_OVEN_CHINESE), new Item.Properties()));
    public static final RegistryObject<Item> PREP_COUNTER_RUSTIC =
            ITEMS.register("prep_counter_rustic",
                    () -> new CookingStationItem(station(ChefBlocks.PREP_COUNTER_RUSTIC), new Item.Properties()));
    public static final RegistryObject<Item> PREP_COUNTER_STEEL =
            ITEMS.register("prep_counter_steel",
                    () -> new CookingStationItem(station(ChefBlocks.PREP_COUNTER_STEEL), new Item.Properties()));
    public static final RegistryObject<Item> PREP_COUNTER_CHINESE =
            ITEMS.register("prep_counter_chinese",
                    () -> new CookingStationItem(station(ChefBlocks.PREP_COUNTER_CHINESE), new Item.Properties()));

    /** 九台烹饪台物品, 顺序同 {@link ChefBlocks#COOKING_STATIONS}。 */
    public static final List<RegistryObject<Item>> COOKING_STATIONS = List.of(
            DEEP_FRYER_RUSTIC, DEEP_FRYER_STEEL, DEEP_FRYER_CHINESE,
            BAKING_OVEN_RUSTIC, BAKING_OVEN_STEEL, BAKING_OVEN_CHINESE,
            PREP_COUNTER_RUSTIC, PREP_COUNTER_STEEL, PREP_COUNTER_CHINESE);

    private static CookingStationBlock station(RegistryObject<Block> block) {
        return (CookingStationBlock) block.get();
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}

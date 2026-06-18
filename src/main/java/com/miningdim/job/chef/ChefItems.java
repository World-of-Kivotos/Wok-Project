package com.miningdim.job.chef;

import com.miningdim.core.MiningConstants;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 厨师 BlockItem 的 DeferredRegister holder (5 档调味台对应的物品形态; 厨师包自有, 不碰中央 ModItems)。
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

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}

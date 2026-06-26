package com.miningdim.registry;

import com.miningdim.core.MiningConstants;
import com.miningdim.marriage.RingItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 物品的 DeferredRegister holder (设计文档 5.2)。阶段0 仅注册与 ModBlocks 对应的 BlockItem,
 * 使方块可获取/可放置。后续子系统 (如进入道具) 在各自 package 追加, 不改本文件。
 *
 * 注意: BlockItem 构造在 lambda 内调用 RegistryObject.get(), 仅在注册完成后求值, 不在静态初始化期 .get() (5.2)。
 */
public final class ModItems {

    private ModItems() {
    }

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MiningConstants.MODID);

    public static final RegistryObject<Item> MINING_PORTAL_ITEM =
            ITEMS.register("mining_portal",
                    () -> new BlockItem(ModBlocks.MINING_PORTAL.get(), new Item.Properties()));

    public static final RegistryObject<Item> FAKE_ORE_ITEM =
            ITEMS.register("fake_ore",
                    () -> new BlockItem(ModBlocks.FAKE_ORE.get(), new Item.Properties()));

    // ---- R4 难度入口方块的 BlockItem ----

    public static final RegistryObject<Item> ENTRANCE_EASY_ITEM =
            ITEMS.register("entrance_easy",
                    () -> new BlockItem(ModBlocks.ENTRANCE_EASY.get(), new Item.Properties()));

    public static final RegistryObject<Item> ENTRANCE_MEDIUM_ITEM =
            ITEMS.register("entrance_medium",
                    () -> new BlockItem(ModBlocks.ENTRANCE_MEDIUM.get(), new Item.Properties()));

    public static final RegistryObject<Item> ENTRANCE_HARD_ITEM =
            ITEMS.register("entrance_hard",
                    () -> new BlockItem(ModBlocks.ENTRANCE_HARD.get(), new Item.Properties()));

    // ---- 结婚系统戒指 (结婚系统 spec 第三章: 订婚/结婚两个独立 Item, NBT 盖章身份) ----

    public static final RegistryObject<Item> ENGAGEMENT_RING =
            ITEMS.register("engagement_ring",
                    () -> new RingItem(new Item.Properties(), true));

    public static final RegistryObject<Item> WEDDING_RING =
            ITEMS.register("wedding_ring",
                    () -> new RingItem(new Item.Properties(), false));

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}

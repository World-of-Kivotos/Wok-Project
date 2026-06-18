package com.miningdim.job.tarot;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.tarot.craft.TarotCraftBlock;
import com.miningdim.job.tarot.craft.TarotCraftBlockEntity;
import com.miningdim.job.tarot.craft.TarotCraftMenu;
import com.miningdim.job.tarot.pack.PackKind;
import com.miningdim.job.tarot.pack.ShinyPackSelectMenu;
import com.miningdim.job.tarot.pack.TarotPackItem;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import com.miningdim.menu.ModMenus;

/**
 * 塔罗师专属 DeferredRegister 持有 (任务铁律: 严禁改中央 ModItems/ModBlocks/ModCreativeTabs; 各子系统自持)。
 * Item / Block / BlockEntityType / MenuType / CreativeModeTab 全在本包注册, 由 {@link TarotSystem} 在
 * register 内 .register(modBus)。
 */
public final class TarotRegistry {

    private TarotRegistry() {
    }

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MiningConstants.MODID);
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MiningConstants.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MiningConstants.MODID);

    // ---- 物品 ----

    /** 单一卡牌 Item (NBT 三键 + 绑定; spec 第三章)。 */
    public static final RegistryObject<Item> TAROT_CARD =
            ITEMS.register("tarot_card", () -> new TarotCardItem(new Item.Properties()));

    /** 塔罗碎片 (重复牌转化 / 破碎返还; spec 第七/八章)。 */
    public static final RegistryObject<Item> TAROT_SHARD =
            ITEMS.register("tarot_shard", () -> new Item(new Item.Properties()));

    /** 普通卡包 (信用点购买; 1 张 R)。 */
    public static final RegistryObject<Item> PACK_COMMON =
            ITEMS.register("tarot_pack_common", () -> new TarotPackItem(new Item.Properties(), PackKind.COMMON));

    /** 高级卡包 (信用点购买; 3 张 SR/SSR + 派生)。 */
    public static final RegistryObject<Item> PACK_ADVANCED =
            ITEMS.register("tarot_pack_advanced", () -> new TarotPackItem(new Item.Properties(), PackKind.ADVANCED));

    /** 闪耀卡包 (青辉石/掉落; 开出自选 1 张 SSR)。 */
    public static final RegistryObject<Item> PACK_SHINY =
            ITEMS.register("tarot_pack_shiny", () -> new TarotPackItem(new Item.Properties(), PackKind.SHINY));

    // ---- 方块 + 方块实体 (合成台) ----

    public static final RegistryObject<Block> CRAFT_TABLE =
            BLOCKS.register("tarot_craft_table",
                    () -> new TarotCraftBlock(BlockBehaviour.Properties.copy(Blocks.LECTERN).noOcclusion()));

    /** 合成台的 BlockItem (放进创造栏)。 */
    public static final RegistryObject<Item> CRAFT_TABLE_ITEM =
            ITEMS.register("tarot_craft_table",
                    () -> new net.minecraft.world.item.BlockItem(CRAFT_TABLE.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<TarotCraftBlockEntity>> CRAFT_TABLE_BE =
            BLOCK_ENTITIES.register("tarot_craft_table",
                    () -> BlockEntityType.Builder.of(TarotCraftBlockEntity::new, CRAFT_TABLE.get()).build(null));

    // ---- MenuType (经公共 menu 脚手架工厂) ----

    public static final RegistryObject<MenuType<TarotCraftMenu>> CRAFT_MENU =
            ModMenus.MENUS.register("tarot_craft",
                    () -> ModMenus.blockMenuType(TarotCraftMenu::new));

    public static final RegistryObject<MenuType<ShinyPackSelectMenu>> SHINY_SELECT_MENU =
            ModMenus.MENUS.register("tarot_shiny_select",
                    () -> ModMenus.remoteMenuType(ShinyPackSelectMenu::new));

    /** 接 modBus (在 TarotSystem.register 内调用一次; MenuType 经 ModMenus.register 由框架统一接 modBus)。 */
    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        BLOCKS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }
}

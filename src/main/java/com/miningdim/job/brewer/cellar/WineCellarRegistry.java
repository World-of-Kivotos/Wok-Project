package com.miningdim.job.brewer.cellar;

import com.miningdim.core.MiningConstants;
import com.miningdim.menu.ModMenus;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 酒窖箱注册 holder (酿酒师 阶段 4; 酿酒师包自有 DeferredRegister, 不碰中央 ModBlocks/ModItems)。
 *
 * 单一方块 wine_cellar: Block + BlockEntityType + BlockItem(同 id) + MenuType。MenuType 走共享中央
 * {@link ModMenus#MENUS} (其 .register(modBus) 由 JobFrameworkSystem 调; 本类只往其上登记, 集成者经 touch
 * {@link #WINE_CELLAR_MENU} 确保静态登记被收集进 pending map)。Block/Item/BE 三个本地 DeferredRegister 由
 * {@link #register(IEventBus)} 接 modBus。
 *
 * 属性 copy 原版 BARREL (木桶观感, 贴酒窖储酒主题; 需斧, 可燃但非高危); 真贴图待补, 模型 JSON 暂用占位纹理。
 */
public final class WineCellarRegistry {

    private WineCellarRegistry() {
    }

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MiningConstants.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MiningConstants.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MiningConstants.MODID);

    /** 酒窖箱方块。 */
    public static final RegistryObject<Block> WINE_CELLAR =
            BLOCKS.register("wine_cellar",
                    () -> new WineCellarBlock(BlockBehaviour.Properties.copy(Blocks.BARREL)));

    /** 酒窖箱方块物品 (创造栏 / 掉落用; 与方块同 id)。 */
    public static final RegistryObject<Item> WINE_CELLAR_ITEM =
            ITEMS.register("wine_cellar",
                    () -> new BlockItem(WINE_CELLAR.get(), new Item.Properties()));

    /** 酒窖箱方块实体类型 (valid block = 酒窖箱; 注册后求值, 遵循禁静态初始化期 .get())。 */
    public static final RegistryObject<BlockEntityType<WineCellarBlockEntity>> WINE_CELLAR_BE =
            BLOCK_ENTITIES.register("wine_cellar",
                    () -> BlockEntityType.Builder.of(WineCellarBlockEntity::new, WINE_CELLAR.get()).build(null));

    /** 酒窖箱 MenuType (经共享 ModMenus 方块工厂; extraData 首读 BlockPos 与 use 的 writeBlockPos 对应)。 */
    public static final RegistryObject<MenuType<WineCellarMenu>> WINE_CELLAR_MENU =
            ModMenus.MENUS.register("wine_cellar",
                    () -> ModMenus.blockMenuType(WineCellarMenu::new));

    /** 接 modBus (酒窖箱自有的 Block/Item/BE 三个 DeferredRegister; MenuType 走共享 ModMenus 不在此接)。 */
    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }
}

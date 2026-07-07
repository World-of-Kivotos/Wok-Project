package com.miningdim.job.brewer.station;

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
 * 酿酒台注册 holder (酿酒师 阶段 3; 酿酒师包自有 DeferredRegister, 不碰中央 ModBlocks/ModItems)。
 *
 * 持: Block + BlockItem + BlockEntityType 三个自有 DeferredRegister; MenuType 经共享 {@link ModMenus#MENUS}
 * 中央 DeferredRegister 登记 (与厨师 ChefMenus 同范式, 故 MenuType 的 register 由 JobFrameworkSystem 接 modBus,
 * 本类只往其上登记)。
 *
 * 集成者经 {@link #register(IEventBus)} 接通三个自有 DeferredRegister; 经 {@link #STATION_MENU} touch 确保
 * MenuType 静态登记被收集; 经 {@link #STATION_ITEM} 把 BlockItem 加进酿酒师创造栏。
 *
 * 属性 copy 原版 BREWING_STAND (酿造观感, 需镐, 不可活塞推); 不需新 PNG (模型 JSON 引用占位 vanilla 纹理,
 * 真贴图待补, 见 openIssues)。
 */
public final class BrewingStationRegistry {

    private BrewingStationRegistry() {
    }

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MiningConstants.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MiningConstants.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MiningConstants.MODID);

    /** 酿酒台方块 (单一; id = brewing_station)。属性 copy 原版酿造台。 */
    public static final RegistryObject<Block> STATION_BLOCK =
            BLOCKS.register("brewing_station",
                    () -> new BrewingStationBlock(BlockBehaviour.Properties.copy(Blocks.BREWING_STAND).noOcclusion()));

    /** 酿酒台 BlockItem (与方块同 id; 供集成者加进酿酒师创造栏)。 */
    public static final RegistryObject<Item> STATION_ITEM =
            ITEMS.register("brewing_station",
                    () -> new BlockItem(STATION_BLOCK.get(), new Item.Properties()));

    /** 酿酒台 BlockEntityType (valid block = 酿酒台方块; 单一 ticker 适配)。 */
    public static final RegistryObject<BlockEntityType<BrewingStationBlockEntity>> STATION_BE =
            BLOCK_ENTITIES.register("brewing_station",
                    () -> BlockEntityType.Builder.of(BrewingStationBlockEntity::new, STATION_BLOCK.get()).build(null));

    /**
     * 酿酒台 MenuType (经共享中央 {@link ModMenus#MENUS} 登记; extraData 首读 BlockPos, 与
     * {@link BrewingStationBlock} 的 openScreen 写入对应)。集成者经 touch 本字段确保静态登记被收集进 pending map。
     */
    public static final RegistryObject<MenuType<BrewingStationMenu>> STATION_MENU =
            ModMenus.MENUS.register("brewing_station",
                    () -> ModMenus.blockMenuType(BrewingStationMenu::new));

    /** 接通三个自有 DeferredRegister 到 modBus (注册顺序: Block -> Item (依赖 Block) -> BlockEntity (依赖 Block))。 */
    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }
}

package com.miningdim.donation;

import com.miningdim.core.MiningConstants;
import com.miningdim.menu.ModMenus;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 捐赠箱注册 holder: 模块自持 Block/Item/BlockEntityType 三个 DeferredRegister, 两个 MenuType 按仓库惯例登记在
 * 共享的 {@link ModMenus#MENUS} 上 (它由职业框架统一接 modBus, 本类只往上登记, 模块入口 touch 字段确保类已加载)。
 *
 * 方块属性从零写而不是 copy 原版木桶: 木桶带 ignitedByLava, 而捐赠箱是公共工程的物资池, 不该有被岩浆点燃的
 * 破坏路径。爆炸抗性取黑曜石量级 1200; 活塞推不动 (PushReaction.BLOCK), 否则推一格就能把方块实体连同仓库挪到
 * 别人的领地里。
 *
 * 硬度写成 -1 (与基岩同口径, 即"不可破坏"): 部分非玩家破坏路径只看硬度, 不经过 BreakEvent 也不问
 * canEntityDestroy, onRemove 又无法否决移除, 所以只能在硬度这一层把它们挡住。箱主与 OP 的挖掘进度由
 * {@link DonationBoxBlock#getDestroyProgress} 按名义硬度 {@link #NOMINAL_HARDNESS} 自行计算, 手感与木桶相同。
 */
public final class DonationRegistry {

    private DonationRegistry() {
    }

    /** 爆炸抗性: 与黑曜石同量级。真正的"炸不掉"由 {@link DonationBoxBlock#onBlockExploded} 兜底, 这里只挡射线。 */
    public static final float EXPLOSION_RESISTANCE = 1200.0F;

    /** 方块属性里的硬度: -1 即不可破坏, 挡住只看硬度的非玩家破坏路径 (见类注释)。 */
    public static final float BLOCK_HARDNESS = -1.0F;

    /** 名义硬度: 箱主与 OP 的挖掘进度按它计算 (与木桶同 2.5, 徒手约 4 秒)。 */
    public static final float NOMINAL_HARDNESS = 2.5F;

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MiningConstants.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MiningConstants.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MiningConstants.MODID);

    public static final RegistryObject<Block> DONATION_BOX = BLOCKS.register("donation_box",
            () -> new DonationBoxBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .instrument(NoteBlockInstrument.BASS)
                    .strength(BLOCK_HARDNESS, EXPLOSION_RESISTANCE)
                    .sound(SoundType.WOOD)
                    .pushReaction(PushReaction.BLOCK)));

    /** 方块物品: 普通 BlockItem, 不带任何内容物 (掉落走战利品表的纯物品条目)。 */
    public static final RegistryObject<Item> DONATION_BOX_ITEM = ITEMS.register("donation_box",
            () -> new BlockItem(DONATION_BOX.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<DonationBoxBlockEntity>> DONATION_BOX_BE =
            BLOCK_ENTITIES.register("donation_box",
                    () -> BlockEntityType.Builder.of(DonationBoxBlockEntity::new, DONATION_BOX.get()).build(null));

    /** 捐赠者界面: 3x3 投入格 + 玩家背包。extraData 首读 BlockPos, 与 use 里的 writeBlockPos 对应。 */
    public static final RegistryObject<MenuType<DonationDonorMenu>> DONOR_MENU =
            ModMenus.MENUS.register("donation_box_donor", () -> ModMenus.blockMenuType(DonationDonorMenu::new));

    /** 管理界面: 54 格内部仓库 + 玩家背包, 仅箱主/协管/OP 可开。 */
    public static final RegistryObject<MenuType<DonationManagerMenu>> MANAGER_MENU =
            ModMenus.MENUS.register("donation_box_manager", () -> ModMenus.blockMenuType(DonationManagerMenu::new));

    /** 接 modBus: 本模块自有的三个 DeferredRegister (MenuType 走共享 ModMenus, 不在此接)。 */
    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }
}

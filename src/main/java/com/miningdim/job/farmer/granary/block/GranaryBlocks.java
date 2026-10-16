package com.miningdim.job.farmer.granary.block;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.farmer.granary.GranaryConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 粮仓方块的注册 (Spoilage_Granary_DesignSpec「部件」)。
 *
 * 本片只注册储粮囤; 账台与气窗/冷源随各自的分片加入。注册 ID {@code granary_bin} 一旦发布不能改。
 */
public final class GranaryBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MiningConstants.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MiningConstants.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MiningConstants.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MiningConstants.MODID);

    /** 储粮囤: 27 格, 谁放下就归谁。 */
    public static final RegistryObject<Block> BIN = BLOCKS.register("granary_bin",
            () -> new GranaryBinBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.0F, 1200.0F)
                    // 推不动: 归属按坐标记, 被推走会把记录孤儿在旧坐标 (与耕地同一条理由)。
                    .pushReaction(PushReaction.BLOCK)
                    .sound(SoundType.WOOD)
                    .noOcclusion()));

    public static final RegistryObject<Item> BIN_ITEM = ITEMS.register("granary_bin",
            () -> new BlockItem(BIN.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<GranaryBinBlockEntity>> BIN_ENTITY =
            BLOCK_ENTITIES.register("granary_bin",
                    () -> BlockEntityType.Builder.of(GranaryBinBlockEntity::new, BIN.get()).build(null));

    /** 账台: 粮仓的大脑, 本身不装东西; 可以嵌在墙上。 */
    public static final RegistryObject<Block> CENTER = BLOCKS.register("granary_center",
            () -> new GranaryCenterBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.0F, 1200.0F)
                    .pushReaction(PushReaction.BLOCK)
                    .sound(SoundType.WOOD)));

    public static final RegistryObject<Item> CENTER_ITEM = ITEMS.register("granary_center",
            () -> new BlockItem(CENTER.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<GranaryCenterBlockEntity>> CENTER_ENTITY =
            BLOCK_ENTITIES.register("granary_center",
                    () -> BlockEntityType.Builder.of(GranaryCenterBlockEntity::new, CENTER.get()).build(null));

    /** 气窗: 嵌在外墙上, 每 4 个储粮囤要 1 扇; 判定时算墙。 */
    public static final RegistryObject<Block> VENT = BLOCKS.register("granary_vent",
            () -> new GranaryVentBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.0F)
                    .sound(SoundType.WOOD)
                    .noOcclusion()));

    public static final RegistryObject<Item> VENT_ITEM = ITEMS.register("granary_vent",
            () -> new BlockItem(VENT.get(), new Item.Properties()));

    /** 农夫模块自己的创造页 (粮仓部件都进这里, 不碰中央创造栏)。 */
    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("farmer_granary",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.miningdim.granary"))
                    .icon(() -> new ItemStack(BIN_ITEM.get()))
                    .displayItems((params, output) -> {
                        output.accept(CENTER_ITEM.get());
                        output.accept(BIN_ITEM.get());
                        output.accept(VENT_ITEM.get());
                    })
                    .build());

    private GranaryBlocks() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        TABS.register(modBus);
    }

    /** 储粮囤的容量上限 (提示文案用)。 */
    public static int binSlots() {
        return GranaryConstants.BIN_SLOTS;
    }
}

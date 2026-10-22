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

import java.util.ArrayList;
import java.util.List;

/** 粮仓方块与各木材外观变体的注册。 */
public final class GranaryBlocks {

    private static final List<String> WOOD_NAMES = List.of("oak", "spruce", "birch", "jungle", "acacia",
            "dark_oak", "mangrove", "cherry", "bamboo", "crimson", "warped");

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MiningConstants.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MiningConstants.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MiningConstants.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MiningConstants.MODID);

    public static final RegistryObject<Block> BIN = registerBin("granary_bin");
    public static final RegistryObject<Item> BIN_ITEM = registerItem("granary_bin", BIN);
    public static final RegistryObject<Block> CENTER = registerCenter("granary_center");
    public static final RegistryObject<Item> CENTER_ITEM = registerItem("granary_center", CENTER);
    public static final RegistryObject<Block> VENT = registerVent("granary_vent");
    public static final RegistryObject<Item> VENT_ITEM = registerItem("granary_vent", VENT);

    /** 顺序与创造栏分组一致；橡木沿用已发布的三个注册 ID。 */
    public static final List<WoodVariant> WOOD_VARIANTS = registerWoodVariants();

    public static final RegistryObject<BlockEntityType<GranaryBinBlockEntity>> BIN_ENTITY =
            BLOCK_ENTITIES.register("granary_bin", () -> BlockEntityType.Builder.of(GranaryBinBlockEntity::new,
                    WOOD_VARIANTS.stream().map(variant -> variant.bin().get()).toArray(Block[]::new)).build(null));
    public static final RegistryObject<BlockEntityType<GranaryCenterBlockEntity>> CENTER_ENTITY =
            BLOCK_ENTITIES.register("granary_center", () -> BlockEntityType.Builder.of(GranaryCenterBlockEntity::new,
                    WOOD_VARIANTS.stream().map(variant -> variant.center().get()).toArray(Block[]::new)).build(null));

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("farmer_granary",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.miningdim.granary"))
                    .icon(() -> new ItemStack(BIN_ITEM.get()))
                    .displayItems((params, output) -> WOOD_VARIANTS.forEach(variant -> {
                        output.accept(variant.centerItem().get());
                        output.accept(variant.binItem().get());
                        output.accept(variant.ventItem().get());
                    }))
                    .build());

    private GranaryBlocks() {
    }

    private static List<WoodVariant> registerWoodVariants() {
        List<WoodVariant> variants = new ArrayList<>(WOOD_NAMES.size());
        variants.add(new WoodVariant("oak", BIN, CENTER, VENT, BIN_ITEM, CENTER_ITEM, VENT_ITEM));
        for (String wood : WOOD_NAMES.subList(1, WOOD_NAMES.size())) {
            String prefix = wood + "_granary_";
            RegistryObject<Block> bin = registerBin(prefix + "bin");
            RegistryObject<Block> center = registerCenter(prefix + "center");
            RegistryObject<Block> vent = registerVent(prefix + "vent");
            variants.add(new WoodVariant(wood, bin, center, vent,
                    registerItem(prefix + "bin", bin), registerItem(prefix + "center", center),
                    registerItem(prefix + "vent", vent)));
        }
        return List.copyOf(variants);
    }

    private static RegistryObject<Block> registerBin(String id) {
        return BLOCKS.register(id, () -> new GranaryBinBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD)
                .strength(2.0F, 1200.0F)
                .pushReaction(PushReaction.BLOCK)
                .sound(SoundType.WOOD)
                .noOcclusion()));
    }

    private static RegistryObject<Block> registerCenter(String id) {
        return BLOCKS.register(id, () -> new GranaryCenterBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD)
                .strength(2.0F, 1200.0F)
                .pushReaction(PushReaction.BLOCK)
                .sound(SoundType.WOOD)
                .noOcclusion()));
    }

    private static RegistryObject<Block> registerVent(String id) {
        return BLOCKS.register(id, () -> new GranaryVentBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD)
                .strength(2.0F)
                .sound(SoundType.WOOD)
                .noOcclusion()));
    }

    private static RegistryObject<Item> registerItem(String id, RegistryObject<Block> block) {
        return ITEMS.register(id, () -> new BlockItem(block.get(), new Item.Properties()));
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        TABS.register(modBus);
    }

    public static int binSlots() {
        return GranaryConstants.BIN_SLOTS;
    }

    public record WoodVariant(String wood, RegistryObject<Block> bin, RegistryObject<Block> center,
                              RegistryObject<Block> vent, RegistryObject<Item> binItem,
                              RegistryObject<Item> centerItem, RegistryObject<Item> ventItem) {
    }
}

package com.miningdim.job.farmer.granary.block;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.farmer.granary.GranaryConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
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
import java.util.Set;

/** 粮仓方块与各木材、石材外观变体的注册。 */
public final class GranaryBlocks {

    private static final List<String> WOOD_NAMES = List.of("oak", "spruce", "birch", "jungle", "acacia",
            "dark_oak", "mangrove", "cherry", "bamboo", "crimson", "warped");
    private static final List<String> STONE_NAMES = List.of("quartz", "smooth_quartz", "stone", "smooth_stone",
            "cobblestone", "mossy_cobblestone", "stone_bricks", "mossy_stone_bricks", "bricks", "deepslate",
            "cobbled_deepslate", "deepslate_bricks", "deepslate_tiles", "blackstone", "polished_blackstone",
            "polished_blackstone_bricks", "sandstone", "smooth_sandstone", "red_sandstone", "smooth_red_sandstone");
    private static final Set<String> WOOD_NAME_SET = Set.copyOf(WOOD_NAMES);

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MiningConstants.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MiningConstants.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MiningConstants.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MiningConstants.MODID);

    public static final RegistryObject<Block> BIN = registerBin("granary_bin", true);
    public static final RegistryObject<Item> BIN_ITEM = registerItem("granary_bin", BIN, GranaryBlockItem.Kind.BIN);
    public static final RegistryObject<Block> CENTER = registerCenter("granary_center", true);
    public static final RegistryObject<Item> CENTER_ITEM = registerItem("granary_center", CENTER, GranaryBlockItem.Kind.CENTER);
    public static final RegistryObject<Block> VENT = registerVent("granary_vent", true);
    public static final RegistryObject<Item> VENT_ITEM = registerItem("granary_vent", VENT, GranaryBlockItem.Kind.VENT);

    /** 顺序与创造栏分组一致；橡木沿用已发布的三个注册 ID。 */
    public static final List<MaterialVariant> MATERIAL_VARIANTS = registerMaterialVariants();
    public static final List<MaterialVariant> WOOD_VARIANTS = MATERIAL_VARIANTS.stream()
            .filter(MaterialVariant::wood).toList();

    public static final RegistryObject<BlockEntityType<GranaryBinBlockEntity>> BIN_ENTITY =
            BLOCK_ENTITIES.register("granary_bin", () -> BlockEntityType.Builder.of(GranaryBinBlockEntity::new,
                    MATERIAL_VARIANTS.stream().map(variant -> variant.bin().get()).toArray(Block[]::new)).build(null));
    public static final RegistryObject<BlockEntityType<GranaryCenterBlockEntity>> CENTER_ENTITY =
            BLOCK_ENTITIES.register("granary_center", () -> BlockEntityType.Builder.of(GranaryCenterBlockEntity::new,
                    MATERIAL_VARIANTS.stream().map(variant -> variant.center().get()).toArray(Block[]::new)).build(null));
    public static final RegistryObject<BlockEntityType<GranaryVentBlockEntity>> VENT_ENTITY =
            BLOCK_ENTITIES.register("granary_vent", () -> BlockEntityType.Builder.of(GranaryVentBlockEntity::new,
                    MATERIAL_VARIANTS.stream().map(variant -> variant.vent().get()).toArray(Block[]::new)).build(null));

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("farmer_granary",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.miningdim.granary"))
                    .icon(() -> new ItemStack(BIN_ITEM.get()))
                    .displayItems((params, output) -> MATERIAL_VARIANTS.forEach(variant -> {
                        output.accept(variant.centerItem().get());
                        output.accept(variant.binItem().get());
                        output.accept(variant.ventItem().get());
                    }))
                    .build());

    private GranaryBlocks() {
    }

    private static List<MaterialVariant> registerMaterialVariants() {
        List<MaterialVariant> variants = new ArrayList<>(WOOD_NAMES.size() + STONE_NAMES.size());
        variants.add(new MaterialVariant("oak", true, BIN, CENTER, VENT, BIN_ITEM, CENTER_ITEM, VENT_ITEM));
        for (String material : concat(WOOD_NAMES.subList(1, WOOD_NAMES.size()), STONE_NAMES)) {
            boolean wood = WOOD_NAME_SET.contains(material);
            String prefix = material + "_granary_";
            RegistryObject<Block> bin = registerBin(prefix + "bin", wood);
            RegistryObject<Block> center = registerCenter(prefix + "center", wood);
            RegistryObject<Block> vent = registerVent(prefix + "vent", wood);
            variants.add(new MaterialVariant(material, wood, bin, center, vent,
                    registerItem(prefix + "bin", bin, GranaryBlockItem.Kind.BIN),
                    registerItem(prefix + "center", center, GranaryBlockItem.Kind.CENTER),
                    registerItem(prefix + "vent", vent, GranaryBlockItem.Kind.VENT)));
        }
        return List.copyOf(variants);
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> result = new ArrayList<>(first.size() + second.size());
        result.addAll(first);
        result.addAll(second);
        return result;
    }

    private static RegistryObject<Block> registerBin(String id, boolean wood) {
        return BLOCKS.register(id, () -> new GranaryBinBlock(materialProperties(wood)
                .strength(2.0F, 1200.0F).pushReaction(PushReaction.BLOCK).noOcclusion()));
    }

    private static RegistryObject<Block> registerCenter(String id, boolean wood) {
        return BLOCKS.register(id, () -> new GranaryCenterBlock(materialProperties(wood)
                .strength(2.0F, 1200.0F).pushReaction(PushReaction.BLOCK).noOcclusion()));
    }

    private static RegistryObject<Block> registerVent(String id, boolean wood) {
        return BLOCKS.register(id, () -> new GranaryVentBlock(materialProperties(wood).strength(2.0F).noOcclusion()));
    }

    private static BlockBehaviour.Properties materialProperties(boolean wood) {
        return BlockBehaviour.Properties.of()
                .mapColor(wood ? MapColor.WOOD : MapColor.STONE)
                .sound(wood ? SoundType.WOOD : SoundType.STONE);
    }

    private static RegistryObject<Item> registerItem(String id, RegistryObject<Block> block, GranaryBlockItem.Kind kind) {
        return ITEMS.register(id, () -> new GranaryBlockItem(block.get(), new Item.Properties(), kind));
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

    public record MaterialVariant(String material, boolean wood, RegistryObject<Block> bin,
                                  RegistryObject<Block> center, RegistryObject<Block> vent,
                                  RegistryObject<Item> binItem, RegistryObject<Item> centerItem,
                                  RegistryObject<Item> ventItem) {
    }
}

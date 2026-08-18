package com.miningdim.power;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.cable.ConductorMaterial;
import com.miningdim.power.cable.EnergyCableBlock;
import com.miningdim.power.cable.EnergyCableBlockEntity;
import com.miningdim.power.cable.SpecialCableMaterial;
import com.miningdim.power.endgame.LowTemperatureControllerBlock;
import com.miningdim.power.endgame.LowTemperatureControllerBlockEntity;
import com.miningdim.power.endgame.LowTemperatureControllerMenu;
import com.miningdim.power.generator.GeneratorBlockEntity;
import com.miningdim.power.generator.GeneratorFuelCoreItem;
import com.miningdim.power.generator.GeneratorMenu;
import com.miningdim.power.generator.GeneratorPortBlockEntity;
import com.miningdim.power.generator.GeneratorSpec;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Block and item registrations owned by the power subsystem. */
public final class PowerRegistry {

    public static final DeferredRegister<net.minecraft.world.level.block.Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MiningConstants.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MiningConstants.MODID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, MiningConstants.MODID);

    public static final RegistryObject<GeneratorMultiblockBlock> INDUSTRIAL_GENERATOR =
            registerGenerator("industrial_generator", GeneratorSpec.LOW);
    public static final RegistryObject<GeneratorMultiblockBlock> MODERN_GENERATOR =
            registerGenerator("modern_generator", GeneratorSpec.MEDIUM);
    public static final RegistryObject<GeneratorMultiblockBlock> FUTURE_ENERGY_GENERATOR =
            registerGenerator("future_energy_generator", GeneratorSpec.HIGH);

    public static final RegistryObject<Item> INDUSTRIAL_GENERATOR_ITEM =
            registerBlockItem("industrial_generator", INDUSTRIAL_GENERATOR);
    public static final RegistryObject<Item> MODERN_GENERATOR_ITEM =
            registerBlockItem("modern_generator", MODERN_GENERATOR);
    public static final RegistryObject<Item> FUTURE_ENERGY_GENERATOR_ITEM =
            registerBlockItem("future_energy_generator", FUTURE_ENERGY_GENERATOR);

    public static final RegistryObject<Item> INDUSTRIAL_FUEL_CORE = ITEMS.register(
            "industrial_fuel_core", () -> new GeneratorFuelCoreItem(GeneratorSpec.LOW));
    public static final RegistryObject<Item> MODERN_FUEL_CORE = ITEMS.register(
            "modern_fuel_core", () -> new GeneratorFuelCoreItem(GeneratorSpec.MEDIUM));
    public static final RegistryObject<Item> FUTURE_FUEL_CORE = ITEMS.register(
            "future_fuel_core", () -> new GeneratorFuelCoreItem(GeneratorSpec.HIGH));
    public static final RegistryObject<Item> NICHROME_FUSE = ITEMS.register(
            "nichrome_fuse", () -> new Item(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> GRAPHENE_SHEET = ITEMS.register(
            "graphene_sheet", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> SUPERCONDUCTOR_PRECURSOR = ITEMS.register(
            "superconductor_precursor", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> NBTI_CONDUCTOR = ITEMS.register(
            "nbti_conductor", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> YBCO_TAPE = ITEMS.register(
            "ybco_tape", () -> new Item(new Item.Properties()));

    // ---- 有线 FE 线缆 (导体材料数据驱动, 逻辑在 com.miningdim.power.cable/grid) ----

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MiningConstants.MODID);

    /** P1 保留的三档导体集合，供阶段性兼容断言和配方门槛使用。 */
    public static final List<ConductorMaterial> P1_MATERIALS = List.of(
            ConductorMaterial.IRON, ConductorMaterial.ALUMINUM, ConductorMaterial.COPPER);

    /** 当前已落地注册的 P1-P2 导体。网络与方块实体继续统一通过 {@link ConductorMaterial} 参数化。 */
    public static final List<ConductorMaterial> REGISTERED_MATERIALS = List.of(
            ConductorMaterial.IRON,
            ConductorMaterial.ALUMINUM,
            ConductorMaterial.COPPER,
            ConductorMaterial.TINNED_COPPER,
            ConductorMaterial.OFC_COPPER,
            ConductorMaterial.OFE_COPPER,
            ConductorMaterial.SILVER_PLATED_COPPER,
            ConductorMaterial.GOLD,
            ConductorMaterial.SILVER,
            ConductorMaterial.GRAPHENE,
            ConductorMaterial.NBTI_SUPERCONDUCTOR,
            ConductorMaterial.YBCO_SUPERCONDUCTOR);

    public static final Map<ConductorMaterial, RegistryObject<EnergyCableBlock>> CABLES = registerCables();
    public static final Map<ConductorMaterial, RegistryObject<Item>> CABLE_ITEMS = registerCableItems();
    public static final Map<ConductorMaterial, RegistryObject<Item>> WIRE_ITEMS = registerWireItems();

    public static final RegistryObject<EnergyCableBlock> TUNGSTEN_HEAT_RESISTANT_WIRE = BLOCKS.register(
            SpecialCableMaterial.TUNGSTEN.blockId(), () -> new EnergyCableBlock(
                    SpecialCableMaterial.TUNGSTEN,
                    BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK).noOcclusion()));
    public static final RegistryObject<Item> TUNGSTEN_HEAT_RESISTANT_WIRE_ITEM = ITEMS.register(
            SpecialCableMaterial.TUNGSTEN.blockId(),
            () -> new BlockItem(TUNGSTEN_HEAT_RESISTANT_WIRE.get(), new Item.Properties()));
    public static final RegistryObject<EnergyCableBlock> TUNGSTEN_HEAT_RESISTANT_CABLE =
            TUNGSTEN_HEAT_RESISTANT_WIRE;
    public static final RegistryObject<Item> TUNGSTEN_HEAT_RESISTANT_CABLE_ITEM =
            TUNGSTEN_HEAT_RESISTANT_WIRE_ITEM;

    public static final RegistryObject<LowTemperatureControllerBlock> LOW_TEMPERATURE_CONTROLLER = BLOCKS.register(
            "low_temperature_controller", () -> new LowTemperatureControllerBlock(
                    BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK)));
    public static final RegistryObject<Item> LOW_TEMPERATURE_CONTROLLER_ITEM = registerBlockItem(
            "low_temperature_controller", LOW_TEMPERATURE_CONTROLLER);

    public static final RegistryObject<BlockEntityType<LowTemperatureControllerBlockEntity>>
            LOW_TEMPERATURE_CONTROLLER_BE = BLOCK_ENTITIES.register("low_temperature_controller",
                    () -> BlockEntityType.Builder.of(LowTemperatureControllerBlockEntity::new,
                            LOW_TEMPERATURE_CONTROLLER.get()).build(null));

    /** 全部线缆级共用一个方块实体类型 (范式同入口三方块共用 ENTRANCE 类型)。 */
    public static final RegistryObject<BlockEntityType<EnergyCableBlockEntity>> ENERGY_CABLE_BE =
            BLOCK_ENTITIES.register("energy_cable",
                    () -> BlockEntityType.Builder.of(EnergyCableBlockEntity::new, cableBlocks()).build(null));

    public static final RegistryObject<BlockEntityType<GeneratorBlockEntity>> GENERATOR_CONTROLLER_BE =
            BLOCK_ENTITIES.register("generator_controller",
                    () -> BlockEntityType.Builder.of(GeneratorBlockEntity::new, generatorBlocks()).build(null));
    public static final RegistryObject<BlockEntityType<GeneratorPortBlockEntity>> GENERATOR_PORT_BE =
            BLOCK_ENTITIES.register("generator_port",
                    () -> BlockEntityType.Builder.of(GeneratorPortBlockEntity::new, generatorBlocks()).build(null));

    public static final RegistryObject<MenuType<GeneratorMenu>> GENERATOR_MENU =
            MENUS.register("generator", () -> IForgeMenuType.create(
                    (windowId, inventory, data) -> new GeneratorMenu(windowId, inventory, data.readBlockPos())));
    public static final RegistryObject<MenuType<LowTemperatureControllerMenu>> LOW_TEMPERATURE_CONTROLLER_MENU =
            MENUS.register("low_temperature_controller", () -> IForgeMenuType.create(
                    (windowId, inventory, data) -> new LowTemperatureControllerMenu(
                            windowId, inventory, data.readBlockPos())));

    private PowerRegistry() {
    }

    private static Map<ConductorMaterial, RegistryObject<EnergyCableBlock>> registerCables() {
        Map<ConductorMaterial, RegistryObject<EnergyCableBlock>> map = new EnumMap<>(ConductorMaterial.class);
        for (ConductorMaterial material : REGISTERED_MATERIALS) {
            map.put(material, BLOCKS.register(material.blockId(), () -> new EnergyCableBlock(material,
                    BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK).noOcclusion())));
        }
        return map;
    }

    private static Map<ConductorMaterial, RegistryObject<Item>> registerCableItems() {
        Map<ConductorMaterial, RegistryObject<Item>> map = new EnumMap<>(ConductorMaterial.class);
        for (ConductorMaterial material : REGISTERED_MATERIALS) {
            map.put(material, ITEMS.register(material.blockId(),
                    () -> new BlockItem(CABLES.get(material).get(), new Item.Properties())));
        }
        return map;
    }

    private static Map<ConductorMaterial, RegistryObject<Item>> registerWireItems() {
        Map<ConductorMaterial, RegistryObject<Item>> map = new EnumMap<>(ConductorMaterial.class);
        for (ConductorMaterial material : REGISTERED_MATERIALS) {
            map.put(material, ITEMS.register(material.id() + "_wire", () -> new Item(new Item.Properties())));
        }
        return map;
    }

    private static Block[] cableBlocks() {
        Block[] conductorBlocks = CABLES.values().stream().map(RegistryObject::get).toArray(Block[]::new);
        Block[] blocks = java.util.Arrays.copyOf(conductorBlocks, conductorBlocks.length + 1);
        blocks[conductorBlocks.length] = TUNGSTEN_HEAT_RESISTANT_WIRE.get();
        return blocks;
    }

    private static Block[] generatorBlocks() {
        return new Block[]{INDUSTRIAL_GENERATOR.get(), MODERN_GENERATOR.get(), FUTURE_ENERGY_GENERATOR.get()};
    }

    private static RegistryObject<GeneratorMultiblockBlock> registerGenerator(String name, GeneratorSpec spec) {
        return BLOCKS.register(name, () -> new GeneratorMultiblockBlock(
                spec,
                BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK)
                        .noOcclusion()
                        .pushReaction(PushReaction.BLOCK)));
    }

    private static <T extends Block> RegistryObject<Item> registerBlockItem(
            String name, RegistryObject<T> block) {
        return ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        MENUS.register(modBus);
    }
}

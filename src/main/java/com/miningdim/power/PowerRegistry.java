package com.miningdim.power;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.cable.ConductorMaterial;
import com.miningdim.power.cable.EnergyCableBlock;
import com.miningdim.power.cable.EnergyCableBlockEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.eventbus.api.IEventBus;
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

    public static final RegistryObject<GeneratorMultiblockBlock> INDUSTRIAL_GENERATOR =
            registerGenerator("industrial_generator");
    public static final RegistryObject<GeneratorMultiblockBlock> MODERN_GENERATOR =
            registerGenerator("modern_generator");
    public static final RegistryObject<GeneratorMultiblockBlock> FUTURE_ENERGY_GENERATOR =
            registerGenerator("future_energy_generator");

    public static final RegistryObject<Item> INDUSTRIAL_GENERATOR_ITEM =
            registerBlockItem("industrial_generator", INDUSTRIAL_GENERATOR);
    public static final RegistryObject<Item> MODERN_GENERATOR_ITEM =
            registerBlockItem("modern_generator", MODERN_GENERATOR);
    public static final RegistryObject<Item> FUTURE_ENERGY_GENERATOR_ITEM =
            registerBlockItem("future_energy_generator", FUTURE_ENERGY_GENERATOR);

    // ---- 有线 FE 线缆 (导体材料数据驱动, 逻辑在 com.miningdim.power.cable/grid) ----

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MiningConstants.MODID);

    /**
     * P1 实际注册方块的导体材料白名单: 仅铁、铜 (原版金属可直接 raw 搓)。{@code ConductorMaterial} 其余 10 行
     * 数据已就位, 待各自门槛 (新矿 / 提纯机 / 镀层 / 合成) 落地后逐级加入本表点亮, 见 ConductorMaterial 类注释。
     */
    private static final List<ConductorMaterial> P1_MATERIALS = List.of(
            ConductorMaterial.IRON, ConductorMaterial.COPPER);

    public static final Map<ConductorMaterial, RegistryObject<EnergyCableBlock>> CABLES = registerCables();
    public static final Map<ConductorMaterial, RegistryObject<Item>> CABLE_ITEMS = registerCableItems();

    /** 全部线缆级共用一个方块实体类型 (范式同入口三方块共用 ENTRANCE 类型)。 */
    public static final RegistryObject<BlockEntityType<EnergyCableBlockEntity>> ENERGY_CABLE_BE =
            BLOCK_ENTITIES.register("energy_cable",
                    () -> BlockEntityType.Builder.of(EnergyCableBlockEntity::new, cableBlocks()).build(null));

    private PowerRegistry() {
    }

    private static Map<ConductorMaterial, RegistryObject<EnergyCableBlock>> registerCables() {
        Map<ConductorMaterial, RegistryObject<EnergyCableBlock>> map = new EnumMap<>(ConductorMaterial.class);
        for (ConductorMaterial material : P1_MATERIALS) {
            map.put(material, BLOCKS.register(material.blockId(), () -> new EnergyCableBlock(material,
                    BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK).noOcclusion())));
        }
        return map;
    }

    private static Map<ConductorMaterial, RegistryObject<Item>> registerCableItems() {
        Map<ConductorMaterial, RegistryObject<Item>> map = new EnumMap<>(ConductorMaterial.class);
        for (ConductorMaterial material : P1_MATERIALS) {
            map.put(material, ITEMS.register(material.blockId(),
                    () -> new BlockItem(CABLES.get(material).get(), new Item.Properties())));
        }
        return map;
    }

    private static Block[] cableBlocks() {
        return CABLES.values().stream().map(RegistryObject::get).toArray(Block[]::new);
    }

    private static RegistryObject<GeneratorMultiblockBlock> registerGenerator(String name) {
        return BLOCKS.register(name, () -> new GeneratorMultiblockBlock(
                BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK)
                        .noOcclusion()
                        .pushReaction(PushReaction.BLOCK)));
    }

    private static RegistryObject<Item> registerBlockItem(
            String name, RegistryObject<GeneratorMultiblockBlock> block) {
        return ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }
}

package com.miningdim.power.rubber;

import com.miningdim.core.MiningConstants;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 橡胶基础资源的方块、物品与割胶原木方块实体注册。 */
public final class PowerRubberRegistry {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MiningConstants.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MiningConstants.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MiningConstants.MODID);

    public static final RegistryObject<RubberLogBlock> RUBBER_LOG = BLOCKS.register("rubber_log",
            () -> new RubberLogBlock(BlockBehaviour.Properties.copy(Blocks.OAK_LOG)));
    public static final RegistryObject<Block> RUBBER_PLANKS = BLOCKS.register("rubber_planks",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.OAK_PLANKS)));
    public static final RegistryObject<LeavesBlock> RUBBER_LEAVES = BLOCKS.register("rubber_leaves",
            () -> new LeavesBlock(BlockBehaviour.Properties.copy(Blocks.OAK_LEAVES)));
    public static final RegistryObject<SaplingBlock> RUBBER_SAPLING = BLOCKS.register("rubber_tree_sapling",
            () -> new SaplingBlock(new RubberTreeGrower(), BlockBehaviour.Properties.copy(Blocks.OAK_SAPLING)));

    public static final RegistryObject<Item> RUBBER_LOG_ITEM = registerBlockItem("rubber_log", RUBBER_LOG);
    public static final RegistryObject<Item> RUBBER_PLANKS_ITEM = registerBlockItem("rubber_planks", RUBBER_PLANKS);
    public static final RegistryObject<Item> RUBBER_LEAVES_ITEM = registerBlockItem("rubber_leaves", RUBBER_LEAVES);
    public static final RegistryObject<Item> RUBBER_SAPLING_ITEM =
            registerBlockItem("rubber_tree_sapling", RUBBER_SAPLING);

    public static final RegistryObject<Item> LATEX = ITEMS.register("latex", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> RUBBER = ITEMS.register("rubber", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> INSULATION_PVC =
            ITEMS.register("insulation_pvc", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> INSULATION_PE =
            ITEMS.register("insulation_pe", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> INSULATION_EPR =
            ITEMS.register("insulation_epr", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> INSULATION_XLPE =
            ITEMS.register("insulation_xlpe", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> INSULATION_SILICONE =
            ITEMS.register("insulation_silicone", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> RUBBER_TAPPING_KNIFE =
            ITEMS.register("rubber_tapping_knife", () -> new Item(new Item.Properties().durability(128)));

    public static final RegistryObject<BlockEntityType<RubberLogBlockEntity>> RUBBER_LOG_BE =
            BLOCK_ENTITIES.register("rubber_log", () -> BlockEntityType.Builder
                    .of(RubberLogBlockEntity::new, RUBBER_LOG.get()).build(null));

    private PowerRubberRegistry() {
    }

    private static <T extends Block> RegistryObject<Item> registerBlockItem(String id, RegistryObject<T> block) {
        return ITEMS.register(id, () -> new BlockItem(block.get(), new Item.Properties()));
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }
}

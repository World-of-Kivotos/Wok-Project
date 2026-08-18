package com.miningdim.power;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.machine.AirSeparatingRecipe;
import com.miningdim.power.machine.AirSeparationMenu;
import com.miningdim.power.machine.AirSeparationUnitBlockEntity;
import com.miningdim.power.machine.MetallurgicPurifierBlockEntity;
import com.miningdim.power.machine.MetallurgicPurifierMenu;
import com.miningdim.power.machine.MetallurgicPurifyingRecipe;
import com.miningdim.power.machine.PowerMachineBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 提纯机、空分机及其数据包配方的独立注册边界。 */
public final class PowerMachineRegistry {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MiningConstants.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MiningConstants.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MiningConstants.MODID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, MiningConstants.MODID);
    public static final DeferredRegister<RecipeType<?>> RECIPE_TYPES =
            DeferredRegister.create(ForgeRegistries.RECIPE_TYPES, MiningConstants.MODID);
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, MiningConstants.MODID);

    public static final RegistryObject<PowerMachineBlock> PURIFIER_BLOCK = BLOCKS.register(
            "metallurgic_purifier", () -> new PowerMachineBlock(
                    BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK), PowerMachineBlock.MachineKind.PURIFIER));
    public static final RegistryObject<PowerMachineBlock> AIR_SEPARATOR_BLOCK = BLOCKS.register(
            "air_separation_unit", () -> new PowerMachineBlock(
                    BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK), PowerMachineBlock.MachineKind.AIR_SEPARATOR));

    public static final RegistryObject<Item> PURIFIER_ITEM = registerBlockItem("metallurgic_purifier", PURIFIER_BLOCK);
    public static final RegistryObject<Item> AIR_SEPARATOR_ITEM =
            registerBlockItem("air_separation_unit", AIR_SEPARATOR_BLOCK);

    public static final RegistryObject<Item> DEOXIDIZED_COPPER_INGOT =
            ITEMS.register("deoxidized_copper_ingot", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> PHOSPHORUS_DEOXIDIZED_COPPER_INGOT =
            ITEMS.register("phosphorus_deoxidized_copper_ingot", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> OFC_COPPER_INGOT =
            ITEMS.register("ofc_copper_ingot", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> OFE_COPPER_INGOT =
            ITEMS.register("ofe_copper_ingot", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> GOLD_4N_INGOT =
            ITEMS.register("gold_4n_ingot", () -> new Item(new Item.Properties()));
    public static final RegistryObject<Item> ARGON_CANISTER =
            ITEMS.register("argon_canister", () -> new Item(new Item.Properties().stacksTo(16)));
    public static final RegistryObject<Item> LIQUID_NITROGEN_CANISTER =
            ITEMS.register("liquid_nitrogen_canister", () -> new Item(new Item.Properties().stacksTo(16)));

    public static final RegistryObject<BlockEntityType<MetallurgicPurifierBlockEntity>> METALLURGIC_PURIFIER_BE =
            BLOCK_ENTITIES.register("metallurgic_purifier", () -> BlockEntityType.Builder
                    .of(MetallurgicPurifierBlockEntity::new, PURIFIER_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<AirSeparationUnitBlockEntity>> AIR_SEPARATION_UNIT_BE =
            BLOCK_ENTITIES.register("air_separation_unit", () -> BlockEntityType.Builder
                    .of(AirSeparationUnitBlockEntity::new, AIR_SEPARATOR_BLOCK.get()).build(null));

    public static final RegistryObject<MenuType<MetallurgicPurifierMenu>> PURIFIER_MENU =
            MENUS.register("metallurgic_purifier", () -> IForgeMenuType.create(
                    (windowId, inventory, data) -> new MetallurgicPurifierMenu(
                            windowId, inventory, data.readBlockPos())));
    public static final RegistryObject<MenuType<AirSeparationMenu>> AIR_SEPARATOR_MENU =
            MENUS.register("air_separation_unit", () -> IForgeMenuType.create(
                    (windowId, inventory, data) -> new AirSeparationMenu(
                            windowId, inventory, data.readBlockPos())));

    public static final RegistryObject<RecipeType<MetallurgicPurifyingRecipe>> METALLURGIC_PURIFYING_TYPE =
            RECIPE_TYPES.register("metallurgic_purifying", () -> namedRecipeType("metallurgic_purifying"));
    public static final RegistryObject<RecipeType<AirSeparatingRecipe>> AIR_SEPARATING_TYPE =
            RECIPE_TYPES.register("air_separating", () -> namedRecipeType("air_separating"));
    public static final RegistryObject<RecipeSerializer<MetallurgicPurifyingRecipe>> METALLURGIC_PURIFYING_SERIALIZER =
            RECIPE_SERIALIZERS.register("metallurgic_purifying", MetallurgicPurifyingRecipe.Serializer::new);
    public static final RegistryObject<RecipeSerializer<AirSeparatingRecipe>> AIR_SEPARATING_SERIALIZER =
            RECIPE_SERIALIZERS.register("air_separating", AirSeparatingRecipe.Serializer::new);

    private PowerMachineRegistry() {
    }

    private static <T extends Block> RegistryObject<Item> registerBlockItem(String id, RegistryObject<T> block) {
        return ITEMS.register(id, () -> new BlockItem(block.get(), new Item.Properties()));
    }

    private static <T extends net.minecraft.world.item.crafting.Recipe<?>> RecipeType<T> namedRecipeType(String id) {
        return new RecipeType<>() {
            @Override
            public String toString() {
                return MiningConstants.MODID + ":" + id;
            }
        };
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        MENUS.register(modBus);
        RECIPE_TYPES.register(modBus);
        RECIPE_SERIALIZERS.register(modBus);
    }
}

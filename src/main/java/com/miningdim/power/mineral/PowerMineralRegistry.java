package com.miningdim.power.mineral;

import com.miningdim.core.MiningConstants;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.EnumMap;
import java.util.Map;

/** 能源基础矿物的方块与物品注册表。 */
public final class PowerMineralRegistry {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MiningConstants.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MiningConstants.MODID);

    private static final Map<PowerMineral, RegistryObject<Block>> ORES = registerOres(false);
    private static final Map<PowerMineral, RegistryObject<Block>> DEEPSLATE_ORES = registerOres(true);
    private static final Map<PowerMineral, RegistryObject<Item>> ORE_ITEMS = registerOreItems();
    private static final Map<PowerMineral, RegistryObject<Item>> RAW_MATERIALS = registerRawMaterials();
    private static final Map<PowerMineral, RegistryObject<Item>> INGOTS = registerIngots();

    private PowerMineralRegistry() {
    }

    public static RegistryObject<Block> ore(PowerMineral mineral) {
        return ORES.get(mineral);
    }

    public static RegistryObject<Block> deepslateOre(PowerMineral mineral) {
        return DEEPSLATE_ORES.get(mineral);
    }

    public static RegistryObject<Item> oreItem(PowerMineral mineral) {
        return ORE_ITEMS.get(mineral);
    }

    public static RegistryObject<Item> rawMaterial(PowerMineral mineral) {
        return RAW_MATERIALS.get(mineral);
    }

    public static RegistryObject<Item> ingot(PowerMineral mineral) {
        if (!mineral.hasIngot()) {
            throw new IllegalArgumentException(mineral.name() + " has no ingot registration");
        }
        return INGOTS.get(mineral);
    }

    private static Map<PowerMineral, RegistryObject<Block>> registerOres(boolean deepslate) {
        Map<PowerMineral, RegistryObject<Block>> entries = new EnumMap<>(PowerMineral.class);
        for (PowerMineral mineral : PowerMineral.values()) {
            String id = deepslate ? mineral.deepslateOreId() : mineral.oreId();
            BlockBehaviour.Properties properties = BlockBehaviour.Properties.copy(
                    deepslate ? Blocks.DEEPSLATE_IRON_ORE : Blocks.IRON_ORE);
            entries.put(mineral, BLOCKS.register(id, () -> new Block(properties)));
        }
        return Map.copyOf(entries);
    }

    private static Map<PowerMineral, RegistryObject<Item>> registerOreItems() {
        Map<PowerMineral, RegistryObject<Item>> entries = new EnumMap<>(PowerMineral.class);
        for (PowerMineral mineral : PowerMineral.values()) {
            entries.put(mineral, ITEMS.register(mineral.oreId(),
                    () -> new BlockItem(ore(mineral).get(), new Item.Properties())));
            ITEMS.register(mineral.deepslateOreId(),
                    () -> new BlockItem(deepslateOre(mineral).get(), new Item.Properties()));
        }
        return Map.copyOf(entries);
    }

    private static Map<PowerMineral, RegistryObject<Item>> registerRawMaterials() {
        Map<PowerMineral, RegistryObject<Item>> entries = new EnumMap<>(PowerMineral.class);
        for (PowerMineral mineral : PowerMineral.values()) {
            entries.put(mineral, ITEMS.register(mineral.rawMaterialId(), () -> new Item(new Item.Properties())));
        }
        return Map.copyOf(entries);
    }

    private static Map<PowerMineral, RegistryObject<Item>> registerIngots() {
        Map<PowerMineral, RegistryObject<Item>> entries = new EnumMap<>(PowerMineral.class);
        for (PowerMineral mineral : PowerMineral.values()) {
            if (mineral.hasIngot()) {
                entries.put(mineral, ITEMS.register(mineral.ingotId(), () -> new Item(new Item.Properties())));
            }
        }
        return Map.copyOf(entries);
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
    }
}

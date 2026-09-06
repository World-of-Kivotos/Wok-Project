package com.miningdim.job.fisher.ore;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.fisher.soup.OreFishSoupItem;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public final class OreFishingItems {
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MiningConstants.MODID);
    public static final Map<OreFishType, RegistryObject<Item>> FISH;
    public static final Map<OreFishType, RegistryObject<Item>> SOUPS;

    static {
        Map<OreFishType, RegistryObject<Item>> fish = new EnumMap<>(OreFishType.class);
        Map<OreFishType, RegistryObject<Item>> soups = new EnumMap<>(OreFishType.class);
        for (OreFishType type : OreFishType.values()) {
            fish.put(type, ITEMS.register(type.fishId(), () -> new OreFishItem(type,
                    new Item.Properties().rarity(type.rarity()))));
            soups.put(type, ITEMS.register(type.soupId(), () -> new OreFishSoupItem(type,
                    new Item.Properties().stacksTo(16).craftRemainder(Items.BOWL).rarity(type.rarity())
                            .food(new FoodProperties.Builder().nutrition(8).saturationMod(0.6F).alwaysEat().build()))));
        }
        FISH = Collections.unmodifiableMap(fish);
        SOUPS = Collections.unmodifiableMap(soups);
    }

    private OreFishingItems() {
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}

package com.miningdim.job.chef.recipe;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.stream.Collectors;

/** Published with a successful recipe reload; cooking-pot recipes keep their original FD identity. */
public final class ChefDishCatalog {
    public record Dish(int star, int station, int motion, int flips, int beats, boolean drink) {}

    private static Map<ResourceLocation, Dish> dishes = Map.of();

    private ChefDishCatalog() {}

    public static @Nullable Dish find(ResourceLocation output) {
        return dishes.get(output);
    }

    public static Map<ResourceLocation, Dish> all() {
        return dishes;
    }

    static void publish(Map<ResourceLocation, ChefRecipeMigration.Dish> source) {
        dishes = source.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> {
            ChefRecipeMigration.Dish dish = entry.getValue();
            return new Dish(dish.star(), dish.station(), dish.motion(), dish.flips(), dish.beats(), dish.drink());
        }));
    }
}

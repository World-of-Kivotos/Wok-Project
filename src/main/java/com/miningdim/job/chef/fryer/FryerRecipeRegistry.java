package com.miningdim.job.chef.fryer;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.chef.station.CookingStationBlock.Kind;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;

@Mod.EventBusSubscriber(modid = MiningConstants.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class FryerRecipeRegistry {
    private static final java.util.Map<Kind, RecipeType<FryerRecipe>> TYPES = new java.util.EnumMap<>(Kind.class);
    private static final java.util.Map<Kind, FryerRecipeSerializer> SERIALIZERS = new java.util.EnumMap<>(Kind.class);

    private FryerRecipeRegistry() {}

    @SubscribeEvent
    public static void register(RegisterEvent event) {
        event.register(ForgeRegistries.Keys.RECIPE_TYPES, helper -> {
            for (Kind kind : Kind.values()) {
                ResourceLocation id = id(kind);
                RecipeType<FryerRecipe> type = new RecipeType<>() {
                    @Override public String toString() { return id.toString(); }
                };
                TYPES.put(kind, type); helper.register(id, type);
            }
        });
        event.register(ForgeRegistries.Keys.RECIPE_SERIALIZERS, helper -> {
            for (Kind kind : Kind.values()) {
                FryerRecipeSerializer serializer = new FryerRecipeSerializer(kind);
                SERIALIZERS.put(kind, serializer); helper.register(id(kind), serializer);
            }
        });
    }

    private static ResourceLocation id(Kind kind) {
        return new ResourceLocation(MiningConstants.MODID, switch (kind) {
            case DEEP_FRYER -> "frying";
            case BAKING_OVEN -> "baking";
            case PREP_COUNTER -> "preparing";
        });
    }
    public static RecipeType<FryerRecipe> recipeType() { return recipeType(Kind.DEEP_FRYER); }
    public static FryerRecipeSerializer recipeSerializer() { return recipeSerializer(Kind.DEEP_FRYER); }
    public static RecipeType<FryerRecipe> recipeType(Kind kind) { return TYPES.get(kind); }
    public static FryerRecipeSerializer recipeSerializer(Kind kind) { return SERIALIZERS.get(kind); }
}

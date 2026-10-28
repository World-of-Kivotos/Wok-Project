package com.miningdim.job.chef.fryer;

import com.miningdim.core.MiningConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;

@Mod.EventBusSubscriber(modid = MiningConstants.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class FryerRecipeRegistry {
    private static final ResourceLocation ID = new ResourceLocation(MiningConstants.MODID, "frying");
    private static RecipeType<FryerRecipe> recipeType;
    private static FryerRecipeSerializer recipeSerializer;

    private FryerRecipeRegistry() {}

    @SubscribeEvent
    public static void register(RegisterEvent event) {
        event.register(ForgeRegistries.Keys.RECIPE_TYPES, helper -> {
            recipeType = new RecipeType<>() {
                @Override public String toString() { return ID.toString(); }
            };
            helper.register(ID, recipeType);
        });
        event.register(ForgeRegistries.Keys.RECIPE_SERIALIZERS, helper -> {
            recipeSerializer = new FryerRecipeSerializer();
            helper.register(ID, recipeSerializer);
        });
    }

    public static RecipeType<FryerRecipe> recipeType() { return recipeType; }
    public static FryerRecipeSerializer recipeSerializer() { return recipeSerializer; }
}

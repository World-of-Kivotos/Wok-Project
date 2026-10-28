package com.miningdim.job.chef.recipe.mixin;

import com.miningdim.job.chef.recipe.ChefRecipeMigration;
import com.google.gson.JsonElement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.crafting.RecipeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Map;

@Mixin(RecipeManager.class)
public abstract class ChefRecipeManagerMixin {
    @ModifyVariable(method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Map<ResourceLocation, JsonElement> miningdim$migrateChefRecipes(Map<ResourceLocation, JsonElement> value,
            Map<ResourceLocation, JsonElement> original, ResourceManager resources) {
        return ChefRecipeMigration.migrate(value, resources);
    }
}

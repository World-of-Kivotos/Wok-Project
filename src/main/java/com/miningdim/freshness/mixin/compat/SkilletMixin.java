package com.miningdim.freshness.mixin.compat;

import com.miningdim.freshness.FreshnessClockData;
import com.miningdim.freshness.FreshnessConfig;
import com.miningdim.freshness.FreshnessFdTargets;
import com.miningdim.freshness.FreshnessProcessing;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Pseudo
@Mixin(targets = FreshnessFdTargets.SKILLET, remap = false)
public abstract class SkilletMixin {

    @Inject(method = FreshnessFdTargets.SKILLET_COOK, at = @At("HEAD"), cancellable = true, remap = false)
    private void miningdim$guardSkillet(ItemStack cookingStack, Level level, CallbackInfo callback) {
        if (FreshnessConfig.replacementEnabled() && level instanceof ServerLevel serverLevel
                && FreshnessProcessing.anyRotten(List.of(cookingStack), FreshnessClockData.nowMinute(serverLevel))) {
            callback.cancel();
        }
    }

    @Redirect(method = FreshnessFdTargets.SKILLET_COOK, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/crafting/CampfireCookingRecipe;assemble(Lnet/minecraft/world/Container;Lnet/minecraft/core/RegistryAccess;)Lnet/minecraft/world/item/ItemStack;",
            remap = true), remap = false)
    private ItemStack miningdim$inheritSkilletProduct(CampfireCookingRecipe recipe, Container input,
                                                     RegistryAccess registries, ItemStack cookingStack, Level level) {
        ItemStack product = recipe.assemble(input, registries);
        if (FreshnessConfig.stampingEnabled() && level instanceof ServerLevel serverLevel) {
            FreshnessProcessing.inherit(product, List.of(cookingStack), FreshnessProcessing.HEAT_BONUS,
                    FreshnessClockData.nowMinute(serverLevel));
        }
        return product;
    }
}

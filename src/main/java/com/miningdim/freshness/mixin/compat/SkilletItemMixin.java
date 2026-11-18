package com.miningdim.freshness.mixin.compat;

import com.miningdim.freshness.FreshnessFdHandCooking;
import com.miningdim.freshness.FreshnessFdTargets;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = FreshnessFdTargets.SKILLET_ITEM, remap = false)
public abstract class SkilletItemMixin {

    @Inject(method = {FreshnessFdTargets.SKILLET_ITEM_FINISH, FreshnessFdTargets.SKILLET_ITEM_FINISH_SRG},
            at = @At("HEAD"), cancellable = true, remap = false)
    private void miningdim$beginHandCooking(ItemStack skillet, Level level, LivingEntity user,
                                           CallbackInfoReturnable<ItemStack> callback) {
        if (FreshnessFdHandCooking.begin(skillet, level)) {
            callback.setReturnValue(skillet);
        }
    }

    // FD 1.2.9 的回调在正式 JAR 中仍保留 lambda 的官方名; 配方方法由 refmap 映射到 SRG。
    @Redirect(method = "lambda$finishUsingItem$1", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/crafting/CampfireCookingRecipe;assemble(Lnet/minecraft/world/Container;Lnet/minecraft/core/RegistryAccess;)Lnet/minecraft/world/item/ItemStack;",
            remap = true), remap = false)
    private static ItemStack miningdim$inheritHandProduct(CampfireCookingRecipe recipe, Container container,
                                                         RegistryAccess registries, Level level, Player player,
                                                         ItemStack skillet, CampfireCookingRecipe callbackRecipe) {
        return FreshnessFdHandCooking.inheritProduct(recipe.assemble(container, registries), skillet, level);
    }
}

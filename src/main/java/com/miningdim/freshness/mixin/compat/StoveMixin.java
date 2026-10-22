package com.miningdim.freshness.mixin.compat;

import com.miningdim.freshness.FreshnessClockData;
import com.miningdim.freshness.FreshnessConfig;
import com.miningdim.freshness.FreshnessFdTargets;
import com.miningdim.freshness.FreshnessProcessing;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Pseudo
@Mixin(targets = FreshnessFdTargets.STOVE, remap = false)
public abstract class StoveMixin {

    @Shadow @Final private ItemStackHandler items;
    @Unique private ItemStack miningdim$currentStoveIngredient = ItemStack.EMPTY;

    @Inject(method = FreshnessFdTargets.STOVE_COOK, at = @At("HEAD"), cancellable = true, remap = false)
    private void miningdim$guardStove(CallbackInfo callback) {
        if (FreshnessConfig.replacementEnabled() && levelOf() instanceof ServerLevel serverLevel) {
            List<ItemStack> ingredients = new ArrayList<>();
            for (int slot = 0; slot < items.getSlots(); slot++) {
                ingredients.add(items.getStackInSlot(slot));
            }
            if (FreshnessProcessing.anyRotten(ingredients, FreshnessClockData.nowMinute(serverLevel))) {
                callback.cancel();
            }
        }
    }

    // FD 每格只读取一次原料, 随后才查配方和生成该格产物。这里的状态不会进入全局来源上下文。
    @Redirect(method = FreshnessFdTargets.STOVE_COOK, at = @At(value = "INVOKE",
            target = "Lnet/minecraftforge/items/ItemStackHandler;getStackInSlot(I)Lnet/minecraft/world/item/ItemStack;"),
            remap = false)
    private ItemStack miningdim$readStoveIngredient(ItemStackHandler handler, int slot) {
        ItemStack ingredient = handler.getStackInSlot(slot);
        miningdim$currentStoveIngredient = ingredient.copy();
        return ingredient;
    }

    @Redirect(method = FreshnessFdTargets.STOVE_COOK, at = @At(value = "INVOKE",
            target = "Lvectorwing/farmersdelight/common/utility/ItemUtils;spawnItemEntity(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;DDDDDD)V",
            remap = false), remap = false)
    private void miningdim$inheritStoveProduct(Level level, ItemStack product,
                                                double x, double y, double z,
                                                double xSpeed, double ySpeed, double zSpeed) {
        if (FreshnessConfig.stampingEnabled() && level instanceof ServerLevel serverLevel) {
            FreshnessProcessing.inherit(product, List.of(miningdim$currentStoveIngredient),
                    FreshnessProcessing.HEAT_BONUS, FreshnessClockData.nowMinute(serverLevel));
        }
        vectorwing.farmersdelight.common.utility.ItemUtils.spawnItemEntity(
                level, product, x, y, z, xSpeed, ySpeed, zSpeed);
    }

    @Unique private Level levelOf() {
        return ((net.minecraft.world.level.block.entity.BlockEntity) (Object) this).getLevel();
    }
}

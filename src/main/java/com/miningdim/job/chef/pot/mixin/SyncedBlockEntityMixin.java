package com.miningdim.job.chef.pot.mixin;

import com.miningdim.job.chef.pot.PotCraftService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;
import vectorwing.farmersdelight.common.block.entity.SyncedBlockEntity;

@Mixin(value = SyncedBlockEntity.class, remap = false)
public abstract class SyncedBlockEntityMixin {
    @Inject(method = "inventoryChanged()V", at = @At("TAIL"), remap = false)
    private void wok$potChanged(CallbackInfo callback) {
        if ((Object) this instanceof CookingPotBlockEntity pot) PotCraftService.changed(pot);
    }
}

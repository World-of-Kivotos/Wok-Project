package com.miningdim.district.mixin.create;

import com.miningdim.district.guard.create.CreateGuards;
import com.miningdim.district.guard.create.CreateHookTargets;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * C6 (设计文档 22.6): 收割机。必须有: 只有 C1 时它照样把作物重置为幼苗或设成空气, 而且不掉落。
 */
@Pseudo
@Mixin(targets = CreateHookTargets.HARVESTER, remap = false)
public abstract class HarvesterMixin {

    @Inject(method = CreateHookTargets.VISIT_NEW_POSITION, at = @At("HEAD"), cancellable = true, remap = false)
    private void miningdim$guardDistrict(@Coerce Object context, BlockPos pos, CallbackInfo callback) {
        if (!CreateGuards.actorMayTouch(context, pos)) {
            callback.cancel();
        }
    }
}

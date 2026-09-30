package com.miningdim.district.mixin.create;

import com.miningdim.district.guard.create.CreateGuards;
import com.miningdim.district.guard.create.CreateHookTargets;
import com.miningdim.district.guard.create.CreateReflection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * C12 (设计文档 22.6): 软管滑轮灌液。受保护的格子 → 包内可见的 SpaceType.BLOCKING (经 CreateReflection 取; 取不到时
 * 放行)。
 */
@Pseudo
@Mixin(targets = CreateHookTargets.FLUID_FILLING, remap = false)
public abstract class FluidFillingMixin {

    @Inject(method = CreateHookTargets.GET_AT_POS, at = @At("HEAD"), cancellable = true, remap = false)
    private void miningdim$guardDistrict(Level level, BlockPos pos, Fluid fluid,
                                         CallbackInfoReturnable<Object> callback) {
        if (!CreateGuards.fluidMayReach(level, pos)) {
            Object blocking = CreateReflection.spaceBlocking();
            if (blocking != null) {
                callback.setReturnValue(blocking);
            }
        }
    }
}

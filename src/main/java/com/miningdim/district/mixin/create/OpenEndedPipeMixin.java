package com.miningdim.district.mixin.create;

import com.miningdim.district.guard.create.CreateGuards;
import com.miningdim.district.guard.create.CreateHookTargets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * C13 (设计文档 22.6): 开口管道。出口那一格 (outputPos, 即 getOutputPos()) 受保护时不放液、不吸液。
 */
@Pseudo
@Mixin(targets = CreateHookTargets.OPEN_ENDED_PIPE, remap = false)
public abstract class OpenEndedPipeMixin {

    @Shadow(remap = false)
    private Level world;

    @Shadow(remap = false)
    private BlockPos outputPos;

    @Inject(method = CreateHookTargets.PROVIDE_FLUID_TO_SPACE, at = @At("HEAD"), cancellable = true, remap = false)
    private void miningdim$guardProvide(FluidStack fluid, boolean simulate, CallbackInfoReturnable<Boolean> callback) {
        if (!CreateGuards.fluidMayReach(world, outputPos)) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = CreateHookTargets.REMOVE_FLUID_FROM_SPACE, at = @At("HEAD"), cancellable = true, remap = false)
    private void miningdim$guardRemove(boolean simulate, CallbackInfoReturnable<FluidStack> callback) {
        if (!CreateGuards.fluidMayReach(world, outputPos)) {
            callback.setReturnValue(FluidStack.EMPTY);
        }
    }
}

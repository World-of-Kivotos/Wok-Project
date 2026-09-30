package com.miningdim.district.mixin.create;

import com.miningdim.district.guard.create.CreateGuards;
import com.miningdim.district.guard.create.CreateHookTargets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * C4 (设计文档 22.6): 装置上的钻头、锯、压路机 (它们调 super.canBreak)。区内方块当墙, 平移装置的碰撞检查随之停下。
 * 犁覆写了它且不调 super, 由 C1、C5 管。
 */
@Pseudo
@Mixin(targets = CreateHookTargets.BREAKING_MOVEMENT, remap = false)
public abstract class BlockBreakingMovementMixin {

    @Inject(method = CreateHookTargets.MOVEMENT_CAN_BREAK, at = @At("HEAD"), cancellable = true, remap = false)
    private void miningdim$guardDistrict(Level level, BlockPos pos, BlockState state,
                                         CallbackInfoReturnable<Boolean> callback) {
        if (!CreateGuards.mayBreak(level, pos)) {
            callback.setReturnValue(false);
        }
    }
}

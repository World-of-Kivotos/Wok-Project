package com.miningdim.district.mixin.create;

import com.miningdim.district.guard.create.CreateGuards;
import com.miningdim.district.guard.create.CreateHookTargets;
import com.miningdim.district.guard.create.CreateReflection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * C7 (设计文档 22.6): 压路机铺路 (向下最多 12 格、横向约 6 格, 火车上沿轨道)。位置受保护 → 返回包内可见的
 * PaveResult.FAIL (经 CreateReflection 取; 取不到时放行, C5 仍在)。
 */
@Pseudo
@Mixin(targets = CreateHookTargets.ROLLER, remap = false)
public abstract class RollerMixin {

    @Inject(method = CreateHookTargets.TRY_FILL, at = @At("HEAD"), cancellable = true, remap = false)
    private void miningdim$guardDistrict(@Coerce Object context, BlockPos pos, BlockState state,
                                         CallbackInfoReturnable<Object> callback) {
        if (!CreateGuards.actorMayTouch(context, pos)) {
            Object fail = CreateReflection.paveFail();
            if (fail != null) {
                callback.setReturnValue(fail);
            }
        }
    }
}

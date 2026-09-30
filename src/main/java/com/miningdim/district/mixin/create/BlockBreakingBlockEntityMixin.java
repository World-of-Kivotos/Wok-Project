package com.miningdim.district.mixin.create;

import com.miningdim.district.guard.create.CreateGuards;
import com.miningdim.district.guard.create.CreateHookTargets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * C3 (设计文档 22.6): 固定的钻头、锯 (钻头没覆写 canBreak, 锯调 super)。breakingPos 受保护时不开始拆, 也没有无尽的裂纹
 * 动画; C1 仍在后面兜着。
 */
@Pseudo
@Mixin(targets = CreateHookTargets.BREAKING_BLOCK_ENTITY, remap = false)
public abstract class BlockBreakingBlockEntityMixin {

    @Shadow(remap = false)
    protected BlockPos breakingPos;

    @Inject(method = CreateHookTargets.BE_CAN_BREAK, at = @At("HEAD"), cancellable = true, remap = false)
    private void miningdim$guardDistrict(BlockState stateToBreak, float blockHardness,
                                         CallbackInfoReturnable<Boolean> callback) {
        if (!CreateGuards.mayBreak(((BlockEntity) (Object) this).getLevel(), breakingPos)) {
            callback.setReturnValue(false);
        }
    }
}

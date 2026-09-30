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
 * C10 (设计文档 22.6): 一切组装 (活塞、轴承、滑轮、龙门、矿车组装器, 底盘与强力胶够到区内方块时; 活塞杆也走这一查)。
 * 受保护的方块不可移动 → 边缘方块整个装置组装失败 (机械动力自己在方块上显示原因), 侧面的邻居只是不粘上。
 */
@Pseudo
@Mixin(targets = CreateHookTargets.MOVEMENT_CHECKS, remap = false)
public abstract class BlockMovementChecksMixin {

    @Inject(method = CreateHookTargets.IS_MOVEMENT_ALLOWED, at = @At("HEAD"), cancellable = true, remap = false)
    private static void miningdim$guardDistrict(BlockState state, Level level, BlockPos pos,
                                                CallbackInfoReturnable<Boolean> callback) {
        if (!CreateGuards.mayMoveBlock(level, pos)) {
            callback.setReturnValue(false);
        }
    }
}

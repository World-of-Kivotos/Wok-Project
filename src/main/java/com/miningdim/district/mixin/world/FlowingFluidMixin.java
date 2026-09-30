package com.miningdim.district.mixin.world;

import com.miningdim.district.guard.DistrictWorldGuards;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 地块边界: 流体 (设计文档 22.9, 区内边界口径)。canSpreadTo 是 spreadTo 之前唯一的闸 (spread 对 DOWN、spreadToSides 对
 * 四个水平方向调它); 与 Flan 在同一方法头部的注入共存, 谁先谁后结果都一样 (都只会拒绝)。判定见
 * {@link DistrictWorldGuards#fluidMayFlow}。
 */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {

    @Inject(method = "canSpreadTo", at = @At("HEAD"), cancellable = true)
    private void miningdim$guardPlotBoundary(BlockGetter level, BlockPos fromPos, BlockState fromBlockState,
                                             Direction direction, BlockPos toPos, BlockState toBlockState,
                                             FluidState toFluidState, Fluid fluid,
                                             CallbackInfoReturnable<Boolean> callback) {
        if (level instanceof Level world && !DistrictWorldGuards.fluidMayFlow(world, fromPos, direction, toPos)) {
            callback.setReturnValue(false);
        }
    }
}

package com.miningdim.district.mixin.world;

import com.miningdim.district.guard.DistrictWorldGuards;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 地块边界: 发射器与投掷器 (设计文档 22.9, 单向口径)。原版的每一种发射行为都作用在前方那一格; DropperBlock 覆写了
 * dispenseFrom 且不调 super, 所以对两者各注入一次。前方属于别的区域 → 原版的"发射失败"咔哒声 (1001) 并取消, 物品不消耗。
 */
@Mixin({DispenserBlock.class, DropperBlock.class})
public abstract class DispenserBlockMixin {

    @Inject(method = "dispenseFrom", at = @At("HEAD"), cancellable = true)
    private void miningdim$guardPlotBoundary(ServerLevel level, BlockPos pos, CallbackInfo callback) {
        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(DispenserBlock.FACING)
                && !DistrictWorldGuards.mayDispense(level, pos, state.getValue(DispenserBlock.FACING))) {
            level.levelEvent(1001, pos, 0);
            callback.cancel();
        }
    }
}

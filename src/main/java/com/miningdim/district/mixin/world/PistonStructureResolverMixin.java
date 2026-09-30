package com.miningdim.district.mixin.world;

import com.miningdim.district.guard.DistrictWorldGuards;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 地块边界: 活塞 (设计文档 22.9, 严格口径)。resolve() 被 checkIfExtend (伸出被拒就不排方块事件)、moveBlocks (缩回时返回
 * false 就是"缩回但不拉") 与 Forge 监听者共用; 粘液块、蜂蜜块的侧枝与挤碎的格子都在 toPush / toDestroy 里。
 * 判定见 {@link DistrictWorldGuards#pistonMayMove}。
 */
@Mixin(PistonStructureResolver.class)
public abstract class PistonStructureResolverMixin {

    @Shadow
    @Final
    private Level level;

    @Shadow
    @Final
    private BlockPos pistonPos;

    @Shadow
    @Final
    private boolean extending;

    @Shadow
    @Final
    private Direction pushDirection;

    @Shadow
    @Final
    private List<BlockPos> toPush;

    @Shadow
    @Final
    private List<BlockPos> toDestroy;

    @Shadow
    @Final
    private Direction pistonDirection;

    @Inject(method = "resolve", at = @At("RETURN"), cancellable = true)
    private void miningdim$guardPlotBoundary(CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValueZ() && !DistrictWorldGuards.pistonMayMove(level, pistonPos, pistonDirection,
                extending, pushDirection, toPush, toDestroy)) {
            callback.setReturnValue(false);
        }
    }
}

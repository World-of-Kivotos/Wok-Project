package com.miningdim.district.mixin.world;

import com.miningdim.district.guard.DistrictWorldGuards;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SpongeBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 地块边界: 海绵 (设计文档 22.9, 单向口径)。removeWaterBreadthFirstSearch 用 BlockPos.breadthFirstTraversal 逐格吸水;
 * HEAD 把世界放进 ThreadLocal (@ModifyArg 拿不到外层方法的参数), RETURN 清掉, @ModifyArg 把过滤器包一层: 邻居的水池不会
 * 被你的海绵吸干。周围 7 格内没有在用自管区时原样返回, 不包。
 */
@Mixin(SpongeBlock.class)
public abstract class SpongeBlockMixin {

    @Inject(method = "removeWaterBreadthFirstSearch", at = @At("HEAD"))
    private void miningdim$rememberLevel(Level level, BlockPos pos, CallbackInfoReturnable<Boolean> callback) {
        DistrictWorldGuards.spongeSearchStarted(level);
    }

    @Inject(method = "removeWaterBreadthFirstSearch", at = @At("RETURN"))
    private void miningdim$forgetLevel(Level level, BlockPos pos, CallbackInfoReturnable<Boolean> callback) {
        DistrictWorldGuards.spongeSearchEnded();
    }

    @ModifyArg(method = "removeWaterBreadthFirstSearch", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;breadthFirstTraversal(Lnet/minecraft/core/BlockPos;IILjava/util/function/BiConsumer;Ljava/util/function/Predicate;)I"),
            index = 4)
    private Predicate<BlockPos> miningdim$guardPlotBoundary(BlockPos start, int maxDepth, int maxCount,
                                                            BiConsumer<BlockPos, Consumer<BlockPos>> visitor,
                                                            Predicate<BlockPos> predicate) {
        return DistrictWorldGuards.spongeFilter(start, predicate);
    }
}

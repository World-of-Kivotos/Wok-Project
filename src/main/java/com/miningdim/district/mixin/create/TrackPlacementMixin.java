package com.miningdim.district.mixin.create;

import com.miningdim.district.guard.create.CreateGuards;
import com.miningdim.district.guard.create.CreateHookTargets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * C14 (设计文档 22.6): 轨道的弯道与长直道 (TrackPlacement.placeTracks / paveTracks 直接 setBlock, 一次最多 32 格, 站在
 * 外面就能把弯道铺进区里)。tryConnect 在服务端自己扣物品并铺轨, 所以不能在 RETURN 改结果: 在它唯一一次调
 * Player.isCreative (算完两端、延伸段与曲线、以 simulate = true 试放一次之后, 开始扣物品之前) 处判定; 这次的 PlacementInfo
 * 由 placeTracks 的 HEAD 在 simulate = true 那一次交出来 (放进 ThreadLocal)。非 OP、范围碰到禁放区 → valid 置假并提前返回,
 * TrackBlockItem 见 valid 为假就回 FAIL。
 */
@Pseudo
@Mixin(targets = CreateHookTargets.TRACK_PLACEMENT, remap = false)
public abstract class TrackPlacementMixin {

    @Inject(method = CreateHookTargets.TRY_CONNECT, at = @At("HEAD"), remap = false)
    private static void miningdim$begin(Level level, Player player, BlockPos pos2, BlockState state2, ItemStack stack,
                                        boolean girder, boolean maximiseTurn,
                                        CallbackInfoReturnable<Object> callback) {
        CreateGuards.trackConnectStarted();
    }

    @Inject(method = CreateHookTargets.PLACE_TRACKS, at = @At("HEAD"), remap = false)
    private static void miningdim$capture(Level level, @Coerce Object info, BlockState state1, BlockState state2,
                                          BlockPos targetPos1, BlockPos targetPos2, boolean simulate,
                                          CallbackInfoReturnable<Object> callback) {
        CreateGuards.trackInfoComputed(level, info, simulate);
    }

    @Inject(method = CreateHookTargets.TRY_CONNECT,
            at = @At(value = "INVOKE", target = CreateHookTargets.PLAYER_IS_CREATIVE, remap = true),
            cancellable = true, remap = false)
    private static void miningdim$guardBanArea(Level level, Player player, BlockPos pos2, BlockState state2,
                                               ItemStack stack, boolean girder, boolean maximiseTurn,
                                               CallbackInfoReturnable<Object> callback) {
        Object blocked = CreateGuards.trackPlacementBlocked(level, player);
        if (blocked != null) {
            callback.setReturnValue(blocked);
        }
    }
}

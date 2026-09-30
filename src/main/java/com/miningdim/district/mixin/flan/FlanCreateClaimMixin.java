package com.miningdim.district.mixin.flan;

import com.miningdim.district.flan.real.FlanClaimGuard;
import com.miningdim.district.flan.real.FlanHookTargets;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * F1 (设计文档 22.20): Flan 的 {@code ClaimStorage.createClaim} —— 金锄头新圈 (普通与 3D 模式) 与 {@code /flan add}、
 * {@code add rect}、{@code add all} 都走它。候选领地碰到自管区或它外围 8 格就在 HEAD 拒绝 (返回 false), OP 例外。
 */
@Pseudo
@Mixin(targets = FlanHookTargets.CLAIM_STORAGE, remap = false)
public abstract class FlanCreateClaimMixin {

    @Inject(method = FlanHookTargets.CREATE_CLAIM, at = @At("HEAD"), cancellable = true, remap = false)
    private void miningdim$guardPersonalCreate(BlockPos pos1, BlockPos pos2, ServerPlayer player,
                                               CallbackInfoReturnable<Boolean> callback) {
        if (FlanClaimGuard.createDenied(pos1, pos2, player)) {
            callback.setReturnValue(false);
        }
    }
}

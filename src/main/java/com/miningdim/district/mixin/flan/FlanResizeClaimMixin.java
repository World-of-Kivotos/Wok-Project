package com.miningdim.district.mixin.flan;

import com.miningdim.district.flan.real.FlanClaimGuard;
import com.miningdim.district.flan.real.FlanHookTargets;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * F2 (设计文档 22.20): Flan 的 {@code ClaimStorage.resizeClaim} —— 金锄头拖角与 {@code /flan expand} 都走它。个人领地
 * 改范围时不许新占自管区与外围 8 格里的列, 在 HEAD 拒绝 (返回 false); 管理员领地不管, OP 例外。金锄头的编辑
 * 状态与登记不一致时对谁都拒 (22.22)。Flan 的 {@code Claim} 参数与目标实例 ({@code this}, 即
 * {@code ClaimStorage}) 一律当 {@code Object} 传: mixin 包里不许出现 Flan 的包名 (边界规则), 转型在 flan/real 里做。
 */
@Pseudo
@Mixin(targets = FlanHookTargets.CLAIM_STORAGE, remap = false)
public abstract class FlanResizeClaimMixin {

    @Inject(method = FlanHookTargets.RESIZE_CLAIM, at = @At("HEAD"), cancellable = true, remap = false)
    private void miningdim$guardPersonalResize(@Coerce Object claim, BlockPos from, BlockPos to, ServerPlayer player,
                                               CallbackInfoReturnable<Boolean> callback) {
        if (FlanClaimGuard.resizeDenied(this, claim, from, to, player)) {
            callback.setReturnValue(false);
        }
    }
}

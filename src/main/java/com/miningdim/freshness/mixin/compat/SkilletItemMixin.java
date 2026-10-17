package com.miningdim.freshness.mixin.compat;

import com.miningdim.freshness.FreshnessFdHandCooking;
import com.miningdim.freshness.FreshnessFdTargets;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FD 煎锅手持烹饪 (#13) 的 (开发环境: 官方方法名)。
 *
 * <p>{@code finishUsingItem} 是 FD **覆盖的 MC 方法**: 开发环境按官方名 (finishUsingItem)、正式服用 SRG 名
 * (m_5922_)。两个名字各写一个转发 mixin, 环境里只会有其中一个对得上 —— 另一个由可选配置的
 * {@code defaultRequire = 0} 静默跳过, 于是开发与正式服都织得上。</p>
 *
 * <p>判定与记账全在 {@link FreshnessFdHandCooking}: 烂原料不出品、成品按原料继承 (加热 +25%)。</p>
 */
@Pseudo
@Mixin(targets = FreshnessFdTargets.SKILLET_ITEM, remap = false)
public abstract class SkilletItemMixin {

    @Inject(method = "finishUsingItem", at = @At("HEAD"), cancellable = true, remap = false)
    private void miningdim$beginHandCooking(ItemStack skillet, Level level, LivingEntity user,
                                            CallbackInfoReturnable<ItemStack> callback) {
        if (FreshnessFdHandCooking.begin(skillet, level)) {
            callback.setReturnValue(skillet);
        }
    }

    @Inject(method = "finishUsingItem", at = @At("RETURN"), remap = false)
    private void miningdim$stampHandCookedProduct(ItemStack skillet, Level level, LivingEntity user,
                                                  CallbackInfoReturnable<ItemStack> callback) {
        FreshnessFdHandCooking.stampAtReturn(level, user);
    }
}
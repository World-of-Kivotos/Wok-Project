package com.miningdim.freshness.mixin;

import com.miningdim.freshness.FreshnessHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

/**
 * 漏斗: 每次搬运前先给自己那几格结算 (盖章 / 把过期的换成腐烂物), **不做任何合并**
 * (Spoilage_Granary_DesignSpec「观察点」漏斗行、反漏洞 #10)。
 *
 * 注入 {@code tryMoveItems} 的 HEAD: 它是漏斗每 tick 的搬运入口 (吸进来、推出去都经过它), 在这里结算,
 * 保证「漏斗把过期原料喂进第三方机器」这条路走不通; 不注入 {@code suckInItems} 是因为吸进来的东西随后
 * 一定会走 tryMoveItems。混入私有静态方法, 签名按 1.20.1 锁定。
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {

    @Inject(method = "tryMoveItems", at = @At("HEAD"))
    private static void freshness$settleHopperSlots(Level level, BlockPos pos, BlockState state,
                                                    HopperBlockEntity hopper, BooleanSupplier moving,
                                                    CallbackInfoReturnable<Boolean> cir) {
        FreshnessHooks.settleHopper(hopper, level, pos);
    }
}

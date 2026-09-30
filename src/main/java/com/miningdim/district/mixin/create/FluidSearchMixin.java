package com.miningdim.district.mixin.create;

import com.miningdim.district.guard.create.CreateGuards;
import com.miningdim.district.guard.create.CreateHookTargets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * C11 (设计文档 22.6): 软管滑轮抽液的洪水搜索 (范围 hosePulleyRange = 128)。search 里两处 getFluidState, 受保护的格子
 * 返回空流体: 搜索绕开自管区。INVOKE 指向 Minecraft 方法, remap = true (正式服是 SRG 名 m_6425_)。
 *
 * <p>两处都要织进去: 写成 {@code expect = 2} 而不是 {@code require = 2} (22.7): require 不满足时 Mixin 0.8.5 抛
 * InjectionError, 不看配置的 required, 服务端起不来; expect 平时不被 Mixin 核对, 由插件的织入核对 (MixinHandlerScan)
 * 数够两处才算应用上。
 */
@Pseudo
@Mixin(targets = CreateHookTargets.FLUID_MANIPULATION, remap = false)
public abstract class FluidSearchMixin {

    @Redirect(method = CreateHookTargets.SEARCH,
            at = @At(value = "INVOKE", target = CreateHookTargets.LEVEL_GET_FLUID_STATE, remap = true),
            remap = false, expect = 2)
    private FluidState miningdim$guardDistrict(Level level, BlockPos pos) {
        return CreateGuards.fluidMayReach(level, pos) ? level.getFluidState(pos) : Fluids.EMPTY.defaultFluidState();
    }
}

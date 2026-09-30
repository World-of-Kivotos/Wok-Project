package com.miningdim.district.mixin.create;

import com.miningdim.district.guard.create.CreateGuards;
import com.miningdim.district.guard.create.CreateHookTargets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * C8 (设计文档 22.6): 平移装置 (机械活塞、绳索滑轮、电梯滑轮、龙门) 的地形碰撞。isCollidingWithWorld 里受保护的格子报
 * "没加载": 没加载的区块本来就算碰撞, 装置停在边界外。INVOKE 指向 Minecraft 方法, 必须显式 remap = true (正式服是 SRG 名
 * m_46749_)。
 */
@Pseudo
@Mixin(targets = CreateHookTargets.COLLIDER, remap = false)
public abstract class ContraptionColliderMixin {

    @Redirect(method = CreateHookTargets.IS_COLLIDING_WITH_WORLD,
            at = @At(value = "INVOKE", target = CreateHookTargets.LEVEL_IS_LOADED, remap = true), remap = false)
    private static boolean miningdim$guardDistrict(Level level, BlockPos pos) {
        return CreateGuards.colliderMayEnter(level, pos) && level.isLoaded(pos);
    }
}

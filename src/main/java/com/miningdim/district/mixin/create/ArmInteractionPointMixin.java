package com.miningdim.district.mixin.create;

import com.miningdim.district.guard.create.CreateGuards;
import com.miningdim.district.guard.create.CreateHookTargets;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * C17 (设计文档 22.6): 机械臂的交互点。服务端不查交互点的距离与归属 (mechanicalArmRange 只在客户端查; ArmPlacementPacket
 * 对任何已加载的机械臂照单全收; 蓝图炮打印的机械臂带着交互点)。ArmInteractionPoint.deserialize 是交互点从 NBT 读出来的
 * 唯一入口, 它本来就会在类型不认识时返回 null、调用方跳过; 交互点落到别的区域 (单向口径) 时同样返回 null, 在它读目标
 * 方块之前 (远处的点也不会因此同步加载区块)。
 */
@Pseudo
@Mixin(targets = CreateHookTargets.ARM_POINT, remap = false)
public abstract class ArmInteractionPointMixin {

    @Inject(method = CreateHookTargets.ARM_DESERIALIZE, at = @At("HEAD"), cancellable = true, remap = false)
    private static void miningdim$guardDistrict(CompoundTag tag, Level level, BlockPos anchor,
                                                CallbackInfoReturnable<Object> callback) {
        if (!CreateGuards.armPointMayReach(level, anchor, tag)) {
            callback.setReturnValue(null);
        }
    }
}

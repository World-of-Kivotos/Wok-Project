package com.miningdim.district.mixin.create;

import com.miningdim.district.guard.create.CreateGuards;
import com.miningdim.district.guard.create.CreateHookTargets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * C18 (设计文档 22.6): 显示链接。updateGatheredData 读来源、再经 transferData 写目标 (告示牌、讲台、显示板的字); tickSource、
 * 失去红石信号、改配置的网络包都走它。目标偏移可以来自蓝图 (writeSafe 带着 TargetOffset), 服务端不查距离。目标或来源
 * 落到别的区域 (单向口径) 时这一次不读不写。
 */
@Pseudo
@Mixin(targets = CreateHookTargets.DISPLAY_LINK, remap = false)
public abstract class DisplayLinkMixin {

    @Shadow(remap = false)
    public abstract BlockPos getSourcePosition();

    @Shadow(remap = false)
    public abstract BlockPos getTargetPosition();

    @Inject(method = CreateHookTargets.DISPLAY_UPDATE, at = @At("HEAD"), cancellable = true, remap = false)
    private void miningdim$guardDistrict(CallbackInfo callback) {
        BlockEntity self = (BlockEntity) (Object) this;
        if (!CreateGuards.displayLinkMayUpdate(self.getLevel(), self.getBlockPos(), getSourcePosition(),
                getTargetPosition())) {
            callback.cancel();
        }
    }
}

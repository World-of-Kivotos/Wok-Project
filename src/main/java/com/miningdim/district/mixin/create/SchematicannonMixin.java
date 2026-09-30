package com.miningdim.district.mixin.create;

import com.miningdim.district.guard.create.CreateGuards;
import com.miningdim.district.guard.create.CreateHookTargets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * C2 (设计文档 22.6): 蓝图炮。目标受保护, 或目标在外围 8 格内且要放的是机器 → 这一格跳过, 不耗火药和材料。
 */
@Pseudo
@Mixin(targets = CreateHookTargets.SCHEMATICANNON, remap = false)
public abstract class SchematicannonMixin {

    @Inject(method = CreateHookTargets.SHOULD_PLACE, at = @At("HEAD"), cancellable = true, remap = false)
    private void miningdim$guardDistrict(BlockPos pos, BlockState state, BlockEntity blockEntity,
                                         BlockState toReplace, BlockState toReplaceOther, boolean isNormalCube,
                                         CallbackInfoReturnable<Boolean> callback) {
        if (!CreateGuards.schematicannonMayPlace(((BlockEntity) (Object) this).getLevel(), pos, state)) {
            callback.setReturnValue(false);
        }
    }
}

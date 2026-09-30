package com.miningdim.district.mixin.create;

import com.miningdim.district.guard.create.CreateGuards;
import com.miningdim.district.guard.create.CreateHookTargets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * C9 (设计文档 22.6): 装置拆装。轴承、火车、矿车这类没有地形碰撞的装置转进、开进区里之后拆装, 机械动力会先
 * destroyBlock 原地方块再放上去; 落点受保护, 或落点在外围 8 格内且是机器 → 照抄机械动力自己"被挡住"那一支 (播放 2001、
 * 掉成物品、原地方块不动), 返回 true 跳过这一格。装置里这几格的容器内容随之丢失, 与机械动力原本的行为相同。
 */
@Pseudo
@Mixin(targets = CreateHookTargets.CONTRAPTION, remap = false)
public abstract class ContraptionDisassemblyMixin {

    @Shadow(remap = false)
    protected abstract boolean customBlockPlacement(LevelAccessor level, BlockPos pos, BlockState state);

    @Redirect(method = CreateHookTargets.ADD_BLOCKS_TO_WORLD,
            at = @At(value = "INVOKE", target = CreateHookTargets.CUSTOM_BLOCK_PLACEMENT, remap = false),
            remap = false)
    private boolean miningdim$guardDistrict(@Coerce Object contraption, LevelAccessor level, BlockPos pos,
                                            BlockState state) {
        if (!CreateGuards.contraptionMayPlace(level, pos, state)) {
            CreateGuards.dropBlockedContraptionBlock(level, pos, state);
            return true;
        }
        return customBlockPlacement(level, pos, state);
    }
}

package com.miningdim.job.farmer.granary.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.util.GeckoLibUtil;

public final class GranaryVentBlockEntity extends BlockEntity implements GeoBlockEntity {

    private final AnimatableInstanceCache animationCache = GeckoLibUtil.createInstanceCache(this);

    public GranaryVentBlockEntity(BlockPos pos, BlockState state) {
        super(GranaryBlocks.VENT_ENTITY.get(), pos, state);
    }

    // 静态骨骼模型没有动画，不注册动画控制器。
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return animationCache;
    }
}
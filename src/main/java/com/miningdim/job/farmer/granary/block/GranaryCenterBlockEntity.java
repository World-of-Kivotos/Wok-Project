package com.miningdim.job.farmer.granary.block;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * 粮仓账台方块实体 (Spoilage_Granary_DesignSpec「部件」「物品数据与存档」: 账台 BE 只留 Owner、Gid、CreatedTick)。
 *
 * 账台本身不装东西 —— 它只是粮仓的「大脑」: 归属、档位、成员、结构页都挂在这三样键上, 后续分片再加。
 */
public final class GranaryCenterBlockEntity extends BlockEntity implements GeoBlockEntity {

    private static final String KEY_OWNER = "Owner";
    private static final String KEY_GID = "Gid";
    private static final String KEY_CREATED = "CreatedTick";

    private final AnimatableInstanceCache animationCache = GeckoLibUtil.createInstanceCache(this);

    private UUID owner;
    private long granaryId;
    private long createdTick;

    public GranaryCenterBlockEntity(BlockPos pos, BlockState state) {
        super(GranaryBlocks.CENTER_ENTITY.get(), pos, state);
    }

    // 静态骨骼模型没有动画，不注册动画控制器。
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return animationCache;
    }

    public UUID owner() {
        return owner;
    }

    public long granaryId() {
        return granaryId;
    }

    public long createdTick() {
        return createdTick;
    }

    public void assignOwner(UUID owner, long granaryId, long createdTick) {
        this.owner = owner;
        this.granaryId = granaryId;
        this.createdTick = createdTick;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (owner != null) {
            tag.putUUID(KEY_OWNER, owner);
        }
        tag.putLong(KEY_GID, granaryId);
        tag.putLong(KEY_CREATED, createdTick);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        owner = tag.hasUUID(KEY_OWNER) ? tag.getUUID(KEY_OWNER) : null;
        granaryId = tag.getLong(KEY_GID);
        createdTick = tag.getLong(KEY_CREATED);
    }
}

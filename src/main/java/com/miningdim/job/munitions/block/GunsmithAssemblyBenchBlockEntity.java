package com.miningdim.job.munitions.block;

import com.miningdim.job.munitions.ModMunitionsBlockEntities;
import com.miningdim.job.munitions.ModMunitionsSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public final class GunsmithAssemblyBenchBlockEntity extends BlockEntity {

    public static final int DEMO_DURATION_TICKS = 160;
    private static final int WELD_SOUND_INTERVAL_TICKS = 24;
    private static final String K_ANIMATION_END = "AnimationEndTick";

    private long animationEndTick;
    private long nextWeldSoundTick;

    public GunsmithAssemblyBenchBlockEntity(BlockPos pos, BlockState state) {
        super(ModMunitionsBlockEntities.GUNSMITH_ASSEMBLY_BENCH.get(), pos, state);
    }

    public boolean startAssembly(int durationTicks) {
        if (durationTicks <= 0) {
            throw new IllegalArgumentException("durationTicks must be positive");
        }
        if (level == null || level.isClientSide || isAnimating()) {
            return false;
        }
        long now = level.getGameTime();
        animationEndTick = now + durationTicks;
        nextWeldSoundTick = now + WELD_SOUND_INTERVAL_TICKS;
        setActiveState(true);
        playWeldSound();
        setChanged();
        return true;
    }

    public void serverTick() {
        if (level == null || level.isClientSide) {
            return;
        }
        if (animationEndTick == 0L) {
            if (getBlockState().getValue(GunsmithAssemblyBenchBlock.ACTIVE)) {
                setActiveState(false);
            }
            return;
        }
        long now = level.getGameTime();
        if (now >= animationEndTick) {
            animationEndTick = 0L;
            nextWeldSoundTick = 0L;
            setActiveState(false);
            setChanged();
            return;
        }
        setActiveState(true);
        if (now >= nextWeldSoundTick) {
            playWeldSound();
            nextWeldSoundTick = now + WELD_SOUND_INTERVAL_TICKS;
        }
    }

    public boolean isAnimating() {
        if (level == null) {
            return animationEndTick > 0L;
        }
        if (level.isClientSide) {
            return getBlockState().getValue(GunsmithAssemblyBenchBlock.ACTIVE);
        }
        return animationEndTick > level.getGameTime();
    }

    public float animationTicks(float partialTick) {
        if (level == null) {
            return 0.0F;
        }
        return level.getGameTime() + partialTick;
    }

    private void setActiveState(boolean active) {
        if (level == null) {
            return;
        }
        BlockState state = getBlockState();
        if (state.getBlock() instanceof GunsmithAssemblyBenchBlock
                && GunsmithAssemblyBenchBlock.isMain(state)) {
            GunsmithAssemblyBenchBlock.setStructureActive(level, worldPosition, state, active);
        }
    }

    private void playWeldSound() {
        if (level == null) {
            return;
        }
        float pitch = 0.94F + level.random.nextFloat() * 0.14F;
        level.playSound(null, worldPosition, ModMunitionsSounds.MUNITIONS_BENCH_WELD.get(),
                SoundSource.BLOCKS, 0.34F, pitch);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        writeAnimationState(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        readAnimationState(tag);
    }

    private void writeAnimationState(CompoundTag tag) {
        tag.putLong(K_ANIMATION_END, animationEndTick);
    }

    private void readAnimationState(CompoundTag tag) {
        // Missing animation keys represent the initial idle state for pre-feature chunks.
        animationEndTick = tag.getLong(K_ANIMATION_END);
    }

    @Override
    public AABB getRenderBoundingBox() {
        BlockState state = getBlockState();
        if (!(state.getBlock() instanceof GunsmithAssemblyBenchBlock)) {
            return super.getRenderBoundingBox();
        }
        DirectionBounds bounds = DirectionBounds.from(worldPosition, state.getValue(GunsmithAssemblyBenchBlock.FACING));
        return new AABB(bounds.minX, worldPosition.getY(), bounds.minZ,
                bounds.maxX + 1.0D, worldPosition.getY() + 2.25D, bounds.maxZ + 1.0D);
    }

    private record DirectionBounds(int minX, int maxX, int minZ, int maxZ) {

        private static DirectionBounds from(BlockPos mainPos, net.minecraft.core.Direction facing) {
            int minX = mainPos.getX();
            int maxX = mainPos.getX();
            int minZ = mainPos.getZ();
            int maxZ = mainPos.getZ();
            for (GunsmithAssemblyBenchBlock.Part part : GunsmithAssemblyBenchBlock.Part.values()) {
                BlockPos partPos = GunsmithAssemblyBenchBlock.partPos(mainPos, facing, part);
                minX = Math.min(minX, partPos.getX());
                maxX = Math.max(maxX, partPos.getX());
                minZ = Math.min(minZ, partPos.getZ());
                maxZ = Math.max(maxZ, partPos.getZ());
            }
            return new DirectionBounds(minX, maxX, minZ, maxZ);
        }
    }
}

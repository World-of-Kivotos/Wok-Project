package com.miningdim.power.rubber;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 割胶冷却只依赖绝对游戏时间，避免为每根树干注册方块 tick。
 * 物品化时由 {@link BlockEntity#saveToItem} 保留该时刻，拆放不会重置冷却。
 */
public final class RubberLogBlockEntity extends BlockEntity {

    public static final long TAP_COOLDOWN_TICKS = 24_000L;

    private long nextTapGameTime;
    private boolean wasTapped;

    public RubberLogBlockEntity(BlockPos pos, BlockState state) {
        super(PowerRubberRegistry.RUBBER_LOG_BE.get(), pos, state);
    }

    public boolean tryTap(long gameTime) {
        if (wasTapped && gameTime < nextTapGameTime) {
            return false;
        }
        wasTapped = true;
        nextTapGameTime = gameTime + TAP_COOLDOWN_TICKS;
        setChanged();
        return true;
    }

    public long nextTapGameTime() {
        return nextTapGameTime;
    }

    public boolean wasTapped() {
        return wasTapped;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putLong("NextTapGameTime", nextTapGameTime);
        tag.putBoolean("WasTapped", wasTapped);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        nextTapGameTime = tag.getLong("NextTapGameTime");
        wasTapped = tag.getBoolean("WasTapped");
    }
}

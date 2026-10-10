package com.miningdim.job.chef.station;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.jetbrains.annotations.Nullable;

/**
 * 冷藏备餐台 (备餐台 B 不锈钢): 工作中掀盖绕后沿铰链翻起 45°, 盖子和铜把手最高到 y≈22.4, 会伸进上方那一格。
 * 方案 prep_b 为此预备了"工作中但掀盖合着"的模型 prep_counter_steel_on_closed, 要求上方有东西时换用它。
 *
 * {@link #LID_BLOCKED}: 上方那格有东西时为 true, blockstate 在 lit=true 时据此选 _on 或 _on_closed;
 * 待机 (lit=false) 掀盖本来就合着, 两个取值用同一个待机模型。放置时判一次, 之后随上方的邻居形状更新重算。
 *
 * "有东西"按上方方块的选择框 (getShape) 是否为空判断, 比方案说明里写的"碰撞形状非空"更严: 火把、花、
 * 告示牌这类没有碰撞箱、但看得见的方块同样会和翻开的盖子穿模, 也要让盖子合着。
 */
public final class RefrigeratedPrepBlock extends CookingStationBlock {

    public static final BooleanProperty LID_BLOCKED = BooleanProperty.create("lid_blocked");

    RefrigeratedPrepBlock(BlockBehaviour.Properties properties, StationShape shape,
                          StationParticles.Emitter particles) {
        super(properties, Kind.PREP_COUNTER, Style.STEEL, shape, particles);
        registerDefaultState(defaultBlockState().setValue(LID_BLOCKED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(LID_BLOCKED);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState placed = super.getStateForPlacement(context);
        if (placed == null) {
            return null;
        }
        BlockPos above = context.getClickedPos().above();
        return placed.setValue(LID_BLOCKED, blocksLid(context.getLevel().getBlockState(above), context.getLevel(), above));
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (direction != Direction.UP) {
            return state;
        }
        return state.setValue(LID_BLOCKED, blocksLid(neighborState, level, neighborPos));
    }

    /** 上方方块是否会挡住翻开的掀盖: 它的选择框不为空。 */
    static boolean blocksLid(BlockState above, BlockGetter level, BlockPos abovePos) {
        return !above.getShape(level, abovePos).isEmpty();
    }
}

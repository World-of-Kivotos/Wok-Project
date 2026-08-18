package com.miningdim.power.cable;

import com.miningdim.power.grid.EnergyNetworkManager;
import com.miningdim.power.grid.CoolingControllerAttachment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import org.jetbrains.annotations.Nullable;

/**
 * 有线 FE 线缆方块 (每级导体各一, 由 {@link CableProfile} 参数化)。实现 {@link EntityBlock} 挂线缆 BE,
 * 但刻意不提供 ticker: 搬电由 {@link EnergyNetworkManager} 集中做 (线缆零 per-tick 成本)。
 * neighborChanged 时只标脏端点集, 让相邻机器/发电机的增减在下次 settlement 被重扫到, 不在此做任何遍历。
 */
public final class EnergyCableBlock extends Block implements EntityBlock {

    public static final BooleanProperty DOWN = BlockStateProperties.DOWN;
    public static final BooleanProperty UP = BlockStateProperties.UP;
    public static final BooleanProperty NORTH = BlockStateProperties.NORTH;
    public static final BooleanProperty SOUTH = BlockStateProperties.SOUTH;
    public static final BooleanProperty WEST = BlockStateProperties.WEST;
    public static final BooleanProperty EAST = BlockStateProperties.EAST;

    private static final VoxelShape CENTER_SHAPE = Block.box(6.0D, 6.0D, 6.0D, 10.0D, 10.0D, 10.0D);
    private static final VoxelShape[] ARM_SHAPES = createArmShapes();
    private static final VoxelShape[] SHAPES = createShapes();

    private final CableProfile material;

    public EnergyCableBlock(CableProfile material, Properties properties) {
        super(properties);
        this.material = material;
        registerDefaultState(stateDefinition.any()
                .setValue(DOWN, false)
                .setValue(UP, false)
                .setValue(NORTH, false)
                .setValue(SOUTH, false)
                .setValue(WEST, false)
                .setValue(EAST, false));
    }

    public CableProfile material() {
        return material;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(DOWN, UP, NORTH, SOUTH, WEST, EAST);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return connectionState(context.getLevel(), context.getClickedPos(), defaultBlockState());
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return state.setValue(connectionProperty(direction),
                connectsTo(level, neighborPos, direction, neighborState));
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[shapeIndex(state)];
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                        CollisionContext context) {
        return SHAPES[shapeIndex(state)];
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EnergyCableBlockEntity(pos, state);
    }

    public static void refreshConnectionState(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof EnergyCableBlock cable)) {
            throw new IllegalStateException("energy cable block entity at non-cable state " + pos);
        }
        BlockState repaired = cable.connectionState(level, pos, state);
        if (repaired != state) {
            level.setBlock(pos, repaired, Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                BlockPos neighborPos, boolean movedByPiston) {
        if (level instanceof ServerLevel serverLevel) {
            EnergyNetworkManager.get(serverLevel).markEndpointsDirty(pos);
        }
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
    }

    private BlockState connectionState(LevelAccessor level, BlockPos pos, BlockState state) {
        BlockState connected = state;
        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = pos.relative(direction);
            if (level instanceof ServerLevel serverLevel && !serverLevel.hasChunkAt(neighborPos)) {
                connected = connected.setValue(connectionProperty(direction), false);
                continue;
            }
            connected = connected.setValue(connectionProperty(direction),
                    connectsTo(level, neighborPos, direction, level.getBlockState(neighborPos)));
        }
        return connected;
    }

    private static boolean connectsTo(LevelAccessor level, BlockPos neighborPos, Direction direction,
                                      BlockState neighborState) {
        if (level instanceof ServerLevel serverLevel && !serverLevel.hasChunkAt(neighborPos)) {
            return false;
        }
        if (neighborState.getBlock() instanceof EnergyCableBlock) {
            return true;
        }
        BlockEntity neighbor = level.getBlockEntity(neighborPos);
        if (neighbor instanceof CoolingControllerAttachment attachment
                && attachment.controlledCablePos().equals(neighborPos.relative(direction.getOpposite()))) {
            return true;
        }
        return neighbor != null
                && neighbor.getCapability(ForgeCapabilities.ENERGY, direction.getOpposite()).isPresent();
    }

    private static BooleanProperty connectionProperty(Direction direction) {
        return switch (direction) {
            case DOWN -> DOWN;
            case UP -> UP;
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case EAST -> EAST;
        };
    }

    private static int shapeIndex(BlockState state) {
        int index = 0;
        for (Direction direction : Direction.values()) {
            if (state.getValue(connectionProperty(direction))) {
                index |= 1 << direction.get3DDataValue();
            }
        }
        return index;
    }

    private static VoxelShape[] createArmShapes() {
        VoxelShape[] arms = new VoxelShape[Direction.values().length];
        arms[Direction.DOWN.get3DDataValue()] = Block.box(6.0D, 0.0D, 6.0D, 10.0D, 6.0D, 10.0D);
        arms[Direction.UP.get3DDataValue()] = Block.box(6.0D, 10.0D, 6.0D, 10.0D, 16.0D, 10.0D);
        arms[Direction.NORTH.get3DDataValue()] = Block.box(6.0D, 6.0D, 0.0D, 10.0D, 10.0D, 6.0D);
        arms[Direction.SOUTH.get3DDataValue()] = Block.box(6.0D, 6.0D, 10.0D, 10.0D, 10.0D, 16.0D);
        arms[Direction.WEST.get3DDataValue()] = Block.box(0.0D, 6.0D, 6.0D, 6.0D, 10.0D, 10.0D);
        arms[Direction.EAST.get3DDataValue()] = Block.box(10.0D, 6.0D, 6.0D, 16.0D, 10.0D, 10.0D);
        return arms;
    }

    private static VoxelShape[] createShapes() {
        VoxelShape[] shapes = new VoxelShape[1 << Direction.values().length];
        for (int index = 0; index < shapes.length; index++) {
            VoxelShape shape = CENTER_SHAPE;
            for (Direction direction : Direction.values()) {
                if ((index & (1 << direction.get3DDataValue())) != 0) {
                    shape = Shapes.or(shape, ARM_SHAPES[direction.get3DDataValue()]);
                }
            }
            shapes[index] = shape;
        }
        return shapes;
    }
}

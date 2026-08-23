package com.miningdim.job.chef;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * 调味台方块 (Chef_Job_DesignSpec 第四章; 5 档实例 低/中/高/超凡/闪耀, 同工程师五档生产台思路)。
 *
 * 每档携带 {@link #tierCap()} = 本档能产出的最高品质上限 (做菜时与厨师等级取 min 双重封顶)。右键服务端开 GUI
 * (NetworkHooks.openScreen)。继承普通 {@link Block} 实现 {@link EntityBlock} (与 EntranceBlock 同范式, 保
 * RenderShape.MODEL 正常渲染)。服务端权威: 客户端 use 仅回 SUCCESS 触发挥手。
 */
public final class SeasoningTableBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** false=主台/左半，拥有方块实体；true=右半，只负责模型、碰撞和交互转发。 */
    public static final BooleanProperty SECONDARY = BooleanProperty.create("secondary");

    private static final VoxelShape PRIMARY_NORTH_SHAPE = Shapes.or(
            Block.box(0.0D, 8.0D, 0.0D, 16.0D, 11.0D, 16.0D),
            Block.box(2.0D, 3.0D, 2.0D, 16.0D, 4.0D, 14.0D),
            Block.box(1.0D, 0.0D, 1.0D, 3.0D, 8.0D, 3.0D),
            Block.box(1.0D, 0.0D, 13.0D, 3.0D, 8.0D, 15.0D),
            Block.box(0.0D, 11.0D, 14.0D, 16.0D, 16.0D, 16.0D),
            Block.box(1.0D, 11.0D, 1.0D, 15.0D, 15.0D, 13.0D));
    private static final VoxelShape SECONDARY_NORTH_SHAPE = Shapes.or(
            Block.box(0.0D, 8.0D, 0.0D, 16.0D, 11.0D, 16.0D),
            Block.box(0.0D, 3.0D, 2.0D, 14.0D, 4.0D, 14.0D),
            Block.box(13.0D, 0.0D, 1.0D, 15.0D, 8.0D, 3.0D),
            Block.box(13.0D, 0.0D, 13.0D, 15.0D, 8.0D, 15.0D),
            Block.box(0.0D, 11.0D, 14.0D, 16.0D, 16.0D, 16.0D),
            Block.box(7.0D, 11.0D, 9.0D, 15.5D, 14.5D, 14.0D));
    private static final Map<Direction, VoxelShape> PRIMARY_SHAPES = rotatedShapes(PRIMARY_NORTH_SHAPE);
    private static final Map<Direction, VoxelShape> SECONDARY_SHAPES = rotatedShapes(SECONDARY_NORTH_SHAPE);

    private final ChefQuality tierCap;

    public SeasoningTableBlock(BlockBehaviour.Properties properties, ChefQuality tierCap) {
        super(properties);
        this.tierCap = tierCap;
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(SECONDARY, false));
    }

    /** 本档调味台能产出的最高品质 (与厨师等级取 min)。 */
    public ChefQuality tierCap() {
        return tierCap;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, SECONDARY);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockPos secondaryPos = context.getClickedPos().relative(facing.getClockWise());
        if (!context.getLevel().getWorldBorder().isWithinBounds(secondaryPos)
                || !context.getLevel().getBlockState(secondaryPos).canBeReplaced(context)) {
            return null;
        }
        return defaultBlockState().setValue(FACING, facing).setValue(SECONDARY, false);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && !state.getValue(SECONDARY)) {
            BlockPos secondaryPos = connectedPos(pos, state);
            level.setBlock(secondaryPos, state.setValue(SECONDARY, true), Block.UPDATE_ALL);
        }
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Map<Direction, VoxelShape> shapes = state.getValue(SECONDARY) ? SECONDARY_SHAPES : PRIMARY_SHAPES;
        return shapes.get(state.getValue(FACING));
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                        CollisionContext context) {
        return getShape(state, level, pos, context);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        Direction connection = connectionDirection(state);
        // 旧存档中的调味台没有 secondary 属性，加载后会成为主台。主台缺少右半时必须保留，
        // 待玩家交互时再安全补齐；否则任意邻居更新都会把旧调味台直接删除。
        if (state.getValue(SECONDARY) && direction == connection && !isMatchingHalf(state, neighborState)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        BlockPos primaryPos = primaryPos(pos, state);
        BlockState primaryState = level.getBlockState(primaryPos);
        if (primaryState.is(this) && !primaryState.getValue(SECONDARY)) {
            ensureSecondary(level, primaryPos, primaryState);
        }
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(primaryPos) instanceof SeasoningTableBlockEntity be) {
            NetworkHooks.openScreen(serverPlayer, be, buf -> buf.writeBlockPos(primaryPos));
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock())) {
            BlockPos otherPos = connectedPos(pos, state);
            BlockState otherState = level.getBlockState(otherPos);
            if (isMatchingHalf(state, otherState)) {
                // 只让玩家实际破坏的半边掉落调味台物品；另一半静默移除。
                // 如果被静默移除的是主台，其库存仍由下面的事务清理逻辑正常掉出。
                level.setBlock(otherPos, Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
            }
            if (level.getBlockEntity(pos) instanceof SeasoningTableBlockEntity be) {
                be.cancelForBlockBreak();
                for (var stack : be.dropContents()) {
                    net.minecraft.world.Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
                }
            }
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(SECONDARY) ? null : new SeasoningTableBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null; // 火候推进仅服务端权威 (第四章防作弊)。
        }
        if (state.getValue(SECONDARY)) {
            return null;
        }
        return createTickerHelper(type, ChefBlockEntities.SEASONING_TABLE.get(),
                (lvl, pos, st, be) -> be.serverTick());
    }

    private static BlockPos connectedPos(BlockPos pos, BlockState state) {
        return pos.relative(connectionDirection(state));
    }

    private static BlockPos primaryPos(BlockPos pos, BlockState state) {
        return state.getValue(SECONDARY) ? connectedPos(pos, state) : pos;
    }

    private void ensureSecondary(Level level, BlockPos primaryPos, BlockState primaryState) {
        BlockPos secondaryPos = connectedPos(primaryPos, primaryState);
        if (level.getBlockState(secondaryPos).isAir()) {
            level.setBlock(secondaryPos, primaryState.setValue(SECONDARY, true), Block.UPDATE_ALL);
        }
    }

    private boolean isMatchingHalf(BlockState state, BlockState otherState) {
        return otherState.is(this)
                && otherState.getValue(FACING) == state.getValue(FACING)
                && otherState.getValue(SECONDARY) != state.getValue(SECONDARY);
    }

    private static Direction connectionDirection(BlockState state) {
        Direction right = state.getValue(FACING).getClockWise();
        return state.getValue(SECONDARY) ? right.getOpposite() : right;
    }

    private static Map<Direction, VoxelShape> rotatedShapes(VoxelShape north) {
        Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
        shapes.put(Direction.NORTH, north);
        VoxelShape current = north;
        for (Direction direction : new Direction[]{Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            current = rotateClockwise(current);
            shapes.put(direction, current);
        }
        return shapes;
    }

    private static VoxelShape rotateClockwise(VoxelShape source) {
        VoxelShape[] result = {Shapes.empty()};
        source.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) ->
                result[0] = Shapes.or(result[0], Shapes.box(
                        1.0D - maxZ, minY, minX,
                        1.0D - minZ, maxY, maxX)));
        return result[0].optimize();
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static <E extends BlockEntity, A extends BlockEntity> BlockEntityTicker<A> createTickerHelper(
            BlockEntityType<A> actual, BlockEntityType<E> expected, BlockEntityTicker<? super E> ticker) {
        return expected == actual ? (BlockEntityTicker<A>) ticker : null;
    }
}

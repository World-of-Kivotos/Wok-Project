package com.miningdim.job.brewer.station;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A real 2-wide by 2-high brewing workstation. The lower-left part owns the
 * block entity; every other part forwards interaction and lifecycle work to it.
 */
public final class BrewingStationBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    private static final List<Part> MULTIBLOCK_PARTS = List.of(
            Part.LOWER_LEFT, Part.LOWER_RIGHT, Part.UPPER_LEFT, Part.UPPER_RIGHT);

    private static final VoxelShape LOWER_LEFT_SHAPE = Shapes.or(
            box(0, 13, 1, 16, 16, 16),
            box(1, 0, 2, 3, 13, 15),
            box(14, 0, 2, 16, 13, 15),
            box(2, 8, 1, 11, 13, 8));
    private static final VoxelShape LOWER_RIGHT_SHAPE = Shapes.or(
            box(0, 13, 1, 16, 16, 16),
            box(0, 0, 2, 2, 13, 15),
            box(13, 0, 2, 15, 13, 15),
            box(2, 2, 3, 14, 4, 14),
            box(9, 4, 5, 14, 13, 14));
    private static final VoxelShape UPPER_LEFT_SHAPE = Shapes.or(
            box(0, 0, 13, 16, 6, 16),
            box(0, 0, 13, 2, 16, 16),
            box(14, 0, 13, 16, 16, 16),
            box(0, 14, 2, 16, 16, 16),
            box(2, 0, 4, 12, 11, 15),
            box(6, 8, 5, 10, 15, 14));
    private static final VoxelShape UPPER_RIGHT_SHAPE = Shapes.or(
            box(0, 0, 13, 16, 6, 16),
            box(0, 0, 13, 2, 16, 16),
            box(14, 0, 13, 16, 16, 16),
            box(0, 14, 2, 16, 16, 16),
            box(4, 0, 4, 14, 13, 15),
            box(1, 3, 2, 6, 12, 14));

    public BrewingStationBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(PART, Part.LEGACY));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape base = switch (state.getValue(PART)) {
            case LEGACY -> Shapes.block();
            case LOWER_LEFT -> LOWER_LEFT_SHAPE;
            case LOWER_RIGHT -> LOWER_RIGHT_SHAPE;
            case UPPER_LEFT -> UPPER_LEFT_SHAPE;
            case UPPER_RIGHT -> UPPER_RIGHT_SHAPE;
        };
        return rotateNorthShape(base, state.getValue(FACING));
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockPos mainPos = context.getClickedPos();
        CollisionContext collisionContext = context.getPlayer() == null
                ? CollisionContext.empty() : CollisionContext.of(context.getPlayer());
        for (Part part : MULTIBLOCK_PARTS) {
            if (part == Part.LOWER_LEFT) {
                continue;
            }
            BlockPos targetPos = partPos(mainPos, facing, part);
            BlockState targetState = defaultBlockState()
                    .setValue(FACING, facing)
                    .setValue(PART, part);
            if (targetPos.getY() < context.getLevel().getMinBuildHeight()
                    || targetPos.getY() >= context.getLevel().getMaxBuildHeight()
                    || !context.getLevel().getWorldBorder().isWithinBounds(targetPos)
                    || !context.getLevel().getBlockState(targetPos).canBeReplaced(context)
                    || !context.getLevel().isUnobstructed(targetState, targetPos, collisionContext)) {
                return null;
            }
        }
        return defaultBlockState()
                .setValue(FACING, facing)
                .setValue(PART, Part.LOWER_LEFT);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (state.getValue(PART) != Part.LOWER_LEFT) {
            return;
        }
        Direction facing = state.getValue(FACING);
        for (Part part : MULTIBLOCK_PARTS) {
            if (part != Part.LOWER_LEFT) {
                level.setBlock(partPos(pos, facing, part), state.setValue(PART, part), Block.UPDATE_ALL);
            }
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        BlockPos mainPos = mainPos(pos, state);
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(mainPos) instanceof BrewingStationBlockEntity be) {
            be.setOperator(serverPlayer.getUUID());
            NetworkHooks.openScreen(serverPlayer, be, buf -> buf.writeBlockPos(mainPos));
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && !isMain(state)) {
            BlockPos mainPos = mainPos(pos, state);
            BlockState mainState = level.getBlockState(mainPos);
            if (matchesPart(mainState, state.getValue(FACING), Part.LOWER_LEFT)) {
                BlockEntity mainBe = level.getBlockEntity(mainPos);
                level.setBlock(mainPos, Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(player, 2001, mainPos, Block.getId(mainState));
                if (!player.isCreative()
                        && (!mainState.requiresCorrectToolForDrops() || player.hasCorrectToolForDrops(mainState))) {
                    Block.dropResources(mainState, level, mainPos, mainBe, player, player.getMainHandItem());
                }
            }
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!level.isClientSide && !state.is(newState.getBlock()) && isMain(state)
                && level.getBlockEntity(pos) instanceof BrewingStationBlockEntity be) {
            for (int slot = 0; slot < BrewingStationBlockEntity.TOTAL_SLOTS; slot++) {
                ItemStack stack = be.inventory().getStackInSlot(slot);
                if (!stack.isEmpty()) {
                    Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack.copy());
                    be.inventory().setStackInSlot(slot, ItemStack.EMPTY);
                }
            }
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(PART) == Part.LEGACY) {
            return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
        }
        BlockPos mainPos = mainPos(pos, state);
        Direction facing = state.getValue(FACING);
        for (Part part : MULTIBLOCK_PARTS) {
            if (partPos(mainPos, facing, part).equals(neighborPos)
                    && !matchesPart(neighborState, facing, part)) {
                return Blocks.AIR.defaultBlockState();
            }
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return isMain(state) ? new BrewingStationBlockEntity(pos, state) : null;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide || !isMain(state)) {
            return null;
        }
        return createTickerHelper(type, BrewingStationRegistry.STATION_BE.get(),
                (lvl, pos, st, be) -> be.serverTick());
    }

    public static boolean isMain(BlockState state) {
        return state.hasProperty(PART)
                && (state.getValue(PART) == Part.LOWER_LEFT || state.getValue(PART) == Part.LEGACY);
    }

    public static BlockPos mainPos(BlockPos pos, BlockState state) {
        Direction facing = state.getValue(FACING);
        Direction right = rightDirection(facing);
        return switch (state.getValue(PART)) {
            case LEGACY, LOWER_LEFT -> pos;
            case LOWER_RIGHT -> pos.relative(right.getOpposite());
            case UPPER_LEFT -> pos.below();
            case UPPER_RIGHT -> pos.below().relative(right.getOpposite());
        };
    }

    public static BlockPos partPos(BlockPos mainPos, Direction facing, Part part) {
        Direction right = rightDirection(facing);
        return switch (part) {
            case LEGACY, LOWER_LEFT -> mainPos;
            case LOWER_RIGHT -> mainPos.relative(right);
            case UPPER_LEFT -> mainPos.above();
            case UPPER_RIGHT -> mainPos.above().relative(right);
        };
    }

    private boolean matchesPart(BlockState state, Direction facing, Part part) {
        return state.is(this)
                && state.getValue(FACING) == facing
                && state.getValue(PART) == part;
    }

    private static Direction rightDirection(Direction facing) {
        return facing.getCounterClockWise();
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    private static VoxelShape rotateNorthShape(VoxelShape shape, Direction facing) {
        if (facing == Direction.NORTH) {
            return shape;
        }
        VoxelShape rotated = Shapes.empty();
        for (var box : shape.toAabbs()) {
            VoxelShape next = switch (facing) {
                case EAST -> Shapes.box(1.0D - box.maxZ, box.minY, box.minX,
                        1.0D - box.minZ, box.maxY, box.maxX);
                case SOUTH -> Shapes.box(1.0D - box.maxX, box.minY, 1.0D - box.maxZ,
                        1.0D - box.minX, box.maxY, 1.0D - box.minZ);
                case WEST -> Shapes.box(box.minZ, box.minY, 1.0D - box.maxX,
                        box.maxZ, box.maxY, 1.0D - box.minX);
                default -> Shapes.create(box);
            };
            rotated = Shapes.or(rotated, next);
        }
        return rotated.optimize();
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static <E extends BlockEntity, A extends BlockEntity> BlockEntityTicker<A> createTickerHelper(
            BlockEntityType<A> actual, BlockEntityType<E> expected, BlockEntityTicker<? super E> ticker) {
        return expected == actual ? (BlockEntityTicker<A>) ticker : null;
    }

    public enum Part implements StringRepresentable {
        LEGACY("legacy"),
        LOWER_LEFT("lower_left"),
        LOWER_RIGHT("lower_right"),
        UPPER_LEFT("upper_left"),
        UPPER_RIGHT("upper_right");

        private final String name;

        Part(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}

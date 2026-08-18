package com.miningdim.power;

import com.miningdim.power.generator.GeneratorBlockEntity;
import com.miningdim.power.generator.GeneratorPortBlockEntity;
import com.miningdim.power.generator.GeneratorSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
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
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * A complete 3 x 2 x 2 generator shell stored as twelve states of one registered block.
 * The placement position is the lower front-center cell ({@link Part#X1_Z0_Y0}).
 */
public final class GeneratorMultiblockBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    public static final Part ANCHOR_PART = Part.X1_Z0_Y0;
    public static final Part PORT_PART = Part.X1_Z1_Y0;

    private static final ThreadLocal<Boolean> CLEARING_STRUCTURE =
            ThreadLocal.withInitial(() -> false);
    private final GeneratorSpec spec;

    public GeneratorMultiblockBlock(GeneratorSpec spec, BlockBehaviour.Properties properties) {
        super(properties);
        this.spec = Objects.requireNonNull(spec, "spec");
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(PART, ANCHOR_PART));
    }

    public GeneratorSpec spec() {
        return spec;
    }

    public static boolean isAnchor(BlockState state) {
        return state.hasProperty(PART) && state.getValue(PART) == ANCHOR_PART;
    }

    /**
     * Resolves a part position from the lower front-center anchor. Models are authored facing north:
     * local +x follows the clockwise horizontal axis and local +z points behind the front face.
     */
    public static BlockPos partPos(BlockPos anchorPos, Direction facing, Part part) {
        Direction localX = facing.getClockWise();
        Direction back = facing.getOpposite();
        int xOffset = part.x() - ANCHOR_PART.x();
        return anchorPos.offset(
                localX.getStepX() * xOffset + back.getStepX() * part.z(),
                part.y(),
                localX.getStepZ() * xOffset + back.getStepZ() * part.z());
    }

    /** Resolves the lower front-center anchor from any one of the twelve part states. */
    public static BlockPos anchorPos(BlockPos partPos, BlockState state) {
        Direction facing = state.getValue(FACING);
        Direction localX = facing.getClockWise();
        Direction back = facing.getOpposite();
        Part part = state.getValue(PART);
        int xOffset = part.x() - ANCHOR_PART.x();
        return partPos.offset(
                -localX.getStepX() * xOffset - back.getStepX() * part.z(),
                -part.y(),
                -localX.getStepZ() * xOffset - back.getStepZ() * part.z());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockPos anchorPos = context.getClickedPos();
        CollisionContext collisionContext = context.getPlayer() == null
                ? CollisionContext.empty()
                : CollisionContext.of(context.getPlayer());
        for (Part part : Part.values()) {
            BlockPos targetPos = partPos(anchorPos, facing, part);
            BlockState targetState = defaultBlockState()
                    .setValue(FACING, facing)
                    .setValue(PART, part);
            if (!context.getLevel().isInWorldBounds(targetPos)
                    || context.getLevel().isOutsideBuildHeight(targetPos)
                    || !context.getLevel().getWorldBorder().isWithinBounds(targetPos)
                    || !context.getLevel().getBlockState(targetPos).canBeReplaced(context)
                    || !context.getLevel().isUnobstructed(targetState, targetPos, collisionContext)) {
                return null;
            }
        }
        return defaultBlockState()
                .setValue(FACING, facing)
                .setValue(PART, ANCHOR_PART);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !isAnchor(state)) {
            return;
        }

        Direction facing = state.getValue(FACING);
        for (Part part : Part.values()) {
            if (part != ANCHOR_PART) {
                level.setBlock(partPos(pos, facing, part), state.setValue(PART, part), Block.UPDATE_ALL);
            }
        }
        if (level instanceof ServerLevel serverLevel) {
            GeneratorBlockEntity.ensureLegacyEntities(serverLevel, pos);
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.CONSUME;
        }
        GeneratorBlockEntity controller = GeneratorBlockEntity.ensureLegacyEntities(serverLevel, pos);
        if (isAnchor(state) && controller != null) {
            NetworkHooks.openScreen(serverPlayer, controller, buf -> buf.writeBlockPos(controller.getBlockPos()));
        }
        return InteractionResult.CONSUME;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        Part part = state.getValue(PART);
        if (part == ANCHOR_PART) {
            return new GeneratorBlockEntity(pos, state);
        }
        if (part == PORT_PART) {
            return new GeneratorPortBlockEntity(pos, state);
        }
        return null;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                    BlockEntityType<T> type) {
        if (level.isClientSide || !isAnchor(state)) {
            return null;
        }
        return createTickerHelper(type, PowerRegistry.GENERATOR_CONTROLLER_BE.get(),
                (tickerLevel, tickerPos, tickerState, controller) -> controller.serverTick());
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static <E extends BlockEntity, A extends BlockEntity> BlockEntityTicker<A> createTickerHelper(
            BlockEntityType<A> actual, BlockEntityType<E> expected, BlockEntityTicker<? super E> ticker) {
        return actual == expected ? (BlockEntityTicker<A>) ticker : null;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!level.isClientSide && !state.is(newState.getBlock()) && !CLEARING_STRUCTURE.get()) {
            BlockPos anchorPos = anchorPos(pos, state);
            if (level.getBlockEntity(anchorPos) instanceof GeneratorBlockEntity controller) {
                controller.dropInternalContents();
            }
            clearStructure(level, pos, state);
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    private static void clearStructure(Level level, BlockPos sourcePos, BlockState sourceState) {
        GeneratorMultiblockBlock block = (GeneratorMultiblockBlock) sourceState.getBlock();
        Direction facing = sourceState.getValue(FACING);
        BlockPos anchorPos = anchorPos(sourcePos, sourceState);

        CLEARING_STRUCTURE.set(true);
        try {
            for (Part part : Part.values()) {
                BlockPos targetPos = partPos(anchorPos, facing, part);
                BlockState targetState = level.getBlockState(targetPos);
                if (matchesPart(targetState, block, facing, part)) {
                    level.setBlock(targetPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        } finally {
            CLEARING_STRUCTURE.remove();
        }
    }

    /** 熔毁路径移除整机时复用多方块一致性清理，避免触发普通库存掉落路径。 */
    public static void clearStructureForMeltdown(ServerLevel level, BlockPos anchorPos) {
        BlockState anchorState = level.getBlockState(anchorPos);
        if (!(anchorState.getBlock() instanceof GeneratorMultiblockBlock) || !isAnchor(anchorState)) {
            throw new IllegalArgumentException("meltdown clear requires generator anchor at " + anchorPos);
        }
        clearStructure(level, anchorPos, anchorState);
    }

    private static boolean matchesPart(BlockState state, GeneratorMultiblockBlock block,
                                       Direction facing, Part part) {
        return state.getBlock() == block
                && state.getValue(FACING) == facing
                && state.getValue(PART) == part;
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        BlockState mirrored = state.rotate(mirror.getRotation(state.getValue(FACING)));
        if (mirror == Mirror.NONE) {
            return mirrored;
        }
        return mirrored.setValue(PART, state.getValue(PART).mirrored());
    }

    public enum Part implements StringRepresentable {
        X0_Z0_Y0("x0_z0_y0", 0, 0, 0),
        X0_Z0_Y1("x0_z0_y1", 0, 0, 1),
        X0_Z1_Y0("x0_z1_y0", 0, 1, 0),
        X0_Z1_Y1("x0_z1_y1", 0, 1, 1),
        X1_Z0_Y0("x1_z0_y0", 1, 0, 0),
        X1_Z0_Y1("x1_z0_y1", 1, 0, 1),
        X1_Z1_Y0("x1_z1_y0", 1, 1, 0),
        X1_Z1_Y1("x1_z1_y1", 1, 1, 1),
        X2_Z0_Y0("x2_z0_y0", 2, 0, 0),
        X2_Z0_Y1("x2_z0_y1", 2, 0, 1),
        X2_Z1_Y0("x2_z1_y0", 2, 1, 0),
        X2_Z1_Y1("x2_z1_y1", 2, 1, 1);

        private static final Part[][][] BY_COORDINATE = new Part[3][2][2];

        static {
            for (Part part : values()) {
                BY_COORDINATE[part.x][part.z][part.y] = part;
            }
        }

        private final String name;
        private final int x;
        private final int z;
        private final int y;

        Part(String name, int x, int z, int y) {
            this.name = name;
            this.x = x;
            this.z = z;
            this.y = y;
        }

        public int x() {
            return x;
        }

        public int z() {
            return z;
        }

        public int y() {
            return y;
        }

        public Part mirrored() {
            return BY_COORDINATE[2 - x][z][y];
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}

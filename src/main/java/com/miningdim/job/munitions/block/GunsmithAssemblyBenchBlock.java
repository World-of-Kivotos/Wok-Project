package com.miningdim.job.munitions.block;

import com.miningdim.job.munitions.MunitionsConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.BlockGetter;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

public final class GunsmithAssemblyBenchBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");
    // 各部位朝北时的体积, 跟随方块模型: 台面 y0..10 满格, 其上是维修垫、平放的枪床 (东端托底挡块)、控制台、
    // 洞洞板后墙、工作灯与机械臂设备台 (含送料盘)。
    // 床面上的枪由 BlockEntityRenderer 绘制, 这里给它的空间 (x 3..27.5 含西端定位销, z 为 GunsmithGunBed.AXIS_Z ± HALF_WIDTH,
    // 高到 y 13) 留一块碰撞/选取箱, 玩家不会站进枪里; 机械臂不参与碰撞。焊花也由渲染器按机械臂程序在零件接触点生成, 方块本身不放粒子。
    // 模型改动后用 tools/gunsmith_workstation 重新核对这些数值。
    private static final Map<Part, Map<Direction, VoxelShape>> SHAPES = createShapes();

    private final Supplier<BlockEntityType<GunsmithAssemblyBenchBlockEntity>> beType;

    public GunsmithAssemblyBenchBlock(BlockBehaviour.Properties properties,
                                      Supplier<BlockEntityType<GunsmithAssemblyBenchBlockEntity>> beType) {
        super(properties);
        this.beType = beType;
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(PART, Part.MAIN)
                .setValue(ACTIVE, false));
    }

    public static boolean isMain(BlockState state) {
        return state.hasProperty(PART) && state.getValue(PART) == Part.MAIN;
    }

    public static BlockPos mainPos(BlockPos pos, BlockState state) {
        Direction facing = state.getValue(FACING);
        Direction side = facing.getClockWise();
        Direction back = facing.getOpposite();
        return switch (state.getValue(PART)) {
            case MAIN -> pos;
            case SIDE -> pos.relative(side.getOpposite());
            case BACK -> pos.relative(back.getOpposite());
            case BACK_SIDE -> pos.relative(side.getOpposite()).relative(back.getOpposite());
        };
    }

    public static BlockPos partPos(BlockPos mainPos, Direction facing, Part part) {
        Direction side = facing.getClockWise();
        Direction back = facing.getOpposite();
        return switch (part) {
            case MAIN -> mainPos;
            case SIDE -> mainPos.relative(side);
            case BACK -> mainPos.relative(back);
            case BACK_SIDE -> mainPos.relative(side).relative(back);
        };
    }

    public static void setStructureActive(Level level, BlockPos mainPos, BlockState mainState, boolean active) {
        Direction facing = mainState.getValue(FACING);
        for (Part part : Part.values()) {
            BlockPos targetPos = partPos(mainPos, facing, part);
            BlockState targetState = level.getBlockState(targetPos);
            if (matchesPart(targetState, mainState.getBlock(), facing, part)
                    && targetState.getValue(ACTIVE) != active) {
                level.setBlock(targetPos, targetState.setValue(ACTIVE, active), Block.UPDATE_CLIENTS);
            }
        }
    }

    private static boolean matchesPart(BlockState state, Block block, Direction facing, Part part) {
        return state.getBlock() == block
                && state.getValue(FACING) == facing
                && state.getValue(PART) == part;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART, ACTIVE);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(PART)).get(state.getValue(FACING));
    }

    private static Map<Part, Map<Direction, VoxelShape>> createShapes() {
        VoxelShape worktop = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 10.0D, 16.0D);
        VoxelShape backWall = Block.box(0.0D, 10.0D, 12.5D, 16.0D, 16.0D, 16.0D);
        Map<Part, VoxelShape> north = new EnumMap<>(Part.class);
        north.put(Part.MAIN, Shapes.or(worktop,
                Block.box(1.5D, 10.0D, 1.75D, 14.5D, 11.0D, 7.75D),
                Block.box(1.5D, 10.0D, 7.75D, 16.0D, 11.0D, 15.75D),
                Block.box(3.0D, 11.0D, 8.0D, 16.0D, 13.0D, 15.5D)));
        north.put(Part.SIDE, Shapes.or(worktop,
                Block.box(0.0D, 10.0D, 7.75D, 14.5D, 11.0D, 15.75D),
                Block.box(0.0D, 11.0D, 8.0D, 11.5D, 13.0D, 15.5D),
                Block.box(11.5D, 10.0D, 9.5D, 13.5D, 14.0D, 14.5D),
                Block.box(5.0D, 10.0D, 1.0D, 14.0D, 13.0D, 5.0D)));
        north.put(Part.BACK, Shapes.or(worktop, backWall,
                Block.box(0.5D, 10.0D, 2.5D, 6.0D, 16.0D, 7.0D),
                Block.box(3.0D, 10.0D, 1.5D, 8.5D, 12.0D, 5.0D),
                Block.box(3.0D, 10.0D, 7.5D, 14.5D, 11.5D, 10.5D)));
        north.put(Part.BACK_SIDE, Shapes.or(worktop, backWall,
                Block.box(3.5D, 10.0D, 1.0D, 15.0D, 11.5D, 13.0D),
                Block.box(2.0D, 11.5D, 6.0D, 6.5D, 14.0D, 9.0D),
                Block.box(3.75D, 11.5D, 1.25D, 13.75D, 14.0D, 3.75D)));
        Map<Part, Map<Direction, VoxelShape>> shapes = new EnumMap<>(Part.class);
        north.forEach((part, shape) -> shapes.put(part, rotatedShapes(shape.optimize())));
        return shapes;
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

    // 与方块状态 "y": 90 的模型旋转一致: 俯视顺时针, 北 -> 东。
    private static VoxelShape rotateClockwise(VoxelShape source) {
        VoxelShape[] result = {Shapes.empty()};
        source.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) ->
                result[0] = Shapes.or(result[0], Shapes.box(
                        1.0D - maxZ, minY, minX,
                        1.0D - minZ, maxY, maxX)));
        return result[0].optimize();
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        for (Part part : Part.values()) {
            if (part == Part.MAIN) {
                continue;
            }
            BlockPos targetPos = partPos(context.getClickedPos(), facing, part);
            if (!context.getLevel().getBlockState(targetPos).canBeReplaced(context)
                    || !context.getLevel().getWorldBorder().isWithinBounds(targetPos)) {
                return null;
            }
        }
        return defaultBlockState()
                .setValue(FACING, facing)
                .setValue(PART, Part.MAIN)
                .setValue(ACTIVE, false);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!isMain(state)) {
            return;
        }
        Direction facing = state.getValue(FACING);
        for (Part part : Part.values()) {
            if (part != Part.MAIN) {
                level.setBlock(partPos(pos, facing, part),
                        state.setValue(PART, part).setValue(ACTIVE, false), Block.UPDATE_CLIENTS);
            }
        }
    }

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // 破坏从属格时, 原本靠 updateShape 级联把 MAIN 变 AIR -> destroyBlock(drop=true), 该级联路径以空工具
        // 掉落, 绕过 requiresCorrectToolForDrops (直接破坏 MAIN 却经 ServerPlayerGameMode 走工具门, 两条路径
        // 不对称)。此处把从属格破坏改写成"以玩家工具破坏 MAIN": 先 UPDATE_SUPPRESS_DROPS 掐掉级联掉落,
        // 再按 hasCorrectToolForDrops 手动为 MAIN 补一次掉落, 生存/创造两路径与直接破坏 MAIN 对称。(审查 m-1)
        if (!level.isClientSide && !isMain(state)) {
            BlockPos mainPos = mainPos(pos, state);
            BlockState mainState = level.getBlockState(mainPos);
            if (mainState.getBlock() == this && isMain(mainState)) {
                level.setBlock(mainPos, Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(player, 2001, mainPos, Block.getId(mainState));
                if (!player.isCreative()
                        && (!mainState.requiresCorrectToolForDrops() || player.hasCorrectToolForDrops(mainState))) {
                    Block.dropResources(mainState, level, mainPos, null, player, player.getMainHandItem());
                }
            }
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!level.isClientSide && !state.is(newState.getBlock()) && isMain(state)
                && level.getBlockEntity(pos) instanceof GunsmithAssemblyBenchBlockEntity be) {
            for (ItemStack stack : be.dropContents()) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
            }
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        BlockPos mainPos = mainPos(pos, state);
        Direction facing = state.getValue(FACING);
        for (Part part : Part.values()) {
            if (partPos(mainPos, facing, part).equals(neighborPos)
                    && !matchesPart(neighborState, this, facing, part)) {
                return Blocks.AIR.defaultBlockState();
            }
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!MunitionsConfig.GUNSMITH_ENABLED.get()) {
            player.displayClientMessage(Component.translatable("message.miningdim.gunsmith.disabled"), true);
            return InteractionResult.CONSUME;
        }
        BlockPos mainPos = mainPos(pos, state);
        if (level.getBlockEntity(mainPos) instanceof GunsmithAssemblyBenchBlockEntity be) {
            if (be.isAnimating()) {
                player.displayClientMessage(
                        Component.translatable("message.miningdim.gunsmith_assembly_bench.busy"), true);
            } else if (player instanceof ServerPlayer serverPlayer) {
                NetworkHooks.openScreen(serverPlayer, be, buf -> buf.writeBlockPos(mainPos));
            }
        }
        return InteractionResult.CONSUME;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return isMain(state) ? new GunsmithAssemblyBenchBlockEntity(pos, state) : null;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide || !isMain(state)) {
            return null;
        }
        return createTickerHelper(type, beType.get(), (lvl, pos, st, be) -> be.serverTick());
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static <E extends BlockEntity, A extends BlockEntity> BlockEntityTicker<A> createTickerHelper(
            BlockEntityType<A> actual, BlockEntityType<E> expected, BlockEntityTicker<? super E> ticker) {
        return expected == actual ? (BlockEntityTicker<A>) ticker : null;
    }

    public enum Part implements StringRepresentable {
        MAIN("main"),
        SIDE("side"),
        BACK("back"),
        BACK_SIDE("back_side");

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

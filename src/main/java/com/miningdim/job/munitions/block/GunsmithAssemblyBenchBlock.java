package com.miningdim.job.munitions.block;

import com.miningdim.job.munitions.MunitionsConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
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
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

public final class GunsmithAssemblyBenchBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");
    private static final VoxelShape BASE_SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 5.25D, 16.0D);

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
        return BASE_SHAPE;
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

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!isMain(state) || !state.getValue(ACTIVE) || random.nextFloat() > 0.86F) {
            return;
        }
        Direction facing = state.getValue(FACING);
        Direction side = facing.getClockWise();
        Direction back = facing.getOpposite();
        double sideOffset = 0.5D + (random.nextDouble() - 0.5D) * 0.12D;
        double backOffset = 0.5D + (random.nextDouble() - 0.5D) * 0.12D;
        double x = pos.getX() + 0.5D + side.getStepX() * sideOffset + back.getStepX() * backOffset;
        double y = pos.getY() + 0.40D + random.nextDouble() * 0.06D;
        double z = pos.getZ() + 0.5D + side.getStepZ() * sideOffset + back.getStepZ() * backOffset;
        double vx = (random.nextDouble() - 0.5D) * 0.08D;
        double vy = 0.03D + random.nextDouble() * 0.06D;
        double vz = (random.nextDouble() - 0.5D) * 0.08D;
        level.addParticle(ParticleTypes.ELECTRIC_SPARK, x, y, z, vx, vy, vz);
        level.addParticle(ParticleTypes.ELECTRIC_SPARK, x, y, z, -vx, vy * 0.7D, -vz);
        if (random.nextInt(5) == 0) {
            level.addParticle(ParticleTypes.SMOKE, x, y + 0.05D, z, 0.0D, 0.018D, 0.0D);
        }
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

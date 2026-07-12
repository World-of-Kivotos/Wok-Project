package com.miningdim.job.munitions.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

public final class GunsmithPressBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    private final Supplier<BlockEntityType<GunsmithPressBlockEntity>> beType;

    public GunsmithPressBlock(BlockBehaviour.Properties properties,
                              Supplier<BlockEntityType<GunsmithPressBlockEntity>> beType) {
        super(properties);
        this.beType = beType;
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(ACTIVE, false));
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(ACTIVE, false);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ACTIVE);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof GunsmithPressBlockEntity be) {
            NetworkHooks.openScreen(serverPlayer, be, buf -> buf.writeBlockPos(pos));
        }
        return InteractionResult.CONSUME;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GunsmithPressBlockEntity(pos, state);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!state.getValue(ACTIVE)) {
            return;
        }
        Direction facing = state.getValue(FACING);
        Direction side = facing.getClockWise();
        double forward = -0.12D + random.nextDouble() * 0.18D;
        double lateral = (random.nextDouble() - 0.5D) * 0.58D;
        double x = pos.getX() + 0.5D + facing.getStepX() * forward + side.getStepX() * lateral;
        double y = pos.getY() + 0.47D + random.nextDouble() * 0.22D;
        double z = pos.getZ() + 0.5D + facing.getStepZ() * forward + side.getStepZ() * lateral;
        double vx = side.getStepX() * (random.nextDouble() - 0.5D) * 0.08D
                + facing.getStepX() * (0.02D + random.nextDouble() * 0.04D);
        double vy = 0.03D + random.nextDouble() * 0.06D;
        double vz = side.getStepZ() * (random.nextDouble() - 0.5D) * 0.08D
                + facing.getStepZ() * (0.02D + random.nextDouble() * 0.04D);

        for (int i = 0; i < 2; i++) {
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, x, y, z, vx, vy, vz);
        }
        if (random.nextInt(4) == 0) {
            level.addParticle(ParticleTypes.SMOKE, x, y + 0.04D, z, 0.0D, 0.018D, 0.0D);
        }
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide) {
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
}

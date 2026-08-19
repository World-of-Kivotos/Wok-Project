package com.miningdim.power.storage;

import com.miningdim.power.PowerRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** 三级储电共用的方块壳；余额与进出结算全在 BE 的服务端路径上。 */
public final class PowerCellBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    private final PowerCellSpec spec;

    public PowerCellBlock(BlockBehaviour.Properties properties, PowerCellSpec spec) {
        super(properties);
        this.spec = Objects.requireNonNull(spec, "spec");
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LIT, false));
    }

    public PowerCellSpec spec() {
        return spec;
    }

    /** 由方块反查规格；方块实体在构造期即需要它。 */
    public static PowerCellSpec specOf(Block block) {
        if (block instanceof PowerCellBlock cell) {
            return cell.spec;
        }
        throw new IllegalArgumentException("block is not a power cell: " + block);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LIT);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)
                || !(level.getBlockEntity(pos) instanceof PowerCellBlockEntity cell)) {
            return InteractionResult.CONSUME;
        }
        // 潜行右键给手持物品充电。护甲穿在身上没法接线缆, 在 Flux 无线充电接入之前, 这是随身装备
        // 唯一的补给通路; 普通右键仍然是开界面。
        if (player.isShiftKeyDown()) {
            chargeHeldItem(cell, player.getItemInHand(hand));
            return InteractionResult.CONSUME;
        }
        NetworkHooks.openScreen(serverPlayer, cell, buffer -> buffer.writeBlockPos(pos));
        return InteractionResult.CONSUME;
    }

    /** 把储电里的电灌进手持物品, 灌满或储电抽干为止。 */
    private static void chargeHeldItem(PowerCellBlockEntity cell, net.minecraft.world.item.ItemStack held) {
        if (held.isEmpty()) {
            return;
        }
        held.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY)
                .ifPresent(target -> {
                    int guard = 0;
                    while (guard++ < 1_000) {
                        int room = target.receiveEnergy(Integer.MAX_VALUE, true);
                        if (room <= 0) {
                            return;
                        }
                        int available = cell.extractForCharging(room);
                        if (available <= 0) {
                            return;
                        }
                        target.receiveEnergy(available, false);
                    }
                });
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PowerCellBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                    BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(type, PowerRegistry.POWER_CELL_BE.get(),
                (tickerLevel, tickerPos, tickerState, cell) -> cell.serverTick());
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static <E extends BlockEntity, A extends BlockEntity> BlockEntityTicker<A> createTickerHelper(
            BlockEntityType<A> actual, BlockEntityType<E> expected, BlockEntityTicker<? super E> ticker) {
        return actual == expected ? (BlockEntityTicker<A>) ticker : null;
    }
}

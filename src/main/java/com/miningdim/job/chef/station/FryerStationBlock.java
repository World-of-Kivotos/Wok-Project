package com.miningdim.job.chef.station;

import com.miningdim.job.chef.fryer.FryerBlockEntity;
import com.miningdim.job.chef.fryer.FryerRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

public class FryerStationBlock extends CookingStationBlock implements EntityBlock {
    FryerStationBlock(BlockBehaviour.Properties properties, Style style, StationShape shape,
                      StationParticles.Emitter particles) {
        super(properties, Kind.DEEP_FRYER, style, shape, particles);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FryerBlockEntity(pos, state);
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moved) {
        super.onPlace(state, level, pos, oldState, moved);
        if (!level.isClientSide && !oldState.is(state.getBlock())) {
            FryerBlockEntity.clearCheckpoint(level, pos);
        }
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        if (level.isClientSide || type != FryerRegistry.blockEntityType()) return null;
        return (world, pos, blockState, entity) -> ((FryerBlockEntity) entity).serverTick();
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.getBlockEntity(pos) instanceof FryerBlockEntity fryer) {
            if (isPreviewToggle(player, hand) && !fryer.busy()) {
                return super.use(state, level, pos, player, hand, hit);
            }
            if (!level.isClientSide && hand == InteractionHand.MAIN_HAND) {
                NetworkHooks.openScreen((ServerPlayer) player, fryer, pos);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (!state.is(next.getBlock()) && !level.isClientSide
                && level.getBlockEntity(pos) instanceof FryerBlockEntity fryer) {
            for (var stack : fryer.breakAndDrop()) {
                Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
            }
            level.updateNeighbourForOutputSignal(pos, this);
        }
        super.onRemove(state, level, pos, next, moved);
    }

    @Nullable
    public static Component fryerBlockedReason(Level level, BlockPos pos) {
        if (!(level.getBlockState(pos).getBlock() instanceof FryerStationBlock station)) {
            throw new IllegalStateException("Fryer transaction attached to another block at " + pos);
        }
        return station.workBlockedReason(level, pos);
    }
}

package com.miningdim.job.tarot.craft;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

/**
 * 塔罗合成台方块 (TarotReader spec 第八章)。右键服务端经 {@link NetworkHooks#openScreen} 打开
 * {@link TarotCraftMenu} (经 {@link TarotCraftBlockEntity} 的 MenuProvider + writeExtraData 传 BlockPos)。
 *
 * 继承普通 {@link Block} 实现 {@link EntityBlock} (非 BaseEntityBlock, 保 RenderShape.MODEL 可见), 仿
 * entrance.EntranceBlock 范式。破坏时把输入槽里的牌掉回世界 (onRemove)。
 */
public final class TarotCraftBlock extends Block implements EntityBlock {

    public TarotCraftBlock(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof TarotCraftBlockEntity be) {
            NetworkHooks.openScreen(serverPlayer, be, buf -> be.writeExtraData(serverPlayer, buf));
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof TarotCraftBlockEntity be) {
            for (var stack : be.dropContents()) {
                net.minecraft.world.Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
            }
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TarotCraftBlockEntity(pos, state);
    }
}

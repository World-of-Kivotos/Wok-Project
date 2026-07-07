package com.miningdim.job.brewer.cellar;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

/**
 * 酒窖箱方块 (酿酒师 阶段 4)。存酒陈酿: 年份按现实挂钟涨 (离线也涨, 加载时懒结算补齐), 满月加成, 烧干小麦
 * 保鲜, 断粮则衰退至变质。右键服务端开 GUI (NetworkHooks.openScreen)。继承普通 {@link Block} 实现
 * {@link EntityBlock} (与调味台 / 生产台同范式, 保 RenderShape.MODEL 正常渲染; BaseEntityBlock 默认 INVISIBLE
 * 不合用)。
 *
 * 服务端权威: 客户端 use 仅回 SUCCESS 触发挥手; ticker 仅服务端 (陈酿结算按现实挂钟, 客户端无权推进)。
 */
public final class WineCellarBlock extends Block implements EntityBlock {

    public WineCellarBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof WineCellarBlockEntity be) {
            NetworkHooks.openScreen(serverPlayer, be, buf -> buf.writeBlockPos(pos));
        }
        return InteractionResult.CONSUME;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WineCellarBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null; // 陈酿结算仅服务端权威 (现实挂钟差由服务端读取)。
        }
        return createTickerHelper(type, WineCellarRegistry.WINE_CELLAR_BE.get(),
                (lvl, pos, st, be) -> be.serverTick());
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static <E extends BlockEntity, A extends BlockEntity> BlockEntityTicker<A> createTickerHelper(
            BlockEntityType<A> actual, BlockEntityType<E> expected, BlockEntityTicker<? super E> ticker) {
        return expected == actual ? (BlockEntityTicker<A>) ticker : null;
    }
}

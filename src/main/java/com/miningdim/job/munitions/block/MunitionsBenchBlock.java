package com.miningdim.job.munitions.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * 军火台方块 (Munitions_Job_DesignSpec 五/十章)。被动产线方块: 右键开 GUI 塞料选口径, 时间戳追算被动产弹入缓冲。
 *
 * 交互 (仿工程师生产台上锁范式):
 *  - 普通右键: 开 GUI (NetworkHooks.openScreen, 仅服务端权威)。锁定时非主人 (OP 除外) 拒开。
 *  - 潜行右键空手: 切换锁 (仅主人)。actionbar 反馈。
 * setPlacedBy: 记录 ownerUUID (放置者; BE 存 owner)。台数上限校验在 {@code MunitionsSystem} 的 EntityPlaceEvent
 * 处理 (那里能取消放置 + 退还方块物品; 仿农夫 onFarmlandPlace), 本方块只负责开 GUI/上锁/ticker。
 *
 * 继承普通 Block + 实现 EntityBlock (而非 BaseEntityBlock), 与工程师生产台同范式: 军火台要正常渲染为可见模型
 * (RenderShape.MODEL), BaseEntityBlock 默认 INVISIBLE 不合用。
 *
 * ticker (五章被动产线): getTicker -> be.serverTick() 做时间戳追算 (只服务端; 不每 tick 遍历, 见 BlockEntity)。
 */
public final class MunitionsBenchBlock extends Block implements EntityBlock {

    private final Supplier<BlockEntityType<MunitionsBenchBlockEntity>> beType;

    /**
     * @param properties 方块属性
     * @param beType     方块实体类型供给 (延迟取, 注册后才 .get(); 用于 ticker 类型适配)
     */
    public MunitionsBenchBlock(BlockBehaviour.Properties properties,
                              Supplier<BlockEntityType<MunitionsBenchBlockEntity>> beType) {
        super(properties);
        this.beType = beType;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof Player player
                && level.getBlockEntity(pos) instanceof MunitionsBenchBlockEntity be) {
            be.setOwner(player.getUUID());
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)
                || !(level.getBlockEntity(pos) instanceof MunitionsBenchBlockEntity be)) {
            return InteractionResult.CONSUME;
        }

        // 潜行 + 空手 = 切锁 (仅主人); 否则开 GUI。
        if (player.isShiftKeyDown() && player.getItemInHand(hand).isEmpty()) {
            if (be.isOwner(player)) {
                boolean nowLocked = be.toggleLocked();
                serverPlayer.displayClientMessage(Component.translatable(nowLocked
                        ? "message.miningdim.munitions.locked"
                        : "message.miningdim.munitions.unlocked"), true);
            } else {
                serverPlayer.displayClientMessage(
                        Component.translatable("message.miningdim.munitions.not_owner"), true);
            }
            return InteractionResult.CONSUME;
        }

        // 锁定时非主人 (且非 OP) 拒开 GUI。
        if (!be.canAccess(serverPlayer)) {
            serverPlayer.displayClientMessage(
                    Component.translatable("message.miningdim.munitions.locked_no_access"), true);
            return InteractionResult.CONSUME;
        }

        NetworkHooks.openScreen(serverPlayer, be, buf -> buf.writeBlockPos(pos));
        return InteractionResult.CONSUME;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MunitionsBenchBlockEntity(pos, state);
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

    @Nullable
    @SuppressWarnings("unchecked")
    private static <E extends BlockEntity, A extends BlockEntity> BlockEntityTicker<A> createTickerHelper(
            BlockEntityType<A> actual, BlockEntityType<E> expected, BlockEntityTicker<? super E> ticker) {
        return expected == actual ? (BlockEntityTicker<A>) ticker : null;
    }
}

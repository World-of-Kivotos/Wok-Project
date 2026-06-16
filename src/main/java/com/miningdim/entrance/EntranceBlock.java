package com.miningdim.entrance;

import com.miningdim.core.Difficulty;
import com.miningdim.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
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
import org.jetbrains.annotations.Nullable;

/**
 * 难度入口方块 (R4)。每个难度一个方块实例 (Easy/Medium/Hard), 难度在构造时绑定。
 *  - 右键 ({@link #use}) 或踩上去 ({@link #stepOn}) 都触发 {@link EntranceHooks#requestEnter}
 *    把玩家送入对应难度的固定区域 (落点随机安全点由入场层解析)。
 *  - 携带 {@link EntranceBlockEntity}: 放置时在方块正上方生成原版 text_display 浮空字, 破坏时收回。
 *
 * 触发只在服务端执行 (世界写权威 C5); 客户端 use 返回 SUCCESS 仅做手部摆动动画, 不参与决策。
 * 连点防抖由 BlockEntity 的冷却 tick 承担 (见 {@link EntranceBlockEntity#tryTrigger})。
 *
 * 继承普通 {@link Block} 而非 BaseEntityBlock: 入口块要正常渲染为可见模型 (RenderShape.MODEL 默认),
 * BaseEntityBlock 默认 INVISIBLE 不合用; 故直接实现 {@link EntityBlock} 补 BlockEntity 能力。
 */
public final class EntranceBlock extends Block implements EntityBlock {

    private final Difficulty difficulty;

    public EntranceBlock(BlockBehaviour.Properties properties, Difficulty difficulty) {
        super(properties);
        this.difficulty = difficulty;
    }

    public Difficulty difficulty() {
        return difficulty;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            // 客户端只回 SUCCESS 触发挥手动画; 真正的传送在服务端分支执行。
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof EntranceBlockEntity be) {
            be.tryTrigger(serverPlayer);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
        // 踩踏触发 (站到方块顶面时): 仅服务端、仅玩家。入口块是完整实心方块, 用 stepOn 而非 entityInside
        // (后者对完整实心块不会触发, 因玩家无法与实心体素重叠)。冷却防止站在上面每 tick 反复触发。
        if (level.isClientSide) {
            return;
        }
        if (entity instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof EntranceBlockEntity be) {
            be.tryTrigger(serverPlayer);
        }
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EntranceBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        // 仅服务端 tick: 推进浮空字延迟生成与冷却倒计时 (客户端无需 tick)。
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(type, ModBlockEntities.ENTRANCE.get(),
                (lvl, pos, st, be) -> be.serverTick());
    }

    /** 类型安全的 ticker 适配 (仅当方块实体类型匹配本块的类型时返回 ticker)。 */
    @Nullable
    @SuppressWarnings("unchecked")
    private static <E extends BlockEntity, A extends BlockEntity> BlockEntityTicker<A> createTickerHelper(
            BlockEntityType<A> actual, BlockEntityType<E> expected, BlockEntityTicker<? super E> ticker) {
        return expected == actual ? (BlockEntityTicker<A>) ticker : null;
    }
}

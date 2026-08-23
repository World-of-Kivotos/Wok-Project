package com.miningdim.job.chef;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

/**
 * 调味台方块 (Chef_Job_DesignSpec 第四章; 5 档实例 低/中/高/超凡/闪耀, 同工程师五档生产台思路)。
 *
 * 每档携带 {@link #tierCap()} = 本档能产出的最高品质上限 (做菜时与厨师等级取 min 双重封顶)。右键服务端开 GUI
 * (NetworkHooks.openScreen)。继承普通 {@link Block} 实现 {@link EntityBlock} (与 EntranceBlock 同范式, 保
 * RenderShape.MODEL 正常渲染)。服务端权威: 客户端 use 仅回 SUCCESS 触发挥手。
 */
public final class SeasoningTableBlock extends Block implements EntityBlock {

    /** 与料理工位模型一致：台体、锅灶、后挡板及调料架均有准确轮廓。 */
    private static final VoxelShape TABLE_SHAPE = Shapes.or(
            Block.box(0.0D, 8.0D, 0.0D, 16.0D, 11.0D, 16.0D),
            Block.box(2.0D, 3.0D, 2.0D, 14.0D, 4.0D, 14.0D),
            Block.box(1.0D, 0.0D, 1.0D, 3.0D, 8.0D, 3.0D),
            Block.box(13.0D, 0.0D, 1.0D, 15.0D, 8.0D, 3.0D),
            Block.box(1.0D, 0.0D, 13.0D, 3.0D, 8.0D, 15.0D),
            Block.box(13.0D, 0.0D, 13.0D, 15.0D, 8.0D, 15.0D),
            Block.box(0.0D, 11.0D, 14.0D, 16.0D, 16.0D, 16.0D),
            Block.box(1.0D, 11.0D, 1.0D, 9.0D, 15.0D, 9.0D),
            Block.box(9.5D, 11.0D, 10.0D, 15.5D, 14.5D, 14.0D));

    private final ChefQuality tierCap;

    public SeasoningTableBlock(BlockBehaviour.Properties properties, ChefQuality tierCap) {
        super(properties);
        this.tierCap = tierCap;
    }

    /** 本档调味台能产出的最高品质 (与厨师等级取 min)。 */
    public ChefQuality tierCap() {
        return tierCap;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return TABLE_SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                        CollisionContext context) {
        return TABLE_SHAPE;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof SeasoningTableBlockEntity be) {
            NetworkHooks.openScreen(serverPlayer, be, buf -> buf.writeBlockPos(pos));
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof SeasoningTableBlockEntity be) {
            be.cancelForBlockBreak();
            for (var stack : be.dropContents()) {
                net.minecraft.world.Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
            }
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SeasoningTableBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null; // 火候推进仅服务端权威 (第四章防作弊)。
        }
        return createTickerHelper(type, ChefBlockEntities.SEASONING_TABLE.get(),
                (lvl, pos, st, be) -> be.serverTick());
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static <E extends BlockEntity, A extends BlockEntity> BlockEntityTicker<A> createTickerHelper(
            BlockEntityType<A> actual, BlockEntityType<E> expected, BlockEntityTicker<? super E> ticker) {
        return expected == actual ? (BlockEntityTicker<A>) ticker : null;
    }
}

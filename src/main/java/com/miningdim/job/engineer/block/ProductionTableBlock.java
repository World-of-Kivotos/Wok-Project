package com.miningdim.job.engineer.block;

import com.miningdim.job.engineer.NanoTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * 生产台方块 (MillenniumEngineer_Mod_DesignSpec 四 / 九)。六档各一个实例 (低/中/高/极品/超凡/闪耀),
 * 机器档在构造时绑定, 决定可造档与生成耗时 (4.1 第三道门)。
 *
 * 交互 (9.1 上锁):
 *  - 普通右键: 开 GUI (NetworkHooks.openScreen, 仅服务端权威)。锁定时非主人 (OP 除外) 拒开。
 *  - 潜行右键空手: 切换锁 (仅主人)。actionbar 反馈。
 * setPlacedBy: 记录 ownerUUID (放置者; 9.1 BE 存 owner)。
 *
 * 继承普通 Block + 实现 EntityBlock (而非 BaseEntityBlock), 与 EntranceBlock 同范式: 生产台要正常渲染为可见
 * 模型 (RenderShape.MODEL), BaseEntityBlock 默认 INVISIBLE 不合用。
 */
public final class ProductionTableBlock extends HorizontalDirectionalBlock implements EntityBlock {

    private final NanoTier machineTier;
    private final Supplier<BlockEntityType<ProductionTableBlockEntity>> beType;

    /**
     * @param properties  方块属性
     * @param machineTier 机器档 (决定可造档上限与生成耗时)
     * @param beType      方块实体类型供给 (延迟取, 注册后才 .get(); 用于 ticker 类型适配)
     */
    public ProductionTableBlock(BlockBehaviour.Properties properties, NanoTier machineTier,
                                Supplier<BlockEntityType<ProductionTableBlockEntity>> beType) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
        this.machineTier = machineTier;
        this.beType = beType;
    }

    public NanoTier machineTier() {
        return machineTier;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof Player player
                && level.getBlockEntity(pos) instanceof ProductionTableBlockEntity be) {
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
                || !(level.getBlockEntity(pos) instanceof ProductionTableBlockEntity be)) {
            return InteractionResult.CONSUME;
        }

        // 潜行 + 空手 = 切锁 (仅主人); 否则开 GUI。
        if (player.isShiftKeyDown() && player.getItemInHand(hand).isEmpty()) {
            if (be.isOwner(player)) {
                boolean nowLocked = be.toggleLocked();
                if (level instanceof ServerLevel serverLevel) {
                    serverLevel.sendParticles(nowLocked ? ParticleTypes.ELECTRIC_SPARK : ParticleTypes.HAPPY_VILLAGER,
                            pos.getX() + 0.5D, pos.getY() + 1.05D, pos.getZ() + 0.5D,
                            12, 0.35D, 0.12D, 0.35D, 0.02D);
                }
                serverPlayer.displayClientMessage(Component.translatable(nowLocked
                        ? "message.miningdim.engineer.locked"
                        : "message.miningdim.engineer.unlocked"), true);
            } else {
                serverPlayer.displayClientMessage(
                        Component.translatable("message.miningdim.engineer.not_owner"), true);
            }
            return InteractionResult.CONSUME;
        }

        // 锁定时非主人 (且非 OP) 拒开 GUI (9.1)。
        if (!be.canAccess(serverPlayer)) {
            serverPlayer.displayClientMessage(
                    Component.translatable("message.miningdim.engineer.locked_no_access"), true);
            return InteractionResult.CONSUME;
        }

        NetworkHooks.openScreen(serverPlayer, be, buf -> buf.writeBlockPos(pos));
        return InteractionResult.CONSUME;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ProductionTableBlockEntity(pos, state);
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

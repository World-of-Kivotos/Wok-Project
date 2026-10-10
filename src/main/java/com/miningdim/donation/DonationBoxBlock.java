package com.miningdim.donation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
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
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 捐赠箱方块。继承普通 {@link Block} 并实现 {@link EntityBlock} (与酒窖箱同范式, 保持 RenderShape.MODEL)。
 *
 * 右键: 服务端按权限分流 —— 箱主/协管/OP 开管理界面, 其他人开捐赠界面。假玩家 (机器模拟的玩家) 什么都开不了。
 *
 * 破坏保护分三层, 互为兜底:
 *  1. {@link #getDestroyProgress}: 非箱主且非 OP 的挖掘进度恒为 0。客户端也跑这段 (箱主身份随区块同步),
 *     所以不会出现"裂纹走完、方块消失又弹回来"的假象。
 *  2. 服务端 BreakEvent 监听 ({@link DonationEvents}) 取消无权破坏, 覆盖创造模式瞬间破坏。
 *  3. {@link #onDestroyedByPlayer}: 即使别的模组把 BreakEvent 放行, 无权玩家仍拆不掉。
 *
 * 非玩家路径: 爆炸 ({@link #canDropFromExplosion} + {@link #onBlockExploded}) 与实体破坏 ({@link #canEntityDestroy},
 * 覆盖凋灵与末影龙) 一律无效; 活塞推不动由方块属性 PushReaction.BLOCK 保证; 末影人只搬 enderman_holdable 标签里的方块;
 * 只看硬度的模组破坏路径由硬度 -1 挡住, 见 {@link DonationRegistry}。
 *
 * 被移除时 (箱主/OP 拆除、/setblock ... destroy 等) 仓库全部散落在原地, 方块本身按战利品表掉一个不带内容物的
 * 普通物品。指令的 replace/move 路径先经方块实体的 Clearable 清空 (与原版容器一致), 到这里已经没有东西可散。
 */
public final class DonationBoxBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public DonationBoxBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
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

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DonationBoxBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != DonationRegistry.DONATION_BOX_BE.get()) {
            return null;
        }
        return (lvl, pos, st, be) -> ((DonationBoxBlockEntity) be).serverTick();
    }

    /**
     * 记录放置者为箱主。假玩家 (机械手等) 视同非玩家放置: 它们的 UUID 不属于任何真人, 记成箱主只会造出一个
     * 谁都管不了的箱子。
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !(level.getBlockEntity(pos) instanceof DonationBoxBlockEntity be)) {
            return;
        }
        if (placer instanceof Player player && !(placer instanceof FakePlayer)) {
            be.assignPlacer(player.getUUID(), player.getGameProfile().getName());
            DonationAudit.admin(level, pos, "PLACED", DonationAudit.describe(player.getUUID(),
                    player.getGameProfile().getName()), "owner=self");
        } else {
            be.assignPlacer(null, "");
            DonationAudit.admin(level, pos, "PLACED", "non-player", "owner=-");
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer) || player instanceof FakePlayer
                || !(level.getBlockEntity(pos) instanceof DonationBoxBlockEntity be)) {
            return InteractionResult.CONSUME;
        }
        NetworkHooks.openScreen(serverPlayer,
                be.canManage(serverPlayer) ? be.managerMenuProvider() : be.donorMenuProvider(),
                buf -> buf.writeBlockPos(pos));
        return InteractionResult.CONSUME;
    }

    /**
     * 方块硬度是 -1 (见 {@link DonationRegistry} 类注释), 原版实现对它恒返回 0, 所以这里对有权者按名义硬度
     * 复刻原版公式; 无权者恒为 0。方块实体缺失 (理论上不该出现) 时只有 OP 挖得动。
     */
    @Override
    public float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        boolean allowed = level.getBlockEntity(pos) instanceof DonationBoxBlockEntity be
                ? be.canBreak(player)
                : DonationBoxBlockEntity.isOperator(player);
        if (!allowed) {
            return 0.0F;
        }
        int divisor = ForgeHooks.isCorrectToolForDrops(state, player) ? 30 : 100;
        return player.getDigSpeed(state, pos) / DonationRegistry.NOMINAL_HARDNESS / divisor;
    }

    /**
     * 无权者拒绝; 有权者放行前把自己登记为破坏者, 同一调用栈里的 {@link #onRemove} 据此在审计里写明是谁拆的。
     */
    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player,
                                       boolean willHarvest, FluidState fluid) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof DonationBoxBlockEntity be) {
            if (!be.canBreak(player)) {
                return false;
            }
            be.markRemover(player);
            try {
                return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
            } finally {
                be.markRemover(null);
            }
        }
        return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }

    /**
     * 仓库散落。只在方块真的换成别的方块时触发 (朝向变化不算); 快照回滚期间 (放置被领地模组取消) 跳过,
     * 那时的方块实体是刚建出来的空壳。
     *
     * 审计先于散落写 (散落会把栈拆空): 汇总行带破坏者 (玩家拆除时由 onDestroyedByPlayer 登记, 其余为 non-player)
     * 与箱主, 另按物品逐行记明细 —— 拆箱不经过管理界面的逐次记账, 这是它唯一的取出记录。
     */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock()) && !level.isClientSide && !level.restoringBlockSnapshots
                && level.getBlockEntity(pos) instanceof DonationBoxBlockEntity be) {
            List<ItemStack> contents = be.drainForRemoval();
            be.recordRemovalAudit(DonationAudit.removal(level, pos, be.takeRemover(), be.ownerDescription(), contents));
            for (ItemStack stack : contents) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    /** 凋灵、末影龙等实体破坏一律无效。 */
    @Override
    public boolean canEntityDestroy(BlockState state, BlockGetter level, BlockPos pos, Entity entity) {
        return false;
    }

    /** 即使某个极强的爆炸越过了抗性, 也不掉落 (否则方块留着、物品又掉一个, 就是复制)。 */
    @Override
    public boolean canDropFromExplosion(BlockState state, BlockGetter level, BlockPos pos, Explosion explosion) {
        return false;
    }

    /** 爆炸不移除方块: 原版实现在这里 setBlock(AIR), 这里什么都不做。 */
    @Override
    public void onBlockExploded(BlockState state, Level level, BlockPos pos, Explosion explosion) {
        // 刻意保留方块与仓库原样; 抗性已挡住常规爆炸, 这里兜住任意威力的爆炸。
    }
}

package com.miningdim.job.farmer.granary.block;

import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.job.farmer.granary.GranaryAcceptance;
import com.miningdim.job.farmer.granary.GranaryInspection;
import com.miningdim.job.farmer.granary.GranaryQuery;
import com.miningdim.job.farmer.granary.GranaryRecord;
import com.miningdim.job.farmer.granary.GranarySystem;
import com.miningdim.job.farmer.granary.GranaryTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import com.miningdim.job.farmer.granary.GranaryCenterMenu;
import com.miningdim.job.farmer.granary.GranaryCenterPage;
import com.miningdim.job.farmer.granary.GranaryCenterPageS2C;
import net.minecraftforge.network.NetworkHooks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;

/**
 * 粮仓账台 (Spoilage_Granary_DesignSpec「部件」): 粮仓的大脑, 本身不装东西。
 *
 * 可以放在屋里, 也可以嵌在墙上 (建议嵌在外墙, 来验收的人在屋外就能右键到) —— 结构判定是按「账台周围
 * 能走的格子」起洪泛的, 所以嵌墙不影响判定。每人最多两座, 上限在放置事件里查。
 */
public final class GranaryCenterBlock extends BaseEntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final VoxelShape BASE_SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 13.0D, 16.0D);
    private static final VoxelShape NORTH_SHAPE =
            Shapes.or(BASE_SHAPE, Block.box(2.0D, 13.0D, 4.0D, 15.0D, 16.0D, 13.0D));
    private static final VoxelShape EAST_SHAPE =
            Shapes.or(BASE_SHAPE, Block.box(3.0D, 13.0D, 2.0D, 12.0D, 16.0D, 15.0D));
    private static final VoxelShape SOUTH_SHAPE =
            Shapes.or(BASE_SHAPE, Block.box(1.0D, 13.0D, 3.0D, 14.0D, 16.0D, 12.0D));
    private static final VoxelShape WEST_SHAPE =
            Shapes.or(BASE_SHAPE, Block.box(4.0D, 13.0D, 1.0D, 13.0D, 16.0D, 14.0D));

    public GranaryCenterBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

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
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case NORTH -> NORTH_SHAPE;
            case EAST -> EAST_SHAPE;
            case SOUTH -> SOUTH_SHAPE;
            case WEST -> WEST_SHAPE;
            case UP, DOWN -> throw new IllegalStateException("Granary center facing must be horizontal");
        };
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GranaryCenterBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level instanceof ServerLevel serverLevel) || !(placer instanceof ServerPlayer player)) {
            return;
        }
        if (serverLevel.getBlockEntity(pos) instanceof GranaryCenterBlockEntity center) {
            GranarySystem.bindCenterOwner(serverLevel, pos, center, player);
        }
    }

    /**
     * 只有房主或 OP 能拆账台, **而且不能还有别的成员的囤** (规格「拆除与归还」: 有成员的囤时账台拆不掉 ——
     * 否则成员的东西一夜之间没人管了)。
     */
    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player,
                                       boolean willHarvest, FluidState fluid) {
        if (level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof GranaryCenterBlockEntity center) {
            if (center.owner() != null && !center.owner().equals(player.getUUID()) && !player.hasPermissions(2)) {
                player.displayClientMessage(Component.translatable("message.miningdim.granary.not_owner"), true);
                return false;
            }
            if (!player.hasPermissions(2)
                    && GranarySystem.otherMembersKeepBins(serverLevel, pos, player.getUUID())) {
                player.displayClientMessage(
                        Component.translatable("message.miningdim.granary.center_has_members"), true);
                return false;
            }
        }
        return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }

    /**
     * 右键账台: 看结构与状态; **潜行右键**是验收 (界面做出来之前, 验收按钮就落在这条路上)。
     *
     * 服务端权威: 客户端只回 SUCCESS 触发挥手, 判定与落档都在服务端做。假玩家 (机器、Deployer) 一律
     * 不给看也不给验收 (反漏洞 #35/#69), 而且不提示 —— 免得刷屏。
     */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)
                || player instanceof FakePlayer || player.isRemoved()) {
            return InteractionResult.CONSUME;
        }
        if (player.isShiftKeyDown()) {
            showAcceptance(serverPlayer, GranaryInspection.accept(serverLevel, pos, serverPlayer));
            return InteractionResult.CONSUME;
        }
        GranaryInspection.Report report = GranaryInspection.inspect(serverLevel, pos);
        if (report.orphan()) {
            serverPlayer.displayClientMessage(
                    Component.translatable("message.miningdim.granary.not_a_granary"), false);
            return InteractionResult.CONSUME;
        }
        /*
         * 普通右键: 开多页签界面 (规格「账台的多页签界面」)。界面里的每一页都由服务端现算
         * (GranaryCenterView), 结构报告与 /granary status 是同一批行 —— 文字版仍然留给命令与脚本。
         */
        NetworkHooks.openScreen(serverPlayer, new net.minecraft.world.MenuProvider() {
            @Override
            public Component getDisplayName() {
                return Component.translatable("container.miningdim.granary_center");
            }

            @Override
            public net.minecraft.world.inventory.AbstractContainerMenu createMenu(
                    int windowId, net.minecraft.world.entity.player.Inventory inventory,
                    net.minecraft.world.entity.player.Player player) {
                return new GranaryCenterMenu(windowId, inventory, pos);
            }
        }, buffer -> buffer.writeBlockPos(pos));
        if (serverPlayer.containerMenu instanceof GranaryCenterMenu menu) {
            GranaryCenterPageS2C.send(serverPlayer, menu, GranaryCenterPage.STRUCTURE);
        }
        return InteractionResult.CONSUME;
    }

    /** 结构页 (文字版): 与 {@code /granary status} 读同一批行 (见 {@code GranaryQuery#statusLines})。 */
    private static void showStatus(ServerPlayer player, GranaryInspection.Report report) {
        for (Component line : GranaryQuery.statusLines((ServerLevel) player.level(), report, player)) {
            player.displayClientMessage(line, false);
        }
    }

    /** 验收那一下的结果。 */
    private static void showAcceptance(ServerPlayer player, GranaryInspection.Acceptance acceptance) {
        String key = switch (acceptance.rejection()) {
            case NONE -> "message.miningdim.granary.accepted";
            case NOT_A_GRANARY -> "message.miningdim.granary.not_a_granary";
            case LEVEL_TOO_LOW -> "message.miningdim.granary.accept_level";
            case NOT_READY -> "message.miningdim.granary.accept_not_ready";
            case TOP_TIER -> "message.miningdim.granary.report_top";
            case FAKE_PLAYER -> null;
        };
        if (key == null) {
            return;
        }
        Component detail = acceptance.accepted()
                ? GranaryQuery.tierName(acceptance.tier())
                : Component.translatable(acceptance.verdict() == null
                        ? "granary.tier.none" : acceptance.verdict().tier().translationKey());
        if (acceptance.rejection() == GranaryInspection.Rejection.NOT_READY) {
            player.displayClientMessage(Component.translatable(key, acceptance.verdict().checklist()), false);
            return;
        }
        if (acceptance.rejection() == GranaryInspection.Rejection.LEVEL_TOO_LOW) {
            GranaryTier candidate = acceptance.verdict().tier();
            player.displayClientMessage(Component.translatable(key, detail,
                    candidate.unlockLevel(), JobServices.jobService().level(player, JobId.FARMER)), false);
            return;
        }
        player.displayClientMessage(Component.translatable(key, detail), false);
    }

    /** 方块移除的汇合点: 归还账台额度。 */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            GranarySystem.releaseCenter(serverLevel, pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}

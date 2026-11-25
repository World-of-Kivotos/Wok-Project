package com.miningdim.job.brewer.cellar;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import com.miningdim.job.brewer.BrewerItems;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;
import net.minecraft.util.StringRepresentable;

/**
 * 酒窖箱方块 (酿酒师 阶段 4)。存酒陈酿: 年份按现实挂钟涨 (离线也涨, 加载时懒结算补齐), 满月加成, 烧干小麦
 * 保鲜, 断粮则衰退至变质。右键服务端开 GUI (NetworkHooks.openScreen)。继承普通 {@link Block} 实现
 * {@link EntityBlock} (与调味台 / 生产台同范式, 保 RenderShape.MODEL 正常渲染; BaseEntityBlock 默认 INVISIBLE
 * 不合用)。
 *
 * 服务端权威: 客户端 use 仅回 SUCCESS 触发挥手; ticker 仅服务端 (陈酿结算按现实挂钟, 客户端无权推进)。
 */
public final class WineCellarBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    public WineCellarBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, Part.LEGACY));
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockPos sidePos = partPos(pos, facing, Part.SIDE);
        if (!level.getWorldBorder().isWithinBounds(sidePos)
                || !level.getBlockState(sidePos).canBeReplaced(context)) {
            return null;
        }
        return defaultBlockState().setValue(FACING, facing).setValue(PART, Part.MAIN);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable net.minecraft.world.entity.LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && state.getValue(PART) == Part.MAIN) {
            level.setBlock(partPos(pos, state.getValue(FACING), Part.SIDE),
                    state.setValue(PART, Part.SIDE), Block.UPDATE_ALL);
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        BlockPos mainPos = mainPos(pos, state);
        if (player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(mainPos) instanceof WineCellarBlockEntity be) {
            if (be.isLocked()) {
                serverPlayer.displayClientMessage(Component.translatable("message.miningdim.wine_cellar.locked"), true);
            } else {
                NetworkHooks.openScreen(serverPlayer, be, buf -> buf.writeBlockPos(mainPos));
            }
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && isMain(state) && level instanceof ServerLevel server
                && level.getBlockEntity(pos) instanceof WineCellarBlockEntity be) {
            if (be.isLocked()) {
                be.archiveRemovedSave(server);
            } else {
                be.settleNow();
                int reserve = be.drainFuelReserve();
                while (reserve > 0) {
                    int count = Math.min(64, reserve);
                    double width = net.minecraft.world.entity.EntityType.ITEM.getWidth();
                    double spread = 1.0D - width;
                    ItemEntity drop = new ItemEntity(level,
                            pos.getX() + level.random.nextDouble() * spread + width / 2.0D,
                            pos.getY() + level.random.nextDouble() * spread,
                            pos.getZ() + level.random.nextDouble() * spread + width / 2.0D,
                            new ItemStack(BrewerItems.DRIED_WHEAT.get(), count));
                    drop.setDeltaMovement(level.random.triangle(0.0D, 0.11485D),
                            level.random.triangle(0.2D, 0.11485D), level.random.triangle(0.0D, 0.11485D));
                    level.addFreshEntity(drop);
                    reserve -= count;
                }
                for (int slot = 0; slot < be.inventory().getSlots(); slot++) {
                    ItemStack contents = be.inventory().getStackInSlot(slot);
                    if (!contents.isEmpty()) {
                        Block.popResource(level, pos, contents.copy());
                        be.inventory().setStackInSlot(slot, ItemStack.EMPTY);
                    }
                }
            }
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && state.getValue(PART) == Part.SIDE && !locked(level, pos, state)) {
            BlockPos mainPos = mainPos(pos, state);
            BlockState mainState = level.getBlockState(mainPos);
            if (mainState.is(this) && mainState.getValue(PART) == Part.MAIN
                    && mainState.getValue(FACING) == state.getValue(FACING)) {
                BlockEntity mainBe = level.getBlockEntity(mainPos);
                level.setBlock(mainPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(player, 2001, mainPos, Block.getId(mainState));
                if (!player.getAbilities().instabuild && player.hasCorrectToolForDrops(mainState)) {
                    Block.dropResources(mainState, level, mainPos, mainBe, player, player.getMainHandItem());
                }
            }
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(PART) == Part.LEGACY) {
            return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
        }
        Direction sideDirection = sideDirection(state.getValue(FACING));
        Direction linkedDirection = isMain(state) ? sideDirection : sideDirection.getOpposite();
        Part expectedPart = isMain(state) ? Part.SIDE : Part.MAIN;
        if (direction == linkedDirection && (!neighborState.is(this)
                || neighborState.getValue(PART) != expectedPart
                || neighborState.getValue(FACING) != state.getValue(FACING))) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    private static boolean locked(BlockGetter level, BlockPos pos, BlockState state) {
        return level.getBlockEntity(mainPos(pos, state)) instanceof WineCellarBlockEntity cellar && cellar.isLocked();
    }

    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player,
                                       boolean willHarvest, FluidState fluid) {
        return !locked(level, pos, state) && super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }

    @Override
    public float getExplosionResistance(BlockState state, BlockGetter level, BlockPos pos, Explosion explosion) {
        return locked(level, pos, state) ? Blocks.BEDROCK.getExplosionResistance()
                : super.getExplosionResistance(state, level, pos, explosion);
    }

    @Override
    public boolean canEntityDestroy(BlockState state, BlockGetter level, BlockPos pos, Entity entity) {
        return !locked(level, pos, state) && super.canEntityDestroy(state, level, pos, entity);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return isMain(state) ? new WineCellarBlockEntity(pos, state) : null;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide || !isMain(state)) {
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

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    public static boolean isMain(BlockState state) {
        return state.getValue(PART) != Part.SIDE;
    }

    public static Direction sideDirection(Direction facing) {
        return facing.getClockWise();
    }

    public static BlockPos mainPos(BlockPos pos, BlockState state) {
        return isMain(state) ? pos : pos.relative(sideDirection(state.getValue(FACING)).getOpposite());
    }

    public static BlockPos partPos(BlockPos mainPos, Direction facing, Part part) {
        return part == Part.SIDE ? mainPos.relative(sideDirection(facing)) : mainPos;
    }

    public enum Part implements StringRepresentable {
        LEGACY("legacy"),
        MAIN("main"),
        SIDE("side");

        private final String serializedName;

        Part(String serializedName) {
            this.serializedName = serializedName;
        }

        @Override
        public String getSerializedName() {
            return serializedName;
        }
    }
}

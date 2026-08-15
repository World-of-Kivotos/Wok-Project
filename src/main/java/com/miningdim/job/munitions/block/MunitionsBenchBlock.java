package com.miningdim.job.munitions.block;

import com.miningdim.job.munitions.MunitionsSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Supplier;

public final class MunitionsBenchBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    private final Supplier<BlockEntityType<MunitionsBenchBlockEntity>> beType;
    private final int unlockLevel;
    private final int maxEffectiveLevel;

    public MunitionsBenchBlock(BlockBehaviour.Properties properties,
                              Supplier<BlockEntityType<MunitionsBenchBlockEntity>> beType,
                              int unlockLevel,
                              int maxEffectiveLevel) {
        super(properties);
        this.beType = beType;
        this.unlockLevel = clampLevel(unlockLevel);
        this.maxEffectiveLevel = Math.max(this.unlockLevel, clampLevel(maxEffectiveLevel));
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(PART, Part.MAIN)
                .setValue(ACTIVE, false));
    }

    public int unlockLevel() {
        return unlockLevel;
    }

    public int maxEffectiveLevel() {
        return maxEffectiveLevel;
    }

    public int effectiveLevelFor(int playerLevel) {
        return Math.min(clampLevel(playerLevel), maxEffectiveLevel);
    }

    private static int clampLevel(int level) {
        return Math.max(1, Math.min(10, level));
    }

    public static boolean isMain(BlockState state) {
        return state.hasProperty(PART) && state.getValue(PART) == Part.MAIN;
    }

    public static BlockPos mainPos(BlockPos pos, BlockState state) {
        return isMain(state) ? pos : pos.relative(state.getValue(FACING));
    }

    public static BlockPos extensionPos(BlockPos mainPos, BlockState mainState) {
        return mainPos.relative(mainState.getValue(FACING).getOpposite());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART, ACTIVE);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockPos extensionPos = context.getClickedPos().relative(facing.getOpposite());
        if (!context.getLevel().getBlockState(extensionPos).canBeReplaced(context)
                || !context.getLevel().getWorldBorder().isWithinBounds(extensionPos)) {
            return null;
        }
        return defaultBlockState()
                .setValue(FACING, facing)
                .setValue(PART, Part.MAIN)
                .setValue(ACTIVE, false);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!isMain(state)) {
            return;
        }

        BlockPos extensionPos = extensionPos(pos, state);
        BlockState extensionState = state.setValue(PART, Part.EXTENSION).setValue(ACTIVE, false);
        level.setBlock(extensionPos, extensionState, Block.UPDATE_ALL);

        if (!level.isClientSide && placer instanceof Player player
                && level.getBlockEntity(pos) instanceof MunitionsBenchBlockEntity be) {
            be.setOwner(player.getUUID());
        }
    }

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // 双掉落防线 (审查 C-1, 对齐 vanilla BedBlock): 掉落唯一来源是 main 半块 loot (part=main 条件); 生存挖任一半
        // 不在此干预, 搭档半块由 updateShape 级联 destroyBlock 处理 —— 级联掉落同样被 loot 条件约束到恰好 1 个。
        // 旧实现生存路径主动 setBlock(other, AIR) 有两个坑: 挖 extension 时 main (含 BE) 被 setBlock 抹掉不走 loot,
        // 玩家挖台反而颗粒无收; 爆炸/凋灵同 tick 毁两半时各自 roll loot 可掉 2 个 (dupe)。
        // 创造模式例外: 挖 extension 时先以 UPDATE_SUPPRESS_DROPS 清掉 main, 防级联 destroyBlock 凭空掉落。
        if (!level.isClientSide && player.isCreative() && !isMain(state)) {
            BlockPos mainPos = mainPos(pos, state);
            BlockState mainState = level.getBlockState(mainPos);
            if (mainState.getBlock() == this && isMain(mainState)) {
                level.setBlock(mainPos, Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                level.levelEvent(player, 2001, mainPos, Block.getId(mainState));
            }
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    /**
     * 台数计数的唯一回收点 (F009: 挪出 forgeBus BreakEvent, 覆盖玩家破坏/爆炸/活塞/级联/指令全部路径)。
     *
     * 三条理由:
     * (a) {@code !state.is(newState.getBlock())} 是 vanilla 惯用判据, 少了它每次 ACTIVE 属性翻转都会误扣 ——
     *     {@code LevelChunk.setBlockState} 对同方块的每次状态变更 (进度 tick 切换 ACTIVE) 都会调一次 onRemove。
     * (b) 只在 MAIN 半块扣: EXTENSION 没有 BE ({@link #newBlockEntity} 对它返 null), 挖任一半最终都只会让
     *     MAIN 走一次 onRemove (updateShape 级联或 playerWillDestroy 的创造模式清理), 天然单次不重复扣。
     * (c) 放置侧按放置者 increment ({@code MunitionsSystem.onBenchPlace}), 而 {@link #setPlacedBy} 把 owner
     *     写成放置者, 故两侧同一个 UUID; 非玩家放置 (owner 为 null) 天然两侧都不计, 与放置侧对称。
     */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!level.isClientSide && isMain(state) && !state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof MunitionsBenchBlockEntity be) {
            UUID owner = be.owner();
            if (owner != null && level instanceof ServerLevel serverLevel) {
                MunitionsSavedData.get(serverLevel.getServer().overworld()).decrement(owner);
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        Direction linkDirection = isMain(state) ? state.getValue(FACING).getOpposite() : state.getValue(FACING);
        if (direction == linkDirection
                && (neighborState.getBlock() != this || neighborState.getValue(PART) == state.getValue(PART))) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        BlockPos mainPos = mainPos(pos, state);
        if (!(player instanceof ServerPlayer serverPlayer)
                || !(level.getBlockEntity(mainPos) instanceof MunitionsBenchBlockEntity be)) {
            return InteractionResult.CONSUME;
        }

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

        if (!be.canAccess(serverPlayer)) {
            serverPlayer.displayClientMessage(
                    Component.translatable("message.miningdim.munitions.locked_no_access"), true);
            return InteractionResult.CONSUME;
        }

        NetworkHooks.openScreen(serverPlayer, be, buf -> buf.writeBlockPos(mainPos));
        return InteractionResult.CONSUME;
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!state.getValue(ACTIVE) || random.nextFloat() > 0.72F) {
            return;
        }
        Direction facing = state.getValue(FACING);
        Direction side = facing.getClockWise();
        double forward = isMain(state) ? 0.18D : -0.18D;
        double lateral = (random.nextDouble() - 0.5D) * 0.62D;
        double x = pos.getX() + 0.5D + facing.getStepX() * forward + side.getStepX() * lateral;
        double y = pos.getY() + 0.72D + random.nextDouble() * 0.28D;
        double z = pos.getZ() + 0.5D + facing.getStepZ() * forward + side.getStepZ() * lateral;
        double vx = side.getStepX() * (random.nextDouble() - 0.5D) * 0.06D;
        double vy = 0.02D + random.nextDouble() * 0.045D;
        double vz = side.getStepZ() * (random.nextDouble() - 0.5D) * 0.06D;

        level.addParticle(ParticleTypes.ELECTRIC_SPARK, x, y, z, vx, vy, vz);
        if (random.nextInt(5) == 0) {
            level.addParticle(ParticleTypes.SMOKE, x, y + 0.05D, z, 0.0D, 0.015D, 0.0D);
        }
        if (random.nextInt(7) == 0) {
            level.addParticle(ParticleTypes.FLAME, x, y, z, vx * 0.4D, vy * 0.4D, vz * 0.4D);
        }
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
        return isMain(state) ? new MunitionsBenchBlockEntity(pos, state) : null;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide || !isMain(state)) {
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

    public enum Part implements StringRepresentable {
        MAIN("main"),
        EXTENSION("extension");

        private final String name;

        Part(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}

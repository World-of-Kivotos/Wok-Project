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
import net.minecraft.world.level.BlockGetter;
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
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Supplier;

public final class MunitionsBenchBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    public static final EnumProperty<Layout> LAYOUT = EnumProperty.create("layout", Layout.class);
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");
    private static final VoxelShape LEGACY_SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D);
    private static final VoxelShape WIDE_BODY_SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 25.5D, 16.0D);
    private static final VoxelShape WIDE_MAIN_NORTH_SHAPE = Shapes.or(WIDE_BODY_SHAPE,
            Block.box(0.5D, 2.5D, -4.0D, 8.0D, 8.0D, 1.0D));
    private static final VoxelShape WIDE_MAIN_EAST_SHAPE = Shapes.or(WIDE_BODY_SHAPE,
            Block.box(15.0D, 2.5D, 0.5D, 20.0D, 8.0D, 8.0D));
    private static final VoxelShape WIDE_MAIN_SOUTH_SHAPE = Shapes.or(WIDE_BODY_SHAPE,
            Block.box(8.0D, 2.5D, 15.0D, 15.5D, 8.0D, 20.0D));
    private static final VoxelShape WIDE_MAIN_WEST_SHAPE = Shapes.or(WIDE_BODY_SHAPE,
            Block.box(-4.0D, 2.5D, 8.0D, 1.0D, 8.0D, 15.5D));

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
                .setValue(LAYOUT, Layout.LEGACY_DEPTH)
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
        return isMain(state) ? pos : pos.relative(extensionDirection(state).getOpposite());
    }

    public static BlockPos extensionPos(BlockPos mainPos, BlockState mainState) {
        return mainPos.relative(extensionDirection(mainState));
    }

    public static Direction extensionDirection(BlockState state) {
        Direction facing = state.getValue(FACING);
        return state.getValue(LAYOUT) == Layout.WIDE
                ? facing.getClockWise()
                : facing.getOpposite();
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART, LAYOUT, ACTIVE);
    }

    /**
     * WIDE 副格用 ENTITYBLOCK_ANIMATED 而不是 INVISIBLE: 两者都不会让区块网格画方块模型 (ChunkRenderDispatcher
     * 只画 RenderShape.MODEL; 副格没有 BlockEntity, {@link #newBlockEntity} 对它返 null, 所以也不会多渲染
     * 一份骨骼模型), 但原版 {@code ParticleEngine.crack} 第一件事就是判 {@code getRenderShape() == INVISIBLE}
     * 并直接 return —— 挖副格连破坏进度的裂纹粒子都画不出来。({@code destroy} 那条路只判 isAir 再走 Forge 的
     * IClientBlockExtensions, 不看 RenderShape, 碎屑粒子两种取值都有。)
     */
    @Override
    public RenderShape getRenderShape(BlockState state) {
        if (state.getValue(LAYOUT) == Layout.LEGACY_DEPTH) {
            return RenderShape.MODEL;
        }
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return outlineShape(state);
    }

    /**
     * 碰撞箱不能带上外伸的出料抽屉。{@link #getStateForPlacement} 取 {@code getHorizontalDirection().getOpposite()},
     * 也就是机器正面恒对着放置者, 抽屉那 4/16 恰好伸进放置者所站的那一格; 而原版 {@code BlockItem.canPlace}
     * 会调 {@code Level.isUnobstructed}, 只要待放置状态的碰撞形状与任何 {@code blocksBuilding} 实体相交
     * 就返回 false —— 玩家贴身往脚前一格放台子会被静默拒绝, 没有任何提示。抽屉只留在轮廓形状里做选择框。
     */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                        CollisionContext context) {
        return state.getValue(LAYOUT) == Layout.LEGACY_DEPTH ? LEGACY_SHAPE : WIDE_BODY_SHAPE;
    }

    private static VoxelShape outlineShape(BlockState state) {
        if (state.getValue(LAYOUT) == Layout.LEGACY_DEPTH) {
            return LEGACY_SHAPE;
        }
        if (!isMain(state)) {
            return WIDE_BODY_SHAPE;
        }
        return switch (state.getValue(FACING)) {
            case NORTH -> WIDE_MAIN_NORTH_SHAPE;
            case EAST -> WIDE_MAIN_EAST_SHAPE;
            case SOUTH -> WIDE_MAIN_SOUTH_SHAPE;
            case WEST -> WIDE_MAIN_WEST_SHAPE;
            default -> throw new IllegalStateException("Wide munitions bench has non-horizontal facing");
        };
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockPos extensionPos = context.getClickedPos().relative(facing.getClockWise());
        if (!context.getLevel().getBlockState(extensionPos).canBeReplaced(context)
                || !context.getLevel().getWorldBorder().isWithinBounds(extensionPos)) {
            return null;
        }
        return defaultBlockState()
                .setValue(FACING, facing)
                .setValue(PART, Part.MAIN)
                .setValue(LAYOUT, Layout.WIDE)
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
     * (d) 复核 (blocker): {@code level.restoringBlockSnapshots} 时必须跳过。撞上限被取消的放置也会走一遍
     *     "setBlock 主+副 -> EntityMultiPlaceEvent 取消 -> BlockSnapshot.restore 把两半 setBlock 回 AIR"——
     *     此时 {@link #setPlacedBy} 早已把 owner 写好但 increment 从未执行 (event 在 increment 之前就
     *     setCanceled), 若这里照常 decrement 就会把台数计数刷到比实际已放置台数还低, 相当于每撞一次上限就
     *     白送一格额度, 可无限刷台。vanilla 自己的 {@code Block.popResource}/{@code popExperience} 就是靠这个
     *     字段跳过快照回滚期的副作用 (forge-1.20.1-47.4.20 sources 核实), 这里对齐同一套纪律。
     */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!level.isClientSide && !level.restoringBlockSnapshots && isMain(state) && !state.is(newState.getBlock())
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
        Direction extensionDirection = extensionDirection(state);
        Direction linkDirection = isMain(state) ? extensionDirection : extensionDirection.getOpposite();
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
        boolean wide = state.getValue(LAYOUT) == Layout.WIDE;
        // LEGACY 机身只有一格高, 主副格前后排列, 火花贴着正面 0.18 格发。WIDE 机身高 25.5/16 且两半左右
        // 排列, 沿用老参数会把大半火花埋进模型实体里, 所以按新机身的工作面取高度: 压头骨骼在 y 0.72-1.28,
        // 出料抽屉在 y 0.18-0.50, 两处轮流发。
        double forward = wide ? 0.30D : (isMain(state) ? 0.18D : -0.18D);
        double lateral = (random.nextDouble() - 0.5D) * 0.62D;
        boolean atDrawer = wide && random.nextInt(3) == 0;
        double x = pos.getX() + 0.5D + facing.getStepX() * forward + side.getStepX() * lateral;
        double y = pos.getY() + (wide
                ? (atDrawer ? 0.20D + random.nextDouble() * 0.28D : 0.78D + random.nextDouble() * 0.52D)
                : 0.72D + random.nextDouble() * 0.28D);
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

    /**
     * WIDE 双格是手性的: 副格恒在 {@code facing.getClockWise()} 一侧。镜像不是旋转, 只翻 FACING 会让两半
     * 的相对位置与 {@link #extensionDirection} 对不上, 结构方块镜像放置出来的台子在下一次邻居更新时被
     * {@link #updateShape} 判定成断链, 两半一起变成空气。所以镜像后必须再把 FACING 翻一次以换回手性
     * (顺时针侧变逆时针侧)。手性结构无法在镜像下同时保住朝向和占位, 这里选择保住占位: 镜像后的台子
     * 会朝向反面, 但两半仍然连着。LEGACY 的副格在 {@code facing.getOpposite()}, 本身镜像对称, 保持原样。
     */
    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        BlockState mirrored = state.rotate(mirror.getRotation(state.getValue(FACING)));
        if (mirror == Mirror.NONE || mirrored.getValue(LAYOUT) != Layout.WIDE) {
            return mirrored;
        }
        return mirrored.setValue(FACING, mirrored.getValue(FACING).getOpposite());
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

    public enum Layout implements StringRepresentable {
        LEGACY_DEPTH("legacy_depth"),
        WIDE("wide");

        private final String name;

        Layout(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}

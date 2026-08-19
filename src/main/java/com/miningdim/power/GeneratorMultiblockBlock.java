package com.miningdim.power;

import com.miningdim.power.generator.GeneratorBlockEntity;
import com.miningdim.power.generator.GeneratorPortBlockEntity;
import com.miningdim.power.generator.GeneratorSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * A complete 3 x 2 x 2 generator shell stored as twelve states of one registered block.
 * The placement position is the lower front-center cell ({@link Part#X1_Z0_Y0}).
 */
public final class GeneratorMultiblockBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    public static final Part ANCHOR_PART = Part.X1_Z0_Y0;
    public static final Part PORT_PART = Part.X1_Z1_Y0;

    private static final ThreadLocal<Boolean> CLEARING_STRUCTURE =
            ThreadLocal.withInitial(() -> false);
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/power");
    /** anchor 周期自检间隔 (tick)。只为兜底捡漏, 不必更密。 */
    private static final int STRUCTURE_AUDIT_INTERVAL = 20;
    private final GeneratorSpec spec;

    public GeneratorMultiblockBlock(GeneratorSpec spec, BlockBehaviour.Properties properties) {
        super(properties);
        this.spec = Objects.requireNonNull(spec, "spec");
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(PART, ANCHOR_PART));
    }

    public GeneratorSpec spec() {
        return spec;
    }

    public static boolean isAnchor(BlockState state) {
        return state.hasProperty(PART) && state.getValue(PART) == ANCHOR_PART;
    }

    /**
     * Resolves a part position from the lower front-center anchor. Models are authored facing north:
     * local +x follows the clockwise horizontal axis and local +z points behind the front face.
     */
    public static BlockPos partPos(BlockPos anchorPos, Direction facing, Part part) {
        Direction localX = facing.getClockWise();
        Direction back = facing.getOpposite();
        int xOffset = part.x() - ANCHOR_PART.x();
        return anchorPos.offset(
                localX.getStepX() * xOffset + back.getStepX() * part.z(),
                part.y(),
                localX.getStepZ() * xOffset + back.getStepZ() * part.z());
    }

    /** Resolves the lower front-center anchor from any one of the twelve part states. */
    public static BlockPos anchorPos(BlockPos partPos, BlockState state) {
        Direction facing = state.getValue(FACING);
        Direction localX = facing.getClockWise();
        Direction back = facing.getOpposite();
        Part part = state.getValue(PART);
        int xOffset = part.x() - ANCHOR_PART.x();
        return partPos.offset(
                -localX.getStepX() * xOffset - back.getStepX() * part.z(),
                -part.y(),
                -localX.getStepZ() * xOffset - back.getStepZ() * part.z());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        Component obstruction = findObstruction(context, facing);
        if (obstruction != null) {
            // 12 格里任何一格不通过就整体拒绝, 原版对此只会静默 FAIL; 3x2x2 的占地远超玩家的直觉,
            // 不把被挡的那一格报出来, 现场表现就是"右键没反应", 且无法区分是地形挡住还是残留幽灵格。
            if (context.getPlayer() instanceof ServerPlayer player) {
                player.displayClientMessage(obstruction, true);
            }
            return null;
        }
        return defaultBlockState()
                .setValue(FACING, facing)
                .setValue(PART, ANCHOR_PART);
    }

    /**
     * 逐格核对 3x2x2 占地。可放置时返回 null; 否则返回首个被挡格的诊断文案 (含绝对坐标与阻挡来源),
     * 供放置路径回报玩家。包级可见以便 GameTest 直接断言文案内容而不必抓网络包。
     */
    @Nullable
    Component findObstruction(BlockPlaceContext context, Direction facing) {
        BlockPos anchorPos = context.getClickedPos();
        Level level = context.getLevel();
        CollisionContext collisionContext = context.getPlayer() == null
                ? CollisionContext.empty()
                : CollisionContext.of(context.getPlayer());
        for (Part part : Part.values()) {
            BlockPos targetPos = partPos(anchorPos, facing, part);
            if (!level.isInWorldBounds(targetPos)
                    || level.isOutsideBuildHeight(targetPos)
                    || !level.getWorldBorder().isWithinBounds(targetPos)) {
                return Component.translatable("message.miningdim.power.generator.out_of_bounds",
                        targetPos.getX(), targetPos.getY(), targetPos.getZ());
            }
            BlockState occupant = level.getBlockState(targetPos);
            if (!occupant.canBeReplaced(context)) {
                return Component.translatable("message.miningdim.power.generator.blocked_by_block",
                        targetPos.getX(), targetPos.getY(), targetPos.getZ(),
                        occupant.getBlock().getName());
            }
            BlockState targetState = defaultBlockState()
                    .setValue(FACING, facing)
                    .setValue(PART, part);
            if (!level.isUnobstructed(targetState, targetPos, collisionContext)) {
                return Component.translatable("message.miningdim.power.generator.blocked_by_entity",
                        targetPos.getX(), targetPos.getY(), targetPos.getZ());
            }
        }
        return null;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !isAnchor(state)) {
            return;
        }

        Direction facing = state.getValue(FACING);
        for (Part part : Part.values()) {
            if (part != ANCHOR_PART) {
                level.setBlock(partPos(pos, facing, part), state.setValue(PART, part), Block.UPDATE_ALL);
            }
        }
        if (level instanceof ServerLevel serverLevel) {
            GeneratorBlockEntity.ensureLegacyEntities(serverLevel, pos);
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.CONSUME;
        }
        GeneratorBlockEntity controller = GeneratorBlockEntity.ensureLegacyEntities(serverLevel, pos);
        if (controller == null) {
            // 右键是玩家对着一格幽灵能做的最直接动作: 就地复核, 确属残留就当场清掉并告知, 免得只能靠挖。
            if (auditStructure(serverLevel, pos, state)) {
                serverPlayer.displayClientMessage(
                        Component.translatable("message.miningdim.power.generator.broken_cleared"), true);
            }
            return InteractionResult.CONSUME;
        }
        if (isAnchor(state)) {
            NetworkHooks.openScreen(serverPlayer, controller, buf -> buf.writeBlockPos(controller.getBlockPos()));
        }
        return InteractionResult.CONSUME;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        Part part = state.getValue(PART);
        if (part == ANCHOR_PART) {
            return new GeneratorBlockEntity(pos, state);
        }
        if (part == PORT_PART) {
            return new GeneratorPortBlockEntity(pos, state);
        }
        return null;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                    BlockEntityType<T> type) {
        if (level.isClientSide || !isAnchor(state)) {
            return null;
        }
        return createTickerHelper(type, PowerRegistry.GENERATOR_CONTROLLER_BE.get(),
                (tickerLevel, tickerPos, tickerState, controller) -> {
                    if (auditOnSchedule(tickerLevel, tickerPos, tickerState)) {
                        return;
                    }
                    controller.serverTick();
                });
    }

    /**
     * anchor 的周期性自检。邻居更新触发的 {@link #updateShape} 覆盖不到两类残留: 旧存档里遗留的孤格,
     * 以及被 /setblock 之类直接改坏、之后再没有邻居动过的机器。按坐标错峰, 避免全服发电机挤在同一 tick。
     */
    private boolean auditOnSchedule(Level level, BlockPos pos, BlockState state) {
        if (!(level instanceof ServerLevel serverLevel)
                || Math.floorMod(serverLevel.getGameTime() + pos.asLong(), STRUCTURE_AUDIT_INTERVAL) != 0) {
            return false;
        }
        return auditStructure(serverLevel, pos, state);
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static <E extends BlockEntity, A extends BlockEntity> BlockEntityTicker<A> createTickerHelper(
            BlockEntityType<A> actual, BlockEntityType<E> expected, BlockEntityTicker<? super E> ticker) {
        return actual == expected ? (BlockEntityTicker<A>) ticker : null;
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        // 结构完整性只能在世界稳定之后判定: 放置补格与拆除清理本身都会制造中间态, 当场裁决必然误杀。
        // 故一律推迟一 tick 交给 tick() 复核; 届时方块若已消失, 原版调度会按方块类型比对自行跳过。
        level.scheduleTick(pos, this, 1);
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        auditStructure(level, pos, state);
    }

    /**
     * 判定并清除一处残缺结构: 12 格里只要有一格不是本机对应的 part 状态, 整机就已不成立, 剩下的格子
     * 就是"看不见整机、却占着位置"的残留 —— 它们会让同一片空间再也放不下发电机。返回 true 表示本次
     * 确实清掉了残留。
     *
     * 本体方块不掉落: 走到残缺态说明正常拆除路径已经掉过一次本体, 再掉一次就是复制; 但玩家自己放进去
     * 的燃料芯与保险丝必须归还, 故仍走 dropInternalContents。
     */
    private boolean auditStructure(ServerLevel level, BlockPos pos, BlockState state) {
        if (CLEARING_STRUCTURE.get() || structureIntegrity(level, pos, state) != Integrity.BROKEN) {
            return false;
        }
        BlockPos anchorPos = anchorPos(pos, state);
        if (level.getBlockEntity(anchorPos) instanceof GeneratorBlockEntity controller) {
            controller.dropInternalContents();
        }
        LOGGER.warn("clearing broken generator structure dimension={} trigger={} part={} facing={} anchor={}",
                level.dimension().location(), pos, state.getValue(PART).getSerializedName(),
                state.getValue(FACING), anchorPos);
        clearStructure(level, pos, state);
        return true;
    }

    private Integrity structureIntegrity(ServerLevel level, BlockPos pos, BlockState state) {
        Direction facing = state.getValue(FACING);
        BlockPos anchorPos = anchorPos(pos, state);
        for (Part part : Part.values()) {
            BlockPos partPos = partPos(anchorPos, facing, part);
            // 跨区块的机器在邻块未加载时无法判定; 此时宁可不动手, 否则会把完好的机器误判成残缺删掉。
            if (!level.hasChunkAt(partPos)) {
                return Integrity.UNLOADED;
            }
            if (!matchesPart(level.getBlockState(partPos), this, facing, part)) {
                return Integrity.BROKEN;
            }
        }
        return Integrity.INTACT;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!level.isClientSide && !state.is(newState.getBlock()) && !CLEARING_STRUCTURE.get()) {
            BlockPos anchorPos = anchorPos(pos, state);
            if (level.getBlockEntity(anchorPos) instanceof GeneratorBlockEntity controller) {
                controller.dropInternalContents();
            }
            clearStructure(level, pos, state);
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    private static void clearStructure(Level level, BlockPos sourcePos, BlockState sourceState) {
        GeneratorMultiblockBlock block = (GeneratorMultiblockBlock) sourceState.getBlock();
        Direction facing = sourceState.getValue(FACING);
        BlockPos anchorPos = anchorPos(sourcePos, sourceState);

        CLEARING_STRUCTURE.set(true);
        try {
            for (Part part : Part.values()) {
                BlockPos targetPos = partPos(anchorPos, facing, part);
                BlockState targetState = level.getBlockState(targetPos);
                if (matchesPart(targetState, block, facing, part)) {
                    level.setBlock(targetPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        } finally {
            CLEARING_STRUCTURE.remove();
        }
    }

    /** 熔毁路径移除整机时复用多方块一致性清理，避免触发普通库存掉落路径。 */
    public static void clearStructureForMeltdown(ServerLevel level, BlockPos anchorPos) {
        BlockState anchorState = level.getBlockState(anchorPos);
        if (!(anchorState.getBlock() instanceof GeneratorMultiblockBlock) || !isAnchor(anchorState)) {
            throw new IllegalArgumentException("meltdown clear requires generator anchor at " + anchorPos);
        }
        clearStructure(level, anchorPos, anchorState);
    }

    private static boolean matchesPart(BlockState state, GeneratorMultiblockBlock block,
                                       Direction facing, Part part) {
        return state.getBlock() == block
                && state.getValue(FACING) == facing
                && state.getValue(PART) == part;
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        BlockState mirrored = state.rotate(mirror.getRotation(state.getValue(FACING)));
        if (mirror == Mirror.NONE) {
            return mirrored;
        }
        return mirrored.setValue(PART, state.getValue(PART).mirrored());
    }

    /** 结构自检的三态: UNLOADED 表示区块没加载全, 判不了, 与"完好"同样不允许动手。 */
    private enum Integrity {
        INTACT,
        BROKEN,
        UNLOADED
    }

    public enum Part implements StringRepresentable {
        X0_Z0_Y0("x0_z0_y0", 0, 0, 0),
        X0_Z0_Y1("x0_z0_y1", 0, 0, 1),
        X0_Z1_Y0("x0_z1_y0", 0, 1, 0),
        X0_Z1_Y1("x0_z1_y1", 0, 1, 1),
        X1_Z0_Y0("x1_z0_y0", 1, 0, 0),
        X1_Z0_Y1("x1_z0_y1", 1, 0, 1),
        X1_Z1_Y0("x1_z1_y0", 1, 1, 0),
        X1_Z1_Y1("x1_z1_y1", 1, 1, 1),
        X2_Z0_Y0("x2_z0_y0", 2, 0, 0),
        X2_Z0_Y1("x2_z0_y1", 2, 0, 1),
        X2_Z1_Y0("x2_z1_y0", 2, 1, 0),
        X2_Z1_Y1("x2_z1_y1", 2, 1, 1);

        private static final Part[][][] BY_COORDINATE = new Part[3][2][2];

        static {
            for (Part part : values()) {
                BY_COORDINATE[part.x][part.z][part.y] = part;
            }
        }

        private final String name;
        private final int x;
        private final int z;
        private final int y;

        Part(String name, int x, int z, int y) {
            this.name = name;
            this.x = x;
            this.z = z;
            this.y = y;
        }

        public int x() {
            return x;
        }

        public int z() {
            return z;
        }

        public int y() {
            return y;
        }

        public Part mirrored() {
            return BY_COORDINATE[2 - x][z][y];
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}

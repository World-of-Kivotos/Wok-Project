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
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public final class MunitionsBenchBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    public static final EnumProperty<Layout> LAYOUT = EnumProperty.create("layout", Layout.class);
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");
    private static final VoxelShape LEGACY_SHAPE = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D);
    /**
     * WIDE 两格的轮廓 (选择框 / 右键命中), 按格 (主 / 副) 和朝向各一份, 由 {@link MunitionsBenchGeometry} 的朝北盒子转出来
     * (与方块状态的 y 旋转同向): 静态件的大块 ({@code MAIN_BOXES} / {@code EXTENSION_BOXES}) 加运动件扫过的范围
     * ({@code *_PART_BOXES}), 皮带上的弹、冲头、两根杆都点得中台子。盒子全都在自己那一格里。
     */
    private static final Map<Part, Map<Direction, VoxelShape>> WIDE_OUTLINES = createWideOutlines();
    /**
     * WIDE 两格的碰撞: 每格一整块实心柱, 高到该格静态轮廓箱的最高点 (主格 22.5 px 的压机, 副格 20.5 px 的压机横梁),
     * 与朝向无关。台面只有 8 px, 低于玩家 / 生物的跨步高度 (9.6 px), 贴着模型的碰撞会让人一步跨上台面、站进运动件中间;
     * 柱子也高过起跳高度 (约 20 px), 跳不上去。与改版前 GeckoLib 机身的整格碰撞一样 (只是高度跟着新模型走)。
     * 柱子不出格, 所以原版 {@code BlockItem.canPlace} 的实体遮挡检查不会拦下贴身放置 (旧 GeckoLib 机身的出料抽屉伸出
     * 正面 4 px, 当年只能留在轮廓里, 不敢进碰撞箱)。
     */
    private static final Map<Part, VoxelShape> WIDE_COLLISIONS = createWideCollisions();

    private final Supplier<BlockEntityType<MunitionsBenchBlockEntity>> beType;
    /** 档位下标, 见 {@link #tier()}。 */
    private final int tier;
    private final int unlockLevel;
    private final int maxEffectiveLevel;

    /**
     * @param tier 档位下标 0..5 (普通..闪耀), 注册时由 ModMunitionsBlocks 按注册名给定 (GameTest 逐块核对它与注册名对应的档位相同);
     *             越界直接拒绝 (注册期的编程错误, 不静默按普通档)
     */
    public MunitionsBenchBlock(BlockBehaviour.Properties properties,
                              Supplier<BlockEntityType<MunitionsBenchBlockEntity>> beType,
                              int tier,
                              int unlockLevel,
                              int maxEffectiveLevel) {
        super(properties);
        if (tier < 0 || tier >= MunitionsBenchProgram.tierCount()) {
            throw new IllegalArgumentException("munitions bench tier must be 0.." + (MunitionsBenchProgram.tierCount() - 1)
                    + ", got " + tier);
        }
        this.beType = beType;
        this.tier = tier;
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

    /**
     * 档位下标 0..5 (普通..闪耀, 与注册名、资产文件名的档位同序), 构造时给定, 不查注册表 (注册完成前后都一样, 不会把没注册时的普通档记下来)。
     * 渲染器 (运动件、火花、灯效、计数屏颜色) 与服务端的冲压音都按它取 {@link MunitionsBenchProgram#cycleTicks} (档位越高生产动画越快),
     * 两端同一个数。
     */
    public int tier() {
        return tier;
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
     * 两种布局都由区块网格画静态 JSON: LEGACY 是老的整格模型; WIDE 是弹药流水线的静态件
     * ({@code munitions_bench*_line_*.json}), 皮带上的弹、冲头这些运动件由主格方块实体的渲染器
     * ({@code MunitionsBenchRenderer}) 另画。MODEL 也让两格都有原版的破坏裂纹与碎屑粒子 (流水线的 JSON 关掉了环境光遮蔽)。
     */
    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /** 轮廓 (选择框 / 右键命中): WIDE 贴着静态件与运动件 (见 {@link #WIDE_OUTLINES}), LEGACY 仍是整格。 */
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (state.getValue(LAYOUT) == Layout.LEGACY_DEPTH) {
            return LEGACY_SHAPE;
        }
        return WIDE_OUTLINES.get(state.getValue(PART)).get(state.getValue(FACING));
    }

    /** 碰撞: WIDE 每格一整块实心柱 (见 {@link #WIDE_COLLISIONS}), LEGACY 仍是整格。 */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (state.getValue(LAYOUT) == Layout.LEGACY_DEPTH) {
            return LEGACY_SHAPE;
        }
        return WIDE_COLLISIONS.get(state.getValue(PART));
    }

    /**
     * 支撑面 (火把、按钮、告示牌能不能贴上去) 按看得见的轮廓算, 不按碰撞柱: 碰撞柱的侧面和顶面都是整面, 按它算会让
     * 火把贴在台面上方的空气里、方块顶上的东西插进压机。
     */
    @Override
    public VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
        return getShape(state, level, pos, CollisionContext.empty());
    }

    private static Map<Part, Map<Direction, VoxelShape>> createWideOutlines() {
        Map<Part, Map<Direction, VoxelShape>> shapes = new EnumMap<>(Part.class);
        shapes.put(Part.MAIN, rotatedShapes(MunitionsBenchGeometry.MAIN_BOXES, MunitionsBenchGeometry.MAIN_PART_BOXES));
        shapes.put(Part.EXTENSION, rotatedShapes(MunitionsBenchGeometry.EXTENSION_BOXES,
                MunitionsBenchGeometry.EXTENSION_PART_BOXES));
        return shapes;
    }

    private static Map<Direction, VoxelShape> rotatedShapes(float[][]... northBoxLists) {
        Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            VoxelShape shape = Shapes.empty();
            for (float[][] northBoxes : northBoxLists) {
                for (float[] northBox : northBoxes) {
                    float[] box = MunitionsBenchGeometry.rotated(northBox, quarterTurns(facing));
                    shape = Shapes.or(shape, Block.box(box[0], box[1], box[2], box[3], box[4], box[5]));
                }
            }
            shapes.put(facing, shape.optimize());
        }
        return shapes;
    }

    private static Map<Part, VoxelShape> createWideCollisions() {
        Map<Part, VoxelShape> shapes = new EnumMap<>(Part.class);
        shapes.put(Part.MAIN, Block.box(0.0D, 0.0D, 0.0D, 16.0D, topPixels(MunitionsBenchGeometry.MAIN_BOXES), 16.0D));
        shapes.put(Part.EXTENSION,
                Block.box(0.0D, 0.0D, 0.0D, 16.0D, topPixels(MunitionsBenchGeometry.EXTENSION_BOXES), 16.0D));
        return shapes;
    }

    private static double topPixels(float[][] boxes) {
        double top = 0.0D;
        for (float[] box : boxes) {
            top = Math.max(top, box[4]);
        }
        return top;
    }

    /** 朝向 → 俯视顺时针转的 90° 次数 (与方块状态的 y 旋转、{@link MunitionsBenchGeometry#rotated} 相同)。 */
    public static int quarterTurns(Direction facing) {
        return switch (facing) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0;
        };
    }

    /**
     * 运动件渲染器绕主格中心转的角度 ({@code Axis.YP.rotationDegrees}, 右手系, 俯视逆时针为正): 北 0 / 东 -90 / 南 180 / 西 90,
     * 即俯视顺时针转 {@link #quarterTurns} 次, 与方块状态的 y 旋转、{@link #benchPixelToWorld} 同向。放在通用代码里,
     * GameTest 才能拿它和 benchPixelToWorld 对上 (渲染器是客户端类)。
     */
    public static float partsYRotationDegrees(Direction facing) {
        return switch (facing) {
            case EAST -> -90.0F;
            case SOUTH -> 180.0F;
            case WEST -> 90.0F;
            default -> 0.0F;
        };
    }

    /**
     * 朝北的整台像素 (主格局部: x 东、y 上、z 南, 原点主格西北下角, 副格在 x 16..32) → 世界坐标, 按朝向绕主格中心转,
     * 与方块状态的 y 旋转、运动件渲染器的朝向角一致。服务端的冲压音与渲染器的火花都用它找冲压点。
     */
    public static Vec3 benchPixelToWorld(BlockPos mainPos, Direction facing, double pixelX, double pixelY,
                                         double pixelZ) {
        double x = pixelX;
        double z = pixelZ;
        for (int turn = 0; turn < quarterTurns(facing); turn++) {
            // (x, z) → (16 - z, x): 与 MunitionsBenchGeometry.rotated 同向, 北面 (z = 0) 转到东面 (x = 16)
            double turnedX = 16.0D - z;
            z = x;
            x = turnedX;
        }
        return new Vec3(mainPos.getX() + x / 16.0D, mainPos.getY() + pixelY / 16.0D, mainPos.getZ() + z / 16.0D);
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
     *
     * 同一处也是内容物掉落的唯一出口 (V09): 料槽、缓冲弹药、迁移待吐队列经 {@link MunitionsBenchBlockEntity#dropContents}
     * 交出后按容器惯例撒在原位, 覆盖与台数回收相同的全部移除路径; 快照回滚期同样跳过 (那台是刚被撤销放置的空台)。
     */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!level.isClientSide && !level.restoringBlockSnapshots && isMain(state) && !state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof MunitionsBenchBlockEntity be) {
            UUID owner = be.owner();
            if (owner != null && level instanceof ServerLevel serverLevel) {
                MunitionsSavedData.get(serverLevel.getServer().overworld()).decrement(owner);
            }
            for (ItemStack stack : be.dropContents()) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    /**
     * 上锁的台只许台主或 OP 拆 (V09): 锁原先只拦 GUI, 谁都能一斧头把别人的台连料带弹拆掉。
     * 生存挖掘在这里把进度压成 0 (服务端判不到挖完, 客户端预测的破坏随包确认回滚, 也不白耗工具耐久);
     * 创造模式不看挖掘进度, 由 {@link #onDestroyedByPlayer} 兜住。爆炸与实体破坏由 {@link #getExplosionResistance}
     * 和 {@link #canEntityDestroy} 兜住; 指令 (/setblock、/fill) 本就要 OP, 不拦。
     */
    @Override
    public float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        if (!mayPlayerBreak(state, level, pos, player)) {
            return 0.0F;
        }
        return super.getDestroyProgress(state, player, level, pos);
    }

    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player,
                                       boolean willHarvest, FluidState fluid) {
        if (!mayPlayerBreak(state, level, pos, player)) {
            return false;
        }
        return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }

    /**
     * 上锁的台同样扛住爆炸 (V09 复审): 锁若只拦玩家挖掘, TNT、苦力怕、HE 弹药 (测试端 ExplosiveAmmoDestroysBlock=true)、
     * 发电机熔毁 (复用同一套抗爆判定) 照样能把台炸掉, 而 {@link #onRemove} 会把料和缓冲弹药撒在原地 —— 新生成的
     * 掉落物不吃本次爆炸的伤害, 爆炸就成了绕过锁偷料偷弹。所以上锁时两半都报基岩级抗爆: 只炸副格也不行, 副格没了
     * 主格会经 {@link #updateShape} 级联变 AIR, 照样走 onRemove 掉落。抗爆跟着主格 BE 的锁走, 解锁即回到锻造台原值,
     * 照常可炸可掉落。代价: 上锁的台对身后方块有基岩同等的挡爆效果。
     */
    @Override
    public float getExplosionResistance(BlockState state, BlockGetter level, BlockPos pos, Explosion explosion) {
        if (lockedBench(state, level, pos) != null) {
            return Blocks.BEDROCK.getExplosionResistance();
        }
        return super.getExplosionResistance(state, level, pos, explosion);
    }

    /**
     * 凋灵本体撞方块、末影龙穿墙走的是这条判定而不是抗爆; 蓝色凋灵头颅更是在这里放行时把抗爆直接压到 0.8
     * ({@code WitherSkull.getBlockExplosionResistance}), 只改 {@link #getExplosionResistance} 拦不住。上锁时一律不许。
     */
    @Override
    public boolean canEntityDestroy(BlockState state, BlockGetter level, BlockPos pos, Entity entity) {
        if (lockedBench(state, level, pos) != null) {
            return false;
        }
        return super.canEntityDestroy(state, level, pos, entity);
    }

    /** 未上锁 (或找不到主格 BE) 时人人可拆; 上锁时仅台主或 OP。锁状态只在服务端权威, 客户端侧恒放行。 */
    private static boolean mayPlayerBreak(BlockState state, BlockGetter level, BlockPos pos, Player player) {
        MunitionsBenchBlockEntity be = lockedBench(state, level, pos);
        return be == null || be.isOwner(player) || player.hasPermissions(2);
    }

    /** 按主格 BE 查锁 (副格经 {@link #mainPos} 指回主格): 已上锁返回该 BE; 未上锁或找不到主格 BE 返回 null。 */
    @Nullable
    private static MunitionsBenchBlockEntity lockedBench(BlockState state, BlockGetter level, BlockPos pos) {
        return level.getBlockEntity(mainPos(pos, state)) instanceof MunitionsBenchBlockEntity be && be.isLocked()
                ? be
                : null;
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

    /**
     * 只给 LEGACY 老台子发随机火花 (老模型没有运动件, 保持原样)。WIDE 的火花由运动件渲染器在冲头压到底的那一刻
     * (这一档的 {@link MunitionsBenchProgram#strikeTick} + n × 循环长度) 从冲压点放, 与服务端的冲压音同拍, 这里不再按手调位置乱发。
     */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!state.getValue(ACTIVE) || state.getValue(LAYOUT) == Layout.WIDE || random.nextFloat() > 0.72F) {
            return;
        }
        Direction facing = state.getValue(FACING);
        Direction side = facing.getClockWise();
        // LEGACY 机身只有一格高, 主副格前后排列, 火花贴着正面 0.18 格发。
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

package com.miningdim.job.chef.station;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 厨师烹饪台 (炸锅 / 烤炉 / 备餐台, 各三种外观) 的通用方块。造型来自厨师重构的 station-preview 预览工具里
 * 定稿的九个方案, 形状、遮挡、光照与粒子照各方案 meta.json 的实装说明, 逐台的取值写在 {@link CookingStations}。
 *
 * 状态: {@link #FACING} 放置时正面朝向玩家 (模型正面画在 north, 与农夫乐事炉灶一致); {@link #LIT} 是"工作中"。
 * 工作状态统一叫 lit 而不叫 active: 铸铁烤箱灶的方案说明指出, 若以后把台子加进 farmersdelight:heat_sources,
 * 农夫乐事判断热源时只认 lit 属性 (没有 lit 的方块一直算热), 用别的名字会让待机的台子也被当成热源。
 * 旋转 / 镜像 (结构方块、投影类工具) 沿用 {@link HorizontalDirectionalBlock} 自带的实现, 只转 FACING。
 *
 * 本阶段只有方块本身, 没有方块实体、烹饪逻辑与"掌勺"小游戏。为了能进游戏目测待机/工作中两种外观,
 * {@link #use} 暂时提供一个预览开关: 创造模式玩家双手空着潜行右键切换 lit, 生存玩家无效。
 * 这是临时入口, 接入烹饪逻辑 (由方块实体在开始/结束一段工作时切 lit) 后删除。届时要遵守方案说明里的
 * 滞后要求: 只在状态真的变化时 setBlock, 不要每出一道菜来回切 (每切一次都会重建区块网格和光照)。
 */
public class CookingStationBlock extends HorizontalDirectionalBlock {

    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    /** 台子的功能种类; 以后的配方与小游戏按它分流, 外观风格不影响功能。 */
    public enum Kind {
        DEEP_FRYER("deep_fryer"),
        BAKING_OVEN("baking_oven"),
        PREP_COUNTER("prep_counter");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    /** 外观风格: A 田园 / B 商用不锈钢 / C 中式, 三者功能相同, 玩家按喜好造。 */
    public enum Style {
        RUSTIC("rustic"),
        STEEL("steel"),
        CHINESE("chinese");

        private final String id;

        Style(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    private final Kind kind;
    private final Style style;
    private final StationShape shape;
    private final StationParticles.Emitter particles;
    private final boolean decorRisesAbove;

    CookingStationBlock(BlockBehaviour.Properties properties, Kind kind, Style style,
                        StationShape shape, StationParticles.Emitter particles) {
        this(properties, kind, style, shape, particles, false);
    }

    /**
     * @param decorRisesAbove 模型 (台面摆件、烟囱等) 高出方块顶、会伸进上方一格: 上方放方块会穿模,
     *                        物品提示里提醒玩家"上方请留空"。有备用模型自动收起的台子 (冷藏备餐台) 不算。
     */
    CookingStationBlock(BlockBehaviour.Properties properties, Kind kind, Style style,
                        StationShape shape, StationParticles.Emitter particles, boolean decorRisesAbove) {
        super(properties);
        this.kind = kind;
        this.style = style;
        this.shape = shape;
        this.particles = particles;
        this.decorRisesAbove = decorRisesAbove;
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(LIT, false));
    }

    public Kind kind() {
        return kind;
    }

    public Style style() {
        return style;
    }

    /** 模型是否高出方块顶 (见构造参数说明), 供物品提示使用。 */
    public boolean decorRisesAbove() {
        return decorRisesAbove;
    }

    /** 工作中的粒子发射器; GameTest 用它核对出生点。 */
    StationParticles.Emitter particles() {
        return particles;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LIT);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(LIT, false);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shape.outline(state.getValue(FACING));
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                        CollisionContext context) {
        return shape.collision(state.getValue(FACING));
    }

    /** 台子是家具: 生物寻路不把它当成能穿过或站上去走的地面 (同原版炼药锅、农夫乐事厨锅)。 */
    @Override
    public boolean isPathfindable(BlockState state, BlockGetter level, BlockPos pos, PathComputationType type) {
        return false;
    }

    /**
     * 临时预览开关 (见类注释): 创造模式、潜行、双手都空时切换 lit; 其余情况放行给原版。
     * 原版只有在双手都空时才会在潜行状态下调用方块的 use, 这里仍显式检查, 不依赖调用方的约定。
     */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (!isPreviewToggle(player, hand)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        boolean lighting = !state.getValue(LIT);
        Component blocked = lighting ? workBlockedReason(level, pos) : null;
        if (blocked != null) {
            player.displayClientMessage(blocked, true);
            return InteractionResult.CONSUME;
        }
        level.setBlock(pos, state.setValue(LIT, lighting), Block.UPDATE_ALL);
        level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos);
        return InteractionResult.CONSUME;
    }

    static boolean isPreviewToggle(Player player, InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND
                && player.isCreative()
                && player.isShiftKeyDown()
                && player.getMainHandItem().isEmpty()
                && player.getOffhandItem().isEmpty();
    }

    /**
     * 当前位置不能进入工作状态的原因 (给玩家看的动作栏提示), 能工作时返回 null。
     * 自带热源的台子恒为 null; 炉上油锅要看下方热源。
     */
    @Nullable
    protected Component workBlockedReason(Level level, BlockPos pos) {
        return null;
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (state.getValue(LIT)) {
            particles.animate(StationParticles.into(level, pos, state.getValue(FACING)), random);
        }
    }
}

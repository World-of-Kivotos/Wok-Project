package com.miningdim.job.chef.station;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 炉上油锅 (炸锅 A 田园): 坐在炉灶上的铸铁油锅, 和农夫乐事厨锅一样要下方热源才能工作。
 *
 * 热源只按名字读农夫乐事的三个方块标签 (heat_sources / tray_heat_sources / heat_conductors), 判定口径照抄
 * 厨锅: 下方方块在 heat_sources 里就算热 (它有 lit 属性时看 lit); 下方是 heat_conductors (漏斗等) 时再看
 * 下两格。农夫乐事在 build.gradle 里只是开发期 runtimeOnly、正式服可选, 所以这里绝不 import 它的类;
 * 没装农夫乐事时三个标签都是空的, 这台锅就找不到热源 (其余八台自带热源, 不受影响)。
 *
 * {@link #SUPPORT}: 下方是营火类 (tray_heat_sources) 时为 tray, 换带托架和四条腿的 *_tray 模型 (照抄厨锅的托架),
 * 选择框/碰撞箱也像厨锅那样向下多出 1 像素的托架板。下方方块变化时随邻居更新重算; 下方热源熄灭或被拆掉时
 * 顺带把 lit 关掉。
 *
 * 邻居更新覆盖不到的情形 (经漏斗隔一格传热时最下面的热源熄灭或被拆掉、热源被不发形状更新的 setBlock 换掉、
 * 中途卸掉农夫乐事使标签变空) 由计划刻兜底: 进入工作状态时 ({@link #onPlace}, 预览开关与活塞推动都会走到)
 * 排一个 {@link #HEAT_RECHECK_TICKS} 刻后的复核, 仍有热就再排下一次, 没热就熄火。同一位置同一方块的计划刻
 * 原版会去重, 重复排不会叠加。以后接入方块实体逐 tick 复核时可以去掉这一层。
 */
public final class StovetopFryerBlock extends CookingStationBlock {

    public static final EnumProperty<Support> SUPPORT = EnumProperty.create("support", Support.class);

    static final TagKey<Block> HEAT_SOURCES = farmersDelightTag("heat_sources");
    static final TagKey<Block> TRAY_HEAT_SOURCES = farmersDelightTag("tray_heat_sources");
    static final TagKey<Block> HEAT_CONDUCTORS = farmersDelightTag("heat_conductors");

    /** 锅身 14x8x14 像素 (锅脚 + 四壁), 炸篮、木柄、温度计和两侧锅耳不进形状, 同厨锅的勺子。 */
    static final VoxelShape POT = Block.box(1.0D, 0.0D, 1.0D, 15.0D, 8.0D, 15.0D);
    /** 营火托架: 锅下多一块整格宽、1 像素厚的托架板 (农夫乐事厨锅 SHAPE_WITH_TRAY 同口径)。 */
    static final VoxelShape POT_ON_TRAY = Shapes.or(POT, Block.box(0.0D, -1.0D, 0.0D, 16.0D, 0.0D, 16.0D));

    /** 工作中复核下方热源的间隔 (刻)。 */
    static final int HEAT_RECHECK_TICKS = 20;

    /** 锅下面垫的是什么: none 直接坐在方块上 (炉灶等), tray 架在营火类热源上。 */
    public enum Support implements StringRepresentable {
        NONE("none"),
        TRAY("tray");

        private final String name;

        Support(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    StovetopFryerBlock(BlockBehaviour.Properties properties, StationParticles.Emitter particles) {
        // 交给父类的形状不生效: 本类按 SUPPORT 重写了 getShape / getCollisionShape (POT 或 POT_ON_TRAY),
        // 改油锅形状要改上面两个常量, 不是这里。
        super(properties, Kind.DEEP_FRYER, Style.RUSTIC, StationShape.symmetric(POT), particles);
        registerDefaultState(defaultBlockState().setValue(SUPPORT, Support.NONE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(SUPPORT);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState placed = super.getStateForPlacement(context);
        if (placed == null) {
            return null;
        }
        BlockState below = context.getLevel().getBlockState(context.getClickedPos().below());
        return placed.setValue(SUPPORT, supportFor(below));
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (direction != Direction.DOWN) {
            return state;
        }
        BlockState updated = state.setValue(SUPPORT, supportFor(neighborState));
        if (updated.getValue(LIT) && !isHeated(level, pos)) {
            updated = updated.setValue(LIT, false);
        }
        return updated;
    }

    /** 进入工作状态 (lit 由 false 变 true, 或带着 lit=true 被放下 / 推过来) 时排第一次热源复核。 */
    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide && state.getValue(LIT)) {
            level.scheduleTick(pos, this, HEAT_RECHECK_TICKS);
        }
    }

    /** 计划刻复核: 下方没热了就熄火 (发完整更新, 粒子随之停), 还有热就排下一次。 */
    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.getValue(LIT)) {
            return;
        }
        if (isHeated(level, pos)) {
            level.scheduleTick(pos, this, HEAT_RECHECK_TICKS);
        } else {
            level.setBlock(pos, state.setValue(LIT, false), Block.UPDATE_ALL);
        }
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(SUPPORT) == Support.TRAY ? POT_ON_TRAY : POT;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                        CollisionContext context) {
        return getShape(state, level, pos, context);
    }

    @Nullable
    @Override
    protected Component workBlockedReason(Level level, BlockPos pos) {
        return isHeated(level, pos) ? null : Component.translatable("chef.station.needs_heat");
    }

    /** 下方是否有可用热源 (农夫乐事厨锅 isHeated 同口径, 含经 heat_conductors 隔一格传热)。 */
    public static boolean isHeated(BlockGetter level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        if (below.is(HEAT_SOURCES)) {
            return litOrAlwaysHot(below);
        }
        if (below.is(HEAT_CONDUCTORS)) {
            BlockState source = level.getBlockState(pos.below(2));
            return source.is(HEAT_SOURCES) && litOrAlwaysHot(source);
        }
        return false;
    }

    static Support supportFor(BlockState below) {
        return below.is(TRAY_HEAT_SOURCES) ? Support.TRAY : Support.NONE;
    }

    /** 热源方块有 lit 属性时看 lit (营火、农夫乐事炉灶), 没有时一直算热 (岩浆块、火)。 */
    private static boolean litOrAlwaysHot(BlockState source) {
        return !source.hasProperty(BlockStateProperties.LIT) || source.getValue(BlockStateProperties.LIT);
    }

    private static TagKey<Block> farmersDelightTag(String path) {
        return TagKey.create(Registries.BLOCK, new ResourceLocation("farmersdelight", path));
    }
}

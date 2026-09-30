package com.miningdim.district.guard;

import com.miningdim.district.flan.LogThrottle;
import com.miningdim.district.guard.create.CreateBlockPolicy;
import com.miningdim.district.service.DistrictContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Predicate;

/**
 * 守卫的静态门面 (设计文档 22.3、22.9): 持有 volatile 的当前 {@link GuardView}, 初值 {@link GuardView#OFF} (什么都不拦)。
 * mixin 的方法体只转调这里 (或 CreateGuards), 判定全在普通类里。
 *
 * <ul>
 *   <li>{@link #install}: DistrictSystem 在 ServerStarting 绑定 context 之后调 (DEGRADED 与 LIVE 都装: 守卫只依赖库,
 *       不依赖 Flan); 此后该 context 的索引每重建一次就发布一个新的 view。功能 OFF 时从不装。</li>
 *   <li>{@link #reset}: ServerStopping 时回到 OFF。</li>
 *   <li>GameTest 里默认不装 (DistrictTestEnv 的 context 有自己的索引, 但不 install); 需要守卫的用例走
 *       {@link GuardTestZones}。</li>
 * </ul>
 *
 * <p>四种判定 (22.2), a 是动作的来源、b 是落到的格子: 严格 {@code zone(a) != zone(b)} (活塞); 单向
 * {@code zone(b) != 0 && zone(b) != zone(a)} (发射器、下落的方块、海绵、岩浆点火); 区内边界 (a、b 在同一个在用自管区里且
 * 区域号不同, 流体); 受保护 {@code zone(b) != 0} (机械动力的机器, 见 CreateGuards)。
 *
 * <p>任何异常一律放行 (fail open), 每小时至多记一次 ERROR: 守卫出 bug 不能让世界 tick 崩掉。
 */
public final class DistrictWorldGuards {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/district");
    private static final LogThrottle THROTTLE = new LogThrottle(System::currentTimeMillis);

    private static volatile GuardView view = GuardView.OFF;
    @Nullable
    private static volatile DistrictZoneIndex installedIndex;

    private DistrictWorldGuards() {
    }

    /** 当前 view (热路径一次 volatile 读)。 */
    public static GuardView view() {
        return view;
    }

    /** 开服装上 (22.3): 该 context 的设置与索引; 索引此后每重建一次就发布新的 view。 */
    public static void install(DistrictContext ctx) {
        DistrictZoneIndex index = ctx.zones();
        GuardView base = new GuardView(ctx.guards(), index.current(), CreateBlockPolicy.of(ctx.guards()), true);
        installedIndex = index;
        view = base;
        index.onRebuilt(() -> {
            if (installedIndex == index) {
                view = view.withZones(index.current());
            }
        });
    }

    /** 停服复位: 回到 OFF。 */
    public static void reset() {
        installedIndex = null;
        view = GuardView.OFF;
    }

    /** 装着的索引 (开服 install 的那个); 没装为 null。 */
    @Nullable
    public static DistrictZoneIndex installedIndex() {
        return installedIndex;
    }

    /** 只给 {@link GuardTestZones}: 直接发布一个 view (门由调用方把)。 */
    static void publish(GuardView next) {
        installedIndex = null;
        view = next;
    }

    // ================================================================
    // 地块边界 (22.9)
    // ================================================================

    /**
     * 活塞 (PistonStructureResolver.resolve 的 RETURN, 严格口径): Z = zone(活塞)。伸出时活塞头要进的格子、要推的每一格的
     * 起点与终点、要挤碎的每一格, 都要在 Z 里。缩回被拒时把要拉的方块与各自的终点重发给客户端 (纠正没有守卫的客户端自己
     * 拉过来的幽灵方块)。
     */
    public static boolean pistonMayMove(Level level, BlockPos pistonPos, Direction pistonDirection, boolean extending,
                                        Direction pushDirection, List<BlockPos> toPush, List<BlockPos> toDestroy) {
        try {
            GuardView current = view;
            if (level.isClientSide || !current.crossPlotActive()) {
                return true;
            }
            DistrictZoneSnapshot zones = current.zones();
            int home = zones.zoneAt(level, pistonPos.getX(), pistonPos.getZ());
            boolean allowed = true;
            if (extending) {
                BlockPos head = pistonPos.relative(pistonDirection);
                allowed = zones.zoneAt(level, head.getX(), head.getZ()) == home;
            }
            for (int i = 0; allowed && i < toPush.size(); i++) {
                BlockPos from = toPush.get(i);
                BlockPos to = from.relative(pushDirection);
                allowed = zones.zoneAt(level, from.getX(), from.getZ()) == home
                        && zones.zoneAt(level, to.getX(), to.getZ()) == home;
            }
            for (int i = 0; allowed && i < toDestroy.size(); i++) {
                BlockPos crushed = toDestroy.get(i);
                allowed = zones.zoneAt(level, crushed.getX(), crushed.getZ()) == home;
            }
            if (!allowed && !extending) {
                resendToClients(level, toPush, pushDirection);
            }
            return allowed;
        } catch (RuntimeException failure) {
            failOpen("piston", failure);
            return true;
        }
    }

    private static void resendToClients(Level level, List<BlockPos> toPush, Direction pushDirection) {
        for (BlockPos from : toPush) {
            BlockPos to = from.relative(pushDirection);
            BlockState fromState = level.getBlockState(from);
            BlockState toState = level.getBlockState(to);
            level.sendBlockUpdated(from, fromState, fromState, Block.UPDATE_CLIENTS);
            level.sendBlockUpdated(to, toState, toState, Block.UPDATE_CLIENTS);
        }
    }

    /**
     * 流体横向扩散 (FlowingFluid.canSpreadTo 的 HEAD, 区内边界口径): 只拦同一个在用自管区里的地块边界; 区的外沿交给
     * Flan 的"水和岩浆越界"开关。竖直方向一律放行 (区是全高方柱)。
     */
    public static boolean fluidMayFlow(Level level, BlockPos from, Direction direction, BlockPos to) {
        try {
            if (direction.getAxis().isVertical() || level.isClientSide) {
                return true;
            }
            GuardView current = view;
            if (!current.crossPlotActive()) {
                return true;
            }
            DistrictZoneSnapshot zones = current.zones();
            int source = zones.zoneAt(level, from.getX(), from.getZ());
            int target = zones.zoneAt(level, to.getX(), to.getZ());
            return !sameDistrictDifferentZone(source, target);
        } catch (RuntimeException failure) {
            failOpen("fluid", failure);
            return true;
        }
    }

    /** 发射器与投掷器 (dispenseFrom 的 HEAD, 单向口径): 前方那一格属于别的区域就不发射。 */
    public static boolean mayDispense(Level level, BlockPos pos, Direction facing) {
        try {
            GuardView current = view;
            if (level.isClientSide || !current.crossPlotActive()) {
                return true;
            }
            BlockPos front = pos.relative(facing);
            return !oneWayBlocked(current.zones(), level, pos.getX(), pos.getZ(), front.getX(), front.getZ());
        } catch (RuntimeException failure) {
            failOpen("dispenser", failure);
            return true;
        }
    }

    /** 下落的方块的起点随实体存盘的键 (int[]{x, z}, FallingBlockEntityMixin 写、读)。 */
    public static final String FALLING_START_KEY = "MiningdimDistrictFallStart";

    /**
     * 下落的方块 (FallingBlockEntity.tick 里 move 之后、落地之前, 单向口径): 从第一次被看到时所在的列横移进别的区域就不许。
     */
    public static boolean fallingBlockMayStay(Level level, int startX, int startZ, int x, int z) {
        try {
            GuardView current = view;
            if (level.isClientSide || !current.crossPlotActive() || (startX == x && startZ == z)) {
                return true;
            }
            return !oneWayBlocked(current.zones(), level, startX, startZ, x, z);
        } catch (RuntimeException failure) {
            failOpen("falling block", failure);
            return true;
        }
    }

    /** 海绵这一次吸水所在的世界 (@ModifyArg 拿不到外层方法的参数, 由 HEAD 放进来、RETURN 清掉)。 */
    private static final ThreadLocal<Level> SPONGE_LEVEL = new ThreadLocal<>();

    /** SpongeBlock.removeWaterBreadthFirstSearch 的 HEAD。 */
    public static void spongeSearchStarted(Level level) {
        SPONGE_LEVEL.set(level);
    }

    /** SpongeBlock.removeWaterBreadthFirstSearch 的 RETURN。 */
    public static void spongeSearchEnded() {
        SPONGE_LEVEL.remove();
    }

    /** 海绵 BFS 的过滤器 (世界取 {@link #spongeSearchStarted} 放进来的那个)。 */
    public static Predicate<BlockPos> spongeFilter(BlockPos sponge, Predicate<BlockPos> original) {
        return spongeFilter(SPONGE_LEVEL.get(), sponge, original);
    }

    /**
     * 海绵 (removeWaterBreadthFirstSearch 的 BFS 过滤器, 单向口径): 周围 7 格内没有在用自管区时原样返回; 否则包一层,
     * 别的区域的格子一律不吸。
     */
    public static Predicate<BlockPos> spongeFilter(@Nullable Level level, BlockPos sponge,
                                                   Predicate<BlockPos> original) {
        try {
            GuardView current = view;
            if (level == null || level.isClientSide || !current.crossPlotActive()) {
                return original;
            }
            DistrictZoneSnapshot zones = current.zones();
            int x = sponge.getX();
            int z = sponge.getZ();
            if (!zones.touchesDistrict(level, x - 7, z - 7, x + 7, z + 7)) {
                return original;
            }
            int home = zones.zoneAt(level, x, z);
            return pos -> {
                int target = zones.zoneAt(level, pos.getX(), pos.getZ());
                return (target == 0 || target == home) && original.test(pos);
            };
        } catch (RuntimeException failure) {
            failOpen("sponge", failure);
            return original;
        }
    }

    /** 岩浆点火 (FluidPlaceBlockEvent, 单向口径 liquidPos → pos)。 */
    public static boolean lavaMayIgnite(Level level, BlockPos liquidPos, BlockPos pos) {
        try {
            GuardView current = view;
            if (level.isClientSide || !current.crossPlotActive() || liquidPos.equals(pos)) {
                return true;
            }
            return !oneWayBlocked(current.zones(), level, liquidPos.getX(), liquidPos.getZ(), pos.getX(), pos.getZ());
        } catch (RuntimeException failure) {
            failOpen("lava ignition", failure);
            return true;
        }
    }

    // ================================================================
    // 口径
    // ================================================================

    /** 单向: 落点属于某个在用自管区, 且与来源的区域不同。 */
    public static boolean oneWayBlocked(DistrictZoneSnapshot zones, Level level, int fromX, int fromZ, int toX,
                                        int toZ) {
        int target = zones.zoneAt(level, toX, toZ);
        return target != 0 && target != zones.zoneAt(level, fromX, fromZ);
    }

    /** 区内边界: 两个区域号都在同一个在用自管区里, 且不同。 */
    public static boolean sameDistrictDifferentZone(int a, int b) {
        return a != b && a != 0 && b != 0 && (a >>> 16) == (b >>> 16);
    }

    /** 守卫出错时放行, 每个地方每小时至多记一次 ERROR。 */
    public static void failOpen(String where, Throwable failure) {
        if (THROTTLE.loud("district-guard-" + where)) {
            LOGGER.error("[miningdim] district guard ({}) failed; letting the action through", where, failure);
        }
    }
}

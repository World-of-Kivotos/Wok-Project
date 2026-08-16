package com.miningdim.trap;

import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.Subsystem;
import com.miningdim.core.VoxelOccupancy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 陷阱子系统入口 (模块化铁律 3)。
 *
 * 职责:
 *  - 离线静态陷阱布点: 经 {@link #staticPlacementFor(InstanceState, VoxelOccupancy, BlockPos)} 计算并缓存
 *    (8.5/9.5 与矿物同阶段, 由 GenerationScheduler 在工作线程调用; MiningChunkGenerator 区块填充读表落陷阱方块)。
 *  - 动态陷阱运行期驱动: 订阅 LevelTickEvent(END, 仅矿山维度), 按 danger 评估周期遍历活跃实例触发动态陷阱 (9.6/9.8)。
 *  - 反应窗口延迟队列: 动态陷阱的"预警 -> reactionWindow -> 落地"用 server game time 调度延迟任务, 在 tick 主线程执行。
 *
 * 跨子系统: danger 由压力子系统经 {@link #setDangerSource} 注入 (DangerSource, 推依赖); 实例查询经 core
 * MiningServices.instanceManager()。本系统不 import 矿物/压力等其他子系统实现类 (铁律 2)。
 *
 * 对外入口 (阶段2 接线点):
 *  - {@link #get()} 取单例;
 *  - {@link #staticPlacement(long)} 供 MiningChunkGenerator 查已缓存静态陷阱表落方块;
 *  - {@link #staticPlacementFor} 供 GenerationScheduler 离线预热;
 *  - {@link #setDangerSource} 供压力子系统注入 danger 读取。
 */
public final class TrapSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/TrapSystem");

    private static volatile TrapSystem instance;

    /** 实例静态陷阱表缓存: instanceId -> (seed, placement)。同 OreSystem 的缓存语义。 */
    private final ConcurrentMap<Long, CachedStatic> staticCache = new ConcurrentHashMap<>();

    /** 动态陷阱引擎 (运行期决策 + 世界写经 server.execute)。 */
    private final DynamicTrapEngine dynamicEngine = new DynamicTrapEngine();

    /** 反应窗口延迟任务队列: 到 dueTick 时在主线程执行 (动态陷阱预警后落地)。瞬态, 不持久化。 */
    private final List<DelayedTask> delayedTasks = new ArrayList<>();

    /** 上次 danger 评估的 server game time, 用于按 dangerEvalIntervalTicks 降频 (DG-4)。 */
    private long lastEvalTick = Long.MIN_VALUE;

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        instance = this;
        // 动态陷阱挂 forge 总线 tick 事件 (9.8 挂载点)。
        forgeBus.register(this);
        // 滑动重置后按 instanceId 缓存的旧静态陷阱表 (旧几何/旧种子) 与动态引擎运行期状态必须失效重算 (D3)。
        MiningServices.registerInstanceResetListener(this::invalidate);
        LOGGER.info("[miningdim] TrapSystem registered (static placement + dynamic engine on LevelTickEvent)");
    }

    @Override
    public String name() {
        return "TrapSystem";
    }

    /** 取单例; 未注册抛 IllegalStateException (C9)。 */
    public static TrapSystem get() {
        TrapSystem ref = instance;
        if (ref == null) {
            throw new IllegalStateException("TrapSystem not registered yet (check subsystem register order)");
        }
        return ref;
    }

    /** 压力子系统注入 danger 读取能力 (DangerSource)。 */
    public void setDangerSource(DangerSource source) {
        dynamicEngine.setDangerSource(source);
    }

    /**
     * 读当前注入的 danger 源对某玩家的取值 (动态陷阱门控所读的同一字段)。
     * 供压力/陷阱接线回归测试断言注入生效 (注入前恒 0f stub, 注入后反映真实 danger)。
     */
    public float injectedDangerOf(net.minecraft.server.level.ServerPlayer player, long instanceId) {
        return dynamicEngine.injectedDangerOf(player, instanceId);
    }

    // ---- 静态陷阱表 (离线) ----

    /**
     * 取/算实例静态陷阱表 (9.5)。缓存命中 (同 instanceId 同 seed) 复用; 否则在调用线程计算并缓存。
     * 由 GenerationScheduler 在工作线程首次调用预热。
     *
     * @param state       实例
     * @param voxels      体素视图
     * @param spawnAnchor 出生锚点世界坐标 (9.5 步骤1 距出生点过滤基准)
     */
    public StaticTrapPlacement staticPlacementFor(InstanceState state, VoxelOccupancy voxels, BlockPos spawnAnchor) {
        CachedStatic cached = staticCache.get(state.instanceId());
        if (cached != null && cached.seed == state.seed()) {
            return cached.placement;
        }
        StaticTrapPlacement placement = StaticTrapGenerator.generate(
                state.seed(), state.difficulty(), state.regionBox(), voxels, spawnAnchor);
        staticCache.put(state.instanceId(), new CachedStatic(state.seed(), placement));
        return placement;
    }

    /**
     * 已缓存静态陷阱表 (MiningChunkGenerator 热路径读表; 运行期动态陷阱查"非陷阱区")。
     * 未缓存时返回空表 (而非 null): 区块填充/刷怪校验拿到空表即"无静态陷阱", 行为安全且不 NPE。
     */
    public StaticTrapPlacement staticPlacement(long instanceId) {
        CachedStatic cached = staticCache.get(instanceId);
        if (cached != null) {
            return cached.placement;
        }
        InstanceState state = MiningServices.instanceManager().byId(instanceId).orElse(null);
        if (state == null) {
            // 实例不存在: 给一个退化空表 (regionBox 取一个 0 体积盒占位)。
            return EMPTY_PLACEMENT;
        }
        return new StaticTrapPlacement(state.regionBox(), java.util.Map.of(), java.util.List.of());
    }

    /** 实例释放/重置时清缓存与运行期状态。供 InstanceManager/ResetService 经接口或阶段2 接线调用。 */
    public void invalidate(long instanceId) {
        staticCache.remove(instanceId);
        dynamicEngine.onInstanceReleased(instanceId);
    }

    // ---- 反应窗口延迟任务 ----

    /** 调度一个在 dueTick (server game time) 时于主线程执行的任务 (动态陷阱预警后落地)。 */
    void scheduleDelayed(long dueTick, Runnable task) {
        synchronized (delayedTasks) {
            delayedTasks.add(new DelayedTask(dueTick, task));
        }
    }

    // ---- 运行期 tick (9.8) ----

    /**
     * 矿山维度 LevelTickEvent(END): 1) 跑到期的延迟任务; 2) 按评估周期遍历活跃实例触发动态陷阱。
     * 仅在 SERVER 端、矿山维度执行 (9.8 不污染主世界 tick)。
     */
    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.side != LogicalSide.SERVER || event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!(event.level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!serverLevel.dimension().equals(MiningConstants.MINING_LEVEL)) {
            return; // 仅矿山维度
        }
        MinecraftServer server = serverLevel.getServer();
        long gameTime = serverLevel.getGameTime();

        runDueDelayedTasks(gameTime);

        int evalInterval = MiningServices.config().dangerEvalIntervalTicks();
        if (gameTime - lastEvalTick < evalInterval) {
            return; // DG-4 降频
        }
        lastEvalTick = gameTime;

        // 9.8 仅遍历有在线玩家的活跃实例; 空实例跳过。
        MiningServices.instanceManager().forEach(state -> {
            if (!state.active() || state.refCount() == 0) {
                return;
            }
            dynamicEngine.evaluateInstance(serverLevel, state, server, gameTime);
        });
    }

    private void runDueDelayedTasks(long gameTime) {
        List<Runnable> due = new ArrayList<>();
        synchronized (delayedTasks) {
            delayedTasks.removeIf(t -> {
                if (t.dueTick <= gameTime) {
                    due.add(t.task);
                    return true;
                }
                return false;
            });
        }
        // 任务在主线程 (本事件即主线程) 直接执行世界写。异常自然冒泡到 Forge 事件分发外层 (C9), 不在此生吞。
        for (Runnable r : due) {
            r.run();
        }
    }

    // ---- 内部类型 ----

    private record CachedStatic(long seed, StaticTrapPlacement placement) {
    }

    private record DelayedTask(long dueTick, Runnable task) {
    }

    /** 实例不存在时的退化空表占位 (0 体积 region: containsWorld 恒 false, 任何查询都返回"无陷阱")。 */
    private static final StaticTrapPlacement EMPTY_PLACEMENT = new StaticTrapPlacement(
            new com.miningdim.core.RegionBox(0, 0, 0, 0, 0, 0), java.util.Map.of(), java.util.List.of());
}

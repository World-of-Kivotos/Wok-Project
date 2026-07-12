package com.miningdim.trap;

import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.Subsystem;
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

/**
 * 陷阱子系统入口 (模块化铁律 3)。
 *
 * 职责:
 *  - 静态陷阱触发: 注册 {@link StaticTrapTrigger} (方案 C, vanilla-noise datapack minecraft:ore feature 布点的
 *    {@link com.miningdim.trap.block.TrapOreBlock}; 挖到即按 KIND 分发爆炸/岩浆/落石)。取代已废弃的离线体素布点
 *    (旧 StaticTrapGenerator/staticPlacementFor 随维度迁 minecraft:noise 已判死, 同 OreSystem)。
 *  - 动态陷阱运行期驱动: 订阅 LevelTickEvent(END, 仅矿山维度), 按 danger 评估周期遍历活跃实例触发动态陷阱 (9.6/9.8)。
 *  - 反应窗口延迟队列: 静态/动态陷阱的"预警 -> reactionWindow -> 落地"用 server game time 调度延迟任务, 在 tick 主线程执行。
 *
 * 跨子系统: danger 由压力子系统经 {@link #setDangerSource} 注入 (DangerSource, 推依赖); 实例查询经 core
 * MiningServices.instanceManager()。本系统不 import 矿物/压力等其他子系统实现类 (铁律 2)。
 *
 * 对外入口:
 *  - {@link #get()} 取单例;
 *  - {@link #staticPlacement(long)} 保留供 {@link DynamicTrapEngine} 致死区避让谓词 (方案 C 后恒返空表, 见方法注释);
 *  - {@link #scheduleDelayed} 反应窗口延迟落地;
 *  - {@link #setDangerSource} 供压力子系统注入 danger 读取。
 */
public final class TrapSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/TrapSystem");

    private static volatile TrapSystem instance;

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
        // 静态陷阱触发器 (协议级伪装): 挖到坐标命中 TrapRegistry 时按 KIND 分发效果, 反应窗口经本系统延迟队列落地。
        forgeBus.register(new StaticTrapTrigger());
        // 静态陷阱伪装落地器: 矿洞区块加载时把 datapack 布下的 trap_ore 就地换成真原版矿石, 陷阱身份只存 TrapRegistry
        // (世界里不再有可被 F3/Jade/矿透识破的方块)。
        forgeBus.register(new TrapDisguiseConverter());
        LOGGER.info("[miningdim] TrapSystem registered (static trap trigger + disguise converter + dynamic engine on LevelTickEvent)");
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

    // ---- 静态陷阱致死区谓词 (方案 C 后恒空; 仅供动态陷阱避让) ----

    /**
     * 静态陷阱致死区查询表。方案 C 迁移后静态陷阱已是真实世界方块 ({@link com.miningdim.trap.block.TrapOreBlock}),
     * 不再有离线体素表; 本方法恒返回空表, 仅为兼容 {@link DynamicTrapEngine} 的"身后刷怪避开致死陷阱区"谓词
     * ({@code StaticTrapPlacement.inLethalTrapRadius}) 而保留 —— 空表即"无已知致死区", 刷怪不额外避让 (与迁移前
     * 该表恒空的既有行为一致, 不引入行为变化)。若未来要让动态刷怪避开真实陷阱块, 应改为扫世界而非复活离线表。
     */
    public StaticTrapPlacement staticPlacement(long instanceId) {
        return EMPTY_PLACEMENT;
    }

    /** 实例释放/重置时清运行期状态 (静态陷阱已无缓存表可清; 动态引擎节流状态照清)。 */
    public void invalidate(long instanceId) {
        dynamicEngine.onInstanceReleased(instanceId);
    }

    // ---- 反应窗口延迟任务 ----

    /** 调度一个在 dueTick (server game time) 时于主线程执行的任务 (动态陷阱预警后落地)。 */
    void scheduleDelayed(long dueTick, Runnable task) {
        synchronized (delayedTasks) {
            delayedTasks.add(new DelayedTask(dueTick, task));
        }
    }

    /** 当前挂起的反应窗口延迟任务数 (GameTest 断言陷阱触发已入调度队列用; 非致死过滤/幽灵条目不入队则计数不变)。 */
    int pendingDelayedTaskCount() {
        synchronized (delayedTasks) {
            return delayedTasks.size();
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

    private record DelayedTask(long dueTick, Runnable task) {
    }

    /** 静态陷阱致死区谓词的退化空表 (0 体积 region: containsWorld 恒 false, 任何查询都返回"无致死陷阱区")。 */
    private static final StaticTrapPlacement EMPTY_PLACEMENT = new StaticTrapPlacement(
            new com.miningdim.core.RegionBox(0, 0, 0, 0, 0, 0), java.util.Map.of(), java.util.List.of());
}

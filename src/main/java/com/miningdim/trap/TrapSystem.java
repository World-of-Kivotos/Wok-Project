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
 *  - 动态陷阱运行期驱动: 订阅 LevelTickEvent(END, 仅矿山维度), 按 danger 评估周期遍历活跃实例触发动态陷阱 (9.6/9.8)。
 *  - 反应窗口延迟队列: 动态陷阱的"预警 -> reactionWindow -> 落地"用 server game time 调度延迟任务, 在 tick 主线程执行。
 *
 * 离线静态陷阱布点这条链已删除 (F033 修复): 原离线布点计算的形参吃体素占用视图, 但维度已改用 minecraft:noise
 * 生成 (F032), 全库已无任何该视图的构造方, 该链本就是零调用方的死代码。陷阱探测现改为直接读真实世界地形危害
 * (见 {@link WorldHazards} / job.miner.TrapScanService), 不再依赖离线布点表。
 *
 * 跨子系统: danger 由压力子系统经 {@link #setDangerSource} 注入 (DangerSource, 推依赖); 实例查询经 core
 * MiningServices.instanceManager()。本系统不 import 矿物/压力等其他子系统实现类 (铁律 2)。
 *
 * 对外入口 (阶段2 接线点):
 *  - {@link #get()} 取单例;
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
        // 滑动重置后动态引擎运行期状态 (节流时间戳) 必须失效重算 (D3)。
        MiningServices.registerInstanceResetListener(this::invalidate);
        LOGGER.info("[miningdim] TrapSystem registered (dynamic engine on LevelTickEvent)");
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

    /** 实例释放/重置时清运行期状态。供 InstanceManager/ResetService 经接口或阶段2 接线调用。 */
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
}

package com.miningdim.instance;

import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.Subsystem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 实例子系统入口 (模块化铁律 3)。本子系统是实例生命周期的"后端": InstanceManager (region 分配 / 引用计数 /
 * 排队背压 / 空实例 GC) + 持久层 SavedData。维度走 minecraft:noise 按需生成 (F021/F032: 离线体素管线
 * 已下线), 不再有独立的生成调度/区块 force-load 环节。InstanceManager 在 ServerStartedEvent 才构建
 * (依赖 server + 矿山 ServerLevel + config 服务), 构建后注入 MiningServices 并从 SavedData 重建 + 孤儿清理 (12.8)。
 *
 * 玩家 Capability 与"进入/离开/登录恢复"路径的归属 (阶段2 集成裁决, 见 MiningDim 类注释):
 *   这些职责由 entry 子系统 (EntrySystem + MiningCapabilities) 统一拥有, 它实现了设计文档 14.2 完整防虚空
 *   进入链路与 14.6 登录恢复, 离开路径 (登出/换维度/重生) 也在 EntrySystem 内统一汇聚到 onPlayerLeave。
 *   故本子系统不再注册玩家 Capability、不订阅玩家生命周期离开事件 —— 避免与 entry 重复 attach 能力、
 *   重复触发 onPlayerLeave (双重引用计数/双重传送)。本子系统只持有实例后端与服务端生命周期驱动。
 *
 * 注入顺序: ConfigSystem 须排在本子系统之前 (InstanceManager 构建时读 config)。
 */
public final class InstanceSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/InstanceSystem");

    private InstanceManager manager;

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        // 仅订阅本子系统后端的生命周期事件 (启动重建 / tick 驱动 / 停服)。
        forgeBus.register(this);
    }

    @Override
    public String name() {
        return "instance";
    }

    /** 服务端启动后构建并重建 InstanceManager, 注入 MiningServices (12.8)。 */
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        ServerLevel miningLevel = server.getLevel(MiningConstants.MINING_LEVEL);
        if (miningLevel == null) {
            // 矿山维度未加载 (数据包缺失/未注册): 不静默掩盖, 记 Major 日志, 本子系统降级为不可用。
            LOGGER.error("[miningdim] mining dimension {} not present at server start; instance subsystem disabled",
                    MiningConstants.MINING_LEVEL.location());
            return;
        }
        manager = new InstanceManager(server, miningLevel);
        MiningServices.registerInstanceManager(manager);
        manager.rebuildFromStorage();

        LOGGER.info("[miningdim] instance subsystem online");
    }

    /** 维度 tick 末驱动: 周期 GC 扫描 (12.6, 仅矿山维度 END 阶段)。 */
    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (manager == null || event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!(event.level instanceof ServerLevel level) || !level.dimension().equals(MiningConstants.MINING_LEVEL)) {
            return;
        }
        // GC 扫描按 gcScanIntervalTicks 周期触发, 而非每 tick (12.6)。
        int scanInterval = Math.max(1, MiningServices.config().gcScanIntervalTicks());
        if (level.getServer().getTickCount() % scanInterval == 0) {
            manager.gcScan();
        }
    }

    /** 停服第一阶段: 落盘。共享门面在此阶段暂不清空, 见 {@link #onServerStopped} 的理由。 */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (manager != null) {
            manager.shutdown();
        }
    }

    /**
     * 停服第二阶段: 清本子系统引用与随存档生命周期的门面 (避免跨存档脏引用, F091)。
     *
     * 必须挂 {@link ServerStoppedEvent} 而非 ServerStoppingEvent (MiningStoreSubsystem 同款用法, 见其类
     * 注释): ChunkSystem.onServerStopping 会在同一次关服流程里调 MiningServices.instanceManager() 释放
     * 强加载票 (R5); Forge 按子系统注册顺序广播 ServerStoppingEvent, 本子系统注册早于 ChunkSystem, 若在
     * ServerStoppingEvent 阶段就清空共享门面, ChunkSystem 的监听器执行时会撞上
     * "IInstanceManager not registered" 崩服 (F091 曾在此踩坑, 实测复现)。ServerStoppedEvent 严格晚于
     * 全部 ServerStoppingEvent 监听器, 保证没有任何子系统还会用这份共享门面。
     */
    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        if (manager != null) {
            MiningServices.clearServerScoped();
            manager = null;
        }
    }
}

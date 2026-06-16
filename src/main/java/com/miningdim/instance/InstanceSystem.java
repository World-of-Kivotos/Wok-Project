package com.miningdim.instance;

import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.Subsystem;
import com.miningdim.worldgen.MiningVoxelLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 实例子系统入口 (模块化铁律 3)。本子系统是实例生命周期的"后端": InstanceManager (region 分配 / 引用计数 /
 * 排队背压 / 空实例 GC) + 持久层 SavedData + 离线生成调度 + 区块 force-load。InstanceManager 在
 * ServerStartedEvent 才构建 (依赖 server + 矿山 ServerLevel + config 服务), 构建后注入 MiningServices
 * 并从 SavedData 重建 + 孤儿清理 (12.8)。
 *
 * 玩家 Capability 与"进入/离开/登录恢复"路径的归属 (阶段2 集成裁决, 见 MiningDim 类注释):
 *   这些职责由 entry 子系统 (EntrySystem + MiningCapabilities) 统一拥有, 它实现了设计文档 14.2 完整防虚空
 *   进入链路与 14.6 登录恢复, 离开路径 (登出/换维度/重生) 也在 EntrySystem 内统一汇聚到 onPlayerLeave。
 *   故本子系统不再注册玩家 Capability、不订阅玩家生命周期离开事件 —— 避免与 entry 重复 attach 能力、
 *   重复触发 onPlayerLeave (双重引用计数/双重传送)。本子系统只持有实例后端与服务端生命周期驱动。
 *
 * 注入顺序: ConfigSystem 须排在本子系统之前 (InstanceManager 构建时读 config); WorldgenSystem 须排在前
 * (本子系统在 ServerStartedEvent 把离线调度器的 voxelsOf 接进 worldgen 的 MiningVoxelLookup seam)。
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

    /** 服务端启动后构建并重建 InstanceManager, 注入 MiningServices, 接通体素查表 seam (12.8 / 7.7.1)。 */
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

        // worldgen 的 MiningChunkGenerator 经 MiningVoxelLookup.resolve(id) 查冻结体素落方块; 此处把离线
        // 调度器的 voxelsOf 接进该 seam (单向依赖 worldgen seam, 无环, 满足铁律 2)。provider 设置前
        // resolve 返回 null, ChunkGenerator 安全降级填实心墙, 不崩服。
        InstanceManager bound = manager;
        MiningVoxelLookup.setProvider(bound.scheduler()::voxelsOf);

        LOGGER.info("[miningdim] instance subsystem online");
    }

    /** 维度 tick 末驱动: 分帧区块加载 + 周期 GC 扫描 (12.6/12.7, 仅矿山维度 END 阶段)。 */
    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event) {
        if (manager == null || event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!(event.level instanceof ServerLevel level) || !level.dimension().equals(MiningConstants.MINING_LEVEL)) {
            return;
        }
        manager.tickGeneration();
        // GC 扫描按 gcScanIntervalTicks 周期触发, 而非每 tick (12.6)。
        int scanInterval = Math.max(1, MiningServices.config().gcScanIntervalTicks());
        if (level.getServer().getTickCount() % scanInterval == 0) {
            manager.gcScan();
        }
    }

    /** 停服: 关闭生成线程池, 落盘, 清本子系统引用 (避免跨存档脏引用)。 */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (manager != null) {
            manager.shutdown();
            manager = null;
        }
    }
}

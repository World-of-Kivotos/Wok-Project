package com.miningdim.worldgen;

import com.miningdim.core.MiningServices;
import com.miningdim.core.Subsystem;
import com.miningdim.registry.ModRegistration;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * worldgen 子系统入口 (设计文档 5.3 / 第七章; 模块化铁律 3)。register 内完成:
 *   1. 把 OfflineCaveGenerator 注入 MiningServices (core.IOfflineGenerator), 供 instance 子系统调度生成。
 *   2. 订阅 RegisterEvent (modBus), 把 MiningChunkGenerator.CODEC / MiningBiomeSource.CODEC 注册到
 *      Registries.CHUNK_GENERATOR / BIOME_SOURCE (1.20.1 这两个原版 codec 注册表用 RegisterEvent 直注,
 *      不能 DeferredRegister 覆盖, 5.3)。注册 ID 取 MiningConstants 锁定值, 与 dimension/mining.json 的
 *      generator.type / biome_source.type 字面一致, 否则 datapack 反序列化失败。
 *   3. 订阅 ServerStoppingEvent (forgeBus), 关闭离线生成线程池, 释放工作线程。
 *
 * 注入顺序: WorldgenSystem 应早于 InstanceSystem (instance 调度器在分配时取 offlineGenerator)。
 * OfflineCaveGenerator 的 config 读取已懒化 (见其注释), 故 ConfigSystem 不必早于本子系统。
 *
 * 体素查表 seam (MiningVoxelLookup) 的 provider 由阶段2 集成层接入 (把 instance 的 voxelsOf 接进来),
 * 不在本 register 内强行去 import instance 实现 —— 那会违反铁律 2。
 */
public final class WorldgenSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/WorldgenSystem");

    private final OfflineCaveGenerator generator = new OfflineCaveGenerator();

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        // 1. 注入离线生成器门面。
        MiningServices.registerOfflineGenerator(generator);

        // 2. RegisterEvent: 注册两个 CODEC (mod 事件总线)。注册逻辑集中在 registry.ModRegistration
        //    作为单一真源 (见其类注释), 本子系统仅订阅并委派, 避免在两处重复 helper.register 同一 ID。
        modBus.addListener(ModRegistration::onRegister);

        // 3. 服务端停止: 关线程池 (forge 事件总线)。
        forgeBus.addListener(this::onServerStopping);

        LOGGER.info("[miningdim] WorldgenSystem registered (offline generator injected)");
    }

    private void onServerStopping(ServerStoppingEvent event) {
        generator.shutdown();
    }
}

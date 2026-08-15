package com.miningdim.worldgen;

import com.miningdim.core.Subsystem;
import com.miningdim.registry.ModRegistration;
import net.minecraftforge.eventbus.api.IEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * worldgen 子系统入口 (设计文档 5.3 / 第七章; 模块化铁律 3)。register 内完成:
 *   订阅 RegisterEvent (modBus), 把 MiningBiomeSource.CODEC 注册到 Registries.BIOME_SOURCE
 *   (1.20.1 该原版 codec 注册表用 RegisterEvent 直注, 不能 DeferredRegister 覆盖, 5.3)。注册 ID 取
 *   MiningConstants.BIOME_SOURCE_ID, 与 dimension/mining.json 的 biome_source.type 字面一致, 否则
 *   datapack 反序列化失败。
 *
 * 自定义 ChunkGenerator 与离线体素生成管线 (OfflineCaveGenerator/GenerationScheduler) 已下线 (F021/F032):
 * dimension/mining.json 的 generator.type 现为 minecraft:noise, 按需生成, 本子系统不再持有生成器实例、
 * 不再注入 core.IOfflineGenerator、不再需要在停服时关闭生成线程池。
 */
public final class WorldgenSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/WorldgenSystem");

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        // RegisterEvent: 注册 BiomeSource CODEC (mod 事件总线)。注册逻辑集中在 registry.ModRegistration
        // 作为单一真源 (见其类注释), 本子系统仅订阅并委派, 避免在两处重复 helper.register 同一 ID。
        modBus.addListener(ModRegistration::onRegister);

        LOGGER.info("[miningdim] WorldgenSystem registered");
    }
}

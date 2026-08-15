package com.miningdim.registry;

import com.miningdim.core.MiningConstants;
import com.miningdim.worldgen.MiningBiomeSource;
import net.minecraft.core.registries.Registries;
import net.minecraftforge.registries.RegisterEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 集中处理 RegisterEvent 的直注对象 (设计文档 5.3): 自定义 BiomeSource 的 Codec 注册到
 * Registries.BIOME_SOURCE。该原版 codec 注册表不能用 DeferredRegister 覆盖, 必须用 RegisterEvent
 * 直注 (1.20.1 注册的是 Codec<? extends BiomeSource>, 非 MapCodec —— MapCodec 化是 1.20.5+)。
 *
 * 接线: CODEC 由 worldgen 子系统提供, 注册逻辑集中于本 registry 包作为单一真源:
 *   com.miningdim.worldgen.MiningBiomeSource.CODEC (Codec<MiningBiomeSource>)
 * 注册 ID 取 MiningConstants.BIOME_SOURCE_ID, 与 dimension/mining.json 的 biome_source.type 字面一致
 * (miningdim:mining_biome_source), 否则数据包反序列化维度时找不到 codec 而失败。
 *
 * 自定义 ChunkGenerator 的 codec 已随自定义 ChunkGenerator 一并下线 (F021/F032): dimension/mining.json
 * 的 generator.type 现为 minecraft:noise, 不再需要注册自定义 ChunkGenerator codec。
 *
 * 订阅入口: WorldgenSystem.register 内 modBus.addListener(ModRegistration::onRegister)。
 * 全 mod 仅此一处注册该 codec; 不得在别处重复 helper.register 同一 ID (Forge 对重复键直接抛错)。
 */
public final class ModRegistration {

    private ModRegistration() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/ModRegistration");

    /**
     * RegisterEvent 回调: 在 BIOME_SOURCE 注册表阶段直注 codec。
     * helper.register(ResourceLocation, value) 是 1.20.1 RegisterEvent 的注册签名。
     */
    public static void onRegister(RegisterEvent event) {
        event.register(Registries.BIOME_SOURCE, helper -> {
            helper.register(MiningConstants.BIOME_SOURCE_ID, MiningBiomeSource.CODEC);
            LOGGER.info("[miningdim] registered biome source codec {}", MiningConstants.BIOME_SOURCE_ID);
        });
    }
}

package com.miningdim.registry;

import com.miningdim.core.MiningConstants;
import com.miningdim.worldgen.MiningBiomeSource;
import com.miningdim.worldgen.MiningChunkGenerator;
import net.minecraft.core.registries.Registries;
import net.minecraftforge.registries.RegisterEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 集中处理 RegisterEvent 的直注对象 (设计文档 5.3): 自定义 ChunkGenerator / BiomeSource 的 Codec
 * 注册到 Registries.CHUNK_GENERATOR / BIOME_SOURCE。这两个原版 codec 注册表不能用 DeferredRegister 覆盖,
 * 必须用 RegisterEvent 直注 (1.20.1 注册的是 Codec<? extends ChunkGenerator> / Codec<? extends BiomeSource>,
 * 非 MapCodec —— MapCodec 化是 1.20.5+)。
 *
 * 接线 (阶段2 DECIDED): 两个 CODEC 由 worldgen 子系统提供, 注册逻辑集中于本 registry 包作为单一真源:
 *   com.miningdim.worldgen.MiningChunkGenerator.CODEC  (Codec<MiningChunkGenerator>)
 *   com.miningdim.worldgen.MiningBiomeSource.CODEC     (Codec<MiningBiomeSource>)
 * 注册 ID 取 MiningConstants 锁定值, 与 dimension/mining.json 的 generator.type / biome_source.type 字面一致
 * (经核对: CHUNK_GENERATOR_ID=miningdim:mining_chunk_generator, BIOME_SOURCE_ID=miningdim:mining_biome_source,
 * 与 data/miningdim/dimension/mining.json 完全一致), 否则数据包反序列化维度时找不到 codec 而失败。
 *
 * 订阅入口: WorldgenSystem.register 内 modBus.addListener(ModRegistration::onRegister)。
 * 全 mod 仅此一处注册这两个 codec; 不得在别处重复 helper.register 同一 ID (Forge 对重复键直接抛错)。
 */
public final class ModRegistration {

    private ModRegistration() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/ModRegistration");

    /**
     * RegisterEvent 回调: 在 CHUNK_GENERATOR / BIOME_SOURCE 注册表阶段直注两个 codec。
     * helper.register(ResourceLocation, value) 是 1.20.1 RegisterEvent 的注册签名。
     */
    public static void onRegister(RegisterEvent event) {
        event.register(Registries.CHUNK_GENERATOR, helper -> {
            helper.register(MiningConstants.CHUNK_GENERATOR_ID, MiningChunkGenerator.CODEC);
            LOGGER.info("[miningdim] registered chunk generator codec {}", MiningConstants.CHUNK_GENERATOR_ID);
        });

        event.register(Registries.BIOME_SOURCE, helper -> {
            helper.register(MiningConstants.BIOME_SOURCE_ID, MiningBiomeSource.CODEC);
            LOGGER.info("[miningdim] registered biome source codec {}", MiningConstants.BIOME_SOURCE_ID);
        });
    }
}

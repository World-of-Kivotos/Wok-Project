package com.miningdim.worldgen;

import com.miningdim.core.Difficulty;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.RegionLayout;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.stream.Stream;

/**
 * 自定义 BiomeSource (设计文档 5.3 / 6.4 / 7.8.4; R2 改: 难度=区域)。按所在 region 的难度返回
 * Easy/Medium/Hard 三难度 biome 之一, 整列同一 biome (不再按 worldY 子盒分带) —— 纯查表, 无随机、
 * 不跑跨区块算法 (对齐 D2)。难度经 MiningServices.instanceManager().regionAt(blockX, blockZ) 解析。
 *
 * Codec (1.20.1, 非 MapCodec): 元素类型 Codec<? extends BiomeSource>, 经 RegisterEvent 注册到
 * Registries.BIOME_SOURCE。dimension/mining.json 的 biome_source 段只有 type 字段, 无 biome 列表,
 * 故三个 biome holder 不能走 JSON 字段, 而由 RegistryOps.retrieveGetter(Registries.BIOME) 在反序列化
 * 时从 datapack biome 注册表按 Difficulty.biomeKey() 解析 (data/miningdim/worldgen/biome/mining_*.json)。
 *
 * getNoiseBiome 的入参 x/y/z 是 1/4 区块 (biome 分辨率) 坐标, 需 QuartPos.toBlock 还原方块 Y (6.4)。
 */
public final class MiningBiomeSource extends BiomeSource {

    /**
     * Codec: 无 JSON 字段, 全部 holder 由 BIOME 注册表 getter 内部解析。retrieveGetter 返回一个在
     * decode 期注入 HolderGetter<Biome> 的构建器; 用 group(单字段) + apply 包成无参 JSON 仍可工作的 Codec。
     */
    public static final Codec<MiningBiomeSource> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    RegistryOps.retrieveGetter(Registries.BIOME)
            ).apply(instance, MiningBiomeSource::new));

    private final Holder<Biome> easy;
    private final Holder<Biome> medium;
    private final Holder<Biome> hard;
    private final Holder<Biome> wall;

    public MiningBiomeSource(HolderGetter<Biome> biomes) {
        // getOrThrow: biome 不存在即 datapack 缺失, 自然抛异常崩溃定位 (C9), 不掩盖。
        this.easy = biomes.getOrThrow(Difficulty.EASY.biomeKey());
        this.medium = biomes.getOrThrow(Difficulty.MEDIUM.biomeKey());
        this.hard = biomes.getOrThrow(Difficulty.HARD.biomeKey());
        this.wall = biomes.getOrThrow(MiningConstants.MINING_WALL_BIOME);
    }

    @Override
    protected Codec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        // 7.8.4 / 6.4: possibleBiomes 必须含全部会返回的 biome (三难度 + 基岩墙), 否则原版校验/spawn 预计算 Holder 解析报错。
        return Stream.of(easy, medium, hard, wall);
    }

    /**
     * 按所在 region 难度返回 biome (R2: 难度=区域, 整列同一 biome)。入参 x/z 是 1/4 区块坐标, 经
     * QuartPos.toBlock 还原方块 XZ 再查 region。region 外 (缓冲带 + 网格外) 归 mining_wall 群系 —— 这些列
     * 经 surface_rule 填成纯基岩, 把三个难度盒子封死 (D1)。y 不参与 (整列同 biome)。
     */
    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        return biomeForRegion(QuartPos.toBlock(x), QuartPos.toBlock(z));
    }

    /**
     * region -> 难度 biome, 改查 core.RegionLayout 的当前快照 (D3/13.4)。三块 region 会随重置整体滑动到
     * 新坐标, 若按编译期固定单元几何判归属, 滑动后的新区会被判成网格外, 整块新区就生成成 mining_wall
     * (surface_rule 填纯基岩)。RegionLayout 是 core 的纯静态快照 (不依赖 MiningServices/InstanceManager),
     * 客户端反序列化 Codec 时不会碰任何服务端单例, 原本"不能依赖 InstanceManager"的约束依然满足 (R2)。
     * 三区外 (缓冲带 + 网格外) 归 mining_wall 基岩墙群系: surface_rule 把这些列整列填基岩, 封死难度盒子。
     */
    private Holder<Biome> biomeForRegion(int blockX, int blockZ) {
        Difficulty d = RegionLayout.current().difficultyAt(blockX, blockZ);
        if (d == null) {
            return wall;
        }
        return switch (d) {
            case EASY -> easy;
            case MEDIUM -> medium;
            case HARD -> hard;
        };
    }
}

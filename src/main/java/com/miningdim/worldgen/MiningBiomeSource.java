package com.miningdim.worldgen;

import com.miningdim.core.Difficulty;
import com.miningdim.core.MiningConstants;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.stream.Stream;

/**
 * 自定义 BiomeSource (设计文档 5.3 / 6.4 / 7.8.4; R2 改: 难度=区域; worldgen 翻修 1.0.2 改: 难度盒内成片变体)。
 * 按所在 region 的难度先定档 (Easy/Medium/Hard), 再在档内按坐标哈希把 XZ 平面切成约 {@link #VARIANT_PATCH} 格
 * 见方的片, 每片确定性落到"基础群系 + 该难度洞穴变体"之一。区外 (缓冲带 + 网格外) 归 mining_wall 基岩墙群系。
 *
 * 变体划片选用坐标哈希而非 Climate.Sampler: 本维度 noise_settings 的 noise_router 全部为 0, 采样气候通道恒为
 * 常量 (temperature/erosion/humidity 皆 0), 无法据以分片; 故改用与世界种子无关的整数坐标哈希 ({@link #mix})。
 * 哈希只依赖方块 XZ 与难度档 id, 客户端 (渲染群系着色时也会调 getNoiseBiome) 与服务端算出的分片完全一致,
 * 且不触碰服务端 InstanceManager (否则客户端 NPE)。片粒度 64 格, 与难度几何判定 (Difficulty.forBlock) 正交。
 *
 * Codec (1.20.1, 非 MapCodec): 元素类型 Codec<? extends BiomeSource>, 经 RegisterEvent 注册到
 * Registries.BIOME_SOURCE。dimension/mining.json 的 biome_source 段只有 type 字段, 无 biome 列表,
 * 故所有 biome holder 由 RegistryOps.retrieveGetter(Registries.BIOME) 在反序列化时从 datapack biome 注册表
 * 按键解析 (data/miningdim/worldgen/biome/mining_*.json)。
 *
 * getNoiseBiome 的入参 x/y/z 是 1/4 区块 (biome 分辨率) 坐标, 需 QuartPos.toBlock 还原方块 XZ (6.4)。
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

    /**
     * 变体分片边长 (方块), 落在 60~120 的设计区间内。取 64 而非 96: 区域 256 宽划 4 片/轴 (16 片/盒),
     * 经离线穷举 (对三难度区全部片坐标求 mix 哈希) 验证每个难度池内全部变体至少出现一次;
     * 96 时 hard 区仅 9 片, hard_deepdark 恰好一次不落 (死变体, 违背深暗变体拍板), 故弃用。
     */
    private static final int VARIANT_PATCH = 64;

    /** 每难度盒内 (基础 + 变体) 群系池, base 恒为下标 0 (与 weights[0] 对齐)。构造期从 BIOME 注册表解析。 */
    private final EnumMap<Difficulty, List<Holder<Biome>>> pools = new EnumMap<>(Difficulty.class);

    /** 每难度盒内各变体的分片权重 (与 pools 同序; 下标 0 = 基础群系, 权重大即基础占主体)。 */
    private final EnumMap<Difficulty, int[]> weights = new EnumMap<>(Difficulty.class);

    private final Holder<Biome> wall;

    public MiningBiomeSource(HolderGetter<Biome> biomes) {
        // getOrThrow: biome 不存在即 datapack 缺失, 自然抛异常崩溃定位 (C9), 不掩盖。变体缺注册会在此直接崩,
        // 早于 collectPossibleBiomes 的 holder 校验, 定位更准。
        this.wall = biomes.getOrThrow(MiningConstants.MINING_WALL_BIOME);
        // base(下标0) 权重 3, 每个洞穴变体权重 1 —— 基础群系约占难度盒六成, 变体成片点缀。
        registerPool(biomes, Difficulty.EASY, new int[]{3, 1}, "mining_easy_lush");
        registerPool(biomes, Difficulty.MEDIUM, new int[]{3, 1, 1}, "mining_medium_lush", "mining_medium_dripstone");
        registerPool(biomes, Difficulty.HARD, new int[]{3, 1, 1}, "mining_hard_dripstone", "mining_hard_deepdark");
    }

    /** 解析 (难度基础群系 + 变体路径列表) 为 holder 池并登记权重; weights 长度必须等于 1(base)+变体数。 */
    private void registerPool(HolderGetter<Biome> biomes, Difficulty d, int[] variantWeights, String... variantPaths) {
        // base 与变体数不匹配权重表 = 编码错误, 直接抛 (不静默兜底), 让 665 基线 GameTest 立刻暴露。
        if (variantWeights.length != variantPaths.length + 1) {
            throw new IllegalArgumentException("variant weight count mismatch for " + d.configName());
        }
        List<Holder<Biome>> pool = new ArrayList<>(variantPaths.length + 1);
        pool.add(biomes.getOrThrow(d.biomeKey()));
        for (String path : variantPaths) {
            pool.add(biomes.getOrThrow(ResourceKey.create(Registries.BIOME,
                    new ResourceLocation(MiningConstants.MODID, path))));
        }
        pools.put(d, pool);
        weights.put(d, variantWeights);
    }

    @Override
    protected Codec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        // 7.8.4 / 6.4: possibleBiomes 必须含全部会返回的 biome (三难度基础 + 全部变体 + 基岩墙), 否则原版
        // 校验/spawn 预计算 Holder 解析报错。distinct 防重 (各池互不相交, 仅防御未来误配同名)。
        return Stream.concat(
                pools.values().stream().flatMap(List::stream),
                Stream.of(wall)
        ).distinct();
    }

    /**
     * 先按 region 难度定档, 再在档内按坐标哈希分片选基础/变体群系。入参 x/z 是 1/4 区块坐标, 经 QuartPos.toBlock
     * 还原方块 XZ。region 外 (缓冲带 + 网格外) 归 mining_wall 基岩墙群系。y 不参与 (整列同 biome, D2/R2)。
     */
    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        int blockX = QuartPos.toBlock(x);
        int blockZ = QuartPos.toBlock(z);
        Difficulty d = Difficulty.forBlock(blockX, blockZ);
        if (d == null) {
            return wall; // 缓冲带 + 网格外: 基岩墙群系 (surface_rule 整列填基岩, 封死难度盒子)。
        }
        List<Holder<Biome>> pool = pools.get(d);
        int idx = selectVariant(blockX, blockZ, d, weights.get(d));
        return pool.get(idx);
    }

    /**
     * 纯确定性加权分片选择: 把方块 XZ 量化到 {@link #VARIANT_PATCH} 格片, 对 (片X, 片Z, 难度id) 做整数哈希,
     * 落到 [0, 权重和) 后按累积权重定位变体下标。同片同结果、客户端服务端同结果 (只依赖坐标与档 id, 无种子)。
     */
    private static int selectVariant(int blockX, int blockZ, Difficulty d, int[] weights) {
        int patchX = Math.floorDiv(blockX, VARIANT_PATCH);
        int patchZ = Math.floorDiv(blockZ, VARIANT_PATCH);
        long h = mix(patchX, patchZ, d.id());
        int total = 0;
        for (int w : weights) {
            total += w;
        }
        int r = (int) Math.floorMod(h, (long) total);
        int acc = 0;
        for (int i = 0; i < weights.length - 1; i++) {
            acc += weights[i];
            if (r < acc) {
                return i;
            }
        }
        // r 落在最后一个权重区间 -> 最后一个变体 (真实末桶, 非兜底死代码)。
        return weights.length - 1;
    }

    /** splitmix64 收尾混合器 + 逐坐标乘常量: 与世界种子无关的稳定整数哈希, 保证客户端服务端分片一致。 */
    private static long mix(int patchX, int patchZ, int difficultyId) {
        long h = (patchX & 0xFFFFFFFFL) * 0x9E3779B97F4A7C15L;
        h ^= (patchZ & 0xFFFFFFFFL) * 0xC2B2AE3D27D4EB4FL;
        h ^= (difficultyId + 1L) * 0x165667B19E3779F9L;
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return h;
    }
}

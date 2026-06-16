package com.miningdim.core;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;

/**
 * 确定性种子工具 (设计文档 12.4 / D3)。所有离线生成、矿物分布、陷阱布设的随机性都从这里派生,
 * 杜绝跨区块共享可变 Random 导致的不可复现 (7.1.1 Critical 缺口)。
 *
 * 铁律 (D3): 同一 (globalSeed, instanceId, resetGeneration) 必产出逐位相等的体素;
 * 坐标派生 hash 用 SplitMix64 finalizer, 保证 id 相邻的实例 seed 不相邻、不可预测 (12.4)。
 * 本类纯静态, 不持有任何可变状态。
 */
public final class SeedUtil {

    private SeedUtil() {
    }

    // SplitMix64 混合常量 (12.4 给定)
    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;
    private static final long MIX1 = 0xBF58476D1CE4E5B9L;
    private static final long MIX2 = 0x94D049BB133111EBL;

    /**
     * 由全局种子与实例 id (及重置代数) 派生实例确定性种子 (12.4 deriveSeed 扩展 resetGeneration)。
     * resetGeneration 是该实例被重置的次数: 0 为首次生成, 每次 NEW_SEED 重置 +1, 以刷新随机布局。
     * SAME_SEED 重置不改 resetGeneration (复用原 seed)。
     */
    public static long deriveSeed(long globalSeed, long instanceId, int resetGeneration) {
        long z = globalSeed ^ ((instanceId * GOLDEN_GAMMA) + (long) resetGeneration * GOLDEN_GAMMA);
        z = (z ^ (z >>> 30)) * MIX1;
        z = (z ^ (z >>> 27)) * MIX2;
        return z ^ (z >>> 31);
    }

    /**
     * 坐标 + 特征派生 hash (D3): 给定实例 seed 与 (x, z, featureId) 产出一个稳定 long,
     * 用于"每列/每特征独立但确定"的局部随机, 不依赖任何可变 Random 推进顺序。
     * featureId 区分用途 (如骨架/矿物/陷阱), 避免不同子系统在同坐标撞随机流。
     */
    public static long hash(long seed, int x, int z, int featureId) {
        long z0 = seed ^ ((long) x * GOLDEN_GAMMA)
                ^ Long.rotateLeft((long) z * MIX1, 21)
                ^ ((long) featureId * MIX2);
        z0 = (z0 ^ (z0 >>> 30)) * MIX1;
        z0 = (z0 ^ (z0 >>> 27)) * MIX2;
        return z0 ^ (z0 >>> 31);
    }

    /**
     * 由 long 种子构造原版 RandomSource (1.20.1 WorldgenRandom + LegacyRandomSource)。
     * 每次调用返回独立实例; 严禁在多区块/多线程间共享同一返回值 (7.1.1)。
     */
    public static RandomSource fromSeed(long seed) {
        return new WorldgenRandom(new LegacyRandomSource(seed));
    }
}

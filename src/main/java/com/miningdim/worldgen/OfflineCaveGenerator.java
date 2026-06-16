package com.miningdim.worldgen;

import com.miningdim.core.Difficulty;
import com.miningdim.core.IMiningConfig;
import com.miningdim.core.IOfflineGenerator;
import com.miningdim.core.MiningServices;
import com.miningdim.core.RegionBox;
import com.miningdim.core.VoxelOccupancy;
import com.miningdim.worldgen.carve.NoiseCarver;
import com.miningdim.worldgen.connectivity.ConnectivityFixer;
import com.miningdim.worldgen.skeleton.HybridSkeleton;
import com.miningdim.worldgen.skeleton.RandomWalkSkeleton;
import com.miningdim.worldgen.skeleton.RoomCorridorSkeleton;
import com.miningdim.worldgen.skeleton.SkeletonGenerator;
import com.miningdim.worldgen.skeleton.SkeletonResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 离线矿洞生成器 (设计文档 3.3 / 第七章 / D2-D4), core.IOfflineGenerator 的唯一实现。
 *
 * 职责: 把三阶段管线 Skeleton -> NoiseCarving -> ConnectivityFix (7.3) 提交到自管线程池 (与 MC
 * chunk worker 隔离, 7.9.2) 串行执行, 全程纯内存体素计算, 不触碰 ServerLevel (C7/D8)。完成后
 * 冻结为 immutable VoxelOccupancy 供 MiningChunkGenerator 查表 (落方块阶段回主线程由生成器调用方负责)。
 *
 * 确定性 (D3): 同一 (seed, difficulty, regionBox) 逐位相等 —— 各阶段只用 GenContext 派生的 stageSeed
 * 构造串行 RandomSource / 坐标派生 hash, 无可变 Random 跨调用共享 (7.6)。
 *
 * 线程池大小取 IMiningConfig.maxGenWorkers() (perf.maxGenWorkers, 默认 2)。配置子系统须早于 worldgen
 * 注入 (主类 List<Subsystem> 顺序), 否则这里 MiningServices.config() 会抛 IllegalStateException ——
 * 这是注入顺序错的真实缺陷, 不掩盖 (C9)。
 */
public final class OfflineCaveGenerator implements IOfflineGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/OfflineCaveGenerator");

    /**
     * 工作线程池, 懒初始化 (volatile + 双检锁)。懒化的原因: 池大小取 IMiningConfig.maxGenWorkers,
     * 而本实现可能在 ConfigSystem 之前被 WorldgenSystem.register 构造注入 MiningServices; 把 config 读取
     * 推迟到首个 generate 调用 (世界加载后, 配置必已 Loading 完成, 16.3), 避免 register 期的注入顺序耦合。
     */
    private volatile ExecutorService workers;

    /** 难度 -> 骨架算法 (7.4.1 映射表)。三实现无状态, 可被并发任务共享。 */
    private final SkeletonGenerator easyAlgo = new RandomWalkSkeleton();
    private final SkeletonGenerator mediumAlgo = new HybridSkeleton();
    private final SkeletonGenerator hardAlgo = new RoomCorridorSkeleton();

    @Override
    public CompletableFuture<VoxelOccupancy> generate(long seed, Difficulty difficulty, RegionBox regionBox) {
        // 提交到工作线程池; 异常自然以 future 异常完成冒泡到调用方 (C9), 不在此生吞。
        return CompletableFuture.supplyAsync(() -> runPipeline(seed, difficulty, regionBox), pool());
    }

    private ExecutorService pool() {
        ExecutorService p = workers;
        if (p == null) {
            synchronized (this) {
                p = workers;
                if (p == null) {
                    IMiningConfig cfg = MiningServices.config();
                    int poolSize = Math.max(1, cfg.maxGenWorkers());
                    p = Executors.newFixedThreadPool(poolSize, namedDaemonFactory());
                    workers = p;
                    LOGGER.info("[miningdim] OfflineCaveGenerator worker pool size={}", poolSize);
                }
            }
        }
        return p;
    }

    /** 三阶段串行执行 (单线程内, 保证 RandomSource 推进顺序确定, 7.6.2)。 */
    private VoxelOccupancy runPipeline(long seed, Difficulty difficulty, RegionBox box) {
        long t0 = System.nanoTime();
        GenContext ctx = new GenContext(seed, difficulty, box);
        VoxelGrid grid = new VoxelGrid(box);

        // Stage 1: 骨架 (输出自身连通 + 出生锚点)。
        SkeletonGenerator algo = skeletonFor(difficulty);
        SkeletonResult skeleton = algo.generate(grid, ctx);

        // Stage 2: 噪声扩挖 (只扩挖不回填, 可能引入新孤岛, 交给 Stage 3)。
        NoiseCarver.apply(grid, ctx);

        // Stage 3: 连通性修复 (最后一道闸, 保证 air 主分量全连通; 出生锚点 ∈ 主分量)。
        int mainVolume = new ConnectivityFixer(grid).fix(skeleton.spawnAnchor());

        VoxelOccupancy frozen = grid.freeze();
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        LOGGER.info("[miningdim] generated instance voxels: difficulty={} mainAir={} totalAir={} elapsedMs={}",
                difficulty.configName(), mainVolume, grid.airCount(), ms);
        return frozen;
    }

    private SkeletonGenerator skeletonFor(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> easyAlgo;
            case MEDIUM -> mediumAlgo;
            case HARD -> hardAlgo;
        };
    }

    /** 关闭线程池 (供 WorldgenSystem 在服务端停止时调用, 释放工作线程)。未初始化则无操作。 */
    public void shutdown() {
        ExecutorService p = workers;
        if (p != null) {
            p.shutdownNow();
        }
    }

    private static ThreadFactory namedDaemonFactory() {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "miningdim-gen-" + seq.incrementAndGet());
            t.setDaemon(true); // 守护线程, 不阻塞 JVM 退出
            return t;
        };
    }
}

package com.miningdim.instance;

import com.miningdim.core.GenState;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.RegionBox;
import com.miningdim.core.VoxelOccupancy;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.world.ForgeChunkManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 离线生成调度器 (设计文档 7.9 / D2 / D8)。职责:
 *   1. 持有一个与 MC chunk worker 隔离的固定 ExecutorService (poolSize = config.maxGenWorkers)。
 *   2. submit(): 把实例置 GENERATING, 调 IOfflineGenerator.generate 提交体素计算到工作线程;
 *      完成后经 server.execute 回主线程缓存体素并把 genState 置 READY (异常 -> FAILED + 兜底)。
 *   3. 维护"已就绪实例的强加载区块"分帧队列: 每 tick 限速 force-load 若干区块, 触发 MiningChunkGenerator
 *      按缓存体素落方块, 避免一次性加载整 region 卡主线程 (绝不主线程逐块 setBlock, 13 章 Critical)。
 *   4. 暴露 voxelsOf(instanceId): 供 worldgen 子系统的 ChunkGenerator 查表落方块 (阶段2 接线点)。
 *
 * 线程纪律 (D8): generate 在工作线程纯计算; genState 写、force-load 写均经 server.execute 回主线程。
 * 体素缓存用 ConcurrentHashMap 是读侧防御 (ChunkGenerator 可能在区块 worker 线程读), 写仍主线程串行。
 */
public final class GenerationScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/GenerationScheduler");

    /** force-load owner key (ForgeChunkManager 按 modId + ownerBlockPos 归属强加载票)。 */
    private final MinecraftServer server;

    /** 工作线程池; 大小 = config.maxGenWorkers, 与 MC chunk worker 隔离 (7.9.2)。 */
    private final ExecutorService genPool;

    /** instanceId -> 冻结体素视图。生成完成后写入, 重置/回收时移除。读侧可跨线程 (区块 worker)。 */
    private final Map<Long, VoxelOccupancy> voxelCache = new ConcurrentHashMap<>();

    /**
     * 待分帧 force-load 的区块任务队列 (主线程独占, 故用非并发 ArrayDeque)。
     * 元素打包 (instanceId, chunkX, chunkZ) 为 long 不便, 故用小记录承载。
     */
    private final Deque<ChunkLoadTask> chunkLoadQueue = new ArrayDeque<>();

    /** 每 tick 最多 force-load 的区块数 (限速, 防止单 tick 加载整 region)。 */
    private static final int MAX_CHUNK_LOADS_PER_TICK = 4;

    /**
     * 生成终态回调 (主线程): 实例进入 READY/READY_FALLBACK/FAILED 后通知 InstanceManager,
     * 以兑现/异常完成挂起的 allocate future。由 InstanceManager 注入, 不暴露给其他子系统。
     */
    private final java.util.function.Consumer<InstanceState> onTerminalState;

    public GenerationScheduler(MinecraftServer server, int maxGenWorkers,
                               java.util.function.Consumer<InstanceState> onTerminalState) {
        this.server = server;
        this.onTerminalState = onTerminalState;
        int pool = Math.max(1, maxGenWorkers);
        this.genPool = Executors.newFixedThreadPool(pool, namedDaemonFactory());
        LOGGER.info("[miningdim] GenerationScheduler started with {} worker thread(s)", pool);
    }

    private static ThreadFactory namedDaemonFactory() {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "miningdim-gen-" + seq.incrementAndGet());
            t.setDaemon(true);
            // 工作线程优先级略低于游戏主线程, 减少对 TPS 的争抢 (纯计算可后台慢慢跑)。
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        };
    }

    /**
     * 为实例提交离线生成 (7.9.1 步骤 2-5)。前置: 实例已登记、genState == PENDING。
     * 完成后回主线程: 缓存体素、入队 region 区块强加载、genState=READY; 异常 -> FAILED 并记日志,
     * 兜底由上层 (ResetService/运维) 决定重试 —— 本调度器不静默吞异常 (C9)。
     * 仅主线程调用 (由 InstanceManager 串行)。
     */
    public void submit(InstanceState instance) {
        instance.setGenState(GenState.GENERATING);

        CompletableFuture<VoxelOccupancy> future = MiningServices.offlineGenerator()
                .generate(instance.seed(), instance.difficulty(), instance.regionBox());

        future.whenComplete((voxels, error) ->
                server.execute(() -> onGenerationComplete(instance, voxels, error)));
    }

    /** 工作线程完成后的主线程收尾 (D8: genState/区块写必经主线程)。 */
    private void onGenerationComplete(InstanceState instance, VoxelOccupancy voxels, Throwable error) {
        long id = instance.instanceId();

        // 实例可能在生成途中已被 GC 销毁 (玩家全退 + 回收); 此时不再缓存/加载, 直接丢弃结果。
        if (instance.genState() == GenState.RECYCLED) {
            LOGGER.info("[miningdim] instance {} recycled during generation; discarding result", id);
            return;
        }

        if (error != null) {
            instance.setGenState(GenState.FAILED);
            LOGGER.error("[miningdim] offline generation FAILED for instance {} (difficulty {})",
                    id, instance.difficulty(), error);
            onTerminalState.accept(instance);
            return;
        }

        if (voxels == null) {
            // 离线生成器契约要求非异常即返回非 null; 出现 null 视为生成器违约, 不掩盖。
            instance.setGenState(GenState.FAILED);
            LOGGER.error("[miningdim] offline generator returned null voxels for instance {} without error", id);
            onTerminalState.accept(instance);
            return;
        }

        voxelCache.put(id, voxels);
        instance.setGenState(GenState.READY);
        enqueueRegionChunkLoads(instance);
        LOGGER.info("[miningdim] instance {} generation READY ({} voxels)", id, voxels.width() * voxels.height() * voxels.depth());
        onTerminalState.accept(instance);
    }

    /** 把实例 region 覆盖的全部区块入队待分帧强加载。 */
    private void enqueueRegionChunkLoads(InstanceState instance) {
        RegionBox box = instance.regionBox();
        int minChunkX = box.originX() >> 4;
        int minChunkZ = box.originZ() >> 4;
        int maxChunkX = (box.originX() + box.sizeX() - 1) >> 4;
        int maxChunkZ = (box.originZ() + box.sizeZ() - 1) >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                chunkLoadQueue.addLast(new ChunkLoadTask(instance.instanceId(), cx, cz));
            }
        }
    }

    /**
     * 维度 tick 末调用: 限速消费区块强加载队列 (主线程, 7.9 分帧)。每 tick 最多 MAX_CHUNK_LOADS_PER_TICK 个。
     * force-load 触发区块生成 -> MiningChunkGenerator 读 voxelsOf 落方块。实例若已不再 READY (被重置/回收),
     * 跳过其残留任务。
     */
    public void tickChunkLoads() {
        ServerLevel level = server.getLevel(MiningConstants.MINING_LEVEL);
        if (level == null) {
            return;
        }
        int budget = MAX_CHUNK_LOADS_PER_TICK;
        while (budget > 0 && !chunkLoadQueue.isEmpty()) {
            ChunkLoadTask task = chunkLoadQueue.pollFirst();
            InstanceState inst = MiningServices.instanceManager().byId(task.instanceId()).orElse(null);
            if (inst == null || !inst.genState().isEnterable()) {
                continue; // 实例已失效, 丢弃残留区块任务, 不计入预算
            }
            ForgeChunkManager.forceChunk(level, MiningConstants.MODID,
                    new net.minecraft.core.BlockPos(inst.regionBox().originX(), inst.regionBox().originY(), inst.regionBox().originZ()),
                    task.chunkX(), task.chunkZ(), true, false);
            budget--;
        }
    }

    /** 取实例的冻结体素视图 (ChunkGenerator 查表落方块用; 阶段2 worldgen 接线点)。未就绪返回 null。 */
    public VoxelOccupancy voxelsOf(long instanceId) {
        return voxelCache.get(instanceId);
    }

    /** 重置/回收时清除体素缓存并释放该 region 强加载票 (主线程)。 */
    public void release(InstanceState instance) {
        voxelCache.remove(instance.instanceId());
        ServerLevel level = server.getLevel(MiningConstants.MINING_LEVEL);
        if (level == null) {
            return;
        }
        RegionBox box = instance.regionBox();
        int minChunkX = box.originX() >> 4;
        int minChunkZ = box.originZ() >> 4;
        int maxChunkX = (box.originX() + box.sizeX() - 1) >> 4;
        int maxChunkZ = (box.originZ() + box.sizeZ() - 1) >> 4;
        net.minecraft.core.BlockPos owner =
                new net.minecraft.core.BlockPos(box.originX(), box.originY(), box.originZ());
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                ForgeChunkManager.forceChunk(level, MiningConstants.MODID, owner, cx, cz, false, false);
            }
        }
    }

    /** 服务端停止时优雅关闭线程池 (ServerStoppingEvent)。 */
    public void shutdown() {
        genPool.shutdownNow();
        try {
            if (!genPool.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("[miningdim] generation pool did not terminate within 5s");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        voxelCache.clear();
        chunkLoadQueue.clear();
    }

    /** 待强加载区块任务 (实例 id + 区块坐标)。 */
    private record ChunkLoadTask(long instanceId, int chunkX, int chunkZ) {
    }
}

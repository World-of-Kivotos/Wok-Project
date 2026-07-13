package com.miningdim.instance;

import com.miningdim.core.GenState;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.RegionBox;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.world.ForgeChunkManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 离线生成调度器 (设计文档 7.9)。原设计的自定义离线体素管线 (IOfflineGenerator -> voxelsOf ->
 * MiningChunkGenerator 落方块) 已判废: 维度改用 minecraft:noise 生成 + 原版 ore feature, MiningChunkGenerator
 * 从不被实例化, 故 submit 不再跑体素计算 (原"首次 enter 慢"根源)。当前职责收敛为:
 *   1. submit(): 直接把实例置 READY 并通知终态回调 (兑现挂起的 allocate future)。
 *   2. 维护 region 强加载区块的分帧队列: 每 tick 限速 force-load 若干区块, 触发原版噪声生成落地形,
 *      避免一次性加载整 region 卡主线程 (绝不主线程逐块 setBlock, 13 章 Critical)。
 *
 * 线程纪律 (D8): 全部方法均主线程调用 (由 InstanceManager 串行)。
 */
public final class GenerationScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/GenerationScheduler");

    /** force-load owner key (ForgeChunkManager 按 modId + ownerBlockPos 归属强加载票)。 */
    private final MinecraftServer server;

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

    public GenerationScheduler(MinecraftServer server,
                               java.util.function.Consumer<InstanceState> onTerminalState) {
        this.server = server;
        this.onTerminalState = onTerminalState;
    }

    /**
     * 为实例提交生成 (7.9.1)。前置: 实例已登记、genState == PENDING。同步在主线程完成: 置 READY、入队 region
     * 区块分帧强加载 (触发原版噪声落地形)、通知终态回调兑现挂起的 allocate future。不再有工作线程异步窗口 ——
     * 自定义体素管线已判废, 无 256x384x256 体素计算等待 (原"首次 enter 慢"根源)。仅主线程调用 (由 InstanceManager 串行)。
     */
    public void submit(InstanceState instance) {
        instance.setGenState(GenState.READY);
        enqueueRegionChunkLoads(instance);
        LOGGER.info("[miningdim] instance {} READY (offline voxel generation retired; noise terrain)",
                instance.instanceId());
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
     * force-load 触发区块生成 -> 原版噪声生成落地形。实例若已不再 READY (被重置/回收), 跳过其残留任务。
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

    /**
     * 从分帧队列移除指定实例尚未消费的残留区块强加载任务 (reset UNLOAD 断源调用), 返回清除数。主线程。
     *
     * tickChunkLoads 虽已有 isEnterable 守卫会丢弃 RESETTING 实例的残留任务, 但那是"消费到才丢";
     * 开机预热期队列积压数百个任务时, 重置若不显式清队, AWAIT_UNLOAD 等待窗口内仍可能被逐 tick 消费触碰。
     * 此处一次性断源, 令重置的卸载等待不与旧排队任务竞争。
     */
    public int cancelQueuedLoads(long instanceId) {
        int before = chunkLoadQueue.size();
        chunkLoadQueue.removeIf(task -> task.instanceId() == instanceId);
        int cleared = before - chunkLoadQueue.size();
        LOGGER.debug("[miningdim] cancelled {} queued chunk-load task(s) for instance {}", cleared, instanceId);
        return cleared;
    }

    /** 回收/离场空置时释放该 region 强加载票, 允许区块自然卸载 (主线程)。 */
    public void release(InstanceState instance) {
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

    /** 服务端停止时清空未消费的区块强加载队列 (ServerStoppingEvent)。 */
    public void shutdown() {
        chunkLoadQueue.clear();
    }

    /** 待强加载区块任务 (实例 id + 区块坐标)。 */
    private record ChunkLoadTask(long instanceId, int chunkX, int chunkZ) {
    }
}

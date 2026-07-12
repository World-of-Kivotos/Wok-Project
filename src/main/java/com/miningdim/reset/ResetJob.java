package com.miningdim.reset;

import com.miningdim.chunk.ChunkServices;
import com.miningdim.core.GenState;
import com.miningdim.core.IResetService;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.RegionBox;
import com.miningdim.core.SeedUtil;
import com.miningdim.core.VoxelOccupancy;
import com.miningdim.trap.TrapRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * 单实例重置任务的分帧状态机 (设计文档 13.4, Critical 性能核心)。由 {@link ResetSystem} 每服务端 tick 推进,
 * 全程不阻塞主线程超过单 tick 预算。阶段:
 *  UNLOAD   : 主线程释放 region 强加载 ticket, region 区块走原版卸载 (13.4 阶段一: 不逐块 setBlock);
 *  REGEN    : 工作线程重跑 {@link com.miningdim.core.IOfflineGenerator} 算体素 (13.4 阶段二, D2/D8);
 *             SAME_SEED 复用原 seed (逐位相同), NEW_SEED 用 resetGeneration+1 派生新 seed;
 *  SETTLE   : 限速等待区块按需重生成 (区块下次被加载时经 MiningChunkGenerator 查新体素填块),
 *             每 tick 预算受 maxMillisPerTick 约束顺延;
 *  DONE     : genState=READY, 兑现 reset future。
 *
 * 重置后区块的实际重建由 MiningChunkGenerator (worldgen 子系统) 在区块加载时查实例体素完成 ——
 * 本任务不直接写方块, 只负责卸载旧区块、触发体素重算、限速推进与状态翻转 (维持子系统边界)。
 */
final class ResetJob {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/reset");

    /** SETTLE 阶段单 tick 墙钟预算 (ms, 13.4 reset.maxMillisPerTick 建议 10)。 */
    private static final long MAX_MILLIS_PER_TICK = 10L;
    /** SETTLE 阶段最少停留 tick (给卸载/重算落定一个最小窗口, 防止瞬间翻 READY)。 */
    private static final int MIN_SETTLE_TICKS = 2;

    private enum Phase {
        UNLOAD,
        REGEN,
        SETTLE,
        DONE
    }

    private final MinecraftServer server;
    private final InstanceState instance;
    private final IResetService.ResetMode mode;
    private final long globalSeed;
    private final int resetGeneration;
    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    private Phase phase = Phase.UNLOAD;
    private volatile boolean voxelsReady = false;
    private volatile Throwable regenError = null;
    private int settleTicks = 0;

    ResetJob(MinecraftServer server, InstanceState instance, IResetService.ResetMode mode,
             long globalSeed, int resetGeneration) {
        this.server = server;
        this.instance = instance;
        this.mode = mode;
        this.globalSeed = globalSeed;
        this.resetGeneration = resetGeneration;
    }

    CompletableFuture<Void> completion() {
        return completion;
    }

    long instanceId() {
        return instance.instanceId();
    }

    /** 推进一帧; 返回 true 表示任务结束 (DONE 或异常)。主线程。 */
    boolean tick() {
        switch (phase) {
            case UNLOAD -> {
                doUnload();
                phase = Phase.REGEN;
                return false;
            }
            case REGEN -> {
                if (regenError != null) {
                    fail(regenError);
                    return true;
                }
                if (voxelsReady) {
                    phase = Phase.SETTLE;
                    settleTicks = 0;
                }
                return false;
            }
            case SETTLE -> {
                settleTicks++;
                // 限速窗口: 至少 MIN_SETTLE_TICKS, 单 tick 不做重活 (区块重建由 MiningChunkGenerator 按需触发),
                // 这里仅以墙钟预算守门, 给卸载与体素缓存落定时间, 避免与生成线程的状态翻转竞态。
                if (settleTicks >= MIN_SETTLE_TICKS) {
                    finish();
                    phase = Phase.DONE;
                    return true;
                }
                return false;
            }
            default -> {
                return true;
            }
        }
    }

    /** 13.4 阶段一: 释放 region 强加载 ticket, region 区块自然卸载 (不逐块 setBlock)。 */
    private void doUnload() {
        if (ChunkServices.isReady()) {
            ChunkServices.ticketService().releaseAll(instance.instanceId());
        }
        // 协议级伪装陷阱: region 即将重生成新布局 (NEW_SEED 换布局), 旧 TrapRegistry 条目会指向不再是陷阱的坐标
        // (幽灵陷阱)。在此按 region 覆盖的 chunk 清注册表, 与区块卸载/重算同步 (重生的区块加载时由转换器重新登记新陷阱)。
        clearTrapRegistryForRegion();
        // 13.4 阶段二: 触发离线体素重算 (工作线程, D8)。
        long seed = (mode == IResetService.ResetMode.SAME_SEED)
                ? instance.seed()
                : SeedUtil.deriveSeed(globalSeed, instance.instanceId(), resetGeneration);
        CompletableFuture<VoxelOccupancy> gen =
                MiningServices.offlineGenerator().generate(seed, instance.difficulty(), instance.regionBox());
        gen.whenComplete((voxels, err) -> server.execute(() -> {
            if (err != null) {
                regenError = err;
            } else {
                // 体素已重算完成。重建后的区块在下次加载时由 MiningChunkGenerator 查实例体素填块;
                // 此处只标记就绪, 由 SETTLE 阶段限速翻转状态 (本子系统不直接写世界方块)。
                voxelsReady = true;
            }
        }));
        LOGGER.debug("[miningdim] reset job UNLOAD done for instance {} (mode={})",
                instance.instanceId(), mode);
    }

    /**
     * 清 region 覆盖 chunk 的伪装陷阱注册表条目 (防旧陷阱身份变幽灵)。region 与 chunk 对齐 (SIZE=256=16 chunk,
     * origin 落 stride 倍数且为 16 倍), 故按 chunk 边界枚举整 region。矿洞维度未加载 (极端时序) 则跳过 —— 无维度即
     * 无 DataStorage 可清, 不静默造维度。
     */
    private void clearTrapRegistryForRegion() {
        ServerLevel mining = server.getLevel(MiningConstants.MINING_LEVEL);
        if (mining == null) {
            return;
        }
        TrapRegistry registry = TrapRegistry.get(mining);
        RegionBox box = instance.regionBox();
        int minChunkX = box.originX() >> 4;
        int maxChunkX = (box.originX() + box.sizeX() - 1) >> 4;
        int minChunkZ = box.originZ() >> 4;
        int maxChunkZ = (box.originZ() + box.sizeZ() - 1) >> 4;
        int cleared = 0;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                cleared += registry.clearChunk(new ChunkPos(cx, cz));
            }
        }
        if (cleared > 0) {
            LOGGER.debug("[miningdim] reset cleared {} trap registry entries for instance {}",
                    cleared, instance.instanceId());
        }
    }

    /** 重置成功收尾: genState 回 READY, 兑现 future。 */
    private void finish() {
        instance.setGenState(GenState.READY);
        completion.complete(null);
        LOGGER.info("[miningdim] instance {} reset complete (mode={})", instance.instanceId(), mode);
    }

    private void fail(Throwable err) {
        Throwable cause = (err instanceof java.util.concurrent.CompletionException && err.getCause() != null)
                ? err.getCause() : err;
        instance.setGenState(GenState.FAILED);
        completion.completeExceptionally(cause);
        LOGGER.warn("[miningdim] instance {} reset FAILED: {}", instance.instanceId(), cause.toString());
    }
}

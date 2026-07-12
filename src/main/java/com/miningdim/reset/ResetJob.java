package com.miningdim.reset;

import com.miningdim.core.GenState;
import com.miningdim.core.IResetService;
import com.miningdim.core.InstanceState;
import com.miningdim.core.RegionBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 单实例重置任务的分帧状态机 (设计文档第十三章)。由 {@link ResetSystem} 每服务端 tick 推进, 全程主线程、
 * 不阻塞超过单 tick 预算。区别于旧实现: 不再重算离线体素 (维度用 minecraft:noise, 体素管线已判废), 而是
 * 真正做物理区块再生成 —— 卸载 region 区块 + 删除其地形/实体存档, 令下次加载经 noise 从头重生成。
 *
 * 阶段:
 *   UNLOAD       : 释放 region 全部强加载票 (滑动窗口 + 生成期强制块) + 清生成调度器该实例的排队任务 (断源) +
 *                  清伪装陷阱注册表 (各一次)。
 *   AWAIT_UNLOAD : 逐 tick 轮询 region 16x16 个 chunk 是否全部无 holder; 每 {@link #TICKET_REPLAY_INTERVAL_TICKS}
 *                  幂等重放释放票 (对冲预热管线补票); 超 {@link #AWAIT_UNLOAD_TIMEOUT_TICKS} 仍未卸载完 -> FAILED
 *                  (不静默, 暴露"有票没释放/有人没撤离"的真实缺陷; FAILED 可再次 /mining reset 自救)。
 *   PURGE        : flush 挂起异步写 -> 对 region 每个 chunk 逐一删存档 (地形 + 实体) -> 再 flush 落盘。单 tick 完成。
 *   SETTLE       : 停留 {@link #MIN_SETTLE_TICKS} tick 让删除落定, 再翻 READY。
 *   DONE         : genState=READY, 兑现 completion; 带耗时日志。
 *
 * 一切原版侧 I/O 经 {@link ResetChunkOps} 接缝下沉 (生产走 {@link LiveResetChunkOps}, 测试注入记录型替身),
 * 故本状态机可用 GameTest 精确锁死。任何一步异常 -> {@link #fail} 置 FAILED + completion 异常兑现 (自然冒泡)。
 *
 * mode / resetGeneration 不再驱动体素 (布局由 noise 世界种子 + 坐标决定), 保留仅作诊断日志; resetGeneration
 * 机制在 {@link ResetSystem} 侧不动, 供未来掺哈希做布局变化时复用。
 */
final class ResetJob {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/reset");

    /**
     * AWAIT_UNLOAD 上限 (tick); 60s。上限受开机预热生成管线积压影响 —— 开机时三固定实例各入队数百区块经原版
     * 噪声生成管线分帧加载, 生成中的区块持 ChunkHolder, 旧 300 tick (15s) 不足以在预热高峰排干 (2026-07-12 真服实测
     * MEDIUM/HARD 超时转 FAILED)。抬到 1200 tick 覆盖预热积压; 仍超时则确有票没释放/有人没撤离的真实缺陷, 转 FAILED。
     */
    private static final int AWAIT_UNLOAD_TIMEOUT_TICKS = 1200;
    /** AWAIT_UNLOAD 期间幂等重放 releaseTickets 的周期 (tick); 对冲预热管线/滑动窗口任何来源的票据竞态。 */
    private static final int TICKET_REPLAY_INTERVAL_TICKS = 20;
    /** PURGE 后最少停留 tick, 给删除落定一个最小窗口, 防止瞬间翻 READY。 */
    private static final int MIN_SETTLE_TICKS = 2;

    private enum Phase {
        UNLOAD,
        AWAIT_UNLOAD,
        PURGE,
        SETTLE,
        DONE
    }

    private final InstanceState instance;
    private final ResetChunkOps ops;
    private final IResetService.ResetMode mode;
    private final int resetGeneration;
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private final long startNanos = System.nanoTime();

    private Phase phase = Phase.UNLOAD;
    private int awaitTicks = 0;
    private int settleTicks = 0;

    ResetJob(InstanceState instance, ResetChunkOps ops, IResetService.ResetMode mode, int resetGeneration) {
        this.instance = instance;
        this.ops = ops;
        this.mode = mode;
        this.resetGeneration = resetGeneration;
    }

    CompletableFuture<Void> completion() {
        return completion;
    }

    long instanceId() {
        return instance.instanceId();
    }

    /** 推进一帧; 返回 true 表示任务结束 (DONE 或 FAILED)。主线程。 */
    boolean tick() {
        try {
            switch (phase) {
                case UNLOAD -> {
                    doUnload();
                    phase = Phase.AWAIT_UNLOAD;
                    return false;
                }
                case AWAIT_UNLOAD -> {
                    if (ops.allChunksUnloaded()) {
                        phase = Phase.PURGE;
                        return false;
                    }
                    if (++awaitTicks > AWAIT_UNLOAD_TIMEOUT_TICKS) {
                        fail(new IllegalStateException("reset: region chunks of instance " + instance.instanceId()
                                + " failed to unload within " + AWAIT_UNLOAD_TIMEOUT_TICKS + " ticks"));
                        return true;
                    }
                    // 每 TICKET_REPLAY_INTERVAL_TICKS 幂等重放释放票: 预热管线可能在 UNLOAD 后又为该 region 补票,
                    // 单次 releaseTickets 无法覆盖; 周期重放对冲此竞态 (releaseTickets 已幂等, 对未强制块无副作用)。
                    if (awaitTicks % TICKET_REPLAY_INTERVAL_TICKS == 0) {
                        ops.releaseTickets();
                    }
                    return false;
                }
                case PURGE -> {
                    doPurge();
                    phase = Phase.SETTLE;
                    settleTicks = 0;
                    return false;
                }
                case SETTLE -> {
                    if (++settleTicks >= MIN_SETTLE_TICKS) {
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
        } catch (Throwable err) {
            fail(err);
            return true;
        }
    }

    /** UNLOAD: 释放强加载票 + 清生成调度器排队任务 (断源) + 清 region 伪装陷阱注册表 (各一次)。 */
    private void doUnload() {
        ops.releaseTickets();
        int cancelledLoads = ops.cancelQueuedLoads();
        int cleared = ops.clearTrapRegistry();
        LOGGER.debug("[miningdim] reset UNLOAD instance {} (mode={}, gen={}); cancelled {} queued load(s), "
                        + "cleared {} trap registry entries",
                instance.instanceId(), mode, resetGeneration, cancelledLoads, cleared);
    }

    /** PURGE: flush -> 逐 chunk 删地形+实体存档 -> 再 flush 落盘 (单 tick)。 */
    private void doPurge() {
        ops.flushPendingWrites();
        RegionBox box = instance.regionBox();
        int minChunkX = box.originX() >> 4;
        int maxChunkX = (box.originX() + box.sizeX() - 1) >> 4;
        int minChunkZ = box.originZ() >> 4;
        int maxChunkZ = (box.originZ() + box.sizeZ() - 1) >> 4;
        int purged = 0;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                ops.deleteChunk(cx, cz);
                purged++;
            }
        }
        ops.flushPendingWrites();
        LOGGER.debug("[miningdim] reset PURGE instance {}: deleted {} chunk save(s)", instance.instanceId(), purged);
    }

    /** 重置成功收尾: genState 回 READY, 兑现 completion, 记耗时。 */
    private void finish() {
        instance.setGenState(GenState.READY);
        completion.complete(null);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        LOGGER.info("[miningdim] instance {} reset complete (mode={}, gen={}, {} ms)",
                instance.instanceId(), mode, resetGeneration, elapsedMs);
    }

    private void fail(Throwable err) {
        Throwable cause = (err instanceof CompletionException && err.getCause() != null) ? err.getCause() : err;
        instance.setGenState(GenState.FAILED);
        completion.completeExceptionally(cause);
        // FAILED 现为可恢复态 (ResetSystem.reset 放行 FAILED): 日志带自救指引, 不留"砖死到重启"错觉。
        LOGGER.warn("[miningdim] instance {} reset FAILED: {}; instance remains resettable, re-run /mining reset to retry",
                instance.instanceId(), cause.toString());
    }
}

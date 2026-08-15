package com.miningdim.reset;

import com.miningdim.chunk.ChunkServices;
import com.miningdim.core.GenState;
import com.miningdim.core.IResetService;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningServices;
import com.miningdim.core.RegionBox;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * 单实例重置任务的分帧状态机 (D3 滑动 region)。由 {@link ResetSystem} 每服务端 tick 推进。阶段:
 *  UNLOAD : 主线程撤离在场玩家、释放该实例的全部区块强加载 ticket (必须先于几何变更, 否则旧 owner/旧 box
 *           发出的 ticket 无法被正确撤销), 并按 mode 计算/派生本次重置目标 seed;
 *  REGEN  : 调 IInstanceManager.slideRegion 把实例整块滑到一块从未生成过的新坐标 (写回新 regionBox/seed
 *           后直接置 READY —— 离线生成已下线, 维度走 minecraft:noise 按需生成), 之后逐 tick 轮询
 *           instance.genState() 直到 isEnterable() 或 FAILED 或超时 (通常首帧即通过, 轮询作为失败态兜底保留);
 *  SETTLE : 清该实例的影子态 (liveMobs) 并广播 MiningServices.fireInstanceReset, 驱动陷阱/矿物/出生/压力
 *           等子系统的按实例缓存失效重算; 停留满 MIN_SETTLE_TICKS 后落定;
 *  DONE   : genState=READY (幂等, 生成通路通常已写过), 兑现 reset future。
 *
 * 已知遗留: 旧坐标 region 的区块 (含玩家挖的坑、放的箱子、掉落物、残留怪物) 被整体遗弃在磁盘上不再访问,
 * 旧 region 对应 .mca 文件的磁盘回收未实现 (由后续分支处理)。同一根因还有坐标空间本身的单调消耗:
 * MiningSavedData.allocateRegionOriginX 只增不减、越过 MiningConstants.MAX_REGION_WORLD_X 直接拒绝
 * 分配 (绝不绕回复用旧坐标), 默认三难度重置节奏下约 888 天耗尽; 且世界 X 坐标越过 2^23 (~298 天) 起
 * 原版实体位置/渲染的 float 精度即开始劣化。两者都是本次 D3 未处理的遗留, 一并留给后续分支
 * (候选方向: 旧坐标确认落盘删除后回收游标区间, 或改单轴推进为 Z 轴换行的二维铺开)。
 *
 * 限速契约: 离线生成管线已下线 (F021/F032), slideRegion 写回新 regionBox/seed 后直接置 READY;
 * 区块由玩家进入时 com.miningdim.chunk.ChunkTicketManager 的滑动窗口按需强加载, 本任务自身不做
 * 任何加载限速, 只负责轮询 genState 与超时判定 (兜底失败态)。
 */
final class ResetJob {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/reset");

    /** SETTLE 阶段最少停留 tick (给影子态清理与监听器回调落定一个最小窗口, 防止瞬间翻 READY)。 */
    private static final int MIN_SETTLE_TICKS = 2;

    /** REGEN 阶段等待生成完成的超时 tick 数 (5 分钟), 超过判失败, 绝不无限挂着 future。 */
    private static final int REGEN_TIMEOUT_TICKS = 6000;

    private enum Phase {
        UNLOAD,
        REGEN,
        SETTLE,
        DONE
    }

    private final MinecraftServer server;
    private final InstanceState instance;
    private final IResetService.ResetMode mode;
    private final ResetSystem owner;
    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    private Phase phase = Phase.UNLOAD;
    private long targetSeed;
    private boolean slideRequested = false;
    private RegionBox slidTo;
    private int regenWaitTicks = 0;
    private int settleTicks = 0;

    ResetJob(MinecraftServer server, InstanceState instance, IResetService.ResetMode mode, ResetSystem owner) {
        this.server = server;
        this.instance = instance;
        this.mode = mode;
        this.owner = owner;
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
                return tickRegen();
            }
            case SETTLE -> {
                if (settleTicks == 0) {
                    // 首个 tick: 清实例影子态 (liveMobs)。子系统按实例缓存失效的广播已提前到
                    // InstanceManager.slideRegion 内部, 几何一改立刻生效 (分支复核 finding #11),
                    // 此处不再重复调用 MiningServices.fireInstanceReset。
                    instance.liveMobs().clear();
                }
                settleTicks++;
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

    /** UNLOAD: 撤离在场玩家 + 释放全部区块 ticket (必须先于滑动几何变更) + 计算本次目标 seed。 */
    private void doUnload() {
        if (!instance.playerSet().isEmpty()) {
            owner.evacuate(instance, server);
        }
        if (ChunkServices.isReady()) {
            ChunkServices.ticketService().releaseAll(instance.instanceId());
        }
        targetSeed = (mode == IResetService.ResetMode.SAME_SEED)
                ? instance.seed()
                : MiningServices.instanceManager().deriveNextResetSeed(instance.instanceId());
        LOGGER.debug("[miningdim] reset job UNLOAD done for instance {} (mode={}, targetSeed={})",
                instance.instanceId(), mode, targetSeed);
    }

    /** REGEN: 首次进入触发滑动, 之后逐 tick 轮询 genState 直到就绪/失败/超时。 */
    private boolean tickRegen() {
        if (!slideRequested) {
            slideRequested = true;
            slidTo = MiningServices.instanceManager().slideRegion(instance.instanceId(), targetSeed);
            LOGGER.info("[miningdim] instance {} region slid to origin ({}, {}, {}) seed={}",
                    instance.instanceId(), slidTo.originX(), slidTo.originY(), slidTo.originZ(), targetSeed);
        }

        GenState state = instance.genState();
        if (state == GenState.FAILED) {
            fail(new IllegalStateException(
                    "instance " + instance.instanceId() + " regeneration failed after slide"));
            return true;
        }
        if (state.isEnterable()) {
            phase = Phase.SETTLE;
            settleTicks = 0;
            return false;
        }

        regenWaitTicks++;
        if (regenWaitTicks > REGEN_TIMEOUT_TICKS) {
            fail(new IllegalStateException("instance " + instance.instanceId()
                    + " regeneration timed out after slide (last genState=" + state + ")"));
            return true;
        }
        return false;
    }

    /** 重置成功收尾: genState 回 READY (幂等), 兑现 future。 */
    private void finish() {
        if (instance.genState() != GenState.FAILED) {
            instance.setGenState(GenState.READY);
        }
        completion.complete(null);
        // slidTo 在 finish() 可达前必经 tickRegen 首次 slideRegion 调用赋值, 恒非 null。
        LOGGER.info("[miningdim] instance {} reset complete (mode={}, region origin=({}, {}, {}))",
                instance.instanceId(), mode, slidTo.originX(), slidTo.originY(), slidTo.originZ());
    }

    private void fail(Throwable err) {
        Throwable cause = (err instanceof java.util.concurrent.CompletionException && err.getCause() != null)
                ? err.getCause() : err;
        instance.setGenState(GenState.FAILED);
        completion.completeExceptionally(cause);
        LOGGER.warn("[miningdim] instance {} reset FAILED: {}", instance.instanceId(), cause.toString());
    }

    /** ResetSystem tick 循环捕获到本 job 抛异常时调用的失败收尾 (复用 fail 逻辑)。包内可见。 */
    void abort(Throwable cause) {
        fail(cause);
    }
}

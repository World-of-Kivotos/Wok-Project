package com.miningdim.reset;

import com.miningdim.core.GenState;
import com.miningdim.core.IResetService;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.Subsystem;
import com.miningdim.entry.IMiningPlayerData;
import com.miningdim.entry.MiningCapabilities;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 重置子系统入口 + {@link IResetService} 实现 (设计文档第十三章, G8/D1)。单实例 region 级重置:
 * 仅删除/重生成该 region 区块, 不触碰其他实例, 不增删维度 (C1)。
 *
 * 重置语义 (13.2-13.5, D3 滑动 region):
 *  - reset(): 前置校验 genState 可重置 + (requireEmpty 时) 无玩家; 置 RESETTING, 入分帧 {@link ResetJob} 队列;
 *  - evacuate(): 强制撤离在场玩家回各自进入前坐标 (读 Capability, D5/14.6); 离线者标记待撤离;
 *  - NEW_SEED 的重置代数与派生 seed 已下沉到 core InstanceManager.deriveNextResetSeed (F089 持久化,
 *    随存档落盘, 重启不归零), 本子系统不再自行跟踪计数。
 *
 * 线程 (D8): reset/evacuate 的世界写在主线程 (经 server.execute 或 tick); 体素重算在工作线程 (ResetJob 内部)。
 * 本子系统在 register 内把自身注入 MiningServices, 供命令/GC/入口层调用。
 */
public final class ResetSystem implements Subsystem, IResetService {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/reset");

    private MinecraftServer server;
    private ServerLevel miningLevel;

    /** R6 每难度定时自动重置调度器 (开服时构建, 关服置空)。 */
    private AutoResetScheduler autoResetScheduler;

    /** 进行中的分帧重置任务 (每 tick 推进)。 */
    private final List<ResetJob> activeJobs = new ArrayList<>();

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        // 注入门面: 命令/GC/入口层经 MiningServices.resetService() 取用。
        MiningServices.registerResetService(this);
        forgeBus.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        this.server = event.getServer();
        this.miningLevel = server.getLevel(MiningConstants.MINING_LEVEL);
        if (miningLevel == null) {
            throw new IllegalStateException(
                    "Mining dimension " + MiningConstants.MINING_LEVEL.location() + " missing at server start");
        }
        // R6: 构建每难度定时自动重置调度器 (其构造读 AutoResetData, 首次开服初始化 lastReset 基准)。
        this.autoResetScheduler = new AutoResetScheduler(server, miningLevel, this);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // 关服时未完成的重置任务以异常收尾, 避免 future 永久挂起。
        for (ResetJob job : activeJobs) {
            job.completion().completeExceptionally(
                    new IllegalStateException("server stopping, reset of instance " + job.instanceId() + " aborted"));
        }
        activeJobs.clear();
        this.autoResetScheduler = null;
        this.server = null;
        this.miningLevel = null;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // R6: 推进每难度定时自动重置 (内部按秒降频; 与分帧重置任务相互独立)。
        if (autoResetScheduler != null) {
            autoResetScheduler.tick();
        }
        if (activeJobs.isEmpty()) {
            return;
        }
        // 本 tick 入口是重置链路的最外层调度点: 单个 job.tick() 抛出的异常在此统一捕获并将该 job
        // 失败收尾, 不许连坐拖垮整个服务端 tick 事件, 但异常必须落日志且 future 必须异常完成, 不静默吞。
        Iterator<ResetJob> it = activeJobs.iterator();
        while (it.hasNext()) {
            ResetJob job = it.next();
            boolean done;
            try {
                done = job.tick();
            } catch (RuntimeException ex) {
                LOGGER.error("[miningdim] reset job for instance {} threw during tick, aborting", job.instanceId(), ex);
                job.abort(ex);
                done = true;
            }
            if (done) {
                it.remove();
            }
        }
    }

    // ---- IResetService ----

    @Override
    public CompletableFuture<Void> reset(long instanceId, ResetMode mode) {
        if (server == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("reset requested before server start"));
        }
        InstanceState inst = MiningServices.instanceManager().byId(instanceId).orElse(null);
        if (inst == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("reset: instance " + instanceId + " does not exist"));
        }
        // 13.2: READY/READY_FALLBACK 可重置; RESETTING/GENERATING/已回收均拒绝。FAILED 也放行——
        // GenState 状态流转表把 "FAILED --运维/自动重试--> PENDING" 列为设计内的合法转移, 且
        // AutoResetScheduler 失败后本就设计成下个周期自动重试 (onResetComplete 失败分支不更新
        // lastReset, 注释明写"下个周期会再次判到期并重试")。R1 下三块固定难度实例永久存在、从不重建,
        // 若这里继续把 FAILED 挡在 isEnterable() 门外, 一次失败的重置会让该难度永久锁死、重试机制
        // 形同虚设 (分支复核 finding #10)。
        if (!inst.genState().isEnterable() && inst.genState() != GenState.FAILED) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "reset: instance " + instanceId + " not in resettable state (" + inst.genState() + ")"));
        }
        // 13.2 前置条件: requireEmpty 且有人在场 -> 拒绝, 调用方须先 evacuate (FORCE 路径)。
        if (MiningServices.config().resetRequireEmpty() && !inst.playerSet().isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "reset: instance " + instanceId + " is occupied (requireEmpty=true); evacuate first"));
        }

        inst.setGenState(GenState.RESETTING);
        ResetJob job = new ResetJob(server, inst, mode, this);
        activeJobs.add(job);
        LOGGER.info("[miningdim] enqueued reset for instance {} (mode={})", instanceId, mode);
        return job.completion();
    }

    @Override
    public void evacuate(InstanceState instance, MinecraftServer mcServer) {
        // 13.3: 撤离必须在删除区块前完成。快照 playerSet 防遍历中并发修改。
        List<UUID> snapshot = new ArrayList<>(instance.playerSet());
        for (UUID uuid : snapshot) {
            ServerPlayer player = mcServer.getPlayerList().getPlayer(uuid);
            if (player == null) {
                // 离线玩家: 标记待撤离 (登录时由 14.6 送回), 并从在场集合移除。
                PendingEvacuations.mark(uuid);
                instance.playerSet().remove(uuid);
                continue;
            }
            teleportToFallback(player);
            // 统一离开汇聚点 (12.6): playerSet remove、active 重算、ticket 释放、唤醒排队。
            MiningServices.instanceManager().onPlayerLeave(instance.instanceId(), player);
        }
        // 撤离后若仍非空 (极端边界: onPlayerLeave 未清干净), 记 Major 日志暴露之, 不静默。
        if (!instance.playerSet().isEmpty()) {
            LOGGER.warn("[miningdim] instance {} still has {} player(s) after evacuate",
                    instance.instanceId(), instance.playerSet().size());
        }
    }

    /**
     * 把玩家传送回进入前回退点 (13.3 撤离目标优先级: Capability.prevDimension+prevPos > 主世界 spawn)。
     * 回退态无效时降级到主世界出生点并记 Major 日志 (14.6)。同时清玩家矿山运行态。
     */
    private void teleportToFallback(ServerPlayer player) {
        Optional<IMiningPlayerData> dataOpt = MiningCapabilities.get(player);
        ServerLevel targetLevel;
        BlockPos targetPos;
        if (dataOpt.isPresent() && dataOpt.get().hasFallback()) {
            IMiningPlayerData data = dataOpt.get();
            ResourceKey<Level> dim = data.prevDimension();
            ServerLevel resolved = player.server.getLevel(dim);
            if (resolved != null) {
                targetLevel = resolved;
                targetPos = data.prevPos();
            } else {
                // 回退维度已失效 (被删等): 降级主世界 spawn, 记 Major (14.6)。
                LOGGER.warn("[miningdim] fallback dimension {} invalid for {}, using overworld spawn",
                        dim.location(), player.getGameProfile().getName());
                targetLevel = player.server.overworld();
                targetPos = targetLevel.getSharedSpawnPos();
            }
        } else {
            targetLevel = player.server.overworld();
            targetPos = targetLevel.getSharedSpawnPos();
        }
        player.teleportTo(targetLevel, targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5,
                player.getYRot(), player.getXRot());
        dataOpt.ifPresent(IMiningPlayerData::clearMiningState);
    }

    @Override
    public String name() {
        return "ResetSystem";
    }
}

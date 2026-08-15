package com.miningdim.entry;

import com.miningdim.chunk.ChunkServices;
import com.miningdim.chunk.IChunkTicketService;
import com.miningdim.core.Difficulty;
import com.miningdim.core.GenState;
import com.miningdim.core.InstanceState;
import com.miningdim.core.InstanceLimitException;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.RegionBox;
import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.job.miner.MinerLevelGate;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 入场流程编排 (设计文档 14.2 / 14.3, Critical 防虚空)。完整链路全程主线程编排:
 *  1 gateCheck 难度门控 (14.4) -> 2 snapshotFallback 写 Capability (14.2) -> 3 allocate 实例 ->
 *  4 awaitReady (生成中等待, 监听 genState) -> 5 force-load spawn 周边区块 (14.3) ->
 *  6 awaitChunksLoaded (确认 FULL 再传送, 防虚空) -> 7 经 ISpawnService.findSpawn 取安全出生点 (池 + 占用 TTL) ->
 *  8 主线程 teleportTo -> 9 onPlayerEnter (refCount++) -> 10 写 currentInstanceId ->
 *  11 initDanger / spawnFreeze -> 12 active=true。
 *
 * 步骤 4-6 是防掉虚空核心: 绝不在 genState 非 READY 或区块未 FULL 时传送。等待与轮询经 {@link #tick}
 * 每服务端 tick 推进, 不阻塞主线程; 超时/竞态 (RESETTING/断线) 回滚 force ticket 并提示。
 *
 * 异常契约 (C9): allocate 超上限抛 {@link InstanceLimitException}, 在本类 requestEnter 的 future 回调里
 * 兜底转玩家提示 (本类即入口层之一)。其余世界写异常自然冒泡。
 */
public final class EntryGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/entry");

    // ---- 14.4 等级门槛: 改为委派矿工职业等级 (MinerLevelGate), 不再用原版经验等级常量 (见 gateCheck)。 ----

    // ---- 14.3 force-load 等待 (PENDING; 取设计文档建议) ----
    /** awaitChunksLoaded 最大等待 tick (默认 200 = 10s)。 */
    private static final int CHUNK_WAIT_TIMEOUT_TICKS = 200;
    /** spawn 周边强加载半径 (区块, 14.3 取 3x3 起步; 至少覆盖中心列所在区块)。 */
    private static final int SPAWN_FORCE_RADIUS_CHUNKS = 1;

    // ---- 11.7 出生冻结 ----
    private static final int SPAWN_FREEZE_TICKS = 200;

    /** 入场推进阶段 (14.2 步骤 4/6)。 */
    private enum Phase {
        AWAIT_READY,
        AWAIT_CHUNKS
    }

    /** 一次进行中的入场任务 (玩家 + 目标实例 + 阶段 + 计时)。 */
    private static final class PendingEnter {
        final UUID playerId;
        final long instanceId;
        final Difficulty difficulty;
        Phase phase;
        int waitedTicks;
        Set<Long> forcedChunks;

        PendingEnter(UUID playerId, long instanceId, Difficulty difficulty) {
            this.playerId = playerId;
            this.instanceId = instanceId;
            this.difficulty = difficulty;
            this.phase = Phase.AWAIT_READY;
            this.waitedTicks = 0;
        }
    }

    private final MinecraftServer server;
    private final List<PendingEnter> pending = new ArrayList<>();

    public EntryGateway(MinecraftServer server) {
        if (server == null) {
            throw new IllegalArgumentException("server must not be null");
        }
        this.server = server;
    }

    /**
     * 玩家发起进入 (命令/GUI/传送门入口统一汇聚到此)。同步部分: 门控 -> 写回退态 -> allocate;
     * allocate 的 future 在主线程回调里登记 PendingEnter, 由 tick 推进后续 force-load 与传送。
     *
     * @param player     发起者
     * @param difficulty 目标难度
     * @param reseed     true=换新图入口; 本次入场分配复用 InstanceManager 既有语义, reseed 仅透传日志与未来扩展。
     */
    public void requestEnter(ServerPlayer player, Difficulty difficulty, boolean reseed) {
        GateResult gate = gateCheck(player, difficulty);
        if (!gate.passed()) {
            MiningServices.network().sendTeleportResult(
                    player, com.miningdim.core.IMiningNetwork.TeleportResult.REJECTED_FULL,
                    -1L, -1, gate.reasonKey());
            return;
        }

        IMiningPlayerData data = MiningCapabilities.get(player).orElseThrow(
                () -> new IllegalStateException("player has no mining capability: " + player.getGameProfile().getName()));
        // 已在某实例内则拒绝重复进入 (避免叠加 currentInstanceId / 回退态被覆盖)。
        if (data.currentInstanceId() != IMiningPlayerData.NO_INSTANCE) {
            MiningServices.network().sendTeleportResult(
                    player, com.miningdim.core.IMiningNetwork.TeleportResult.ERROR,
                    data.currentInstanceId(), -1, "message.miningdim.enter.already_inside");
            return;
        }

        // 14.2 步骤 2: 记录进入前现场到 Capability。
        data.snapshotFallback(player.level().dimension(), player.blockPosition(),
                player.gameMode.getGameModeForPlayer());

        // 14.2 步骤 3: 分配实例。allocate 可能立即异常完成 (REJECT 超上限) 或入队 (QUEUE)。
        UUID playerId = player.getUUID();
        MiningServices.instanceManager().allocate(player, difficulty).whenComplete((inst, err) ->
                // 回到主线程登记 (CompletableFuture 可能在工作线程兑现)。
                server.execute(() -> onAllocateComplete(playerId, difficulty, reseed, inst, err)));
    }

    /** allocate 兑现回调 (主线程): 异常 -> 提示; 成功 -> 登记 PendingEnter 进入 force-load 推进。 */
    private void onAllocateComplete(UUID playerId, Difficulty difficulty, boolean reseed,
                                    InstanceState inst, Throwable err) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (err != null) {
            // C9: 入口层兜底捕获 allocate 异常转提示, 不让其逃逸吞没。
            Throwable cause = (err instanceof java.util.concurrent.CompletionException && err.getCause() != null)
                    ? err.getCause() : err;
            if (player != null) {
                if (cause instanceof InstanceLimitException limit) {
                    com.miningdim.core.IMiningNetwork.TeleportResult res =
                            limit.reason() == InstanceLimitException.Reason.GLOBAL_CAP
                                    ? com.miningdim.core.IMiningNetwork.TeleportResult.REJECTED_FULL
                                    : com.miningdim.core.IMiningNetwork.TeleportResult.QUEUED;
                    MiningServices.network().sendTeleportResult(player, res, -1L, -1,
                            "message.miningdim.enter.cap_" + limit.reason().name().toLowerCase());
                } else {
                    MiningServices.network().sendTeleportResult(player,
                            com.miningdim.core.IMiningNetwork.TeleportResult.ERROR, -1L, -1,
                            "message.miningdim.enter.alloc_failed");
                    LOGGER.warn("[miningdim] allocate failed for {}: {}", playerId, cause.toString());
                }
            }
            return;
        }
        if (player == null) {
            // 玩家在分配期间断线 (14.3 竞态): 不登记, 不改 playerSet。
            return;
        }
        LOGGER.debug("[miningdim] player {} allocated instance {} (reseed={})", playerId, inst.instanceId(), reseed);
        pending.add(new PendingEnter(playerId, inst.instanceId(), difficulty));
    }

    /** 每服务端 tick 推进所有进行中的入场 (14.2 步骤 4-8)。主线程。 */
    public void tick() {
        if (pending.isEmpty()) {
            return;
        }
        Iterator<PendingEnter> it = pending.iterator();
        while (it.hasNext()) {
            PendingEnter pe = it.next();
            if (advance(pe)) {
                it.remove();
            }
        }
    }

    /** 推进单个入场; 返回 true 表示该任务终结 (成功传送 / 失败回滚), 应从队列移除。 */
    private boolean advance(PendingEnter pe) {
        ServerPlayer player = server.getPlayerList().getPlayer(pe.playerId);
        if (player == null) {
            // 14.3 竞态: 等待期间玩家断线, 取消入场, 撤 force ticket。
            rollback(pe);
            return true;
        }
        InstanceState inst = MiningServices.instanceManager().byId(pe.instanceId).orElse(null);
        if (inst == null) {
            // 14.3 竞态: 实例被 GC/重置销毁。
            notifyAndRollback(pe, player,
                    com.miningdim.core.IMiningNetwork.TeleportResult.REJECTED_GENERATING,
                    "message.miningdim.enter.instance_gone");
            return true;
        }
        // 14.3 竞态: 等待期间实例进入 RESETTING (被运维重置), 中止入场。
        if (inst.genState() == GenState.RESETTING) {
            notifyAndRollback(pe, player,
                    com.miningdim.core.IMiningNetwork.TeleportResult.REJECTED_GENERATING,
                    "message.miningdim.enter.resetting");
            return true;
        }

        pe.waitedTicks++;

        switch (pe.phase) {
            case AWAIT_READY -> {
                if (!inst.genState().isEnterable()) {
                    if (pe.waitedTicks > CHUNK_WAIT_TIMEOUT_TICKS) {
                        notifyAndRollback(pe, player,
                                com.miningdim.core.IMiningNetwork.TeleportResult.REJECTED_GENERATING,
                                "message.miningdim.enter.gen_timeout");
                        return true;
                    }
                    return false; // 继续等待生成完成
                }
                // 生成就绪: 申请 spawn 周边 ticking force-load, 进入区块就绪等待。
                if (!ChunkServices.isReady()) {
                    return false; // 维度尚未绑定 (极早期), 下 tick 重试
                }
                IChunkTicketService tickets = ChunkServices.ticketService();
                BlockPos center = regionCenter(inst);
                pe.forcedChunks = tickets.chunksAround(center, SPAWN_FORCE_RADIUS_CHUNKS);
                tickets.ensureTicking(inst, pe.forcedChunks);
                pe.phase = Phase.AWAIT_CHUNKS;
                pe.waitedTicks = 0;
                return false;
            }
            case AWAIT_CHUNKS -> {
                IChunkTicketService tickets = ChunkServices.ticketService();
                if (!tickets.areChunksLoaded(pe.forcedChunks)) {
                    if (pe.waitedTicks > CHUNK_WAIT_TIMEOUT_TICKS) {
                        notifyAndRollback(pe, player,
                                com.miningdim.core.IMiningNetwork.TeleportResult.ERROR,
                                "message.miningdim.enter.chunk_timeout");
                        return true;
                    }
                    return false; // 继续等待区块 FULL
                }
                // 区块就绪: 解析安全出生点并传送 (14.2 步骤 7-12)。
                completeEnter(pe, player, inst);
                return true;
            }
            default -> {
                return true;
            }
        }
    }

    /** 14.2 步骤 7-12: 解析出生点 -> 传送 -> 登记 refCount -> 写 Capability -> 初始化 danger/freeze。 */
    private void completeEnter(PendingEnter pe, ServerPlayer player, InstanceState inst) {
        ServerLevel miningLevel = ChunkServices.ticketService().level();
        BlockPos spawn = MiningServices.spawnService().findSpawn(miningLevel, inst);

        // 必须在传送【之前】取写 Capability: 跨维度 teleportTo 会令 Forge 在同 tick 内暂时失效玩家 capability 的
        // LazyOptional (实测崩因: 传送后立刻 get() 返 empty -> orElseThrow 抛 IllegalStateException -> 逃逸 onServerTick
        // 令主线程 tick 崩 -> 触发关服 -> 关服又卡死 60s 被 ServerHangWatchdog 判崩, 同时是僵尸进程根因)。
        // 故 currentInstanceId/danger/spawnFreeze 在传送前写 (cap 此刻有效), 传送置于最后。
        IMiningPlayerData data = MiningCapabilities.get(player).orElseThrow(
                () -> new IllegalStateException("player lost mining capability mid-enter: " + pe.playerId));
        data.setCurrentInstanceId(inst.instanceId());
        // 11.7 出生冻结 + danger 初始化 (entry 仅置初值, 第十章评估据此钳制)。
        data.setDanger(0.0f);
        data.setSpawnFreezeUntil(miningLevel.getGameTime() + SPAWN_FREEZE_TICKS);

        // refCount++ 与 active 由 InstanceManager 统一维护 (12.6)。
        MiningServices.instanceManager().onPlayerEnter(inst.instanceId(), player);

        // 主线程跨维度传送置于最后 (区块已 FULL 强加载, 不会掉虚空); cap 已在传送前写好, 不受传送期失效影响。
        player.teleportTo(miningLevel, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                player.getYRot(), player.getXRot());

        MiningServices.network().sendTeleportResult(player,
                com.miningdim.core.IMiningNetwork.TeleportResult.SUCCESS, inst.instanceId(), -1, null);
        // R5: 进入成功后给玩家发一条聊天提示 (难度名 + /mining leave 退出指引), translatable。
        player.sendSystemMessage(Component.translatable("message.miningdim.enter.entered_hint",
                Component.translatable("difficulty.miningdim." + inst.difficulty().configName())));
        LOGGER.info("[miningdim] player {} entered instance {} at {}", pe.playerId, inst.instanceId(), spawn);
    }

    /** region 几何中心 (XZ), Y 取 region 全高上界附近, 供 force-load 圆心。 */
    private BlockPos regionCenter(InstanceState inst) {
        RegionBox box = inst.regionBox();
        return new BlockPos(box.originX() + box.sizeX() / 2,
                MiningConstants.REGION_FULL_MAX_WORLD_Y,
                box.originZ() + box.sizeZ() / 2);
    }

    /**
     * 14.4 难度门控: 按矿工职业等级门槛 (Easy L1 / Medium L4 / Hard L8, 见 {@link MinerLevelGate})。
     * 集成阶段裁决 (Miner_Job_DesignSpec 第八章): 门槛口径从原版经验等级 (experienceLevel) 改为矿工职业等级,
     * 经职业框架门面 {@link com.miningdim.job.JobServices#jobService()} 读取; 数值表权威在 MinerLevelGate。
     */
    private GateResult gateCheck(ServerPlayer player, Difficulty difficulty) {
        int minerLevel = JobServices.jobService().level(player, JobId.MINER);
        return MinerLevelGate.canEnter(minerLevel, difficulty) ? GateResult.PASS : GateResult.LEVEL_TOO_LOW;
    }

    /** 失败回滚: 撤 force ticket (若已申请)。playerSet 此前未 add, 无需 remove (14.3 竞态表)。 */
    private void rollback(PendingEnter pe) {
        if (pe.forcedChunks != null && ChunkServices.isReady()) {
            ChunkServices.ticketService().releaseAll(pe.instanceId);
        }
    }

    private void notifyAndRollback(PendingEnter pe, ServerPlayer player,
                                   com.miningdim.core.IMiningNetwork.TeleportResult result, String reasonKey) {
        rollback(pe);
        MiningServices.network().sendTeleportResult(player, result, pe.instanceId, -1, reasonKey);
        player.sendSystemMessage(Component.translatable(reasonKey));
    }
}

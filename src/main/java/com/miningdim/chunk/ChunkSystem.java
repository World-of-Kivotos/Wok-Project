package com.miningdim.chunk;

import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.Subsystem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 区块强加载子系统入口 (设计文档 19.1 / 12.6 / 12.7, R5)。在矿山维度就绪后创建 {@link ChunkTicketManager}
 * 并注入 {@link ChunkServices}, 每服务端 tick 末驱动:
 *  1. 活跃实例 (有人在场) 的滑动 ticket 窗口刷新 (随玩家移动维护以其为心的加载窗口);
 *  2. 空置实例的 ticket 降级 (仅加载不 tick) 与 TTL 到期卸载释放。
 *
 * 不提供 core 门面 (chunk-ticket 非 core 契约): entry/reset 经 {@link ChunkServices} 取用本子系统能力。
 * 本子系统不向 MiningServices 注册任何 core 门面。
 *
 * 线程: 全部逻辑在 ServerTickEvent (主线程) 内执行, 滑动窗口刷新有节流, 避免每 tick 全实例扫描开销。
 */
public final class ChunkSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/chunk");

    /** 滑动窗口刷新节流: 玩家跨区块边界才会变化, 每 N tick 轮询一次足够且省开销 (19.1)。 */
    private static final int WINDOW_REFRESH_INTERVAL_TICKS = 10;

    private MinecraftServer server;
    private ChunkTicketManager ticketManager;

    /**
     * ticket TTL 独立于 InstanceState.lastEmptyTick 的空置起始时刻记录 (F031)。R1 固定难度实例
     * 刻意让 InstanceManager 把 lastEmptyTick 永久保持 -1 以豁免实例 GC 销毁 (onPlayerLeave 不写),
     * 若 ticket TTL 复用同一个字段, releaseAll 分支对这三个实例永远不可达 (emptySince < 0 恒真)。
     * 这里另起一份只服务于 ticket 释放的计时表, 与"实例是否该被 GC 销毁"完全解耦。
     * 线程契约同类: 仅服务端主线程 (ServerTickEvent) 读写, 无需并发容器。
     */
    private final Map<Long, Long> ticketEmptySinceTick = new HashMap<>();

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        forgeBus.register(this);
        // F031 复核修正 #1/#3: 落盘的历史强加载票只能在 Forge reinstatePersistentChunks 把它们重新装回
        // 内存之前拦截清除, 唯一入口是 LoadingValidationCallback, 且 Forge 要求必须在 FMLCommonSetupEvent
        // 的 enqueueWork 内注册 (见 ForgeChunkManager.setForcedChunkLoadingCallback 文档)。清账逻辑见
        // ChunkTicketManager.purgeStalePersistentTickets 类注释。
        modBus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(() ->
                ForgeChunkManager.setForcedChunkLoadingCallback(
                        MiningConstants.MODID, ChunkTicketManager::purgeStalePersistentTickets)));
    }

    /**
     * 矿山维度在 ServerStartedEvent 时一定已加载 (数据包维度随服务端启动注册)。此处取得其 ServerLevel,
     * 构造 ticket 管理器并注入定位器, 供 entry/reset 使用。
     */
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        this.server = event.getServer();
        ServerLevel miningLevel = server.getLevel(MiningConstants.MINING_LEVEL);
        if (miningLevel == null) {
            // 维度 JSON 缺失或加载失败属硬故障, 不可静默: 抛出暴露 datapack/注册问题 (C9)。
            throw new IllegalStateException(
                    "Mining dimension " + MiningConstants.MINING_LEVEL.location() + " not present at server start");
        }
        this.ticketManager = new ChunkTicketManager(miningLevel);
        ChunkServices.registerTicketService(this.ticketManager);
        LOGGER.info("[miningdim] chunk ticket manager bound to {}", MiningConstants.MINING_LEVEL.location());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // 关服时释放本 mod 持有的全部 ticket, 避免 ForcedChunksSavedData 残留 owner (R5)。
        if (ticketManager != null) {
            for (InstanceState inst : MiningServices.instanceManager().snapshot()) {
                ticketManager.releaseAll(inst.instanceId());
            }
        }
        // 清空 ticket TTL 计时表, 避免残留的空置起始时刻串到下一个存档 (换存档后实例 id 语义不同)。
        ticketEmptySinceTick.clear();
        ChunkServices.clear();
        this.ticketManager = null;
        this.server = null;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ticketManager == null || server == null) {
            return;
        }
        long now = server.overworld().getGameTime();
        boolean refreshWindows = (now % WINDOW_REFRESH_INTERVAL_TICKS) == 0L;
        long ttlTicks = (long) MiningServices.config().emptyInstanceTtlSeconds() * 20L;

        MiningServices.instanceManager().forEach(inst -> tickInstance(inst, now, refreshWindows, ttlTicks));
    }

    /** 单实例的 ticket 维护: 活跃刷新窗口, 空置降级与 TTL 释放。 */
    private void tickInstance(InstanceState inst, long now, boolean refreshWindows, long ttlTicks) {
        long instanceId = inst.instanceId();
        if (inst.active() && !inst.playerSet().isEmpty()) {
            // 有人在场即重新计时: 下次翻空要从 0 重新起算 TTL, 不能延续上一次空置期的旧计时。
            ticketEmptySinceTick.remove(instanceId);
            if (refreshWindows) {
                ticketManager.refreshWindow(inst, onlinePlayersIn(inst));
            }
            return;
        }

        // 空置实例: 先降级为仅加载不 tick (短暂往返复用), TTL 到期再彻底释放 (19.1 / 12.6 / 12.7)。
        // TTL 起算点用本类自维护的 ticketEmptySinceTick, 不用 InstanceState.lastEmptyTick: R1 固定
        // 难度实例刻意让该字段永久保持 -1 以豁免实例 GC (见字段注释), 若复用会导致 releaseAll 恒不可达。
        if (!ticketManager.hasTickets(instanceId)) {
            ticketEmptySinceTick.remove(instanceId);
            return;
        }
        Long emptySince = ticketEmptySinceTick.get(instanceId);
        if (emptySince == null) {
            // 刚翻空那一 tick: 记录起点并先降级, TTL 从下个周期开始计。
            ticketEmptySinceTick.put(instanceId, now);
            ticketManager.demoteToLoadOnly(instanceId);
            return;
        }
        if (now - emptySince >= ttlTicks) {
            ticketManager.releaseAll(instanceId);
            ticketEmptySinceTick.remove(instanceId);
        } else {
            ticketManager.demoteToLoadOnly(instanceId);
        }
    }

    /** 取实例内当前在线的玩家实体 (playerSet 存 UUID, 离线者无实体不纳入窗口)。 */
    private List<ServerPlayer> onlinePlayersIn(InstanceState inst) {
        List<ServerPlayer> result = new ArrayList<>();
        for (UUID uuid : inst.playerSet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) {
                result.add(p);
            }
        }
        return result;
    }

    @Override
    public String name() {
        return "ChunkSystem";
    }
}

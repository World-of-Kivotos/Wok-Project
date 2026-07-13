package com.miningdim.chunk;

import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.Subsystem;
import net.minecraft.core.BlockPos;
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
import java.util.List;
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

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        forgeBus.register(this);
        // 世界加载时丢弃本 mod 全部持久化强加载孤儿票 (防旧存档迁移坑); 须在 FMLCommonSetupEvent 经 enqueueWork 注册。
        modBus.addListener(this::onCommonSetup);
    }

    /**
     * 注册强加载校验回调 (ForgeChunkManager.setForcedChunkLoadingCallback)。javadoc 明示此注册须在
     * FMLCommonSetupEvent 经 ParallelDispatchEvent.enqueueWork 完成; 回调在世界加载 reinstatePersistentChunks
     * 时被调用, 拿到本 mod 该维度的持久强加载票, 决定哪些作废。
     */
    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> ForgeChunkManager.setForcedChunkLoadingCallback(
                MiningConstants.MODID, ChunkSystem::dropAllForcedChunkTickets));
    }

    /**
     * 世界加载校验回调 (Forge LoadingValidationCallback.validateTickets): 丢弃本 mod 全部持久化强加载票, 令
     * 开机票据归零。回调只对本 mod 该维度的票据生效 (Forge 按 modId 分组下发 TicketHelper), 本 mod 票据只会
     * 出现在矿洞维度, 故不区分维度无条件全清 —— 取最简实现。
     *
     * 为什么全部丢弃是安全的: 本 mod 强加载票 (ChunkTicketManager 滑动窗口 / reset 期释放) 只在内存簿
     * ChunkTicketManager.byInstance 里跟踪, 该内存簿重启即空 —— 从 ForcedChunksSavedData 恢复出的持久票没有
     * 任何内存持有者, 全是无主孤儿 (更兼已部署的 1.0.6 存档里躺着最多 768 张判废预热票, 摘掉预热代码后无人释放)。
     * 玩家进场所需的 spawn 周边加载由 EntryGateway 在传送前经 ChunkTicketManager 重新铺 (14.3), 不依赖持久票,
     * 故开机把票据归零只清掉泄漏源, 不影响任何在用功能。
     *
     * CME: TicketHelper 内的 map 是 Forge 传入的本 mod 票据不可变快照, removeAllTickets 改的是底层 saveData 而非
     * 该快照, 故遍历 keySet 同时 remove 无并发修改冲突。本 mod 只用 block 票 (owner = region 原点 BlockPos),
     * entity 票一并防御性清空。
     */
    private static void dropAllForcedChunkTickets(ServerLevel level, ForgeChunkManager.TicketHelper ticketHelper) {
        int blockOwners = ticketHelper.getBlockTickets().size();
        int entityOwners = ticketHelper.getEntityTickets().size();
        if (blockOwners == 0 && entityOwners == 0) {
            return;
        }
        for (BlockPos owner : ticketHelper.getBlockTickets().keySet()) {
            ticketHelper.removeAllTickets(owner);
        }
        for (UUID owner : ticketHelper.getEntityTickets().keySet()) {
            ticketHelper.removeAllTickets(owner);
        }
        LOGGER.info("[miningdim] dropped orphan persistent forced-chunk tickets on load of {}: {} block owner(s), {} entity owner(s)",
                level.dimension().location(), blockOwners, entityOwners);
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
        if (inst.active() && !inst.playerSet().isEmpty()) {
            if (refreshWindows) {
                ticketManager.refreshWindow(inst, onlinePlayersIn(inst));
            }
            return;
        }

        // 空置实例: 先降级为仅加载不 tick (短暂往返复用), TTL 到期再彻底释放 (19.1 / 12.6 / 12.7)。
        if (!ticketManager.hasTickets(inst.instanceId())) {
            return;
        }
        long emptySince = inst.lastEmptyTick();
        if (emptySince < 0L) {
            // playerSet 空但 lastEmptyTick 尚未记录 (刚翻空那一 tick), 由 InstanceManager 在 onPlayerLeave 记录;
            // 这里先降级, 下个扫描周期 lastEmptyTick 已就绪再计 TTL。
            ticketManager.demoteToLoadOnly(inst.instanceId());
            return;
        }
        if (now - emptySince >= ttlTicks) {
            ticketManager.releaseAll(inst.instanceId());
        } else {
            ticketManager.demoteToLoadOnly(inst.instanceId());
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

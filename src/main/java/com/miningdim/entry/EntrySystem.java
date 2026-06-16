package com.miningdim.entry;

import com.miningdim.core.GenState;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.Subsystem;
import com.miningdim.reset.PendingEvacuations;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 入场子系统入口 (设计文档第十四章)。装配:
 *  - Capability 注册/挂载/复制 ({@link MiningCapabilities}, mod + forge 双总线);
 *  - 入场流程编排 ({@link EntryGateway}, ServerStartedEvent 创建, ServerTickEvent 推进);
 *  - /mining 命令树 ({@link MiningCommands}, RegisterCommandsEvent);
 *  - 玩家生命周期事件: 登录恢复 (14.6) 与全部离开路径汇聚 onPlayerLeave (12.6)。
 *
 * 本子系统不向 MiningServices 注册 core 门面 (入场无对外门面接口); 玩家 Capability 经 entry 包
 * {@link MiningCapabilities} / {@link IMiningPlayerData} 对外 (reset 子系统读其回退态)。
 *
 * 注入顺序约束: requestEnter 运行期 (玩家命令, 服务端启动后) 才取 instanceManager/network/spawn/config 服务,
 * 不在 register 当场取用, 故对主类 List<Subsystem> 顺序无前置依赖。
 */
public final class EntrySystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/entry");

    private MinecraftServer server;
    private EntryGateway gateway;

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        MiningCapabilities caps = new MiningCapabilities();
        // RegisterCapabilitiesEvent 在 modBus; AttachCapabilitiesEvent/Clone 在 forgeBus。
        modBus.register(caps);
        forgeBus.register(caps);
        forgeBus.register(this);
    }

    EntryGateway gateway() {
        return gateway;
    }

    // ---- 生命周期 ----

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        this.server = event.getServer();
        this.gateway = new EntryGateway(server);
        // R4: 把入口方块 -> 入场流程的触发器接入 entrance seam (entrance 包不 import entry 实现)。
        // 入口方块默认走非 reseed 入场 (常驻固定区域, 复用现有图; reseed 由 /mining reset 单独负责)。
        EntryGateway boundGateway = this.gateway;
        com.miningdim.entrance.EntranceHooks.bind(
                (player, difficulty) -> boundGateway.requestEnter(player, difficulty, false));
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        com.miningdim.entrance.EntranceHooks.unbind();
        this.gateway = null;
        this.server = null;
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        new MiningCommands(this).register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && gateway != null) {
            gateway.tick();
        }
    }

    // ---- 14.6 登录恢复 ----

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Optional<IMiningPlayerData> dataOpt = MiningCapabilities.get(player);
        if (dataOpt.isEmpty()) {
            return;
        }
        IMiningPlayerData data = dataOpt.get();
        long instanceId = data.currentInstanceId();
        if (instanceId == IMiningPlayerData.NO_INSTANCE) {
            return; // 上次不在矿山, 正常登录。
        }

        InstanceState inst = MiningServices.instanceManager().byId(instanceId).orElse(null);
        ResourceKey<Level> loginDim = player.level().dimension();

        // 情况 A (14.6): 被标记待撤离, 或实例已不存在/未就绪 -> 送回回退点。
        if (PendingEvacuations.isMarked(player.getUUID())
                || inst == null
                || !inst.genState().isEnterable()) {
            sendBackToFallback(player, data);
            PendingEvacuations.clear(player.getUUID());
            data.clearMiningState();
            return;
        }

        // 情况 B (14.6): 实例存活且落点仍在 region 内 -> 恢复在场。
        if (loginDim.equals(MiningConstants.MINING_LEVEL)
                && inst.regionBox().contains(player.blockPosition().getX(), player.blockPosition().getZ())) {
            MiningServices.instanceManager().onPlayerEnter(instanceId, player);
            return;
        }

        // 情况 C (14.6): 实例存活但落点异常 (不在 region 内) -> 送回回退点。
        sendBackToFallback(player, data);
        data.clearMiningState();
    }

    // ---- 12.6 离开路径统一汇聚 ----

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        // 断线/退出: 离开当前实例 (refCount--), 但保留 Capability 回退态以便重连 (12.6 表)。
        if (event.getEntity() instanceof ServerPlayer player) {
            leaveCurrentInstance(player, false);
        }
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        // 主动离开矿山维度: from==mining 时离开实例 (12.6 表)。
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (event.getFrom().equals(MiningConstants.MINING_LEVEL)
                && !event.getTo().equals(MiningConstants.MINING_LEVEL)) {
            leaveCurrentInstance(player, true);
        }
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        // 在矿山死亡后重生到他处: 若重生不在原实例 region 内则离开 (12.6 表)。
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Optional<IMiningPlayerData> dataOpt = MiningCapabilities.get(player);
        if (dataOpt.isEmpty()) {
            return;
        }
        long instanceId = dataOpt.get().currentInstanceId();
        if (instanceId == IMiningPlayerData.NO_INSTANCE) {
            return;
        }
        InstanceState inst = MiningServices.instanceManager().byId(instanceId).orElse(null);
        boolean stillInside = inst != null
                && player.level().dimension().equals(MiningConstants.MINING_LEVEL)
                && inst.regionBox().contains(player.blockPosition().getX(), player.blockPosition().getZ());
        if (!stillInside) {
            leaveCurrentInstance(player, true);
        }
    }

    // ---- /mining leave 委派 ----

    /**
     * 主动撤离回回退点 (14.1 leave): 读 Capability 回退态传送, 离开实例。
     * 返回 false 表示玩家本不在任何实例 (命令层据此提示)。
     */
    boolean leaveToFallback(ServerPlayer player) {
        Optional<IMiningPlayerData> dataOpt = MiningCapabilities.get(player);
        if (dataOpt.isEmpty() || dataOpt.get().currentInstanceId() == IMiningPlayerData.NO_INSTANCE) {
            return false;
        }
        IMiningPlayerData data = dataOpt.get();
        sendBackToFallback(player, data);
        leaveCurrentInstance(player, true);
        return true;
    }

    /** 离开当前实例并汇聚到 InstanceManager.onPlayerLeave (12.6)。clearCap=true 时清矿山运行态。 */
    private void leaveCurrentInstance(ServerPlayer player, boolean clearCap) {
        Optional<IMiningPlayerData> dataOpt = MiningCapabilities.get(player);
        if (dataOpt.isEmpty()) {
            return;
        }
        long instanceId = dataOpt.get().currentInstanceId();
        if (instanceId == IMiningPlayerData.NO_INSTANCE) {
            return;
        }
        // byId 可能为空 (实例已销毁): 仍清玩家态, 但无 instance 可 onPlayerLeave。
        MiningServices.instanceManager().byId(instanceId).ifPresent(
                inst -> MiningServices.instanceManager().onPlayerLeave(instanceId, player));
        if (clearCap) {
            dataOpt.get().clearMiningState();
        }
    }

    /** 把玩家传送回 Capability 回退点; 回退失效降级主世界 spawn (14.6 sendBackToFallback)。 */
    private void sendBackToFallback(ServerPlayer player, IMiningPlayerData data) {
        ServerLevel targetLevel;
        BlockPos targetPos;
        if (data.hasFallback()) {
            ServerLevel resolved = player.server.getLevel(data.prevDimension());
            if (resolved != null) {
                targetLevel = resolved;
                targetPos = data.prevPos();
            } else {
                LOGGER.warn("[miningdim] fallback dimension {} invalid for {}, overworld spawn",
                        data.prevDimension().location(), player.getGameProfile().getName());
                targetLevel = player.server.overworld();
                targetPos = targetLevel.getSharedSpawnPos();
            }
        } else {
            targetLevel = player.server.overworld();
            targetPos = targetLevel.getSharedSpawnPos();
        }
        player.teleportTo(targetLevel, targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5,
                player.getYRot(), player.getXRot());
    }

    @Override
    public String name() {
        return "EntrySystem";
    }
}

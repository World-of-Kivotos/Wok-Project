package com.miningdim.persistence;

import com.miningdim.core.GenState;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.PortalInfo;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PlayerMiningData 的 Forge 总线事件接线 (设计文档 12.5 / 14.6)。挂在 forge bus (MinecraftForge.EVENT_BUS),
 * 订阅由 InstanceSystem.register 完成。三类职责:
 *   1. AttachCapabilitiesEvent&lt;Entity&gt;: 仅对 Player attach PlayerMiningDataProvider。
 *   2. PlayerEvent.Clone: 死亡重生/换维度时从原实体 (reviveCaps 后) 深拷贝能力, 读毕 invalidateCaps。
 *   3. PlayerEvent.PlayerLoggedInEvent: 按 14.6 决策恢复在场或送回回退点。
 *
 * 登出/换维度/重生等"离开实例"路径不在本类: 它们统一汇聚到 InstanceManager 的离开处理 (12.6),
 * 由 instance 子系统订阅, 避免持久层与生命周期管理职责交叉。
 */
public final class PlayerMiningEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/PlayerMiningEvents");

    /** attach 时给 Player 挂上 Provider 并注册其 invalidate 监听 (能力随实体卸载释放)。 */
    @SubscribeEvent
    public void onAttachCapabilities(AttachCapabilitiesEvent<Entity> event) {
        if (!(event.getObject() instanceof net.minecraft.world.entity.player.Player)) {
            return;
        }
        PlayerMiningDataProvider provider = new PlayerMiningDataProvider();
        event.addCapability(PlayerMiningCapability.ID, provider);
        event.addListener(provider::invalidate);
    }

    /**
     * 死亡重生与换维度都触发 Clone。1.20.1: 原实体 caps 在 Clone 时已 invalidate, 必须 reviveCaps()
     * 临时恢复后读取, 读毕 invalidateCaps() (12.5 强制写法)。本期统一全量复制 (含 currentInstanceId/danger);
     * wasDeath 时对 currentInstanceId/danger 的清零策略 (D7) 待死亡子系统按 14.6 接入后覆盖, 此处不擅自清零,
     * 以免在策略未定时丢失"死亡瞬间仍属某实例"的事实 (该事实是死亡踢出/danger 处置的输入)。
     */
    @SubscribeEvent
    public void onClone(PlayerEvent.Clone event) {
        ServerPlayer original = (ServerPlayer) event.getOriginal();
        ServerPlayer clone = (ServerPlayer) event.getEntity();

        original.reviveCaps();
        try {
            PlayerMiningData from = original.getCapability(PlayerMiningCapability.CAPABILITY).orElse(null);
            PlayerMiningData to = clone.getCapability(PlayerMiningCapability.CAPABILITY).orElse(null);
            if (from != null && to != null) {
                to.copyFrom(from);
            }
        } finally {
            original.invalidateCaps();
        }
    }

    /**
     * 登录恢复 (14.6)。在主线程触发, 故传送/在场恢复直接执行。决策:
     *   - 不在矿山 (NO_INSTANCE): 正常登录, 不动。
     *   - 待撤离 / 实例已不存在 / 非 READY: 送回回退点并清状态 (情况 A)。
     *   - 实例存活 && 落点在 region 内: 重新计入 playerSet、恢复在场 (情况 B)。
     *   - 实例存活但落点异常: 送回回退点 (情况 C)。
     */
    @SubscribeEvent
    public void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        PlayerMiningData cap = player.getCapability(PlayerMiningCapability.CAPABILITY).orElse(null);
        if (cap == null || !cap.inMiningInstance()) {
            return;
        }

        long instanceId = cap.currentInstanceId();
        InstanceState inst = MiningServices.instanceManager().byId(instanceId).orElse(null);

        // 情况 A: 待撤离 / 实例丢失 / 未就绪
        if (cap.pendingEvacuation() || inst == null || inst.genState() != GenState.READY) {
            sendBackToFallback(player, cap);
            cap.setPendingEvacuation(false);
            cap.setCurrentInstanceId(PlayerMiningData.NO_INSTANCE);
            return;
        }

        // 情况 B: 实例存活且登录落点仍在该 region 内 (维度须为矿山)
        boolean inMiningDim = player.level().dimension().equals(MiningConstants.MINING_LEVEL);
        BlockPos pos = player.blockPosition();
        if (inMiningDim && inst.regionBox().contains(pos.getX(), pos.getZ())) {
            MiningServices.instanceManager().onPlayerEnter(instanceId, player);
            return;
        }

        // 情况 C: 实例存活但落点异常 (不在 region 内或不在矿山维度)
        sendBackToFallback(player, cap);
        cap.setCurrentInstanceId(PlayerMiningData.NO_INSTANCE);
    }

    /**
     * 用 Capability 的 prevDimension/prevPos/prevGameMode 把玩家送回回退点 (14.6)。
     * 回退态无效 (维度被删/从未记录) 时降级到主世界出生点并记 Major 日志, 不静默掩盖。
     */
    private void sendBackToFallback(ServerPlayer player, PlayerMiningData cap) {
        MinecraftServer server = player.server;
        ServerLevel target = null;
        BlockPos targetPos = null;

        if (cap.hasFallback()) {
            ResourceKey<Level> dim = cap.prevDimension();
            ServerLevel lvl = server.getLevel(dim);
            if (lvl != null) {
                target = lvl;
                targetPos = cap.prevPos();
            } else {
                LOGGER.warn("[miningdim] fallback dimension {} no longer exists for player {}; degrading to overworld spawn",
                        dim.location(), player.getGameProfile().getName());
            }
        }

        if (target == null) {
            ServerLevel overworld = server.overworld();
            target = overworld;
            targetPos = overworld.getSharedSpawnPos();
        }

        teleportTo(player, target, targetPos);

        if (cap.prevGameModeId() >= 0) {
            net.minecraft.world.level.GameType mode = net.minecraft.world.level.GameType.byId(cap.prevGameModeId());
            player.setGameMode(mode);
        }
        cap.clearFallback();
    }

    /** 跨维度/同维度传送到指定坐标 (主线程, 居中到方块)。 */
    private void teleportTo(ServerPlayer player, ServerLevel level, BlockPos pos) {
        Vec3 center = new Vec3(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
        if (player.level() == level) {
            player.teleportTo(center.x, center.y, center.z);
        } else {
            player.changeDimension(level, new SimpleTeleporter(center, player.getYRot(), player.getXRot()));
        }
    }

    /**
     * 最小 ITeleporter: 把玩家放到指定坐标, 不造门、不搜锚点 (回退传送只需放置)。
     * 1.20.1 ITeleporter.placeEntity 默认会调 repositionEntity(false), 我们直接返回构造好的 PortalInfo。
     */
    private static final class SimpleTeleporter implements net.minecraftforge.common.util.ITeleporter {
        private final Vec3 pos;
        private final float yRot;
        private final float xRot;

        SimpleTeleporter(Vec3 pos, float yRot, float xRot) {
            this.pos = pos;
            this.yRot = yRot;
            this.xRot = xRot;
        }

        @Override
        public PortalInfo getPortalInfo(Entity entity, ServerLevel destWorld,
                                        java.util.function.Function<ServerLevel, PortalInfo> defaultPortalInfo) {
            return new PortalInfo(pos, Vec3.ZERO, yRot, xRot);
        }

        @Override
        public boolean playTeleportSound(ServerPlayer player, ServerLevel sourceWorld, ServerLevel destWorld) {
            return false;
        }
    }
}

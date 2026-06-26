package com.miningdim.marriage;

import com.miningdim.core.MiningConstants;
import com.miningdim.entry.IMiningPlayerData;
import com.miningdim.entry.MiningCapabilities;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 传送到伴侣的服务端蓄力状态机 (结婚系统 spec 第五章)。进程级单例, 仅服务端主线程访问 (戒指交互回调 + ServerTickEvent
 * 推进, 均主线程)。发起方长按结婚戒指开始蓄力 -> 每 tick 校验"双方静止 + 未受伤 + 双方在线同维度" -> 满 T 即
 * {@code teleportTo} 到伴侣身边 + 进 CD; 任一方移动/潜行/受伤即取消 (天然战斗锁: 挨枪传不掉)。
 *
 * 战斗锁红线 (spec 第五章): 等级 1..5 只缩短蓄力 T / CD, 绝不取消"双方不动 + 可打断"。受伤打断由
 * {@link #onHurt} 在受击事件接入; 移动/潜行打断由每 tick 比对锚点位置 + 潜行态。
 *
 * 知情同意 (spec 第五章): 伴侣收到 actionbar 提示"伴侣正在传送, 保持静止接受/移动取消"; 不弹确认框 —— 伴侣保持
 * 静止即同意, 走两步或潜行即拒绝 (会令发起方蓄力取消)。
 *
 * 跨维度约束: 本系统拒绝把玩家直接传进矿洞维度实例 (会绕过 EntryGateway 的实例引用计数/重入闸/落点安全, 比单纯
 * 重入更严重)。故伴侣在矿洞维度 (currentInstanceId 有效 / 维度为 miningdim:mining) 时蓄力直接拒绝, 提示走
 * /mining enter (该流程内含 AbuseGuard.checkReentryGate); 同维度世界内传送照常。
 */
public final class MarriageTeleport {

    /** 一次进行中的蓄力 (发起方视角)。 */
    private static final class Channel {
        final UUID initiator;
        final UUID partner;
        final int totalTicks;
        int elapsedTicks;
        // 双方蓄力开始时的锚点 (位移打断判定基准)。
        final double initX;
        final double initY;
        final double initZ;
        final double partnerX;
        final double partnerY;
        final double partnerZ;

        Channel(UUID initiator, UUID partner, int totalTicks,
                ServerPlayer initiatorPlayer, ServerPlayer partnerPlayer) {
            this.initiator = initiator;
            this.partner = partner;
            this.totalTicks = totalTicks;
            this.elapsedTicks = 0;
            this.initX = initiatorPlayer.getX();
            this.initY = initiatorPlayer.getY();
            this.initZ = initiatorPlayer.getZ();
            this.partnerX = partnerPlayer.getX();
            this.partnerY = partnerPlayer.getY();
            this.partnerZ = partnerPlayer.getZ();
        }
    }

    /** 蓄力开始/打断的原因码 (命令/交互层据此选文案; 不吞业务结果)。 */
    public enum StartResult {
        STARTED,
        ALREADY_CHANNELING,
        ON_COOLDOWN,
        SPOUSE_OFFLINE,
        DIFFERENT_DIMENSION,
        SPOUSE_IN_MINING_DIM,
        NOT_MARRIED,
        NO_SPOUSE_RESOLVED
    }

    /** 位移打断阈值平方 (格^2; 任一方水平+垂直位移超过 0.35 格即判移动, 容忍站桩微抖)。 */
    private static final double MOVE_THRESHOLD_SQR = 0.35D * 0.35D;

    /** initiatorUUID -> 进行中的蓄力。 */
    private final Map<UUID, Channel> channels = new HashMap<>();

    /** playerUUID -> 该玩家下次可发起传送的最早 gameTime (CD; 发起方进 CD)。 */
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();

    /**
     * 尝试开始一次传送蓄力 (发起方长按结婚戒指触发)。校验: 已婚 + 配偶在线 + 同维度 + 不在 CD + 配偶不在矿洞维度。
     * 通过则登记蓄力并给双方提示 (发起方进度提示 + 伴侣知情同意 actionbar)。
     *
     * @param initiator 发起方
     * @param overworld overworld (取 gameTime + PlayerList)
     * @return 开始结果 (STARTED 或失败原因)
     */
    public StartResult tryStart(ServerPlayer initiator, ServerLevel overworld) {
        long now = overworld.getGameTime();
        UUID initiatorId = initiator.getUUID();

        if (channels.containsKey(initiatorId)) {
            return StartResult.ALREADY_CHANNELING;
        }
        Long cdUntil = cooldownUntil.get(initiatorId);
        if (cdUntil != null && now < cdUntil) {
            return StartResult.ON_COOLDOWN;
        }

        IMiningPlayerData data = MiningCapabilities.get(initiator).orElse(null);
        if (data == null || data.marriageId() == IMiningPlayerData.NO_MARRIAGE) {
            return StartResult.NOT_MARRIED;
        }
        UUID spouseId = data.spouseUUID();
        if (spouseId == null) {
            return StartResult.NO_SPOUSE_RESOLVED;
        }
        ServerPlayer spouse = overworld.getServer().getPlayerList().getPlayer(spouseId);
        if (spouse == null) {
            return StartResult.SPOUSE_OFFLINE;
        }
        // 配偶在矿洞维度: 拒绝直传 (走 /mining enter, 见类注释跨维度约束)。
        if (isInMiningDimension(spouse)) {
            return StartResult.SPOUSE_IN_MINING_DIM;
        }
        if (spouse.level() != initiator.level()) {
            return StartResult.DIFFERENT_DIMENSION;
        }

        MarriageRegistry registry = MarriageRegistry.get(overworld);
        MarriageState state = registry.byId(data.marriageId());
        if (state == null) {
            return StartResult.NOT_MARRIED;
        }
        int level = MarriageTuning.teleportLevel(state.marriedSinceTick(), now);
        int chargeTicks = MarriageTuning.teleportChargeTicks(level);

        channels.put(initiatorId, new Channel(initiatorId, spouseId, chargeTicks, initiator, spouse));
        // 发起方进度提示 + 伴侣知情同意 actionbar (spec 第五章)。
        initiator.displayClientMessage(Component.translatable(
                "message.miningdim.marriage.teleport.charging", chargeTicks / 20), true);
        spouse.displayClientMessage(Component.translatable(
                "message.miningdim.marriage.teleport.partner_notice",
                initiator.getGameProfile().getName()), true);
        return StartResult.STARTED;
    }

    /**
     * 每 tick 推进所有进行中的蓄力 (ServerTickEvent END 调)。逐条校验双方静止/未潜行/在线同维度; 满 T 即传送 + CD,
     * 任一校验失败即取消并双方提示。
     *
     * @param overworld overworld (取 gameTime + PlayerList)
     */
    public void tick(ServerLevel overworld) {
        if (channels.isEmpty()) {
            return;
        }
        long now = overworld.getGameTime();
        Iterator<Map.Entry<UUID, Channel>> it = channels.entrySet().iterator();
        while (it.hasNext()) {
            Channel ch = it.next().getValue();
            ServerPlayer initiator = overworld.getServer().getPlayerList().getPlayer(ch.initiator);
            ServerPlayer partner = overworld.getServer().getPlayerList().getPlayer(ch.partner);

            // 任一方离线: 取消 (在线一方收提示)。
            if (initiator == null || partner == null) {
                cancelOffline(initiator, partner);
                it.remove();
                continue;
            }
            // 任一方换维度: 取消。
            if (initiator.level() != partner.level()) {
                cancel(initiator, partner, "dimension");
                it.remove();
                continue;
            }
            // 潜行打断 (任一方潜行 = 拒绝/打断, spec 第五章)。
            if (initiator.isShiftKeyDown() || partner.isShiftKeyDown()) {
                cancel(initiator, partner, "sneak");
                it.remove();
                continue;
            }
            // 移动打断 (任一方偏离锚点超阈值)。
            if (movedBeyond(initiator, ch.initX, ch.initY, ch.initZ)
                    || movedBeyond(partner, ch.partnerX, ch.partnerY, ch.partnerZ)) {
                cancel(initiator, partner, "moved");
                it.remove();
                continue;
            }

            ch.elapsedTicks++;
            if (ch.elapsedTicks >= ch.totalTicks) {
                complete(initiator, partner, overworld, now);
                it.remove();
            } else if (ch.elapsedTicks % 20 == 0) {
                // 每秒刷新发起方剩余秒数提示 (轻量进度反馈; 实时进度条可后续走 S2C 仿 TeleportResultS2C)。
                int remainSec = (ch.totalTicks - ch.elapsedTicks + 19) / 20;
                initiator.displayClientMessage(Component.translatable(
                        "message.miningdim.marriage.teleport.charging", remainSec), true);
            }
        }
    }

    /**
     * 受伤打断 (spec 第五章: 蓄力期间任一方受伤即取消 = 天然战斗锁)。由受击事件 (LivingHurtEvent) 接入: 受伤者若
     * 是某条蓄力的发起方或伴侣, 立即取消该蓄力。
     *
     * @param hurtPlayerId 受伤玩家 UUID
     * @param overworld    overworld (取在线玩家发提示)
     * @return true=确有蓄力因此取消 (供事件层/测试断言)
     */
    public boolean onHurt(UUID hurtPlayerId, ServerLevel overworld) {
        boolean cancelledAny = false;
        Iterator<Map.Entry<UUID, Channel>> it = channels.entrySet().iterator();
        while (it.hasNext()) {
            Channel ch = it.next().getValue();
            if (ch.initiator.equals(hurtPlayerId) || ch.partner.equals(hurtPlayerId)) {
                ServerPlayer initiator = overworld.getServer().getPlayerList().getPlayer(ch.initiator);
                ServerPlayer partner = overworld.getServer().getPlayerList().getPlayer(ch.partner);
                cancel(initiator, partner, "hurt");
                it.remove();
                cancelledAny = true;
            }
        }
        return cancelledAny;
    }

    /** 是否有指定发起方的进行中蓄力 (测试/诊断)。 */
    public boolean isChanneling(UUID initiatorId) {
        return channels.containsKey(initiatorId);
    }

    /** 服务端停止清空 (防跨存档脏引用)。 */
    public void reset() {
        channels.clear();
        cooldownUntil.clear();
    }

    // ---- 内部 ----

    private void complete(ServerPlayer initiator, ServerPlayer partner, ServerLevel overworld, long now) {
        // 同维度传送到伴侣身边 (跨维度直传已在 tryStart 拒绝; 此处恒同维度)。
        initiator.teleportTo(partner.serverLevel(),
                partner.getX(), partner.getY(), partner.getZ(),
                partner.getYRot(), partner.getXRot());
        // 进 CD (发起方; 等级派生)。
        IMiningPlayerData data = MiningCapabilities.get(initiator).orElse(null);
        int level = 1;
        if (data != null && data.marriageId() != IMiningPlayerData.NO_MARRIAGE) {
            MarriageState state = MarriageRegistry.get(overworld).byId(data.marriageId());
            if (state != null) {
                level = MarriageTuning.teleportLevel(state.marriedSinceTick(), now);
            }
        }
        cooldownUntil.put(initiator.getUUID(), now + MarriageTuning.teleportCooldownTicks(level));

        initiator.displayClientMessage(Component.translatable(
                "message.miningdim.marriage.teleport.arrived",
                partner.getGameProfile().getName()), true);
        partner.displayClientMessage(Component.translatable(
                "message.miningdim.marriage.teleport.partner_arrived",
                initiator.getGameProfile().getName()), true);
    }

    private void cancel(ServerPlayer initiator, ServerPlayer partner, String reasonSuffix) {
        if (initiator != null) {
            initiator.displayClientMessage(Component.translatable(
                    "message.miningdim.marriage.teleport.cancelled." + reasonSuffix), true);
        }
        if (partner != null) {
            partner.displayClientMessage(Component.translatable(
                    "message.miningdim.marriage.teleport.partner_cancelled"), true);
        }
    }

    private void cancelOffline(ServerPlayer initiator, ServerPlayer partner) {
        ServerPlayer online = initiator != null ? initiator : partner;
        if (online != null) {
            online.displayClientMessage(Component.translatable(
                    "message.miningdim.marriage.teleport.cancelled.offline"), true);
        }
    }

    private static boolean movedBeyond(ServerPlayer player, double ax, double ay, double az) {
        double dx = player.getX() - ax;
        double dy = player.getY() - ay;
        double dz = player.getZ() - az;
        return dx * dx + dy * dy + dz * dz > MOVE_THRESHOLD_SQR;
    }

    /** 玩家是否在矿洞维度 (维度键为 miningdim:mining 或 capability 标记在实例内)。 */
    private static boolean isInMiningDimension(ServerPlayer player) {
        if (player.level().dimension().equals(MiningConstants.MINING_LEVEL)) {
            return true;
        }
        IMiningPlayerData data = MiningCapabilities.get(player).orElse(null);
        return data != null && data.currentInstanceId() != IMiningPlayerData.NO_INSTANCE;
    }
}

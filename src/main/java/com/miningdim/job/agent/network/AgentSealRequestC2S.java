package com.miningdim.job.agent.network;

import com.miningdim.job.agent.AgentSealSeam;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S 封印申请包 (SpecialAgent_Job_DesignSpec 五章面板点已解密词条 + 六章封印支线: 探测与封印合一)。玩家在战术
 * 扫描面板点一条已解密且可封的词条 -> 客户端发本包 -> 服务端校验后经封印接缝执行封印。复用 {@code MiningNetwork
 * .CHANNEL} (第七章统一 CHANNEL), discriminator 集中自增登记。
 *
 * 服务端权威 (架构铁律 1 / N4): 本包只携带意图 (目标网络 id + 词条注册名), 不含任何身份/资格/星级字段 ——
 * 发送者身份取 {@link NetworkEvent.Context#getSender()} 不信前端; 干员资格 (等级) / 分级解锁 / 星级门 / 类别门 /
 * 槽位占用 全由服务端经 {@link AgentSealSeam#requestSealResult} 重算 (聚合 SealPlan 三门 + SealRegistry 占槽 +
 * 真改)。客户端 sealable 标记仅作 UI 预过滤, 服务端不信任。
 *
 * 目标复原: targetNetworkId = 客户端面板快照里的目标精英网络 id (Entity.getId())。服务端在发送者所在 ServerLevel
 * 经 {@code getEntity(int)} 复原目标 (找不到 / 非 LivingEntity = 目标已离场/无效, 优雅回失败提示, 不抛)。
 */
public record AgentSealRequestC2S(int targetNetworkId, String affixId) {

    public static void encode(AgentSealRequestC2S msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.targetNetworkId);
        buf.writeUtf(msg.affixId);
    }

    public static AgentSealRequestC2S decode(FriendlyByteBuf buf) {
        int targetNetworkId = buf.readInt();
        String affixId = buf.readUtf();
        return new AgentSealRequestC2S(targetNetworkId, affixId);
    }

    /**
     * 服务端 handler (N4): enqueueWork 切回主线程, 取发送者 -> 复原目标实体 -> 经封印接缝
     * {@link AgentSealSeam#requestSealResult} 执行 (接缝内聚合干员资格/分级/星级/类别/槽位全门 + 真改) ->
     * 据结果 actionbar 回执成功/失败提示。Champions 未加载 (接缝未绑定) 时接缝返 NOT_BOUND 短路, 回 "扫描离线"
     * 提示, 不抛 (与设计哲学 "纯逻辑层照常工作, Champions 路径优雅降级" 一致)。
     *
     * sender 为 null (理论不会出现在 PLAY_TO_SERVER) 时直接放弃, 不构造任何世界状态。
     */
    public static void handle(AgentSealRequestC2S msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) {
                return;
            }
            ServerLevel level = sender.serverLevel();
            Entity target = level.getEntity(msg.targetNetworkId);
            if (!(target instanceof LivingEntity living)) {
                // 目标已离场 / 网络 id 失效: 优雅回失败提示 (不抛; 目标随时可能离区块, 属正常业务分支)。
                feedback(sender, "no_target");
                return;
            }
            AgentSealSeam.SealOutcome outcome = AgentSealSeam.requestSealResult(sender, living, msg.affixId);
            feedback(sender, feedbackKeySuffix(outcome));
        });
        ctx.setPacketHandled(true);
    }

    /** actionbar 回执 (true = 浮在物品栏上方; 与塔罗 displayClientMessage(.., true) 同口径)。 */
    private static void feedback(ServerPlayer player, String keySuffix) {
        player.displayClientMessage(
                Component.translatable("message.miningdim.agent.seal." + keySuffix), true);
    }

    /** 封印结果 -> lang key 后缀 (message.miningdim.agent.seal.<后缀>)。 */
    private static String feedbackKeySuffix(AgentSealSeam.SealOutcome outcome) {
        return switch (outcome) {
            case OK -> "ok";
            case NOT_BOUND -> "offline";
            case NO_TARGET -> "no_target";
            case AFFIX_NOT_SEALABLE -> "not_sealable";
            case CATEGORY_LOCKED -> "category_locked";
            case STAR_TOO_HIGH -> "star_too_high";
            case ALL_SLOTS_OCCUPIED -> "slots_full";
            case AFFIX_ALREADY_SEALED -> "already_sealed";
            case ON_COOLDOWN -> "on_cooldown";
        };
    }
}

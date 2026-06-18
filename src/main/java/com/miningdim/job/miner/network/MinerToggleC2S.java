package com.miningdim.job.miner.network;

import com.miningdim.job.miner.MinerActions;
import com.miningdim.job.miner.MinerSkill;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S 矿工开关/主动技能触发包 (复用 SelectZoneC2S 范式)。键位按下时客户端发送, 携带技能种类 byte。
 *
 * 防作弊 (服务端权威): 包只表达 "玩家想用哪个技能/翻哪个开关" 的意图, 不携带任何坐标/范围/CD 字段;
 * 充能/CD/等级解锁/探测结果一律服务端重算 (见 {@link MinerActions})。
 */
public record MinerToggleC2S(byte skillOrdinal) {

    public static void encode(MinerToggleC2S msg, FriendlyByteBuf buf) {
        buf.writeByte(msg.skillOrdinal);
    }

    public static MinerToggleC2S decode(FriendlyByteBuf buf) {
        return new MinerToggleC2S(buf.readByte());
    }

    /**
     * 服务端 handler: enqueueWork 切回主线程, 取发送者, 解析技能种类后委派 {@link MinerActions}。
     * 越界 byte -> null skill 时直接放弃 (边界兜底, 不构造任何世界状态)。
     */
    public static void handle(MinerToggleC2S msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) {
                return;
            }
            MinerSkill skill = MinerSkill.byOrdinal(msg.skillOrdinal);
            if (skill == null) {
                return; // 非法序号: 放弃。
            }
            MinerActions.handleToggle(sender, skill);
        });
        ctx.setPacketHandled(true);
    }
}

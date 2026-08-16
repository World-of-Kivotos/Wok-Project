package com.miningdim.job.miner.network;

import com.miningdim.job.miner.MinerActions;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S 连锁"按住激活"上报包 (FTB Ultimine 式)。连锁从持久开关改为按住键: 客户端按下沿发 held=true、松开沿发
 * held=false, 按住期间每 {@link com.miningdim.job.miner.MinerConstants#CHAIN_HOLD_HEARTBEAT_TICKS} 心跳重发 held=true 续期。
 *
 * 防作弊 (服务端权威): 只表达"按住/松开"意图, 不携带任何坐标/范围; 激活续期 heldUntilTick、连锁范围、充能一律服务端算
 * (见 {@link MinerActions#handleChainHold})。
 */
public record MinerChainHoldC2S(boolean held) {

    public static void encode(MinerChainHoldC2S msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.held);
    }

    public static MinerChainHoldC2S decode(FriendlyByteBuf buf) {
        return new MinerChainHoldC2S(buf.readBoolean());
    }

    /** 服务端 handler: enqueueWork 切回主线程, 取发送者后委派 {@link MinerActions#handleChainHold}。 */
    public static void handle(MinerChainHoldC2S msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) {
                return;
            }
            MinerActions.handleChainHold(sender, msg.held);
        });
        ctx.setPacketHandled(true);
    }
}

package com.miningdim.job.miner.network;

import com.miningdim.job.miner.MinerActions;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S 连锁预览请求包 (按住连锁期间, 准星目标块变化或每 {@link com.miningdim.job.miner.MinerConstants#CHAIN_PREVIEW_REQUEST_INTERVAL_TICKS}
 * 兜底节流时发)。只携带客户端准星指向的目标方块坐标; 服务端据此权威跑 plan 回预览 (见 {@link MinerActions#handleChainPreview})。
 *
 * 防作弊/防泄密 (服务端权威): 客户端无权算连锁范围, 只上报"我在看哪块"; 是否解锁/是否按住/该块是否可连锁/连锁到哪些位
 * 全由服务端判, 且 plan 对未揭示陷阱按普通矿石处理 (预览不泄漏陷阱位)。
 */
public record MinerChainPreviewC2S(BlockPos target) {

    public static void encode(MinerChainPreviewC2S msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.target);
    }

    public static MinerChainPreviewC2S decode(FriendlyByteBuf buf) {
        return new MinerChainPreviewC2S(buf.readBlockPos());
    }

    /** 服务端 handler: enqueueWork 切回主线程, 取发送者后委派 {@link MinerActions#handleChainPreview}。 */
    public static void handle(MinerChainPreviewC2S msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) {
                return;
            }
            MinerActions.handleChainPreview(sender, msg.target);
        });
        ctx.setPacketHandled(true);
    }
}

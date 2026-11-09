package com.miningdim.job.farmer.granary;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 -> 客户端: 预检结果 (结果码 + 这次问的是哪个名字)。
 *
 * <p>结果码是一个语言键, 界面直接当成一句提示显示 —— 判定与文案都不在客户端。</p>
 */
public record GranaryTrustCheckS2C(int windowId, String outcomeKey, String playerName) {

    public static void encode(GranaryTrustCheckS2C msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.windowId);
        buf.writeUtf(msg.outcomeKey == null ? "" : msg.outcomeKey, 128);
        buf.writeUtf(msg.playerName == null ? "" : msg.playerName, 32);
    }

    public static GranaryTrustCheckS2C decode(FriendlyByteBuf buf) {
        return new GranaryTrustCheckS2C(buf.readVarInt(), buf.readUtf(128), buf.readUtf(32));
    }

    public static void handle(GranaryTrustCheckS2C msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> com.miningdim.job.farmer.granary.client.GranaryTrustClientState.applyCheck(msg)));
        ctx.setPacketHandled(true);
    }
}

package com.miningdim.job.farmer.granary;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端: 预检一个名字 (只问"如果现在加会怎么样", 不动名单)。
 *
 * <p>界面在输入框内容变化时发一次, 服务端用与真操作同一份判定的 {@link GranaryTrustService#check}
 * 回一句提示 —— 这样"名字打错""名单满了""那是你自己"在点按钮之前就能看见。</p>
 */
public record GranaryTrustCheckC2S(int windowId, String playerName) {

    public static void encode(GranaryTrustCheckC2S msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.windowId);
        buf.writeUtf(msg.playerName == null ? "" : msg.playerName, 32);
    }

    public static GranaryTrustCheckC2S decode(FriendlyByteBuf buf) {
        return new GranaryTrustCheckC2S(buf.readVarInt(), buf.readUtf(32));
    }

    public static void handle(GranaryTrustCheckC2S msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) {
                return;
            }
            if (!(sender.containerMenu instanceof GranaryBinMenu menu) || menu.containerId != msg.windowId) {
                return;
            }
            GranaryTrustService.Outcome outcome = GranaryTrustService.check(
                    sender.server, menu.bin(), sender, msg.playerName);
            GranaryNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender),
                    new GranaryTrustCheckS2C(menu.containerId, outcome.translationKey(), msg.playerName));
        });
        ctx.setPacketHandled(true);
    }
}

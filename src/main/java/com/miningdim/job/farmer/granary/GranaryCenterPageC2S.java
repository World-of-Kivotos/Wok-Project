package com.miningdim.job.farmer.granary;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端: 「我要看第几页」(规格「账台的多页签界面」)。
 *
 * 包里只有一个页序号 (还有窗口号用于对账): **客户端说不了别的** —— 内容、权限、能存不能存都由服务端现算。
 * 越界页序号在解码与处理两处都兜底到第一页 (网络来的东西一律当不可信)。
 */
public record GranaryCenterPageC2S(int windowId, int page) {

    public static void encode(GranaryCenterPageC2S msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.windowId);
        buf.writeVarInt(msg.page);
    }

    public static GranaryCenterPageC2S decode(FriendlyByteBuf buf) {
        return new GranaryCenterPageC2S(buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(GranaryCenterPageC2S msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) {
                return;
            }
            // 只认"正开着账台界面"的请求: 别的窗口号一律忽略 (改包客户端最多让自己刷新不了, 拿不到任何数据)。
            if (!(sender.containerMenu instanceof GranaryCenterMenu menu)
                    || menu.containerId != msg.windowId) {
                return;
            }
            GranaryCenterPageS2C.send(sender, menu, GranaryCenterPage.byIndex(msg.page));
        });
        ctx.setPacketHandled(true);
    }
}
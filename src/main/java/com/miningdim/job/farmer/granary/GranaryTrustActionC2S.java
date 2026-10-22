package com.miningdim.job.farmer.granary;

import com.miningdim.job.farmer.granary.block.GranaryBinBlockEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端: 改这个囤的开放名单 (规格「开放设置页」)。
 *
 * 包里只有"做什么 + 对谁 + 什么权限": **判定全在服务端** ({@link GranaryTrustService}: 必须是囤主、名单上限、
 * 认人规则)。越界的动作枚举与权限序号在解码处就兜回安全值 (网络来的东西一律当不可信)。
 */
public record GranaryTrustActionC2S(int windowId, int action, String playerName, int permission) {

    public static void encode(GranaryTrustActionC2S msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.windowId);
        buf.writeVarInt(msg.action);
        buf.writeUtf(msg.playerName == null ? "" : msg.playerName, 32);
        buf.writeVarInt(msg.permission);
    }

    public static GranaryTrustActionC2S decode(FriendlyByteBuf buf) {
        int windowId = buf.readVarInt();
        int action = buf.readVarInt();
        String name = buf.readUtf(32);
        int permission = buf.readVarInt();
        GranaryTrustService.Action[] actions = GranaryTrustService.Action.values();
        int safeAction = action >= 0 && action < actions.length ? action : GranaryTrustService.Action.CLEAR.ordinal();
        GranaryAccess.Permission[] permissions = GranaryAccess.Permission.values();
        int safePermission = permission >= 0 && permission < permissions.length ? permission : 0;
        return new GranaryTrustActionC2S(windowId, safeAction, name, safePermission);
    }

    public static void handle(GranaryTrustActionC2S msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) {
                return;
            }
            if (!(sender.containerMenu instanceof GranaryBinMenu menu) || menu.containerId != msg.windowId) {
                return; // 只认"正开着这个囤的界面"的请求
            }
            GranaryBinBlockEntity bin = menu.bin();
            GranaryTrustService.Result result = GranaryTrustService.apply(sender.server, bin, sender,
                    GranaryTrustService.Action.values()[msg.action], msg.playerName,
                    GranaryAccess.Permission.values()[msg.permission]);
            GranaryTrustStateS2C.send(sender, menu, result);
        });
        ctx.setPacketHandled(true);
    }
}
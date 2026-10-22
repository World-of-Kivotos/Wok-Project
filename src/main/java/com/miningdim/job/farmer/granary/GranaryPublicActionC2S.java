package com.miningdim.job.farmer.granary;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端: 改这个囤的公开模式 (官仓的公开捐粮 / 公开取用)。
 *
 * <p>包里只有"开/关哪一项 + 用几组": 判定全在 {@link GranaryPublicService} (囤主、官仓、配额范围)。
 * 越界的动作在解码处兜回安全值 (网络来的东西一律当不可信)。</p>
 */
public record GranaryPublicActionC2S(int windowId, int action, int groups) {

    public static void encode(GranaryPublicActionC2S msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.windowId);
        buf.writeVarInt(msg.action);
        buf.writeVarInt(msg.groups);
    }

    public static GranaryPublicActionC2S decode(FriendlyByteBuf buf) {
        int windowId = buf.readVarInt();
        int action = buf.readVarInt();
        int groups = buf.readVarInt();
        GranaryPublicService.Action[] actions = GranaryPublicService.Action.values();
        int safeAction = action >= 0 && action < actions.length
                ? action : GranaryPublicService.Action.DONATE_OFF.ordinal();
        return new GranaryPublicActionC2S(windowId, safeAction, GranaryPublic.clampGroups(groups));
    }

    public static void handle(GranaryPublicActionC2S msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) {
                return;
            }
            if (!(sender.containerMenu instanceof GranaryBinMenu menu) || menu.containerId != msg.windowId) {
                return; // 只认"正开着这个囤的界面"的请求
            }
            GranaryPublicService.Action action = GranaryPublicService.Action.values()[msg.action];
            // 界面上的开关只翻"开/关": 份额沿用这个囤当前的设置 (要改份额用 /granary public)。
            GranaryPublic.Settings current = menu.bin().publicSettings();
            int groups = msg.groups > 0 ? msg.groups
                    : (action == GranaryPublicService.Action.DONATE_ON
                    ? current.donateGroupsPerDay() : current.takeGroupsPerDay());
            GranaryPublicService.Result result = GranaryPublicService.apply(
                    sender.serverLevel(), menu.bin(), sender, action, groups);
            GranaryPublicStateS2C.send(sender, menu, result.outcome().translationKey(),
                    result.settings(), GranaryPublicService.isStateGranary(sender.serverLevel(), menu.bin()));
        });
        ctx.setPacketHandled(true);
    }
}

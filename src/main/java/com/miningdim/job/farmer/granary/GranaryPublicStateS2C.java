package com.miningdim.job.farmer.granary;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

/**
 * 服务端 -> 客户端: 这个囤的公开模式当前状态 + 上一次操作的结果码。
 *
 * <p>界面只负责画: 能不能开 (官仓)、捐粮/取用各自的开关与每天几组, 全由服务端算好下发 ——
 * 与开放名单那条路同一条纪律, 客户端不自己判定。</p>
 */
public record GranaryPublicStateS2C(int windowId, String outcomeKey, boolean donate, int donateGroups,
                                    boolean take, int takeGroups, boolean stateTier) {

    public static void encode(GranaryPublicStateS2C msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.windowId);
        buf.writeUtf(msg.outcomeKey == null ? "" : msg.outcomeKey, 128);
        buf.writeBoolean(msg.donate);
        buf.writeVarInt(msg.donateGroups);
        buf.writeBoolean(msg.take);
        buf.writeVarInt(msg.takeGroups);
        buf.writeBoolean(msg.stateTier);
    }

    public static GranaryPublicStateS2C decode(FriendlyByteBuf buf) {
        return new GranaryPublicStateS2C(buf.readVarInt(), buf.readUtf(128),
                buf.readBoolean(), GranaryPublic.clampGroups(buf.readVarInt()),
                buf.readBoolean(), GranaryPublic.clampGroups(buf.readVarInt()),
                buf.readBoolean());
    }

    public static void handle(GranaryPublicStateS2C msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> com.miningdim.job.farmer.granary.client.GranaryPublicClientState.apply(msg)));
        ctx.setPacketHandled(true);
    }

    /** 把当前状态与结果码发回 (打开囤界面时、每次改动后与切档时都走它)。 */
    public static void send(ServerPlayer player, GranaryBinMenu menu, String outcomeKey,
                            GranaryPublic.Settings settings, boolean stateTier) {
        GranaryPublic.Settings current = settings == null ? GranaryPublic.Settings.OFF : settings;
        GranaryNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new GranaryPublicStateS2C(menu.containerId, outcomeKey == null ? "" : outcomeKey,
                        current.donate(), current.donateGroupsPerDay(),
                        current.take(), current.takeGroupsPerDay(), stateTier));
    }
}

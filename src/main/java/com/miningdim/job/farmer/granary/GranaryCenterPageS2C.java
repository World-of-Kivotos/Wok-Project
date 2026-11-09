package com.miningdim.job.farmer.granary;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 -> 客户端: 账台某一页的文本行 (每行一个序列化后的 Component)。
 *
 * 行数有上限 ({@link GranaryCenterView#MAX_LINES} + 一行截断提示), 所以包是有界的; 超长行也由
 * {@link FriendlyByteBuf#writeUtf} 自带的上限兜底。
 */
public record GranaryCenterPageS2C(int windowId, int page, List<String> lines) {

    /** 一行文本的长度上限 (序列化后的 Component JSON; 正常一句话远小于此)。 */
    private static final int MAX_LINE_LENGTH = 8_192;

    public static void encode(GranaryCenterPageS2C msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.windowId);
        buf.writeVarInt(msg.page);
        buf.writeVarInt(msg.lines.size());
        for (String line : msg.lines) {
            buf.writeUtf(line, MAX_LINE_LENGTH);
        }
    }

    public static GranaryCenterPageS2C decode(FriendlyByteBuf buf) {
        int windowId = buf.readVarInt();
        int page = buf.readVarInt();
        int count = Math.max(0, Math.min(GranaryCenterView.MAX_LINES + 1, buf.readVarInt()));
        List<String> lines = new ArrayList<>(Math.min(count, 64));
        for (int i = 0; i < count; i++) {
            if (!buf.isReadable()) {
                // 对方声称的行数比真写进来的多 (改包客户端): 按实际能读到的收, 不越界读。
                break;
            }
            lines.add(buf.readUtf(MAX_LINE_LENGTH));
        }
        return new GranaryCenterPageS2C(windowId, page, List.copyOf(lines));
    }

    /** 客户端 handler: 写进界面缓存 (只在客户端线程跑, 渲染自己去读)。 */
    public static void handle(GranaryCenterPageS2C msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> com.miningdim.job.farmer.granary.client.GranaryCenterClientState.apply(msg)));
        ctx.setPacketHandled(true);
    }

    /** 服务端现算一页并发回 (打开界面时与每次点页签时都走它)。 */
    public static void send(ServerPlayer player, GranaryCenterMenu menu, GranaryCenterPage page) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        GranaryInspection.Report report = GranaryCenterView.reportAt(level, menu.centerPos());
        List<String> lines = GranaryCenterView.lines(page, level, player, report);
        GranaryNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new GranaryCenterPageS2C(menu.containerId, page.ordinal(), lines));
    }
}
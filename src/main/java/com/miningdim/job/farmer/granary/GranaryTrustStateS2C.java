package com.miningdim.job.farmer.granary;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 -> 客户端: 开放名单的当前内容 + 上一次操作的结果码 (规格「开放设置页」)。
 *
 * 名单行的形状与账台页内容一致 (序列化后的 Component), 界面只负责画; 结果码是一个**语言键**
 * ({@link GranaryTrustService.Outcome#translationKey()}), 界面把它当一句话显示 —— 判定与文案都不在客户端。
 */
public record GranaryTrustStateS2C(int windowId, String outcomeKey, List<String> lines) {

    private static final int MAX_LINE_LENGTH = 8_192;
    private static final int MAX_LINES = GranaryAccess.MAX_TRUSTEES + 1;

    public static void encode(GranaryTrustStateS2C msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.windowId);
        buf.writeUtf(msg.outcomeKey == null ? "" : msg.outcomeKey, 128);
        buf.writeVarInt(msg.lines.size());
        for (String line : msg.lines) {
            buf.writeUtf(line, MAX_LINE_LENGTH);
        }
    }

    public static GranaryTrustStateS2C decode(FriendlyByteBuf buf) {
        int windowId = buf.readVarInt();
        String outcomeKey = buf.readUtf(128);
        int count = Math.max(0, Math.min(MAX_LINES, buf.readVarInt()));
        List<String> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (!buf.isReadable()) {
                break; // 声称的行数比真写进来的多: 按实际能读到的收
            }
            lines.add(buf.readUtf(MAX_LINE_LENGTH));
        }
        return new GranaryTrustStateS2C(windowId, outcomeKey, List.copyOf(lines));
    }

    public static void handle(GranaryTrustStateS2C msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> com.miningdim.job.farmer.granary.client.GranaryTrustClientState.apply(msg)));
        ctx.setPacketHandled(true);
    }

    /**
     * 把当前名单与结果码发回 (打开囤界面时与每次改动后都走它)。
     *
     * @param result 这一次操作的回执; 传 {@code null} 表示"只是把当前名单推下去" —— 此时结果码是空串,
     *               界面不显示任何提示 (拿一个假的 CLEARED 兜底会让玩家一开界面就看见"已转回私有")
     */
    public static void send(ServerPlayer player, GranaryBinMenu menu, GranaryTrustService.Result result) {
        List<GranaryAccess.Trustee> trustees = result != null ? result.trustees() : menu.bin().access();
        String outcomeKey = result != null ? result.outcome().translationKey() : "";
        List<String> lines = GranaryTrustService.lines(player.server, trustees);
        GranaryNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new GranaryTrustStateS2C(menu.containerId, outcomeKey, lines));
    }
}
package com.miningdim.network;

import com.miningdim.core.IMiningNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C 传送结果包 (设计文档 15.4.3)。服务端处理完进入/离开请求后下发, 客户端据此弹提示。
 *
 * 客户端不执行任何传送动作 (传送已在服务端完成或被拒, 本包仅做反馈, 15.4.3)。result 为
 * TeleportResult 序号; queuePos = -1 表示不适用; reasonKey 为 i18n key 供客户端本地化。
 */
public record TeleportResultS2C(byte result, long instanceId, int queuePos, String reasonKey) {

    public static void encode(TeleportResultS2C msg, FriendlyByteBuf buf) {
        buf.writeByte(msg.result);
        buf.writeLong(msg.instanceId);
        buf.writeVarInt(msg.queuePos);
        buf.writeUtf(msg.reasonKey);
    }

    public static TeleportResultS2C decode(FriendlyByteBuf buf) {
        byte result = buf.readByte();
        long instanceId = buf.readLong();
        int queuePos = buf.readVarInt();
        String reasonKey = buf.readUtf();
        return new TeleportResultS2C(result, instanceId, queuePos, reasonKey);
    }

    /**
     * 客户端 handler (15.4.3/N4): enqueueWork 切回客户端主线程, 按 result 弹 toast/聊天提示;
     * QUEUED 时显示排队位次。不做世界写。客户端引用经 DistExecutor 隔离专用服务器。
     */
    public static void handle(TeleportResultS2C msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientFeedback.acceptTeleportResult(msg)));
        ctx.setPacketHandled(true);
    }

    /** 把 byte 序号还原为 TeleportResult (越界兜底 ERROR, 防错位字节)。 */
    public IMiningNetwork.TeleportResult resultEnum() {
        IMiningNetwork.TeleportResult[] values = IMiningNetwork.TeleportResult.values();
        int idx = result;
        if (idx < 0 || idx >= values.length) {
            return IMiningNetwork.TeleportResult.ERROR;
        }
        return values[idx];
    }
}

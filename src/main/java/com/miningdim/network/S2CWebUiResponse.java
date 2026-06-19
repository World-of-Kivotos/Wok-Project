package com.miningdim.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C Web UI 响应包 (Web UI bridge 契约第 4 节)。服务端 {@link com.miningdim.webui.server.WebUiServerDispatcher}
 * 处理完一次 {@link C2SWebUiRequest} 后下发, 经 requestId 关联回到客户端等待中的 cefQuery callback。
 *
 * success=true 时 resultJson 为业务结果 JSON (如 echo 响应); success=false 时 resultJson 为 {"error":"..."}。
 * 客户端类引用经 DistExecutor 隔离 (与 DangerSyncS2C.handle 同范式), 防专用服务器加载期触链 MCEF/渲染类。
 */
public record S2CWebUiResponse(long requestId, boolean success, String resultJson) {

    public static void encode(S2CWebUiResponse msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.requestId);
        buf.writeBoolean(msg.success);
        buf.writeUtf(msg.resultJson);
    }

    public static S2CWebUiResponse decode(FriendlyByteBuf buf) {
        long requestId = buf.readLong();
        boolean success = buf.readBoolean();
        String resultJson = buf.readUtf();
        return new S2CWebUiResponse(requestId, success, resultJson);
    }

    /**
     * 客户端 handler (契约第 4/5 节): enqueueWork 切回客户端主线程后, 经 DistExecutor 仅在 Dist.CLIENT
     * 把响应转交 {@link com.miningdim.client.webui.WebUiClientReceiver#onResponse}。双 supplier lambda
     * (() -> () -> ...) 保证服务端 (GameTest/专用服务器) 不 classload 该客户端接收器。
     */
    public static void handle(S2CWebUiResponse msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.miningdim.client.webui.WebUiClientReceiver.onResponse(
                        msg.requestId, msg.success, msg.resultJson)));
        ctx.setPacketHandled(true);
    }
}

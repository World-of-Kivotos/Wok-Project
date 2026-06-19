package com.miningdim.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C Web UI 服务端推送事件包 (Web UI bridge 契约第 4 节)。区别于 {@link S2CWebUiResponse} 的请求-回执模型,
 * 本包是服务端主动向客户端 Web UI 派发的无请求事件 (eventName 标识事件类型, dataJson 为事件载荷),
 * 客户端桥接层将其 executeJavaScript 派发给 JS 侧的事件监听器。
 *
 * 客户端类引用经 DistExecutor 隔离 (与 S2CWebUiResponse.handle 同范式), 防专用服务器加载期触链 MCEF/渲染类。
 */
public record S2CWebUiEvent(String eventName, String dataJson) {

    public static void encode(S2CWebUiEvent msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.eventName);
        buf.writeUtf(msg.dataJson);
    }

    public static S2CWebUiEvent decode(FriendlyByteBuf buf) {
        String eventName = buf.readUtf();
        String dataJson = buf.readUtf();
        return new S2CWebUiEvent(eventName, dataJson);
    }

    /**
     * 客户端 handler (契约第 4/5 节): enqueueWork 切回客户端主线程后, 经 DistExecutor 仅在 Dist.CLIENT
     * 把事件转交 {@link com.miningdim.client.webui.WebUiClientReceiver#onEvent}。双 supplier lambda
     * (() -> () -> ...) 保证服务端 (GameTest/专用服务器) 不 classload 该客户端接收器。
     */
    public static void handle(S2CWebUiEvent msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.miningdim.client.webui.WebUiClientReceiver.onEvent(
                        msg.eventName, msg.dataJson)));
        ctx.setPacketHandled(true);
    }
}

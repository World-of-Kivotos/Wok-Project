package com.miningdim.network;

import com.miningdim.webui.server.WebUiServerDispatcher;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S Web UI 请求包 (Web UI bridge 契约第 4 节)。客户端 CEF 浏览器内 JS 经 cefQuery 发起的意图,
 * 由客户端桥接层封装为本包送到服务端。
 *
 * 服务端权威 (架构铁律 1): 本包只携带意图 (action + 原始 payload JSON), 不含任何身份字段;
 * 发送者身份在 handle 内取 {@link NetworkEvent.Context#getSender()}, 不信前端传的 uuid。
 * requestId 由客户端桥接层生成 (AtomicLong 自增), 仅用于关联本次 C2S 与其 S2C 回执, 对 JS 不可见。
 */
public record C2SWebUiRequest(long requestId, String action, String payloadJson) {

    /**
     * action 名的上行长度上限 (F008)。全库 {@code WebUiServerDispatcher.register} 最长的 action 名是
     * {@code admin.economy.balance} 共 21 字符, 64 留了三倍余量。默认上限
     * {@link FriendlyByteBuf#MAX_STRING_LENGTH} 是 32767, 等于让改版客户端每个垃圾包往服务端日志与回执各塞
     * 32KB 可控文本。超限时解码抛 {@code DecoderException} 由 netty 管道处理 (该连接被断开) —— 这是对畸形包
     * 的正确处置, 不是业务错误, 故不在此 catch。payloadJson 保持默认上限不动: 聚合类 action 的合法 payload
     * 确实可能长, 它已被 respond 的体积守卫与 Gateway 限流共同兜住。
     */
    public static final int MAX_ACTION_CHARS = 64;

    /** 编码 (契约第 4 节): 仅按序写字段, 无世界访问。payloadJson 用工程同款 writeUtf。 */
    public static void encode(C2SWebUiRequest msg, FriendlyByteBuf buf) {
        buf.writeLong(msg.requestId);
        buf.writeUtf(msg.action, MAX_ACTION_CHARS);
        buf.writeUtf(msg.payloadJson);
    }

    /** 解码 (契约第 4 节): 按序读回构造不可变 record, 无世界访问。 */
    public static C2SWebUiRequest decode(FriendlyByteBuf buf) {
        long requestId = buf.readLong();
        String action = buf.readUtf(MAX_ACTION_CHARS);
        String payloadJson = buf.readUtf();
        return new C2SWebUiRequest(requestId, action, payloadJson);
    }

    /**
     * 服务端 handler (契约第 4 节 / N4): enqueueWork 切回主线程后取发送者, 委托
     * {@link WebUiServerDispatcher#dispatchAndRespond} 派发并回执。sender 为 null (理论不会出现在
     * PLAY_TO_SERVER) 时直接放弃, 不构造任何世界状态。业务异常在 dispatcher 的 Gateway 边界统一捕获并
     * 回执 false, 本层不吞 (与 SelectZoneC2S 同纪律)。
     */
    public static void handle(C2SWebUiRequest msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) {
                return;
            }
            WebUiServerDispatcher.dispatchAndRespond(sender, msg.requestId, msg.action, msg.payloadJson);
        });
        ctx.setPacketHandled(true);
    }
}

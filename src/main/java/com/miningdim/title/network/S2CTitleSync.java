package com.miningdim.title.network;

import com.miningdim.client.title.TitleClientCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * S2C 头顶名牌称号同步 (Title_System_DesignSpec 第五章)。
 *
 * 携带 entityId (客户端按它在名牌渲染时对号) 与渲染好的称号 Component; title 为 null 表示该玩家不再佩戴,
 * 客户端移除缓存。包里直接带 Component, 客户端因此不需要本地称号表, 也不自算颜色 (服务端权威)。
 * 只在状态变化时发 (开始追踪 / 登录 / 换维度 / 重生 / 佩戴变化 / 数据包重载), 不做逐 tick 同步。
 *
 * 客户端类引用经 DistExecutor 双箭头隔离, 专用服务器加载本类不会触链到客户端代码。
 */
public record S2CTitleSync(int entityId, @Nullable Component title) {

    public static void encode(S2CTitleSync msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entityId);
        buf.writeNullable(msg.title, FriendlyByteBuf::writeComponent);
    }

    public static S2CTitleSync decode(FriendlyByteBuf buf) {
        int entityId = buf.readVarInt();
        Component title = buf.readNullable(FriendlyByteBuf::readComponent);
        return new S2CTitleSync(entityId, title);
    }

    /** 客户端 handler: enqueueWork 切回客户端主线程, 把称号交给 {@link TitleClientCache}; 不触发任何世界写。 */
    public static void handle(S2CTitleSync msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> TitleClientCache.accept(msg.entityId, msg.title)));
        ctx.setPacketHandled(true);
    }
}

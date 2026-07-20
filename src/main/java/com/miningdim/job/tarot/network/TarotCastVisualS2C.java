package com.miningdim.job.tarot.network;

import com.miningdim.job.tarot.TarotQuality;
import com.miningdim.job.tarot.client.ClientTarotVisuals;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** 一次塔罗施法演出的最小同步载荷。数值效果不经过本包, 客户端也不能借此触发牌效。 */
public record TarotCastVisualS2C(int casterEntityId, int cardId, TarotQuality quality, boolean upright) {

    public static void encode(TarotCastVisualS2C message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.casterEntityId);
        buffer.writeVarInt(message.cardId);
        buffer.writeVarInt(message.quality.ordinal());
        buffer.writeBoolean(message.upright);
    }

    public static TarotCastVisualS2C decode(FriendlyByteBuf buffer) {
        return new TarotCastVisualS2C(
                buffer.readVarInt(),
                buffer.readVarInt(),
                TarotQuality.byOrdinal(buffer.readVarInt()),
                buffer.readBoolean());
    }

    public static void handle(TarotCastVisualS2C message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientTarotVisuals.accept(message)));
        context.setPacketHandled(true);
    }
}

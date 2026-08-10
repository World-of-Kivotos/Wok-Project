package com.miningdim.job.tarot.network;

import com.miningdim.job.tarot.TarotArcana;
import com.miningdim.job.tarot.TarotQuality;
import com.miningdim.job.tarot.client.TarotPackRevealScreen;
import com.miningdim.job.tarot.pack.PackKind;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server-authoritative pack result mirrored to the opener for presentation only.
 * The packet cannot grant items or reroll results; cards have already been placed
 * in the player's inventory before this visual screen opens.
 */
public record TarotPackRevealS2C(
        PackKind packKind,
        List<RevealedCard> cards,
        int totalCards,
        int shardRefund,
        int derivedPacks) {

    public static final int MAX_VISUAL_CARDS = 64;

    public TarotPackRevealS2C {
        if (packKind == null) {
            throw new IllegalArgumentException("packKind must not be null");
        }
        cards = List.copyOf(cards);
        if (cards.size() > MAX_VISUAL_CARDS) {
            throw new IllegalArgumentException("too many tarot reveal cards: " + cards.size());
        }
        if (totalCards < cards.size() || shardRefund < 0 || derivedPacks < 0) {
            throw new IllegalArgumentException("invalid tarot pack reveal counters");
        }
    }

    public static void encode(TarotPackRevealS2C message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.packKind.ordinal());
        buffer.writeVarInt(message.cards.size());
        for (RevealedCard card : message.cards) {
            buffer.writeVarInt(card.cardId);
            buffer.writeVarInt(card.quality.ordinal());
            buffer.writeBoolean(card.upright);
        }
        buffer.writeVarInt(message.totalCards);
        buffer.writeVarInt(message.shardRefund);
        buffer.writeVarInt(message.derivedPacks);
    }

    public static TarotPackRevealS2C decode(FriendlyByteBuf buffer) {
        int packOrdinal = buffer.readVarInt();
        PackKind[] kinds = PackKind.values();
        if (packOrdinal < 0 || packOrdinal >= kinds.length) {
            throw new IllegalArgumentException("tarot pack kind ordinal out of range: " + packOrdinal);
        }
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_VISUAL_CARDS) {
            throw new IllegalArgumentException("tarot reveal card count out of range: " + count);
        }
        List<RevealedCard> cards = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            cards.add(new RevealedCard(
                    buffer.readVarInt(),
                    TarotQuality.byOrdinal(buffer.readVarInt()),
                    buffer.readBoolean()));
        }
        return new TarotPackRevealS2C(
                kinds[packOrdinal],
                cards,
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt());
    }

    public static void handle(TarotPackRevealS2C message,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> TarotPackRevealScreen.open(message)));
        context.setPacketHandled(true);
    }

    /** One card shown by the client. cardId and quality are validated at construction. */
    public record RevealedCard(int cardId, TarotQuality quality, boolean upright) {
        public RevealedCard {
            if (cardId < 0 || cardId >= TarotArcana.COUNT) {
                throw new IllegalArgumentException("tarot reveal cardId out of range: " + cardId);
            }
            if (quality == null) {
                throw new IllegalArgumentException("tarot reveal quality must not be null");
            }
        }
    }
}

package com.miningdim.job.tarot.network;

import com.miningdim.core.MiningConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/** 塔罗师专属轻量网络通道。牌效仍由服务端裁决, 本通道只同步客户端施法演出参数。 */
public final class TarotNetwork {

    private static final String PROTOCOL_VERSION = "1";
    private static final double VISUAL_RANGE_SQR = 64.0D * 64.0D;

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MiningConstants.MODID, "tarot"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private static int nextId;

    private TarotNetwork() {
    }

    public static void register() {
        CHANNEL.registerMessage(nextId++, TarotCastVisualS2C.class,
                TarotCastVisualS2C::encode, TarotCastVisualS2C::decode, TarotCastVisualS2C::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId++, TarotPackRevealS2C.class,
                TarotPackRevealS2C::encode, TarotPackRevealS2C::decode, TarotPackRevealS2C::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** 向施法者周围 64 格玩家同步一次演出起点; 后续逐帧粒子全部由各客户端本地生成。 */
    public static void sendCastVisual(ServerPlayer caster, TarotCastVisualS2C message) {
        for (ServerPlayer viewer : caster.serverLevel().players()) {
            if (viewer.distanceToSqr(caster) <= VISUAL_RANGE_SQR
                    && viewer.connection != null && viewer.connection.isAcceptingMessages()) {
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> viewer), message);
            }
        }
    }

    /** Sends a private pack-opening presentation to the player who owns the result. */
    public static void sendPackReveal(ServerPlayer player, TarotPackRevealS2C message) {
        if (player.connection != null && player.connection.isAcceptingMessages()) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
        }
    }
}

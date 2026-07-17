package com.miningdim.job.engineer.shield.network;

import com.miningdim.core.MiningConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Dedicated plasma-shield channel; it cannot disturb the discriminator ordering of the main channel. */
public final class PlasmaShieldNetwork {

    private static final String PROTOCOL_VERSION = "3";
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MiningConstants.MODID, "plasma_shield"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private PlasmaShieldNetwork() {
    }

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        CHANNEL.registerMessage(0, PlasmaShieldSyncS2C.class,
                PlasmaShieldSyncS2C::encode,
                PlasmaShieldSyncS2C::decode,
                PlasmaShieldSyncS2C::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, PlasmaShieldHitS2C.class,
                PlasmaShieldHitS2C::encode,
                PlasmaShieldHitS2C::decode,
                PlasmaShieldHitS2C::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static boolean send(ServerPlayer player, PlasmaShieldSyncS2C message) {
        if (player.connection == null || !player.connection.isAcceptingMessages()) {
            return false;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
        return true;
    }

    public static boolean sendHit(ServerPlayer player, PlasmaShieldHitS2C message) {
        if (!message.active()
                || player.connection == null
                || !player.connection.isAcceptingMessages()) {
            return false;
        }
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player), message);
        return true;
    }
}

package com.miningdim.job.munitions.gunsmith.network;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.gunsmith.GunsmithComponentRules;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/** 枪匠热重载规则的专属同步通道。 */
public final class GunsmithRulesNetwork {

    private static final String PROTOCOL_VERSION = "3";

    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MiningConstants.MODID, "gunsmith_rules"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private GunsmithRulesNetwork() {
    }

    public static void register() {
        CHANNEL.registerMessage(0, GunsmithRulesSyncS2C.class,
                GunsmithRulesSyncS2C::encode,
                GunsmithRulesSyncS2C::decode,
                GunsmithRulesSyncS2C::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void sync(ServerPlayer player) {
        if (player.connection == null || !player.connection.isAcceptingMessages()) {
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new GunsmithRulesSyncS2C(GunsmithComponentRules.snapshot()));
    }
}

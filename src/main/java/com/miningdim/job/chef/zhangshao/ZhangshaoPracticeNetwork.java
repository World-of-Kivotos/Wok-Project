package com.miningdim.job.chef.zhangshao;

import com.miningdim.core.MiningConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/** 独立注册练习入口，旧厨师频道和实际料理结算沿用已有协议。 */
@Mod.EventBusSubscriber(modid = MiningConstants.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ZhangshaoPracticeNetwork {
    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MiningConstants.MODID, "zhangshao_practice"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private ZhangshaoPracticeNetwork() {}

    @SubscribeEvent
    public static void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            CHANNEL.registerMessage(0, ZhangshaoPracticeS2C.class,
                    ZhangshaoPracticeS2C::encode, ZhangshaoPracticeS2C::decode,
                    ZhangshaoPracticeS2C::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        });
    }
}

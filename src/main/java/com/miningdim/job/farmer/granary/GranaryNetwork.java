package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/**
 * 粮仓自己的 SimpleChannel (本仓"新 SimpleChannel 包"范式)。
 *
 * 两个包: 客户端点页签 -> {@link GranaryCenterPageC2S} (只报"我要看哪一页"), 服务端现算后回
 * {@link GranaryCenterPageS2C} (那一页的文本行)。**判定永远在服务端**, 客户端回执只用于显示。
 *
 * 协议版本 "1"; 注册时机在模块的 FMLCommonSetupEvent.enqueueWork 里 (线程安全窗口)。
 */
public final class GranaryNetwork {

    private GranaryNetwork() {
    }

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MiningConstants.MODID, "granary"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private static int nextId;

    private static int nextId() {
        return nextId++;
    }

    /** 注册全部 packet (两端同序保证 discriminator 一致)。 */
    public static void register() {
        CHANNEL.registerMessage(nextId(), GranaryCenterPageC2S.class,
                GranaryCenterPageC2S::encode, GranaryCenterPageC2S::decode, GranaryCenterPageC2S::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId(), GranaryCenterPageS2C.class,
                GranaryCenterPageS2C::encode, GranaryCenterPageS2C::decode, GranaryCenterPageS2C::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId(), GranaryTrustActionC2S.class,
                GranaryTrustActionC2S::encode, GranaryTrustActionC2S::decode, GranaryTrustActionC2S::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId(), GranaryTrustStateS2C.class,
                GranaryTrustStateS2C::encode, GranaryTrustStateS2C::decode, GranaryTrustStateS2C::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** 仅供用例: 已注册的 discriminator 个数。 */
    static int registeredCount() {
        return nextId;
    }
}
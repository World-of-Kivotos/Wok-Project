package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/**
 * 腐烂底座自己的 SimpleChannel (本仓"新 SimpleChannel 包"范式: 职业/模块可各开自己的 channel)。
 *
 * 只有一个 S2C 包 ({@link FreshnessSyncS2C}: 时钟 + 保质期 + 档位), 客户端只读它做渲染, 不回任何数据 ——
 * 服务端权威 (客户端算出来的剩余只用于显示, 任何判定仍在服务端重算)。
 *
 * 注册时机在 {@code FMLCommonSetupEvent.enqueueWork} 里 (线程安全窗口), 与厨师那个 channel 同一范式。
 */
public final class FreshnessNetwork {

    private FreshnessNetwork() {
    }

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MiningConstants.MODID, "freshness"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private static int nextId;

    private static int nextId() {
        return nextId++;
    }

    /** 注册全部 packet (两端同序保证 discriminator 一致)。由模块在 enqueueWork 内调用一次。 */
    public static void register() {
        CHANNEL.registerMessage(nextId(), FreshnessSyncS2C.class,
                FreshnessSyncS2C::encode, FreshnessSyncS2C::decode, FreshnessSyncS2C::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** 仅供用例: 下一个 discriminator (断言注册表没被别处悄悄占用)。 */
    static int registeredCount() {
        return nextId;
    }
}

package com.miningdim.job.chef;

import com.miningdim.core.MiningConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/**
 * 厨师专属 SimpleChannel (实现手册 "新 SimpleChannel 包" 范式: 职业框架可另开自己的 channel)。
 *
 * 为何不复用 MiningNetwork.CHANNEL: 复用须改 network 包的中央 MiningNetwork.register (跨包文件), 与 "只在自己
 * package 新建文件" 任务约束冲突; 另开 channel 范式工程已认可 (实现手册 registration 节)。1.20.1
 * NetworkRegistry.newSimpleChannel, 严禁 1.20.4+ custom payload。
 *
 * 只一个 C2S 包 ({@link SeasoningGameC2S} 小游戏输入); 服务端权威校验时序/owner 在包 handler。注册时机在
 * {@link ChefSystem} 的 FMLCommonSetupEvent.enqueueWork 内 (线程安全窗口)。
 */
public final class ChefNetwork {

    private ChefNetwork() {
    }

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MiningConstants.MODID, "chef"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private static int nextId = 0;

    private static int nextId() {
        return nextId++;
    }

    /** 集中注册全部 packet (两端同序保证 discriminator 一致)。由 ChefSystem 在 enqueueWork 内调用一次。 */
    public static void register() {
        CHANNEL.registerMessage(nextId(), SeasoningGameC2S.class,
                SeasoningGameC2S::encode, SeasoningGameC2S::decode, SeasoningGameC2S::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }
}

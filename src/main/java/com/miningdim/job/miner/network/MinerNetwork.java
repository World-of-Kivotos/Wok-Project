package com.miningdim.job.miner.network;

import com.miningdim.core.MiningConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/**
 * 矿工子系统专属 SimpleChannel (1.20.1 NetworkRegistry.newSimpleChannel; 禁 1.20.4+ custom payload)。
 *
 * 为何另开 channel 而非复用 MiningNetwork.CHANNEL: 复用须改中央 {@link com.miningdim.network.MiningNetwork#register}
 * 追加 discriminator (集成阶段统一接线职责)。本子系统按实现手册 "新 SimpleChannel 包" 范式自持 channel,
 * 与既有 main channel 的 ResourceLocation 不同 (miningdim:miner) 故 discriminator 独立, 不破坏既有次序。
 *
 * 两包: {@link MinerToggleC2S} (开关翻转 / 主动技能触发, PLAY_TO_SERVER) 与 {@link MinerHighlightS2C}
 * (探矿/陷阱高亮坐标下发, PLAY_TO_CLIENT)。注册次序两端一致, 在 FMLCommonSetupEvent.enqueueWork 内调用 register()。
 */
public final class MinerNetwork {

    private MinerNetwork() {
    }

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MiningConstants.MODID, "miner"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private static int nextId = 0;

    private static int nextId() {
        return nextId++;
    }

    /** 集中注册全部 packet (两端同序)。由 MinerSystem 在 FMLCommonSetupEvent.enqueueWork 内调用一次。 */
    public static void register() {
        CHANNEL.registerMessage(nextId(), MinerToggleC2S.class,
                MinerToggleC2S::encode, MinerToggleC2S::decode, MinerToggleC2S::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId(), MinerHighlightS2C.class,
                MinerHighlightS2C::encode, MinerHighlightS2C::decode, MinerHighlightS2C::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** 下发高亮包到指定玩家 (探矿/陷阱探测服务端权威查询后发)。 */
    public static void sendHighlight(ServerPlayer player, MinerHighlightS2C msg) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }
}

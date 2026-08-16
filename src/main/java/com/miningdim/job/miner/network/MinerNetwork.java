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
 * 三包: {@link MinerToggleC2S} (开关翻转 / 主动技能触发, PLAY_TO_SERVER)、{@link MinerHighlightS2C}
 * (探矿/陷阱高亮坐标下发, PLAY_TO_CLIENT) 与 {@link MinerStatusS2C} (状态 HUD 瞬态态同步, PLAY_TO_CLIENT)。
 * 注册次序两端一致, 在 FMLCommonSetupEvent.enqueueWork 内调用 register()。
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
        CHANNEL.registerMessage(nextId(), MinerStatusS2C.class,
                MinerStatusS2C::encode, MinerStatusS2C::decode, MinerStatusS2C::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId(), MinerChainHoldC2S.class,
                MinerChainHoldC2S::encode, MinerChainHoldC2S::decode, MinerChainHoldC2S::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId(), MinerChainPreviewC2S.class,
                MinerChainPreviewC2S::encode, MinerChainPreviewC2S::decode, MinerChainPreviewC2S::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId(), MinerChainPreviewS2C.class,
                MinerChainPreviewS2C::encode, MinerChainPreviewS2C::decode, MinerChainPreviewS2C::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** 下发高亮包到指定玩家 (探矿/陷阱探测服务端权威查询后发)。 */
    public static void sendHighlight(ServerPlayer player, MinerHighlightS2C msg) {
        if (!canReceive(player)) {
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }

    /** 下发状态 HUD 包到指定玩家 (瞬态态节流同步; 复用 highlight 的活动连接守卫)。 */
    public static void sendStatus(ServerPlayer player, MinerStatusS2C msg) {
        if (!canReceive(player)) {
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }

    /** 下发连锁预览包到指定玩家 (服务端权威跑 plan 后发; 复用同一活动连接守卫)。 */
    public static void sendChainPreview(ServerPlayer player, MinerChainPreviewS2C msg) {
        if (!canReceive(player)) {
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }

    /**
     * S2C 发包健壮性守卫 (镜像 {@link com.miningdim.network.MiningNetwork} 的同名守卫): 仅向持有活动连接的玩家
     * 下发定向包。向尚未连上 (握手未完成) 或正在断开 (channel 已关闭) 的连接发包, 在 Forge 网络栈深处会因 netty
     * channel 为空/关闭而异常 —— 这不是业务错误, 故在出口处过滤为 no-op。connection 在玩家完成登录前可能为 null,
     * 故先做空判防御极端时序 (断连玩家 connection 已置 null)。
     */
    private static boolean canReceive(ServerPlayer player) {
        return player.connection != null && player.connection.isAcceptingMessages();
    }
}

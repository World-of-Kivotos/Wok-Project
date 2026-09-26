package com.miningdim.title.network;

import com.miningdim.core.MiningConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 称号模块自有的 SimpleChannel {@code miningdim:title}。
 *
 * 不挂在核心的 {@code MiningNetwork} 主通道上: 主通道归 wok-core, 从那里登记称号包会形成 wok-core → wok-title
 * 的反向依赖 (精英怪体型包就是这样欠下 D003 债务的), 也会挪动主通道既有包的 discriminator 顺序。
 * 与矿工、塔罗、护盾、渔业图鉴一样, 由模块在 FMLCommonSetup 自行注册自己的通道。
 */
public final class TitleNetwork {

    private static final String PROTOCOL_VERSION = "1";
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MiningConstants.MODID, "title"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private TitleNetwork() {
    }

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        CHANNEL.registerMessage(0, S2CTitleSync.class,
                S2CTitleSync::encode,
                S2CTitleSync::decode,
                S2CTitleSync::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /** 把 subject 的名牌称号发给单个观察者 (开始追踪时补发给后来者, 或登录/换维度/重生时发给自己)。 */
    public static void sendTo(ServerPlayer receiver, ServerPlayer subject, @Nullable Component title) {
        if (!canReceive(receiver)) {
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> receiver), new S2CTitleSync(subject.getId(), title));
    }

    /** 佩戴变化: 发给所有正在看着 subject 的客户端以及他自己。 */
    public static void sendToTrackersAndSelf(ServerPlayer subject, @Nullable Component title) {
        // 只拦"根本没有网络连接"的伪玩家 (广播会对自己调 connection.send); 本人连接正在断开时观察者仍应收到。
        if (subject.connection == null) {
            return;
        }
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> subject),
                new S2CTitleSync(subject.getId(), title));
    }

    private static boolean canReceive(ServerPlayer player) {
        return player.connection != null && player.connection.isAcceptingMessages();
    }
}

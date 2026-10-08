package com.miningdim.district.notice;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 不靠登录门的投递时机 (设计文档 22.12): <b>身份已验证才发, 否则留在队列里</b> (fail closed)。生产已改装
 * {@link LoginGateNoticeGate}; 这个实现只作装 gate 之前的占位与 GameTest 的对照。
 *
 * <ul>
 *   <li>"身份已验证" 默认 = 服务器开着正版验证 ({@link MinecraftServer#usesAuthentication()}: 单人存档、局域网、正版服),
 *       或者是 GameTest 服务端 (mock 玩家没有真实身份可言)。</li>
 *   <li>不做正版验证的服务器 (整合包用 AccessHub 的 /login 确认身份) 上, 身份要等登录确认之后才算数。所以在登录门
 *       (PR #72) 合入之前, 这样的服务器上一条都不发: 行留在队列里 (30 天), 开服记一条 WARN
 *       ({@link NoticeDeliveryGates#checkWiring})。合入之后按 22.12 换成按"登录已确认"判定的 gate。</li>
 *   <li>{@code onPlayerJoined}: 身份已验证就当场调回调 (上线即发), 否则什么都不做。</li>
 * </ul>
 */
public final class DefaultNoticeGate implements NoticeDeliveryGate {

    private final Predicate<ServerPlayer> identityVerified;
    private volatile Consumer<ServerPlayer> deliverPending = player -> {
    };

    /** 生产用: 按 {@link #identityVerifiedByServer}。 */
    public DefaultNoticeGate() {
        this(DefaultNoticeGate::identityVerifiedByServer);
    }

    /** 指定"身份已验证"的判定 (GameTest 用它核对不放行时的行为)。 */
    public DefaultNoticeGate(Predicate<ServerPlayer> identityVerified) {
        this.identityVerified = Objects.requireNonNull(identityVerified, "identityVerified");
    }

    /** 服务器开着正版验证, 或者是 GameTest 服务端。 */
    public static boolean identityVerifiedByServer(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return server != null && verifiesIdentity(server);
    }

    /** 这台服务器上默认 gate 放不放行 (开服检查也用它)。 */
    public static boolean verifiesIdentity(MinecraftServer server) {
        return server.usesAuthentication() || server instanceof GameTestServer;
    }

    @Override
    public boolean canDeliverNow(ServerPlayer player) {
        return identityVerified.test(player);
    }

    @Override
    public void install(Consumer<ServerPlayer> deliverPending) {
        this.deliverPending = Objects.requireNonNull(deliverPending, "deliverPending");
    }

    @Override
    public void onPlayerJoined(ServerPlayer player) {
        if (canDeliverNow(player)) {
            deliverPending.accept(player);
        }
    }
}

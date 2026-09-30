package com.miningdim.district.notice;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 当前生效的 {@link NoticeDeliveryGate} (设计文档 22.12)。{@code DistrictSystem.register} 装一次; 进程级, 不随开服停服
 * 变化 (登录门的监听者也只登记一次, 停服不清)。没装之前是一个回调为空的 {@link DefaultNoticeGate}。
 */
public final class NoticeDeliveryGates {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/district");

    private static volatile NoticeDeliveryGate current = new DefaultNoticeGate();
    private static volatile Consumer<ServerPlayer> callback = player -> {
    };

    private NoticeDeliveryGates() {
    }

    /** 装上 gate, 并把"这名玩家可以收通知了"的回调登记给它。 */
    public static synchronized void install(NoticeDeliveryGate gate, Consumer<ServerPlayer> deliverPending) {
        Objects.requireNonNull(gate, "gate");
        Objects.requireNonNull(deliverPending, "deliverPending");
        gate.install(deliverPending);
        callback = deliverPending;
        current = gate;
    }

    public static NoticeDeliveryGate current() {
        return current;
    }

    /** 登录门 (PR #72) 的类名: 两个分支合并之后才存在。 */
    public static final String LOGIN_GATE_CLASS = "com.miningdim.core.auth.PlayerLoginGate";

    /** 类路径上有没有登录门 (只加载、不初始化)。 */
    public static boolean loginGatePresent() {
        try {
            Class.forName(LOGIN_GATE_CLASS, false, NoticeDeliveryGates.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    /**
     * 开服检查 (功能打开时, DistrictSystem.onServerStarting): 装着的还是 {@link DefaultNoticeGate} 时,
     * <ul>
     *   <li>登录门的类已经在 (两个分支合并了, 却没按 22.12 换 gate) → ERROR;</li>
     *   <li>服务器不做正版验证、也没有登录门 → WARN: 通知一条都不发, 留在队列里 (最多 30 天)。</li>
     * </ul>
     */
    public static void checkWiring(MinecraftServer server) {
        if (!(current instanceof DefaultNoticeGate)) {
            return;
        }
        if (loginGatePresent()) {
            LOGGER.error("[miningdim] district chat notices still use DefaultNoticeGate although {} is on the "
                    + "classpath: install the login-confirmed gate as described in District_Backend_Design.md 22.12",
                    LOGIN_GATE_CLASS);
        }
        if (!DefaultNoticeGate.verifiesIdentity(server)) {
            LOGGER.warn("[miningdim] district chat notices are HELD BACK: this server does not use online-mode "
                    + "authentication and the login gate (PR #72) is not installed. Notices stay queued for up to 30 "
                    + "days and are delivered once the login gate is merged (District_Backend_Design.md 22.12)");
        }
    }

    /**
     * GameTest 专用 (只在 GameTest 服务端可用): 临时换一个 gate, 把当前的回调登记给它; close 时换回原来的 gate,
     * 不重新 install (原来的 gate 登记过的外部监听者不会被登记第二次)。
     */
    public static synchronized Restore swapForTest(NoticeDeliveryGate gate) {
        if (!(ServerLifecycleHooks.getCurrentServer() instanceof GameTestServer)) {
            throw new IllegalStateException("NoticeDeliveryGates.swapForTest is only available on the GameTest server");
        }
        Objects.requireNonNull(gate, "gate");
        NoticeDeliveryGate previous = current;
        gate.install(callback);
        current = gate;
        return () -> {
            synchronized (NoticeDeliveryGates.class) {
                current = previous;
            }
        };
    }

    /** {@link #swapForTest} 的复原句柄 (close 不抛受检异常)。 */
    @FunctionalInterface
    public interface Restore extends AutoCloseable {
        @Override
        void close();
    }
}

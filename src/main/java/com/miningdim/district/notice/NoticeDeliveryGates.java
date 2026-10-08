package com.miningdim.district.notice;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 当前生效的 {@link NoticeDeliveryGate} (设计文档 22.12)。{@code DistrictSystem.register} 装一次; 进程级, 不随开服停服
 * 变化 (登录门的监听者也只登记一次, 停服不清)。没装之前是一个回调为空的 {@link DefaultNoticeGate}。
 */
public final class NoticeDeliveryGates {

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

package com.miningdim.entrance;

import com.miningdim.core.Difficulty;
import net.minecraft.server.level.ServerPlayer;

/**
 * entrance 子系统的运行期接线点 (seam holder)。entry 子系统在服务端启动 (ServerStartedEvent, gateway 建好后)
 * 调 {@link #bind} 注入 {@link EntranceTrigger}; 服务端停止时调 {@link #unbind} 清引用, 防跨存档脏引用。
 *
 * 入口方块在被交互时经 {@link #requestEnter} 转发。注入只在主线程的启动/停止事件发生, 运行期只读,
 * 故无需加锁。未接线时 (极早期或维度未起) 静默忽略 (玩家可重试), 不抛异常打断方块交互。
 */
public final class EntranceHooks {

    private EntranceHooks() {
    }

    private static volatile EntranceTrigger trigger;

    /** entry 子系统启动期注入真正的入场触发器。 */
    public static void bind(EntranceTrigger impl) {
        if (impl == null) {
            throw new IllegalArgumentException("EntranceTrigger must not be null");
        }
        trigger = impl;
    }

    /** 服务端停止时清空。 */
    public static void unbind() {
        trigger = null;
    }

    /**
     * 转发一次入场请求。未接线 (服务端尚未完成启动接线) 时返回 false, 调用方据此不做冷却扣减,
     * 让玩家可在维度就绪后重试。
     */
    public static boolean requestEnter(ServerPlayer player, Difficulty difficulty) {
        EntranceTrigger t = trigger;
        if (t == null) {
            return false;
        }
        t.requestEnter(player, difficulty);
        return true;
    }
}

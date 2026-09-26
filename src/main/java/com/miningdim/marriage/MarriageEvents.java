package com.miningdim.marriage;

import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 婚姻模块对外的监听接口 (Achievement_System_DesignSpec 9.10: 成就"执子之手"当场触发、"千里赴约")。
 *
 * 与 {@code MiningServices.registerInstanceResetListener} 同一多播范式: 下游模块 (成就) 在 mod 构造期注册进来,
 * 本模块不依赖它们; 列表随进程存活, 服务端停止时不清。监听器在服务端主线程上调用, 抛出的异常由这里记录后吞掉 ——
 * 婚礼与传送此刻都已经完成, 监听器的失败不能把它们报成失败。监听器仍应自行捕获并记录自己的失败。
 */
public final class MarriageEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/marriage");

    private static final List<WeddingListener> weddingListeners = new CopyOnWriteArrayList<>();
    private static final List<TeleportListener> teleportListeners = new CopyOnWriteArrayList<>();

    private MarriageEvents() {
    }

    /** 婚礼成功监听。 */
    @FunctionalInterface
    public interface WeddingListener {

        /**
         * 一场婚礼已经完成 (关系已登记、戒指已换、双方婚姻指针已写好)。每场婚礼对双方各调用一次。
         *
         * @param player 本次通知针对的一方
         * @param spouse 另一方
         */
        void onWedding(ServerPlayer player, ServerPlayer spouse);
    }

    /** 传送到伴侣身边的监听。 */
    @FunctionalInterface
    public interface TeleportListener {

        /**
         * 一次传送到伴侣身边已经完成 (传送已执行、冷却已记)。
         *
         * @param traveller          发起并被传送的一方
         * @param spouse             伴侣 (传送的目的地)
         * @param horizontalDistance 蓄力开始时双方的水平距离 (只算 dx、dz; 传送恒在同一维度内)
         */
        void onSpouseTeleport(ServerPlayer traveller, ServerPlayer spouse, double horizontalDistance);
    }

    /** 注册婚礼监听器; 按注册顺序通知。 */
    public static void registerWeddingListener(WeddingListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("wedding listener must not be null");
        }
        weddingListeners.add(listener);
    }

    /** 注册传送监听器; 按注册顺序通知。 */
    public static void registerTeleportListener(TeleportListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("teleport listener must not be null");
        }
        teleportListeners.add(listener);
    }

    /** 通知一场已完成的婚礼, 双方各一次 ({@link MarriageEngine#wed} 成功返回前的最后一步)。 */
    static void fireWedding(ServerPlayer first, ServerPlayer second) {
        for (WeddingListener listener : weddingListeners) {
            notifyWedding(listener, first, second);
            notifyWedding(listener, second, first);
        }
    }

    /** 通知一次已完成的传送 ({@link MarriageTeleport} 完成传送的最后一步)。 */
    static void fireSpouseTeleport(ServerPlayer traveller, ServerPlayer spouse, double horizontalDistance) {
        for (TeleportListener listener : teleportListeners) {
            try {
                listener.onSpouseTeleport(traveller, spouse, horizontalDistance);
            } catch (RuntimeException failure) {
                LOGGER.error("[miningdim] spouse teleport listener failed for {}",
                        traveller.getGameProfile().getName(), failure);
            }
        }
    }

    private static void notifyWedding(WeddingListener listener, ServerPlayer player, ServerPlayer spouse) {
        try {
            listener.onWedding(player, spouse);
        } catch (RuntimeException failure) {
            LOGGER.error("[miningdim] wedding listener failed for {}", player.getGameProfile().getName(), failure);
        }
    }
}

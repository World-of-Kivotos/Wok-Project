package com.miningdim.quest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 任务子系统的静态门面 (与 {@code CaseServices} / {@code EconomyServices} 同范式)。
 *
 * 事件钩子挂在 Forge 总线上, 拿不到构造期注入的引用, 只能经静态门面取服务; 服务本身在 ServerStarting 绑定、
 * ServerStopping 解绑, 因此单机连开两个世界时不会把上一个世界的任务数据带进来。
 *
 * 领奖监听 ({@link #registerClaimListener}) 与 {@code MiningServices.registerInstanceResetListener} 同一多播范式:
 * 下游模块 (成就) 在 mod 构造期注册进来, 本模块不依赖它们。监听器列表<b>不</b>随 {@link #reset} 清空 —— 它们只在
 * mod 构造期注册一次, 清掉之后第二个世界就再也收不到领奖通知。
 */
public final class QuestServices {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/quest");

    private static volatile QuestService service;

    private static final List<QuestClaimListener> claimListeners = new CopyOnWriteArrayList<>();

    private QuestServices() {
    }

    public static void register(QuestService instance) {
        if (instance == null) {
            throw new IllegalArgumentException("quest service must not be null");
        }
        service = instance;
    }

    /**
     * 取服务实例。未绑定时抛 —— 调用方应当先过 {@link #active()}; 走到这里说明生命周期假设已经破了,
     * 返回 null 只会把问题推到更远的地方再炸。
     */
    public static QuestService service() {
        QuestService current = service;
        if (current == null) {
            throw new IllegalStateException("quest service is not bound (server not started?)");
        }
        return current;
    }

    public static boolean isRegistered() {
        return service != null;
    }

    /**
     * 服务已绑定<b>且</b>配置开关为开。所有事件钩子的第一道闸 —— 关掉开关后不应再有任何任务副作用,
     * 包括进度累计与随机事件抛出。
     */
    public static boolean active() {
        return service != null && QuestConfig.ENABLED.get();
    }

    /** 注册一个领奖监听器 (见 {@link QuestClaimListener}); 按注册顺序通知。 */
    public static void registerClaimListener(QuestClaimListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("quest claim listener must not be null");
        }
        claimListeners.add(listener);
    }

    /** 通知一次已完成的领奖。逐个吞掉监听器的异常并记日志: 奖励已经发出, 不能让监听器的失败把领奖报成失败。 */
    static void fireClaimed(QuestClaim claim) {
        for (QuestClaimListener listener : claimListeners) {
            try {
                listener.onQuestClaimed(claim);
            } catch (RuntimeException failure) {
                LOGGER.error("[miningdim] quest claim listener failed for {} claiming {}",
                        claim.player().getGameProfile().getName(), claim.definition().id(), failure);
            }
        }
    }

    /** 解绑服务实例; 领奖监听器列表不清 (见类注释)。 */
    public static void reset() {
        service = null;
    }
}

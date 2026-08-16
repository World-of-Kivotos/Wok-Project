package com.miningdim.quest;

/**
 * 任务子系统的静态门面 (与 {@code CaseServices} / {@code EconomyServices} 同范式)。
 *
 * 事件钩子挂在 Forge 总线上, 拿不到构造期注入的引用, 只能经静态门面取服务; 服务本身在 ServerStarting 绑定、
 * ServerStopping 解绑, 因此单机连开两个世界时不会把上一个世界的任务数据带进来。
 */
public final class QuestServices {

    private static volatile QuestService service;

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

    public static void reset() {
        service = null;
    }
}

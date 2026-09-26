package com.miningdim.title;

/**
 * 称号门面的运行期定位器 (仿 EconomyServices / MarketServices 平行定位器范式, 不改 core.MiningServices)。
 * 称号子系统在 ServerStartingEvent 注入, ServerStoppingEvent 清空。
 */
public final class TitleServices {

    private TitleServices() {
    }

    private static volatile ITitleService titleService;

    /** 称号子系统在服务端启动期注入门面实现 (null 抛 IllegalArgumentException)。 */
    public static void registerTitleService(ITitleService service) {
        if (service == null) {
            throw new IllegalArgumentException("Cannot register null ITitleService");
        }
        titleService = service;
    }

    /** 取称号门面 (未注入抛 IllegalStateException, 不返回 null)。 */
    public static ITitleService titleService() {
        ITitleService current = titleService;
        if (current == null) {
            throw new IllegalStateException(
                    "TitleServices: ITitleService not registered yet (title subsystem binds it at server start)");
        }
        return current;
    }

    /** 是否已注入 (接线就绪判定, 供未接线时的可用性分支)。 */
    public static boolean isRegistered() {
        return titleService != null;
    }

    /** 服务端停止时清空, 防跨存档/跨重启脏引用 (供 ServerStoppingEvent 调用)。 */
    public static void reset() {
        titleService = null;
    }
}

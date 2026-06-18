package com.miningdim.job.tarot;

import com.miningdim.economy.IEconomyService;

/**
 * 经济门面接线 seam (TarotReader spec 第十章; 仿 entrance.EntranceHooks 解耦范式)。
 *
 * 为何用 seam 而非直接定位器: 地基 {@link IEconomyService} 当前只有接口、无实现、无定位器 (economy 子系统
 * 未注入任何 {@link IEconomyService} 到 MiningServices 或自有定位器 —— 见 foundationGaps)。塔罗师扣费/发奖
 * 必须经此接口, 故在本包置一个运行期可绑定的 seam: 集成阶段经济子系统就绪后 {@link #bind} 注入真实实现,
 * 塔罗师 {@link #service()} 取用。未绑定即调用是装配缺陷, 在最外层 (卡包 use 边界) 自然抛 IllegalStateException
 * 暴露 (异常纪律: 不静默 fallback 返回 false 掩盖未接线)。
 *
 * 绑定/解绑只在服务端启动/停止单线程发生; volatile 保运行期读可见性。
 */
public final class TarotEconomyHooks {

    private TarotEconomyHooks() {
    }

    private static volatile IEconomyService service;

    /** 集成阶段经济子系统就绪后注入 (null 抛 IllegalArgumentException)。 */
    public static void bind(IEconomyService impl) {
        if (impl == null) {
            throw new IllegalArgumentException("Cannot bind null IEconomyService");
        }
        service = impl;
    }

    /** 服务端停止解绑, 防跨存档脏引用。 */
    public static void unbind() {
        service = null;
    }

    /** 是否已接线经济服务 (卡包售卖前的可用性判定)。 */
    public static boolean isBound() {
        return service != null;
    }

    /**
     * 取经济服务门面; 未接线抛 IllegalStateException (装配缺陷, 在卡包 use 边界冒泡, 不静默掩盖)。
     */
    public static IEconomyService service() {
        IEconomyService s = service;
        if (s == null) {
            throw new IllegalStateException(
                    "TarotEconomyHooks: IEconomyService not bound (economy subsystem must bind it at server start)");
        }
        return s;
    }
}

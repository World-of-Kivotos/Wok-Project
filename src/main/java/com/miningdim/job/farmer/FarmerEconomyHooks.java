package com.miningdim.job.farmer;

import com.miningdim.economy.IEconomyService;

/**
 * 经济门面接线 seam (FarmingXP_Mod_DesignSpec 第八节; 与 tarot.TarotEconomyHooks 同范式, 仿 entrance.EntranceHooks)。
 *
 * 为何用 seam: 地基 {@link IEconomyService} 当前只有接口、无实现、无定位器 (economy 子系统未注入任何实现,
 * 见 foundationGaps)。农夫卖菜发放信用点必须经此接口, 故在本包置运行期可绑定 seam: 集成阶段经济子系统就绪后
 * {@link #bind} 注入真实实现, 卖菜路径取用。
 *
 * 未接线时的纪律: 卖菜入口 ({@link FarmerWheatSellService#sell}) 先查 {@link #isBound()}, 未绑定即不扣物品也
 * 不发币直接返回 (集成阶段 bind 后才结算, 与 chef.ChefEconomyHooks.tryChargeTableUse 同范式)。{@link #service()}
 * 仍对 "已通过 isBound 检查后却取不到实现" 这一真正装配缺陷 fail-fast 抛 IllegalStateException, 不静默 fallback。
 *
 * 绑定/解绑只在服务端启动/停止单线程发生; volatile 保运行期读可见性。
 */
public final class FarmerEconomyHooks {

    private FarmerEconomyHooks() {
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

    /** 是否已接线经济服务 (卖菜前的可用性判定)。 */
    public static boolean isBound() {
        return service != null;
    }

    /** 取经济服务门面; 未接线抛 IllegalStateException (装配缺陷, 在卖菜边界冒泡)。 */
    public static IEconomyService service() {
        IEconomyService s = service;
        if (s == null) {
            throw new IllegalStateException(
                    "FarmerEconomyHooks: IEconomyService not bound (economy subsystem must bind it at server start)");
        }
        return s;
    }
}

package com.miningdim.job.chef;

import com.miningdim.economy.IEconomyService;

/**
 * 经济门面接线 seam (与 farmer.FarmerEconomyHooks / tarot.TarotEconomyHooks 同范式, 仿 entrance.EntranceHooks)。
 *
 * 为何用 seam: 地基 {@link IEconomyService} 当前只有接口、无实现、无定位器 (见 foundationGaps)。调味台做菜
 * 的信用点 sink 必须经此接口; 集成阶段经济子系统就绪后 {@link #bind} 注入真实实现, 做菜路径
 * {@link #tryChargeTableUse} 取用。未绑定时 ({@link #isBound()} false) 做菜不扣费照常完成 (经济未上线前
 * 不阻塞核心循环; 绑定后自动开始扣费) —— 这是 "经济可选 sink" 语义, 非掩盖空值: 扣费成功/失败由 tryCharge
 * 返回, 余额不足时拒绝做菜由调用方据返回值决定 (见 SeasoningTableBlockEntity.finishCooking)。
 *
 * 绑定/解绑只在服务端启动/停止单线程发生; volatile 保运行期读可见性。
 */
public final class ChefEconomyHooks {

    private ChefEconomyHooks() {
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

    /** 是否已接线经济服务。 */
    public static boolean isBound() {
        return service != null;
    }

    /**
     * 调味台做菜信用点 sink: 未绑定经济服务 -> 返 true 放行 (经济未上线不阻塞做菜); 已绑定 -> 委派
     * {@link IEconomyService#tryCharge} (余额足扣并返 true, 不足返 false 由调用方拒绝做菜)。
     *
     * @param player 操作厨师
     * @param cost   做菜信用点成本 ({@link ChefConfig#TABLE_USE_COST_CREDIT}); <=0 视为免费直接放行
     * @return 是否允许做菜 (扣费成功 / 免费 / 经济未接线 = true; 余额不足 = false)
     */
    public static boolean tryChargeTableUse(net.minecraft.server.level.ServerPlayer player, long cost) {
        if (cost <= 0L || !isBound()) {
            return true;
        }
        return service.tryCharge(player, com.miningdim.economy.Currency.CREDIT, cost);
    }
}

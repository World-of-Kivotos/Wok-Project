package com.miningdim.economy;

/**
 * 货币层服务定位器 (实现手册 "约定 4" 同范式: 在 economy 包另建平行定位器, 避免改 core.MiningServices)。
 * 范式对齐 {@link com.miningdim.core.MiningServices} / {@link com.miningdim.job.JobServices}:
 * 显式强类型私有静态字段 + requireNonNull 注入 + require 取用 (未注入抛 IllegalStateException,
 * 严禁返回 null 掩盖装配缺陷) + reset()。
 *
 * 收敛意图 (审查 Major: 消除 per-job static volatile 重复 seam): farmer/tarot/chef 三处各自复制的
 * IEconomyService seam 中长期应收敛为经本定位器 {@link #economyService()} 取用 (服务定位器已是项目既定模式)。
 * 本任务 (货币层) 只交付定位器本体; 把三处 seam 改为经本定位器取用属各职业包的接线, 见 notes 报告 (不越界改)。
 *
 * 全部静态, 生命周期 = mod 进程。注入只在服务端启动期发生 (单线程), 故无需锁; 运行期只读。
 */
public final class EconomyServices {

    private EconomyServices() {
    }

    private static IEconomyService economyService;

    /** 经济子系统在服务端启动期注入门面实现 (null 抛 IllegalArgumentException)。 */
    public static void registerEconomyService(IEconomyService service) {
        if (service == null) {
            throw new IllegalArgumentException("Cannot register null IEconomyService");
        }
        economyService = service;
    }

    /** 取货币门面 (未注入抛 IllegalStateException, 不返回 null)。 */
    public static IEconomyService economyService() {
        if (economyService == null) {
            throw new IllegalStateException(
                    "EconomyServices: IEconomyService not registered yet (economy subsystem binds it at server start)");
        }
        return economyService;
    }

    /** 是否已注入 (接线就绪判定, 供未接线时的可用性分支)。 */
    public static boolean isRegistered() {
        return economyService != null;
    }

    /** 服务端停止时清空, 防跨存档/跨重启脏引用 (供 ServerStoppingEvent 调用)。 */
    public static void reset() {
        economyService = null;
    }
}

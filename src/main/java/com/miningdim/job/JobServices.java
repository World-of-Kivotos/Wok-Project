package com.miningdim.job;

/**
 * 职业框架服务定位器 (实现手册 "约定 3" 规避: 在 job 包另建平行定位器, 避免改 core.MiningServices)。
 * 范式对齐 {@link com.miningdim.core.MiningServices}: 显式强类型私有静态字段 + requireNonNull 注入 +
 * require 取用 (未注入抛 IllegalStateException, 严禁返回 null) + reset()。
 *
 * 跨职业子系统协作走本定位器取 {@link IJobService}; 仅当确需与 mining 既有子系统协作时才碰 core.MiningServices。
 *
 * 全部静态, 生命周期 = mod 进程。注入只在子系统 register / 服务端启动期发生 (单线程), 故无需锁; 运行期只读。
 */
public final class JobServices {

    private JobServices() {
    }

    private static IJobService jobService;

    /** 子系统在 register 内注入门面实现 (null 抛 IllegalArgumentException)。 */
    public static void registerJobService(IJobService service) {
        if (service == null) {
            throw new IllegalArgumentException("Cannot register null IJobService");
        }
        jobService = service;
    }

    /** 取职业框架门面 (未注入抛 IllegalStateException, 不返回 null)。 */
    public static IJobService jobService() {
        if (jobService == null) {
            throw new IllegalStateException(
                    "JobServices: IJobService not registered yet (check Subsystem register order)");
        }
        return jobService;
    }

    /** 服务端停止时清空, 防跨存档/跨重启脏引用 (供 ServerStoppingEvent 调用)。 */
    public static void reset() {
        jobService = null;
    }
}

package com.miningdim.economy;

import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
 * faucet 入账监听 ({@link #registerFaucetListener}) 与 {@code MiningServices.registerInstanceResetListener} 同一
 * 多播范式: 下游模块 (成就) 在 mod 构造期把自己注册进来, 本模块不依赖它们。
 *
 * 全部静态, 生命周期 = mod 进程。注入只在服务端启动期发生 (单线程), 故无需锁; 运行期只读。
 */
public final class EconomyServices {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/economy");

    private EconomyServices() {
    }

    private static IEconomyService economyService;

    /** faucet 入账监听器; mod 构造期注册, {@link #reset} 不清 (没有重新注册的时机)。 */
    private static final List<FaucetListener> faucetListeners = new CopyOnWriteArrayList<>();

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

    /** 注册一个 faucet 入账监听器 (见 {@link FaucetListener}); 按注册顺序通知。 */
    public static void registerFaucetListener(FaucetListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Cannot register null FaucetListener");
        }
        faucetListeners.add(listener);
    }

    /**
     * 通知一笔已提交的 faucet 入账 ({@link EconomyService#grantDaily} 经账本的提交后队列调用)。与实例重置广播不同,
     * 这里逐个吞掉监听器的异常并记日志: 钱已经落盘, 让监听器的失败冒回入账方, 只会把一笔成功的入账报成失败。
     */
    static void fireFaucetCredited(ServerPlayer player, String faucetKey, long credited) {
        for (FaucetListener listener : faucetListeners) {
            try {
                listener.onFaucetCredited(player, faucetKey, credited);
            } catch (RuntimeException failure) {
                LOGGER.error("[miningdim] faucet listener failed for {} ({} credit on {})",
                        player.getGameProfile().getName(), credited, faucetKey, failure);
            }
        }
    }

    /** 服务端停止时清空, 防跨存档/跨重启脏引用 (供 ServerStoppingEvent 调用)。监听器列表不清。 */
    public static void reset() {
        economyService = null;
    }
}

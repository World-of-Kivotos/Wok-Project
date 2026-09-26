package com.miningdim.economy;

import net.minecraft.server.level.ServerPlayer;

/**
 * 信用点 faucet 入账监听 (Achievement_System_DesignSpec 9.10, 成就统计项 credits_earned 的来源)。
 *
 * 只由 {@link IEconomyService#grantDaily} 触发, 且在那笔入账所在的<b>最外层</b>账本事务提交之后: 批量结算
 * (如 {@code recordMinedOreDrops} 把一次连锁的全部产出合并成一个事务) 里的每一笔都要等整批提交才通知, 整批回滚则
 * 一笔都不通知。{@code grant}、{@code grantBundle} (市场收入、管理员发放、退款、婚姻) 与青辉石入账都不触发。
 *
 * <p>经 {@link EconomyServices#registerFaucetListener} 在 mod 构造期注册, 在服务端主线程上调用。监听器抛出的异常由
 * 广播方记录后吞掉, 不影响入账方; 监听器仍应自行捕获并记录自己的失败。
 */
@FunctionalInterface
public interface FaucetListener {

    /**
     * 一笔 faucet 入账已提交。
     *
     * @param player    入账玩家
     * @param faucetKey 计数键 (所有信用点 faucet 共用 {@link EconomyConstants#GLOBAL_DAILY_CREDIT_FAUCET_KEY},
     *                  任务奖励走自己的键)
     * @param credited  衰减后实际入账的信用点 (&gt; 0; 衰减后不足 1 点的入账不通知)
     */
    void onFaucetCredited(ServerPlayer player, String faucetKey, long credited);
}

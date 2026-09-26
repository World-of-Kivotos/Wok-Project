package com.miningdim.caseopening;

/**
 * 开箱结算监听 (Achievement_System_DesignSpec 9.10, 成就开箱条件的来源)。
 *
 * 在落结算锚 ({@code markEconomySettled}) 的那个事务提交之后调用, 每个开箱 id 只调用一次 (锚从无到有的那一次):
 * 正常开箱、登录恢复补扣款、提交结果不明后的对账与启动期对账四条落锚路径都覆盖; 事务回滚时不调用。
 * 启动期对账时开箱人通常不在线, 由监听方自行决定怎么处理 (成就在登录时经
 * {@link CaseOpeningService#bestSettledRarity} 补查)。
 *
 * <p>经 {@link CaseServices#registerOpeningListener} 在 mod 构造期注册, 在服务端主线程上调用。监听器抛出的异常由
 * 广播方记录后吞掉, 不影响已经完成的开箱; 监听器仍应自行捕获并记录自己的失败。
 */
@FunctionalInterface
public interface CaseOpeningListener {

    /** 一次开箱已结算。 */
    void onOpeningSettled(SettledOpening opening);
}

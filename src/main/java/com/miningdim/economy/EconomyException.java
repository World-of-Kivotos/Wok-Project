package com.miningdim.economy;

/**
 * 货币层领域异常 (CLAUDE.md 异常纪律 / 任务约束 "余额不足/非法转账必须抛领域异常自然冒泡, 不静默返回")。
 *
 * 非受检 (RuntimeException), 遵循 C9 自然冒泡: {@link IEconomyService} 扣费/转账校验失败时直接抛出,
 * 业务层 (职业代码) 不得本地 try/catch 生吞, 仅在最外层 (命令 / 网络 handler / 子系统) 经
 * {@link com.miningdim.error.MiningErrors#guardForPlayer} 兜底转玩家友好文案。
 *
 * {@link Reason} 区分失败原因, 供最外层选择 {@link com.miningdim.error.MiningMessages} 的 i18n 文案。
 * 与 {@link com.miningdim.core.InstanceLimitException} 同范式 (final + Reason enum + 不暴露堆栈给玩家)。
 */
public final class EconomyException extends RuntimeException {

    /** 失败原因。 */
    public enum Reason {
        /** 余额不足以支付本次扣费 (tryCharge 扣款方; 货币层 tryCharge 实际返 false 不抛, 此码供 DB 交易层复用)。 */
        INSUFFICIENT_FUNDS,
        /**
         * 试图转移一种绑定货币 (青辉石不可转移, 1.2/附录)。货币层无 P2P 入口 (反洗钱, 0.3-46),
         * 此码供 DB 交易层在拒绝 AZURE 挂单时抛出, 与 {@link Currency#isTransferable()} 同源裁决。
         */
        CURRENCY_NOT_TRANSFERABLE,
        /** 金额为负或非法 (扣费/入账/转账金额必须 > 0)。 */
        ILLEGAL_AMOUNT,
        /** 同一幂等 operationId 被另一玩家或不同金额复用。 */
        OPERATION_CONFLICT,
        /** 入账会导致余额溢出 long 上界 (防 7.3 M0 统计被脏数据击穿)。 */
        BALANCE_OVERFLOW
    }

    private final Reason reason;

    public EconomyException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}

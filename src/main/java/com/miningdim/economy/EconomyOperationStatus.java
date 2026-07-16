package com.miningdim.economy;

/**
 * 带幂等键的双币扣款状态。
 *
 * <p>{@link #NONE} 仅表示未找到已持久化操作（也用于首次余额不足）；不会写入存档。其余三个状态会随
 * {@link EconomyWalletData} 持久化，供跨重启的 Saga 恢复查询。</p>
 */
public enum EconomyOperationStatus {
    /** 不存在持久化操作，或首次尝试因余额不足而未扣款。 */
    NONE,
    /** 两种货币已原子扣除，等待外部业务提交或退款。 */
    CHARGED,
    /** 外部业务已完成；扣款保留，操作进入终态。 */
    COMPLETED,
    /** 扣款已原子退回；操作进入终态。 */
    REFUNDED;

    /** 当前状态下两种货币是否仍保持扣除。 */
    public boolean fundsRemainCharged() {
        return this == CHARGED || this == COMPLETED;
    }

    /** 是否已进入不可再反向流转的终态。 */
    public boolean isTerminal() {
        return this == COMPLETED || this == REFUNDED;
    }
}

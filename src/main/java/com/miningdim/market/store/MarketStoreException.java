package com.miningdim.market.store;

/**
 * 跳蚤市场存储层领域异常 (契约第 4 节: DAO 所有方法的 SQLException 包装成本非受检异常自然冒泡, 不吞)。
 *
 * 为什么非受检 (RuntimeException): 遵循 CLAUDE.md / 契约第 0 节异常纪律 —— 存储层错误必须自然冒泡到
 * 最外层 Gateway ({@code WebUiServerDispatcher.dispatchAndRespond}) 统一兜底, 中间业务层 (交易引擎 B)
 * 不得本地 try/catch 生吞。DAO 内部允许 catch {@link java.sql.SQLException} 的唯一目的是把受检异常
 * 转译成本类重抛 (资源边界包装, 非吞异常), 保留原始 SQLException 为 cause 以不丢失底层诊断现场。
 *
 * 与货币层 {@link com.miningdim.economy.EconomyException} 同纪律 (final RuntimeException, 错误冒泡),
 * 但不带 Reason 枚举: 存储层故障 (磁盘/锁/SQL 语法) 无玩家可理解的细分语义, 统一作"市场存储不可用"上抛。
 */
public final class MarketStoreException extends RuntimeException {

    public MarketStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.miningdim.caseopening.store;

/** Persisted Saga phases. Rows are never deleted, so an opening ID remains durable and idempotent. */
public enum CaseOpeningStatus {
    RESERVED,
    DEBITED,
    COMMITTED,
    REFUNDED,
    /**
     * 与货币账本互相矛盾、无法自动判定的行 (例如 SQL 已退款而账本已完成)。
     *
     * 此前这种行靠抛异常表达"已隔离", 结果是它永不自愈: 该玩家【每次登录都抛】, 且抛出点之后的
     * enforceMainHand 被整段跳过。落成一个终态后, 后续恢复直接跳过它, 由人工按告警处理。
     */
    QUARANTINED
}

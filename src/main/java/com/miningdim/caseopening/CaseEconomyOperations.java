package com.miningdim.caseopening;

import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.function.Supplier;

/** Narrow adapter around the economy ledger's durable idempotent two-currency operation API. */
public interface CaseEconomyOperations {

    enum State {
        NONE,
        DEBITED,
        COMPLETED,
        REFUNDED
    }

    /** 把 body 内的账本与开箱库写入合并进单个事务 (两者已同库同连接)。 */
    <T> T inTransaction(Supplier<T> body);

    /** 登记一个在当前 {@link #inTransaction} 的最外层提交之后才执行的动作, 回滚则丢弃; 不在事务中时立即执行。 */
    void afterCommit(Runnable action);

    long creditBalance(ServerPlayer player);

    long azureBalance(ServerPlayer player);

    boolean charge(ServerPlayer player, UUID operationId, long creditCost, long azureCost);

    State state(UUID playerId, UUID operationId);

    State complete(UUID playerId, UUID operationId);

    State refund(UUID playerId, UUID operationId);
}

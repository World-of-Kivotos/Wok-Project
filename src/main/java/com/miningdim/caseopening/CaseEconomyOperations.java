package com.miningdim.caseopening;

import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Narrow adapter around the economy ledger's durable idempotent two-currency operation API. */
public interface CaseEconomyOperations {

    enum State {
        NONE,
        DEBITED,
        COMPLETED,
        REFUNDED
    }

    long creditBalance(ServerPlayer player);

    long azureBalance(ServerPlayer player);

    boolean charge(ServerPlayer player, UUID operationId, long creditCost, long azureCost);

    State state(UUID playerId, UUID operationId);

    State complete(UUID playerId, UUID operationId);

    State refund(UUID playerId, UUID operationId);
}

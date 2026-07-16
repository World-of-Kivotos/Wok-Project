package com.miningdim.caseopening;

import com.miningdim.economy.EconomyOperationStatus;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Production adapter; keeps the case module coupled only to the public economy facade. */
public final class EconomyCaseOperations implements CaseEconomyOperations {

    private IEconomyService economy() {
        return EconomyServices.economyService();
    }

    @Override
    public long creditBalance(ServerPlayer player) {
        return economy().creditBalance(player);
    }

    @Override
    public long azureBalance(ServerPlayer player) {
        return economy().heartstoneBalance(player);
    }

    @Override
    public boolean charge(ServerPlayer player, UUID operationId, long creditCost, long azureCost) {
        return switch (economy().tryChargeBundle(player, operationId, creditCost, azureCost)) {
            case CHARGED, COMPLETED -> true;
            case NONE, REFUNDED -> false;
        };
    }

    @Override
    public State state(UUID playerId, UUID operationId) {
        return map(economy().operationStatus(playerId, operationId));
    }

    @Override
    public State complete(UUID playerId, UUID operationId) {
        return map(economy().completeBundle(playerId, operationId));
    }

    @Override
    public State refund(UUID playerId, UUID operationId) {
        return map(economy().refundBundle(playerId, operationId));
    }

    private static State map(EconomyOperationStatus status) {
        return switch (status) {
            case NONE -> State.NONE;
            case CHARGED -> State.DEBITED;
            case COMPLETED -> State.COMPLETED;
            case REFUNDED -> State.REFUNDED;
        };
    }
}

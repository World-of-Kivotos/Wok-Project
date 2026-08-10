package com.miningdim.caseopening;

import com.miningdim.economy.EconomyOperationDomain;
import com.miningdim.economy.EconomyOperationStatus;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Production adapter; keeps the case module coupled only to the public economy facade. */
public final class EconomyCaseOperations implements CaseEconomyOperations {

    /**
     * 业务域在适配器层固定, 不上浮到 CaseEconomyOperations 接口 —— 该接口本就是开箱专用的窄适配器,
     * 让调用方逐处传域只会增加传错的机会。openingId 由客户端提交, 域是它与其它业务之间唯一的隔离带。
     */
    private static final EconomyOperationDomain DOMAIN = EconomyOperationDomain.CASE_OPENING;

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
        return switch (economy().tryChargeBundle(DOMAIN, player, operationId, creditCost, azureCost)) {
            case CHARGED, COMPLETED -> true;
            case NONE, REFUNDED -> false;
        };
    }

    @Override
    public State state(UUID playerId, UUID operationId) {
        return map(economy().operationStatus(DOMAIN, playerId, operationId));
    }

    @Override
    public State complete(UUID playerId, UUID operationId) {
        return map(economy().completeBundle(DOMAIN, playerId, operationId));
    }

    @Override
    public State refund(UUID playerId, UUID operationId) {
        return map(economy().refundBundle(DOMAIN, playerId, operationId));
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

package com.miningdim.job.tarot.pack;

import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyServices;
import com.miningdim.job.tarot.TarotConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.items.ItemHandlerHelper;

/** Server-authoritative purchase flow. Opening a purchased pack never charges again. */
public final class TarotPackService {

    public enum PurchaseStatus {
        SUCCESS,
        NOT_ENOUGH_CURRENCY,
        DAILY_LIMIT
    }

    public record PurchaseResult(PurchaseStatus status, PackKind kind, int count, long totalPrice,
                                 int remainingToday) {
        public boolean success() {
            return status == PurchaseStatus.SUCCESS;
        }
    }

    private TarotPackService() {
    }

    public static PurchaseResult buy(ServerPlayer player, PackKind kind, int count) {
        if (count <= 0 || count > 64) {
            throw new IllegalArgumentException("pack purchase count must be in [1,64]");
        }
        int cap = TarotConfig.DAILY_PACK_LIMIT.get();
        long today = TarotPackClock.currentUtcDayStamp();
        TarotPackSavedData data = TarotPackSavedData.get(player.getServer().overworld());
        boolean testMode = TarotConfig.TEST_MODE.get();

        if (!testMode && !data.canAcquire(player.getUUID(), count, cap, today)) {
            return new PurchaseResult(PurchaseStatus.DAILY_LIMIT, kind, count, 0L,
                    Math.max(0, cap - data.acquiredToday(player.getUUID(), today)));
        }

        long unitPrice = price(kind);
        long totalPrice = Math.multiplyExact(unitPrice, (long) count);
        if (!testMode && totalPrice > 0L
                && !EconomyServices.economyService().tryCharge(player, currency(kind), totalPrice)) {
            return new PurchaseResult(PurchaseStatus.NOT_ENOUGH_CURRENCY, kind, count, totalPrice,
                    Math.max(0, cap - data.acquiredToday(player.getUUID(), today)));
        }

        if (!testMode) {
            data.recordAcquired(player.getUUID(), count, cap, today);
        }
        for (int i = 0; i < count; i++) {
            ItemHandlerHelper.giveItemToPlayer(player, TarotPackItem.create(kind, player.getUUID()));
        }
        int remaining = testMode ? cap : Math.max(0, cap - data.acquiredToday(player.getUUID(), today));
        return new PurchaseResult(PurchaseStatus.SUCCESS, kind, count, testMode ? 0L : totalPrice, remaining);
    }

    public static long price(PackKind kind) {
        return switch (kind) {
            case COMMON -> TarotConfig.PRICE_COMMON_PACK.get();
            case ADVANCED -> TarotConfig.PRICE_ADVANCED_PACK.get();
            case SHINY -> TarotConfig.PRICE_SHINY_PACK_AZURE.get();
        };
    }

    public static Currency currency(PackKind kind) {
        return kind == PackKind.SHINY ? Currency.AZURE : Currency.CREDIT;
    }
}

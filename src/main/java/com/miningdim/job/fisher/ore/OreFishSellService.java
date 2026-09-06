package com.miningdim.job.fisher.ore;

import com.miningdim.economy.EconomyConstants;
import com.miningdim.economy.EconomyServices;
import com.miningdim.job.fisher.journal.FishingJournalNetwork;
import com.miningdim.job.fisher.journal.FishingJournalService;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Sells the complete ore-fish stack in a player's main hand through the shared credit faucet. */
public final class OreFishSellService {
    private OreFishSellService() {
    }

    public record SellResult(int soldCount, long creditsGranted, boolean economyUnavailable, boolean invalidHeldItem) {
        static SellResult unavailable() {
            return new SellResult(0, 0L, true, false);
        }

        static SellResult invalidItem() {
            return new SellResult(0, 0L, false, true);
        }

        static SellResult zeroGrant() {
            return new SellResult(0, 0L, false, false);
        }
    }

    public static SellResult sellMainHand(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        OreFishType type = typeFor(stack);
        if (type == null) {
            return SellResult.invalidItem();
        }
        if (!EconomyServices.isRegistered()) {
            return SellResult.unavailable();
        }

        if (FishingJournalService.collect(player, stack)) {
            FishingJournalNetwork.send(player, FishingJournalService.snapshot(player), false);
        }
        int count = stack.getCount();
        long gross = Math.multiplyExact(OreFishingConfig.sellPrice(type), count);
        long granted = EconomyServices.economyService().grantDaily(player, gross,
                EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY,
                EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER);
        if (granted <= 0L) {
            return SellResult.zeroGrant();
        }
        stack.shrink(count);
        return new SellResult(count, granted, false, false);
    }

    public static int executeSell(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        SellResult result = sellMainHand(player);
        if (result.economyUnavailable()) {
            context.getSource().sendFailure(Component.translatable("message.miningdim.fishing.sell.economy_unavailable"));
            return 0;
        }
        if (result.invalidHeldItem()) {
            context.getSource().sendFailure(Component.translatable("message.miningdim.fishing.sell.no_fish"));
            return 0;
        }
        if (result.soldCount() == 0) {
            context.getSource().sendFailure(Component.translatable("message.miningdim.fishing.sell.zero_payout"));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.translatable("message.miningdim.fishing.sell.success",
                result.soldCount(), result.creditsGranted()), true);
        return 1;
    }

    private static OreFishType typeFor(ItemStack stack) {
        for (OreFishType type : OreFishType.values()) {
            if (stack.is(OreFishingItems.FISH.get(type).get())) {
                return type;
            }
        }
        return null;
    }
}

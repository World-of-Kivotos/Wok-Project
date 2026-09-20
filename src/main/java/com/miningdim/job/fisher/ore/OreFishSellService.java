package com.miningdim.job.fisher.ore;

import com.miningdim.economy.EconomyConstants;
import com.miningdim.economy.EconomyServices;
import com.miningdim.job.fisher.journal.FishingJournalNetwork;
import com.miningdim.job.fisher.journal.FishingJournalService;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
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
    }

    /**
     * 先扣后发, 与卖菜 {@code FarmerWheatSellService.sell} 同纪律。
     *
     * 原先是"先调 grantDaily, 实发为零就保留鱼"。问题出在 grantDaily 的第一步就是
     * {@code ledger.recordFaucetGrant} —— 毛收入在算实发额之前已经写进当日 faucet 计数器了。于是深档玩家
     * 每喊一次 /fishing sell 都把 gross 记进当日累计却没成交, 计数器与 Economy_BalanceSheet 的 faucet 汇总
     * 都会记下一笔从未发生的销售, 而衰减档位正是按这个计数器推进的。
     *
     * 改成先扣鱼再入账后, 计数器里的每一笔都对应一次真实成交; 实发为零照样扣鱼, 与卖菜"收购曲线到底仍算卖出"
     * 同口径。入账抛异常时把鱼原样退回再重抛 —— 不是吞异常, 是让已经发生的半步副作用不要留在玩家头上。
     */
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
        stack.shrink(count);
        long granted;
        try {
            granted = EconomyServices.economyService().grantDaily(player, gross,
                    EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY,
                    EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER);
        } catch (RuntimeException payoutFailed) {
            refund(player, type, count);
            throw payoutFailed;
        }
        return new SellResult(count, granted, false, false);
    }

    /** 入账失败时把已离手的鱼还回去; 背包放不下就掉在脚边, 不静默蒸发。 */
    private static void refund(ServerPlayer player, OreFishType type, int count) {
        Item fish = OreFishingItems.FISH.get(type).get();
        int remaining = count;
        while (remaining > 0) {
            int batch = Math.min(remaining, fish.getMaxStackSize());
            ItemStack stack = new ItemStack(fish, batch);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            remaining -= batch;
        }
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
        // 实发为零不是失败: 鱼已成交, 只是当日衰减已经到底。单独一条回执说清楚, 免得玩家以为命令没生效。
        if (result.creditsGranted() <= 0L) {
            context.getSource().sendSuccess(() -> Component.translatable(
                    "message.miningdim.fishing.sell.zero_payout", result.soldCount()), false);
            return result.soldCount();
        }
        // broadcastToAdmins 传 false: 卖鱼是普通玩家的日常动作, 与 /farmer sell 同口径, 不该刷 OP 聊天与服务端日志。
        context.getSource().sendSuccess(() -> Component.translatable("message.miningdim.fishing.sell.success",
                result.soldCount(), result.creditsGranted()), false);
        return result.soldCount();
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

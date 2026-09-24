package com.miningdim.job.fisher.ore;

import com.miningdim.economy.EconomyConstants;
import com.miningdim.economy.EconomyServices;
import com.miningdim.job.fisher.journal.FishingJournalNetwork;
import com.miningdim.job.fisher.journal.FishingJournalService;
import com.miningdim.job.fisher.size.FishSizeClass;
import com.miningdim.job.fisher.size.FishSizeNbt;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 按背包主栏批量收购矿石鱼, 走全服统一的信用点 faucet。
 *
 * 体型档把同一鱼种拆成小/标准/大几组互不堆叠的栈, 所以收购不再只看主手那一栈:
 * <ul>
 *   <li>{@code /fishing sell}: 主手必须是矿石鱼; 卖主手整组, 连带背包主栏里同鱼种的其它非奖杯栈。</li>
 *   <li>{@code /fishing sell all}: 卖背包主栏里全部非奖杯矿石鱼。</li>
 * </ul>
 * 奖杯个体是独一份的收藏品, 批量收购一律跳过; 只有拿在主手上执行 {@code /fishing sell} 才会卖掉, 那是明确意图。
 * 副手不参与。收购价仍是鱼种基础价乘条数, 与体型无关。
 */
public final class OreFishSellService {
    private OreFishSellService() {
    }

    public enum Scope {
        HELD_SPECIES,
        ALL
    }

    /** nothingToSell: 主手不是矿石鱼 (HELD_SPECIES) 或背包里没有可批量出售的矿石鱼 (ALL)。 */
    public record SellResult(int soldCount, long creditsGranted, boolean economyUnavailable, boolean nothingToSell) {
        static SellResult unavailable() {
            return new SellResult(0, 0L, true, false);
        }

        static SellResult nothing() {
            return new SellResult(0, 0L, false, true);
        }
    }

    public static SellResult sellMainHand(ServerPlayer player) {
        return sell(player, Scope.HELD_SPECIES);
    }

    public static SellResult sellAll(ServerPlayer player) {
        return sell(player, Scope.ALL);
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
     * 同口径。一次命令卖出的全部栈合并成一笔 grantDaily, 不按栈拆笔。入账抛异常时把扣下的栈原样 (连同体型标签)
     * 退回再重抛 —— 不是吞异常, 是让已经发生的半步副作用不要留在玩家头上。
     */
    public static SellResult sell(ServerPlayer player, Scope scope) {
        Inventory inventory = player.getInventory();
        OreFishType heldType = typeFor(player.getMainHandItem());
        if (scope == Scope.HELD_SPECIES && heldType == null) {
            return SellResult.nothing();
        }
        List<ItemStack> selected = selectStacks(inventory, scope, heldType);
        if (selected.isEmpty()) {
            return SellResult.nothing();
        }
        if (!EconomyServices.isRegistered()) {
            return SellResult.unavailable();
        }

        boolean collected = false;
        for (ItemStack stack : selected) {
            collected |= FishingJournalService.collect(player, stack);
        }
        if (collected) {
            FishingJournalNetwork.send(player, FishingJournalService.snapshot(player), false);
        }
        long gross = 0L;
        int count = 0;
        List<ItemStack> removed = new ArrayList<>(selected.size());
        for (ItemStack stack : selected) {
            gross = Math.addExact(gross, Math.multiplyExact(OreFishingConfig.sellPrice(typeFor(stack)), stack.getCount()));
            count += stack.getCount();
        }
        for (ItemStack stack : selected) {
            removed.add(stack.copy());
            stack.shrink(stack.getCount());
        }
        long granted;
        try {
            granted = EconomyServices.economyService().grantDaily(player, gross,
                    EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY,
                    EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER);
        } catch (RuntimeException payoutFailed) {
            refund(player, removed);
            throw payoutFailed;
        }
        return new SellResult(count, granted, false, false);
    }

    /** 背包主栏 (含快捷栏, 不含副手与盔甲栏) 里本次要卖的栈, 返回的是槽位里的原对象。 */
    private static List<ItemStack> selectStacks(Inventory inventory, Scope scope, OreFishType heldType) {
        List<ItemStack> selected = new ArrayList<>();
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            ItemStack stack = inventory.items.get(slot);
            OreFishType type = typeFor(stack);
            if (type == null) {
                continue;
            }
            boolean mainHand = slot == inventory.selected && Inventory.isHotbarSlot(slot);
            if (scope == Scope.HELD_SPECIES && type != heldType) {
                continue;
            }
            if (isTrophy(stack) && !(scope == Scope.HELD_SPECIES && mainHand)) {
                continue;
            }
            selected.add(stack);
        }
        return selected;
    }

    private static boolean isTrophy(ItemStack stack) {
        return FishSizeNbt.read(stack).map(info -> info.sizeClass() == FishSizeClass.TROPHY).orElse(false);
    }

    /** 入账失败时把已离手的栈按原样还回去; 背包放不下就掉在脚边, 不静默蒸发。 */
    private static void refund(ServerPlayer player, List<ItemStack> removed) {
        for (ItemStack stack : removed) {
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
        }
    }

    public static int executeSell(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return execute(context, Scope.HELD_SPECIES);
    }

    public static int executeSellAll(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return execute(context, Scope.ALL);
    }

    private static int execute(CommandContext<CommandSourceStack> context, Scope scope)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        SellResult result = sell(player, scope);
        if (result.economyUnavailable()) {
            context.getSource().sendFailure(Component.translatable("message.miningdim.fishing.sell.economy_unavailable"));
            return 0;
        }
        if (result.nothingToSell()) {
            context.getSource().sendFailure(Component.translatable(scope == Scope.ALL
                    ? "message.miningdim.fishing.sell.no_fish_inventory"
                    : "message.miningdim.fishing.sell.no_fish"));
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

    static OreFishType typeFor(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        for (OreFishType type : OreFishType.values()) {
            if (stack.is(OreFishingItems.FISH.get(type).get())) {
                return type;
            }
        }
        return null;
    }
}

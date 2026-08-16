package com.miningdim.enchant;

import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyServices;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 金钱修补的运行期结算: 每秒给身上带该附魔的装备补耐久, 按 {@link RepairPricing} 的单价扣信用点。
 *
 * <b>只管已装备的六个槽</b> (双手 + 四件护甲), 不扫背包。背包里躺着的备用装备一起修会变成一笔玩家看不见的
 * 持续开销, 而且与"这件装备正在被我用"的直觉不符。
 *
 * <b>钱不够就少修</b>, 而不是不修: 按余额算出修得起几点, 修那几点。全有或全无会让一个余额将尽的玩家眼睁睁
 * 看着装备一路损坏到断, 而他明明还有钱能撑一会儿。余额为 0 时自然退化成不修, 装备照常掉耐久, 不欠账不透支。
 *
 * 线程: 服务端主线程 (PlayerTickEvent)。
 */
public final class MoneyMendingHandler {

    /** 结算间隔 (tick)。每秒一次: 再密没有可感收益, 只是把扣费拆得更碎。 */
    private static final int INTERVAL_TICKS = 20;

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) {
            return;
        }
        if (!MoneyMendingConfig.ENABLED.get() || !EconomyServices.isRegistered()) {
            return;
        }
        // 用玩家自己的 tickCount 取模: 天然把不同玩家的结算错开到不同 tick, 不会整服同一帧一起算。
        if (player.tickCount % INTERVAL_TICKS != 0) {
            return;
        }
        repairEquipped(player);
    }

    /**
     * 走一遍该玩家的六个装备槽 (双手 + 四件护甲), 各修一次。
     *
     * 与 tick 闸分开是为了可测: tick 闸依赖 {@code player.tickCount} 取模, 在 GameTest 里没法稳定摆到某一帧,
     * 而真正要验的是"修了几点、扣了多少钱、背包里的不修"这些, 与哪一帧触发无关。
     */
    static void repairEquipped(ServerPlayer player) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            repairOne(player, player.getItemBySlot(slot));
        }
    }

    /** 修一件装备 (若它带本附魔且确实有损耗)。 */
    private static void repairOne(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamaged()) {
            return;
        }
        if (EnchantmentHelper.getItemEnchantmentLevel(ModEnchantments.MONEY_MENDING.get(), stack) <= 0) {
            return;
        }
        long costPerPoint = RepairPricing.costPerDurability(stack);
        if (costPerPoint == RepairPricing.UNSUPPORTED) {
            // 附魔已挂在一件算不出单价的装备上 (跨版本改动或指令强塞): 不修, 也绝不按免费处理。
            return;
        }

        int wanted = Math.min(MoneyMendingConfig.REPAIR_POINTS_PER_SECOND.get(), stack.getDamageValue());
        long balance = EconomyServices.economyService().creditBalance(player);
        int affordable = (int) Math.min(wanted, balance / costPerPoint);
        if (affordable <= 0) {
            return;
        }

        long cost = (long) affordable * costPerPoint;
        // 先扣费再补耐久: 反过来的话扣费失败就白修了。tryCharge 余额不足返 false 且不扣。
        if (!EconomyServices.economyService().tryCharge(player, Currency.CREDIT, cost)) {
            return;
        }
        stack.setDamageValue(stack.getDamageValue() - affordable);
    }
}

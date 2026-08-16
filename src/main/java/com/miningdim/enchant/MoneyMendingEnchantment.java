package com.miningdim.enchant;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * 金钱修补: 装备在身时自动花信用点补耐久。
 *
 * 与原版经济修补<b>互斥</b>。理由不是风味而是必要 —— 经济修补的燃料是经验, 而经验对本服玩家近乎免费, 两者
 * 共存时永远是经济修补先修完, 本附魔在同一件装备上就是一张永不生效的废票。互斥之后玩家要做一次真实取舍:
 * 用经验换耐久, 还是用信用点换耐久。
 *
 * <b>获取途径只有任务</b> (主控 2026-08-17 定): 不可从附魔台附出 ({@link #isDiscoverable} = false), 不进
 * 村民交易 ({@link #isTradeable} = false), 只作宝藏级附魔由任务奖励直接发书。它是一项纯付费便利, 让它靠肝
 * 任务获得比让它在市场上流通更健康。
 *
 * 单等级 (对标原版经济修补): 分等级只能分"修得更快"或"修得更便宜", 前者无意义 (费用按点算, 修得快只是钱花
 * 得快), 后者会把好不容易挂住的定价模型重新撕开一道口子。
 */
public final class MoneyMendingEnchantment extends Enchantment {

    MoneyMendingEnchantment() {
        // BREAKABLE: 一切有耐久的物品都可作载体; 真正能不能附由 canEnchant 按有没有计价口径再筛一道。
        super(Rarity.VERY_RARE, EnchantmentCategory.BREAKABLE, EquipmentSlot.values());
    }

    @Override
    public int getMaxLevel() {
        return 1;
    }

    @Override
    public int getMinCost(int level) {
        return 25;
    }

    @Override
    public int getMaxCost(int level) {
        return 75;
    }

    @Override
    public boolean isTreasureOnly() {
        return true;
    }

    /** 附魔台附不出来 (只走任务发书)。 */
    @Override
    public boolean isDiscoverable() {
        return false;
    }

    /** 村民不卖 (与"只走任务"一致; 也避免绕开任务这条唯一龙头)。 */
    @Override
    public boolean isTradeable() {
        return false;
    }

    @Override
    protected boolean checkCompatibility(Enchantment other) {
        return super.checkCompatibility(other) && other != Enchantments.MENDING;
    }

    /**
     * 只有<b>算得出维修单价</b>的装备才允许附。木/石/皮革/锁链/海龟壳与全部 mod 物品 (含 TaCZ 枪械、铸甲师
     * 护甲板) 一律附不上 —— 详见 {@link RepairPricing} 类注释: 给它们一个含糊的兜底费率等于开一条套利缝。
     */
    @Override
    public boolean canEnchant(ItemStack stack) {
        return super.canEnchant(stack) && RepairPricing.supports(stack.getItem());
    }
}

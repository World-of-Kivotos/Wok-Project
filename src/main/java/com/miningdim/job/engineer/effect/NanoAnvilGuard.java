package com.miningdim.job.engineer.effect;

import com.miningdim.job.engineer.NanoNbt;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.AnvilUpdateEvent;
import net.minecraftforge.event.entity.player.PlayerXpEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * 禁用经验修补 / 铁砧修复对纳米特效甲的耐久越界 (MillenniumEngineer_Mod_DesignSpec 5.2 / 12.1)。
 *
 * PENDING 12.1 范围裁决 (本实现取保守方案: 仅纳米特效甲禁用, 不全服禁用): 当铁砧左侧物品带任意纳米特效时,
 * 取消铁砧输出 —— 防原版回耐久 (经验修补 / 同物合并修) 偷偷把耐久越过特效的耐久门槛 (机能修复 < 50% 失效 /
 * 重塑损失阈值), 绕过纳米经济。不带纳米特效的普通物品照常铁砧修复 (不影响普通玩家)。
 *
 * 范围仅纳米特效甲是为了不影响全服普通铁砧生意 (规格 5.2: 全服禁用影响所有人, 不可取); 集成阶段若拍板改为
 * 全服禁用经验修补, 改本 handler 判定即可 (见 notes 报告 12.1 仍 PENDING)。
 */
public final class NanoAnvilGuard {

    /**
     * 旧存档或指令生成物可能同时带纳米特效与经验修补。在经验球进入原版修补逻辑前移除该附魔，
     * 保证经验完整进入玩家经验条，同时不影响普通装备。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onXpPickup(PlayerXpEvent.PickupXp event) {
        // 必须覆盖全部 EquipmentSlot 而非仅护甲槽: MENDING 注册时传入的就是 EquipmentSlot.values(),
        // 原版经验修补会连主手与副手一起纳入候选。只扫护甲槽时, 玩家把带经验修补的纳米特效甲拿在手上
        // 拾取经验即可免费回耐久, 直接绕过维修套件的纳米经济。
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            stripMendingFromNanoEffectArmor(event.getEntity().getItemBySlot(slot));
        }
    }

    public static boolean stripMendingFromNanoEffectArmor(ItemStack stack) {
        if (NanoNbt.effects(stack).isEmpty()) {
            return false;
        }
        Map<Enchantment, Integer> enchantments = new HashMap<>(EnchantmentHelper.getEnchantments(stack));
        if (enchantments.remove(Enchantments.MENDING) == null) {
            return false;
        }
        EnchantmentHelper.setEnchantments(enchantments, stack);
        return true;
    }

    @SubscribeEvent
    public void onAnvilUpdate(AnvilUpdateEvent event) {
        if (NanoNbt.effects(event.getLeft()).isEmpty()) {
            return; // 普通物品: 不干预铁砧。
        }
        // 左侧是纳米特效甲: 仅当铁砧输出会回耐久 (output.damageValue < left.damageValue) 时才取消 —— 这正是
        // 经验修补/同物合并修/材料修这些 "偷偷越过特效耐久门" 的路径 (5.2 要挡的)。纯改名 / 附魔书附魔不回耐久
        // (output 耐久 == left 耐久), 放行, 不连带禁掉带特效甲的正常铁砧改名/附魔 (越界拦截已修)。
        ItemStack output = event.getOutput();
        if (output.isEmpty()) {
            return; // 无输出 (尚未算出): 无可拦, 留给其它 handler。
        }
        if (output.getDamageValue() < event.getLeft().getDamageValue()) {
            event.setCanceled(true);
        }
    }
}

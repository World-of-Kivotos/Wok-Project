package com.miningdim.job.engineer.effect;

import com.miningdim.job.engineer.NanoNbt;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.AnvilUpdateEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

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

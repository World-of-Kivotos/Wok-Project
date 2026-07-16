package com.miningdim.job.engineer.effect;

import com.miningdim.job.engineer.armor.item.PlateArmorItem;
import com.miningdim.job.engineer.shield.item.PlasmaShieldItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 纳米多重护盾的反应式免疫窗执行 (MillenniumEngineer_Mod_DesignSpec 6.2 "触发后 X 秒免疫")。订阅
 * {@link LivingHurtEvent}, 在受击时分两路决策:
 *  1. 已有任意护盾甲处于免疫窗 (windowTick > 0): 本次伤害归零 (窗口内全免疫), 结束。
 *  2. 无活动窗口但有 armed 充能 (regenTick <= 0 且 charges > 0): 消耗一次充能, 开 X 秒免疫窗, 重置再生倒计时,
 *     本次伤害归零。armed 由 {@link NanoEffectTicker} 倒计时驱动 (到点停在 0 等待触发, 不自动开窗)。
 *
 * 反应式 (区别于旧实现的时钟自动开窗): 充能 = 真正的 5 次救命资源, 仅受击时消耗, 不被时钟空耗、不在战斗外提供
 * 可预测 60s 节律免疫窗。
 *
 * 热路径优化 (TACZ 高频命中): 先读单个 int (windowTick / charges), 不预先解析整个特效 EnumSet。charges > 0 是
 * "该件带护盾且有充能" 的可靠廉价代理 —— K_SHIELD_CHARGES 仅在 writeEffects 含 SHIELD 时写入, clearEffects 时清除,
 * 故无需先 hasEffect 解析 ListTag 即可短路 (避免每次命中对 4 甲各 new 一个 EnumSet)。
 *
 * 注意: 本 handler 与职业框架易伤 (VulnerabilityHurtHandler) 是不同语义 (那是乘伤, 这是窗口免疫),
 * 二者独立订阅 LivingHurtEvent 不冲突 —— 免疫直接 setAmount(0) 提前结束本次伤害结算。
 */
public final class NanoShieldHandler {

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // 新插板是一套独立护甲原理；穿戴时禁用其他槽位遗留的纳米全免窗，避免未经平衡的双系统叠加。
        if (PlateArmorItem.equippedBy(player) != null || PlasmaShieldItem.equippedBy(player) != null) {
            return;
        }
        // 一路: 已在免疫窗内 -> 立即免疫 (热路径只读 windowTick 单 int, 不解析特效集合)。
        for (ItemStack armor : player.getInventory().armor) {
            if (NanoEffects.shieldWindowActive(armor)) {
                event.setAmount(0.0f);
                return;
            }
        }
        // 二路: 无活动窗口, 找一件 armed 充能件反应式触发免疫窗 (消耗一次充能, 由 NanoEffects 单一权威结算)。
        for (ItemStack armor : player.getInventory().armor) {
            if (NanoEffects.tryReactiveShield(armor)) {
                event.setAmount(0.0f);
                return;
            }
        }
    }
}

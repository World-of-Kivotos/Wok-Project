package com.miningdim.job.chef;

import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 稳膛抗击退 (Chef_Job_DesignSpec 第十章红线: 严禁 AttributeModifier, 必须用 LivingKnockBackEvent)。
 *
 * 稳膛激活时按档减比击退 (中 50% / 高 70% / 超凡 85% / 闪耀 100%), 读 {@link ChefWindowEffectState}
 * 的窗口快照, 不挂任何 KNOCKBACK_RESISTANCE 属性修饰符 (零属性泄漏)。100% 时 setStrength(0) 完全免疫。
 */
public final class ChefKnockbackHandler {

    @SubscribeEvent
    public void onKnockback(LivingKnockBackEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        int resistPerMille = ChefWindowEffectState.knockbackResistPerMille(player.getUUID());
        if (resistPerMille <= 0) {
            return;
        }
        float factor = 1.0F - Math.min(1.0F, resistPerMille / 1000.0F);
        event.setStrength(event.getStrength() * factor);
    }
}

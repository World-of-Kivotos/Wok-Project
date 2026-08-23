package com.miningdim.job.chef;

import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** 稳膛只在击退事件缩放，不安装任何属性修饰符。 */
public final class ChefAimHandler {

    @SubscribeEvent
    public void onKnockback(LivingKnockBackEvent event) {
        int resistance = ChefWindowEffectState.magnitudeOf(event.getEntity(), ChefEffectType.STABLE_AIM);
        if (resistance > 0) {
            event.setStrength(event.getStrength() * (1.0F - resistance / 1000.0F));
        }
    }
}

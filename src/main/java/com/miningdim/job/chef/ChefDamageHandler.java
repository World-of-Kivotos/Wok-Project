package com.miningdim.job.chef;

import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** 厨师窗口伤害结算；强度直接读实体的真实 MobEffect。 */
public final class ChefDamageHandler {

    @SubscribeEvent
    public void onHurt(LivingHurtEvent event) {
        LivingEntity entity = event.getEntity();
        if (ChefWindowEffectState.active(entity, ChefEffectType.FIRE_QUELL)
                && event.getSource().is(DamageTypeTags.IS_FIRE)) {
            event.setCanceled(true);
            return;
        }
        if (ChefWindowEffectState.active(entity, ChefEffectType.FEATHER)
                && event.getSource().is(DamageTypes.FALL)) {
            event.setCanceled(true);
            return;
        }
        if (!event.getSource().is(DamageTypes.EXPLOSION) && !event.getSource().is(DamageTypes.PLAYER_EXPLOSION)) {
            return;
        }
        int reduction = ChefWindowEffectState.magnitudeOf(entity, ChefEffectType.GREASE);
        if (reduction > 0) {
            event.setAmount(event.getAmount() * (1.0F - reduction / 1000.0F));
        }
    }
}

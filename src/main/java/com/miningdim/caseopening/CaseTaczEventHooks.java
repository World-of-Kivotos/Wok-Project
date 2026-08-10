package com.miningdim.caseopening;

import com.tacz.guns.api.event.common.GunDrawEvent;
import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.event.common.GunShootEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;

/** Optional TaCZ 1.1.8 event boundary; loaded only when Forge reports TaCZ present. */
public final class CaseTaczEventHooks {

    private CaseTaczEventHooks() {
    }

    public static void register(IEventBus forgeBus) {
        forgeBus.register(new CaseTaczEventHooks());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onGunDraw(GunDrawEvent event) {
        stripUnauthorized(event.getLogicalSide(), event.getEntity(), event.getCurrentGunItem());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onGunFire(GunFireEvent event) {
        if (stripUnauthorized(event.getLogicalSide(), event.getShooter(), event.getGunItemStack())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void onGunShoot(GunShootEvent event) {
        if (stripUnauthorized(event.getLogicalSide(), event.getShooter(), event.getGunItemStack())) {
            event.setCanceled(true);
        }
    }

    private static boolean stripUnauthorized(LogicalSide side, LivingEntity entity, ItemStack gunStack) {
        return side == LogicalSide.SERVER
                && entity instanceof ServerPlayer player
                && CaseServices.isRegistered()
                && CaseServices.service().enforceGunStack(player, gunStack);
    }
}

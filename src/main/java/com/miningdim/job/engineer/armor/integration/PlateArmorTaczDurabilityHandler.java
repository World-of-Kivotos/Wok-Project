package com.miningdim.job.engineer.armor.integration;

import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.api.event.common.EntityKillByGunEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** TaCZ 一弹一次的插板磨损入口；Post 与 Kill 互斥，不按普通段/穿甲段重复扣耐久。 */
public final class PlateArmorTaczDurabilityHandler {

    private final PlateArmorTaczWearLedger ledger = new PlateArmorTaczWearLedger();

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBeforeHurtByGun(EntityHurtByGunEvent.Pre event) {
        if (event.getLogicalSide().isServer() && event.getHurtEntity() instanceof Player player) {
            ledger.capture(event.getBullet().getUUID(), player);
        }
    }

    @SubscribeEvent
    public void onHurtByGun(EntityHurtByGunEvent.Post event) {
        if (event.getLogicalSide().isServer() && event.getHurtEntity() instanceof Player player) {
            ledger.settle(event.getBullet().getUUID(), player, event.getBaseAmount());
        }
    }

    @SubscribeEvent
    public void onKilledByGun(EntityKillByGunEvent event) {
        if (event.getLogicalSide().isServer() && event.getKilledEntity() instanceof Player player) {
            ledger.settle(event.getBullet().getUUID(), player, event.getBaseDamage());
        }
    }
}

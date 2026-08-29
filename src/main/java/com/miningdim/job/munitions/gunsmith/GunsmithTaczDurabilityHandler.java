package com.miningdim.job.munitions.gunsmith;

import com.miningdim.job.munitions.MunitionsConfig;
import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.event.common.GunShootEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;

import java.util.Map;
import java.util.WeakHashMap;

/** TaCZ 开火链的 WOK 枪械耐久适配。客户端预拦截损坏枪，服务端权威扣除每发耐久。 */
public final class GunsmithTaczDurabilityHandler {

    private static final int MESSAGE_COOLDOWN_TICKS = 20;
    private static final Map<Player, Integer> LAST_MESSAGE_TICK = new WeakHashMap<>();

    private GunsmithTaczDurabilityHandler() {
    }

    public static void register(IEventBus forgeBus) {
        forgeBus.register(new GunsmithTaczDurabilityHandler());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onGunShoot(GunShootEvent event) {
        if (!MunitionsConfig.GUNSMITH_ENABLED.get()) {
            return;
        }
        // 开火链每发只解析一次枪 NBT, 且必须走不抛的入口: 事件总线里冒出来的解析异常会直接打断开火并崩客户端 (审查 40)。
        GunsmithGunDurability.Managed managed =
                GunsmithGunDurability.tryManaged(event.getGunItemStack());
        if (managed == null || managed.state().current() > 0) {
            return;
        }
        event.setCanceled(true);
        warn(event.getShooter() instanceof Player player ? player : null,
                "message.miningdim.gunsmith_durability.broken");
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onGunFire(GunFireEvent event) {
        if (!MunitionsConfig.GUNSMITH_ENABLED.get()
                || event.getLogicalSide() != LogicalSide.SERVER) {
            return;
        }
        GunsmithGunDurability.Managed managed =
                GunsmithGunDurability.tryManaged(event.getGunItemStack());
        if (managed == null) {
            return;
        }
        GunsmithGunDurability.ShotWear wear =
                GunsmithGunDurability.consumeShot(managed, event.getGunItemStack());
        if (!wear.canFire()) {
            event.setCanceled(true);
            warn(event.getShooter() instanceof Player player ? player : null,
                    "message.miningdim.gunsmith_durability.broken");
            return;
        }
        if (wear.becameBroken()) {
            warn(event.getShooter() instanceof Player player ? player : null,
                    "message.miningdim.gunsmith_durability.depleted");
        }
    }

    private static void warn(Player player, String translationKey) {
        if (player == null) {
            return;
        }
        synchronized (LAST_MESSAGE_TICK) {
            int now = player.tickCount;
            Integer previous = LAST_MESSAGE_TICK.get(player);
            if (previous != null && now - previous < MESSAGE_COOLDOWN_TICKS) {
                return;
            }
            LAST_MESSAGE_TICK.put(player, now);
        }
        player.displayClientMessage(Component.translatable(translationKey), true);
    }
}

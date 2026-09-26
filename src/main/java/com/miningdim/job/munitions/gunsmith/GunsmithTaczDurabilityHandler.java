package com.miningdim.job.munitions.gunsmith;

import com.miningdim.job.munitions.MunitionsConfig;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.event.common.GunShootEvent;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.resource.pojo.data.gun.Bolt;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import org.jetbrains.annotations.Nullable;

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
        // V20: TaCZ 连发的每一轮都先抛本事件、后调 reduceAmmoOnce, 弹匣打空后的那一轮也会走到这里。
        // 这一轮没弹就既不扣耐久也不拦截: TaCZ 随后在 reduceAmmoOnce 处返回 false 结束循环, 不会射出子弹。
        if (!roundWillFire(event.getShooter(), event.getGunItemStack())) {
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

    /** 读出 TaCZ 当下的枪机类型、膛内弹与余弹, 交给 {@link #roundFeeds} 判定。 */
    private static boolean roundWillFire(LivingEntity shooter, ItemStack gun) {
        IGun iGun = IGun.getIGunOrNull(gun);
        if (iGun == null) {
            // GunFireEvent 只由 TaCZ 枪抛出, 走不到这里; 万一走到, 保持旧口径照扣。
            return true;
        }
        IGunOperator operator = IGunOperator.fromLivingEntity(shooter);
        boolean useInventoryAmmo = iGun.useInventoryAmmo(gun);
        return roundFeeds(operator.consumesAmmoOrNot(),
                boltAction(iGun.getGunId(gun)),
                iGun.hasBulletInBarrel(gun),
                useInventoryAmmo,
                useInventoryAmmo && iGun.hasInventoryAmmo(shooter, gun, operator.needCheckAmmo()),
                iGun.getCurrentAmmoCount(gun));
    }

    @Nullable
    private static BoltAction boltAction(ResourceLocation gunId) {
        Bolt bolt = TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> index.getGunData().getBolt())
                .orElse(null);
        if (bolt == Bolt.MANUAL_ACTION) {
            return BoltAction.MANUAL_ACTION;
        }
        if (bolt == Bolt.CLOSED_BOLT) {
            return BoltAction.CLOSED_BOLT;
        }
        if (bolt == Bolt.OPEN_BOLT) {
            return BoltAction.OPEN_BOLT;
        }
        return null;
    }

    /**
     * 这一轮能否真的上弹射出, 与 TaCZ 1.1.8 ModernKineticGunScriptAPI.reduceAmmoOnce 同一口径, 只是不带副作用:
     * 手动枪机只看膛内弹; 闭膛先吃弹匣 (或背包弹), 弹匣空了再打膛内那一发; 开膛只看弹匣 (或背包弹)。
     * 不消耗弹药 (创造模式等) 时 TaCZ 根本不调 reduceAmmoOnce, 每一轮都会射出; 查不到枪机类型时 TaCZ 返回 false。
     * 纯函数, 不引用 TaCZ 类型, 供 GameTest 直接驱动。
     */
    static boolean roundFeeds(boolean consumesAmmo, @Nullable BoltAction action, boolean barrelLoaded,
                              boolean useInventoryAmmo, boolean hasInventoryAmmo, int magazineAmmo) {
        if (!consumesAmmo) {
            return true;
        }
        if (action == null) {
            return false;
        }
        boolean chambered = barrelLoaded && action != BoltAction.OPEN_BOLT;
        boolean noAmmo = useInventoryAmmo ? !hasInventoryAmmo : magazineAmmo < 1;
        return switch (action) {
            case MANUAL_ACTION -> chambered;
            case CLOSED_BOLT -> !noAmmo || chambered;
            case OPEN_BOLT -> !noAmmo;
        };
    }

    /** TaCZ Bolt 的镜像, 让 {@link #roundFeeds} 不必引用 TaCZ 类型。 */
    enum BoltAction {
        OPEN_BOLT,
        CLOSED_BOLT,
        MANUAL_ACTION
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

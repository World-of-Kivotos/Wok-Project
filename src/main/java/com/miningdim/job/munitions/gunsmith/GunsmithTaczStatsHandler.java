package com.miningdim.job.munitions.gunsmith;

import com.tacz.guns.api.GunProperties;
import com.tacz.guns.api.event.common.AttachmentPropertyEvent;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.modifier.ParameterizedCachePair;
import com.tacz.guns.resource.modifier.AttachmentCacheProperty;
import com.tacz.guns.resource.pojo.data.gun.InaccuracyType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;

import java.util.EnumMap;
import java.util.Map;

public final class GunsmithTaczStatsHandler {

    private GunsmithTaczStatsHandler() {
    }

    public static void register(IEventBus forgeBus) {
        forgeBus.register(new GunsmithTaczStatsHandler());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onAttachmentProperty(AttachmentPropertyEvent event) {
        // 功能门 (审查 G-1): 事件体内查 config (SERVER config 于世界加载后可用, 注册期不可读);
        // 关闭时存量枪械的旁挂系数一并失效, 加伤数值过评审前不生效。
        if (!com.miningdim.job.munitions.MunitionsConfig.GUNSMITH_ENABLED.get()) {
            return;
        }
        ItemStack gun = event.getGunItem();
        GunsmithGunStats stats = GunsmithGunStats.from(gun);
        if (stats == null) {
            return;
        }

        AttachmentCacheProperty cache = event.getCacheProperty();
        multiplyFloat(cache, GunProperties.EFFECTIVE_RANGE, stats.headshot());
        multiplyInteger(cache, GunProperties.ROUNDS_PER_MINUTE, stats.recoil());
        multiplyFloat(cache, GunProperties.ADS_TIME, inverse(stats.handling()));
        multiplyInaccuracy(cache, GunProperties.INACCURACY, inverse(stats.spread()));
        multiplyInaccuracy(cache, GunProperties.AIM_INACCURACY, inverse(stats.handling()));
        multiplyRecoil(cache, inverse(stats.recoil()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onGunHurt(EntityHurtByGunEvent.Pre event) {
        if (event.getLogicalSide() != LogicalSide.SERVER) {
            return;
        }
        // 同 onAttachmentProperty: 3A WIP 功能门, 关闭时加伤链整体不生效。
        if (!com.miningdim.job.munitions.MunitionsConfig.GUNSMITH_ENABLED.get()) {
            return;
        }
        ItemStack gun = findGunsmithGun(event.getAttacker(), event.getGunId());
        GunsmithGunStats stats = GunsmithGunStats.from(gun);
        if (stats == null) {
            return;
        }
        event.setBaseAmount((float) (event.getBaseAmount() * stats.damage()));
        if (event.isHeadShot()) {
            event.setHeadshotMultiplier((float) (event.getHeadshotMultiplier() * stats.headshot()));
        }
    }

    private static ItemStack findGunsmithGun(LivingEntity attacker, ResourceLocation gunId) {
        if (attacker == null) {
            return ItemStack.EMPTY;
        }
        ItemStack mainHand = attacker.getMainHandItem();
        if (matchesGun(mainHand, gunId)) {
            return mainHand;
        }
        ItemStack offHand = attacker.getOffhandItem();
        if (matchesGun(offHand, gunId)) {
            return offHand;
        }
        return ItemStack.EMPTY;
    }

    private static boolean matchesGun(ItemStack stack, ResourceLocation gunId) {
        if (stack.isEmpty() || GunsmithGunStats.from(stack) == null) {
            return false;
        }
        IGun gun = IGun.getIGunOrNull(stack);
        return gun != null && (gunId == null || gunId.equals(gun.getGunId(stack)));
    }

    private static void multiplyFloat(AttachmentCacheProperty cache,
                                      com.tacz.guns.api.GunProperty<Float> property,
                                      double multiplier) {
        Float value = cache.getCache(property);
        if (value != null) {
            cache.setCache(property, (float) (value * multiplier));
        }
    }

    private static void multiplyInteger(AttachmentCacheProperty cache,
                                        com.tacz.guns.api.GunProperty<Integer> property,
                                        double multiplier) {
        Integer value = cache.getCache(property);
        if (value != null) {
            cache.setCache(property, Math.max(1, (int) Math.round(value * multiplier)));
        }
    }

    private static void multiplyInaccuracy(AttachmentCacheProperty cache,
                                           com.tacz.guns.api.GunProperty<Map<InaccuracyType, Float>> property,
                                           double multiplier) {
        Map<InaccuracyType, Float> value = cache.getCache(property);
        if (value == null || value.isEmpty()) {
            return;
        }
        EnumMap<InaccuracyType, Float> adjusted = new EnumMap<>(InaccuracyType.class);
        for (Map.Entry<InaccuracyType, Float> entry : value.entrySet()) {
            adjusted.put(entry.getKey(), (float) (entry.getValue() * multiplier));
        }
        cache.setCache(property, adjusted);
    }

    private static void multiplyRecoil(AttachmentCacheProperty cache, double multiplier) {
        ParameterizedCachePair<Float, Float> recoil = cache.getCache(GunProperties.RECOIL);
        if (recoil == null || recoil.left() == null || recoil.right() == null) {
            return;
        }
        float horizontal = (float) (recoil.left().getDefaultValue() * multiplier);
        float vertical = (float) (recoil.right().getDefaultValue() * multiplier);
        cache.setCache(GunProperties.RECOIL, ParameterizedCachePair.of(horizontal, vertical));
    }

    private static double inverse(double coefficient) {
        return 1.0D / Math.max(0.1D, coefficient);
    }
}

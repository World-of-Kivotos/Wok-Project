package com.miningdim.job.munitions.gunsmith;

import com.miningdim.job.munitions.MunitionsAmmoFactory;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.resource.pojo.data.gun.BulletData;
import com.tacz.guns.resource.pojo.data.gun.ExtraDamage;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.Optional;

public final class GunsmithTaczBridge {

    private GunsmithTaczBridge() {
    }

    public static Optional<GunsmithBaseStats> findBaseStats(ResourceLocation gunId) {
        Objects.requireNonNull(gunId, "gunId");
        if (!MunitionsAmmoFactory.isTaczLoaded()) {
            return Optional.empty();
        }
        Optional<GunData> gunData = TimelessAPI.getCommonGunIndex(gunId).map(index -> index.getGunData());
        if (gunData.isEmpty()) {
            return Optional.empty();
        }
        BulletData bulletData = gunData.get().getBulletData();
        if (bulletData == null) {
            return Optional.empty();
        }
        ExtraDamage extraDamage = bulletData.getExtraDamage();
        if (extraDamage == null) {
            return Optional.empty();
        }
        return Optional.of(new GunsmithBaseStats(
                bulletData.getDamageAmount(),
                extraDamage.getHeadShotMultiplier(),
                gunData.get().getRoundsPerMinute(),
                gunData.get().getAimTime()));
    }
}

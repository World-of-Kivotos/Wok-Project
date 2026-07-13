package com.miningdim.job.munitions.gunsmith;

import com.miningdim.job.munitions.MunitionsAmmoFactory;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.resource.pojo.data.gun.BulletData;
import com.tacz.guns.resource.pojo.data.gun.ExtraDamage;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class GunsmithTaczBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/gunsmith_tacz");

    private GunsmithTaczBridge() {
    }

    public static Optional<GunsmithBaseStats> findBaseStats(ResourceLocation gunId) {
        Objects.requireNonNull(gunId, "gunId");
        if (!MunitionsAmmoFactory.isTaczLoaded()) {
            return Optional.empty();
        }
        var index = TimelessAPI.getCommonGunIndex(gunId);
        if (index.isEmpty()) {
            return unavailable(gunId, "common gun index");
        }
        GunData gunData = index.get().getGunData();
        if (gunData == null) {
            return unavailable(gunId, "gun data");
        }
        BulletData bulletData = gunData.getBulletData();
        if (bulletData == null) {
            return unavailable(gunId, "bullet data");
        }
        ExtraDamage extraDamage = bulletData.getExtraDamage();
        if (extraDamage == null) {
            return unavailable(gunId, "extra damage data");
        }
        LinkedList<ExtraDamage.DistanceDamagePair> damageAdjust = extraDamage.getDamageAdjust();
        if (damageAdjust == null || damageAdjust.isEmpty()) {
            return unavailable(gunId, "distance damage curve");
        }
        ExtraDamage.DistanceDamagePair firstDamagePair = damageAdjust.getFirst();
        try {
            return Optional.of(new GunsmithBaseStats(
                    bulletData.getDamageAmount(),
                    extraDamage.getHeadShotMultiplier(),
                    firstDamagePair.getDistance(),
                    gunData.getAimTime()));
        } catch (IllegalArgumentException exception) {
            LOGGER.error("TaCZ firearm {} has invalid gunsmith base stats", gunId, exception);
            return Optional.empty();
        }
    }

    public static Optional<List<FireMode>> findFireModes(ResourceLocation gunId) {
        Objects.requireNonNull(gunId, "gunId");
        if (!MunitionsAmmoFactory.isTaczLoaded()) {
            return Optional.empty();
        }
        var index = TimelessAPI.getCommonGunIndex(gunId);
        if (index.isEmpty()) {
            return unavailableFireModes(gunId, "common gun index");
        }
        GunData gunData = index.get().getGunData();
        if (gunData == null) {
            return unavailableFireModes(gunId, "gun data");
        }
        List<FireMode> fireModes = gunData.getFireModeSet();
        if (fireModes == null || fireModes.isEmpty() || fireModes.stream().anyMatch(Objects::isNull)
                || fireModes.contains(FireMode.UNKNOWN)) {
            return unavailableFireModes(gunId, "valid fire modes");
        }
        return Optional.of(List.copyOf(fireModes));
    }

    private static Optional<GunsmithBaseStats> unavailable(ResourceLocation gunId, String missingData) {
        LOGGER.error("TaCZ firearm {} is missing {}; gunsmith stats are unavailable", gunId, missingData);
        return Optional.empty();
    }

    private static Optional<List<FireMode>> unavailableFireModes(ResourceLocation gunId, String missingData) {
        LOGGER.error("TaCZ firearm {} is missing {}; gunsmith fire modes are unavailable", gunId, missingData);
        return Optional.empty();
    }
}

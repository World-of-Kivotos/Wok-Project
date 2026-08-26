package com.miningdim.job.munitions.gunsmith;

import com.miningdim.job.munitions.MunitionsAmmoFactory;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.resource.pojo.data.gun.BulletData;
import com.tacz.guns.resource.pojo.data.gun.BurstData;
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
        Optional<GunsmithWeaponBaseProfile> spreadsheetProfile =
                GunsmithWeaponBaseProfile.findByGunId(gunId);
        if (spreadsheetProfile.isPresent()) {
            try {
                return Optional.of(spreadsheetProfile.get().baseStats(gunData.getAimTime()));
            } catch (IllegalArgumentException exception) {
                LOGGER.error("TaCZ firearm {} has invalid spreadsheet-synchronized base stats", gunId, exception);
                return Optional.empty();
            }
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
        return findFireModeProfile(gunId).map(FireModeProfile::fireModes);
    }

    public static Optional<FireModeProfile> findFireModeProfile(ResourceLocation gunId) {
        Objects.requireNonNull(gunId, "gunId");
        if (!MunitionsAmmoFactory.isTaczLoaded()) {
            return Optional.empty();
        }
        var index = TimelessAPI.getCommonGunIndex(gunId);
        if (index.isEmpty()) {
            return unavailableFireModeProfile(gunId, "common gun index");
        }
        GunData gunData = index.get().getGunData();
        if (gunData == null) {
            return unavailableFireModeProfile(gunId, "gun data");
        }
        List<FireMode> fireModes = gunData.getFireModeSet();
        if (fireModes == null || fireModes.isEmpty() || fireModes.stream().anyMatch(Objects::isNull)
                || fireModes.contains(FireMode.UNKNOWN)) {
            return unavailableFireModeProfile(gunId, "valid fire modes");
        }
        if (!fireModes.contains(FireMode.BURST)) {
            return Optional.of(new FireModeProfile(fireModes, 0, false));
        }
        BurstData burstData = gunData.getBurstData();
        if (burstData == null || burstData.getCount() <= 0) {
            return unavailableFireModeProfile(gunId, "valid burst data");
        }
        return Optional.of(new FireModeProfile(fireModes, burstData.getCount(),
                burstData.isContinuousShoot()));
    }

    private static Optional<GunsmithBaseStats> unavailable(ResourceLocation gunId, String missingData) {
        LOGGER.error("TaCZ firearm {} is missing {}; gunsmith stats are unavailable", gunId, missingData);
        return Optional.empty();
    }

    private static Optional<FireModeProfile> unavailableFireModeProfile(
            ResourceLocation gunId, String missingData) {
        LOGGER.error("TaCZ firearm {} is missing {}; gunsmith fire-mode profile is unavailable",
                gunId, missingData);
        return Optional.empty();
    }

    public record FireModeProfile(List<FireMode> fireModes, int burstCount,
                                  boolean continuousBurst) {

        public FireModeProfile {
            fireModes = List.copyOf(fireModes);
        }
    }
}

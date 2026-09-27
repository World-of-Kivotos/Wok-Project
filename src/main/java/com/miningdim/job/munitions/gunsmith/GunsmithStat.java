package com.miningdim.job.munitions.gunsmith;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Function;

enum GunsmithStat {
    DAMAGE,
    HEADSHOT,
    RANGE,
    RECOIL,
    SPREAD,
    HANDLING;

    double coefficient(GunsmithPlatform platform, Function<GunsmithPressPart, Double> resolver) {
        Objects.requireNonNull(resolver, "resolver");
        GunsmithPressPart source = sourcePartOrNull(platform);
        if (source == null) {
            return 1.0D;
        }
        return Objects.requireNonNull(resolver.apply(source), "gunsmith part coefficient");
    }

    /** 该平台上决定这项属性的部件; 这项属性在该平台不受部件影响 (恒为 1.0) 时返回 null。 */
    @Nullable
    GunsmithPressPart sourcePartOrNull(GunsmithPlatform platform) {
        Objects.requireNonNull(platform, "platform");
        if ((this == RANGE && (platform == GunsmithPlatform.PISTOL || platform == GunsmithPlatform.SNIPER
                || platform == GunsmithPlatform.MACHINE_GUN || platform == GunsmithPlatform.SHOTGUN
                || platform == GunsmithPlatform.SMG))
                || (this == RECOIL && platform == GunsmithPlatform.BULLPUP)
                || (this == HANDLING && platform == GunsmithPlatform.SHOTGUN)) {
            return null;
        }
        return sourcePart(platform);
    }

    private GunsmithPressPart sourcePart(GunsmithPlatform platform) {
        return switch (this) {
            case DAMAGE -> switch (platform) {
                case PISTOL -> GunsmithPressPart.HAMMER;
                case BULLPUP -> GunsmithPressPart.RECEIVER;
                case AR, AK, MARKSMAN, MACHINE_GUN, SHOTGUN -> GunsmithPressPart.BOLT;
                case SNIPER, SMG -> GunsmithPressPart.RECEIVER;
            };
            case HEADSHOT -> GunsmithPressPart.BARREL;
            case RANGE -> GunsmithPressPart.CORE;
            case RECOIL -> platform == GunsmithPlatform.PISTOL ? GunsmithPressPart.SLIDE : GunsmithPressPart.STOCK;
            case SPREAD -> platform == GunsmithPlatform.PISTOL ? GunsmithPressPart.TRIGGER : GunsmithPressPart.HANDGUARD;
            case HANDLING -> switch (platform) {
                case MACHINE_GUN -> GunsmithPressPart.BIPOD;
                case SNIPER -> GunsmithPressPart.FIRING_PIN;
                default -> GunsmithPressPart.GRIP;
            };
        };
    }
}

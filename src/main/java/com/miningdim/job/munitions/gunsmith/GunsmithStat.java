package com.miningdim.job.munitions.gunsmith;

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
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(resolver, "resolver");
        if ((this == RANGE && (platform == GunsmithPlatform.PISTOL || platform == GunsmithPlatform.SNIPER
                || platform == GunsmithPlatform.MACHINE_GUN))
                || (this == RECOIL && platform == GunsmithPlatform.BULLPUP)
                || (this == HANDLING && (platform == GunsmithPlatform.MARKSMAN
                        || platform == GunsmithPlatform.SNIPER))) {
            return 1.0D;
        }
        return Objects.requireNonNull(resolver.apply(sourcePart(platform)), "gunsmith part coefficient");
    }

    private GunsmithPressPart sourcePart(GunsmithPlatform platform) {
        return switch (this) {
            case DAMAGE -> switch (platform) {
                case PISTOL -> GunsmithPressPart.HAMMER;
                case BULLPUP -> GunsmithPressPart.RECEIVER;
                case AR, AK, MARKSMAN, MACHINE_GUN -> GunsmithPressPart.BOLT;
                case SNIPER -> GunsmithPressPart.RECEIVER;
            };
            case HEADSHOT -> GunsmithPressPart.BARREL;
            case RANGE -> GunsmithPressPart.CORE;
            case RECOIL -> platform == GunsmithPlatform.PISTOL ? GunsmithPressPart.SLIDE : GunsmithPressPart.STOCK;
            case SPREAD -> platform == GunsmithPlatform.PISTOL ? GunsmithPressPart.TRIGGER : GunsmithPressPart.HANDGUARD;
            case HANDLING -> platform == GunsmithPlatform.MACHINE_GUN
                    ? GunsmithPressPart.BIPOD : GunsmithPressPart.GRIP;
        };
    }
}

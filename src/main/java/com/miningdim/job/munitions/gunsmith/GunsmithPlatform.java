package com.miningdim.job.munitions.gunsmith;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public enum GunsmithPlatform {
    AR("ar", "gunsmith.platform.ar", EnumSet.of(
            GunsmithPressPart.CORE,
            GunsmithPressPart.BARREL,
            GunsmithPressPart.BOLT,
            GunsmithPressPart.HANDGUARD,
            GunsmithPressPart.GRIP,
            GunsmithPressPart.STOCK)),
    AK("ak", "gunsmith.platform.ak", EnumSet.of(
            GunsmithPressPart.CORE,
            GunsmithPressPart.BARREL,
            GunsmithPressPart.BOLT,
            GunsmithPressPart.HANDGUARD,
            GunsmithPressPart.GRIP,
            GunsmithPressPart.STOCK)),
    PISTOL("pistol", "gunsmith.platform.pistol", EnumSet.of(
            GunsmithPressPart.BARREL,
            GunsmithPressPart.SLIDE,
            GunsmithPressPart.GRIP,
            GunsmithPressPart.TRIGGER,
            GunsmithPressPart.HAMMER)),
    BULLPUP("bullpup", "gunsmith.platform.bullpup", EnumSet.of(
            GunsmithPressPart.CORE,
            GunsmithPressPart.BARREL,
            GunsmithPressPart.HANDGUARD,
            GunsmithPressPart.GRIP,
            GunsmithPressPart.RECEIVER)),
    MARKSMAN("marksman", "gunsmith.platform.marksman", List.of(
            GunsmithPressPart.HANDGUARD,
            GunsmithPressPart.CORE,
            GunsmithPressPart.STOCK,
            GunsmithPressPart.BOLT,
            GunsmithPressPart.BARREL));

    private final String id;
    private final String labelKey;
    private final Set<GunsmithPressPart> supportedParts;

    GunsmithPlatform(String id, String labelKey, Collection<GunsmithPressPart> supportedParts) {
        this.id = id;
        this.labelKey = labelKey;
        this.supportedParts = Collections.unmodifiableSet(new LinkedHashSet<>(supportedParts));
    }

    public int index() {
        return ordinal();
    }

    public String id() {
        return id;
    }

    public String labelKey() {
        return labelKey;
    }

    public Set<GunsmithPressPart> supportedParts() {
        return supportedParts;
    }

    public boolean supports(GunsmithPressPart part) {
        return supportedParts.contains(Objects.requireNonNull(part, "part"));
    }

    public static GunsmithPlatform byIndex(int index) {
        GunsmithPlatform[] values = values();
        if (index < 0 || index >= values.length) {
            throw new IllegalArgumentException("Unknown gunsmith platform index: " + index);
        }
        return values[index];
    }

    public static GunsmithPlatform byId(String id) {
        for (GunsmithPlatform platform : values()) {
            if (platform.id.equals(id)) {
                return platform;
            }
        }
        throw new IllegalArgumentException("Unknown gunsmith platform: " + id);
    }
}

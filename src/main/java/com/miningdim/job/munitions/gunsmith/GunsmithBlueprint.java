package com.miningdim.job.munitions.gunsmith;

import net.minecraft.resources.ResourceLocation;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum GunsmithBlueprint {
    M4A1("m4a1", GunsmithPlatform.AR),
    M16A1("m16a1", GunsmithPlatform.AR),
    M16A4("m16a4", GunsmithPlatform.AR),
    HK416D("hk416d", GunsmithPlatform.AR),
    SPR15HB("spr15hb", GunsmithPlatform.MARKSMAN),
    AK47("ak47", GunsmithPlatform.AK),
    RPK("rpk", GunsmithPlatform.AK),
    TYPE_81("type_81", GunsmithPlatform.AK),
    M1911("m1911", GunsmithPlatform.PISTOL),
    M870("m870", GunsmithPlatform.SHOTGUN),
    M1887_LONG("ccrp", "m1887_long", "ccrp.gun.m1887_long.name", GunsmithPlatform.SHOTGUN),
    KSG("hare", "ksg", "hare.gun.ksg.name", GunsmithPlatform.SHOTGUN),
    M1014("m1014", GunsmithPlatform.SHOTGUN),
    UZI("uzi", GunsmithPlatform.SMG),
    UMP45("ump45", GunsmithPlatform.SMG),
    HK_MP5A5("hk_mp5a5", GunsmithPlatform.SMG),
    STERLING("wyyc1991", "stl", "wyyc.stl.name", GunsmithPlatform.SMG),
    MPX("ccrp", "mpx", "ccrp.gun.mpx.name", GunsmithPlatform.SMG),
    KAR98K("kar98", GunsmithPlatform.SNIPER),
    SMLE_III("lavender", "smle_iii", "lavender.gun.smle_iii.name", GunsmithPlatform.SNIPER),
    M700("m700", GunsmithPlatform.SNIPER);

    private static final Map<ResourceLocation, GunsmithBlueprint> BY_GUN_ID = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(GunsmithBlueprint::gunId, Function.identity()));

    private final ResourceLocation gunId;
    private final GunsmithPlatform platform;
    private final String templateId;
    private final String nameKey;
    private final Set<GunsmithPressPart> requiredParts;

    GunsmithBlueprint(String templateId, GunsmithPlatform platform) {
        this("tacz", templateId, "tacz.gun." + templateId + ".name", platform, platform.supportedParts());
    }

    GunsmithBlueprint(String namespace, String templateId, String nameKey, GunsmithPlatform platform) {
        this(namespace, templateId, nameKey, platform, platform.supportedParts());
    }

    GunsmithBlueprint(String namespace, String templateId, String nameKey, GunsmithPlatform platform,
                      Set<GunsmithPressPart> requiredParts) {
        this.gunId = new ResourceLocation(namespace, templateId);
        this.platform = platform;
        this.templateId = templateId;
        this.nameKey = Objects.requireNonNull(nameKey, "nameKey");
        if (nameKey.isBlank()) {
            throw new IllegalArgumentException("Gunsmith blueprint name key must not be blank: " + gunId);
        }
        Objects.requireNonNull(requiredParts, "requiredParts");
        if (requiredParts.isEmpty()) {
            throw new IllegalArgumentException("Gunsmith blueprint must require at least one part: " + templateId);
        }
        EnumSet<GunsmithPressPart> orderedParts = EnumSet.copyOf(requiredParts);
        if (!platform.supportedParts().containsAll(orderedParts)) {
            throw new IllegalArgumentException("Gunsmith blueprint requires an illegal platform part: " + templateId);
        }
        this.requiredParts = Collections.unmodifiableSet(orderedParts);
    }

    public ResourceLocation gunId() {
        return gunId;
    }

    public GunsmithPlatform platform() {
        return platform;
    }

    public String templateId() {
        return templateId;
    }

    public String nameKey() {
        return nameKey;
    }

    public Set<GunsmithPressPart> requiredParts() {
        return requiredParts;
    }

    public int iconModelData() {
        return switch (platform) {
            case AR -> 1;
            case AK -> 2;
            case PISTOL -> 3;
            case SNIPER -> 4;
            case SMG -> 5;
            case SHOTGUN -> 6;
            case MARKSMAN -> 7;
            case BULLPUP, MACHINE_GUN -> throw new IllegalStateException(
                    "Gunsmith blueprint platform has no icon model: " + platform.id());
        };
    }

    public static Optional<GunsmithBlueprint> find(ResourceLocation gunId) {
        return Optional.ofNullable(BY_GUN_ID.get(Objects.requireNonNull(gunId, "gunId")));
    }

    public static GunsmithBlueprint require(ResourceLocation gunId) {
        return find(gunId).orElseThrow(() -> new IllegalArgumentException("Unknown gunsmith blueprint gun id: " + gunId));
    }
}

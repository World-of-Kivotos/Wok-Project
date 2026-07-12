package com.miningdim.job.munitions.gunsmith;

import net.minecraft.resources.ResourceLocation;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum GunsmithBlueprint {
    M4A1("m4a1", GunsmithPlatform.AR),
    M16A1("m16a1", GunsmithPlatform.AR),
    M16A4("m16a4", GunsmithPlatform.AR),
    HK416D("hk416d", GunsmithPlatform.AR),
    SPR15HB("spr15hb", GunsmithPlatform.AR),
    AK47("ak47", GunsmithPlatform.AK),
    RPK("rpk", GunsmithPlatform.AK),
    TYPE_81("type_81", GunsmithPlatform.AK);

    private static final Map<ResourceLocation, GunsmithBlueprint> BY_GUN_ID = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(GunsmithBlueprint::gunId, Function.identity()));

    private final ResourceLocation gunId;
    private final GunsmithPlatform platform;
    private final String templateId;
    private final String nameKey;

    GunsmithBlueprint(String templateId, GunsmithPlatform platform) {
        this.gunId = new ResourceLocation("tacz", templateId);
        this.platform = platform;
        this.templateId = templateId;
        this.nameKey = "tacz.gun." + templateId + ".name";
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

    public static Optional<GunsmithBlueprint> find(ResourceLocation gunId) {
        return Optional.ofNullable(BY_GUN_ID.get(Objects.requireNonNull(gunId, "gunId")));
    }

    public static GunsmithBlueprint require(ResourceLocation gunId) {
        return find(gunId).orElseThrow(() -> new IllegalArgumentException("Unknown gunsmith blueprint gun id: " + gunId));
    }
}

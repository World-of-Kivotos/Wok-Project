package com.miningdim.job.munitions.gunsmith;

public enum GunsmithPlatform {
    AR("ar", "gunsmith.platform.ar"),
    AK("ak", "gunsmith.platform.ak");

    private final String id;
    private final String labelKey;

    GunsmithPlatform(String id, String labelKey) {
        this.id = id;
        this.labelKey = labelKey;
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

    public static GunsmithPlatform byIndex(int index) {
        GunsmithPlatform[] values = values();
        if (index < 0 || index >= values.length) {
            return AR;
        }
        return values[index];
    }

    public static GunsmithPlatform byId(String id) {
        for (GunsmithPlatform platform : values()) {
            if (platform.id.equals(id)) {
                return platform;
            }
        }
        return AR;
    }
}

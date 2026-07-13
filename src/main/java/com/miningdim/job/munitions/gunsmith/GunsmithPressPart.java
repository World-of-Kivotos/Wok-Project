package com.miningdim.job.munitions.gunsmith;

public enum GunsmithPressPart {
    CORE("core", "gunsmith.part.core", "gunsmith.role.core", "GAS", 4, 6, 0),
    BARREL("barrel", "gunsmith.part.barrel", "gunsmith.role.barrel", "BARREL", 3, 7, 0),
    BOLT("bolt", "gunsmith.part.bolt", "gunsmith.role.bolt", "BOLT", 6, 5, 0),
    HANDGUARD("handguard", "gunsmith.part.handguard", "gunsmith.role.handguard", "HG", 3, 2, 4),
    GRIP("grip", "gunsmith.part.grip", "gunsmith.role.grip", "GRIP", 2, 1, 3),
    STOCK("stock", "gunsmith.part.stock", "gunsmith.role.stock", "STOCK", 2, 2, 5),
    SLIDE("slide", "gunsmith.part.slide", "gunsmith.role.slide", "SLIDE", 4, 6, 0),
    TRIGGER("trigger", "gunsmith.part.trigger", "gunsmith.role.trigger", "TRIGGER", 2, 3, 1),
    HAMMER("hammer", "gunsmith.part.hammer", "gunsmith.role.hammer", "HAMMER", 2, 4, 0),
    RECEIVER("receiver", "gunsmith.part.receiver", "gunsmith.role.receiver", "RECEIVER", 6, 6, 5);

    private final String id;
    private final String labelKey;
    private final String roleKey;
    private final String shortLabel;
    private final int partsCost;
    private final int alloyCost;
    private final int polymerCost;

    GunsmithPressPart(String id, String labelKey, String roleKey, String shortLabel,
                      int partsCost, int alloyCost, int polymerCost) {
        this.id = id;
        this.labelKey = labelKey;
        this.roleKey = roleKey;
        this.shortLabel = shortLabel;
        this.partsCost = partsCost;
        this.alloyCost = alloyCost;
        this.polymerCost = polymerCost;
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

    public String roleKey() {
        return roleKey;
    }

    public String shortLabel() {
        return shortLabel;
    }

    public int partsCost() {
        return partsCost;
    }

    public int alloyCost() {
        return alloyCost;
    }

    public int polymerCost() {
        return polymerCost;
    }

    public static GunsmithPressPart byIndex(int index) {
        GunsmithPressPart[] values = values();
        if (index < 0 || index >= values.length) {
            throw new IllegalArgumentException("Unknown gunsmith press part index: " + index);
        }
        return values[index];
    }

    public static GunsmithPressPart byId(String id) {
        for (GunsmithPressPart part : values()) {
            if (part.id.equals(id)) {
                return part;
            }
        }
        throw new IllegalArgumentException("Unknown gunsmith press part: " + id);
    }
}

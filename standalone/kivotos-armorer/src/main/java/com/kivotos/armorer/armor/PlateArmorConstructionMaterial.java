package com.kivotos.armorer.armor;

/** 主防护结构材料。材料配置修正漏伤、承压、耐久与机动，但不改变护甲等级。 */
public enum PlateArmorConstructionMaterial {
    UHMWPE("uhmwpe", 850, 0.88D, 1.08D, 0.94D, 1.08D, 0.0D),
    ARAMID("aramid", 900, 0.94D, 1.18D, 1.00D, 0.90D, 0.0D),
    ARMOR_STEEL("armor_steel", 760, 0.88D, 0.88D, 0.88D, 1.15D, 0.03D),
    COMBINED("combined", 610, 0.94D, 0.94D, 0.94D, 1.08D, 0.01D),
    ALUMINUM("aluminum", 580, 0.98D, 1.00D, 0.98D, 1.00D, 0.015D),
    TITANIUM("titanium", 700, 0.90D, 0.98D, 0.94D, 1.08D, 0.005D),
    CERAMIC("ceramic", 420, 0.94D, 0.88D, 1.00D, 1.15D, 0.005D);

    private final String id;
    private final int defaultDurability;
    private final double defaultBallisticLeakMultiplier;
    private final double defaultArmorPiercingLeakMultiplier;
    private final double defaultGeneralLeakMultiplier;
    private final double defaultPressureCapacityMultiplier;
    private final double defaultMovementPenalty;

    PlateArmorConstructionMaterial(String id,
                                   int defaultDurability,
                                   double defaultBallisticLeakMultiplier,
                                   double defaultArmorPiercingLeakMultiplier,
                                   double defaultGeneralLeakMultiplier,
                                   double defaultPressureCapacityMultiplier,
                                   double defaultMovementPenalty) {
        this.id = id;
        this.defaultDurability = defaultDurability;
        this.defaultBallisticLeakMultiplier = defaultBallisticLeakMultiplier;
        this.defaultArmorPiercingLeakMultiplier = defaultArmorPiercingLeakMultiplier;
        this.defaultGeneralLeakMultiplier = defaultGeneralLeakMultiplier;
        this.defaultPressureCapacityMultiplier = defaultPressureCapacityMultiplier;
        this.defaultMovementPenalty = defaultMovementPenalty;
    }

    public String id() {
        return id;
    }

    public String translationKey() {
        return "material.kivotos_armorer.plate_armor." + id;
    }

    public int defaultDurability() {
        return defaultDurability;
    }

    public double defaultBallisticLeakMultiplier() {
        return defaultBallisticLeakMultiplier;
    }

    public double defaultArmorPiercingLeakMultiplier() {
        return defaultArmorPiercingLeakMultiplier;
    }

    public double defaultGeneralLeakMultiplier() {
        return defaultGeneralLeakMultiplier;
    }

    public double defaultPressureCapacityMultiplier() {
        return defaultPressureCapacityMultiplier;
    }

    public double defaultMovementPenalty() {
        return defaultMovementPenalty;
    }
}


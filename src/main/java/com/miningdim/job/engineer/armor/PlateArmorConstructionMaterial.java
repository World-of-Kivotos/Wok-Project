package com.miningdim.job.engineer.armor;

/**
 * 主防护结构材料。模块化载体取默认主板材质，固定式护甲取内置防护材料。
 * 材料不改变 R/Q/G/T，只决定装备最大耐久；双材质固定式护甲保留独立类别。
 */
public enum PlateArmorConstructionMaterial {
    UHMWPE("uhmwpe", 640),
    ARAMID("aramid", 860),
    ARMOR_STEEL("armor_steel", 510),
    TITANIUM_ARAMID("titanium_aramid", 630),
    COMBINED("combined", 610),
    ALUMINUM("aluminum", 550),
    TITANIUM("titanium", 580),
    CERAMIC_ARAMID("ceramic_aramid", 540),
    CERAMIC("ceramic", 480);

    private final String id;
    private final int defaultDurability;

    PlateArmorConstructionMaterial(String id, int defaultDurability) {
        this.id = id;
        this.defaultDurability = defaultDurability;
    }

    public String id() {
        return id;
    }

    public String translationKey() {
        return "material.miningdim.plate_armor." + id;
    }

    public int defaultDurability() {
        return defaultDurability;
    }
}

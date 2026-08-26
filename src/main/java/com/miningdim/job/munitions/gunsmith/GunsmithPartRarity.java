package com.miningdim.job.munitions.gunsmith;

/**
 * 组件型号自身的稀有度；与单件冲压品质和浮动系数彼此独立。
 */
public enum GunsmithPartRarity {
    STANDARD("standard", "gunsmith.rarity.standard", 0xF2F6FF),
    MODIFIED("modified", "gunsmith.rarity.modified", 0xEAF7FF),
    SPECIAL("special", "gunsmith.rarity.special", 0xEEFFF4),
    ADVANCED("advanced", "gunsmith.rarity.advanced", 0xF5EEFF),
    PROTOTYPE("prototype", "gunsmith.rarity.prototype", 0xFFF0F0);

    private final String id;
    private final String labelKey;
    private final int textColor;

    GunsmithPartRarity(String id, String labelKey, int textColor) {
        this.id = id;
        this.labelKey = labelKey;
        this.textColor = textColor;
    }

    public String id() {
        return id;
    }

    public String labelKey() {
        return labelKey;
    }

    public int textColor() {
        return textColor;
    }
}

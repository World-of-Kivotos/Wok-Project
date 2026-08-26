package com.miningdim.job.munitions.gunsmith;

import java.util.List;

/** 枪匠部件的实际组件型号。 */
public enum GunsmithPartVariant {
    BASE("base", "gunsmith.variant.base", 0, GunsmithPartRarity.STANDARD, null),
    GEHENNA_GAS("gehenna_gas", "gunsmith.variant.gehenna_gas", 10000,
            GunsmithPartRarity.SPECIAL, GunsmithFaction.GEHENNA),
    RED_EAST_HIGH_PRESSURE_GAS("red_east_high_pressure_gas",
            "gunsmith.variant.red_east_high_pressure_gas", 10100,
            GunsmithPartRarity.SPECIAL, GunsmithFaction.RED_WINTER),
    MK_AX_A_BOLT("mk_ax_a_bolt", "gunsmith.variant.mk_ax_a_bolt", 10200,
            GunsmithPartRarity.PROTOTYPE, GunsmithFaction.BLUE_HEAVY_INDUSTRIES),
    TRINITY_PRECISION_GRADUATED_BARREL("trinity_precision_graduated_barrel",
            "gunsmith.variant.trinity_precision_graduated_barrel", 10300,
            GunsmithPartRarity.ADVANCED, GunsmithFaction.TRINITY),
    AR_THREE_ROUND_BURST_BOLT("ar_three_round_burst_bolt",
            "gunsmith.variant.ar_three_round_burst_bolt", 10400,
            GunsmithPartRarity.MODIFIED, null),
    TRINITY_PRECISION_GRADUATED_SNIPER_BARREL("trinity_precision_graduated_sniper_barrel",
            "gunsmith.variant.trinity_precision_graduated_sniper_barrel", 10600,
            GunsmithPartRarity.ADVANCED, GunsmithFaction.TRINITY),
    RED_WINTER_CHIXUE_A_BOLT("red_winter_chixue_a_bolt",
            "gunsmith.variant.red_winter_chixue_a_bolt", 10700,
            GunsmithPartRarity.SPECIAL, GunsmithFaction.RED_WINTER);

    private final String id;
    private static final int SNIPER_FIRING_PIN_MODEL_DATA_BASE = 10500;
    private final String labelKey;
    private final int customModelDataBase;
    private final GunsmithPartRarity rarity;
    private final GunsmithFaction faction;

    GunsmithPartVariant(String id, String labelKey, int customModelDataBase,
                        GunsmithPartRarity rarity, GunsmithFaction faction) {
        this.id = id;
        this.labelKey = labelKey;
        this.customModelDataBase = customModelDataBase;
        this.rarity = rarity;
        this.faction = faction;
    }

    public String id() {
        return id;
    }

    public String labelKey() {
        return labelKey;
    }

    public int index() {
        return ordinal();
    }

    public boolean supports(GunsmithPlatform platform, GunsmithPressPart part) {
        if (this == BASE) {
            return true;
        }
        return switch (this) {
            case GEHENNA_GAS -> platform == GunsmithPlatform.AR && part == GunsmithPressPart.CORE;
            case RED_EAST_HIGH_PRESSURE_GAS -> platform == GunsmithPlatform.AK
                    && part == GunsmithPressPart.CORE;
            case MK_AX_A_BOLT -> platform == GunsmithPlatform.AR
                    && part == GunsmithPressPart.BOLT;
            case TRINITY_PRECISION_GRADUATED_BARREL -> platform == GunsmithPlatform.AR
                    && part == GunsmithPressPart.BARREL;
            case AR_THREE_ROUND_BURST_BOLT -> platform == GunsmithPlatform.AR
                    && part == GunsmithPressPart.BOLT;
            case TRINITY_PRECISION_GRADUATED_SNIPER_BARREL -> platform == GunsmithPlatform.SNIPER
                    && part == GunsmithPressPart.BARREL;
            case RED_WINTER_CHIXUE_A_BOLT -> platform == GunsmithPlatform.AK
                    && part == GunsmithPressPart.BOLT;
            case BASE -> true;
        };
    }

    public boolean forcesBurstFireMode() {
        return this == AR_THREE_ROUND_BURST_BOLT;
    }

    /** 对成品枪最大耐久的倍率修正；小于 1 表示组件代价。 */
    public double maximumDurabilityMultiplier() {
        return this == TRINITY_PRECISION_GRADUATED_BARREL ? 0.70D : 1.0D;
    }

    public GunsmithPartRarity rarity() {
        return rarity;
    }

    public GunsmithFaction faction() {
        return faction;
    }

    public int customModelData(GunsmithPlatform platform, GunsmithPressPart part,
                               GunsmithPartQuality quality) {
        if (this == BASE) {
            // FIRING_PIN was appended after the shared ordinal-based ranges were already published.
            // Its raw 611-615 range would collide with MACHINE_GUN/BARREL, so keep it in a reserved range.
            if (platform == GunsmithPlatform.SNIPER && part == GunsmithPressPart.FIRING_PIN) {
                return SNIPER_FIRING_PIN_MODEL_DATA_BASE + quality.index() + 1;
            }
            return platform.index() * 100 + part.index() * 10 + quality.index() + 1;
        }
        if (!supports(platform, part)) {
            throw new IllegalArgumentException("Gunsmith variant " + id + " does not support "
                    + platform.id() + "/" + part.id());
        }
        return customModelDataBase + quality.index() + 1;
    }

    public double damageMultiplier(GunsmithPartQuality quality) {
        return GunsmithComponentRules.get(this).damage(quality);
    }

    public double headshotMultiplier(GunsmithPartQuality quality) {
        return GunsmithComponentRules.get(this).headshot(quality);
    }

    public double fireRateMultiplier(GunsmithPartQuality quality) {
        return GunsmithComponentRules.get(this).fireRate(quality);
    }

    public double rangeMultiplier(GunsmithPartQuality quality) {
        return GunsmithComponentRules.get(this).effectiveRange(quality);
    }

    public double applyRangeMultiplier(double base, GunsmithPartQuality quality) {
        return GunsmithComponentRules.get(this).applyRange(base, quality);
    }

    public double spreadMultiplier(GunsmithPartQuality quality) {
        return GunsmithComponentRules.get(this).spread(quality);
    }

    public double ammoSpeedMultiplier(GunsmithPartQuality quality) {
        return GunsmithComponentRules.get(this).ammoSpeed(quality);
    }

    public double armorIgnoreMultiplier(GunsmithPartQuality quality) {
        return GunsmithComponentRules.get(this).armorIgnore(quality);
    }

    public double recoilMultiplier(GunsmithPartQuality quality) {
        return GunsmithComponentRules.get(this).recoil(quality);
    }

    public double verticalRecoilMultiplier(GunsmithPartQuality quality) {
        return GunsmithComponentRules.get(this).verticalRecoil(quality);
    }

    public double adsSpeedMultiplier(GunsmithPartQuality quality) {
        return GunsmithComponentRules.get(this).adsSpeed(quality);
    }

    public boolean hasStatEffects(GunsmithPartQuality quality) {
        return GunsmithComponentRules.get(this).hasEffects(quality);
    }

    public static GunsmithPartVariant byId(String id) {
        // 兼容已发布命名与测试包早期命名；新写入统一使用当前稳定 id。
        if ("basic".equals(id)) {
            return BASE;
        }
        if ("gehenna_high_speed_gas".equals(id)) {
            return GEHENNA_GAS;
        }
        // 纠正早期误建的 AR 机匣槽：存量物品仍可迁移为 MK-AX-A 枪机。
        if ("mk_ax_a_receiver".equals(id)) {
            return MK_AX_A_BOLT;
        }
        for (GunsmithPartVariant variant : values()) {
            if (variant.id.equals(id)) {
                return variant;
            }
        }
        throw new IllegalArgumentException("Unknown gunsmith part variant: " + id);
    }

    public static GunsmithPartVariant byIndex(int index) {
        GunsmithPartVariant[] variants = values();
        if (index < 0 || index >= variants.length) {
            throw new IllegalArgumentException("Unknown gunsmith part variant index: " + index);
        }
        return variants[index];
    }

    public static List<GunsmithPartVariant> availableFor(GunsmithPlatform platform, GunsmithPressPart part) {
        return java.util.Arrays.stream(values()).filter(variant -> variant.supports(platform, part)).toList();
    }
}

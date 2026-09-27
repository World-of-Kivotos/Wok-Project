package com.miningdim.job.munitions.gunsmith;

import com.miningdim.core.MiningConstants;
import net.minecraft.resources.ResourceLocation;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * WOK main-server firearm base values transcribed from WOK主服枪械数据表.xlsx.
 *
 * <p>These values are only applied to guns assembled by the WOK gunsmith. Regular TaCZ
 * guns keep the owning gun pack's data.</p>
 *
 * <p>Each value is the gun row's effective value in a recalculated workbook: the row's
 * override when one is filled in, otherwise the default of the ammo it fires. Never
 * transcribe cached cell values. The bolt-action rows (KAR98K, SMLE III, M700) carry the
 * 2026-09 PvE overrides; a workbook that still shows 25/20 for them predates that change.
 * The second 2026-09 batch overrides the SMG, pump/lever shotgun and M1911 rows and adds the
 * M16A1, M16A4 and SPR15HB rows (DU rows 10-12 until their academy page is assigned); a
 * workbook without those rows or overrides predates that batch.
 * Values apply to every fire mode: a pack's fire_mode_adjust damage, headshot and
 * armor-ignore bonuses are replaced by the profile, while its RPM, bullet-speed, knockback
 * and inaccuracy adjustments still apply.</p>
 */
public record GunsmithWeaponBaseProfile(double armorIgnore, double headshotMultiplier,
                                        List<DamagePoint> damageCurve) {

    public static final float INFINITE_DISTANCE = Integer.MAX_VALUE;

    private static final Map<GunsmithBlueprint, GunsmithWeaponBaseProfile> BY_BLUEPRINT = profiles();

    public GunsmithWeaponBaseProfile {
        if (!Double.isFinite(armorIgnore) || armorIgnore < 0.0D || armorIgnore > 1.0D) {
            throw new IllegalArgumentException("armorIgnore must be finite and between zero and one");
        }
        requirePositiveFinite(headshotMultiplier, "headshotMultiplier");
        damageCurve = List.copyOf(Objects.requireNonNull(damageCurve, "damageCurve"));
        if (damageCurve.size() < 2 || damageCurve.size() > 3) {
            throw new IllegalArgumentException("spreadsheet damage curve must contain two or three points");
        }
        float previousDistance = 0.0F;
        for (DamagePoint point : damageCurve) {
            Objects.requireNonNull(point, "damageCurve contains a null point");
            if (point.distance() <= previousDistance) {
                throw new IllegalArgumentException("damage curve distances must be strictly increasing");
            }
            previousDistance = point.distance();
        }
        if (damageCurve.get(damageCurve.size() - 1).distance() != INFINITE_DISTANCE) {
            throw new IllegalArgumentException("the final spreadsheet damage point must be infinite");
        }
    }

    public double damage() {
        return damageCurve.get(0).damage();
    }

    public double effectiveRange() {
        return damageCurve.get(0).distance();
    }

    public GunsmithBaseStats baseStats(double adsTime) {
        return new GunsmithBaseStats(damage(), headshotMultiplier, effectiveRange(), adsTime);
    }

    public static Optional<GunsmithWeaponBaseProfile> find(GunsmithBlueprint blueprint) {
        return Optional.ofNullable(BY_BLUEPRINT.get(Objects.requireNonNull(blueprint, "blueprint")));
    }

    /** Resolves original TaCZ ids, the legacy M4 gunsmith id, and dedicated AR burst ids. */
    public static Optional<GunsmithWeaponBaseProfile> findByGunId(ResourceLocation gunId) {
        Objects.requireNonNull(gunId, "gunId");
        Optional<GunsmithBlueprint> direct = GunsmithBlueprint.find(gunId);
        if (direct.isPresent()) {
            return find(direct.get());
        }
        if (!MiningConstants.MODID.equals(gunId.getNamespace())) {
            return Optional.empty();
        }
        if ("m4a1_gunsmith".equals(gunId.getPath())) {
            return find(GunsmithBlueprint.M4A1);
        }
        String suffix = "_gunsmith_burst";
        if (!gunId.getPath().endsWith(suffix)) {
            return Optional.empty();
        }
        String templateId = gunId.getPath().substring(0, gunId.getPath().length() - suffix.length());
        for (GunsmithBlueprint blueprint : GunsmithBlueprint.values()) {
            if (blueprint.platform() == GunsmithPlatform.AR
                    && blueprint.templateId().equals(templateId)) {
                return find(blueprint);
            }
        }
        return Optional.empty();
    }

    static Map<GunsmithBlueprint, GunsmithWeaponBaseProfile> all() {
        return BY_BLUEPRINT;
    }

    private static Map<GunsmithBlueprint, GunsmithWeaponBaseProfile> profiles() {
        EnumMap<GunsmithBlueprint, GunsmithWeaponBaseProfile> profiles =
                new EnumMap<>(GunsmithBlueprint.class);
        // DU
        put(profiles, GunsmithBlueprint.M1911, 0.20D, 1.50D, 19.0D, 19.0F, 14.5D, 40.0F, 9.5D);
        put(profiles, GunsmithBlueprint.UZI, 0.15D, 1.50D, 13.0D, 15.0F, 10.0D, 30.0F, 6.5D);
        put(profiles, GunsmithBlueprint.UMP45, 0.15D, 1.50D, 13.0D, 25.0F, 10.0D, 35.0F, 6.5D);
        put(profiles, GunsmithBlueprint.M870, 0.25D, 1.10D, 67.0D, 18.0F, 42.0D, 32.0F, 25.0D);
        put(profiles, GunsmithBlueprint.M4A1, 0.30D, 1.50D, 8.0D, 35.0F, 7.0D, 60.0F, 6.0D);
        // DU (rows 10-11, placement pending): academy page not yet assigned
        put(profiles, GunsmithBlueprint.M16A1, 0.30D, 1.50D, 9.5D, 45.0F, 8.0D, 70.0F, 5.5D);
        put(profiles, GunsmithBlueprint.M16A4, 0.30D, 1.50D, 10.5D, 45.0F, 8.5D, 70.0F, 8.0D);
        // DU (row 12, placement pending): marksman row; values cover every fire mode
        put(profiles, GunsmithBlueprint.SPR15HB, 0.30D, 2.00D, 16.5D, 40.0F, 13.0D, 72.0F, 11.5D);
        // Gehenna
        put(profiles, GunsmithBlueprint.HK416D, 0.30D, 1.50D, 8.0D, 25.0F, 7.0D, 60.0F, 6.0D);
        put(profiles, GunsmithBlueprint.HK_MP5A5, 0.15D, 1.50D, 10.0D, 25.0F, 7.5D, 40.0F, 5.0D);
        put(profiles, GunsmithBlueprint.KAR98K, 0.40D, 1.50D, 45.0D, 80.0F, 36.0D,
                INFINITE_DISTANCE, 36.0D);
        // Trinity
        put(profiles, GunsmithBlueprint.STERLING, 0.15D, 1.50D, 16.0D, 15.0F, 12.0D, 30.0F, 8.0D);
        put(profiles, GunsmithBlueprint.M1887_LONG, 0.25D, 1.10D, 79.0D, 35.0F, 49.5D, 60.0F, 29.5D);
        put(profiles, GunsmithBlueprint.SMLE_III, 0.40D, 1.50D, 40.0D, 80.0F, 32.0D,
                INFINITE_DISTANCE, 32.0D);
        // Millennium
        put(profiles, GunsmithBlueprint.MPX, 0.15D, 1.50D, 9.5D, 25.0F, 7.0D, 40.0F, 5.0D);
        put(profiles, GunsmithBlueprint.KSG, 0.25D, 1.10D, 64.0D, 35.0F, 40.0D, 60.0F, 24.0D);
        put(profiles, GunsmithBlueprint.M700, 0.40D, 1.50D, 40.0D, 120.0F, 32.0D,
                INFINITE_DISTANCE, 32.0D);
        // Abydos
        put(profiles, GunsmithBlueprint.M1014, 0.00D, 1.10D, 40.0D, 25.0F, 25.0D, 30.0F, 15.0D);
        return Map.copyOf(profiles);
    }

    private static void put(Map<GunsmithBlueprint, GunsmithWeaponBaseProfile> profiles,
                            GunsmithBlueprint blueprint, double armorIgnore, double headshotMultiplier,
                            double firstDamage, float firstRange,
                            double secondDamage, float secondRange, double thirdDamage) {
        List<DamagePoint> damageCurve = secondRange == INFINITE_DISTANCE
                && Double.compare(secondDamage, thirdDamage) == 0
                ? List.of(new DamagePoint(firstRange, firstDamage),
                        new DamagePoint(INFINITE_DISTANCE, secondDamage))
                : List.of(new DamagePoint(firstRange, firstDamage),
                        new DamagePoint(secondRange, secondDamage),
                        new DamagePoint(INFINITE_DISTANCE, thirdDamage));
        GunsmithWeaponBaseProfile previous = profiles.put(blueprint,
                new GunsmithWeaponBaseProfile(armorIgnore, headshotMultiplier, damageCurve));
        if (previous != null) {
            throw new IllegalStateException("duplicate spreadsheet profile for " + blueprint.gunId());
        }
    }

    private static void requirePositiveFinite(double value, String field) {
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new IllegalArgumentException(field + " must be positive and finite");
        }
    }

    public record DamagePoint(float distance, double damage) {

        public DamagePoint {
            if (!Float.isFinite(distance) || distance <= 0.0F) {
                throw new IllegalArgumentException("damage point distance must be positive and finite");
            }
            requirePositiveFinite(damage, "damage point damage");
        }
    }
}

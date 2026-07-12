package com.miningdim.job.munitions.gunsmith;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

public final class GunsmithGunStats {

    public static final String ROOT_KEY = "MiningDimGunsmith";
    public static final String PARTS_KEY = "Parts";
    public static final String STATS_KEY = "Stats";

    private final CompoundTag root;
    private final CompoundTag stats;

    private GunsmithGunStats(CompoundTag root, CompoundTag stats) {
        this.root = root;
        this.stats = stats;
        requireString(root, "platform");
        requireString(root, "template");
        gunId();
        value("damage");
        value("headshot");
        value("recoil");
        value("spread");
        value("handling");
        value("average");
    }

    public static GunsmithGunStats from(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(ROOT_KEY)) {
            return null;
        }
        if (!tag.contains(ROOT_KEY, Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Gunsmith root data is not a compound");
        }
        CompoundTag root = tag.getCompound(ROOT_KEY);
        if (!root.contains(STATS_KEY, Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Gunsmith root data has no stats compound");
        }
        return new GunsmithGunStats(root, root.getCompound(STATS_KEY));
    }

    public String platform() {
        return root.getString("platform");
    }

    public String template() {
        return root.getString("template");
    }

    public ResourceLocation gunId() {
        String encoded = requireString(root, "gunId");
        ResourceLocation gunId = ResourceLocation.tryParse(encoded);
        if (gunId == null) {
            throw new IllegalArgumentException("Gunsmith root data has an invalid gunId: " + encoded);
        }
        return gunId;
    }

    public double damage() {
        return value("damage");
    }

    public double headshot() {
        return value("headshot");
    }

    public double recoil() {
        return value("recoil");
    }

    public double spread() {
        return value("spread");
    }

    public double handling() {
        return value("handling");
    }

    public double average() {
        return value("average");
    }

    public double effectiveDamage(GunsmithBaseStats baseStats) {
        return Objects.requireNonNull(baseStats, "baseStats").damage() * damage();
    }

    public double effectiveHeadshot(GunsmithBaseStats baseStats) {
        return Objects.requireNonNull(baseStats, "baseStats").headshot() * headshot();
    }

    public int effectiveRpm(GunsmithBaseStats baseStats) {
        return effectiveRpm(Objects.requireNonNull(baseStats, "baseStats").rpm(), recoil());
    }

    public double effectiveAdsTime(GunsmithBaseStats baseStats) {
        return effectiveAdsTime(Objects.requireNonNull(baseStats, "baseStats").adsTime(), handling());
    }

    public double recoilChange() {
        return inverse(recoil()) - 1.0D;
    }

    public double spreadChange() {
        return inverse(spread()) - 1.0D;
    }

    private double value(String key) {
        if (!stats.contains(key, Tag.TAG_DOUBLE)) {
            throw new IllegalArgumentException("Gunsmith stats has no double value for " + key);
        }
        double value = stats.getDouble(key);
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new IllegalArgumentException("Gunsmith stat must be positive and finite: " + key);
        }
        return value;
    }

    static int effectiveRpm(int baseRpm, double coefficient) {
        if (baseRpm <= 0) {
            throw new IllegalArgumentException("Base RPM must be positive");
        }
        long rpm = Math.round(baseRpm * coefficient);
        if (rpm < 1L || rpm > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Effective RPM is outside the integer range: " + rpm);
        }
        return (int) rpm;
    }

    static double effectiveAdsTime(double baseAdsTime, double coefficient) {
        if (!Double.isFinite(baseAdsTime) || baseAdsTime <= 0.0D) {
            throw new IllegalArgumentException("Base ADS time must be positive and finite");
        }
        return baseAdsTime * inverse(coefficient);
    }

    private static double inverse(double coefficient) {
        if (!Double.isFinite(coefficient) || coefficient <= 0.0D) {
            throw new IllegalArgumentException("Coefficient must be positive and finite: " + coefficient);
        }
        return 1.0D / coefficient;
    }

    private static String requireString(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_STRING)) {
            throw new IllegalArgumentException("Gunsmith root data has no string value for " + key);
        }
        String value = tag.getString(key);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Gunsmith root data has an empty value for " + key);
        }
        return value;
    }
}

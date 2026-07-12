package com.miningdim.job.munitions.gunsmith;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

public final class GunsmithGunStats {

    public static final String ROOT_KEY = "MiningDimGunsmith";
    public static final String PARTS_KEY = "Parts";
    public static final String STATS_KEY = "Stats";
    public static final double M4_BASE_DAMAGE = 6.5D;
    public static final double M4_BASE_HEADSHOT = 1.5D;
    public static final int M4_BASE_RPM = 810;
    public static final double M4_BASE_ADS_TIME = 0.16D;

    private final CompoundTag root;
    private final CompoundTag stats;

    private GunsmithGunStats(CompoundTag root, CompoundTag stats) {
        this.root = root;
        this.stats = stats;
    }

    public static GunsmithGunStats from(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(ROOT_KEY)) {
            return null;
        }
        CompoundTag root = tag.getCompound(ROOT_KEY);
        if (!root.contains(STATS_KEY)) {
            return null;
        }
        return new GunsmithGunStats(root, root.getCompound(STATS_KEY));
    }

    public String platform() {
        return root.getString("platform");
    }

    public String template() {
        return root.getString("template");
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

    public double effectiveDamage() {
        return M4_BASE_DAMAGE * damage();
    }

    public double effectiveHeadshot() {
        return M4_BASE_HEADSHOT * headshot();
    }

    public int effectiveRpm() {
        return Math.max(1, (int) Math.round(M4_BASE_RPM * recoil()));
    }

    public double effectiveAdsTime() {
        return M4_BASE_ADS_TIME * inverse(handling());
    }

    public double recoilChange() {
        return inverse(recoil()) - 1.0D;
    }

    public double spreadChange() {
        return inverse(spread()) - 1.0D;
    }

    private double value(String key) {
        return stats.contains(key) ? stats.getDouble(key) : 1.0D;
    }

    private static double inverse(double coefficient) {
        return 1.0D / Math.max(0.1D, coefficient);
    }
}

package com.miningdim.job.munitions.gunsmith;

import com.miningdim.job.munitions.ModMunitionsItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public final class GunsmithAssemblyRecipe {

    public static final GunsmithPlatform PLATFORM = GunsmithPlatform.AR;

    private static final String TEMPLATE = "m4a1";

    private GunsmithAssemblyRecipe() {
    }

    public static boolean isBlueprint(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        return !stack.isEmpty() && stack.is(ModMunitionsItems.M4_ASSEMBLY_TEMPLATE.get());
    }

    public static boolean matchesPart(ItemStack stack, GunsmithPressPart part) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(part, "part");
        if (stack.isEmpty() || !stack.is(ModMunitionsItems.GUNSMITH_PART.get())) {
            return false;
        }
        GunsmithPartItem.PartData data = GunsmithPartItem.requirePartData(stack);
        return data.platform() == PLATFORM && data.part() == part;
    }

    public static ItemStack assemble(ItemStack baseGun, Map<GunsmithPressPart, ItemStack> parts) {
        Objects.requireNonNull(baseGun, "baseGun");
        if (baseGun.isEmpty()) {
            throw new IllegalArgumentException("Assembly base gun is empty");
        }
        EnumMap<GunsmithPressPart, Double> coefficients = coefficients(parts, true);
        ItemStack result = baseGun.copy();
        CompoundTag root = new CompoundTag();
        root.putString("template", TEMPLATE);
        root.putString("platform", PLATFORM.id());
        root.putString("gunId", GunsmithGunFactory.M4A1_ID.toString());
        root.put(GunsmithGunStats.PARTS_KEY, partTags(parts));
        root.put(GunsmithGunStats.STATS_KEY, stats(coefficients));
        result.getOrCreateTag().put(GunsmithGunStats.ROOT_KEY, root);
        return result;
    }

    public static Preview preview(Map<GunsmithPressPart, ItemStack> parts) {
        EnumMap<GunsmithPressPart, Double> coefficients = coefficients(parts, false);
        double recoil = (coefficients.get(GunsmithPressPart.CORE)
                + coefficients.get(GunsmithPressPart.STOCK)) / 2.0D;
        double spread = coefficients.get(GunsmithPressPart.HANDGUARD);
        double handling = coefficients.get(GunsmithPressPart.GRIP);
        double average = average(coefficients);
        return new Preview(
                GunsmithGunStats.M4_BASE_DAMAGE * coefficients.get(GunsmithPressPart.BOLT),
                GunsmithGunStats.M4_BASE_HEADSHOT * coefficients.get(GunsmithPressPart.BARREL),
                GunsmithGunStats.effectiveRpm(recoil),
                (1.0D / recoil - 1.0D) * 100.0D,
                (1.0D / spread - 1.0D) * 100.0D,
                GunsmithGunStats.effectiveAdsTime(handling),
                average);
    }

    private static EnumMap<GunsmithPressPart, Double> coefficients(Map<GunsmithPressPart, ItemStack> parts,
                                                                     boolean requireComplete) {
        Objects.requireNonNull(parts, "parts");
        if (parts.size() != GunsmithPressPart.values().length) {
            throw new IllegalArgumentException("Assembly parts must contain every gunsmith press part exactly once");
        }
        EnumMap<GunsmithPressPart, Double> coefficients = new EnumMap<>(GunsmithPressPart.class);
        for (GunsmithPressPart part : GunsmithPressPart.values()) {
            if (!parts.containsKey(part)) {
                throw new IllegalArgumentException("Assembly parts is missing " + part.id());
            }
            ItemStack stack = Objects.requireNonNull(parts.get(part), "Assembly part stack for " + part.id());
            if (stack.isEmpty()) {
                if (requireComplete) {
                    throw new IllegalArgumentException("Assembly part stack is empty for " + part.id());
                }
                coefficients.put(part, 1.0D);
                continue;
            }
            GunsmithPartItem.PartData data = GunsmithPartItem.requirePartData(stack);
            if (data.platform() != PLATFORM) {
                throw new IllegalArgumentException("Assembly part platform must be " + PLATFORM.id());
            }
            if (data.part() != part) {
                throw new IllegalArgumentException("Assembly slot " + part.id()
                        + " received " + data.part().id());
            }
            coefficients.put(part, data.coefficient());
        }
        return coefficients;
    }

    private static CompoundTag partTags(Map<GunsmithPressPart, ItemStack> parts) {
        CompoundTag result = new CompoundTag();
        for (GunsmithPressPart part : GunsmithPressPart.values()) {
            GunsmithPartItem.PartData data = GunsmithPartItem.requirePartData(parts.get(part));
            CompoundTag partTag = new CompoundTag();
            partTag.putString("quality", data.quality().id());
            partTag.putDouble("coefficient", data.coefficient());
            result.put(part.id(), partTag);
        }
        return result;
    }

    private static CompoundTag stats(EnumMap<GunsmithPressPart, Double> coefficients) {
        CompoundTag stats = new CompoundTag();
        stats.putDouble("damage", coefficients.get(GunsmithPressPart.BOLT));
        stats.putDouble("headshot", coefficients.get(GunsmithPressPart.BARREL));
        stats.putDouble("recoil", (coefficients.get(GunsmithPressPart.CORE)
                + coefficients.get(GunsmithPressPart.STOCK)) / 2.0D);
        stats.putDouble("spread", coefficients.get(GunsmithPressPart.HANDGUARD));
        stats.putDouble("handling", coefficients.get(GunsmithPressPart.GRIP));
        stats.putDouble("average", average(coefficients));
        return stats;
    }

    private static double average(EnumMap<GunsmithPressPart, Double> coefficients) {
        double total = 0.0D;
        for (GunsmithPressPart part : GunsmithPressPart.values()) {
            total += coefficients.get(part);
        }
        return total / GunsmithPressPart.values().length;
    }

    public record Preview(double damage, double headshot, int rpm, double recoilChange,
                          double spreadChange, double adsTime, double average) {

        public double fireRate() {
            return rpm;
        }

        public double recoil() {
            return recoilChange;
        }

        public double spread() {
            return spreadChange;
        }

        public double aimDownSight() {
            return adsTime;
        }

        public double overallCoefficient() {
            return average;
        }
    }
}

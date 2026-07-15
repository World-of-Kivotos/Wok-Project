package com.miningdim.job.munitions.gunsmith;

import com.miningdim.job.munitions.ModMunitionsItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class GunsmithAssemblyRecipe {

    private GunsmithAssemblyRecipe() {
    }

    public static boolean isBlueprint(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.is(ModMunitionsItems.M4_ASSEMBLY_TEMPLATE.get())) {
            return true;
        }
        if (!GunsmithBlueprintItem.isBlueprintItem(stack)) {
            return false;
        }
        GunsmithBlueprintItem.requireBlueprint(stack);
        return true;
    }

    public static GunsmithBlueprint blueprint(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.is(ModMunitionsItems.M4_ASSEMBLY_TEMPLATE.get())) {
            return GunsmithBlueprint.M4A1;
        }
        if (!GunsmithBlueprintItem.isBlueprintItem(stack)) {
            throw new IllegalArgumentException("Item stack is not a gunsmith blueprint");
        }
        return GunsmithBlueprintItem.requireBlueprint(stack);
    }

    public static ResourceLocation assembledGunId(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.is(ModMunitionsItems.M4_ASSEMBLY_TEMPLATE.get())) {
            return GunsmithGunFactory.M4A1_ID;
        }
        return blueprint(stack).gunId();
    }

    public static boolean matchesPart(ItemStack stack, GunsmithPressPart part) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(part, "part");
        if (stack.isEmpty() || !stack.is(ModMunitionsItems.GUNSMITH_PART.get())) {
            return false;
        }
        GunsmithPartItem.PartData data = GunsmithPartItem.requirePartData(stack);
        return data.part() == part;
    }

    public static boolean matchesPart(ItemStack stack, GunsmithPressPart part, GunsmithPlatform platform) {
        Objects.requireNonNull(platform, "platform");
        if (!matchesPart(stack, part)) {
            return false;
        }
        return GunsmithPartItem.requirePartData(stack).platform() == platform;
    }

    public static ItemStack assemble(ItemStack baseGun, ItemStack blueprintStack,
                                     Map<GunsmithPressPart, ItemStack> parts) {
        Objects.requireNonNull(baseGun, "baseGun");
        Objects.requireNonNull(blueprintStack, "blueprintStack");
        if (baseGun.isEmpty()) {
            throw new IllegalArgumentException("Assembly base gun is empty");
        }
        GunsmithBlueprint blueprint = blueprint(blueprintStack);
        EnumMap<GunsmithPressPart, ResolvedPart> resolvedParts = resolvedParts(parts, blueprint, true);
        ItemStack result = baseGun.copy();
        CompoundTag root = new CompoundTag();
        root.putInt(GunsmithGunStats.VERSION_KEY, GunsmithGunStats.CURRENT_VERSION);
        root.putString("template", blueprint.templateId());
        root.putString("platform", blueprint.platform().id());
        root.putString("gunId", assembledGunId(blueprintStack).toString());
        root.put(GunsmithGunStats.PARTS_KEY, partTags(resolvedParts, blueprint.requiredParts()));
        root.put(GunsmithGunStats.STATS_KEY, stats(blueprint, resolvedParts));
        result.getOrCreateTag().put(GunsmithGunStats.ROOT_KEY, root);
        return result;
    }

    public static Preview preview(GunsmithBlueprint blueprint, Map<GunsmithPressPart, ItemStack> parts,
                                  GunsmithBaseStats baseStats) {
        Objects.requireNonNull(blueprint, "blueprint");
        Objects.requireNonNull(baseStats, "baseStats");
        EnumMap<GunsmithPressPart, ResolvedPart> resolvedParts =
                resolvedParts(parts, blueprint, false);
        double range = coefficient(blueprint, resolvedParts, GunsmithStat.RANGE);
        double recoil = coefficient(blueprint, resolvedParts, GunsmithStat.RECOIL);
        double spread = coefficient(blueprint, resolvedParts, GunsmithStat.SPREAD);
        double handling = coefficient(blueprint, resolvedParts, GunsmithStat.HANDLING);
        double fireRateMultiplier = fireRateMultiplier(resolvedParts, blueprint.requiredParts());
        double verticalRecoilMultiplier = GunsmithGunStats.combineVerticalRecoil(
                recoil, verticalRecoilMultiplier(resolvedParts, blueprint.requiredParts()));
        double average = average(resolvedParts, blueprint.requiredParts());
        return new Preview(
                baseStats.damage() * coefficient(blueprint, resolvedParts, GunsmithStat.DAMAGE),
                baseStats.headshot() * coefficient(blueprint, resolvedParts, GunsmithStat.HEADSHOT),
                range,
                baseStats.effectiveRange() * range,
                (fireRateMultiplier - 1.0D) * 100.0D,
                (verticalRecoilMultiplier - 1.0D) * 100.0D,
                (1.0D / spread - 1.0D) * 100.0D,
                GunsmithGunStats.effectiveAdsTime(baseStats.adsTime(), handling),
                average);
    }

    private static EnumMap<GunsmithPressPart, ResolvedPart> resolvedParts(Map<GunsmithPressPart, ItemStack> parts,
                                                                           GunsmithBlueprint blueprint,
                                                                           boolean requireComplete) {
        Objects.requireNonNull(parts, "parts");
        Objects.requireNonNull(blueprint, "blueprint");
        Set<GunsmithPressPart> requiredParts = blueprint.requiredParts();
        EnumMap<GunsmithPressPart, ResolvedPart> resolved = new EnumMap<>(GunsmithPressPart.class);
        for (GunsmithPressPart part : requiredParts) {
            if (!parts.containsKey(part)) {
                throw new IllegalArgumentException("Assembly parts is missing " + part.id());
            }
            ItemStack stack = Objects.requireNonNull(parts.get(part), "Assembly part stack for " + part.id());
            if (stack.isEmpty()) {
                if (requireComplete) {
                    throw new IllegalArgumentException("Assembly part stack is empty for " + part.id());
                }
                resolved.put(part, new ResolvedPart(GunsmithPartVariant.BASIC,
                        GunsmithPartQuality.COMMON, 1.0D));
                continue;
            }
            GunsmithPartItem.PartData data = GunsmithPartItem.requirePartData(stack);
            if (data.platform() != blueprint.platform()) {
                throw new IllegalArgumentException("Assembly part platform must be " + blueprint.platform().id());
            }
            if (data.part() != part) {
                throw new IllegalArgumentException("Assembly slot " + part.id()
                        + " received " + data.part().id());
            }
            resolved.put(part, new ResolvedPart(data.variant(), data.quality(), data.coefficient()));
        }
        return resolved;
    }

    private static CompoundTag partTags(EnumMap<GunsmithPressPart, ResolvedPart> parts,
                                        Set<GunsmithPressPart> requiredParts) {
        CompoundTag result = new CompoundTag();
        for (GunsmithPressPart part : GunsmithPressPart.values()) {
            if (!requiredParts.contains(part)) {
                continue;
            }
            ResolvedPart data = Objects.requireNonNull(parts.get(part), "resolved part " + part.id());
            CompoundTag partTag = new CompoundTag();
            partTag.putString("variant", data.variant().id());
            partTag.putString("quality", data.quality().id());
            partTag.putDouble("coefficient", data.coefficient());
            result.put(part.id(), partTag);
        }
        return result;
    }

    private static CompoundTag stats(GunsmithBlueprint blueprint,
                                     EnumMap<GunsmithPressPart, ResolvedPart> resolvedParts) {
        CompoundTag stats = new CompoundTag();
        double recoil = coefficient(blueprint, resolvedParts, GunsmithStat.RECOIL);
        stats.putDouble("damage", coefficient(blueprint, resolvedParts, GunsmithStat.DAMAGE));
        stats.putDouble("headshot", coefficient(blueprint, resolvedParts, GunsmithStat.HEADSHOT));
        stats.putDouble("range", coefficient(blueprint, resolvedParts, GunsmithStat.RANGE));
        stats.putDouble("recoil", recoil);
        stats.putDouble("spread", coefficient(blueprint, resolvedParts, GunsmithStat.SPREAD));
        stats.putDouble("handling", coefficient(blueprint, resolvedParts, GunsmithStat.HANDLING));
        stats.putDouble("average", average(resolvedParts, blueprint.requiredParts()));
        stats.putDouble("fireRate", fireRateMultiplier(resolvedParts, blueprint.requiredParts()));
        stats.putDouble("verticalRecoil", GunsmithGunStats.combineVerticalRecoil(
                recoil, verticalRecoilMultiplier(resolvedParts, blueprint.requiredParts())));
        return stats;
    }

    private static double coefficient(GunsmithBlueprint blueprint,
                                      EnumMap<GunsmithPressPart, ResolvedPart> resolvedParts,
                                      GunsmithStat stat) {
        return stat.coefficient(blueprint.platform(), part -> {
            ResolvedPart resolved = Objects.requireNonNull(resolvedParts.get(part),
                    "resolved part " + part.id());
            return resolved.variant().coefficientForStat(stat, resolved.coefficient());
        });
    }

    private static double fireRateMultiplier(EnumMap<GunsmithPressPart, ResolvedPart> resolvedParts,
                                             Set<GunsmithPressPart> requiredParts) {
        double multiplier = 1.0D;
        for (GunsmithPressPart part : requiredParts) {
            ResolvedPart resolved = Objects.requireNonNull(resolvedParts.get(part),
                    "resolved part " + part.id());
            multiplier *= resolved.variant().fireRateMultiplier(resolved.coefficient());
        }
        return multiplier;
    }

    private static double verticalRecoilMultiplier(EnumMap<GunsmithPressPart, ResolvedPart> resolvedParts,
                                                    Set<GunsmithPressPart> requiredParts) {
        double multiplier = 1.0D;
        for (GunsmithPressPart part : requiredParts) {
            ResolvedPart resolved = Objects.requireNonNull(resolvedParts.get(part),
                    "resolved part " + part.id());
            multiplier *= resolved.variant().verticalRecoilMultiplier(resolved.coefficient());
        }
        return multiplier;
    }

    private static double average(EnumMap<GunsmithPressPart, ResolvedPart> resolvedParts,
                                  Set<GunsmithPressPart> requiredParts) {
        double total = 0.0D;
        for (GunsmithPressPart part : requiredParts) {
            total += Objects.requireNonNull(resolvedParts.get(part), "resolved part " + part.id()).coefficient();
        }
        return total / requiredParts.size();
    }

    public record Preview(double damage, double headshot, double range, double effectiveRange,
                          double fireRateChange, double recoilChange, double spreadChange,
                          double adsTime, double average) {

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

    private record ResolvedPart(GunsmithPartVariant variant, GunsmithPartQuality quality, double coefficient) {
    }
}

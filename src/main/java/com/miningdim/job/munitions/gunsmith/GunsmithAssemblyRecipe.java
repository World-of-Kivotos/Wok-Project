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
        return GunsmithBlueprintItem.tryBlueprint(stack) != null;
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

    public static ResourceLocation assembledGunId(ItemStack stack,
                                                  Map<GunsmithPressPart, ItemStack> parts) {
        GunsmithBlueprint blueprint = blueprint(stack);
        if (blueprint.platform() != GunsmithPlatform.AR) {
            return assembledGunId(stack);
        }
        Objects.requireNonNull(parts, "parts");
        ItemStack bolt = Objects.requireNonNull(parts.get(GunsmithPressPart.BOLT),
                "Assembly parts is missing bolt");
        if (!bolt.isEmpty()) {
            GunsmithPartItem.PartData boltData = GunsmithPartItem.requirePartData(bolt);
            if (boltData.platform() != blueprint.platform()
                    || boltData.part() != GunsmithPressPart.BOLT) {
                throw new IllegalArgumentException("Assembly bolt does not match blueprint platform");
            }
            if (boltData.variant().forcesBurstFireMode()) {
                return GunsmithGunFactory.burstGunId(blueprint);
            }
        }
        return assembledGunId(stack);
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
        EnumMap<GunsmithPressPart, Double> coefficients = coefficients(parts, blueprint, true);
        ItemStack result = baseGun.copy();
        CompoundTag root = new CompoundTag();
        root.putInt(GunsmithGunStats.VERSION_KEY, GunsmithGunStats.CURRENT_VERSION);
        root.putString("template", blueprint.templateId());
        root.putString("platform", blueprint.platform().id());
        root.putString("gunId", assembledGunId(blueprintStack, parts).toString());
        root.put(GunsmithGunStats.PARTS_KEY, partTags(parts, blueprint.requiredParts()));
        root.put(GunsmithGunStats.STATS_KEY, stats(blueprint, coefficients));
        result.getOrCreateTag().put(GunsmithGunStats.ROOT_KEY, root);
        GunsmithGunDurability.initializeNew(result);
        return result;
    }

    public static Preview preview(GunsmithBlueprint blueprint, Map<GunsmithPressPart, ItemStack> parts,
                                  GunsmithBaseStats baseStats) {
        Objects.requireNonNull(blueprint, "blueprint");
        Objects.requireNonNull(baseStats, "baseStats");
        EnumMap<GunsmithPressPart, Double> coefficients =
                coefficients(parts, blueprint, false);
        EnumMap<GunsmithPressPart, GunsmithPartVariant> variants = variants(parts, blueprint, false);
        double range = applyVariantRange(coefficient(blueprint, coefficients, GunsmithStat.RANGE),
                blueprint, parts, variants);
        double recoil = coefficient(blueprint, coefficients, GunsmithStat.RECOIL);
        double spread = coefficient(blueprint, coefficients, GunsmithStat.SPREAD);
        double handling = coefficient(blueprint, coefficients, GunsmithStat.HANDLING);
        double recoilMultiplier = variantProduct(blueprint, parts, variants, VariantStat.RECOIL);
        double spreadMultiplier = variantProduct(blueprint, parts, variants, VariantStat.SPREAD);
        double adsSpeedMultiplier = variantProduct(blueprint, parts, variants, VariantStat.ADS_SPEED);
        double fireRateMultiplier = variantProduct(blueprint, parts, variants, VariantStat.FIRE_RATE);
        double average = average(coefficients, blueprint.requiredParts());
        return new Preview(
                baseStats.damage() * coefficient(blueprint, coefficients, GunsmithStat.DAMAGE)
                        * variantProduct(blueprint, parts, variants, VariantStat.DAMAGE),
                baseStats.headshot() * coefficient(blueprint, coefficients, GunsmithStat.HEADSHOT)
                        * variantProduct(blueprint, parts, variants, VariantStat.HEADSHOT),
                range,
                baseStats.effectiveRange() * range,
                (1.0D / recoil * recoilMultiplier - 1.0D) * 100.0D,
                (1.0D / spread * spreadMultiplier - 1.0D) * 100.0D,
                (fireRateMultiplier - 1.0D) * 100.0D,
                GunsmithGunStats.effectiveAdsTime(baseStats.adsTime(), handling * adsSpeedMultiplier),
                average);
    }

    public static EnumMap<GunsmithPressPart, ItemStack> previewCompatibleParts(
            GunsmithBlueprint blueprint, Map<GunsmithPressPart, ItemStack> parts) {
        Objects.requireNonNull(blueprint, "blueprint");
        Objects.requireNonNull(parts, "parts");
        EnumMap<GunsmithPressPart, ItemStack> compatible = new EnumMap<>(GunsmithPressPart.class);
        for (GunsmithPressPart part : blueprint.requiredParts()) {
            ItemStack stack = parts.get(part);
            compatible.put(part, stack != null
                    && matchesPart(stack, part, blueprint.platform()) ? stack : ItemStack.EMPTY);
        }
        return compatible;
    }

    private static EnumMap<GunsmithPressPart, GunsmithPartVariant> variants(
            Map<GunsmithPressPart, ItemStack> parts, GunsmithBlueprint blueprint, boolean requireComplete) {
        EnumMap<GunsmithPressPart, GunsmithPartVariant> variants = new EnumMap<>(GunsmithPressPart.class);
        for (GunsmithPressPart part : blueprint.requiredParts()) {
            ItemStack stack = parts.get(part);
            if (stack == null || stack.isEmpty()) {
                if (requireComplete) {
                    throw new IllegalArgumentException("Assembly part stack is empty for " + part.id());
                }
                variants.put(part, GunsmithPartVariant.BASE);
                continue;
            }
            variants.put(part, GunsmithPartItem.requirePartData(stack).variant());
        }
        return variants;
    }

    private static GunsmithPartQuality quality(Map<GunsmithPressPart, ItemStack> parts,
                                                GunsmithPressPart part) {
        ItemStack stack = parts.get(part);
        return stack == null || stack.isEmpty() ? GunsmithPartQuality.COMMON
                : GunsmithPartItem.requirePartData(stack).quality();
    }

    private static EnumMap<GunsmithPressPart, Double> coefficients(Map<GunsmithPressPart, ItemStack> parts,
                                                                     GunsmithBlueprint blueprint,
                                                                     boolean requireComplete) {
        Objects.requireNonNull(parts, "parts");
        Objects.requireNonNull(blueprint, "blueprint");
        Set<GunsmithPressPart> requiredParts = blueprint.requiredParts();
        EnumMap<GunsmithPressPart, Double> coefficients = new EnumMap<>(GunsmithPressPart.class);
        for (GunsmithPressPart part : requiredParts) {
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
            if (data.platform() != blueprint.platform()) {
                throw new IllegalArgumentException("Assembly part platform must be " + blueprint.platform().id());
            }
            if (data.part() != part) {
                throw new IllegalArgumentException("Assembly slot " + part.id()
                        + " received " + data.part().id());
            }
            coefficients.put(part, data.coefficient());
        }
        return coefficients;
    }

    private static CompoundTag partTags(Map<GunsmithPressPart, ItemStack> parts,
                                        Set<GunsmithPressPart> requiredParts) {
        CompoundTag result = new CompoundTag();
        for (GunsmithPressPart part : GunsmithPressPart.values()) {
            if (!requiredParts.contains(part)) {
                continue;
            }
            GunsmithPartItem.PartData data = GunsmithPartItem.requirePartData(parts.get(part));
            CompoundTag partTag = new CompoundTag();
            partTag.putString("quality", data.quality().id());
            partTag.putString("variant", data.variant().id());
            partTag.putDouble("coefficient", data.coefficient());
            result.put(part.id(), partTag);
        }
        return result;
    }

    private static CompoundTag stats(GunsmithBlueprint blueprint,
                                     EnumMap<GunsmithPressPart, Double> coefficients) {
        CompoundTag stats = new CompoundTag();
        // NBT 只缓存由品质浮动系数推导的基础值。组件规则运行时读取，热更新不会使存量枪失效。
        stats.putDouble("damage", coefficient(blueprint, coefficients, GunsmithStat.DAMAGE));
        stats.putDouble("headshot", coefficient(blueprint, coefficients, GunsmithStat.HEADSHOT));
        stats.putDouble("range", coefficient(blueprint, coefficients, GunsmithStat.RANGE));
        stats.putDouble("recoil", coefficient(blueprint, coefficients, GunsmithStat.RECOIL));
        stats.putDouble("spread", coefficient(blueprint, coefficients, GunsmithStat.SPREAD));
        stats.putDouble("handling", coefficient(blueprint, coefficients, GunsmithStat.HANDLING));
        stats.putDouble("average", average(coefficients, blueprint.requiredParts()));
        return stats;
    }

    private static double applyVariantRange(double base, GunsmithBlueprint blueprint,
                                            Map<GunsmithPressPart, ItemStack> parts,
                                            EnumMap<GunsmithPressPart, GunsmithPartVariant> variants) {
        double result = base;
        for (GunsmithPressPart part : blueprint.requiredParts()) {
            result = variants.get(part).applyRangeMultiplier(result, quality(parts, part));
        }
        return result;
    }

    private static double variantProduct(GunsmithBlueprint blueprint,
                                         Map<GunsmithPressPart, ItemStack> parts,
                                         EnumMap<GunsmithPressPart, GunsmithPartVariant> variants,
                                         VariantStat stat) {
        double result = 1.0D;
        for (GunsmithPressPart part : blueprint.requiredParts()) {
            GunsmithPartVariant variant = variants.get(part);
            GunsmithPartQuality quality = quality(parts, part);
            result *= switch (stat) {
                case DAMAGE -> variant.damageMultiplier(quality);
                case HEADSHOT -> variant.headshotMultiplier(quality);
                case SPREAD -> variant.spreadMultiplier(quality);
                case RECOIL -> variant.recoilMultiplier(quality);
                case ADS_SPEED -> variant.adsSpeedMultiplier(quality);
                case FIRE_RATE -> variant.fireRateMultiplier(quality);
            };
        }
        return result;
    }

    private static double coefficient(GunsmithBlueprint blueprint, EnumMap<GunsmithPressPart, Double> coefficients,
                                      GunsmithStat stat) {
        return stat.coefficient(blueprint.platform(), coefficients::get);
    }

    private static double average(EnumMap<GunsmithPressPart, Double> coefficients,
                                  Set<GunsmithPressPart> requiredParts) {
        double total = 0.0D;
        for (GunsmithPressPart part : requiredParts) {
            total += Objects.requireNonNull(coefficients.get(part), "coefficient for " + part.id());
        }
        return total / requiredParts.size();
    }

    private enum VariantStat {
        DAMAGE,
        HEADSHOT,
        SPREAD,
        RECOIL,
        ADS_SPEED,
        FIRE_RATE
    }

    public record Preview(double damage, double headshot, double range, double effectiveRange, double recoilChange,
                          double spreadChange, double fireRateChange, double adsTime, double average) {

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

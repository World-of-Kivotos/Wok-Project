package com.miningdim.job.munitions.gunsmith;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class GunsmithGunStats {

    public static final String ROOT_KEY = "MiningDimGunsmith";
    public static final String PARTS_KEY = "Parts";
    public static final String STATS_KEY = "Stats";
    public static final String VERSION_KEY = "version";
    public static final int CURRENT_VERSION = 2;

    private final CompoundTag root;
    private final CompoundTag stats;
    private final int version;
    private final List<PartSummary> parts;

    private GunsmithGunStats(CompoundTag root, CompoundTag stats) {
        this.root = root;
        this.stats = stats;
        this.version = version(root);
        String platform = requireString(root, "platform");
        GunsmithBlueprint blueprint = requireBlueprint(requireString(root, "template"));
        if (!blueprint.platform().id().equals(platform)) {
            throw new IllegalArgumentException("Gunsmith platform does not match template: " + platform);
        }
        this.parts = readParts(root, blueprint.requiredParts());
        ResourceLocation encodedGunId = gunId();
        if (!matchesBlueprintGunId(blueprint, encodedGunId)) {
            throw new IllegalArgumentException("Gunsmith gun id does not match template: " + encodedGunId);
        }
        if (version == CURRENT_VERSION) {
            validateCurrentStats();
        } else {
            value("damage");
            value("headshot");
            value("spread");
            value("handling");
            value("average");
            range();
            recoil();
        }
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
        return version == 1 ? value("damage") : coefficient(GunsmithPressPart.BOLT);
    }

    public double headshot() {
        return version == 1 ? value("headshot") : coefficient(GunsmithPressPart.BARREL);
    }

    public double range() {
        return version == 1
                ? requiredPart(GunsmithPressPart.CORE).coefficient()
                : coefficient(GunsmithPressPart.CORE);
    }

    public double recoil() {
        return version == 1
                ? requiredPart(GunsmithPressPart.STOCK).coefficient()
                : coefficient(GunsmithPressPart.STOCK);
    }

    public double spread() {
        return version == 1 ? value("spread") : coefficient(GunsmithPressPart.HANDGUARD);
    }

    public double handling() {
        return version == 1 ? value("handling") : coefficient(GunsmithPressPart.GRIP);
    }

    public double average() {
        return version == 1 ? value("average") : averageCoefficient();
    }

    public List<PartSummary> parts() {
        return parts;
    }

    public double effectiveDamage(GunsmithBaseStats baseStats) {
        return Objects.requireNonNull(baseStats, "baseStats").damage() * damage();
    }

    public double effectiveHeadshot(GunsmithBaseStats baseStats) {
        return Objects.requireNonNull(baseStats, "baseStats").headshot() * headshot();
    }

    public double effectiveRange(GunsmithBaseStats baseStats) {
        return Objects.requireNonNull(baseStats, "baseStats").effectiveRange() * range();
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

    private static int version(CompoundTag root) {
        if (!root.contains(VERSION_KEY)) {
            // v1 assembled guns predate the version field; their saved Parts data is the migration source.
            return 1;
        }
        if (!root.contains(VERSION_KEY, Tag.TAG_INT)) {
            throw new IllegalArgumentException("Gunsmith root data has no integer version");
        }
        int version = root.getInt(VERSION_KEY);
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported gunsmith data version: " + version);
        }
        return version;
    }

    private static List<PartSummary> readParts(CompoundTag root, Set<GunsmithPressPart> requiredParts) {
        if (!root.contains(PARTS_KEY, Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Gunsmith root data has no parts compound");
        }
        CompoundTag encodedParts = root.getCompound(PARTS_KEY);
        for (String key : encodedParts.getAllKeys()) {
            if (!isKnownPartId(key)) {
                throw new IllegalArgumentException("Gunsmith parts contains an unknown part: " + key);
            }
        }
        List<PartSummary> parts = new ArrayList<>();
        for (GunsmithPressPart part : GunsmithPressPart.values()) {
            boolean encoded = encodedParts.contains(part.id());
            if (requiredParts.contains(part) != encoded) {
                throw new IllegalArgumentException(encoded
                        ? "Gunsmith parts contains a part not required by the template: " + part.id()
                        : "Gunsmith parts is missing a required part: " + part.id());
            }
            if (!encoded) {
                continue;
            }
            if (!encodedParts.contains(part.id(), Tag.TAG_COMPOUND)) {
                throw new IllegalArgumentException("Gunsmith part data is not a compound: " + part.id());
            }
            CompoundTag encodedPart = encodedParts.getCompound(part.id());
            String qualityId = requireString(encodedPart, "quality");
            GunsmithPartQuality quality = requireQuality(qualityId);
            if (!encodedPart.contains("coefficient", Tag.TAG_DOUBLE)) {
                throw new IllegalArgumentException("Gunsmith part has no double coefficient: " + part.id());
            }
            double coefficient = encodedPart.getDouble("coefficient");
            if (!Double.isFinite(coefficient)
                    || coefficient < quality.minCoefficient()
                    || coefficient > quality.maxCoefficient()) {
                throw new IllegalArgumentException("Gunsmith part coefficient is outside the quality range: " + part.id());
            }
            parts.add(new PartSummary(part, quality, coefficient));
        }
        return List.copyOf(parts);
    }

    private static boolean isKnownPartId(String id) {
        for (GunsmithPressPart part : GunsmithPressPart.values()) {
            if (part.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static GunsmithBlueprint requireBlueprint(String templateId) {
        for (GunsmithBlueprint blueprint : GunsmithBlueprint.values()) {
            if (blueprint.templateId().equals(templateId)) {
                return blueprint;
            }
        }
        throw new IllegalArgumentException("Unknown gunsmith template: " + templateId);
    }

    private static boolean matchesBlueprintGunId(GunsmithBlueprint blueprint, ResourceLocation gunId) {
        return blueprint.gunId().equals(gunId)
                || blueprint == GunsmithBlueprint.M4A1 && GunsmithGunFactory.M4A1_ID.equals(gunId);
    }

    private static GunsmithPartQuality requireQuality(String qualityId) {
        for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
            if (quality.id().equals(qualityId)) {
                return quality;
            }
        }
        throw new IllegalArgumentException("Unknown gunsmith part quality: " + qualityId);
    }

    private PartSummary requiredPart(GunsmithPressPart part) {
        for (PartSummary summary : parts) {
            if (summary.part() == part) {
                return summary;
            }
        }
        throw new IllegalArgumentException("Gunsmith v1 data has no " + part.id() + " part summary");
    }

    private void validateCurrentStats() {
        validateCurrentStat("damage", damage());
        validateCurrentStat("headshot", headshot());
        validateCurrentStat("range", range());
        validateCurrentStat("recoil", recoil());
        validateCurrentStat("spread", spread());
        validateCurrentStat("handling", handling());
        validateCurrentStat("average", average());
    }

    private void validateCurrentStat(String key, double expected) {
        double encoded = value(key);
        if (Double.compare(encoded, expected) != 0) {
            throw new IllegalArgumentException("Gunsmith stat does not match installed parts: " + key);
        }
    }

    private double coefficient(GunsmithPressPart part) {
        for (PartSummary summary : parts) {
            if (summary.part() == part) {
                return summary.coefficient();
            }
        }
        return 1.0D;
    }

    private double averageCoefficient() {
        double total = 0.0D;
        for (GunsmithPressPart part : GunsmithPressPart.values()) {
            total += coefficient(part);
        }
        return total / GunsmithPressPart.values().length;
    }

    public record PartSummary(GunsmithPressPart part, GunsmithPartQuality quality, double coefficient) {
    }
}

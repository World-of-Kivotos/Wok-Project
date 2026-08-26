package com.miningdim.job.munitions.gunsmith;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class GunsmithGunStats {

    public static final String ROOT_KEY = "MiningDimGunsmith";
    public static final String PARTS_KEY = "Parts";
    public static final String STATS_KEY = "Stats";
    public static final String VERSION_KEY = "version";
    public static final int CURRENT_VERSION = 7;

    private static final Set<GunsmithPressPart> LEGACY_MARKSMAN_FIVE_PARTS = Set.of(
            GunsmithPressPart.HANDGUARD,
            GunsmithPressPart.CORE,
            GunsmithPressPart.STOCK,
            GunsmithPressPart.BOLT,
            GunsmithPressPart.BARREL);
    private static final Set<GunsmithPressPart> LEGACY_SNIPER_FOUR_PARTS = Set.of(
            GunsmithPressPart.RECEIVER,
            GunsmithPressPart.STOCK,
            GunsmithPressPart.BARREL,
            GunsmithPressPart.HANDGUARD);

    private final CompoundTag root;
    private final CompoundTag stats;
    private final int version;
    private final GunsmithBlueprint blueprint;
    private final List<PartSummary> parts;

    private GunsmithGunStats(CompoundTag root, CompoundTag stats) {
        this.root = root;
        this.stats = stats;
        this.version = version(root);
        String platform = requireString(root, "platform");
        this.blueprint = requireBlueprint(requireString(root, "template"));
        boolean migratedSpr15hbPlatform = isLegacySpr15hbArData(root, blueprint);
        if (!blueprint.platform().id().equals(platform) && !migratedSpr15hbPlatform) {
            throw new IllegalArgumentException("Gunsmith platform does not match template: " + platform);
        }
        boolean migratedArReceiver = isLegacyArReceiverData(root, blueprint, version);
        boolean migratedMarksmanGrip = isLegacyFivePartMarksmanData(root, blueprint, version);
        boolean migratedSniperFiringPin = isLegacyFourPartSniperData(root, blueprint, version);
        if (migratedSpr15hbPlatform) {
            this.parts = readParts(root, GunsmithPlatform.AR,
                    GunsmithPlatform.AR.supportedParts(), migratedArReceiver);
        } else if (migratedMarksmanGrip) {
            List<PartSummary> migrated = new ArrayList<>(readParts(root, GunsmithPlatform.MARKSMAN,
                    LEGACY_MARKSMAN_FIVE_PARTS, false));
            migrated.add(new PartSummary(GunsmithPressPart.GRIP, GunsmithPartQuality.COMMON,
                    GunsmithPartVariant.BASE, 1.0D));
            this.parts = List.copyOf(migrated);
        } else if (migratedSniperFiringPin) {
            List<PartSummary> migrated = new ArrayList<>(readParts(root, GunsmithPlatform.SNIPER,
                    LEGACY_SNIPER_FOUR_PARTS, false));
            migrated.add(new PartSummary(GunsmithPressPart.FIRING_PIN, GunsmithPartQuality.COMMON,
                    GunsmithPartVariant.BASE, 1.0D));
            this.parts = List.copyOf(migrated);
        } else {
            this.parts = readParts(root, blueprint.platform(), blueprint.requiredParts(), migratedArReceiver);
        }
        ResourceLocation encodedGunId = gunId();
        if (!matchesBlueprintGunId(blueprint, encodedGunId, parts)) {
            throw new IllegalArgumentException("Gunsmith gun id does not match template: " + encodedGunId);
        }
        if (migratedSpr15hbPlatform || migratedMarksmanGrip || migratedSniperFiringPin) {
            validateMigratedPlatformStats();
        } else if (version == CURRENT_VERSION || version == 3 || version == 5 || version == 6
                || version == 4 && !migratedArReceiver) {
            validateCurrentStats();
        } else if (version == 4) {
            validateMigratedArReceiverStats();
        } else if (version == 2) {
            validateVersion2Stats();
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

    /**
     * 只读展示与第三方属性事件使用的容错入口；装配和写入路径仍使用 {@link #from(ItemStack)} 硬校验。
     */
    @Nullable
    public static GunsmithGunStats tryFrom(ItemStack stack) {
        try {
            return from(stack);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    public static boolean hasGunsmithData(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(ROOT_KEY);
    }

    public String platform() {
        return blueprint.platform().id();
    }

    public String template() {
        return root.getString("template");
    }

    public GunsmithBlueprint blueprint() {
        return blueprint;
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
        return version == 1 ? value("damage") : baseDamage() * variantProduct(VariantStat.DAMAGE);
    }

    double baseDamage() {
        return version == 1 ? value("damage") : coefficient(GunsmithStat.DAMAGE);
    }

    public double headshot() {
        return version == 1 ? value("headshot") : baseHeadshot() * specialHeadshot();
    }

    double baseHeadshot() {
        return version == 1 ? value("headshot") : coefficient(GunsmithStat.HEADSHOT);
    }

    public double specialHeadshot() {
        return version == 1 ? 1.0D : variantProduct(VariantStat.HEADSHOT);
    }

    public double range() {
        if (version == 1) {
            return requiredPart(GunsmithPressPart.CORE).coefficient();
        }
        double result = coefficient(GunsmithStat.RANGE);
        for (PartSummary part : parts) {
            result = part.variant().applyRangeMultiplier(result, part.quality());
        }
        return result;
    }

    public double recoil() {
        return version == 1
                ? requiredPart(GunsmithPressPart.STOCK).coefficient()
                : coefficient(GunsmithStat.RECOIL);
    }

    public double spread() {
        return version == 1 ? value("spread") : coefficient(GunsmithStat.SPREAD);
    }

    public double handling() {
        return version == 1 ? value("handling") : coefficient(GunsmithStat.HANDLING);
    }

    public double average() {
        return version == 1 ? value("average") : averageCoefficient();
    }

    public double fireRate() {
        return version == 1 ? 1.0D : variantProduct(VariantStat.FIRE_RATE);
    }

    /** 兼容主线既有详情面板与测试使用的旧访问器名称。 */
    public double fireRateMultiplier() {
        return fireRate();
    }

    public double ammoSpeed() {
        return version == 1 ? 1.0D : variantProduct(VariantStat.AMMO_SPEED);
    }

    public double armorIgnore() {
        return version == 1 ? 1.0D : variantProduct(VariantStat.ARMOR_IGNORE);
    }

    public double specialSpread() {
        return version == 1 ? 1.0D : variantProduct(VariantStat.SPREAD);
    }

    public double specialRecoil() {
        return version == 1 ? 1.0D : variantProduct(VariantStat.RECOIL);
    }

    public double verticalRecoil() {
        return version == 1 ? 1.0D : variantProduct(VariantStat.VERTICAL_RECOIL);
    }

    public double specialAdsSpeed() {
        return version == 1 ? 1.0D : variantProduct(VariantStat.ADS_SPEED);
    }

    public double adsSpeed() {
        return handling() * specialAdsSpeed();
    }

    public List<PartSummary> parts() {
        return parts;
    }

    public boolean forcesBurstFireMode() {
        return parts.stream().anyMatch(part -> part.variant().forcesBurstFireMode());
    }

    public double maximumDurabilityMultiplier() {
        double multiplier = 1.0D;
        for (PartSummary part : parts) {
            multiplier *= part.variant().maximumDurabilityMultiplier();
        }
        return multiplier;
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
        return effectiveAdsTime(Objects.requireNonNull(baseStats, "baseStats").adsTime(), adsSpeed());
    }

    public double recoilChange() {
        return inverse(recoil()) * specialRecoil() - 1.0D;
    }

    public double spreadChange() {
        return inverse(spread()) * specialSpread() - 1.0D;
    }

    public double verticalRecoilMultiplier() {
        return inverse(recoil()) * specialRecoil() * verticalRecoil();
    }

    public double horizontalRecoilMultiplier() {
        return inverse(recoil()) * specialRecoil();
    }

    public double inaccuracyMultiplier() {
        return inverse(spread()) * specialSpread();
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
        if (version != 2 && version != 3 && version != 4 && version != 5 && version != 6
                && version != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported gunsmith data version: " + version);
        }
        return version;
    }

    private static List<PartSummary> readParts(CompoundTag root, GunsmithPlatform platform,
                                               Set<GunsmithPressPart> requiredParts,
                                               boolean migrateArReceiver) {
        if (!root.contains(PARTS_KEY, Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Gunsmith root data has no parts compound");
        }
        CompoundTag encodedParts = root.getCompound(PARTS_KEY);
        if (migrateArReceiver) {
            encodedParts = migrateLegacyArReceiverParts(encodedParts);
        }
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
            GunsmithPartVariant variant = encodedPart.contains("variant", Tag.TAG_STRING)
                    ? GunsmithPartVariant.byId(encodedPart.getString("variant"))
                    : GunsmithPartVariant.BASE;
            if (!variant.supports(platform, part)) {
                throw new IllegalArgumentException("Gunsmith part variant does not support encoded part: " + part.id());
            }
            parts.add(new PartSummary(part, quality, variant, coefficient));
        }
        return List.copyOf(parts);
    }

    private static boolean isLegacyArReceiverData(CompoundTag root, GunsmithBlueprint blueprint, int version) {
        return version == 4
                && (blueprint.platform() == GunsmithPlatform.AR || isLegacySpr15hbArData(root, blueprint))
                && root.contains(PARTS_KEY, Tag.TAG_COMPOUND)
                && root.getCompound(PARTS_KEY).contains(GunsmithPressPart.RECEIVER.id());
    }

    private static boolean isLegacySpr15hbArData(CompoundTag root, GunsmithBlueprint blueprint) {
        return blueprint == GunsmithBlueprint.SPR15HB
                && GunsmithPlatform.AR.id().equals(root.getString("platform"));
    }

    private static boolean isLegacyFivePartMarksmanData(CompoundTag root, GunsmithBlueprint blueprint,
                                                         int version) {
        return version == 5
                && blueprint == GunsmithBlueprint.SPR15HB
                && GunsmithPlatform.MARKSMAN.id().equals(root.getString("platform"))
                && root.contains(PARTS_KEY, Tag.TAG_COMPOUND)
                && !root.getCompound(PARTS_KEY).contains(GunsmithPressPart.GRIP.id());
    }

    private static boolean isLegacyFourPartSniperData(CompoundTag root, GunsmithBlueprint blueprint,
                                                       int version) {
        return version == 6
                && blueprint.platform() == GunsmithPlatform.SNIPER
                && root.contains(PARTS_KEY, Tag.TAG_COMPOUND)
                && !root.getCompound(PARTS_KEY).contains(GunsmithPressPart.FIRING_PIN.id());
    }

    private static CompoundTag migrateLegacyArReceiverParts(CompoundTag encodedParts) {
        String receiverId = GunsmithPressPart.RECEIVER.id();
        if (!encodedParts.contains(receiverId, Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Legacy AR receiver data is not a compound");
        }
        CompoundTag migrated = encodedParts.copy();
        CompoundTag receiver = migrated.getCompound(receiverId);
        GunsmithPartVariant variant = receiver.contains("variant", Tag.TAG_STRING)
                ? GunsmithPartVariant.byId(receiver.getString("variant")) : GunsmithPartVariant.BASE;
        if (variant == GunsmithPartVariant.MK_AX_A_BOLT) {
            CompoundTag bolt = receiver.copy();
            bolt.putString("variant", variant.id());
            migrated.put(GunsmithPressPart.BOLT.id(), bolt);
        } else if (variant != GunsmithPartVariant.BASE) {
            throw new IllegalArgumentException("Legacy AR receiver contains an unsupported variant");
        }
        migrated.remove(receiverId);
        return migrated;
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

    private static boolean matchesBlueprintGunId(GunsmithBlueprint blueprint, ResourceLocation gunId,
                                                 List<PartSummary> parts) {
        if (parts.stream().anyMatch(part -> part.variant().forcesBurstFireMode())) {
            if (blueprint == GunsmithBlueprint.SPR15HB) {
                return new ResourceLocation("miningdim", "spr15hb_gunsmith_burst").equals(gunId);
            }
            return GunsmithGunFactory.burstGunId(blueprint).equals(gunId);
        }
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
        // v3 起缓存值只验证部件品质的基础系数，组件平衡值由当前热重载规则实时计算。
        validateCurrentStat("damage", coefficient(GunsmithStat.DAMAGE));
        validateCurrentStat("headshot", baseHeadshot());
        validateCurrentStat("range", coefficient(GunsmithStat.RANGE));
        validateCurrentStat("recoil", recoil());
        validateCurrentStat("spread", spread());
        validateCurrentStat("handling", handling());
        validateCurrentStat("average", average());
    }

    private void validateMigratedArReceiverStats() {
        // v4 曾错误地把 AR 机匣作为第七槽写入。结构已迁移到六件套；旧缓存只做类型和值域校验，
        // 实际属性始终根据迁移后的枪机组件和当前热重载规则计算。
        value("damage");
        value("headshot");
        value("range");
        value("recoil");
        value("spread");
        value("handling");
        value("average");
    }

    private void validateMigratedPlatformStats() {
        // 平台扩槽迁移只对旧缓存做类型和值域校验。SPR15HB 的五槽 v5 数据补中性握把；
        // 栓动式步枪的四槽 v6 数据补中性撞针，从而保留旧操控和其余真实组件属性。
        value("damage");
        value("headshot");
        if (version == 1) {
            range();
            recoil();
        } else {
            value("range");
            value("recoil");
        }
        value("spread");
        value("handling");
        value("average");
    }

    private void validateVersion2Stats() {
        validateCurrentStat("damage", legacyVersion2Damage());
        validateCurrentStat("headshot", baseHeadshot());
        validateCurrentStat("range", coreVariant() == GunsmithPartVariant.RED_EAST_HIGH_PRESSURE_GAS
                ? 1.0D : coefficient(GunsmithStat.RANGE));
        validateCurrentStat("recoil", recoil());
        validateCurrentStat("spread", spread());
        validateCurrentStat("handling", handling());
        validateCurrentStat("average", average());
    }

    private double legacyVersion2Damage() {
        double multiplier = coreVariant() == GunsmithPartVariant.RED_EAST_HIGH_PRESSURE_GAS
                ? 1.20D + coreQuality().index() * 0.20D : 1.0D;
        return coefficient(GunsmithStat.DAMAGE) * multiplier;
    }

    private void validateCurrentStat(String key, double expected) {
        double encoded = value(key);
        if (Double.compare(encoded, expected) != 0) {
            throw new IllegalArgumentException("Gunsmith stat does not match installed parts: " + key);
        }
    }

    private double coefficient(GunsmithStat stat) {
        return stat.coefficient(blueprint.platform(), part -> requiredPart(part).coefficient());
    }

    private GunsmithPartVariant coreVariant() {
        for (PartSummary part : parts) {
            if (part.part() == GunsmithPressPart.CORE) {
                return part.variant();
            }
        }
        return GunsmithPartVariant.BASE;
    }

    private GunsmithPartQuality coreQuality() {
        for (PartSummary part : parts) {
            if (part.part() == GunsmithPressPart.CORE) {
                return part.quality();
            }
        }
        return GunsmithPartQuality.COMMON;
    }

    private double variantProduct(VariantStat stat) {
        double result = 1.0D;
        for (PartSummary part : parts) {
            result *= stat.multiplier(part.variant(), part.quality());
        }
        return result;
    }

    private enum VariantStat {
        DAMAGE,
        HEADSHOT,
        FIRE_RATE,
        AMMO_SPEED,
        ARMOR_IGNORE,
        SPREAD,
        RECOIL,
        VERTICAL_RECOIL,
        ADS_SPEED;

        private double multiplier(GunsmithPartVariant variant, GunsmithPartQuality quality) {
            return switch (this) {
                case DAMAGE -> variant.damageMultiplier(quality);
                case HEADSHOT -> variant.headshotMultiplier(quality);
                case FIRE_RATE -> variant.fireRateMultiplier(quality);
                case AMMO_SPEED -> variant.ammoSpeedMultiplier(quality);
                case ARMOR_IGNORE -> variant.armorIgnoreMultiplier(quality);
                case SPREAD -> variant.spreadMultiplier(quality);
                case RECOIL -> variant.recoilMultiplier(quality);
                case VERTICAL_RECOIL -> variant.verticalRecoilMultiplier(quality);
                case ADS_SPEED -> variant.adsSpeedMultiplier(quality);
            };
        }
    }

    private double averageCoefficient() {
        double total = 0.0D;
        for (PartSummary part : parts) {
            total += part.coefficient();
        }
        return total / parts.size();
    }

    public record PartSummary(GunsmithPressPart part, GunsmithPartQuality quality,
                              GunsmithPartVariant variant, double coefficient) {
    }
}

package com.miningdim.job.munitions.gunsmith;

import com.miningdim.job.munitions.MunitionsConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 枪匠成品枪的 NBT 数据视图。
 *
 * 校验分档 (审查 M3): 只有 {@link #CURRENT_VERSION} 的缓存才与当前公式逐项精确比对, 更旧的版本一律只验
 * 类型与值域。理由是缓存里的 stats 从 v2 起就不再参与任何对外数值 —— damage/range/recoil 等全部由
 * Parts 实时重算, 缓存只是写入当时的展示副本。而写入公式每改一次 (例: 主线把格赫娜导气核心的 range
 * 强制写成 1.0, 本版改成写核心系数再乘组件系数), 精确比对就会把一整批完全合法的存量枪判成畸形数据从
 * {@link #from(ItemStack)} 抛出。真正的防篡改在 {@code readParts}: 部件集合、品质、系数值域、型号与槽位
 * 的兼容性照常硬校验。
 */
public final class GunsmithGunStats {

    public static final String ROOT_KEY = "MiningDimGunsmith";
    public static final String PARTS_KEY = "Parts";
    public static final String STATS_KEY = "Stats";
    public static final String VERSION_KEY = "version";
    public static final int CURRENT_VERSION = 7;

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/gunsmith_gun_stats");

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
                    GunsmithPlatform.AR.supportedParts(), migratedArReceiver, version);
        } else if (migratedMarksmanGrip) {
            List<PartSummary> migrated = new ArrayList<>(readParts(root, GunsmithPlatform.MARKSMAN,
                    LEGACY_MARKSMAN_FIVE_PARTS, false, version));
            migrated.add(new PartSummary(GunsmithPressPart.GRIP, GunsmithPartQuality.COMMON,
                    GunsmithPartVariant.BASE, 1.0D));
            this.parts = List.copyOf(migrated);
        } else if (migratedSniperFiringPin) {
            List<PartSummary> migrated = new ArrayList<>(readParts(root, GunsmithPlatform.SNIPER,
                    LEGACY_SNIPER_FOUR_PARTS, false, version));
            migrated.add(new PartSummary(GunsmithPressPart.FIRING_PIN, GunsmithPartQuality.COMMON,
                    GunsmithPartVariant.BASE, 1.0D));
            this.parts = List.copyOf(migrated);
        } else {
            this.parts = readParts(root, blueprint.platform(), blueprint.requiredParts(),
                    migratedArReceiver, version);
        }
        ResourceLocation encodedGunId = gunId();
        if (!matchesBlueprintGunId(blueprint, encodedGunId, parts)) {
            throw new IllegalArgumentException("Gunsmith gun id does not match template: " + encodedGunId);
        }
        if (version == CURRENT_VERSION) {
            validateCurrentStats();
        } else {
            validateLegacyStats();
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

    /**
     * 整枪伤害乘子 = 单件品质系数 x 各槽组件伤害乘数连乘, 封顶到
     * {@link MunitionsConfig#gunsmithDamageMultiplierCap()} (审查 27)。
     *
     * 全链连乘原本无上限: AK 平台的红东高压导气核心 (2.00) 与赤雪-A 枪机 (1.25) 互不排斥,
     * 叠传奇枪机品质 (1.50) 可达 3.75 倍, 对 80 血公服单发躯干 27 点即三发致死。帽必须落在这里而不是
     * TACZ 接入层: tooltip / WebUI 详情 / 伤害曲线 全都读本方法, 分开封顶就会出现"面板显示 3.75 实际只有
     * 2.25"的不一致。爆头帽 {@link MunitionsConfig#GUNSMITH_HEADSHOT_DAMAGE_CAP} 只钳品质复利, 与本帽串联生效。
     */
    public double damage() {
        double uncapped = version == 1 ? value("damage") : baseDamage() * variantProduct(VariantStat.DAMAGE);
        return capDamageMultiplier(uncapped);
    }

    /**
     * 伤害总帽的唯一入口。装配台预览 ({@code GunsmithAssemblyRecipe.preview}) 是独立于 {@link #damage()}
     * 的第二条计算链, 必须调本方法而不是自己再写一遍 Math.min: 两边各封各的顶, 玩家就会在装配台看到
     * 3.75 倍伤害、付完工费拿到手却只有 2.25 倍 (审查 27)。
     */
    public static double capDamageMultiplier(double uncapped) {
        return Math.min(uncapped, MunitionsConfig.gunsmithDamageMultiplierCap());
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
                                               boolean migrateArReceiver, int version) {
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
            // v3 起 variant 是必填字段: 缺失说明数据已经畸形, 必须炸出来; 默认成普通组件会把玩家的势力
            // 组件效果无声抹掉, 而基础系数校验又与型号无关, 玩家只会觉得"枪突然变弱了"。
            if (version >= 3 && !encodedPart.contains("variant", Tag.TAG_STRING)) {
                throw new IllegalArgumentException("Gunsmith part has no string variant: " + part.id());
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

    /**
     * 五槽 marksman (无握把) 与四槽 sniper (无撞针) 都按结构判定, 不锁版本号 (审查 63)。
     *
     * 主线发布版里 SPR15HB 挂在 AR、没有任何 sniper 图纸, 所以这两种形态只可能出自测试服上
     * 中间版本产出的枪。原实现把触发条件锁在 version == 5 / 6 上, 而中间版本的版本号本就会再变,
     * 一变就漏接; 改成 "图纸平台对得上、但缺了新增的那个槽" 后对任何旧版本都生效。保留
     * {@code version < CURRENT_VERSION} 下界是为了不放过当前版本的缺件数据 —— 那是真畸形, 应该抛。
     */
    private static boolean isLegacyFivePartMarksmanData(CompoundTag root, GunsmithBlueprint blueprint,
                                                         int version) {
        return version < CURRENT_VERSION
                && blueprint.platform() == GunsmithPlatform.MARKSMAN
                && root.contains(PARTS_KEY, Tag.TAG_COMPOUND)
                && !root.getCompound(PARTS_KEY).contains(GunsmithPressPart.GRIP.id());
    }

    private static boolean isLegacyFourPartSniperData(CompoundTag root, GunsmithBlueprint blueprint,
                                                       int version) {
        return version < CURRENT_VERSION
                && blueprint.platform() == GunsmithPlatform.SNIPER
                && root.contains(PARTS_KEY, Tag.TAG_COMPOUND)
                && !root.getCompound(PARTS_KEY).contains(GunsmithPressPart.FIRING_PIN.id());
    }

    /**
     * 把中间版本误建的 AR 第七个机匣槽归位回六槽结构 (审查 64)。
     *
     * 两个取舍都有代价, 这里选择保留玩家花大代价做出的特殊组件: MK-AX-A 机匣与原枪机撞槽时只能
     * 留一件, 被顶掉的那件写 WARN 日志留痕, 事后可按日志给当事人补件; 普通 (BASE) 机匣没有任何组件
     * 效果, 已有枪机时直接丢弃即可, 但它顶替了枪机槽时必须降级成同品质的普通枪机 —— 直接 remove
     * 会让这把枪缺件, 从此永远读不出来。
     */
    private static CompoundTag migrateLegacyArReceiverParts(CompoundTag encodedParts) {
        String receiverId = GunsmithPressPart.RECEIVER.id();
        if (!encodedParts.contains(receiverId, Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Legacy AR receiver data is not a compound");
        }
        String boltId = GunsmithPressPart.BOLT.id();
        CompoundTag migrated = encodedParts.copy();
        CompoundTag receiver = migrated.getCompound(receiverId);
        boolean hasBolt = migrated.contains(boltId, Tag.TAG_COMPOUND);
        GunsmithPartVariant variant = receiver.contains("variant", Tag.TAG_STRING)
                ? GunsmithPartVariant.byId(receiver.getString("variant")) : GunsmithPartVariant.BASE;
        if (variant != GunsmithPartVariant.MK_AX_A_BOLT && variant != GunsmithPartVariant.BASE) {
            throw new IllegalArgumentException("Legacy AR receiver contains an unsupported variant");
        }
        migrated.remove(receiverId);
        if (variant == GunsmithPartVariant.MK_AX_A_BOLT) {
            if (hasBolt) {
                CompoundTag replaced = migrated.getCompound(boltId);
                LOGGER.warn("Legacy AR receiver migration dropped the original bolt (quality={}, coefficient={})"
                                + " in favour of the MK-AX-A component that shared the same gun",
                        replaced.getString("quality"), replaced.getDouble("coefficient"));
            }
            CompoundTag bolt = receiver.copy();
            bolt.putString("variant", variant.id());
            migrated.put(boltId, bolt);
        } else if (!hasBolt) {
            CompoundTag bolt = receiver.copy();
            bolt.putString("variant", GunsmithPartVariant.BASE.id());
            migrated.put(boltId, bolt);
        }
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
            // 三连发枪机只挂 AR/BOLT (GunsmithPartVariant.supports), 所以带着它的图纸必然是 AR 平台;
            // 非 AR 平台带三连发枪机是不可能存在的数据, 由 burstGunId 照常抛出来, 不在这里替它编造 gunId。
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
        // 缓存值只验证部件品质的基础系数，组件平衡值由当前热重载规则实时计算，故热更新不会让本校验失效。
        validateCurrentStat("damage", coefficient(GunsmithStat.DAMAGE));
        validateCurrentStat("headshot", baseHeadshot());
        validateCurrentStat("range", coefficient(GunsmithStat.RANGE));
        validateCurrentStat("recoil", recoil());
        validateCurrentStat("spread", spread());
        validateCurrentStat("handling", handling());
        validateCurrentStat("average", average());
    }

    /**
     * 旧版本缓存只验类型与值域, 不与当前公式精确比对 (审查 M3)。
     *
     * 迁移路径 (平台改槽、AR 机匣纠错) 与写入公式变更 (主线把格赫娜导气核心的 range 强制写成 1.0)
     * 都会让旧缓存与重算值对不上; 而 v2 起缓存早已不参与任何对外数值, 拿它做防篡改校验只会把正常
     * 玩家的存量枪判死。
     */
    private void validateLegacyStats() {
        value("damage");
        value("headshot");
        value("spread");
        value("handling");
        value("average");
        if (version == 1) {
            // v1 缓存里没有 range/recoil, 改由核心与枪托部件反查, 顺带确认这两件部件存在。
            range();
            recoil();
        } else {
            value("range");
            value("recoil");
        }
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

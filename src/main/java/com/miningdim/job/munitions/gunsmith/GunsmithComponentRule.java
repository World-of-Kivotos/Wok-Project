package com.miningdim.job.munitions.gunsmith;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.GsonHelper;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 一种枪匠组件型号的服务器权威平衡规则。规则来自 datapack，并在重载后同步给客户端。
 */
public record GunsmithComponentRule(Map<GunsmithPartQuality, Double> damage,
                                    Map<GunsmithPartQuality, Double> headshot,
                                    Map<GunsmithPartQuality, Double> fireRate,
                                    RangeOperation rangeOperation,
                                    Map<GunsmithPartQuality, Double> effectiveRange,
                                    Map<GunsmithPartQuality, Double> ammoSpeed,
                                    Map<GunsmithPartQuality, Double> armorIgnore,
                                    Map<GunsmithPartQuality, Double> spread,
                                    Map<GunsmithPartQuality, Double> recoil,
                                    Map<GunsmithPartQuality, Double> verticalRecoil,
                                    Map<GunsmithPartQuality, Double> adsSpeed) {

    private static final double MIN_MULTIPLIER = 0.05D;
    private static final double MAX_MULTIPLIER = 10.0D;

    public GunsmithComponentRule {
        damage = validatedCopy(damage, "damage");
        headshot = validatedCopy(headshot, "headshot");
        fireRate = validatedCopy(fireRate, "fire_rate");
        effectiveRange = validatedCopy(effectiveRange, "effective_range");
        ammoSpeed = validatedCopy(ammoSpeed, "ammo_speed");
        armorIgnore = validatedCopy(armorIgnore, "armor_ignore");
        spread = validatedCopy(spread, "spread");
        recoil = validatedCopy(recoil, "recoil");
        verticalRecoil = validatedCopy(verticalRecoil, "vertical_recoil");
        adsSpeed = validatedCopy(adsSpeed, "ads_speed");
        if (rangeOperation == null) {
            throw new IllegalArgumentException("Gunsmith component range operation is null");
        }
    }

    public double damage(GunsmithPartQuality quality) {
        return damage.get(quality);
    }

    public double headshot(GunsmithPartQuality quality) {
        return headshot.get(quality);
    }

    public double fireRate(GunsmithPartQuality quality) {
        return fireRate.get(quality);
    }

    public double effectiveRange(GunsmithPartQuality quality) {
        return effectiveRange.get(quality);
    }

    public double spread(GunsmithPartQuality quality) {
        return spread.get(quality);
    }

    public double ammoSpeed(GunsmithPartQuality quality) {
        return ammoSpeed.get(quality);
    }

    public double armorIgnore(GunsmithPartQuality quality) {
        return armorIgnore.get(quality);
    }

    public double recoil(GunsmithPartQuality quality) {
        return recoil.get(quality);
    }

    public double verticalRecoil(GunsmithPartQuality quality) {
        return verticalRecoil.get(quality);
    }

    public double adsSpeed(GunsmithPartQuality quality) {
        return adsSpeed.get(quality);
    }

    public double applyRange(double base, GunsmithPartQuality quality) {
        if (!Double.isFinite(base) || base <= 0.0D) {
            throw new IllegalArgumentException("Gunsmith base range multiplier must be positive and finite");
        }
        double configured = effectiveRange(quality);
        return rangeOperation == RangeOperation.REPLACE ? configured : base * configured;
    }

    public boolean hasEffects(GunsmithPartQuality quality) {
        return damage(quality) != 1.0D
                || headshot(quality) != 1.0D
                || fireRate(quality) != 1.0D
                || effectiveRange(quality) != 1.0D
                || ammoSpeed(quality) != 1.0D
                || armorIgnore(quality) != 1.0D
                || spread(quality) != 1.0D
                || recoil(quality) != 1.0D
                || verticalRecoil(quality) != 1.0D
                || adsSpeed(quality) != 1.0D;
    }

    public static GunsmithComponentRule identity() {
        EnumMap<GunsmithPartQuality, Double> identity = filled(1.0D);
        return new GunsmithComponentRule(identity, identity, identity, RangeOperation.MULTIPLY,
                identity, identity, identity, identity, identity, identity, identity);
    }

    public static GunsmithComponentRule gehennaDefaults() {
        return new GunsmithComponentRule(
                filled(1.0D),
                filled(1.0D),
                qualityValues(1.05D, 1.10D, 1.15D, 1.20D, 1.25D),
                RangeOperation.MULTIPLY,
                filled(1.0D),
                filled(1.0D),
                filled(1.0D),
                qualityValues(1.03D, 1.06D, 1.09D, 1.12D, 1.15D),
                filled(2.0D),
                filled(1.0D),
                filled(1.0D));
    }

    public static GunsmithComponentRule redWinterDefaults() {
        return new GunsmithComponentRule(
                qualityValues(1.20D, 1.40D, 1.60D, 1.80D, 2.00D),
                filled(1.0D),
                filled(0.75D),
                RangeOperation.MULTIPLY,
                filled(0.60D),
                filled(1.0D),
                filled(1.0D),
                qualityValues(1.20D, 1.35D, 1.50D, 1.65D, 1.80D),
                filled(2.00D),
                filled(3.00D),
                filled(1.0D));
    }

    public static GunsmithComponentRule mkAxADefaults() {
        return new GunsmithComponentRule(
                qualityValues(1.05D, 1.10D, 1.15D, 1.20D, 1.25D),
                filled(1.0D),
                filled(1.05D),
                RangeOperation.MULTIPLY,
                qualityValues(1.05D, 1.10D, 1.15D, 1.20D, 1.25D),
                filled(1.0D),
                filled(1.0D),
                qualityValues(0.95D, 0.90D, 0.85D, 0.80D, 0.75D),
                qualityValues(0.95D, 0.90D, 0.85D, 0.80D, 0.75D),
                filled(1.0D),
                filled(0.70D));
    }

    public static GunsmithComponentRule trinityPrecisionGraduatedBarrelDefaults() {
        return new GunsmithComponentRule(
                filled(1.0D),
                filled(1.50D),
                filled(1.0D),
                RangeOperation.MULTIPLY,
                filled(1.50D),
                filled(1.0D),
                filled(1.0D),
                filled(0.70D),
                filled(1.0D),
                filled(1.0D),
                filled(0.50D));
    }

    public static GunsmithComponentRule arThreeRoundBurstBoltDefaults() {
        return new GunsmithComponentRule(
                filled(1.0D),
                filled(1.0D),
                filled(1.0D),
                RangeOperation.MULTIPLY,
                filled(1.0D),
                filled(1.0D),
                filled(1.0D),
                filled(0.75D),
                filled(0.65D),
                filled(1.0D),
                filled(1.0D));
    }

    public static GunsmithComponentRule trinityPrecisionGraduatedSniperBarrelDefaults() {
        return new GunsmithComponentRule(
                filled(1.0D),
                filled(2.0D),
                filled(1.0D),
                RangeOperation.MULTIPLY,
                filled(1.0D),
                filled(1.50D),
                filled(1.0D),
                filled(0.67D),
                filled(1.0D),
                filled(1.0D),
                filled(0.70D));
    }

    public static GunsmithComponentRule redWinterChixueABoltDefaults() {
        return new GunsmithComponentRule(
                filled(1.25D),
                filled(1.0D),
                filled(1.0D),
                RangeOperation.MULTIPLY,
                filled(1.0D),
                filled(1.0D),
                filled(0.75D),
                filled(1.0D),
                filled(1.35D),
                filled(1.0D),
                filled(1.0D));
    }

    public static GunsmithComponentRule fromJson(JsonObject json) {
        JsonObject range = GsonHelper.getAsJsonObject(json, "effective_range");
        RangeOperation operation = RangeOperation.byId(GsonHelper.getAsString(range, "operation"));
        return new GunsmithComponentRule(
                readQualityValues(GsonHelper.getAsJsonObject(json, "damage"), "damage"),
                readQualityValues(GsonHelper.getAsJsonObject(json, "headshot"), "headshot"),
                readQualityValues(GsonHelper.getAsJsonObject(json, "fire_rate"), "fire_rate"),
                operation,
                readQualityValues(GsonHelper.getAsJsonObject(range, "values"), "effective_range.values"),
                readQualityValues(GsonHelper.getAsJsonObject(json, "ammo_speed"), "ammo_speed"),
                readQualityValues(GsonHelper.getAsJsonObject(json, "armor_ignore"), "armor_ignore"),
                readQualityValues(GsonHelper.getAsJsonObject(json, "spread"), "spread"),
                readQualityValues(GsonHelper.getAsJsonObject(json, "recoil"), "recoil"),
                readQualityValues(GsonHelper.getAsJsonObject(json, "vertical_recoil"), "vertical_recoil"),
                readQualityValues(GsonHelper.getAsJsonObject(json, "ads_speed"), "ads_speed"));
    }

    public void encode(FriendlyByteBuf buf) {
        writeQualityValues(buf, damage);
        writeQualityValues(buf, headshot);
        writeQualityValues(buf, fireRate);
        buf.writeEnum(rangeOperation);
        writeQualityValues(buf, effectiveRange);
        writeQualityValues(buf, ammoSpeed);
        writeQualityValues(buf, armorIgnore);
        writeQualityValues(buf, spread);
        writeQualityValues(buf, recoil);
        writeQualityValues(buf, verticalRecoil);
        writeQualityValues(buf, adsSpeed);
    }

    public static GunsmithComponentRule decode(FriendlyByteBuf buf) {
        return new GunsmithComponentRule(readQualityValues(buf), readQualityValues(buf),
                readQualityValues(buf), buf.readEnum(RangeOperation.class), readQualityValues(buf),
                readQualityValues(buf), readQualityValues(buf), readQualityValues(buf),
                readQualityValues(buf), readQualityValues(buf), readQualityValues(buf));
    }

    private static Map<GunsmithPartQuality, Double> readQualityValues(JsonObject json, String field) {
        Set<String> expected = java.util.Arrays.stream(GunsmithPartQuality.values())
                .map(GunsmithPartQuality::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!json.keySet().equals(expected)) {
            throw new IllegalArgumentException("Gunsmith component rule has invalid quality keys: " + field);
        }
        EnumMap<GunsmithPartQuality, Double> result = new EnumMap<>(GunsmithPartQuality.class);
        for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
            JsonElement value = json.get(quality.id());
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("Gunsmith component rule " + field
                        + " is missing numeric quality " + quality.id());
            }
            result.put(quality, value.getAsDouble());
        }
        return result;
    }

    private static void writeQualityValues(FriendlyByteBuf buf, Map<GunsmithPartQuality, Double> values) {
        for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
            buf.writeDouble(values.get(quality));
        }
    }

    private static Map<GunsmithPartQuality, Double> readQualityValues(FriendlyByteBuf buf) {
        EnumMap<GunsmithPartQuality, Double> result = new EnumMap<>(GunsmithPartQuality.class);
        for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
            result.put(quality, buf.readDouble());
        }
        return result;
    }

    private static Map<GunsmithPartQuality, Double> validatedCopy(
            Map<GunsmithPartQuality, Double> source, String field) {
        if (source == null) {
            throw new IllegalArgumentException("Gunsmith component rule field is null: " + field);
        }
        EnumMap<GunsmithPartQuality, Double> copy = new EnumMap<>(GunsmithPartQuality.class);
        for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
            Double value = source.get(quality);
            if (value == null || !Double.isFinite(value)
                    || value < MIN_MULTIPLIER || value > MAX_MULTIPLIER) {
                throw new IllegalArgumentException("Gunsmith component multiplier is invalid: "
                        + field + "." + quality.id());
            }
            copy.put(quality, value);
        }
        return Map.copyOf(copy);
    }

    private static EnumMap<GunsmithPartQuality, Double> filled(double value) {
        return qualityValues(value, value, value, value, value);
    }

    private static EnumMap<GunsmithPartQuality, Double> qualityValues(
            double common, double improved, double milspec, double precision, double legendary) {
        EnumMap<GunsmithPartQuality, Double> values = new EnumMap<>(GunsmithPartQuality.class);
        values.put(GunsmithPartQuality.COMMON, common);
        values.put(GunsmithPartQuality.IMPROVED, improved);
        values.put(GunsmithPartQuality.MILSPEC, milspec);
        values.put(GunsmithPartQuality.PRECISION, precision);
        values.put(GunsmithPartQuality.LEGENDARY, legendary);
        return values;
    }

    public enum RangeOperation {
        MULTIPLY("multiply"),
        REPLACE("replace");

        private final String id;

        RangeOperation(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static RangeOperation byId(String id) {
            for (RangeOperation operation : values()) {
                if (operation.id.equals(id)) {
                    return operation;
                }
            }
            throw new IllegalArgumentException("Unknown gunsmith range operation: " + id);
        }
    }
}

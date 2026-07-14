package com.miningdim.job.engineer.armor;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 插板护甲的服务端平衡配置。四张矩阵均按 I轻、I中、I重、II轻……VI重排列。 */
public final class PlateArmorConfig {

    private static final List<Double> DEFAULT_R = List.of(
            0.45D, 0.50D, 0.55D,
            0.60D, 0.65D, 0.70D,
            0.75D, 0.80D, 0.85D,
            0.85D, 0.88D, 0.90D,
            0.90D, 0.92D, 0.94D,
            0.94D, 0.96D, 0.98D);

    /* I 级只靠 R 区分构型；II-VI 逐步获得穿甲段缓冲，且始终显著低于同格 R。 */
    private static final List<Double> DEFAULT_Q = List.of(
            0.00D, 0.00D, 0.00D,
            0.02D, 0.05D, 0.08D,
            0.08D, 0.10D, 0.15D,
            0.15D, 0.20D, 0.25D,
            0.25D, 0.35D, 0.45D,
            0.45D, 0.50D, 0.55D);

    private static final List<Double> DEFAULT_G = List.of(
            0.35D, 0.40D, 0.45D,
            0.45D, 0.50D, 0.55D,
            0.60D, 0.68D, 0.70D,
            0.70D, 0.76D, 0.78D,
            0.78D, 0.84D, 0.86D,
            0.86D, 0.88D, 0.90D);

    private static final List<Double> DEFAULT_T = List.of(
            16.0D, 20.0D, 24.0D,
            24.0D, 32.0D, 38.0D,
            38.0D, 48.0D, 58.0D,
            58.0D, 72.0D, 84.0D,
            84.0D, 96.0D, 112.0D,
            112.0D, 128.0D, 154.0D);

    private final ForgeConfigSpec.ConfigValue<List<? extends Double>> ballisticProtection;
    private final ForgeConfigSpec.ConfigValue<List<? extends Double>> armorPiercingBuffer;
    private final ForgeConfigSpec.ConfigValue<List<? extends Double>> generalProtection;
    private final ForgeConfigSpec.ConfigValue<List<? extends Double>> pressureCapacity;
    private final ForgeConfigSpec.DoubleValue lightMovement;
    private final ForgeConfigSpec.DoubleValue mediumMovement;
    private final ForgeConfigSpec.DoubleValue heavyMovement;
    private final Map<PlateArmorConstructionMaterial, ForgeConfigSpec.IntValue> materialDurability =
            new EnumMap<>(PlateArmorConstructionMaterial.class);

    private PlateArmorConfig(ForgeConfigSpec.Builder builder) {
        builder.push("plateArmor");
        builder.comment("All 18-value matrices use order I-light, I-medium, I-heavy, then II-light ... VI-heavy.");
        ballisticProtection = builder.comment("R: protection applied to tacz:bullet and tacz:bullet_void normal ballistic segments.")
                .defineList("ballisticProtectionR", DEFAULT_R, PlateArmorConfig::isRate);
        armorPiercingBuffer = builder.comment("Q: buffer applied only to tacz:bullet_ignore_armor and tacz:bullet_void_ignore_armor.")
                .defineList("armorPiercingBufferQ", DEFAULT_Q, PlateArmorConfig::isRate);
        generalProtection = builder.comment("G: protection of the covered portion of eligible non-TaCZ combat damage.")
                .defineList("generalProtectionG", DEFAULT_G, PlateArmorConfig::isRate);
        pressureCapacity = builder.comment("T: per-hit covered damage capacity for eligible non-TaCZ combat damage.")
                .defineList("pressureCapacityT", DEFAULT_T, PlateArmorConfig::isNonNegative);

        builder.push("movement");
        lightMovement = builder.comment("Light plate movement modifier; 0.03 = +3%, MULTIPLY_TOTAL.")
                .defineInRange("light", 0.03D, -0.95D, 10.0D);
        mediumMovement = builder.comment("Medium plate movement modifier.")
                .defineInRange("medium", 0.0D, -0.95D, 10.0D);
        heavyMovement = builder.comment("Heavy plate movement modifier; -0.04 = -4%, MULTIPLY_TOTAL.")
                .defineInRange("heavy", -0.04D, -0.95D, 10.0D);
        builder.pop();

        builder.push("materialDurability");
        builder.comment("Maximum durability by construction material. Material does not change R/Q/G/T.");
        for (PlateArmorConstructionMaterial material : PlateArmorConstructionMaterial.values()) {
            materialDurability.put(material, builder.defineInRange(
                    material.id(), material.defaultDurability(), 1, 100000));
        }
        builder.pop();
        builder.pop();
    }

    public static PlateArmorConfig define(ForgeConfigSpec.Builder builder) {
        return new PlateArmorConfig(builder);
    }

    public double ballisticProtection(PlateArmorTier tier, PlateArmorWeight weight) {
        return matrixValue("ballisticProtectionR", ballisticProtection, tier, weight);
    }

    public double armorPiercingBuffer(PlateArmorTier tier, PlateArmorWeight weight) {
        return matrixValue("armorPiercingBufferQ", armorPiercingBuffer, tier, weight);
    }

    public double generalProtection(PlateArmorTier tier, PlateArmorWeight weight) {
        return matrixValue("generalProtectionG", generalProtection, tier, weight);
    }

    public double pressureCapacity(PlateArmorTier tier, PlateArmorWeight weight) {
        return matrixValue("pressureCapacityT", pressureCapacity, tier, weight);
    }

    public double movementModifier(PlateArmorWeight weight) {
        return switch (weight) {
            case LIGHT -> lightMovement.get();
            case MEDIUM -> mediumMovement.get();
            case HEAVY -> heavyMovement.get();
        };
    }

    public int maxDurability(PlateArmorConstructionMaterial material) {
        ForgeConfigSpec.IntValue value = materialDurability.get(material);
        if (value == null) {
            throw new IllegalArgumentException("unregistered plate armor material: " + material);
        }
        return value.get();
    }

    private static double matrixValue(String name,
                                      ForgeConfigSpec.ConfigValue<List<? extends Double>> configured,
                                      PlateArmorTier tier,
                                      PlateArmorWeight weight) {
        int index = tier.configIndex(weight);
        List<?> values = configured.get();
        if (values.size() != PlateArmorTier.values().length * PlateArmorWeight.values().length) {
            throw new IllegalStateException("plateArmor." + name + " must contain exactly 18 values, got "
                    + values.size());
        }
        Object value = values.get(index);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalStateException("plateArmor." + name + " contains a non-finite number at index " + index);
        }
        return number.doubleValue();
    }

    private static boolean isRate(Object value) {
        return value instanceof Number number
                && Double.isFinite(number.doubleValue())
                && number.doubleValue() >= 0.0D
                && number.doubleValue() < 1.0D;
    }

    private static boolean isNonNegative(Object value) {
        return value instanceof Number number
                && Double.isFinite(number.doubleValue())
                && number.doubleValue() >= 0.0D;
    }
}

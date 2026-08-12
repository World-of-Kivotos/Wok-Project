package com.kivotos.armorer.armor;

import com.kivotos.armorer.ArmorerConfig;

/** 单件插板在当前服务端配置下的即时属性快照。调用时读取，不跨配置重载缓存。 */
public record PlateArmorStats(double ballisticProtection,
                              double armorPiercingBuffer,
                              double generalProtection,
                              double pressureCapacity,
                              double movementModifier) {

    public static PlateArmorStats resolve(PlateArmorVariant variant) {
        PlateArmorConfig config = ArmorerConfig.PLATE_ARMOR;
        PlateArmorTier tier = variant.tier();
        PlateArmorWeight weight = variant.weight();
        PlateArmorConstructionMaterial material = variant.material();
        return new PlateArmorStats(
                adjustProtection(config.ballisticProtection(tier, weight),
                        config.ballisticLeakMultiplier(material)),
                adjustProtection(config.armorPiercingBuffer(tier, weight),
                        config.armorPiercingLeakMultiplier(material)),
                adjustProtection(config.generalProtection(tier, weight),
                        config.generalLeakMultiplier(material)),
                config.pressureCapacity(tier, weight) * config.pressureCapacityMultiplier(material),
                config.movementModifier(weight) - config.movementPenalty(material));
    }

    private static double adjustProtection(double baseProtection, double leakMultiplier) {
        if (baseProtection == 0.0D) {
            return 0.0D;
        }
        double adjusted = 1.0D - (1.0D - baseProtection) * leakMultiplier;
        // 差材料最多让该项失去全部防护，不会把命中放大成额外伤害。
        return adjusted < 0.0D ? 0.0D : adjusted;
    }
}


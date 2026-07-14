package com.miningdim.job.engineer.armor;

import com.miningdim.job.engineer.EngineerConfig;

/** 单件插板在当前服务端配置下的即时属性快照。调用时读取，不跨配置重载缓存。 */
public record PlateArmorStats(double ballisticProtection,
                              double armorPiercingBuffer,
                              double generalProtection,
                              double pressureCapacity,
                              double movementModifier) {

    public static PlateArmorStats resolve(PlateArmorVariant variant) {
        PlateArmorConfig config = EngineerConfig.PLATE_ARMOR;
        PlateArmorTier tier = variant.tier();
        PlateArmorWeight weight = variant.weight();
        return new PlateArmorStats(
                config.ballisticProtection(tier, weight),
                config.armorPiercingBuffer(tier, weight),
                config.generalProtection(tier, weight),
                config.pressureCapacity(tier, weight),
                config.movementModifier(weight));
    }
}

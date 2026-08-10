package com.kivotos.armorer.armor;

/** 插板护甲的全服防护等级。枚举顺序也是配置矩阵的等级顺序，禁止重排。 */
public enum PlateArmorTier {
    I,
    II,
    III,
    IV,
    V,
    VI;

    public int configIndex(PlateArmorWeight weight) {
        return ordinal() * PlateArmorWeight.values().length + weight.ordinal();
    }
}


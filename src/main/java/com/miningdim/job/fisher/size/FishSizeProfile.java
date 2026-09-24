package com.miningdim.job.fisher.size;

import net.minecraft.resources.ResourceLocation;

/**
 * 一个鱼种的体型档案。长度单位厘米, 重量按体长-体重关系 W(g) = a * L(cm)^b 推算 (渔业生物学通用的
 * 长重关系, a/b 取同类真实鱼的量级)。
 *
 * minCm 是能钓到的最小个体, commonCm 是分布中位数, maxCm 是奖杯上限 (取真实最大体长的八九成, 大而不破纪录)。
 */
public record FishSizeProfile(ResourceLocation itemId, double minCm, double commonCm, double maxCm, double a, double b) {
    public static final double MAX_LENGTH_CM = 5000.0D;
    /** 2 吨: 远高于档案里最重的鱼 (约 0.6 吨), 只拦明显写错的数量级。 */
    public static final double MAX_WEIGHT_G = 2_000_000.0D;

    public FishSizeProfile {
        if (!(minCm > 0.0D && minCm < commonCm && commonCm < maxCm && maxCm <= MAX_LENGTH_CM)) {
            throw new IllegalArgumentException("Fish size lengths must satisfy 0 < min < common < max <= "
                    + MAX_LENGTH_CM + " for " + itemId);
        }
        if (!(a > 0.0D && a <= 1.0D)) {
            throw new IllegalArgumentException("Fish size coefficient a must be in (0, 1] for " + itemId);
        }
        if (!(b >= 2.5D && b <= 3.5D)) {
            throw new IllegalArgumentException("Fish size exponent b must be in [2.5, 3.5] for " + itemId);
        }
        if (weightGramsAt(maxCm, a, b) > MAX_WEIGHT_G) {
            throw new IllegalArgumentException("Fish size weight at max length exceeds " + MAX_WEIGHT_G + " g for " + itemId);
        }
    }

    public double weightGramsAt(double lengthCm) {
        return weightGramsAt(lengthCm, a, b);
    }

    private static double weightGramsAt(double lengthCm, double a, double b) {
        return a * Math.pow(lengthCm, b);
    }
}

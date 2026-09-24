package com.miningdim.job.fisher.size;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * 把一个标准正态分位 z 换算成体长与体重。
 *
 * 体长围绕中位数做对数正态展开, z 截断到 +-{@value #Z_LIMIT}: z=0 落在 commonCm, z=+3 恰好是 maxCm,
 * z=-3 恰好是 minCm, 两侧各自用自己的对数跨度, 所以结果永远不出档案给定的区间。截断只影响两端约 0.27%
 * 的样本, 档位阈值都在 +-3 以内, 出档概率不受影响。
 *
 * 体重 = a * L^b 再乘一个同体长个体的肥瘦差 (条件因子), 条件因子取对数正态、标准差 {@value #WEIGHT_SIGMA},
 * 并钳在 +-{@value #WEIGHT_SPREAD} 以内, 避免同一条长度出现离谱的重量。
 */
public final class FishSizeRoller {
    public static final double Z_LIMIT = 3.0D;
    public static final double WEIGHT_SIGMA = 0.08D;
    public static final double WEIGHT_SPREAD = 0.20D;

    private FishSizeRoller() {
    }

    public static FishMeasurement roll(FishSizeProfile profile, RandomSource random) {
        return measure(profile, random.nextGaussian(), random.nextGaussian());
    }

    public static FishMeasurement measure(FishSizeProfile profile, double z, double conditionZ) {
        double clamped = Mth.clamp(z, -Z_LIMIT, Z_LIMIT);
        double span = clamped >= 0.0D
                ? profile.maxCm() / profile.commonCm()
                : profile.commonCm() / profile.minCm();
        double lengthCm = profile.commonCm() * Math.pow(span, clamped / Z_LIMIT);
        int minMm = Math.max(1, (int) Math.round(profile.minCm() * 10.0D));
        int maxMm = Math.max(minMm, (int) Math.round(profile.maxCm() * 10.0D));
        int lengthMm = Mth.clamp((int) Math.round(lengthCm * 10.0D), minMm, maxMm);
        double condition = Mth.clamp(Math.exp(WEIGHT_SIGMA * conditionZ), 1.0D - WEIGHT_SPREAD, 1.0D + WEIGHT_SPREAD);
        long weightMg = Math.max(1L, Math.round(profile.weightGramsAt(lengthCm) * condition * 1000.0D));
        return new FishMeasurement(lengthMm, weightMg, FishSizeClass.forZ(clamped));
    }
}

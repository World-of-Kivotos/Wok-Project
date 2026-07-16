package com.miningdim.job.munitions.gunsmith;

import java.util.Objects;

/**
 * 枪匠系数 -> TACZ 属性乘子的纯映射 (审查 TQ-1 / TACZ-BAL-1)。
 *
 * 刻意不依赖任何 TACZ 类型: dev GameTest 不加载 TACZ, 把"乘还是逆"的方向与爆头封顶留在这里就能直接断言;
 * GunsmithTaczStatsHandler 只负责把这些乘子落到 TACZ 属性缓存, 不再自己算方向。
 *
 * 爆头封顶: TACZ 最终爆头伤害 = 距离伤害 x 爆头倍率, 因此枪匠对二者各乘系数后, 爆头处等效倍率是
 * damage x headshot 的复利 (两个品质帽相乘最高 1.5 x 1.5 = 2.25)。此处把该复利钳到 headshotDamageCap,
 * 反解出可施加的 headshot 乘子, 只压爆头、不动躯干每-stat 帽。
 */
public record GunsmithStatMultipliers(double damage, double headshot, double effectiveRange,
                                      double adsTime, double inaccuracy, double aimInaccuracy,
                                      double verticalRecoil, double horizontalRecoil, double fireRate) {

    public static GunsmithStatMultipliers of(GunsmithGunStats stats, double headshotDamageCap) {
        Objects.requireNonNull(stats, "stats");
        return ofResolved(stats.damage(), stats.headshot(), stats.range(), stats.handling(),
                stats.inaccuracyMultiplier(), stats.verticalRecoilMultiplier(), stats.horizontalRecoilMultiplier(),
                stats.fireRateMultiplier(), headshotDamageCap);
    }

    public static GunsmithStatMultipliers of(double damage, double headshot, double range,
                                             double handling, double spread, double recoil,
                                             double headshotDamageCap) {
        requirePositive(recoil, "recoil");
        requirePositive(spread, "spread");
        return ofResolved(damage, headshot, range, handling, inverse(spread),
                inverse(recoil), inverse(recoil), 1.0D, headshotDamageCap);
    }

    public double recoil() {
        return verticalRecoil;
    }

    public int roundsPerMinute(int baseRoundsPerMinute) {
        if (baseRoundsPerMinute <= 0) {
            throw new IllegalArgumentException("Base rounds per minute must be positive: " + baseRoundsPerMinute);
        }
        return Math.toIntExact(Math.round(baseRoundsPerMinute * fireRate));
    }

    private static GunsmithStatMultipliers ofResolved(double damage, double headshot, double range,
                                                       double handling, double inaccuracy,
                                                       double verticalRecoil, double horizontalRecoil,
                                                       double fireRate, double headshotDamageCap) {
        requirePositive(damage, "damage");
        requirePositive(headshot, "headshot");
        requirePositive(range, "range");
        requirePositive(handling, "handling");
        requirePositive(inaccuracy, "inaccuracy");
        requirePositive(verticalRecoil, "verticalRecoil");
        requirePositive(horizontalRecoil, "horizontalRecoil");
        requirePositive(fireRate, "fireRate");
        requirePositive(headshotDamageCap, "headshotDamageCap");
        double cappedHeadshot = damage * headshot > headshotDamageCap
                ? headshotDamageCap / damage
                : headshot;
        return new GunsmithStatMultipliers(damage, cappedHeadshot, range,
                inverse(handling), inaccuracy, inverse(handling),
                verticalRecoil, horizontalRecoil, fireRate);
    }

    private static double inverse(double coefficient) {
        return 1.0D / coefficient;
    }

    private static void requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new IllegalArgumentException("Gunsmith stat multiplier input must be positive and finite: " + name);
        }
    }
}

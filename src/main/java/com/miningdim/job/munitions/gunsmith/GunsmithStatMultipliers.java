package com.miningdim.job.munitions.gunsmith;

import java.util.Objects;

/**
 * 枪匠系数 -> TACZ 属性乘子的纯映射 (审查 TQ-1 / TACZ-BAL-1)。
 *
 * 刻意不依赖任何 TACZ 类型: dev GameTest 不加载 TACZ, 把"乘还是逆"的方向与爆头封顶留在这里就能直接断言;
 * GunsmithTaczStatsHandler 只负责把这些乘子落到 TACZ 属性缓存, 不再自己算方向。
 *
 * 爆头封顶只约束枪机伤害品质系数 x 基础枪管爆头品质系数的复利。势力组件自己的伤害与爆头倍率在帽外计算，
 * 避免红冬伤害或圣三一枪管的明确特殊效果被基础品质封顶吞掉。
 */
public record GunsmithStatMultipliers(double damage, double headshot, double effectiveRange, double ammoSpeed,
                                      double armorIgnore, double adsTime, double inaccuracy, double aimInaccuracy,
                                      double recoil, double verticalRecoil, double fireRate) {

    public static GunsmithStatMultipliers of(GunsmithGunStats stats, double headshotDamageCap) {
        Objects.requireNonNull(stats, "stats");
        GunsmithStatMultipliers base = of(stats.damage(), stats.baseDamage(), stats.baseHeadshot(), stats.range(),
                stats.handling(), stats.spread(), stats.recoil(), headshotDamageCap);
        double recoil = base.recoil() * stats.specialRecoil();
        return new GunsmithStatMultipliers(base.damage(), base.headshot() * stats.specialHeadshot(),
                base.effectiveRange(), stats.ammoSpeed(), stats.armorIgnore(),
                base.adsTime() * inverse(stats.specialAdsSpeed()),
                base.inaccuracy() * stats.specialSpread(),
                base.aimInaccuracy() * stats.specialSpread(), recoil,
                recoil * stats.verticalRecoil(), stats.fireRate());
    }

    public static GunsmithStatMultipliers of(double damage, double headshot, double range,
                                             double handling, double spread, double recoil,
                                             double headshotDamageCap) {
        return of(damage, damage, headshot, range, handling, spread, recoil, headshotDamageCap);
    }

    static GunsmithStatMultipliers of(double damage, double headshotCapDamage, double headshot, double range,
                                      double handling, double spread, double recoil,
                                      double headshotDamageCap) {
        requirePositive(damage, "damage");
        requirePositive(headshotCapDamage, "headshot cap damage");
        requirePositive(headshot, "headshot");
        requirePositive(range, "range");
        requirePositive(handling, "handling");
        requirePositive(spread, "spread");
        requirePositive(recoil, "recoil");
        requirePositive(headshotDamageCap, "headshotDamageCap");
        double cappedHeadshot = headshotCapDamage * headshot > headshotDamageCap
                ? headshotDamageCap / headshotCapDamage
                : headshot;
        return new GunsmithStatMultipliers(damage, cappedHeadshot, range, 1.0D, 1.0D,
                inverse(handling), inverse(spread), inverse(handling), inverse(recoil),
                inverse(recoil), 1.0D);
    }

    private static double inverse(double coefficient) {
        return 1.0D / coefficient;
    }

    /**
     * 把势力组件制造的额外后坐从原枪/外部配件修正中分离出来。
     * 外部配件只修正受枪托控制后的原枪后坐，不能同比消除势力组件自身增加的部分。
     */
    static double nonReducibleRecoilExtra(double baseControl, double configuredTotal) {
        requirePositive(baseControl, "base recoil control");
        requirePositive(configuredTotal, "configured recoil total");
        return configuredTotal > baseControl ? configuredTotal - baseControl : 0.0D;
    }

    static double attachmentScaledRecoilBase(double baseControl, double configuredTotal) {
        requirePositive(baseControl, "base recoil control");
        requirePositive(configuredTotal, "configured recoil total");
        return configuredTotal < baseControl ? configuredTotal : baseControl;
    }

    static double recoilAfterAttachment(double attachmentMultiplier,
                                        double baseControl,
                                        double configuredTotal) {
        requirePositive(attachmentMultiplier, "attachment recoil multiplier");
        return attachmentMultiplier * attachmentScaledRecoilBase(baseControl, configuredTotal)
                + nonReducibleRecoilExtra(baseControl, configuredTotal);
    }

    private static void requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new IllegalArgumentException("Gunsmith stat multiplier input must be positive and finite: " + name);
        }
    }
}

package com.miningdim.job.brewer;

/**
 * 年份时钟 (酿酒师 第二节)。用现实挂钟 (服务端 {@code System.currentTimeMillis()}, 与经济衰减闸 UTC 时间观同源):
 *
 *  - 基础年份累积按现实毫秒差 (离线/区块卸载也陈酿 —— 酒窖本就该你不在也熟; "至少七天周期"字面=现实天;
 *    服务端挂钟权威, 客户端动不了);
 *  - 满月 (moonPhase==0) 期间陈酿额外加成 (Tide 在满月夜出最稀有鱼的潮汐呼应; 同读原版 {@code level.getMoonPhase()},
 *    这才是与潮汐 mod 的真味连结, 与底层用挂钟还是 tick 无关)。
 *
 * 纯静态换算, 无世界/时间依赖 (现实毫秒由调用方传入), 便于 GameTest 确定性验证 (酒窖箱 BE 把 nowMillis /
 * moonPhase 作参数传入结算)。
 */
public final class VintageClock {

    private VintageClock() {
    }

    /** 现实毫秒差 -> 年份增量 (double, 保留小数, 由调用方累加进 NBT 年份)。负/零差返回 0。 */
    public static double vintageYearsFromMillis(long deltaMillis) {
        if (deltaMillis <= 0L) {
            return 0.0D;
        }
        return (double) deltaMillis / (double) BrewerConstants.MILLIS_PER_VINTAGE_YEAR;
    }

    /** 年份 -> 所需现实毫秒数 (向上取整; 燃料/进度估算用)。 */
    public static long millisForYears(double years) {
        if (years <= 0.0D) {
            return 0L;
        }
        return (long) Math.ceil(years * BrewerConstants.MILLIS_PER_VINTAGE_YEAR);
    }

    /** 原版满月判定 (moonPhase==0); 与 Tide 的 MoonPhasePredicate.anyOf(0) 同义。 */
    public static boolean isFullMoon(int moonPhase) {
        return moonPhase == 0;
    }

    /** 给定基础年份增量与当前月相, 返回潮汐加成后的年份增量 (满月期 ×(1 + FULL_MOON_BONUS), 否则原值)。 */
    public static double applyMoonBonus(double baseYears, int moonPhase) {
        if (baseYears <= 0.0D) {
            return 0.0D;
        }
        return isFullMoon(moonPhase) ? baseYears * (1.0D + BrewerConstants.FULL_MOON_BONUS) : baseYears;
    }
}

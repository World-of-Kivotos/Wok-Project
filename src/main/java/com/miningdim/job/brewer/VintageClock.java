package com.miningdim.job.brewer;

/**
 * 年份时钟 (酿酒师 第二节)。与潮汐 (Tide) mod 同源: 二者都读原版 level 时钟, 不引入自定义时钟、零跨 mod 依赖。
 *
 *  - 基础年份累积按 {@code level.getGameTime()} 的 tick 差 (单调递增; 不受 /time set 与睡觉跳夜影响 ——
 *    那两者改的是 getDayTime, 不是 getGameTime; 只在维度加载 + 服务器运行时推进, 无离线白嫖陈酿);
 *  - 满月 (moonPhase==0) 期间陈酿额外加成 (Tide 在满月夜出最稀有鱼的潮汐呼应; 同读 {@code level.getMoonPhase()})。
 *
 * 纯静态换算, 无世界依赖, 便于 GameTest 确定性验证 (酒窖箱 BE 把 gameTime / moonPhase 作参数传入结算)。
 */
public final class VintageClock {

    private VintageClock() {
    }

    /** gameTime tick 差 -> 年份增量 (double, 保留小数, 由调用方累加进 NBT 年份)。负/零差返回 0。 */
    public static double vintageYearsFromTicks(long deltaTicks) {
        if (deltaTicks <= 0L) {
            return 0.0D;
        }
        return (double) deltaTicks / (double) BrewerConstants.TICKS_PER_VINTAGE_YEAR;
    }

    /** 年份 -> 所需 gameTime tick 数 (向上取整; 燃料/进度估算用)。 */
    public static long ticksForYears(double years) {
        if (years <= 0.0D) {
            return 0L;
        }
        return (long) Math.ceil(years * BrewerConstants.TICKS_PER_VINTAGE_YEAR);
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

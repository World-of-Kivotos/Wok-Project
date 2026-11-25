package com.miningdim.job.brewer.cellar;

import com.miningdim.job.brewer.BrewerConfig;
import com.miningdim.job.brewer.BrewerConstants;
import com.miningdim.job.brewer.VintageClock;

import java.util.ArrayList;
import java.util.List;

/**
 * 酒窖箱陈酿结算纯逻辑 (酿酒师 阶段 4 / 设计文档第三节)。无世界 / 无 {@code System.currentTimeMillis} 依赖:
 * 调用方 (酒窖箱 BE) 把 elapsedMillis(现实挂钟差) / fuelAvailable(燃料槽干小麦数) / moonPhase(原版 level
 * .getMoonPhase) / fuelDebtIn(上次结余的小数燃料债) 作参数传入, 结算结果原样写回各酒槽 NBT、扣整数燃料、回存
 * 新的小数债。纯函数便于 GameTest 确定性断言数值。
 *
 * 关键: 干小麦是整数物品, 而单位耗量 (≈16/瓶/年) 远小于 BE 每次唤醒 (每 {@link BrewerConstants#CELLAR_SETTLE_INTERVAL_TICKS}
 * tick ≈ 5 秒) 的应耗量。若每次唤醒就 ceil 取整扣 1, 会几秒烧一颗、天耗上万, 完全失真。故用【小数燃料债累加器】:
 * 每步把应耗 (含老酒递增) 以小数累加进债, 仅当债跨过整数才扣相应整数颗干小麦, 小数余债跨结算保留。
 *
 * 每支酒以已满的整年份定价，跨酒龄周年时更新费率。所有瓶共用燃料，同时增龄至预算耗尽，
 * 其余现实时间按断粮衰退。这样同一月相和库存下，在线 tick 和区块重载共用相同的价格边界。
 */
public final class CellarSettle {

    private CellarSettle() {
    }

    /** 同年份的一栈酒；瓶数参与耗粮，年份和变质状态仍由整栈共享。 */
    public record BottleState(double vintage, boolean spoiled, int count) {
    }

    /**
     * 结算结果: 各瓶新状态 (与入参同序同长) + 本次扣的整数干小麦 + 结余的小数燃料债 (调用方回存, 下次传入)。
     *
     * @param fuelDebt 结余的小数燃料债, 恒落在 [0,1) (F027: 燃料债按当步可支付预算截断增龄比例, 不再无界累加;
     *                 见类 javadoc 的不变式说明)
     */
    public record Result(List<BottleState> bottles, int fuelConsumed, double fuelDebt) {
    }

    /**
     * 结算一段现实挂钟差对一组在槽酒瓶的陈酿/保鲜/变质影响。
     *
     * @param bottles       各酒槽当前状态 (空槽不应传入: 取出即冻结, 由调用方剔除)
     * @param elapsedMillis 自上次结算以来的现实挂钟毫秒差 (&lt;=0 视为无推进, 原样返回, 零耗燃料、债不变)
     * @param fuelAvailable 燃料槽现有干小麦数 (共享燃料池上限)
     * @param moonPhase     原版当前月相 (0=满月, 触发 FULL_MOON_BONUS)
     * @param fuelDebtIn    上次结余的小数燃料债 (首次传 0)
     * @return 各瓶新状态 + 本次扣的整数干小麦 + 结余小数燃料债
     */
    public static Result settle(List<BottleState> bottles, long elapsedMillis, int fuelAvailable,
                                int moonPhase, double fuelDebtIn) {
        if (bottles == null) {
            throw new IllegalArgumentException("bottles must not be null");
        }
        // 可变工作副本 (逐步累积 vintage / spoiled); 入参不被修改。
        List<BottleState> working = new ArrayList<>(bottles);
        double debt = Math.max(0.0D, fuelDebtIn);

        if (elapsedMillis <= 0L) {
            return new Result(working, 0, debt);
        }

        int fuel = Math.max(0, fuelAvailable);
        double ageRate = VintageClock.applyMoonBonus(1.0D, moonPhase);
        double remaining = VintageClock.vintageYearsFromMillis(elapsedMillis) * ageRate;
        double basePrice = BrewerConfig.DRIED_WHEAT_PER_BOTTLE_YEAR.get();
        double quadraticPrice = BrewerConfig.FUEL_QUAD_COEF.get();

        while (remaining > 0 && fuel > debt) {
            double segment = remaining;
            double price = 0;
            boolean hasWine = false;
            for (BottleState bottle : working) {
                if (bottle.spoiled()) continue;
                hasWine = true;
                double year = Math.floor(bottle.vintage());
                segment = Math.min(segment, 1.0D - (bottle.vintage() - year));
                price += bottle.count() * (basePrice + quadraticPrice * year * year);
            }
            if (!hasWine) {
                return new Result(working, 0, debt);
            }
            double budget = fuel - debt;
            boolean exhaustsFuel = segment * price >= budget;
            double paidYears = exhaustsFuel ? budget / price : segment;
            for (int i = 0; i < working.size(); i++) {
                BottleState bottle = working.get(i);
                if (!bottle.spoiled()) {
                    working.set(i, new BottleState(bottle.vintage() + paidYears, false, bottle.count()));
                }
            }
            remaining -= paidYears;
            if (exhaustsFuel) {
                debt = fuel;
                break;
            }
            debt += paidYears * price;
        }

        if (remaining > 0) {
            decayUnspoiled(working, remaining / ageRate * BrewerConfig.SPOILAGE_DECAY_YEARS_PER_DAY.get());
        }
        // 高频小步累加可能比实际整颗数少几个浮点尾数。保留正常小数债，只对接近整数的结果取整。
        double nearest = Math.rint(debt);
        if (nearest >= 1 && Math.abs(nearest - debt) <= 1.0e-10D) debt = nearest;
        int consumed = Math.min((int) Math.floor(debt), fuel);
        return new Result(working, consumed, debt - consumed);
    }

    /** 对全部未变质瓶倒扣给定的绝对年份量 (已按衰退速率×天数算好), 扣到 0 即变质。 */
    private static void decayUnspoiled(List<BottleState> working, double decayYears) {
        for (int i = 0; i < working.size(); i++) {
            BottleState b = working.get(i);
            if (b.spoiled()) {
                continue;
            }
            double decayed = b.vintage() - decayYears;
            working.set(i, decayed <= 0.0D ? new BottleState(0.0D, true, b.count()) : new BottleState(decayed, false, b.count()));
        }
    }
}

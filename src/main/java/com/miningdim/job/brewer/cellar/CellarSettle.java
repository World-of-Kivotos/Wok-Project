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
 * F027 修复 (燃料债截断): 原实现只要当步燃料槽非空 (fuelLeft&gt;0) 就让该步全部未变质瓶按满额增龄, 应耗全额
 * 记进【无上限】的债, 债可以远超实际持有的燃料 —— 一次区块卸载或离线期间只要断粮一天, 债就永久废掉酒窖 (旁路:
 * 离线时插 1 颗干小麦即可让 fuelLeft&gt;0, 骗过门控买满整天陈酿)。现按【当步可支付燃料预算截断实际增龄比例
 * (agedFraction)】结算, 未获支付的部分直接按衰退处理, 不再欠账:
 *
 *  1) fuelLeft&lt;=0: 整步走衰退分支 (未变质瓶按 {@link BrewerConstants#SPOILAGE_DECAY_YEARS_PER_DAY} 倒扣)。
 *  2) 算全部未变质瓶的满额应耗 fullDemand (含老酒递增)。fullDemand&lt;=0 (无未变质瓶): 本步无事可做。
 *  3) budget = fuelLeft - debt (旧债先占本步预算); budget&lt;=0: 用现有燃料尽量偿还旧债, 再整步走衰退分支。
 *  4) agedFraction = min(1, budget / fullDemand): 按预算能覆盖的比例增龄, 债累加 fullDemand×agedFraction,
 *     随即扣对应整数燃料 (债跨整数部分)。
 *  5) agedFraction&lt;1 (预算不足以覆盖满额应耗): 未覆盖的时间比例 (1-agedFraction) 按衰退速率倒扣 vintage。
 *
 * 不变式: 上述算法保证结算后 {@code debt} 恒落在 [0,1) —— agedFraction=1 时债只留小数部分; agedFraction&lt;1
 * 时 debt 在加满后精确等于 fuelLeft (int), 扣款后归零。故【不能】像"断粮时把 debt 清零"那样处理: 那等于允许
 * 离线时随手插 1 颗干小麦就买满整天陈酿, 把本缺陷的旁路合法化, 而不是修掉它。旧档遗留的 &gt;=1 债由
 * {@link com.miningdim.job.brewer.cellar.WineCellarBlockEntity#load} 侧的迁移逻辑处理 (视为缺陷产物丢弃,
 * 不强行让玩家偿还)。
 *
 * 窖级保鲜门控 (而非逐瓶配给): 一步内的预算按全部未变质瓶【统一比例】增龄/衰退, 不逐瓶单独配给。
 *
 * 离散推进: 把 elapsedMillis 切成若干"现实天"整步 + 不足一天的余步逐步推进 —— 因燃料应耗随 vintage 递增 (老酒烧
 * 钱凶), 分步使 vintage 在步间累积后用更高 vintage 计费, 贴合"推高年份边际收益被燃料成本吃掉"的经济封顶设计。
 */
public final class CellarSettle {

    private CellarSettle() {
    }

    /** 单瓶陈酿状态 (vintage 年份, spoiled 是否变质)。 */
    public record BottleState(double vintage, boolean spoiled) {
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

        int fuelLeft = Math.max(0, fuelAvailable);
        int fuelConsumed = 0;
        long remaining = elapsedMillis;

        while (remaining > 0L) {
            // 本步时长: 满一天取一天, 否则取剩余 (余步)。
            long stepMillis = Math.min(remaining, BrewerConstants.MILLIS_PER_VINTAGE_YEAR);
            double baseYears = VintageClock.vintageYearsFromMillis(stepMillis);
            double stepYears = VintageClock.applyMoonBonus(baseYears, moonPhase); // 满月加成后的年增量。
            double stepDays = baseYears; // MILLIS_PER_VINTAGE_YEAR 既是 1 年又是 1 天, 故天数=未加成年增量。

            if (fuelLeft <= 0) {
                // 1) 燃料槽见底: 整步倒扣 vintage (不欠账 —— 无粮就是无粮)。
                decayUnspoiled(working, BrewerConfig.SPOILAGE_DECAY_YEARS_PER_DAY.get() * stepDays);
                remaining -= stepMillis;
                continue;
            }

            // 2) 全部未变质瓶的满额应耗 (含老酒二次递增); v 取该瓶步初 vintage。
            double demandPerYear = 0.0D;
            for (BottleState b : working) {
                if (b.spoiled()) {
                    continue;
                }
                demandPerYear += BrewerConfig.DRIED_WHEAT_PER_BOTTLE_YEAR.get()
                        + BrewerConfig.FUEL_QUAD_COEF.get() * b.vintage() * b.vintage();
            }
            double fullDemand = demandPerYear * stepYears;

            if (fullDemand <= 0.0D) {
                // 3) 无未变质瓶可陈: 本步无事可做。
                remaining -= stepMillis;
                continue;
            }

            // 4) 当步可用于增龄的燃料预算 (旧债先占额)。
            double budget = fuelLeft - debt;
            if (budget <= 0.0D) {
                // 旧债已吃满本步全部燃料: 尽量偿债, 本步不增龄, 按整步衰退处理。
                int burn = Math.min((int) Math.floor(debt), fuelLeft);
                if (burn > 0) {
                    fuelLeft -= burn;
                    fuelConsumed += burn;
                    debt -= burn;
                }
                decayUnspoiled(working, BrewerConfig.SPOILAGE_DECAY_YEARS_PER_DAY.get() * stepDays);
                remaining -= stepMillis;
                continue;
            }

            // 5) 按预算截断的实际增龄比例。
            double agedFraction = Math.min(1.0D, budget / fullDemand);
            for (int i = 0; i < working.size(); i++) {
                BottleState b = working.get(i);
                if (b.spoiled()) {
                    continue;
                }
                working.set(i, new BottleState(b.vintage() + stepYears * agedFraction, false));
            }
            debt += fullDemand * agedFraction;

            // 债跨过整数才扣相应整数颗 (上限为槽内现存); 小数余债跨步保留 (恒落 [0,1), 见类 javadoc 不变式)。
            int burn = Math.min((int) Math.floor(debt), fuelLeft);
            if (burn > 0) {
                fuelLeft -= burn;
                fuelConsumed += burn;
                debt -= burn;
            }

            if (agedFraction < 1.0D) {
                // 预算不足以覆盖满额应耗: 未覆盖的时间比例按衰退速率倒扣, 而不是继续欠账。
                double decayDays = stepDays * (1.0D - agedFraction);
                decayUnspoiled(working, BrewerConfig.SPOILAGE_DECAY_YEARS_PER_DAY.get() * decayDays);
            }

            remaining -= stepMillis;
        }

        return new Result(working, fuelConsumed, debt);
    }

    /** 对全部未变质瓶倒扣给定的绝对年份量 (已按衰退速率×天数算好), 扣到 0 即变质。 */
    private static void decayUnspoiled(List<BottleState> working, double decayYears) {
        for (int i = 0; i < working.size(); i++) {
            BottleState b = working.get(i);
            if (b.spoiled()) {
                continue;
            }
            double decayed = b.vintage() - decayYears;
            working.set(i, decayed <= 0.0D ? new BottleState(0.0D, true) : new BottleState(decayed, false));
        }
    }
}

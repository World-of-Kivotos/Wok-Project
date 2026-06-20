package com.miningdim.job.brewer.cellar;

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
 * 窖级保鲜门控 (而非逐瓶配给): 某步燃料槽尚有干小麦 (fuelLeft &gt; 0) 则该步全部未变质瓶增龄并累计燃料债; 燃料
 * 槽已空则该步全部未变质瓶按 {@link BrewerConstants#SPOILAGE_DECAY_YEARS_PER_DAY} 倒扣 vintage、扣到 0 即变质。
 * "断粮即陈酿暂停 -&gt; 衰退 -&gt; 变质"以"燃料槽是否见底"为准, 符合设计;一步内的小数欠债跨步保留, 不造成逐瓶半态。
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

    /** 结算结果: 各瓶新状态 (与入参同序同长) + 本次扣的整数干小麦 + 结余的小数燃料债 (调用方回存, 下次传入)。 */
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

            boolean hasFuel = fuelLeft > 0;
            if (hasFuel) {
                // 窖内有粮: 全部未变质瓶增龄, 应耗 (含老酒递增) 以小数累加进债。
                for (int i = 0; i < working.size(); i++) {
                    BottleState b = working.get(i);
                    if (b.spoiled()) {
                        continue; // 已变质: 不增龄不耗燃料。
                    }
                    // 该瓶每年实耗 = 基础 + 二次系数×vintage² (超线性: 嫩酒便宜、高年份指数爆炸)。
                    double fuelPerYear = BrewerConstants.DRIED_WHEAT_PER_BOTTLE_YEAR
                            + BrewerConstants.FUEL_QUAD_COEF * b.vintage() * b.vintage();
                    debt += fuelPerYear * stepYears;
                    working.set(i, new BottleState(b.vintage() + stepYears, false));
                }
                // 债跨过整数才扣相应整数颗 (上限为槽内现存); 小数余债跨步保留。
                int burn = Math.min((int) Math.floor(debt), fuelLeft);
                if (burn > 0) {
                    fuelLeft -= burn;
                    fuelConsumed += burn;
                    debt -= burn;
                }
            } else {
                // 燃料槽见底: 全部未变质瓶倒扣 vintage, 扣到 0 即变质。
                for (int i = 0; i < working.size(); i++) {
                    BottleState b = working.get(i);
                    if (b.spoiled()) {
                        continue;
                    }
                    double decayed = b.vintage() - BrewerConstants.SPOILAGE_DECAY_YEARS_PER_DAY * stepDays;
                    working.set(i, decayed <= 0.0D ? new BottleState(0.0D, true) : new BottleState(decayed, false));
                }
            }

            remaining -= stepMillis;
        }

        return new Result(working, fuelConsumed, debt);
    }
}

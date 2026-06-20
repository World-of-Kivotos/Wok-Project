package com.miningdim.job.brewer.station;

import com.miningdim.job.brewer.WineQuality;
import net.minecraft.util.RandomSource;

/**
 * 基酒品质骰子 (酿酒师 阶段 3 纯逻辑)。酿酒台产出基酒时按操作者酿酒师等级 (1-10) roll 五档品质, 等级越高越可能
 * 出超凡/闪耀, 低等级几乎只出低/中级。
 *
 * 把权重曲线抽成可测纯函数 {@link #weights(int)} (返回五档 double[], 之和恒 = 1), {@link #roll} 据此按
 * RandomSource 抽样。GameTest 直接断言权重 (低等级闪耀≈0、满级闪耀显著>0、各级之和=1), 不需起世界。
 *
 * 曲线设计 (等级 t∈[1,10] 归一化 p = (t-1)/9 ∈[0,1]): 低/中/高/超凡/闪耀 五档权重随 p 平滑迁移 ——
 *  - LOW/MID 随 p 增大而衰减 (新手主出低中);
 *  - HIGH 中段隆起;
 *  - SUPERB 高段抬升;
 *  - BRILLIANT 仅在高 p 才显著 (用 p^3 强压低段, 满级才有可观闪耀概率)。
 * 各档以未归一化分值给出, 末尾整体除以总分归一, 保证之和 = 1 且每档 >= 0 (合法概率分布)。
 */
public final class BrewQualityRoller {

    private BrewQualityRoller() {
    }

    /** 品质档数 (与 {@link WineQuality} 五档对齐: LOW/MID/HIGH/SUPERB/BRILLIANT)。 */
    public static final int TIERS = 5;

    private static final int IDX_LOW = 0;
    private static final int IDX_MID = 1;
    private static final int IDX_HIGH = 2;
    private static final int IDX_SUPERB = 3;
    private static final int IDX_BRILLIANT = 4;

    /** 等级合法区间 (与 IJobService.level 契约一致: 1-10)。 */
    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 10;

    /** 闪耀【仅 L10】可出 (<10 恒 0); 此为 L10 非满月的原始权重, 归一后约 5% (闪耀难做)。 */
    public static final double BRILLIANT_L10_WEIGHT = 0.158D;
    /** 满月酿造时闪耀原始权重的乘子 (归一后约 9.5%, 近翻倍; 蹭潮汐 Tide 满月主题, 逼玩家挑满月开酿)。 */
    public static final double FULL_MOON_BRILLIANT_MULT = 2.0D;

    /** 非满月权重 (便利重载)。 */
    public static double[] weights(int level) {
        return weights(level, false);
    }

    /**
     * 给定酿酒师等级 (1-10) + 是否满月的五档品质权重 (索引 = {@link WineQuality#ordinal()}; 之和 = 1, 每档 >= 0)。
     * 越界等级 clamp 到 [1,10] (容错, 不抛 —— level() 契约保证 1-10, 此为双保险)。
     *
     * 未归一化分值 (p = (level-1)/9):
     *  - LOW      = 1 - 0.9p        (新手主力, 满级仍留少量低酒概率)
     *  - MID      = 0.6 + 0.2p      (中段稳定主力)
     *  - HIGH     = 1.2p            (随等级线性抬升)
     *  - SUPERB   = 0.9p^2          (高段二次抬升)
     *  - BRILLIANT= 仅 L10 可出 (lv<10 恒 0); L10 = {@link #BRILLIANT_L10_WEIGHT} ×(满月 ? {@link #FULL_MOON_BRILLIANT_MULT} : 1)。
     *               归一后 L10 非满月 ≈ 5%, 满月 ≈ 9.5% (闪耀难做 + 满月翻倍)。
     */
    public static double[] weights(int level, boolean fullMoon) {
        int lv = Math.max(MIN_LEVEL, Math.min(MAX_LEVEL, level));
        double p = (lv - MIN_LEVEL) / (double) (MAX_LEVEL - MIN_LEVEL); // [0,1]

        double[] raw = new double[TIERS];
        raw[IDX_LOW] = 1.0D - 0.9D * p;
        raw[IDX_MID] = 0.6D + 0.2D * p;
        raw[IDX_HIGH] = 1.2D * p;
        raw[IDX_SUPERB] = 0.9D * p * p;
        raw[IDX_BRILLIANT] = (lv >= MAX_LEVEL)
                ? BRILLIANT_L10_WEIGHT * (fullMoon ? FULL_MOON_BRILLIANT_MULT : 1.0D)
                : 0.0D;

        double sum = 0.0D;
        for (double w : raw) {
            sum += w;
        }
        // sum 恒 > 0 (MID 项 >= 0.6, LOW 项 >= 0.1), 故除法安全, 无需兜底分支。
        for (int i = 0; i < TIERS; i++) {
            raw[i] /= sum;
        }
        return raw;
    }

    /**
     * 按等级权重抽一档品质 (纯函数, 随机源由调用方传入便于确定性测试)。在 [0,1) 抽一个数, 沿五档累积概率落入
     * 对应区间。浮点累积末档兜底返回 BRILLIANT (防累积和因浮点误差略小于 1 时漏档)。
     */
    public static WineQuality roll(int brewerLevel, boolean fullMoon, RandomSource random) {
        if (random == null) {
            throw new IllegalArgumentException("random source must not be null");
        }
        double[] w = weights(brewerLevel, fullMoon);
        double r = random.nextDouble();
        double cumulative = 0.0D;
        WineQuality[] order = {
                WineQuality.LOW, WineQuality.MID, WineQuality.HIGH, WineQuality.SUPERB, WineQuality.BRILLIANT};
        for (int i = 0; i < TIERS; i++) {
            cumulative += w[i];
            if (r < cumulative) {
                return order[i];
            }
        }
        return WineQuality.BRILLIANT;
    }
}

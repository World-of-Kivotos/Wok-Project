package com.miningdim.job.agent;

import com.miningdim.champion.ChampionSpawnPolicy;

/**
 * 悬赏奖励数值表 (SpecialAgent_Job_DesignSpec 12.1 第 2 条, 2026-09-30 拍板"保守"档)。
 *
 * 拍板区间: 日常每单 2,000~6,000 CP; 周常每单 15,000~30,000 CP + 8~15 青辉石; XP 沿用 8.1 (日常 400~1,500、
 * 周常 2,500~6,000)。区间两端对应目标星级 X 的两端: X = 1 取下限, X = {@link #TOP_STAR} (自然刷出的最高星级 9★)
 * 取上限, 中间线性插值 —— 奖励只随"目标有多难打"走, 讨伐只数 N 由 {@link BountyGenerator} 按星级配好, 使同一星级
 * 的几种悬赏工作量相当。10★ 只出自世界 BOSS, 不进日常/周常, 世界 BOSS 讨伐令单独一组数。
 *
 * 选"保守"的理由 (拍板时已告知): 信用点三大 sink 当前失效, 新 faucet 越小越安全; 普通日常任务约 1 万 CP/天,
 * 悬赏量级与之同档。悬赏信用点走全服衰减主闸 (十一章), 不像任务那样开独立键。
 *
 * 纯值 record; 运行期由 {@link AgentBountyConfig#table()} 读 miningdim-agent.toml 构造, GameTest 用 {@link #DEFAULTS}。
 */
public record BountyRewardTable(
        long dailyCreditMin, long dailyCreditMax,
        long dailyXpMin, long dailyXpMax,
        long weeklyCreditMin, long weeklyCreditMax,
        long weeklyXpMin, long weeklyXpMax,
        long weeklyAzureMin, long weeklyAzureMax,
        long worldBossCredit, long worldBossXp, long worldBossAzure) {

    /** 插值上端对应的目标星级: 困难矿区自然刷出的上限 ({@code ChampionSpawnPolicy.HARD_MAX_STAR})。 */
    public static final int TOP_STAR = ChampionSpawnPolicy.HARD_MAX_STAR;

    /** 拍板默认值 (保守档)。 */
    public static final BountyRewardTable DEFAULTS = new BountyRewardTable(
            2_000L, 6_000L,
            400L, 1_500L,
            15_000L, 30_000L,
            2_500L, 6_000L,
            8L, 15L,
            30_000L, 6_000L, 15L);

    public BountyRewardTable {
        requireRange(dailyCreditMin, dailyCreditMax, "dailyCredit");
        requireRange(dailyXpMin, dailyXpMax, "dailyXp");
        requireRange(weeklyCreditMin, weeklyCreditMax, "weeklyCredit");
        requireRange(weeklyXpMin, weeklyXpMax, "weeklyXp");
        requireRange(weeklyAzureMin, weeklyAzureMax, "weeklyAzure");
        if (worldBossCredit < 0L || worldBossXp < 0L || worldBossAzure < 0L) {
            throw new IllegalArgumentException("world boss rewards must be >= 0");
        }
    }

    /** 日常信用点 (按百取整, 面板上不出现 2,444 这种零头)。 */
    public long dailyCredit(int star) {
        return roundTo(lerp(dailyCreditMin, dailyCreditMax, star), 100L);
    }

    /** 日常 XP (按十取整)。 */
    public long dailyXp(int star) {
        return roundTo(lerp(dailyXpMin, dailyXpMax, star), 10L);
    }

    public long weeklyCredit(int star) {
        return roundTo(lerp(weeklyCreditMin, weeklyCreditMax, star), 100L);
    }

    public long weeklyXp(int star) {
        return roundTo(lerp(weeklyXpMin, weeklyXpMax, star), 10L);
    }

    public long weeklyAzure(int star) {
        return Math.round(lerp(weeklyAzureMin, weeklyAzureMax, star));
    }

    /** 线性插值: star &lt;= 1 取 min, star &gt;= {@link #TOP_STAR} 取 max。 */
    private static double lerp(long min, long max, int star) {
        int clamped = Math.max(1, Math.min(star, TOP_STAR));
        double t = (double) (clamped - 1) / (double) (TOP_STAR - 1);
        return min + (max - min) * t;
    }

    private static long roundTo(double value, long unit) {
        return Math.round(value / unit) * unit;
    }

    private static void requireRange(long min, long max, String name) {
        if (min < 0L || max < min) {
            throw new IllegalArgumentException(name + " range invalid: [" + min + ", " + max + "]");
        }
    }
}

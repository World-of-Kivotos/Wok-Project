package com.miningdim.champion.reward;

import com.miningdim.champion.StarRank;

/**
 * 精英怪击杀奖励池标定纯逻辑 (ChampionStarAffix spec 第十一章奖励与经济闸)。按初始星级把固定信用点总池 (raw)
 * 与青辉石 PvE 掉落量标定: 星级越高池越大。纯函数, 不碰世界/经济门面 —— 产出 raw 由 b 阶段
 * {@code ContributionPool.distribute} 瓜分后逐玩家喂 {@code grantDaily} 并入信用点衰减主闸 (不自开印钞口)。
 *
 * 数值口径 (spec 第十一章标 PENDING, config 暴露前本表为唯一权威硬值): 信用点固定池按星级线性抬升, 青辉石
 * 仅 6★+ 掉落 (1-5★ 只掉信用点)。具体曲线 spec 留 config, 此处给与设计哲学对齐的初值 (低星点缀奖励, 高星
 * 世界 BOSS 级奖励但经主闸衰减不破每日天花板)。一旦 ConfigSystem 暴露 champion.reward.* 应改读配置 (留待接线)。
 */
public final class ChampionReward {

    private ChampionReward() {
    }

    /** 每星固定信用点总池基数 (raw, 瓜分前): 1★=600 起, 每星 +600, 10★=6000。经主闸衰减后实发递减。 */
    public static final long CREDIT_POOL_PER_STAR = 600L;

    /** 青辉石掉落起始星级 (config 默认 6★; 1-5★ 不掉青辉石)。 */
    public static final int AZURE_MIN_STAR = StarRank.CUSTOM_BLOOD_POOL_MIN_STAR;

    /** 6★ 青辉石基础掉落量; 每高一星 +2 (6★=2 … 10★=10)。单设日产软上限由 b 阶段经济层另控。 */
    public static final long AZURE_BASE_AT_MIN_STAR = 2L;
    public static final long AZURE_PER_STAR_ABOVE_MIN = 2L;

    /**
     * 某星级击杀的固定信用点总池 (raw): star × {@link #CREDIT_POOL_PER_STAR}。瓜分前的池总量, 合格者按有效
     * 伤害加权分得后逐人经 grantDaily 入主闸 (实发随当日累计衰减)。
     *
     * @param star 初始星级 (1-10)
     * @return 固定信用点总池 raw
     */
    public static long creditPoolRaw(int star) {
        requireStar(star);
        return star * CREDIT_POOL_PER_STAR;
    }

    /** 该星是否掉青辉石 (≥6★)。 */
    public static boolean dropsAzure(int star) {
        requireStar(star);
        return star >= AZURE_MIN_STAR;
    }

    /**
     * 某星级青辉石掉落量 (6★+): base + (star-6) × perStar; &lt;6★ 返 0。单设日产软上限不在本层 (经济层控)。
     *
     * @param star 初始星级 (1-10)
     * @return 青辉石掉落量 (&lt;6★ = 0)
     */
    public static long azureDrop(int star) {
        requireStar(star);
        if (star < AZURE_MIN_STAR) {
            return 0L;
        }
        return AZURE_BASE_AT_MIN_STAR + (long) (star - AZURE_MIN_STAR) * AZURE_PER_STAR_ABOVE_MIN;
    }

    private static void requireStar(int star) {
        if (star < StarRank.MIN_STAR || star > StarRank.MAX_STAR) {
            throw new IllegalArgumentException("star out of [1,10]: " + star);
        }
    }
}

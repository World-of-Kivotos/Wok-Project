package com.miningdim.champion;

import com.miningdim.core.Difficulty;
import net.minecraft.util.RandomSource;

/**
 * 精英怪生成策略纯逻辑 (ChampionStarAffix spec 第十二章生成接入 + 第十三章 PENDING 难度->星级分布)。
 *
 * 决定两件事, 全为纯函数 (不碰 Champions / 世界, GameTest 直接断言):
 *  (1) 某只刷出的怪是否升格为冠军 ({@link #shouldPromote}): 按矿洞难度档给一个升格概率, 杂兵海里点缀精英。
 *  (2) 升格后掷几星 ({@link #rollStar}): 按难度档给一个 [minStar, maxStar] 区间均匀掷星。
 *
 * 难度->星级映射 (spec 第十三章标 PENDING, config 暴露前本表为唯一权威硬值, 与设计哲学对齐: EASY 矿洞只出
 * 低星强化杂兵, HARD 矿洞才有高星世界 BOSS 量级):
 *   EASY   : 升格率 6%,  星级 [1, 3]
 *   MEDIUM : 升格率 10%, 星级 [3, 6]
 *   HARD   : 升格率 15%, 星级 [5, 10]
 * 区间相邻档有重叠 (难度梯度平滑, 非硬跳)。一旦 ConfigSystem 暴露 champion.* 键应改为读配置 (留待接线)。
 *
 * 概率/区间硬值落本类 (非 EconomyConstants/ChampionRedlines): 它们是生成接入的策略量, 不是红线阈值,
 * 与八红线 (封顶) 语义不同, 故独立成表。
 */
public final class ChampionSpawnPolicy {

    private ChampionSpawnPolicy() {
    }

    // ---- 难度档升格概率 (杂兵中点缀精英的比例) ----

    /** EASY 矿洞升格率 (低星强化杂兵点缀)。 */
    public static final double PROMOTE_CHANCE_EASY = 0.06D;

    /** MEDIUM 矿洞升格率。 */
    public static final double PROMOTE_CHANCE_MEDIUM = 0.10D;

    /** HARD 矿洞升格率 (高星世界 BOSS 量级)。 */
    public static final double PROMOTE_CHANCE_HARD = 0.15D;

    // ---- 难度档星级区间 [min, max] (含两端) ----

    public static final int EASY_MIN_STAR = 1;
    public static final int EASY_MAX_STAR = 3;
    public static final int MEDIUM_MIN_STAR = 3;
    public static final int MEDIUM_MAX_STAR = 6;
    public static final int HARD_MIN_STAR = 5;
    public static final int HARD_MAX_STAR = 10;

    /** 某难度档的升格概率 [0,1]。 */
    public static double promoteChance(Difficulty difficulty) {
        if (difficulty == null) {
            throw new IllegalArgumentException("difficulty must not be null");
        }
        return switch (difficulty) {
            case EASY -> PROMOTE_CHANCE_EASY;
            case MEDIUM -> PROMOTE_CHANCE_MEDIUM;
            case HARD -> PROMOTE_CHANCE_HARD;
        };
    }

    /** 某难度档的星级区间下界。 */
    public static int minStar(Difficulty difficulty) {
        if (difficulty == null) {
            throw new IllegalArgumentException("difficulty must not be null");
        }
        return switch (difficulty) {
            case EASY -> EASY_MIN_STAR;
            case MEDIUM -> MEDIUM_MIN_STAR;
            case HARD -> HARD_MIN_STAR;
        };
    }

    /** 某难度档的星级区间上界。 */
    public static int maxStar(Difficulty difficulty) {
        if (difficulty == null) {
            throw new IllegalArgumentException("difficulty must not be null");
        }
        return switch (difficulty) {
            case EASY -> EASY_MAX_STAR;
            case MEDIUM -> MEDIUM_MAX_STAR;
            case HARD -> HARD_MAX_STAR;
        };
    }

    /**
     * 掷一次升格判定: rng &lt; promoteChance(difficulty) 即升格为冠军。
     *
     * @param difficulty 矿洞难度
     * @param rng        随机源 (服务端 level.random)
     * @return 是否升格
     */
    public static boolean shouldPromote(Difficulty difficulty, RandomSource rng) {
        if (rng == null) {
            throw new IllegalArgumentException("rng must not be null");
        }
        return rng.nextDouble() < promoteChance(difficulty);
    }

    /**
     * 在该难度档星级区间 [min, max] 内均匀掷星。
     *
     * @param difficulty 矿洞难度
     * @param rng        随机源
     * @return 星级 (∈ [minStar, maxStar], 含两端)
     */
    public static int rollStar(Difficulty difficulty, RandomSource rng) {
        if (rng == null) {
            throw new IllegalArgumentException("rng must not be null");
        }
        int lo = minStar(difficulty);
        int hi = maxStar(difficulty);
        return lo + rng.nextInt(hi - lo + 1);
    }
}

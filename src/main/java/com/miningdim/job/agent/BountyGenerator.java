package com.miningdim.job.agent;

import com.miningdim.champion.AffixPool;
import com.miningdim.champion.AffixRoller;
import com.miningdim.champion.AffixSelection;
import com.miningdim.champion.StarRank;
import com.miningdim.champion.WorldBoss;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 悬赏掷取纯逻辑 (SpecialAgent_Job_DesignSpec 10.5 + 12.1 第 1 条)。给定周期、干员等级与随机源, 掷出一张张可接悬赏。
 *
 * <b>目标星级 X</b>: 上限 = min(可接星级 L, 自然刷出最高 9★) —— 10★ 只出自管理员召唤的世界 BOSS, 放进日常/周常就是
 * 没人能按时完成的死单。下限日常往回放 3 档、周常放 2 档, 让同一块板上有轻有重可挑, 周常整体偏难。
 *
 * <b>讨伐只数 N</b>: 按 X 配表 ({@link #starKillCount}), 低星多只、高星一只, 使一张悬赏的工作量大致随奖励同涨。
 * 6★ 以上按约 4 人小队设计 (2026-09-27 拍板), 日常在这一档只要一只。
 *
 * <b>词条类</b>: "讨伐 N 只 ≥X★ 且带某池词条的精英"。精英词条是逐池贪心掷的 (生存 -&gt; 战斗 -&gt; 机动 -&gt; 技能),
 * 低星的总词条上限很小, 排在后面的池经常一条也分不到 —— 写死"技能池 3★ 起"之类的门会掷出几乎完成不了的单。
 * 故这里<b>直接拿精英自己的掷词条器模拟</b>每星每池的出现率 ({@link #poolPresenceRate}), 只掷出现率不低于
 * {@link #MIN_POOL_PRESENCE} 的组合; 精英那边改了预算或掷法, 这张表自动跟着变, 不会与真实生成漂移。
 * 词条类只数比同星星级类少 (要先找到目标, 扫描面板正是干这个的)。
 *
 * 纯逻辑, 无世界引用; 随机源由调用方注入, GameTest 用固定种子断言。
 */
public final class BountyGenerator {

    /** 日常可接悬赏数 = 当前槽位 + 此数, 给玩家留挑选余地 (接满槽位后剩下的不能再接)。 */
    public static final int DAILY_EXTRA_OFFERS = 2;

    /** 周常可接悬赏数 = 当前槽位 + 此数。 */
    public static final int WEEKLY_EXTRA_OFFERS = 1;

    /** 掷一张悬赏时尝试掷成词条类的概率 (没有合格的池时退回星级类)。 */
    public static final double AFFIX_OFFER_CHANCE = 0.4D;

    /** 词条类悬赏要求该池在目标星级的精英身上至少有这么高的出现率。 */
    public static final double MIN_POOL_PRESENCE = 0.35D;

    /** 日常 X 下限相对上限的回退档数。 */
    private static final int DAILY_STAR_SPREAD = 3;

    /** 周常 X 下限相对上限的回退档数。 */
    private static final int WEEKLY_STAR_SPREAD = 2;

    /** 同一块板上尽量不出现重复悬赏的重掷次数。 */
    private static final int DEDUPE_ATTEMPTS = 8;

    /** 星级类讨伐只数, 下标 = X-1 (X = 1..9)。 */
    private static final int[] DAILY_STAR_COUNT = {4, 3, 3, 2, 2, 1, 1, 1, 1};
    private static final int[] WEEKLY_STAR_COUNT = {15, 12, 10, 8, 6, 4, 3, 2, 2};

    /** 模拟出现率时每个星级掷的样本数。 */
    private static final int PRESENCE_SAMPLES = 400;

    /** 模拟用固定种子: 结果是一张确定的表, 同一版本的服务端每次启动都一样。 */
    private static final long PRESENCE_SEED = 0x5EA1_B0D7L;

    private BountyGenerator() {
    }

    /**
     * 掷若干张可接悬赏。
     *
     * @param period     DAILY 或 WEEKLY (EVENT 不走掷取)
     * @param agentLevel 掷取时的干员等级 (决定目标星级上限)
     * @param count      要掷的张数
     * @param idPrefix   id 前缀 (含周期戳, 保证与本期已有悬赏不重名)
     * @param firstIndex 本批第一张的序号
     * @param existing   本期板上已有的悬赏 (去重参照, 不修改)
     * @param table      奖励表
     * @param rng        随机源
     */
    public static List<BountyDefinition> draw(BountyDefinition.Period period, int agentLevel, int count,
                                              String idPrefix, int firstIndex, List<BountyDefinition> existing,
                                              BountyRewardTable table, RandomSource rng) {
        if (period == BountyDefinition.Period.EVENT) {
            throw new IllegalArgumentException("EVENT bounties are not drawn");
        }
        List<BountyDefinition> out = new ArrayList<>(Math.max(0, count));
        List<BountyDefinition> seen = new ArrayList<>(existing);
        for (int i = 0; i < count; i++) {
            String id = idPrefix + "-" + (firstIndex + i);
            BountyDefinition pick = null;
            for (int attempt = 0; attempt < DEDUPE_ATTEMPTS; attempt++) {
                pick = drawOne(period, agentLevel, id, table, rng);
                if (!containsSameTarget(seen, pick)) {
                    break;
                }
            }
            out.add(pick);
            seen.add(pick);
        }
        return out;
    }

    /** 某等级某周期的目标星级上限。 */
    public static int topStar(int agentLevel) {
        return Math.min(AgentSkillTable.maxBountyStar(agentLevel), BountyRewardTable.TOP_STAR);
    }

    /** 某等级某周期的目标星级下限。 */
    public static int bottomStar(BountyDefinition.Period period, int agentLevel) {
        int spread = period == BountyDefinition.Period.DAILY ? DAILY_STAR_SPREAD : WEEKLY_STAR_SPREAD;
        return Math.max(StarRank.MIN_STAR, topStar(agentLevel) - spread);
    }

    private static BountyDefinition drawOne(BountyDefinition.Period period, int agentLevel, String id,
                                            BountyRewardTable table, RandomSource rng) {
        int lo = bottomStar(period, agentLevel);
        int hi = topStar(agentLevel);
        int star = lo + rng.nextInt(hi - lo + 1);

        AffixPool pool = null;
        if (rng.nextDouble() < AFFIX_OFFER_CHANCE) {
            List<AffixPool> eligible = eligiblePools(star);
            if (!eligible.isEmpty()) {
                pool = eligible.get(rng.nextInt(eligible.size()));
            }
        }
        int starCount = starKillCount(period, star);
        boolean daily = period == BountyDefinition.Period.DAILY;
        if (pool == null) {
            return new BountyDefinition(id, period, BountyDefinition.TargetType.KILL_STAR_AT_LEAST, star, null,
                    starCount,
                    daily ? table.dailyCredit(star) : table.weeklyCredit(star),
                    daily ? table.dailyXp(star) : table.weeklyXp(star),
                    daily ? 0L : table.weeklyAzure(star));
        }
        return new BountyDefinition(id, period, BountyDefinition.TargetType.KILL_WITH_AFFIX_CATEGORY, star, pool,
                affixKillCount(period, starCount),
                daily ? table.dailyCredit(star) : table.weeklyCredit(star),
                daily ? table.dailyXp(star) : table.weeklyXp(star),
                daily ? 0L : table.weeklyAzure(star));
    }

    /** 星级类讨伐只数。 */
    public static int starKillCount(BountyDefinition.Period period, int star) {
        int[] table = period == BountyDefinition.Period.DAILY ? DAILY_STAR_COUNT : WEEKLY_STAR_COUNT;
        int idx = Math.max(0, Math.min(star, table.length) - 1);
        return table[idx];
    }

    /**
     * 词条类讨伐只数: 日常比同星星级类少一只、周常减半 (向上取整), 都至少一只。少的那部分是"先找到目标"的成本。
     */
    public static int affixKillCount(BountyDefinition.Period period, int starCount) {
        if (period == BountyDefinition.Period.DAILY) {
            return Math.max(1, starCount - 1);
        }
        return Math.max(1, (starCount + 1) / 2);
    }

    /** 某星级下出现率达标、可以掷成词条类悬赏的池。 */
    public static List<AffixPool> eligiblePools(int star) {
        List<AffixPool> out = new ArrayList<>(AffixPool.values().length);
        for (AffixPool pool : AffixPool.values()) {
            if (poolPresenceRate(pool, star) >= MIN_POOL_PRESENCE) {
                out.add(pool);
            }
        }
        return out;
    }

    /** 某星级精英身上至少带一条该池词条的概率 (由精英掷词条器模拟得出, 见类注释)。 */
    public static double poolPresenceRate(AffixPool pool, int star) {
        int clamped = Math.max(StarRank.MIN_STAR, Math.min(star, StarRank.MAX_STAR));
        return PresenceTable.RATES[clamped - 1][pool.ordinal()];
    }

    /**
     * 世界 BOSS 讨伐令 (L8+, 不占槽位, 见 {@link AgentBountyService#onWorldBossKill})。星级门取世界 BOSS 的最低星级,
     * 奖励不随 BOSS 星级分档: 世界 BOSS 本就由管理员按活动安排, 奖池 (贡献池) 已经随星级涨。
     */
    public static BountyDefinition worldBossOrder(BountyRewardTable table) {
        return new BountyDefinition("world_boss", BountyDefinition.Period.EVENT,
                BountyDefinition.TargetType.KILL_WORLD_BOSS, WorldBoss.MIN_STAR, null, 1,
                table.worldBossCredit(), table.worldBossXp(), table.worldBossAzure());
    }

    private static boolean containsSameTarget(List<BountyDefinition> list, BountyDefinition candidate) {
        for (BountyDefinition def : list) {
            if (def.targetType() == candidate.targetType() && def.minStar() == candidate.minStar()
                    && def.targetPool() == candidate.targetPool()) {
                return true;
            }
        }
        return false;
    }

    /** 出现率表, 首次用到时模拟一次 (按需初始化的持有类, 线程安全由类加载保证)。 */
    private static final class PresenceTable {

        static final double[][] RATES = simulate();

        private static double[][] simulate() {
            double[][] rates = new double[StarRank.MAX_STAR][AffixPool.values().length];
            RandomSource rng = RandomSource.create(PRESENCE_SEED);
            for (int star = StarRank.MIN_STAR; star <= StarRank.MAX_STAR; star++) {
                StarRank rank = StarRank.ofStar(star);
                int[] hits = new int[AffixPool.values().length];
                for (int i = 0; i < PRESENCE_SAMPLES; i++) {
                    Set<AffixPool> present = EnumSet.noneOf(AffixPool.class);
                    for (AffixSelection selection : AffixRoller.roll(rank, rng)) {
                        present.add(selection.affix().pool());
                    }
                    for (AffixPool pool : present) {
                        hits[pool.ordinal()]++;
                    }
                }
                for (AffixPool pool : AffixPool.values()) {
                    rates[star - 1][pool.ordinal()] = (double) hits[pool.ordinal()] / PRESENCE_SAMPLES;
                }
            }
            return rates;
        }
    }
}

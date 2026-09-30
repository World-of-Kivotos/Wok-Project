package com.miningdim.job.agent;

import com.miningdim.champion.AffixPool;
import com.miningdim.champion.StarRank;
import net.minecraft.nbt.CompoundTag;

/**
 * 一张具体悬赏的定义 (SpecialAgent_Job_DesignSpec 10.5 + 12.1 第 1 条, 2026-09-30 拍板三种目标全做)。
 *
 * 一张悬赏 = 周期 + 目标类型 + 星级门 (+ 词条类别) + 计数门 + 奖励三元组。实例由 {@link BountyGenerator} 按干员等级
 * 掷出, 奖励数值出自 {@link BountyRewardTable} (运营可经 miningdim-agent.toml 调), 本类只做结构校验与击杀匹配。
 *
 * 三种目标:
 *  - {@link TargetType#KILL_STAR_AT_LEAST}: 讨伐 N 只 ≥X★ 精英;
 *  - {@link TargetType#KILL_WITH_AFFIX_CATEGORY}: 讨伐 N 只 ≥X★ 且带某池 (生存/战斗/机动/技能) 词条的精英。类别直接用
 *    精英体系自己的四池 {@link AffixPool}, 不另造一套分类 —— 玩家在扫描面板里看到的词条就是按这四池生成的;
 *  - {@link TargetType#KILL_WORLD_BOSS}: 讨伐世界 BOSS。世界 BOSS 只由管理员指令召唤、出现时间不定, 放进周常槽会出现
 *    "整周没刷 BOSS, 槽位白占"的死单, 故它不占日常/周常槽, 周期记为 {@link Period#EVENT} (常驻讨伐令, 见
 *    {@link AgentBountyService#onWorldBossKill})。
 *
 * 青辉石只允许周常与世界 BOSS 讨伐令发 (7.2: 青辉石来自周常悬赏; 十一章 2026-09-30 改为与 6★+ 精英掉落双源并存),
 * 日常给青辉石是非法定义, 构造即抛。
 *
 * 不可变值对象, 无世界引用, GameTest 可直测。
 */
public final class BountyDefinition {

    /** 悬赏周期 (10.5: 日常 UTC 翻日重置 / 周常 ISO 周重置; EVENT = 世界 BOSS 讨伐令, 不随周期清空)。 */
    public enum Period {
        DAILY,
        WEEKLY,
        EVENT
    }

    /** 目标类型 (12.1 第 1 条, 三种全做)。 */
    public enum TargetType {
        /** 讨伐 N 只 ≥X★ 精英。 */
        KILL_STAR_AT_LEAST,
        /** 讨伐 N 只 ≥X★ 且带某池词条的精英 (类别 = {@link AffixPool})。 */
        KILL_WITH_AFFIX_CATEGORY,
        /** 讨伐世界 BOSS (L8+; 只在 {@link Period#EVENT} 下出现)。 */
        KILL_WORLD_BOSS
    }

    private static final String K_ID = "id";
    private static final String K_PERIOD = "period";
    private static final String K_TARGET = "target";
    private static final String K_MIN_STAR = "minStar";
    private static final String K_POOL = "pool";
    private static final String K_COUNT = "count";
    private static final String K_CREDIT = "credit";
    private static final String K_XP = "xp";
    private static final String K_AZURE = "azure";

    private final String id;
    private final Period period;
    private final TargetType targetType;
    private final int minStar;
    private final AffixPool targetPool;
    private final int requiredCount;
    private final long creditReward;
    private final long xpReward;
    private final long azureReward;

    /** 不带词条类别的悬赏 (星级类 / 世界 BOSS)。词条类悬赏必须走带 {@code targetPool} 的构造。 */
    public BountyDefinition(String id, Period period, TargetType targetType, int minStar, int requiredCount,
                            long creditReward, long xpReward, long azureReward) {
        this(id, period, targetType, minStar, null, requiredCount, creditReward, xpReward, azureReward);
    }

    /**
     * @param id            悬赏 id (玩家悬赏板内唯一, 接取时按它定位; 非空)
     * @param period        周期
     * @param targetType    目标类型
     * @param minStar       目标最低星级 (1-10)
     * @param targetPool    词条类别 (仅 {@link TargetType#KILL_WITH_AFFIX_CATEGORY} 必填, 其余必须为 null)
     * @param requiredCount 完成所需合格击杀数 (&gt;=1)
     * @param creditReward  完成奖励信用点 raw (&gt;=0; 经 grantDaily 并入全服信用点主闸)
     * @param xpReward      完成奖励原始 XP (&gt;=0; 经职业框架经验软上限)
     * @param azureReward   完成奖励青辉石 (&gt;=0; 日常必须为 0)
     */
    public BountyDefinition(String id, Period period, TargetType targetType, int minStar, AffixPool targetPool,
                            int requiredCount, long creditReward, long xpReward, long azureReward) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("bounty id must not be blank");
        }
        if (period == null || targetType == null) {
            throw new IllegalArgumentException("period/targetType must not be null");
        }
        if (minStar < StarRank.MIN_STAR || minStar > StarRank.MAX_STAR) {
            throw new IllegalArgumentException("minStar out of [1,10]: " + minStar);
        }
        if ((targetType == TargetType.KILL_WITH_AFFIX_CATEGORY) != (targetPool != null)) {
            throw new IllegalArgumentException("targetPool is required for KILL_WITH_AFFIX_CATEGORY and only for it, got "
                    + targetType + " / " + targetPool);
        }
        if ((period == Period.EVENT) != (targetType == TargetType.KILL_WORLD_BOSS)) {
            // 世界 BOSS 出现时间由管理员定, 放进日常/周常槽会变成可能整周完成不了的死单; 反过来 EVENT 周期不随翻日翻周
            // 清空, 只适合"世界 BOSS 每次出现都能再领一次"的讨伐令。两者必须成对。
            throw new IllegalArgumentException("EVENT period and KILL_WORLD_BOSS must go together, got "
                    + period + " / " + targetType);
        }
        if (requiredCount < 1) {
            throw new IllegalArgumentException("requiredCount must be >= 1, got " + requiredCount);
        }
        if (creditReward < 0L || xpReward < 0L || azureReward < 0L) {
            throw new IllegalArgumentException("rewards must be >= 0");
        }
        if (period == Period.DAILY && azureReward > 0L) {
            throw new IllegalArgumentException("daily bounty must not grant azure (azure is weekly/event only)");
        }
        this.id = id;
        this.period = period;
        this.targetType = targetType;
        this.minStar = minStar;
        this.targetPool = targetPool;
        this.requiredCount = requiredCount;
        this.creditReward = creditReward;
        this.xpReward = xpReward;
        this.azureReward = azureReward;
    }

    public String id() {
        return id;
    }

    public Period period() {
        return period;
    }

    public TargetType targetType() {
        return targetType;
    }

    public int minStar() {
        return minStar;
    }

    /** 词条类别; 非词条类悬赏返回 null。 */
    public AffixPool targetPool() {
        return targetPool;
    }

    public int requiredCount() {
        return requiredCount;
    }

    public long creditReward() {
        return creditReward;
    }

    public long xpReward() {
        return xpReward;
    }

    public long azureReward() {
        return azureReward;
    }

    /**
     * 某次击杀是否计入本悬赏: 必须达入池门槛 (10.5: 击杀盖章 + 入池门槛; 封印不计贡献, 封了没打不合格) 且星级
     * 达标, 词条类再要求目标带该池词条, 世界 BOSS 类再要求目标是世界 BOSS。
     */
    public boolean countsToward(BountyKill kill) {
        if (!kill.qualified() || kill.star() < minStar) {
            return false;
        }
        return switch (targetType) {
            case KILL_STAR_AT_LEAST -> true;
            case KILL_WITH_AFFIX_CATEGORY -> kill.pools().contains(targetPool);
            case KILL_WORLD_BOSS -> kill.worldBoss();
        };
    }

    /** 落盘 (随 {@link AgentBountySavedData} 的玩家悬赏板)。 */
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString(K_ID, id);
        tag.putString(K_PERIOD, period.name());
        tag.putString(K_TARGET, targetType.name());
        tag.putInt(K_MIN_STAR, minStar);
        if (targetPool != null) {
            tag.putString(K_POOL, targetPool.name());
        }
        tag.putInt(K_COUNT, requiredCount);
        tag.putLong(K_CREDIT, creditReward);
        tag.putLong(K_XP, xpReward);
        tag.putLong(K_AZURE, azureReward);
        return tag;
    }

    /**
     * 读盘。结构非法 (枚举名改过、数值越界) 时抛 IllegalArgumentException, 由调用方丢弃该条而不是让整份存档读不出来。
     */
    public static BountyDefinition fromTag(CompoundTag tag) {
        AffixPool pool = tag.contains(K_POOL) ? AffixPool.valueOf(tag.getString(K_POOL)) : null;
        return new BountyDefinition(
                tag.getString(K_ID),
                Period.valueOf(tag.getString(K_PERIOD)),
                TargetType.valueOf(tag.getString(K_TARGET)),
                tag.getInt(K_MIN_STAR),
                pool,
                tag.getInt(K_COUNT),
                tag.getLong(K_CREDIT),
                tag.getLong(K_XP),
                tag.getLong(K_AZURE));
    }
}

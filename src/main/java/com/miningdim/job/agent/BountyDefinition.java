package com.miningdim.job.agent;

import com.miningdim.champion.StarRank;

/**
 * 悬赏定义/模板纯逻辑 (SpecialAgent_Job_DesignSpec 10.5 悬赏 + 十二章 PENDING 模板库): 结构化骨架 + config 钩子。
 *
 * 状态 (十二章 PENDING): 每日/周常的"具体任务模板库"(讨伐 N 只 ≥X★ / 讨伐带某类词条精英 / 讨伐世界 BOSS) 及
 * 与星级/词条挂钩规则未拍板, 故本类只给"结构化定义"骨架 (周期 + 目标类型 + 计数门 + 可接星级门 + 奖励三元组),
 * 不臆造完整模板库。首版悬赏实例由集成层/datapack (data/miningdim/agent_bounty/*.json) 据本结构生成,
 * 绝对奖励底值留 miningdim-agent.toml config (十二章: 经济/XP 绝对数值 PENDING)。
 *
 * 不可变值对象, 无世界引用, dev GameTest 触达安全 (断言计数门/星级门/周期/奖励结构)。
 */
public final class BountyDefinition {

    /** 悬赏周期 (10.5: 每日 UTC 翻日重置 / 周常 ISO 周重置)。 */
    public enum Period {
        DAILY,
        WEEKLY
    }

    /** 目标类型 (十二章 PENDING 模板库的结构化枚举骨架, 不含具体模板数值)。 */
    public enum TargetType {
        /** 讨伐 N 只 ≥X★ 精英 (按星级)。 */
        KILL_STAR_AT_LEAST,
        /** 讨伐带某类别词条的精英 (按词条类别; 具体词条挂钩 PENDING)。 */
        KILL_WITH_AFFIX_CATEGORY,
        /** 讨伐世界 BOSS (L8+ 解锁; 周常向)。 */
        KILL_WORLD_BOSS
    }

    private final String id;
    private final Period period;
    private final TargetType targetType;
    private final int minStar;
    private final int requiredCount;
    private final long creditReward;
    private final long xpReward;
    private final long azureReward;

    /**
     * @param id            悬赏定义 id (datapack 键 / lang key 命名空间; 非空)
     * @param period        周期 (日常 / 周常)
     * @param targetType    目标类型
     * @param minStar       目标最低星级 (1-10; KILL_STAR_AT_LEAST/WORLD_BOSS 用; 越界抛)
     * @param requiredCount 完成所需合格击杀数 (>=1; 计数门)
     * @param creditReward  完成奖励信用点 raw (>=0; 经 grantDaily 并入主闸; 绝对底值留 config)
     * @param xpReward      完成奖励原始 XP (>=0; 经 IJobService.grantXp 并入软上限; 绝对底值留 config)
     * @param azureReward   完成奖励青辉石 (>=0; 仅周常 >0, 日常必须 0; PvE 绑定; 绝对底值留 config)
     */
    public BountyDefinition(String id, Period period, TargetType targetType, int minStar, int requiredCount,
                            long creditReward, long xpReward, long azureReward) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("bounty id must not be blank");
        }
        if (period == null || targetType == null) {
            throw new IllegalArgumentException("period/targetType must not be null");
        }
        if (minStar < StarRank.MIN_STAR || minStar > StarRank.MAX_STAR) {
            throw new IllegalArgumentException("minStar out of [1,10]: " + minStar);
        }
        if (requiredCount < 1) {
            throw new IllegalArgumentException("requiredCount must be >= 1, got " + requiredCount);
        }
        if (creditReward < 0L || xpReward < 0L || azureReward < 0L) {
            throw new IllegalArgumentException("rewards must be >= 0");
        }
        // 青辉石仅周常出 (7.2/十一章: 青辉石唯一来源是周常悬赏); 日常给青辉石是非法定义, 异常自然冒泡。
        if (period == Period.DAILY && azureReward > 0L) {
            throw new IllegalArgumentException("daily bounty must not grant azure (azure is weekly-only)");
        }
        this.id = id;
        this.period = period;
        this.targetType = targetType;
        this.minStar = minStar;
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
     * 某次击杀是否计入本悬赏进度: 必须达盖章入池门槛 (qualifiedKill) 且精英星级 >= minStar (10.5: 完成判定
     * 走击杀盖章 + 入池门槛)。词条类别/世界 BOSS 的细化匹配留集成层 (具体模板 PENDING), 本层只做星级 + 盖章门。
     *
     * @param killedStar    被击杀精英的初始星级
     * @param qualifiedKill 该击杀是否达贡献池入池门槛 (集成层经 ContributionPool 判, 封印不计贡献)
     * @return 是否计入本悬赏 +1
     */
    public boolean countsToward(int killedStar, boolean qualifiedKill) {
        return qualifiedKill && killedStar >= minStar;
    }
}

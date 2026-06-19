package com.miningdim.job.agent;

/**
 * 单条悬赏的进度跟踪纯逻辑 (SpecialAgent_Job_DesignSpec 10.5 悬赏完成判定 + UTC 翻日 / ISO 周重置)。
 *
 * 跟踪一条已接悬赏的: 合格击杀计数 + 是否已发奖 (claimed, 防重复发) + 接取时的日戳/周戳 (翻转重置门控)。
 * 纯逻辑 (计数 + dayStamp/weekStamp), 无世界引用; 日戳/周戳由调用方经 {@link AgentClock} 注入 (GameTest 注入
 * 固定戳断言翻转边界), 本类不取实时时钟。
 *
 * 重置门控 (10.5): 日常悬赏跨 UTC epochDay 重置 (计数清零 + 可重新接取); 周常跨 ISO 周戳重置。本类按周期持
 * 对应戳, {@link #rolloverIfStale} 比对当前戳决定是否清空 (同戳不重置, 异戳重置 + 防 claimed 残留)。
 *
 * 完成判定 (10.5): 合格击杀 (达盖章入池门槛, 由 {@link BountyDefinition#countsToward} 前置过滤) 累计到
 * requiredCount 即完成; 完成后 {@link #tryClaim} 发奖一次 (claimed=true), 重复 claim 返回 false (不重复发奖)。
 */
public final class BountyProgress {

    private final BountyDefinition definition;
    private int killCount;
    private boolean claimed;
    /** 接取/上次重置时的周期戳 (DAILY = epochDay; WEEKLY = ISO 周戳); 跨戳触发重置。 */
    private long periodStamp;

    /**
     * @param definition  本悬赏定义
     * @param periodStamp 接取时的周期戳 (DAILY 传 epochDay; WEEKLY 传 ISO 周戳)
     */
    public BountyProgress(BountyDefinition definition, long periodStamp) {
        if (definition == null) {
            throw new IllegalArgumentException("definition must not be null");
        }
        this.definition = definition;
        this.killCount = 0;
        this.claimed = false;
        this.periodStamp = periodStamp;
    }

    public BountyDefinition definition() {
        return definition;
    }

    public int killCount() {
        return killCount;
    }

    public boolean claimed() {
        return claimed;
    }

    public long periodStamp() {
        return periodStamp;
    }

    /**
     * 跨周期戳时重置进度 (10.5: 日常翻日 / 周常翻周清零 + 重置发奖标记)。同戳不动 (同日/同周内累计不丢)。
     *
     * @param currentPeriodStamp 当前周期戳 (DAILY 传当前 epochDay; WEEKLY 传当前 ISO 周戳; 与 definition.period() 同口径)
     * @return 是否发生了重置 (true = 跨周期清零)
     */
    public boolean rolloverIfStale(long currentPeriodStamp) {
        if (currentPeriodStamp == periodStamp) {
            return false;
        }
        this.periodStamp = currentPeriodStamp;
        this.killCount = 0;
        this.claimed = false;
        return true;
    }

    /**
     * 记录一次击杀 (合格性 + 星级匹配由 {@link BountyDefinition#countsToward} 判): 命中则计数 +1。已完成 (达
     * requiredCount) 后不再增计 (防溢出, 完成即封顶)。
     *
     * @param killedStar    被击杀精英初始星级
     * @param qualifiedKill 该击杀是否达入池门槛 (封印不计贡献 -> 封了没打不算合格)
     * @return 本次是否计入 (true = killCount 真实 +1)
     */
    public boolean recordKill(int killedStar, boolean qualifiedKill) {
        if (isComplete()) {
            return false; // 已达标, 不再增计。
        }
        if (!definition.countsToward(killedStar, qualifiedKill)) {
            return false;
        }
        killCount++;
        return true;
    }

    /** 是否已达成完成条件 (合格击杀计数 >= requiredCount)。 */
    public boolean isComplete() {
        return killCount >= definition.requiredCount();
    }

    /**
     * 尝试领取完成奖励 (10.5: 完成发奖一次, 不重复): 已完成且未领过则标记 claimed 返回 true (调用方据此发钱/XP/
     * 青辉石); 未完成或已领过返回 false (不发)。
     *
     * @return 本次是否应发奖 (true = 首次领取, 集成层据此调 grantDaily/grantXp/grant)
     */
    public boolean tryClaim() {
        if (!isComplete() || claimed) {
            return false;
        }
        claimed = true;
        return true;
    }
}

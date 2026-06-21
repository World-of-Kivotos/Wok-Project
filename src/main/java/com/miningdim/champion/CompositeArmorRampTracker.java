package com.miningdim.champion;

/**
 * 复合装甲 ramp 受击计数器纯逻辑 (ChampionStarAffix spec 7.1 复合装甲: ramp 每受击 +上限/5, 3s 无伤重置)。
 *
 * 单冠军一只的有状态计数器 (服务端 tick 串行, 非线程安全): 维护"当前 3s 窗内累计受击次数"+"上次受击 tick"。每次
 * 受击调 {@link #onHit(long)} 推进 —— 若距上次受击 &gt;= 3s (60 tick) 则先把计数清 0 (无伤重置), 再 +1 并夹到
 * {@link ChampionDamageReduction#COMPOSITE_RAMP_STEPS} (满 5 达上限不再叠)。返回的 hitCount 交
 * {@link ChampionDamageReduction#compositeRampRate} 折算成当前 ramp 减伤率进净减伤连乘。
 *
 * 与 {@link com.miningdim.champion.aggregate.RetaliationAggregator} 同范式 (单怪有状态 + 窗口滚动), 不碰世界/实体,
 * GameTest 直接断言。多只冠军各持一个本计数器实例 (由受击 handler 按 UUID 持 Map), 实例间独立。
 */
public final class CompositeArmorRampTracker {

    private long lastHitTick = Long.MIN_VALUE;
    private int hitCount = 0;

    /**
     * 记一次受击, 返回施加无伤重置后的当前受击次数 (∈ [1, COMPOSITE_RAMP_STEPS])。距上次受击 &gt;= 3s 则先清 0
     * 再计本次 (= 1); 否则在原计数上 +1 夹到上限。
     *
     * @param nowTick 当前 gameTime tick (无伤重置窗判定)
     * @return 本次受击后的累计受击次数 (供 compositeRampRate 折算 ramp 率)
     */
    public int onHit(long nowTick) {
        if (lastHitTick == Long.MIN_VALUE || nowTick - lastHitTick >= ChampionDamageReduction.COMPOSITE_RAMP_RESET_TICKS) {
            hitCount = 0; // 3s 无伤重置: ramp 归零重新爬。
        }
        lastHitTick = nowTick;
        if (hitCount < ChampionDamageReduction.COMPOSITE_RAMP_STEPS) {
            hitCount++;
        }
        return hitCount;
    }

    /** 当前累计受击次数 (诊断/测试; 未推进时 0)。 */
    public int hitCount() {
        return hitCount;
    }

    /** 上次受击 tick (诊断/测试; 未受击为 Long.MIN_VALUE)。 */
    public long lastHitTick() {
        return lastHitTick;
    }
}

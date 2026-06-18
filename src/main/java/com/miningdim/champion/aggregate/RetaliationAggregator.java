package com.miningdim.champion.aggregate;

import com.miningdim.champion.ChampionRedlines;

/**
 * per-attacker 反伤秒窗累加器纯逻辑 (ChampionStarAffix spec 红线 2 / 7.4 反击单元 / 9.5)。
 *
 * 反伤一律按"攻击者最大血量的 %"计 (绝不按收到伤害的 % 反弹)。同一 attacker 身上所有反伤源 (反击单元 + 反震
 * + …) 累加后统一封顶: 滚动秒窗 ≤30% attacker maxHP/s, 单次反击窗口累计 ≤40% attacker maxHP (spec 红线 2)。
 *
 * 本类是单 attacker 的有状态累加器 (服务端 tick 串行, 非线程安全): 每次反伤源拟反弹时调
 * {@link #admit(double, long)} 申请额度, 返回"实际可反弹量 (被秒窗/窗口双上限夹断后的值)"。注 100% 反伤比时
 * 申请量被夹到 16HP/s (16=80×0.2 即 attacker 80 血时 20% 例; 通用 = 30% attackerMaxHp/s) 与窗上限。
 * 不碰世界/实体, GameTest 直接断言。
 *
 * 窗口模型: 单次反击窗口 ≤5s (spec 7.4); 秒窗按 20tick 滚动。本累加器以 attackerMaxHp 为基数, 维护当前秒窗
 * 已反弹量 (跨秒重置) + 当前窗口累计已反弹量 (窗口结束重置)。
 */
public final class RetaliationAggregator {

    private static final long TICKS_PER_SECOND = 20L;

    private final double attackerMaxHp;
    private final double perSecondCap;
    private final double perWindowCap;

    private long currentSecondStartTick = Long.MIN_VALUE;
    private double secondAccumulated = 0.0D;

    private long currentWindowStartTick = Long.MIN_VALUE;
    private double windowAccumulated = 0.0D;

    /**
     * @param attackerMaxHp 攻击者 (玩家) 最大有效血量 (必须 &gt;0; 反伤 %的基数)
     */
    public RetaliationAggregator(double attackerMaxHp) {
        if (!(attackerMaxHp > 0.0D) || Double.isNaN(attackerMaxHp)) {
            throw new IllegalArgumentException("attackerMaxHp must be > 0, got " + attackerMaxHp);
        }
        this.attackerMaxHp = attackerMaxHp;
        this.perSecondCap = attackerMaxHp * ChampionRedlines.RETALIATION_PER_SECOND_CAP_PCT;
        this.perWindowCap = attackerMaxHp * ChampionRedlines.RETALIATION_PER_WINDOW_CAP_PCT;
    }

    /** 本攻击者每秒反伤上限 (= 30% attackerMaxHp)。 */
    public double perSecondCap() {
        return perSecondCap;
    }

    /** 本攻击者单窗反伤累计上限 (= 40% attackerMaxHp)。 */
    public double perWindowCap() {
        return perWindowCap;
    }

    /**
     * 申请反弹一笔反伤, 返回经秒窗 (30%/s) + 窗口 (40%/窗) 双上限夹断后的实际可反弹量 (≥0; 0 = 额度耗尽)。
     * 多反伤源对同一 attacker 共享同一累加器即实现"多源累加统一封顶"(spec 红线 2: 非各源独立)。
     *
     * @param requested 本源拟反弹的反伤量 (HP; 已按 attackerMaxHp 折算, 如反击单元 100% 反伤比折算后量; 必须 &gt;=0)
     * @param nowTick   当前 gameTime tick (秒窗/窗口边界判定)
     * @return 实际可反弹量 (被双上限夹断)
     */
    public double admit(double requested, long nowTick) {
        if (requested < 0.0D || Double.isNaN(requested)) {
            throw new IllegalArgumentException("requested retaliation must be >= 0, got " + requested);
        }

        // 秒窗滚动: 跨入新的 20tick 秒窗则重置秒累计。
        if (currentSecondStartTick == Long.MIN_VALUE || nowTick - currentSecondStartTick >= TICKS_PER_SECOND) {
            currentSecondStartTick = nowTick;
            secondAccumulated = 0.0D;
        }
        // 窗口滚动: 跨入新的 5s 反击窗口则重置窗口累计。
        long windowTicks = (long) (ChampionRedlines.RETALIATION_WINDOW_SECONDS * TICKS_PER_SECOND);
        if (currentWindowStartTick == Long.MIN_VALUE || nowTick - currentWindowStartTick >= windowTicks) {
            currentWindowStartTick = nowTick;
            windowAccumulated = 0.0D;
        }

        double secondRoom = perSecondCap - secondAccumulated;
        double windowRoom = perWindowCap - windowAccumulated;
        double allowed = Math.max(0.0D, Math.min(requested, Math.min(secondRoom, windowRoom)));

        secondAccumulated += allowed;
        windowAccumulated += allowed;
        return allowed;
    }

    /** 当前秒窗已反弹量 (诊断/测试用)。 */
    public double secondAccumulated() {
        return secondAccumulated;
    }

    /** 当前窗口已反弹量 (诊断/测试用)。 */
    public double windowAccumulated() {
        return windowAccumulated;
    }
}

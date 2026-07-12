package com.miningdim.champion;

/**
 * 超速移动 OVERDRIVE 力竭窗状态机纯逻辑 (ChampionStarAffix spec 7.3: +移速 25/40/55/70/85% + 强化力竭窗;
 * 红线 = 力竭窗硬减速 ≥50% + 时长 ≥ 加速段 + 明显可辨, MC 风筝反制必须成立; Stage2 批2)。
 *
 * 三相循环 (2026-07-07 用户拍板 4s/5s/3s + 力竭 -50%):
 *   加速 SURGE 80 tick (+品质档移速) -> 力竭 EXHAUST 100 tick (硬减速 -50%, 玩家的拉开/反击窗口)
 *   -> 常态 NORMAL 60 tick (无修饰) -> 循环。
 *
 * 相位由"距锚点 tick 数"确定性推导 (anchor = 冠军获得攻击目标那刻, handler 维护), 本类无状态纯函数:
 * GameTest 直接断言相位边界与各相移速修饰值 (删相位推导/删力竭减速必挂)。战斗门控 (仅有攻击目标时循环 +
 * 失去目标宽限) 的判定也在本类 ({@link #targetGraceExpired}), 施加 modifier / 粒子由
 * {@code ChampionSelfEffectHandler} 负责 (真服验)。
 */
public final class OverdriveCycle {

    /** 加速段时长 (tick): 4s (2026-07-07 用户拍板)。 */
    public static final long SURGE_TICKS = 80L;

    /** 力竭窗时长 (tick): 5s, 满足红线"力竭时长 ≥ 加速段" (5s ≥ 4s)。 */
    public static final long EXHAUST_TICKS = 100L;

    /** 常态段时长 (tick): 3s (循环间歇, 给相位节奏可预期性)。 */
    public static final long NORMAL_TICKS = 60L;

    /** 整循环时长 (tick): 80+100+60 = 240 (12s)。 */
    public static final long CYCLE_TICKS = SURGE_TICKS + EXHAUST_TICKS + NORMAL_TICKS;

    /** 力竭窗硬减速 (MULTIPLY_TOTAL 系数): -50% (红线下限, 绝不定身)。 */
    public static final double EXHAUST_SLOW = -0.50D;

    /**
     * 失去攻击目标的宽限窗 (tick): 10s 内重新索敌则循环不重置 —— 防"脱战 3 秒重进白嫖加速开局",
     * 也防目标短暂走出视线导致相位抖动。超窗后 handler 摘 modifier 归常态并清锚点。
     */
    public static final long TARGET_LOSS_GRACE_TICKS = 200L;

    /** 三相: 加速 (突进追击) / 力竭 (硬减速反击窗) / 常态 (无修饰)。 */
    public enum Phase {
        SURGE,
        EXHAUST,
        NORMAL
    }

    private OverdriveCycle() {
    }

    /**
     * 距锚点 tick 数 -> 当前相位: [0,80) 加速 / [80,180) 力竭 / [180,240) 常态, 模 240 循环。
     *
     * @param ticksSinceAnchor 距锚点 tick 数 (须 &gt;=0; 锚点在未来属调用方 bug, 抛不掩盖)
     * @return 当前相位
     */
    public static Phase phaseAt(long ticksSinceAnchor) {
        if (ticksSinceAnchor < 0L) {
            throw new IllegalArgumentException("ticksSinceAnchor must be >= 0, got " + ticksSinceAnchor);
        }
        long t = ticksSinceAnchor % CYCLE_TICKS;
        if (t < SURGE_TICKS) {
            return Phase.SURGE;
        }
        if (t < SURGE_TICKS + EXHAUST_TICKS) {
            return Phase.EXHAUST;
        }
        return Phase.NORMAL;
    }

    /**
     * 该相位的 MOVEMENT_SPEED MULTIPLY_TOTAL 修饰系数: 加速 = +品质档 (25/40/55/70/85%), 力竭 = {@link #EXHAUST_SLOW},
     * 常态 = 0 (handler 对 0 摘 modifier 不挂)。
     *
     * @param phase   相位
     * @param quality 超速移动品质
     * @return 移速修饰系数
     */
    public static double speedModifier(Phase phase, AffixQuality quality) {
        if (phase == null) {
            throw new IllegalArgumentException("phase must not be null");
        }
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
        return switch (phase) {
            case SURGE -> AffixDef.OVERDRIVE.valueFor(quality);
            case EXHAUST -> EXHAUST_SLOW;
            case NORMAL -> 0.0D;
        };
    }

    /**
     * 失去目标宽限是否已过期 (过期 -> handler 清锚点归常态): 距最后一次见到目标 &gt; 10s。
     * lastTargetSeenTick = {@link Long#MIN_VALUE} (从未有目标) 视为已过期 (无战斗不循环)。
     *
     * @param nowTick            当前 gameTime tick
     * @param lastTargetSeenTick 最后一次持有存活攻击目标的 tick
     * @return 宽限是否过期
     */
    public static boolean targetGraceExpired(long nowTick, long lastTargetSeenTick) {
        if (lastTargetSeenTick == Long.MIN_VALUE) {
            return true;
        }
        return nowTick - lastTargetSeenTick > TARGET_LOSS_GRACE_TICKS;
    }
}

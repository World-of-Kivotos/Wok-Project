package com.miningdim.champion;

import java.util.ArrayList;
import java.util.List;

/**
 * 精英怪【机动词条·闪光 BLINK】(Stage2 批4 波1; ChampionStarAffix spec 7.2 抵近型反风筝瞬移) 的周期折算 +
 * 缰绳/门控判定 + 落点候选环几何 纯逻辑。把 {@link AffixDef#BLINK} 品质档折算成施放周期 (tick), 给出"有存活攻击
 * 目标且在缰绳内才推进"的门控真值, 并按【玩家背后优先】的角度序列在距玩家 2-3 格环上生成候选落点 (x,z) 坐标。
 *
 * 纯函数集合, 不碰世界/实体/Champions/net.minecraft, GameTest 直接断言 (删被测折算/门控/几何必挂)。分工: 本类只算
 * "周期 tick + 缰绳/门控 boolean + 环上候选 (x,z) + 禁近判定"; 实际逐候选过 {@code KnockbackSafetyGuard.evaluateLanding}
 * 选点、预兆粒子、mob.teleportTo 由 integration 层 {@code ChampionBlinkHandler} 施加 (真服验)。
 *
 * 周期语义 (spec 7.2 抵近瞬移): 循环计时只在【冠军有存活攻击目标且距目标 &lt;= 缰绳】的扫描 tick 推进 (丢目标/超
 * 缰绳冻结不耗周期, 用户裁定缰绳 24 格), 故用"已累加循环 tick"模型 —— handler 每个门控通过的扫描调
 * {@link #advanceCycle} 步进一个扫描粒度, {@link #cycleReady} 判到点; 到点由 handler 清零重计 (全候选被拒亦清零,
 * 周期照走不补偿, 防岩浆房每 tick 重试)。抵近型的意义 = 优先瞬到玩家逃跑方向 (背后) 截断风筝。
 */
public final class ChampionBlinkPlan {

    private ChampionBlinkPlan() {
    }

    /** tick/秒 (周期秒表 → tick 折算基)。 */
    public static final long TICKS_PER_SECOND = 20L;

    /**
     * 扫描/周期步进粒度 (tick): handler 每 1s 扫一次近玩家冠军, 门控通过的扫描把循环推进一个此粒度 (与
     * {@code ChampionBlinkHandler} 的 ServerTick 节流对齐)。
     */
    public static final long SCAN_INTERVAL_TICKS = 20L;

    /** 缰绳距离 (格; 用户裁定 波1): 冠军距攻击目标玩家 &lt;= 此值才推进周期, 超出冻结不耗周期。 */
    public static final double LEASH_RANGE = 24.0D;

    /** 落点候选环半径 (格): spec"玩家旁 2-3 格"取中值, 全部角度候选皆在此半径 (恒落 [2,3] 区间)。 */
    public static final double RING_RADIUS = 2.5D;

    /** 禁近下限 (格): 落点距玩家水平距离 &lt; 此值一律拒 (spec"禁落玩家 1 格内"); 防抵近瞬移贴脸卡进玩家碰撞箱。 */
    public static final double MIN_LANDING_DISTANCE = 1.0D;

    /**
     * 候选角度偏移序列 (度; 相对 handler 传入的 baseAngle = 指向玩家背后的水平角): 背后 (0) 优先, 再向两侧对称扇形
     * 展开到身前 (±160)。抵近反风筝的意义 = 优先瞬到玩家逃跑方向 (背后) 截断风筝, 背后被 hazard 挡则退取侧后/侧翼。
     */
    private static final double[] CANDIDATE_ANGLE_OFFSETS_DEG =
            {0.0, 40.0, -40.0, 80.0, -80.0, 120.0, -120.0, 160.0};

    /**
     * 该品质施放周期 (tick) = {@link AffixDef#BLINK} 品质档秒 × 20 (9/8/7/5.5/4 s = 180/160/140/110/80 tick)。
     * 注: 超凡 (EPIC) 档 110 tick 非 20 的整数倍, handler 按 20-tick 扫描粒度步进时实际在 120 tick (6 扫描步) 到点 ——
     * 与 {@link ChampionVisualDisruptionValues} 的 210-tick 档同源舍入, 属可接受的扫描粒度取整 (周期语义以本表为准)。
     *
     * @param quality 闪光品质
     * @return 周期 tick (&gt;0)
     */
    public static long cycleTicks(AffixQuality quality) {
        requireQuality(quality);
        // Math.round 防浮点表示误差 (5.5 在 double 精确, 折算仍显式取整保长整值稳定)。
        return Math.round(AffixDef.BLINK.valueFor(quality) * TICKS_PER_SECOND);
    }

    /**
     * 推进循环一个扫描粒度 (handler 仅在门控通过的扫描 tick 调用; 不通过不调 = 不推进 = 冻结不耗周期)。
     *
     * @param elapsedTicks 已累加循环 tick (须 &gt;=0)
     * @return 推进后的累加 tick
     */
    public static long advanceCycle(long elapsedTicks) {
        requireNonNegative(elapsedTicks);
        return elapsedTicks + SCAN_INTERVAL_TICKS;
    }

    /**
     * 循环是否到点 (已累加 ≥ 该品质周期): 到点则 handler 起一次预兆并把累加清零重计。
     *
     * @param elapsedTicks 已累加循环 tick (须 &gt;=0)
     * @param quality      闪光品质
     * @return 是否到点
     */
    public static boolean cycleReady(long elapsedTicks, AffixQuality quality) {
        requireNonNegative(elapsedTicks);
        requireQuality(quality);
        return elapsedTicks >= cycleTicks(quality);
    }

    /** 缰绳判定: 冠军距攻击目标 &lt;= {@link #LEASH_RANGE} (含边界) 即在缰绳内。 */
    public static boolean withinLeash(double distanceToTarget) {
        return distanceToTarget <= LEASH_RANGE;
    }

    /**
     * 周期推进门控真值: 有存活攻击目标玩家 且 在缰绳内 才推进循环计时; 任一不满足则冻结不耗周期。
     *
     * @param hasLivingTarget  冠军是否有存活的攻击目标玩家 (handler 侧世界查询)
     * @param distanceToTarget 冠军到该目标的距离 (格; hasLivingTarget=false 时本参数不参与判定)
     * @return 是否应推进本次周期计时
     */
    public static boolean shouldAdvanceCycle(boolean hasLivingTarget, double distanceToTarget) {
        return hasLivingTarget && withinLeash(distanceToTarget);
    }

    /**
     * 生成距玩家 {@link #RING_RADIUS} 格环上的候选落点 (x,z) 序列 (背后优先扇形; 见 {@link #CANDIDATE_ANGLE_OFFSETS_DEG})。
     * 纯几何 (不含世界查询/Y): handler 传入玩家水平坐标 + baseAngle (指向玩家背后的水平角), 本法据偏移序列在环上取点;
     * handler 再逐点补 Y + 过 {@code KnockbackSafetyGuard.evaluateLanding} 选首个安全点 (全拒则本周期放弃)。
     *
     * @param targetX   玩家 X (世界坐标)
     * @param targetZ   玩家 Z (世界坐标)
     * @param baseAngle 玩家背后方向的水平角 (弧度; 候选 = 玩家 + RING_RADIUS·(cos(baseAngle+off), sin(baseAngle+off)))
     * @return 候选 (x,z) 列表 (背后优先; 长度 = 偏移序列长度)
     */
    public static List<double[]> ringCandidates(double targetX, double targetZ, double baseAngle) {
        List<double[]> candidates = new ArrayList<>(CANDIDATE_ANGLE_OFFSETS_DEG.length);
        for (double offDeg : CANDIDATE_ANGLE_OFFSETS_DEG) {
            double angle = baseAngle + Math.toRadians(offDeg);
            double x = targetX + RING_RADIUS * Math.cos(angle);
            double z = targetZ + RING_RADIUS * Math.sin(angle);
            candidates.add(new double[]{x, z});
        }
        return candidates;
    }

    /**
     * 禁近判定 (spec"禁落玩家 1 格内"): 候选落点距玩家水平距离 &lt; {@link #MIN_LANDING_DISTANCE} 一律拒。环候选恒在
     * {@link #RING_RADIUS} 格故常态不触发, 属抵近瞬移贴脸的防御性硬闸 (handler 选点前逐候选自查)。
     *
     * @return 是否过近 (应拒)
     */
    public static boolean tooClose(double targetX, double targetZ, double candidateX, double candidateZ) {
        double dx = candidateX - targetX;
        double dz = candidateZ - targetZ;
        return Math.sqrt(dx * dx + dz * dz) < MIN_LANDING_DISTANCE;
    }

    private static void requireQuality(AffixQuality quality) {
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
    }

    private static void requireNonNegative(long elapsedTicks) {
        if (elapsedTicks < 0L) {
            throw new IllegalArgumentException("elapsedTicks must be >= 0, got " + elapsedTicks);
        }
    }
}

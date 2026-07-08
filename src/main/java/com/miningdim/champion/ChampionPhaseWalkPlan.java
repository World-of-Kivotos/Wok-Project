package com.miningdim.champion;

import java.util.ArrayList;
import java.util.List;

/**
 * 精英怪【机动词条·灵体移动 PHASE_WALK】(Stage2 批4 波3 压轴; ChampionStarAffix spec 7.3 穿墙型) 的纯逻辑核 ——
 * 施放周期折算 / 穿墙时长折算 / 驱动步进向量数学 / 实体化保底回退链决策 / 缰绳与到达边界。灵体移动是传送家族里
 * 唯一【连续过程】的位移: 冠军进入灵体态后逐 tick 直线漂移穿墙抵近目标, 出态时按回退链在一个安全格实体化。
 *
 * <p>分工 (与 {@link ChampionBlinkPlan}/{@link ChampionTacticalBlinkPlan} 同纪律): 本类只算"周期 tick + 穿墙时长
 * tick + 每 tick 漂移落点坐标 + 回退链枚举决策 + 缰绳/到达 boolean", 不碰世界/实体/net.minecraft; 逐 tick 手动驱动
 * (setPos/noPhysics)、落点安全裁决 (KnockbackSafetyGuard)、环搜世界查询、粒子/音效由集成层
 * {@code ChampionPhaseWalkHandler} 施加 (真服验)。GameTest 直接断言 (删被测折算/几何/回退序位必挂)。
 *
 * <p>回退链 (spec 9.4 定死, 强制按序; 禁塞墙/悬空孤儿, 不得穿出缰绳): 出态实体化落点按优先级取
 * <ol>
 *   <li>当前位若安全且容身 -&gt; 就地 ({@link FallbackOutcome#IN_PLACE});</li>
 *   <li>否则玩家可达且缰绳内最近合法空地 (环搜有解) -&gt; 环搜落点 ({@link FallbackOutcome#RING});</li>
 *   <li>否则入灵体态前记录的 lastValidPos 可容身 -&gt; 回记录位 ({@link FallbackOutcome#LAST_VALID});</li>
 *   <li>全不满足 -&gt; 强制脱离 + 2s 眩晕行动窗口 ({@link FallbackOutcome#FORCED})。</li>
 * </ol>
 * "环搜有解 / 当前可容身 / lastValid 可容身"三个 boolean 的世界查询在 handler 侧算 (逐候选过守卫 + noCollision),
 * 本类只据这三个 boolean 做严格按序的枚举裁决 —— 决策序位是纯逻辑, 可被 GameTest 8 组合真值表钉死 (删任一序位必挂),
 * 而世界查询留在 handler 保持"世界读方式"与"决策序位"解耦。
 */
public final class ChampionPhaseWalkPlan {

    private ChampionPhaseWalkPlan() {
    }

    /** tick/秒 (穿墙时长秒表 → tick 折算基)。 */
    public static final long TICKS_PER_SECOND = 20L;

    /**
     * 扫描/周期步进粒度 (tick): handler 每 1s 扫一次近玩家冠军, 门控通过 (有存活目标且在缰绳内) 的扫描把循环推进
     * 一个此粒度 (与 {@code ChampionPhaseWalkHandler} 的 ServerTick 节流对齐; 与闪光/战术传送同 1s 节奏)。
     */
    public static final long SCAN_INTERVAL_TICKS = 20L;

    /**
     * 施放周期表 tick (品质 普通/中级/高级/超凡/闪耀 = 15/13/11.5/9.5/8 s = 300/260/230/190/160 tick; 主线拍板)。
     * 索引 = {@link AffixQuality#valueIndex()}。高品质更频繁进入灵体态。注: 周期是"施放频率", 与穿墙【时长】
     * ({@link #phaseDurationTicks}, 取 {@link AffixDef#PHASE_WALK} 的数值表) 是两个独立维度, 勿混。11.5/9.5 s
     * 恰为 20 的整数倍 tick (230/190), 无舍入。
     */
    private static final long[] CYCLE_PERIOD_TICKS = {300L, 260L, 230L, 190L, 160L};

    /** 缰绳距离 (格; 主线拍板 24): 目标距冠军 &lt;= 此值才推进周期; 灵体态漂移点距目标将超此值即强制出态 (不得穿出缰绳)。 */
    public static final double LEASH_RANGE = 24.0D;

    /** 到达阈值 (格): 灵体态漂移到距目标 &lt;= 此值即出态实体化 (已抵近, 无需继续穿墙)。 */
    public static final double ARRIVAL_DISTANCE = 1.5D;

    /** 驱动步进 (格/tick; 主线拍板 0.25): 灵体态每 tick 沿"当前位 → 目标眼位"单位向量前进此距离, 每 tick 重算方向追踪目标。 */
    public static final double DRIVE_STEP = 0.25D;

    /**
     * 环搜落点距目标最小距离 (格): 环搜落点距目标水平距离须 &gt;= 此值 (spec 9.4 "最近合法空地"仍须与目标留出身位,
     * 防实体化贴脸卡进玩家碰撞箱)。与 {@link #ARRIVAL_DISTANCE} 同值 —— 抵近到 1.5 即出态, 环搜落点亦不该更近。
     */
    public static final double MIN_LANDING_DISTANCE = 1.5D;

    /**
     * 环搜半径表 (格; 由近及远, spec 9.4 "半径 1.5..6 环搜"): 每环 {@value #RING_ANGLE_COUNT} 个角度, 逐环由近及远
     * 找首个安全落点。最近环 = {@link #MIN_LANDING_DISTANCE} 保证所有候选天然满足最小落点距离。
     */
    private static final double[] RING_RADII = {1.5D, 2.5D, 3.5D, 4.5D, 5.5D, 6.0D};

    /** 每环角度数 (spec 9.4 "每环 8 角度"): 均分 360 度 (每 45 度一个候选)。 */
    private static final int RING_ANGLE_COUNT = 8;

    /** 零距退化阈值: 漂移起终点几乎重合时无方向可算, 步进退化为原地不动 (防 0/0 = NaN)。 */
    private static final double ZERO_DISTANCE_EPSILON = 1.0e-9D;

    /**
     * 实体化保底回退链的裁决结果 (spec 9.4 强制按序)。语义见类注释。
     */
    public enum FallbackOutcome {
        /** 就地实体化: 当前位安全且容身。 */
        IN_PLACE,
        /** 环搜落点实体化: 当前位不可, 但玩家周围缰绳内环搜到合法空地。 */
        RING,
        /** 回退到入态前记录位: 当前/环搜均不可, lastValidPos 可容身。 */
        LAST_VALID,
        /** 强制脱离: 全不满足, 强塞回 lastValidPos + 2s 眩晕行动窗口 (带高亮预兆)。 */
        FORCED
    }

    /**
     * 该品质施放周期 (tick; 见 {@link #CYCLE_PERIOD_TICKS} 表: 300/260/230/190/160)。
     *
     * @param quality 灵体移动品质
     * @return 周期 tick (&gt;0)
     */
    public static long cyclePeriodTicks(AffixQuality quality) {
        requireQuality(quality);
        return CYCLE_PERIOD_TICKS[quality.valueIndex()];
    }

    /**
     * 该品质穿墙时长 (tick) = {@link AffixDef#PHASE_WALK} 数值表 (穿墙秒 2/2.5/3/3.5/4) × {@value #TICKS_PER_SECOND}
     * = 40/50/60/70/80 tick。灵体态最多持续此时长 (未先因抵近/超缰绳/目标消失而提前出态)。
     *
     * @param quality 灵体移动品质
     * @return 穿墙时长 tick (&gt;0)
     */
    public static long phaseDurationTicks(AffixQuality quality) {
        requireQuality(quality);
        // Math.round 防浮点表示误差 (2.5/3.5 在 double 精确, 折算仍显式取整保长整值稳定)。
        return Math.round(AffixDef.PHASE_WALK.valueFor(quality) * TICKS_PER_SECOND);
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
     * 循环是否到点 (已累加 ≥ 该品质周期): 到点则 handler 进入灵体态并把累加清零重计 (周期照走不补偿)。
     *
     * @param elapsedTicks 已累加循环 tick (须 &gt;=0)
     * @param quality      灵体移动品质
     * @return 是否到点
     */
    public static boolean cycleReady(long elapsedTicks, AffixQuality quality) {
        requireNonNegative(elapsedTicks);
        requireQuality(quality);
        return elapsedTicks >= cyclePeriodTicks(quality);
    }

    /** 缰绳判定: 距离 &lt;= {@link #LEASH_RANGE} (含边界) 即在缰绳内。 */
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

    /** 到达判定: 灵体态漂移到距目标 &lt;= {@link #ARRIVAL_DISTANCE} 即应出态 (已抵近)。 */
    public static boolean reachedTarget(double distanceToTarget) {
        return distanceToTarget <= ARRIVAL_DISTANCE;
    }

    /**
     * 最小落点距离判定: 环搜候选距目标 &gt;= {@link #MIN_LANDING_DISTANCE} 才合法 (身位下限, 防实体化贴脸)。
     * 容差 1e-6: {@link #ringCandidates} 恰在最近环 (=最小距离) 上构造候选, 三角函数+平移的浮点尾差 (~1e-14 量级)
     * 会让边界候选被严格比较随机拒掉 (真服首验前测试即抓到 1.4999999999999971) —— 容差远小于玩法尺度、远大于尾差。
     */
    public static boolean meetsMinLandingDistance(double distanceToTarget) {
        return distanceToTarget >= MIN_LANDING_DISTANCE - 1.0e-6D;
    }

    /**
     * 灵体态单 tick 漂移落点 (纯几何): 沿"起点 → 终点"三维单位向量前进 {@link #DRIVE_STEP} 格, 返回下一 tick 落点
     * 坐标 {x,y,z}。终点取目标【眼位】(handler 每 tick 重算追踪目标)。零距退化: 起终点几乎重合时无方向可算, 原样返回
     * 起点 (防 0/0 = NaN 把冠军漂到 NaN 坐标)。本步进【不】夹到终点 —— 到达由 {@link #reachedTarget} 在 1.5 格先行
     * 拦截出态, 步进 0.25 &lt;&lt; 1.5 故不会越过目标。
     *
     * @param fromX 起点 X (冠军当前脚位)
     * @param fromY 起点 Y
     * @param fromZ 起点 Z
     * @param toX   终点 X (目标眼位)
     * @param toY   终点 Y
     * @param toZ   终点 Z
     * @return 下一 tick 落点 {x,y,z}
     */
    public static double[] driveStep(double fromX, double fromY, double fromZ,
                                     double toX, double toY, double toZ) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < ZERO_DISTANCE_EPSILON) {
            return new double[]{fromX, fromY, fromZ}; // 零距退化: 原地 (防 NaN)。
        }
        double scale = DRIVE_STEP / dist;
        return new double[]{fromX + dx * scale, fromY + dy * scale, fromZ + dz * scale};
    }

    /**
     * 环搜落点候选 (纯几何, 由近及远): 以目标水平坐标为心, 逐环 ({@link #RING_RADII} 由近及远) 每环
     * {@value #RING_ANGLE_COUNT} 个均分角度取候选 (x,z) 坐标。handler 逐候选补 Y (目标脚 Y) + 过
     * {@code KnockbackSafetyGuard.evaluateLanding} + noCollision + {@link #meetsMinLandingDistance}, 取首个通过者。
     * 由近及远的意义 = 实体化尽量贴近目标 (穿墙抵近的目的), 近处被墙/危险挡住再向外找。所有候选距心恒 &gt;= 最近环
     * 半径 (= {@link #MIN_LANDING_DISTANCE}), 天然满足最小落点距离。
     *
     * @param centerX 目标 X (环心, 世界坐标)
     * @param centerZ 目标 Z (环心, 世界坐标)
     * @return 候选 (x,z) 列表 (由近及远; 长度 = 环数 × 每环角度数)
     */
    public static List<double[]> ringCandidates(double centerX, double centerZ) {
        List<double[]> candidates = new ArrayList<>(RING_RADII.length * RING_ANGLE_COUNT);
        for (double radius : RING_RADII) {
            for (int i = 0; i < RING_ANGLE_COUNT; i++) {
                double angle = 2.0D * Math.PI * i / RING_ANGLE_COUNT;
                double x = centerX + radius * Math.cos(angle);
                double z = centerZ + radius * Math.sin(angle);
                candidates.add(new double[]{x, z});
            }
        }
        return candidates;
    }

    /**
     * 实体化保底回退链决策 (spec 9.4 强制按序; 纯裁决, 三个 boolean 的世界查询由 handler 侧算)。严格优先级:
     * 当前可容身 → {@link FallbackOutcome#IN_PLACE}; 否则环搜有解 → {@link FallbackOutcome#RING}; 否则 lastValid
     * 可容身 → {@link FallbackOutcome#LAST_VALID}; 全否 → {@link FallbackOutcome#FORCED}。序位不可交换 —— 删任一
     * 序位或改判定顺序, 8 组合真值表必有行翻转 (GameTest 钉死)。
     *
     * @param currentContainable  当前位安全且容身 (handler: noCollision + evaluateLanding SAFE)
     * @param ringHasSolution     环搜到合法空地 (handler: 环候选逐个过守卫 + noCollision + 最小距离, 有解)
     * @param lastValidContainable 入态前记录位 lastValidPos 可容身 (handler: noCollision)
     * @return 回退链裁决
     */
    public static FallbackOutcome resolveFallback(boolean currentContainable,
                                                  boolean ringHasSolution,
                                                  boolean lastValidContainable) {
        if (currentContainable) {
            return FallbackOutcome.IN_PLACE;
        }
        if (ringHasSolution) {
            return FallbackOutcome.RING;
        }
        if (lastValidContainable) {
            return FallbackOutcome.LAST_VALID;
        }
        return FallbackOutcome.FORCED;
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
